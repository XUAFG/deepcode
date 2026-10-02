package com.dsharnessmobile.shell

import android.content.Context
import org.json.JSONObject

/**
 * 「AI root 权限」授权门（issue #262 方案 A：策略门 + 免责确认门 + 通道身份探测）。
 *
 * ── 本开关**不授予任何能力**（诚实性要求，issue 原文）────────────────────────────
 * root 能力来自真实 root Shizuku 或显式授权的 su，不是本开关给的；两条路径是替代关系。
 * 本开关真实的作用只有两件：
 *  1. **策略门**：通道身份为 root 且未授权时，特权执行面整体拒绝（见 [ShizukuTransport]
 *     的 rootGateRefusal）——不是按 op 分类放行（uid 0 下 `shExec` 是任意 shell，
 *     op 白名单挡不住引号逃逸，分类隔离是假的，故整体 fail-closed）。
 *  2. **知情同意与免责**：开启前必须勾选「已阅读」并打开过免责声明；同意与当前
 *     versionCode 绑定，升级后需重新确认。
 *
 * ── 通道身份 ≠ 设备是否 root（探测判据，issue 原文）────────────────────────────
 * 已 root 但 Shizuku 以 ADB（uid 2000）启动的设备，通道只有 shell 权限——此时本开关
 * 若也没有显式授权 su，开关才置灰并给通道/管理器引导；不能据 uid2000 猜设备未 root。
 *
 * ── 纯逻辑与持久化分离（与 [ShizukuBindState] 同纪律）──────────────────────────
 * 决策核心 [decision] 是纯函数（JVM 可测，不需要 Robolectric）：
 *  - 未勾选/同意过期 → 不许开启（`consent-required`）；
 *  - 通道身份非 root → 不许开启（`not-root-channel`，界面对应置灰+红字）；
 *  - 取消勾选「已阅读」→ 撤销同意并**同时关闭开关**（不留「已授权但未同意」的矛盾态）。
 * 持久化是薄壳：`granted` 与 `consentVersionCode` 存私有 SharedPreferences；
 * 同意与 versionCode 绑定 ⇒ 升级后 consentValid() 自然为 false，无需迁移逻辑。
 */
object RootGrant {

  /** 拒绝码：通道身份不是 root（uid≠0），开关应当置灰。 */
  const val CODE_NOT_ROOT_CHANNEL = "not-root-channel"

  /** 拒绝码：免责确认（勾选「已阅读」）缺失或已随版本升级过期。 */
  const val CODE_CONSENT_REQUIRED = "consent-required"

  /**
   * 拒绝码：应用自身尚未从 Root 管理器获得 root 授权（2026-09-30 主人定例）。
   * 应用 su 未授权时的拒绝词；检测只由用户显式请求，不保证管理器自动弹窗。
   */
  const val CODE_ROOT_NOT_GRANTED = "root-not-granted"

  /** 通道 uid 的 root 判据（与 [ShizukuProbe]/[ShizukuTransport.status] 的 identity 同源）。 */
  const val ROOT_UID = 0

  private const val PREFS = "dsh_root_grant"
  private const val KEY_GRANTED = "granted"
  private const val KEY_CONSENT_VC = "consentVersionCode"

  /**
   * 开关决策（纯函数）。
   *
   * @param channelUid Shizuku 通道身份（服务端 uid；读不到时传 -1，按非 root 处理——fail-closed）。
   * @param granted 当前开关位。
   * @param consentVersionCode 已记录的「已阅读」同意所绑定的 versionCode；0 = 从未同意。
   * @param currentVersionCode 当前构建的 versionCode。
   * @param wantOn 用户想要开启还是关闭。
   * @return `null` = 允许按 [wantOn] 变更；非空 = 结构化拒绝（code 见上方常量）。
   */
   fun decision(
    channelUid: Int,
    rootGranted: Boolean,
    granted: Boolean,
    consentVersionCode: Int,
    currentVersionCode: Int,
    wantOn: Boolean,
  ): JSONObject? {
    if (!wantOn) return null // 关闭永远允许（撤销不是需要资格的动作）
    if (channelUid != ROOT_UID && !rootGranted) {
      return JSONObject().put("ok", false).put("code", CODE_NOT_ROOT_CHANNEL)
        .put("guidance", "无法在未 root 的设备上赋予该权限")
    }
    if (consentVersionCode <= 0 || consentVersionCode != currentVersionCode) {
      return JSONObject().put("ok", false).put("code", CODE_CONSENT_REQUIRED)
        .put("guidance", "先勾选「已阅读」并查看免责声明，才能开启 AI root 权限（升级后需重新确认）。")
    }
    // Shizuku-root and explicitly granted su are independent root transports.
    // Neither route bypasses the effective current-version AI consent at dispatch.
    return null
  }

  /** 「已阅读」同意是否对当前构建有效（与 versionCode 绑定；**0 不算同意**——fail-closed）。 */
  fun consentValid(context: Context): Boolean {
    val consent = prefs(context).getInt(KEY_CONSENT_VC, 0)
    return consent > 0 && consent == BuildConfig.VERSION_CODE
  }

  /** 当前开关位（默认 false——fail-closed：装完/升级后 root 通道保持关闭）。 */
  internal fun effectiveGrant(granted: Boolean, consentVersionCode: Int, currentVersionCode: Int): Boolean =
    granted && consentVersionCode > 0 && consentVersionCode == currentVersionCode

  fun isGranted(context: Context): Boolean = effectiveGrant(
    prefs(context).getBoolean(KEY_GRANTED, false),
    prefs(context).getInt(KEY_CONSENT_VC, 0),
    BuildConfig.VERSION_CODE,
  )

  /**
   * 勾选/取消「已阅读」。
   *
   * 勾选 = 记录同意并绑定当前 versionCode；**取消勾选即撤销同意并同时关闭开关**
   * （issue 用户指定语义，不留矛盾态）。返回写后读回的状态 JSON。
   *
   * 回包必须带 `ok:true`：页面结算（settleLinkCall）只认 `ok === true`，缺这个字段会把
   * 已成功的写入渲染成失败（2026-09-30 用户实测「撤销同意失败」——状态其实已生效）。
   */
  fun setConsent(context: Context, on: Boolean): JSONObject {
    val app = context.applicationContext
    if (on) {
      // Reconfirming an expired consent must not silently reactivate the persisted old switch.
      val keepGranted = isGranted(app)
      prefs(app).edit().putBoolean(KEY_GRANTED, keepGranted)
        .putInt(KEY_CONSENT_VC, BuildConfig.VERSION_CODE).apply()
    } else {
      // 撤销同意必须连带关开关：只清同意而留着 granted=true 会让「未同意但已授权」
      // 成为可达状态，而那个状态正是免责门要杜绝的。
      prefs(app).edit().putInt(KEY_CONSENT_VC, 0).putBoolean(KEY_GRANTED, false).apply()
    }
    return state(app, channelUidNow(app)).put("ok", true)
  }

  /**
   * 开/关开关。[decision] 判据全部满足才写入；关闭无需资格。
   * 返回写后读回的状态 JSON（拒绝时带 code/guidance，界面据此说话）。
   */
  fun setGranted(context: Context, on: Boolean): JSONObject {
    val app = context.applicationContext
    val uid = channelUidNow(app)
    val refusal = decision(
      channelUid = uid,
      rootGranted = RootAccess.isGranted(app),
      granted = isGranted(app),
      consentVersionCode = prefs(app).getInt(KEY_CONSENT_VC, 0),
      currentVersionCode = BuildConfig.VERSION_CODE,
      wantOn = on,
    )
    if (refusal == null) {
      prefs(app).edit().putBoolean(KEY_GRANTED, on).apply()
    }
    // 拒绝回包同时带 `reason`（= code）：页面结算的人话翻译只读 reason（user-copy.ts 的
    // CALL_REASON 唯一真源），code 留给 data-code/grep。两个都不带会落「未在本版登记」兜底。
    return state(app, uid).let {
      if (refusal != null) {
        it.put("ok", false).put("code", refusal.optString("code"))
          .put("reason", refusal.optString("code"))
          .put("guidance", refusal.optString("guidance"))
      } else {
        it.put("ok", true)
      }
    }
  }

  /**
   * 设置页读面：授权位 + 同意有效性 + 通道身份三件事一次带出。
   *
   * `channelRoot=false` 不排除显式 su 授权；两条路径都不可用才禁止开启。
   * 通道 uid 读不到按 -1，不猜 root；已开启的开关仍允许关闭，consent 始终可撤销。
   */
  fun state(context: Context, channelUid: Int): JSONObject {
    val consent = prefs(context).getInt(KEY_CONSENT_VC, 0)
    val valid = consent > 0 && consent == BuildConfig.VERSION_CODE
    return JSONObject()
      .put("granted", isGranted(context))
      .put("consentValid", valid)
      .put("consentVersionCode", consent)
      .put("currentVersionCode", BuildConfig.VERSION_CODE)
      .put("channelUid", channelUid)
      .put("channelRoot", channelUid == ROOT_UID)
      .put("canToggle", channelUid == ROOT_UID || RootAccess.isGranted(context))
      // 应用级 root 授权（Root 管理器）——开关放行的第三道门，UI 单独一行展示进度与引导。
      .put("rootGranted", RootAccess.isGranted(context))
      .put("rootState", RootAccess.state(context).optString("state"))
      .put("ownership", RootOwnershipJobs.state(context))
      // 诚实性说明（issue 要求：做不到技术隔离时不得把不成立的隔离写成事实）：
      .put(
        "honesty",
        "本开关是策略门与知情同意门，不是技术沙箱：通道以 root 运行时，任意 shell 命令在该通道下本就无限制。",
      )
  }

  /** 当前 Shizuku 通道的服务端 uid（读不到 = -1，不阻塞、不抛出）。 */
  internal fun channelUidNow(context: Context): Int =
    runCatching { rikka.shizuku.Shizuku.getUid() }.getOrDefault(-1)

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
