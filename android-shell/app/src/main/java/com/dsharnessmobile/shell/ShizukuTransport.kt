package com.dsharnessmobile.shell

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App-side lifecycle owner for the Shizuku UserService.
 *
 * The manager application remains the only authority that can grant Shizuku permission. This class
 * may request its standard confirmation from an explicit user action but never treats the request
 * as a grant. WebView/engine code gets no raw binder and no arbitrary shell surface.
 */
object ShizukuTransport {
  private const val TAG = "dsh-shizuku"
  private const val REQUEST_CODE_VDISPLAY = 0xD514
  private const val USER_SERVICE_TAG = "dsh-mobile-vdisplay-v1"
  /** 单次 latch 等待片（保持既有 4s 语义：到点先复用/汇报，不无限阻塞调用方）。 */
  private const val BIND_TIMEOUT_MS = 4_000L
  /**
   * 首次绑定允许等待的**总预算**（0.14.0 设备实锤修正）。
   *
   * 缺陷形态：`ensureBound()` 只 await 一片 4s 就返回，而 `Shizuku.bindUserService` 在真机/模拟器上
   * 实测要 5s 量级才回调 `onServiceConnected`。于是**同一条命令第一次必然报「通道失败」、第二次必然成功**——
   * 那不是「Shizuku 抖动」，是状态机时序的确定结果。设备会话实录里 agent 因此判定特权通道不可靠、
   * 转投 Termux 通道，又撞上该通道的环境缺陷，两个缺陷串联把整条链路打崩。
   *
   * 现在在预算内循环等待：绑定完成即返回，超预算才如实汇报「正在建立」并给出可重试建议。
   */
  private const val BIND_TOTAL_MS = 15_000L

  /**
   * 绑定看门狗阈值（0.14.1 设备实锤，Redmi K70E）：一次绑定超过本阈值仍**既无回调也无同步异常**，
   * 就判定这次尝试已死并复位——否则 `binding` 永久为 true，`kickBind` 从此不再发起任何尝试，
   * UI 永久停在「正在建立」（本缺陷的形态）。详见 [ShizukuBindState] 的类注释。
   *
   * 取值依据：真机/模拟器实测回调耗时 ~5s（[BIND_TOTAL_MS] 的注释已录），阈值取 4 倍余量；
   * 且**大于**单次调用的总预算（15s），使第一次 `ensureBound` 能先正常用满自己的窗口、
   * 不会在半途被判定为僵尸（否则会与调用方语义打架）。该值未经多机型校准，调整只改这一处。
   */
  private const val BIND_WATCHDOG_MS = 20_000L

  /** 「正在建立」的结构化 code（可重试语义，与「未绑定需排查」区分）。 */
  private const val CONNECTING_CODE = ShizukuBindCodes.CONNECTING

  private val lock = Any()
  @Volatile private var service: ShizukuUserService? = null

  /**
   * 绑定状态机（绑定闩 / 次数 / 最近错误码 / 看门狗）。替代原先裸的 `@Volatile binding + lastError`：
   * 那对字段没有超时面，一次不回调的 bind 就能把整条通道永久闩死。
   */
  private val bindState = ShizukuBindState(BIND_WATCHDOG_MS)

  @Volatile private var connectedAt = 0L
  private var bindLatch: CountDownLatch? = null

  /** v3：本连接是否已把「应用 uid + 数据目录」回填给 UserService（按连接代次去重）。 */
  @Volatile private var configuredForAge = -1L

  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
      service = ShizukuUserService.Stub.asInterface(binder)
      // 先探活再记账：binder 无效时不得当成「连上」（与 status() 的 bound 判据同口径）。
      bindState.onConnected(binder.pingBinder())
      connectedAt = SystemClock.elapsedRealtime()
      bindLatch?.countDown()
      // 连接已建立，latch 使命完成：清空以便断连后重建。留着它会让下一次 ensureBound 的
      // await 立即返回、永远看不到新的等待窗口（本缺陷的根因形态）。
      bindLatch = null
      Log.i(TAG, "user service connected ${name.className}")
    }

    override fun onServiceDisconnected(name: ComponentName) {
      service = null
      bindState.onDisconnected()
      bindLatch?.countDown()
      // 断连后旧 latch 已经 countDown、语义作废；清掉它，下一次 ensureBound 才会新建并真正等待。
      // 复用已放行的 latch 会让 await 立即返回 → 又变成「第一次必失败」，正是本缺陷的成因。
      bindLatch = null
      Log.w(TAG, "user service disconnected ${name.className}")
    }
  }

  /**
   * 用户显式「重置链接」（设置页「手机控制」）：强制移除 Shizuku 侧 UserService 并清空绑定态。
   *
   * ── 承重墙：为什么必须 `remove = true`（本按钮有效的唯一原因）────────────────────
   * 现场：Shizuku 已授权，却从「可创建」跳成「需要准备」，**重新授权与重启 App 均无效**。
   * 「App 重启无效」直接排除了「我们自己进程内的标志位脏了」——那会被重启清掉。它指向的是
   * **Shizuku 侧的 UserService 实例处于坏态**：绑定请求发出后既不回调也不抛，成为僵尸。
   * 只清我们的标志位对这个坏实例毫无作用（下一次 bind 仍打到同一个坏实例上）。
   * `Shizuku.unbindUserService(args, connection, remove = true)` 在 AAR 里的实现是
   * `IShizukuService.removeUserService(conn, forRemove = true)`（本仓实测 disassemble 确认），
   * 即让 Shizuku 管理器**移除**这个 UserService，下次绑定重建一个干净的。
   *
   * 这不是新增承诺：[readyService] 的既有文案早已写着「在设置页「手机控制」重新连接 Shizuku 会
   * 重启 UserService（无需重装）」——而那条路此前并不存在（该文案的来源其实是另一处
   * `unlockRestrictedSettingsViaShizuku`）。本方法是把已承诺的那条路真的修出来。
   *
   * ── 纪律：绝不同步等待新绑定 ──────────────────────────────────────────────────
   * 本方法在设置页的每次点击上同步执行（UI 高频路径），**不得**在此 await 新绑定——
   * 与 [kickBind] 同纪律。重置后由下一次 2s 轮询 + [kickBind] 自然收敛。
   *
   * ── `ok` 的语义：本方法的 `ok` 与 [status] 的 `ok` **不是同一件事**（勿「统一」）──────────
   * 两者同名但语义不同，本方法同时用 `ok` 与 `code` 说两件不同的事：
   *   - `ok`   = **重置动作是否已执行**（动作面）。成功执行即 true——哪怕通道此刻还没就绪。
   *   - `code` = **当前通道态**（就绪面）。重置后为 [ShizukuBindCodes.RESET]，即「已重置、待重建」。
   * [status] 的 `ok` 则**确为就绪判据**（`bound == true` 才 true），且被 [ControlCarrier.shizukuReady]
   * 与 `VdisplayController` 的就绪分支消费——**绝不能**改它。
   *
   * 为什么必须分开（0.14.2 设备实测缺陷）：重置**真的生效了**（`bindAttempts` 1→3、
   * `userServiceAgeMs` 从 608801ms 归到 66538ms），但本方法此前直接返回 [status]，其 `ok=false`
   * （重置后尚未绑定），而 UI 的 `settleLinkCall` 判 `ok === true` 才算成功 ⇒ 界面把「重置成功、
   * 通道待重建」渲染成「重置 Shizuku 连接失败」。这是「语义在一个字段上被两件事共用」的形态：
   * 一个只读快照的 `ok` 被拿去回答一个动作的成败。
   *
   * 修法：动作成功即显式置 `ok=true` 并加 `reset=true`，**同时原样保留**就绪事实字段
   * （`bound=false` / `binding=false` / `lastError=RESET` / `code=RESET` / `guidance`）——
   * 它们仍如实说「通道当前未就绪」。**只改这一个动作方法的返回**，[status] 一字不动。
   *
   * @param context 任意 context（内部取 applicationContext）。
   * @returns 写后回读的 [status]，但 `ok` 表达**动作已执行**、`reset=true` 标记本次为重置动作；
   *   就绪事实字段一律保留（通道是否已就绪看 `code`/`bound`/`lastError`，不看 `ok`）。
   */
  fun resetConnection(context: Context): JSONObject {
    val app = context.applicationContext
    synchronized(lock) {
      // 承重墙：让 Shizuku 管理器移除这个 UserService。失败不抛出——重置本身必须始终走完
      // （否则 Shizuku 侧已死时连「清空我们这一侧」都做不到，用户会看到按钮毫无反应）。
      runCatching { Shizuku.unbindUserService(args(app), connection, /* remove = */ true) }
        .onFailure { Log.w(TAG, "reset: unbindUserService(remove=true) failed: " + it.javaClass.simpleName + ": " + (it.message ?: "")) }
      service = null
      connectedAt = 0L
      // 作废在飞的僵尸闩：留着它会让下一次 ensureBound 复用一个永不 countDown 的 latch，
      // 于是重置后仍然只是「等满预算再报正在建立」——按钮看起来点了但没反应。
      bindLatch?.countDown()
      bindLatch = null
    }
    // 我们这一侧的记账复位：bindingFlag=false 使下一次 beginAttempt 放行（这就是「重置有效」的判据）。
    bindState.onReset()
    // caps 的 5s TTL 缓存必须失效，否则重置后最多 5 秒内界面仍报旧值，用户会认为按钮没用。
    ControlCarrier.invalidateShizukuCache()
    Log.i(TAG, "shizuku connection reset (user action); attempts=" + bindState.attempts)
    // 动作面 `ok` + `reset` 由纯函数组装（可在 JVM 上逐条断言，见 ShizukuBindStateTest）。
    // 就绪事实一个都不删：code/lastError/bound/binding/guidance 仍由 status() 原样带出，
    // 于是调用方既能知道「重置动作成功」，也能知道「通道此刻还没就绪」。
    return shizukuResetResponse(status(app))
  }

  /**
   * 看门狗执行点（**非阻塞**，可安全出现在 status() 这类高频读路径上）。
   *
   * 只在确有一次僵尸绑定时才动锁与日志（[ShizukuBindState.reapIfStale] 幂等），
   * 因此热路径上的开销是一次 synchronized + 一次减法。
   */
  private fun reapStaleBind() {
    if (!bindState.reapIfStale(SystemClock.elapsedRealtime())) return
    Log.w(
      TAG,
      "bind watchdog: no callback within ${BIND_WATCHDOG_MS}ms; reset binding state (attempts=${bindState.attempts})",
    )
    synchronized(lock) {
      // 放行并作废当前 latch。留着它会让后续 ensureBound 一直复用一个永不 countDown 的 latch，
      // 于是每轮仍然只是「等满预算再报正在建立」——那正是本缺陷的形态。
      bindLatch?.countDown()
      bindLatch = null
    }
  }

  private fun args(context: Context): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
    ComponentName(context.packageName, ShizukuUserServiceBridge::class.java.name),
  )
    .daemon(false)
    .tag(USER_SERVICE_TAG)
    .processNameSuffix("dsh-vdisplay")
    .debuggable(BuildConfig.DEBUG)
    .version(BuildConfig.VERSION_CODE * 10 + ShizukuUserServiceBridge.PROTOCOL_VERSION)

  /** Stable JSON state suitable for the native bridge and VirtualDisplay controller. */
  fun status(context: Context): JSONObject {
    // 看门狗先跑：它是**读路径**上唯一的自愈点（设置页与面板每 2s 轮询 status）。
    // 不放在这里的话，一次不回调的 bind 会让状态永远停在「正在建立」，没有任何人会发现。
    reapStaleBind()
    val out = JSONObject().put("installed", installed(context))
    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    out.put("running", running)
    out.put("granted", false)
    out.put("bound", service?.asBinder()?.pingBinder() == true)
    out.put("userServiceAgeMs", if (connectedAt > 0L) SystemClock.elapsedRealtime() - connectedAt else -1)
    // 绑定面三态的可观测出口（0.14.1）：调用方（设置页 / 工具面 / 诊断）据此区分
    // 「尚未发起」「正在建立（确有 bind 在飞）」「已失败」，而不是由一句文案猜。
    out.put("binding", bindState.binding)
    out.put("bindAttempts", bindState.attempts)
    out.put("bindAgeMs", bindState.attemptAgeMs(SystemClock.elapsedRealtime()))
    out.put("lastError", bindState.lastError)

    if (!out.optBoolean("installed")) {
      return out.put("ok", false).put("code", "shizuku-absent")
        .put("guidance", "未检测到 Shizuku；虚拟屏需要用户安装、启动并授权 Shizuku。")
    }
    if (!running) {
      return out.put("ok", false).put("code", "shizuku-not-running")
        .put("guidance", "Shizuku 已安装但服务未运行。非 root 设备可通过 USB/有线 ADB 或无线调试启动它；重启设备后需再次启动。")
    }

    val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
    val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
    out.put("version", version).put("uid", uid)
    if (version < 12) {
      return out.put("ok", false).put("code", "shizuku-prev11")
        .put("guidance", "Shizuku 版本过低，虚拟屏需要 v12 及以上的 UserService 支持。")
    }

    val granted = runCatching {
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    out.put("granted", granted)
    if (!granted) {
      return out.put("ok", false).put("code", "shizuku-denied")
        .put("guidance", "尚未获得 Shizuku 授权。点击创建虚拟屏后会由 Shizuku 管理器显示用户确认；DSH 不会自行授予权限。")
    }
    if (service?.asBinder()?.pingBinder() != true) {
      // 三态化（0.14.1）：code 只报「正在建立」当且仅当确有 bind 在飞。
      // 旧实现在这里恒报 connecting + 「正在建立 shell UserService」，把「从未发起」与
      // 「绑定已失败（超时/无效 binder/被拒）」都盖成了同一句——正是本缺陷的文案面。
      val binding = bindState.binding
      val ageMs = bindState.attemptAgeMs(SystemClock.elapsedRealtime())
      val code = if (binding) CONNECTING_CODE else bindState.lastError.ifBlank { ShizukuBindCodes.NOT_BOUND }
      val answer = out.put("ok", false).put("code", code)
        .put("guidance", shizukuBindGuidance(code, binding, ageMs, BIND_WATCHDOG_MS))
      if (binding) answer.put("retryAfterMs", BIND_TIMEOUT_MS)
      return answer
    }
    return out.put("ok", true).put("code", "shizuku-ready")
      .put("guidance", "Shizuku shell UserService 已就绪（uid=$uid）。")
  }

  /**
   * 非阻塞「催一下」：已装 + 已运行 + 已授权但尚未绑定时，在后台发起一次 UserService 绑定。
   *
   * 为什么需要它（0.14.0 设备实锤缺陷）：status() 是**纯读**且不得阻塞（它在控制队列与 UI
   * 轮询路径上被高频调用，绝不能等 binder）。但设置页「刷新 Shizuku 状态」与面板的
   * vdisplayStatus 轮询走的正是 status()——此前唯一会发起绑定的是建屏路径的 ensureBound()，
   * 于是「用户已授权、Shizuku 在运行」的机器上，那条 UI 路径**无论刷新多少次都不会建连**，
   * 永远停在 shizuku-user-service-not-bound（用户实测「会一直卡在这」）。
   *
   * 这里把绑定动作与读取动作解耦：读路径只负责**触发**一次后台绑定并立即返回当前状态，
   * 真正的等待交给下一次轮询（2s）自然收敛。绑定成功后 status() 会如实报 ok=true。
   */
  fun kickBind(context: Context) {
    val app = context.applicationContext
    // status() 内部先跑看门狗：若上一次绑定已成僵尸，这里读到的 binding 已是 false，
    // 下面的守卫不会再把它挡回去——「一次不回调的 bind 永久关掉重试」的闭环由此打开。
    val current = status(app)
    if (!current.optBoolean("installed") || !current.optBoolean("running")) return
    if (!current.optBoolean("granted")) return
    if (current.optBoolean("bound")) return
    // 已有绑定在飞（binding=true）时不重复发起——Shizuku.bindUserService 幂等但没必要抖动。
    if (bindState.binding) return
    Thread({
      runCatching { ensureBound(app) }.onFailure {
        Log.w(TAG, "background bind failed: " + it.javaClass.simpleName + ": " + (it.message ?: ""))
      }
    }, "dsh-shizuku-kick").start()
  }

  /** Establish the UserService on an explicit user action. */
  fun ensureBound(context: Context): JSONObject {
    val before = status(context)
    if (!before.optBoolean("installed") || !before.optBoolean("running")) return before
    if (!before.optBoolean("granted")) {
      val requested = runCatching {
        if (!Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQUEST_CODE_VDISPLAY)
        true
      }.getOrDefault(false)
      return status(context)
        .put("requested", requested)
        .put("code", if (requested) "shizuku-permission-requested" else "shizuku-denied")
        .put("guidance", "已向 Shizuku 发起授权请求；请在其系统确认页批准后再次点击创建虚拟屏。")
    }
    if (service?.asBinder()?.pingBinder() == true) return status(context)

    // 进入等待前先回收僵尸绑定：否则 `beginAttempt` 会因 binding=true 直接返回 false，
    // 而调用方只能一路空等满预算——「每轮都报正在建立、永远不重试」正是本缺陷的形态。
    reapStaleBind()

    val deadline = SystemClock.elapsedRealtime() + BIND_TOTAL_MS
    while (true) {
      val latch: CountDownLatch
      synchronized(lock) {
        if (service?.asBinder()?.pingBinder() == true) return status(context)
        latch = bindLatch ?: CountDownLatch(1).also { bindLatch = it }
        if (bindState.beginAttempt(SystemClock.elapsedRealtime())) {
          try {
            Shizuku.bindUserService(args(context.applicationContext), connection)
          } catch (t: Throwable) {
            bindState.onBindThrew(ShizukuBindCodes.bindFailed(t))
            // 发起就抛异常：latch 永不会因回调而放行，必须换新的，否则后续调用会永远复用一个死 latch。
            bindLatch = null
            latch.countDown()
          }
        }
      }
      if (service?.asBinder()?.pingBinder() == true) return status(context)
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0L) break
      // 到点即复用当次结果，不把 4s 片拉长到整段预算——调用方的超时语义不变。
      latch.await(minOf(BIND_TIMEOUT_MS, remaining), TimeUnit.MILLISECONDS)
      if (service?.asBinder()?.pingBinder() == true) return status(context)
      // 回调已发生但服务仍不可用（invalid-binder / disconnected）：不要继续等，如实汇报。
      // 僵尸超时不能走这条 break——那会把「这次尝试已死」当成「已失败并就此定论」，
      // 而正确的语义是「已复位、可立即再试」，故先跑看门狗再看 binding。
      reapStaleBind()
      if (!bindState.binding) break
    }
    // 预算耗尽且确实还在连接中：给出「可重试」而不是「去设置页排查」。区分这两者是本缺陷的核心。
    return if (bindState.binding) {
      status(context).put("code", CONNECTING_CODE).put("retryAfterMs", BIND_TIMEOUT_MS)
        .put(
          "guidance",
          shizukuBindGuidance(
            CONNECTING_CODE,
            true,
            bindState.attemptAgeMs(SystemClock.elapsedRealtime()),
            BIND_WATCHDOG_MS,
          ),
        )
    } else {
      status(context)
    }
  }

  /**
   * v3（2026-09-30）：把「本应用 uid + 数据目录」回填给 UserService（每次连接一次）。
   *
   * 为什么必须做：root 通道里 UserService 的 uid=0，它写的文件属主是 root:root——落进应用
   * 数据目录就是**应用自己读不回来**（0600），watcher / 插件更新 / 引擎读写随之失败。
   * 回填后 UserService 才能把 [writeChunk] 的产物与 [repairOwnership] 的目标修回应用 uid。
   * 旧服务或未确认配置返回 false，执行面拒绝派发；不能在身份/归一化能力未知时继续写入。
   */
  private fun configureIfNeeded(context: Context): Boolean {
    val remote = service ?: return false
    val age = connectedAt
    if (age <= 0L) return false
    if (configuredForAge == age) return true
    val app = context.applicationContext
    val uid = app.applicationInfo.uid
    val root = app.applicationInfo.dataDir
    return try {
      if (remote.protocolVersion() < ShizukuUserServiceBridge.PROTOCOL_VERSION) return false
      remote.configure(uid, root)
      val ack = remote.configuration()
      val valid = ack.getBoolean("ok") && ack.getInt("appUid", -1) == uid &&
        ack.getString("appDataDir") == root && ack.getInt("protocolVersion", -1) >= ShizukuUserServiceBridge.PROTOCOL_VERSION
      if (valid) configuredForAge = age
      valid
    } catch (failure: Throwable) {
      Log.w(TAG, "configure not acknowledged: " + failure.javaClass.simpleName)
      false
    }
  }

  /**
   * 属主归一（root 通道写盘污染的固定自愈原语，v4）。**不受 AI 授权门约束**——它不是模型能力，
   * 而是应用修自己文件的自愈面：目标必须落在应用数据目录内、uid/gid 由 UserService 侧
   * 固定为已配置的应用 uid（不接受任意 uid/gid）。
   */
  fun repairOwnership(context: Context, path: String, maxEntries: Int = 20_000): JSONObject =
    RootExecutionFence.maintenance(context) {
      val (remote, refusal) = readyService(context, applyGate = false)
      if (remote == null) return@maintenance refusal ?: unavailableShell()
      if (!configureIfNeeded(context)) return@maintenance JSONObject().put("ok", false)
        .put("code", "repair-configuration-required").put("reason", "repair-configuration-required")
        .put("guidance", "UserService 未确认应用身份，请重置 Shizuku 连接后再试。")
      val lease = RpcLease(context, "shizuku-ownership-repair", applyGate = false)
      try {
        lease.beforeRpc(remote)?.let { return@maintenance it }
        val reply = remote.repairOwnership(path, maxEntries)
        lease.acknowledged()
        val result = bundleJson(reply).put("transport", "shizuku")
        val verified = reply.getBoolean("ok") && reply.containsKey("checked") && reply.containsKey("healed") &&
          reply.getInt("failures", -1) == 0 && reply.getInt("unverifiedMutations", -1) == 0 &&
          reply.getInt("remaining", -1) == 0 && !reply.getBoolean("truncated") && !reply.getBoolean("deadlineExceeded")
        lease.complete(result, definitive = verified)
      } catch (failure: Throwable) {
        lease.complete(JSONObject().put("ok", false).put("transport", "shizuku")
          .put("code", "repair-result-unknown").put("reason", "repair-result-unknown")
          .put("failures", 1).put("remaining", -1)
          .put("guidance", "属主维护未返回完整验证结果；不重放，需先处理隔离状态。"), definitive = false)
      }
    }

  /** Bounded deep walk reaches polluted startup leaves even when their ancestors are app-owned. */
  fun autoHealOwnership(context: Context): JSONObject = RootOwnershipJobs.runBlocking(context)

  /** Shared Activity/Service startup guard; coalesce near-simultaneous completed scans only. */
  fun prepareStartupOwnership(context: Context): JSONObject = RootOwnershipJobs.runBlocking(context, reuseRecent = true)

  internal fun autoHealOwnershipDirect(context: Context): JSONObject = RootExecutionFence.maintenance(context) {
    val app = context.applicationContext
    val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
    val viaSu = RootAccess.isGranted(app)
    if (uid != RootGrant.ROOT_UID && !viaSu) return@maintenance JSONObject().put("ok", true)
      .put("checked", 0).put("healed", 0).put("failures", 0).put("skipped", "no-root-path")
    val root = java.io.File(app.applicationInfo.dataDir, "files").path
    val result = if (viaSu) RootAccess.repairOwnership(app, root, OwnershipRepairCore.MAX_ENTRIES, 20_000L)
      else repairOwnership(app, root, OwnershipRepairCore.MAX_ENTRIES)
    result.put("channelUid", uid).put("transport", if (viaSu) "su" else "shizuku")
  }


  /**
   * 显式请求 Shizuku 授权（**必须在 UI 线程 + 有前台 Activity**）。
   *
   * 为什么单独成方法（2026-09-30 实测）：`ensureBound` 里的自动请求跑在**后台线程**
   * （repairOwnership / kickBind 的路径），Shizuku 的 `requestPermission` 需要当前 Activity
   * 才能把授权对话框落到用户眼前——后台调用静默失败，结果是「管理器列表里根本没有本应用」
   * 且状态恒为 denied（用户看不到任何可点的授权入口）。
   *
   * 返回写后回读的 [status]，并带 `requested` 说明这次是否真的发起了请求。
   */
  fun requestPermission(activity: Activity): JSONObject {
    val requested = runCatching {
      activity.runOnUiThread { runCatching { Shizuku.requestPermission(REQUEST_CODE_VDISPLAY) } }
      true
    }.getOrDefault(false)
    return status(activity).put("requested", requested)
  }

  /** Native-only fixed argv execution. Never pass user/model-controlled shell text here. */
  fun runController(context: Context, argv: Array<String>): JSONObject = RootExecutionFence.command(context) {
    runControllerInternal(context, argv)
  }

  private fun runControllerInternal(context: Context, argv: Array<String>): JSONObject {
    val (remote, refusal) = readyService(context)
    if (remote == null) return refusal ?: unavailableShell()
    val lease = RpcLease(context, "shizuku-controller")
    return try {
      lease.beforeRpc(remote)?.let { return it }
      val reply = remote.exec(argv, 8_000)
      lease.acknowledged()
      lease.complete(bundleJson(reply).put("transport", "shizuku"), executionDefinitive(reply))
    } catch (failure: Throwable) {
      lease.complete(shellFailure(failure), definitive = false)
    }
  }

  fun identity(context: Context): JSONObject {
    val ready = ensureBound(context)
    if (!ready.optBoolean("ok")) return ready
    return try {
      val remote = service ?: return JSONObject().put("ok", false).put("code", "shizuku-user-service-not-bound")
      JSONObject().put("ok", true).put("uid", remote.uid()).put("protocolVersion", remote.protocolVersion())
    } catch (t: Throwable) {
      JSONObject().put("ok", false).put("code", "shizuku-identity-failed")
        .put("guidance", t.javaClass.simpleName + ": " + (t.message ?: ""))
    }
  }

  // ── 0.14.0 特权 shell 通道（替换退役的内置 adb：execAdbShell / execAdbLine 的壳侧执行面） ──────

  /** 单次 shell 调用默认 / 上限超时（§6「放宽超时」；旧 adb 路径为 8s 级）。 */
  private const val SHELL_TIMEOUT_MS = 20_000
  private const val MAX_SHELL_TIMEOUT_MS = 120_000
  private const val PULL_CHUNK = 512 * 1024
  private const val MAX_TRANSFER_BYTES = 512L * 1024 * 1024
  private const val SHELL_PATH_PREFIX = "export PATH=/system/bin:/system/xbin:\$PATH; "

  /** v2 协议面就绪判定：返回 (service, refusal)——refusal 非空即结构化拒绝，调用方直接透传。 */
  private fun readyService(context: Context, applyGate: Boolean = true): Pair<ShizukuUserService?, JSONObject?> {
    // issue #262 方案 A 策略门（runShell/pullFile/pushFile/removeRemote 四个执行面的共同入口）：
    // 通道身份为 root 且未授权时，在**发起任何绑定/执行之前**拒绝——不是按 op 分类放行，
    // uid 0 下 shExec 是任意 shell，分类隔离不存在（诚实性要求），故整体关闭。
    //
    // applyGate=false 仅供**应用自愈面**（repairOwnership）使用：它只把应用自己数据目录里的文件
    // 属主修回应用自己的 uid，不是模型能力、也不构成权限放大；被自己的策略门挡住会让
    // 「root 通道 + AI 未授权」这一组合失去自愈能力（文档已如此承诺，实现必须一致）。
    if (applyGate) rootGateRefusal(context)?.let { return null to it }
    val ready = ensureBound(context)
    if (!ready.optBoolean("ok")) return null to ready
    val remote = service
    if (remote == null || remote.asBinder()?.pingBinder() != true) {
      return null to status(context).put("ok", false).put("code", "shizuku-user-service-not-bound")
    }
    val pv = runCatching { remote.protocolVersion() }.getOrDefault(-1)
    if (pv < ShizukuUserServiceBridge.PROTOCOL_VERSION) {
      return null to JSONObject().put("ok", false).put("code", "shizuku-user-service-too-old")
        .put("protocolVersion", pv)
        .put("guidance", "Shizuku UserService 协议为 v$pv，本机需要 v${ShizukuUserServiceBridge.PROTOCOL_VERSION}；" +
          "请在设置页「手机控制」重置连接，重启旧 UserService（无需重装）。")
    }
    if (!configureIfNeeded(context)) return null to JSONObject().put("ok", false)
      .put("code", "shizuku-configuration-required").put("reason", "shizuku-configuration-required")
      .put("guidance", "UserService 未确认本应用身份，请重置连接后重试。")
    if (applyGate) actualIdentityRefusal(context, remote)?.let { return null to it }
    return remote to null
  }

  /**
   * 特权 shell 执行（uid 2000）：`sh -c <command>`，PATH 前置系统目录（F3 远端 PATH 污染修复同源）。
   * capture=true 时大输出落 shell 侧 spool 文件，只回报前 8 KiB 与文件坐标（filePath/size）。
   *
   * ── issue #262「命令面引号逃逸」在本方案下的处置（2026-09-30 对账登记）──────────────
   * issue 原文点名：`argv = [sh, -c, PREFIX+command]` 形态下，模型传 `'; id -u; '` 会**逃逸回 root**，
   * 并规定「任何**降权/包装**方案必须走 argv（命令作为单个 argv 元素）」。
   *
   * 本仓现状 = issue 的**方案 A**（策略门 + 免责门 + 通道身份探测，**不做降权包装**）：
   * 命令本就按通道身份（uid 0 或 2000）执行，不存在「被包装后逃逸出去」的对象 ⇒ 该向量在 A 下
   * **不成立**，故此处不改为 argv 形态（改了也只是同一件事换个写法，不增加任何隔离）。
   *
   * ⚠️ **硬约束（留给将来）**：一旦引入任何降权/包装（如 `su -c "setuidgid N ..."` 形态），
   * 必须把待执行命令作为**单个 argv 元素**传入（`ProcessBuilder(listOf(wrapper, "sh", "-c", command))`
   * 或 AIDL 的 argv 数组），**绝不把 command 拼进被包装的 shell 字符串里**——否则模型可用引号
   * 逃出包装、以包装者的身份执行（issue 已实测该逃逸）。
   */
  fun runShell(context: Context, command: String, timeoutMs: Int = SHELL_TIMEOUT_MS, capture: Boolean = false): JSONObject = RootExecutionFence.command(context) {
    runShellInternal(context, command, timeoutMs, capture)
  }

  private fun runShellInternal(context: Context, command: String, timeoutMs: Int = SHELL_TIMEOUT_MS, capture: Boolean = false): JSONObject {
    if (command.isBlank()) {
      return JSONObject().put("ok", false).put("code", "shell-empty").put("guidance", "空命令")
    }
    val (remote, refusal) = readyService(context)
    if (remote == null) return refusal ?: unavailableShell()
    val timeout = timeoutMs.coerceIn(1_000, MAX_SHELL_TIMEOUT_MS)
    val argv = arrayOf("sh", "-c", SHELL_PATH_PREFIX + command)
    val lease = RpcLease(context, if (capture) "shizuku-capture" else "shizuku-exec")
    return try {
      // argv/timeout and all local setup are complete before the final dispatch-point check.
      lease.beforeRpc(remote)?.let { return it }
      val reply = if (capture) remote.execCapture(argv, timeout, 8 * 1024) else remote.exec(argv, timeout)
      lease.acknowledged()
      val result = bundleJson(reply).put("transport", "shizuku")
      if (capture) {
        result.remove("inline")
        result.remove("path")
        result.put("stdout", String(reply.getByteArray("inline") ?: ByteArray(0), Charsets.UTF_8))
          .put("filePath", if (reply.getBoolean("spoolReady")) reply.getString("path") ?: "" else "")
      } else result.put("stdout", reply.getString("stdout") ?: "")
      lease.complete(result, executionDefinitive(reply) && (!capture || reply.getBoolean("spoolReady")))
    } catch (failure: Throwable) {
      lease.complete(shellFailure(failure), definitive = false)
    }
  }

  /** 远端 → 应用私有目录（files/...）分块取回（pull 语义）。 */
  fun pullFile(context: Context, remote: String, local: String): JSONObject = RootExecutionFence.command(context) {
    pullFileInternal(context, remote, local)
  }

  private fun pullFileInternal(context: Context, remote: String, local: String): JSONObject {
    val target = engineLocalFile(context, local)
      ?: return JSONObject().put("ok", false).put("code", "shell-path-denied")
        .put("guidance", "本地落点必须是应用私有目录内的路径（files/...）：$local")
    if (!remote.startsWith("/")) {
      return JSONObject().put("ok", false).put("code", "shell-path-denied")
        .put("guidance", "远端路径必须是绝对路径：$remote")
    }
    val (remoteSvc, refusal) = readyService(context)
    if (remoteSvc == null) return refusal ?: unavailableShell()
    val lease = RpcLease(context, "shizuku-pull")
    var offset = 0L // Only bytes acknowledged by the remote and committed to the local sink.
    return try {
      target.parentFile?.mkdirs()
      java.io.FileOutputStream(target).use { sink ->
        while (true) {
          lease.beforeRpc(remoteSvc)?.let { return transferFacts(it, offset) }
          val chunk = remoteSvc.readChunk(remote, offset, PULL_CHUNK)
          lease.acknowledged()
          if (chunk == null) return transferFacts(lease.complete(shellFailure(
            IllegalStateException("远端不可读（不存在或权限不足）：$remote"))), offset)
          if (chunk.isEmpty()) break
          if (chunk.size > PULL_CHUNK || chunk.size.toLong() > MAX_TRANSFER_BYTES - offset) {
            return transferFacts(lease.complete(JSONObject().put("ok", false).put("code", "shell-output-too-large")
              .put("guidance", "远端文件超过传输上限：$remote")), offset)
          }
          sink.write(chunk)
          offset += chunk.size
        }
      }
      lease.complete(JSONObject().put("ok", true).put("code", "shell-ok")
        .put("localPath", target.absolutePath).put("path", target.absolutePath).put("size", offset).put("offset", offset))
    } catch (failure: Throwable) {
      transferFacts(lease.failed(failure), offset)
    }
  }

  /** 应用私有目录（files/...）→ 远端分块写入（push 语义）。 */
  fun pushFile(context: Context, local: String, remote: String): JSONObject = RootExecutionFence.command(context) {
    pushFileInternal(context, local, remote)
  }

  private fun pushFileInternal(context: Context, local: String, remote: String): JSONObject {
    val source = engineLocalFile(context, local)
      ?: return JSONObject().put("ok", false).put("code", "shell-path-denied")
        .put("guidance", "本地来源必须是应用私有目录内的路径（files/...）：$local")
    if (!remote.startsWith("/")) {
      return JSONObject().put("ok", false).put("code", "shell-path-denied")
        .put("guidance", "远端路径必须是绝对路径：$remote")
    }
    if (!source.isFile) {
      return JSONObject().put("ok", false).put("code", "shell-path-denied")
        .put("guidance", "本地文件不存在：$local")
    }
    if (source.length() > MAX_TRANSFER_BYTES) {
      return JSONObject().put("ok", false).put("code", "shell-output-too-large")
        .put("guidance", "本地文件超过传输上限：$local")
    }
    val (remoteSvc, refusal) = readyService(context)
    if (remoteSvc == null) return refusal ?: unavailableShell()
    val lease = RpcLease(context, "shizuku-push")
    var offset = 0L // Acknowledged successful chunks; never guess bytes written by a failed RPC.
    return try {
      java.io.FileInputStream(source).use { input ->
        val buf = ByteArray(PULL_CHUNK)
        var sent = false
        while (true) {
          val n = input.read(buf)
          if (n < 0 && sent) break
          if (n == 0) continue
          val count = n.coerceAtLeast(0) // Empty source still dispatches one truncating empty write.
          if (count.toLong() > MAX_TRANSFER_BYTES - offset) return transferFacts(lease.complete(
            JSONObject().put("ok", false).put("code", "shell-output-too-large")), offset)
          val slice = if (count == buf.size) buf else buf.copyOf(count)
          val append = offset > 0
          // Local read/copy completed: recheck actual UID + current effective consent EVERY chunk.
          lease.beforeRpc(remoteSvc)?.let { return transferFacts(it, offset) }
          val reply = remoteSvc.writeChunk(remote, slice, append)
          lease.acknowledged()
          if (!reply.containsKey("ok")) return transferFacts(lease.complete(
            JSONObject().put("ok", false).put("code", "shell-write-failed"), definitive = false), offset)
          if (!reply.getBoolean("ok")) {
            return transferFacts(lease.complete(JSONObject().put("ok", false).put("code", "shell-write-failed")
              .put("failedChunkMayBePartial", true)
              .put("guidance", "远端写入失败：" + (reply.getString("error") ?: "") + "（$remote）")), offset)
          }
          offset += count
          sent = true
        }
      }
      lease.complete(JSONObject().put("ok", true).put("code", "shell-ok")
        .put("remotePath", remote).put("path", remote).put("size", offset).put("offset", offset))
    } catch (failure: Throwable) {
      transferFacts(lease.failed(failure), offset)
    }
  }

  /** 远端删除（rm -f 语义；幂等）。 */
  fun removeRemote(context: Context, remote: String): JSONObject = RootExecutionFence.command(context) {
    removeRemoteInternal(context, remote)
  }

  private fun removeRemoteInternal(context: Context, remote: String): JSONObject {
    if (!remote.startsWith("/")) {
      return JSONObject().put("ok", false).put("code", "shell-path-denied")
        .put("guidance", "远端路径必须是绝对路径：$remote")
    }
    val (remoteSvc, refusal) = readyService(context)
    if (remoteSvc == null) return refusal ?: unavailableShell()
    val lease = RpcLease(context, "shizuku-remove")
    return try {
      lease.beforeRpc(remoteSvc)?.let { return it }
      val reply = remoteSvc.removePath(remote)
      lease.acknowledged()
      lease.complete(JSONObject().put("ok", reply.getBoolean("ok"))
        .put("code", if (reply.getBoolean("ok")) "shell-ok" else "shell-remove-failed")
        .put("guidance", reply.getString("error") ?: "").put("remotePath", remote), reply.containsKey("ok"))
    } catch (failure: Throwable) {
      lease.complete(shellFailure(failure), definitive = false)
    }
  }

  /** 引擎相对路径（`files/...`）→ 应用私有目录内的绝对文件；越界一律拒绝（fail-closed）。 */
  private fun engineLocalFile(context: Context, raw: String): java.io.File? {
    if (raw.isBlank()) return null
    val f = if (raw.startsWith("/")) java.io.File(raw) else java.io.File(context.dataDir, raw)
    val canon = runCatching { f.canonicalFile }.getOrNull() ?: return null
    val root = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
    val rootPath = root.path
    return canon.takeIf { it.path == rootPath || it.path.startsWith(rootPath + java.io.File.separator) }
  }

  private fun shellFailure(t: Throwable): JSONObject = JSONObject()
    .put("ok", false)
    .put("code", "shell-transport-failed")
    .put("guidance", "Shizuku shell 通道失败：" + t.javaClass.simpleName + ": " + (t.message ?: ""))

  /**
   * issue #262 方案 A：root 通道授权门（策略门 + 知情同意，**不是技术沙箱**）。
   *
   * 判据用**通道身份**而不是「设备是否 root」：只有 Shizuku 服务端以 root 启动（uid=0）
   * 时本门才生效；uid=2000（ADB 启动）或读不到 uid 的通道维持既有语义，不受影响。
   * uid 0 且未授权 ⇒ 特权执行面整体拒绝——uid 0 下任意 shell 本就无限制，
   * 「按 op 分类只关掉 root 级 op」是做不到的假隔离（issue 已确证），故不装样子。
   *
   * @return `null` = 放行（非 root 通道，或已授权）；非空 = 结构化拒绝 JSON。
   */
  internal fun rootGateRefusal(context: Context): JSONObject? {
    if (RootGrant.isGranted(context)) return null
    val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
    if (uid != RootGrant.ROOT_UID) return null
    return JSONObject()
      .put("ok", false)
      .put("code", "root-grant-required")
      .put("guidance", "Shizuku 通道正以 root（uid 0）运行，而「AI root 权限」未授权：特权通道已按策略关闭。" +
        "到设置页「手机控制」查看免责声明、勾选「已阅读」并开启「AI root 权限」。")
  }

  private fun actualIdentityRefusal(context: Context, remote: ShizukuUserService): JSONObject? =
    dispatchIdentity(context, remote).second

  private fun dispatchIdentity(context: Context, remote: ShizukuUserService,
    applyGate: Boolean = true): Pair<Int, JSONObject?> {
    val uid = runCatching { remote.uid() }.getOrDefault(-1)
    if (uid != 0 && uid != 2000) return uid to JSONObject().put("ok", false)
      .put("code", "shizuku-identity-failed").put("reason", "shizuku-identity-failed")
      .put("guidance", "无法确认实际 UserService 通道身份，请重置连接后再试。")
    if (applyGate && uid == 0 && !RootGrant.isGranted(context)) return uid to JSONObject().put("ok", false)
      .put("code", "root-grant-required").put("reason", "root-grant-required")
      .put("guidance", "AI root 权限未开启或当前版本的免责确认已失效。")
    return uid to null
  }

  /** One lease per actual-root operation; never replay an RPC whose acknowledgement is unknown. */
  private class RpcLease(private val context: Context, private val operation: String,
    private val applyGate: Boolean = true) {
    private var leased = false
    private var inFlight = false
    private var settledResult: JSONObject? = null

    fun beforeRpc(remote: ShizukuUserService): JSONObject? {
      val (uid, refusal) = dispatchIdentity(context, remote, applyGate)
      if (refusal != null) return complete(refusal)
      if (uid == 0 && !leased) {
        RootMaintenanceLease.begin(context, operation)?.let { return it }
        leased = true
        // Persisting the lease is local setup: consent/actual identity must be current AFTER it.
        dispatchIdentity(context, remote, applyGate).second?.let { return complete(it) }
      }
      inFlight = true
      return null
    }

    fun acknowledged() { inFlight = false }

    fun complete(out: JSONObject, definitive: Boolean = true): JSONObject {
      if (!definitive || inFlight) out.put("ok", false)
      if (!leased) {
        if (!definitive || inFlight) out.put("noReplay", true).put("rpcResultUnknown", inFlight)
        return out
      }
      settledResult?.let { return it }
      val result = if (!definitive || inFlight) unknown(out, operation + "-result-incomplete")
      else {
        val finished = try { RootMaintenanceLease.finish(context) } catch (_: Throwable) { false }
        if (finished) out else unknown(out, operation + "-lease-finish-failed")
      }
      settledResult = result
      return result
    }

    fun failed(failure: Throwable): JSONObject = complete(shellFailure(failure), definitive = !inFlight)

    private fun unknown(out: JSONObject, reason: String): JSONObject {
      val result = RootMaintenanceLease.markUnknown(context, reason)
      // Keep timeout/drain/read/offset facts at the top level without replacing the quarantine code.
      for (key in out.keys()) if (key !in setOf("ok", "code", "reason", "guidance")) result.put(key, out.get(key))
      return result.put("operationResult", out).put("noReplay", true).put("rpcResultUnknown", inFlight)
    }
  }

  private fun bundleJson(reply: Bundle): JSONObject = JSONObject().apply {
    for (key in reply.keySet()) put(key, reply.get(key))
  }

  private fun executionDefinitive(reply: Bundle): Boolean = reply.getBoolean("resultComplete") &&
    reply.containsKey("exitCode") && reply.getInt("exitCode", -1) >= 0 &&
    !reply.getBoolean("exitTimedOut") && !reply.getBoolean("drainTimedOut") &&
    !reply.getBoolean("cleanupIncomplete") && !reply.getBoolean("truncated") &&
    reply.getString("readError").isNullOrEmpty()

  private fun transferFacts(out: JSONObject, offset: Long): JSONObject = out
    .put("offset", offset).put("size", offset).put("offsetFact", "acknowledged-bytes")
    .put("partial", offset > 0 || out.optBoolean("rpcResultUnknown") || out.optBoolean("failedChunkMayBePartial"))
    .put("noReplay", true)


  private fun unavailableShell(): JSONObject = JSONObject()
    .put("ok", false).put("code", "shizuku-user-service-not-bound")
    .put("guidance", "Shizuku shell 通道未就绪；请在设置页「手机控制」查看状态与引导。")

  @Suppress("DEPRECATION")
  private fun installed(context: Context): Boolean = runCatching {
    context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
    true
  }.getOrDefault(false)
}

