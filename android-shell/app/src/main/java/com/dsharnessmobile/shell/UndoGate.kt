package com.dsharnessmobile.shell

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 崩溃自动回退（PRD F3 / D6 方案 a）：壳侧「undo 全自动」闸门。
 *
 * 职责：当引擎反复启动失败（看门狗连续失败达到阈值）或引擎日志出现崩溃签名时，
 * 自动执行 dsh-undo-emergency 急救 CLI 的 restore-last-good（把配置/插件代码树
 * 回滚到 boot-state.json 归因出的「最后良好快照」），并恢复引擎。
 *
 * 与 UpdateManager/EngineManager 的 usr 层回退（usr-old）正交：
 * - usr 层：运行时二进制回退（EngineManager.rollbackToOld）
 * - 本类：配置/插件代码回退（dsh-undo-savepoint 快照，F3 主引擎）
 *
 * 幂等约束：
 * - 每个「崩溃纪元」只自动执行一次（.undo-auto-done 标记 + 时间戳），
 *   用户手动重试/手动 undo 后清除标记；
 * - 仅在急救 CLI 存在快照时执行（list 非空）；
 * - 仅在引擎确实无法启动时触发（不误伤正常慢启动）。
 */
object UndoGate {

  private const val TAG = "dsh-undo"
  private val autoUndoRunning = java.util.concurrent.atomic.AtomicBoolean(false)

  /** 看门狗连续失败触发阈值（与 WatchdogV2.MAX_CONSEC_FAILURES 对齐但更保守：熔断即触发）。 */
  const val TRIGGER_CONSEC_FAILURES = 6

  /** 触发后到执行的静默期：留给引擎自己恢复的最后机会（正常慢启动上限 45s+）。 */
  const val WATCH_MS = 15_000L

  /** 崩溃纪元间隔：距上次自动 undo 完成 < 该间隔时不再自动执行（防循环）。 */
  const val RETRY_WINDOW_MS = 30 * 60 * 1000L

  /**
   * Records the first confirmed-dead observation, then grants exactly one caller
   * the right to execute after [WATCH_MS]. A healthy probe disarms the wait.
   */
  /**
   * 回滚的门槛证据（issue #274 ①）。三项都是「这次失败**不该**用回滚来救」的形态：
   *  - 日志里有 `EADDRINUSE`：端口被别的进程占着（我们起不来，重启无意义）；
   *  - 端口**不归本进程**（[portOwnedByApp] == false）：同上，重启我们不解决问题；
   *  - **[slowOnly]**：探活只是**略超**预算（长 turn / 慢磁盘），不是引擎坏了。
   * 三者任一命中 ⇒ 本拍**不 arm 也不 execute**（不碰用户配置、不强杀引擎）。
   *
   * @param logTail 引擎日志尾部（没有则空串）
   * @param portOwnedByApp 端口是否归本应用持有；null = 未知（不因测量失败而否决）
   * @param slowOnly 本次探活是否只是「略超预算」
   */
  data class RollbackEvidence(
    val logTail: String = "",
    val portOwnedByApp: Boolean? = null,
    val slowOnly: Boolean = false,
  ) {
    companion object {
      /** 无证据（既有调用点/测试的默认值）：不否决。 */
      val NONE = RollbackEvidence()
    }
  }

  /**
   * 纯判据：本拍是否应当**放弃**回滚（返回拒绝理由；null = 可以用回滚）。
   *
   * 为什么单独抽成纯函数：这是「自动回滚到底该不该跑」的**第二道门**，必须能被 JVM 逐条反证。
   * 注意它与 [decide] 的分工——[decide] 管「拍数/重试窗/观察窗」这类**计数语义**（既有坑 153 依赖它），
   * 本函数管「这次失败**是不是该用回滚救**」这类**证据语义**。两者都在 [onProbeFailure] 里生效；
   * 但把它们分开，既保住了坑 153 的既有语义（半死引擎仍能走到 [decide] 的 EXECUTE），
   * 又让「探活超时误触发回滚」这一条有独立、可反证的判据。
   */
  internal fun rollbackEvidenceRefusal(evidence: RollbackEvidence): String? {
    if (evidence.logTail.contains("EADDRINUSE")) {
      return "回滚被否决：引擎日志显示 EADDRINUSE（端口被别的进程占着），重启/回滚都不解决问题"
    }
    if (evidence.portOwnedByApp == false) {
      return "回滚被否决：监听端口的不是本进程（端口被他进程占用），重启我们不解决问题"
    }
    if (evidence.slowOnly) {
      return "回滚被否决：本次探活只是略超预算（长 turn / 慢磁盘），不是引擎故障——不碰用户配置、不强杀引擎"
    }
    return null
  }

  /**
   * Records the first confirmed observation, then grants one caller the right to
   * execute after [WATCH_MS]. A healthy probe disarms the wait.
   *
   * 【issue #274 ①】在计数闸门之前先过**证据闸门**（[rollbackEvidenceRefusal]）。
   * 真因：探活在 HTTP 超 2.5s 预算时给 DEGRADED_HTTP（实测出现过 3061ms），长 turn / 慢磁盘
   * 足以让它连续 6 拍（30s）⇒ 直接回滚用户配置 + 强杀活引擎。一次慢响应不该有这种权限。
   * 证据闸门否决时**既不 arm 也不 execute**，并落一行可诊断叙述。
   *
   * @param evidence 设备侧证据（默认无证据 = 不否决；既有调用点因此不受影响）。
   */
  fun onProbeFailure(
    context: Context,
    consecutiveFailures: Int,
    evidence: RollbackEvidence = RollbackEvidence.NONE,
  ): Boolean {
    val now = System.currentTimeMillis()
    val refusal = rollbackEvidenceRefusal(evidence)
    if (refusal != null) {
      // 只在到过触发阈值时落叙述，避免每 5s 一拍刷爆观测文件。
      if (consecutiveFailures >= TRIGGER_CONSEC_FAILURES && !evidenceNoted) {
        evidenceNoted = true
        record(context, "evidence-veto failures=" + consecutiveFailures + " reason=" + refusal)
        Log.i(TAG, refusal)
      }
      return false
    }
    evidenceNoted = false
    return when (decide(consecutiveFailures, now, lastUndoAt(context), armedAt(context))) {
      GateDecision.IDLE -> false
      GateDecision.SUPPRESS -> {
        // 只记一次：重试窗口内每 5s 一拍，逐拍落盘会刷爆观测文件（真实原因由 .undo-auto-done 承载）。
        if (!suppressNoted) {
          suppressNoted = true
          record(context, "suppressed retry-window failures=" + consecutiveFailures + " lastUndoAt=" + lastUndoAt(context))
        }
        false
      }
      GateDecision.ARM -> {
        suppressNoted = false
        val armed = try {
          armFile(context).writeText(now.toString())
          true
        } catch (t: Throwable) {
          Log.e(TAG, "auto-undo arm failed", t)
          record(context, "arm-failed " + (t.message ?: t.javaClass.simpleName))
          false
        }
        if (armed) {
          Log.i(TAG, "auto-undo armed at $now; waiting $WATCH_MS ms")
          record(context, "armed failures=" + consecutiveFailures + " watchMs=" + WATCH_MS)
        }
        false
      }
      GateDecision.WAIT -> false
      GateDecision.EXECUTE -> {
        suppressNoted = false
        record(context, "trigger failures=" + consecutiveFailures)
        true
      }
    }
  }

  /**
   * 自动回撤闸门的**纯决策**（JVM 可直接单测，不依赖 Context / 文件系统）。
   *
   * 抽出来的理由：本闸门是「自动 undo 到底会不会跑」的唯一判据，而它原先整体依赖
   * Context + 文件读，导致这段决定「是否自救」的逻辑**零测试覆盖**——`planTick` 的熔断锁存
   * 盲区正是同类「恢复判据无防线」的产物。把四态判定独立成纯函数后可直接断言。
   *
   * 语义（与 [onProbeFailure] 逐条对应）：
   * - [GateDecision.IDLE]     未达触发阈值（[TRIGGER_CONSEC_FAILURES]），不动作；
   * - [GateDecision.SUPPRESS] 距上次成功执行不足 [RETRY_WINDOW_MS]，防循环；
   * - [GateDecision.ARM]      首次确认死亡：起 [WATCH_MS] 观察窗，留给引擎自愈的最后机会；
   * - [GateDecision.WAIT]     观察窗未走完；
   * - [GateDecision.EXECUTE]  观察窗走完仍不健康 → 放行执行。
   *
   * @param consecutiveFailures 看门狗给出的连续确认死亡拍数
   * @param nowMs 当前墙钟毫秒
   * @param lastUndoAtMs 上次自动 undo 成功时刻（无则 null）
   * @param armedAtMs 观察窗起始时刻（未起窗则 null）
   */
  internal fun decide(
    consecutiveFailures: Int,
    nowMs: Long,
    lastUndoAtMs: Long?,
    armedAtMs: Long?,
  ): GateDecision = when {
    consecutiveFailures < TRIGGER_CONSEC_FAILURES -> GateDecision.IDLE
    lastUndoAtMs != null && nowMs - lastUndoAtMs < RETRY_WINDOW_MS -> GateDecision.SUPPRESS
    armedAtMs == null -> GateDecision.ARM
    nowMs - armedAtMs < WATCH_MS -> GateDecision.WAIT
    else -> GateDecision.EXECUTE
  }

  /** [decide] 的取值域。 */
  internal enum class GateDecision { IDLE, ARM, WAIT, SUPPRESS, EXECUTE }

  // ── 可观测性（0.14.1）：不受 DevLogPrefs 闸门的独立落盘 ─────────────────────
  //
  // 为什么需要独立通道：`LogCollector.log` 在 appContext == null 时直接 return，而 appContext 仅在
  // `LogCollector.start` 内设置、后者受 `DevLogPrefs.isEnabled` 闸门且**默认 false** —— 于是默认
  // 设备上「自动 undo 是否触发 / 是否成功」这类看门狗叙述行**全部落空**，用户无从判断回撤跑没跑
  // （这正是「感觉自动 undo 失效了」的直接来源）。此处按 `.undo-auto-done` marker 的既有范式，
  // 写一条**进程私有但恒在**的判据文件：run-as 可读，不依赖任何调试开关。
  //
  // 边界：不碰 LogCollector / boot-diag.log（T6 写面），自带独立文件与轮转。
  private const val ATTEMPT_FILE = "undo-gate.log"
  private const val ATTEMPT_MAX_BYTES = 64L * 1024

  /** 观测行前缀（设备侧单条 grep；与 boot-segments 同类，机器可判）。 */
  const val ATTEMPT_MARK = "dsh-undo-gate"

  /** 本进程是否已记过「被重试窗口抑制」（每纪元只记一次）。 */
  @Volatile private var suppressNoted = false

  /** 证据否决的叙述只落一次（与 [suppressNoted] 同款：避免每 5s 一拍刷爆观测文件）。 */
  @Volatile private var evidenceNoted = false

  /**
   * 落一条闸门观测（best-effort，绝不抛）。**不经 DevLogPrefs 闸门**——它必须在默认配置下可见。
   * 超限轮转一代，与 boot-diag.log 同口径。
   */
  private fun record(context: Context, message: String) {
    val line = ATTEMPT_MARK + " at=" + System.currentTimeMillis() + " " + message.replace('\n', ' ')
    // 双写：LogCollector 打开时进统一日志面（顺序与上下文在一起），关闭时下面的文件仍在。
    try { LogCollector.log(TAG, message) } catch (_: Throwable) {}
    try {
      val f = File(context.filesDir, ATTEMPT_FILE)
      if (f.length() > ATTEMPT_MAX_BYTES) {
        val prev = File(context.filesDir, ATTEMPT_FILE + ".1")
        try { prev.delete() } catch (_: Throwable) { /* 旧代删不掉不影响本轮追加 */ }
        try { f.renameTo(prev) } catch (_: Throwable) { /* 轮转失败则继续追加 */ }
      }
      f.appendText(line + "\n")
    } catch (_: Throwable) {
      // 观测面本身不得成为故障源（磁盘满 / 目录不可写）。
    }
  }

  /** 观察窗起始时刻（无则 null）。 */
  private fun armedAt(context: Context): Long? =
    armFile(context).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()

  /**
   * 记录「已知良好快照 id」——**只由壳侧自己的健康观测写入**（引擎探活 HEALTHY 的那一拍）。
   *
   * 为什么必须由壳侧判：急救 CLI 的 `restore-last-good` 目标是插件自报的
   * `boot-state.json.lastGoodAt`，而那个 `ok=true` 是**插件 apply 阶段/30s 定时**写的，并不代表
   * 引擎整体起来了（2026-09-21 设备实测：注入坏插件后，引擎起不来，而 boot-state 仍写 ok、
   * `lastGoodAt` 前移到崩溃那次启动 ⇒ `restore-last-good` 选中的正是**含坏配置的那份快照**，
   * 回滚把坏配置原样写回，日志却报 `executed ok`）。
   * 壳侧的唯一硬证据是**引擎 HTTP 活着**（看门狗 5s 一拍的两级探活）——那就用它来定义「好」。
   *
   * 幂等且廉价：一次目录列举 + 一次字符串比较；值不变不落盘（5s 一拍，不刷文件）。
   */
  fun noteHealthy(context: Context, engine: EngineManager) {
    val fp = installFingerprint(context)
    val patch = PluginMounts.patchFile(engine)
    // 清单式回滚的两份清单都在这里维护（与「已知良好」同一时刻：**壳侧确认健康的那一拍**）：
    //  - 硬清单：安装指纹变化（新装/升级）即把本版本自带的插件并进去（只增不减，绝不被拔）
    //  - 软清单：只在挂载清单**有变化**时写（没变化直接跳过），它回答「故障是不是清单变化引起的」
    if (PluginMounts.ensureHard(context, patch, fp)) {
      record(context, "hard-manifest updated names=" + PluginMounts.hardNames(context).size + " fp=" + (fp?.take(12) ?: "none"))
    }
    if (PluginMounts.noteHealthy(context, patch)) {
      record(context, "soft-manifest updated names=" + (PluginMounts.softNames(context)?.size ?: 0))
    }
    val id = newestSnapshotId(autoSnapshotIds(engine)) ?: return
    // 无安装指纹就不记：没有指纹就无法回答「这份快照属于哪次安装」，而跨版本的配置回滚正是要禁止的
    // （见 [knownGoodUsable]）——宁可退回「不自动回滚」，也不做一次归属不明的写回。
    if (fp == null) return
    if (id == knownGoodId(context) && fp == knownGoodFp(context)) return
    try {
      knownGoodFile(context).writeText(id + "\n" + fp + "\n")
      record(context, "known-good snapshot=" + id + " fp=" + fp.take(12))
    } catch (t: Throwable) {
      Log.e(TAG, "known-good write failed", t)
    }
  }

  /**
   * 纯逻辑：这份「已知良好」记录能否用于**本次安装**的回滚（JVM 单测覆盖）。
   *
   * 版本护栏（2026-09-21 用户追问「已有插件与新插件冲突时，会不会为了救旧插件而把新版本的改动回退掉」
   * 的正面回答）：只有当记录里的安装指纹与当前安装一致时才允许写回配置。否则会出现一种最坏的混合态：
   * **新版本的代码（APK 已装）+ 旧版本的配置（快照写回）**——新版本的补丁/挂载项被静默删掉，
   * 用户看到的是「升级后功能反而没了」，且无从归因。跨版本时正确的恢复路径是内嵌快照重解包/工厂复位，
   * 不是把上一次安装的配置写回。
   *
   * @return 可用的快照 id；不可用（跨版本 / 记录缺指纹 / id 为空）返回 null。
   */
  internal fun knownGoodUsable(storedId: String?, storedFp: String?, currentFp: String?): String? =
    storedId?.takeIf { it.isNotEmpty() && !storedFp.isNullOrEmpty() && storedFp == currentFp }

  /** 当前安装的快照指纹（`files/.snapshot-fingerprint`，EngineManager 写入；缺失返回 null）。 */
  private fun installFingerprint(context: Context): String? =
    File(context.filesDir, ".snapshot-fingerprint")
      .takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

  /** 快照目录里的 id 列表（排除 `boot-state.json` / `env-vault` 等非快照条目）。 */
  private fun autoSnapshotIds(engine: EngineManager): List<String> {
    val dir = File(File(engine.homeDir, ".dsh/undo-snapshots"), "auto")
    return try {
      dir.list()?.toList() ?: emptyList()
    } catch (t: Throwable) {
      Log.w(TAG, "snapshot dir unreadable: " + t.message)
      emptyList()
    }
  }

  /**
   * 纯逻辑：取最新快照 id（JVM 单测覆盖）。
   *
   * id 形如 `20260920-235635-524b`（本地时间 + 4 位随机），字典序即时间序；非该形状的名字
   * （`boot-state.json`、`env-vault`、`*.tmp`）一律不认——否则会把状态文件当成快照 id 传给 CLI。
   */
  internal fun newestSnapshotId(names: List<String>): String? =
    names.filter { SNAPSHOT_ID.matches(it) }.maxOrNull()

  /** 已知良好快照 id（无则 null）。 */
  internal fun knownGoodId(context: Context): String? =
    knownGoodFile(context).takeIf { it.exists() }?.readText()?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

  /** 写入该记录时的安装指纹（第二行；无则 null）。 */
  internal fun knownGoodFp(context: Context): String? =
    knownGoodFile(context).takeIf { it.exists() }?.readText()?.lineSequence()?.drop(1)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

  /** 该快照 id 在 auto 目录里是否仍存在（快照会被 prune 掉，选目标前必须核对）。 */
  private fun snapshotExists(context: Context, engine: EngineManager, id: String): Boolean =
    File(File(File(engine.homeDir, ".dsh/undo-snapshots"), "auto"), id).isDirectory

  /**
   * 执行自动 undo（必须后台线程调用）：
   * 1. 急救 CLI 回滚（**优先 `restore <已知良好 id>`**，无可用 id 才退回 `restore-last-good`）
   * 2. 写 .undo-auto-done 标记（幂等 + 供启动页显示）
   * 3. 返回是否执行了回滚（+ 摘要）
   */
  fun execute(context: Context, engine: EngineManager): UndoResult {
    if (!autoUndoRunning.compareAndSet(false, true)) return UndoResult(false, "自动回撤已在执行", null)
    try {
      val dsh = File(engine.homeDir, ".dsh")
      val cli = File(context.filesDir, "undo-emergency.mjs")
      if (!cli.exists()) {
        Log.e(TAG, "auto-undo aborted: emergency CLI not deployed at " + cli.absolutePath)
        record(context, "aborted cli-missing at=" + cli.absolutePath)
        return UndoResult(false, "急救 CLI 未部署", null)
      }
      // 先确认有快照（空库不执行，避免空转）
      val list = runCli(context, engine, cli, dsh, listOf("list"))
      // #211.1：CLI 超时 = 状态未知，绝不判「无快照可回滚」（旧实现两者同形，超时被静默误判为
      // 空库 → 自动回退从未执行）。此分支只是「不执行」并给出可区分的摘要，不吞掉区别。
      if (list.any { it.contains(ProcIo.TIMEOUT_FLAG) }) {
        Log.w(TAG, "auto-undo aborted: emergency CLI list timed out; snapshot state unknown")
        record(context, "aborted list-timeout flag=" + ProcIo.TIMEOUT_FLAG)
        return UndoResult(false, "急救 CLI 超时：快照清单状态未知（非「无快照可回滚」）", null)
      }
      if (!list.any { it.startsWith("2026") || it.startsWith("20") } && !list.any { it.contains("[auto]") }) {
        Log.i(TAG, "auto-undo skipped: no snapshots found")
        record(context, "skipped no-snapshots listLines=" + list.size)
        return UndoResult(false, "无快照可回滚", null)
      }
      // ── 清单式外科修复（2026-09-21 用户拍板）：**先拔坏插件，不做整份回滚** ──────────────
      // 整份回滚会把「最后一次健康启动之后用户装的插件」全部从装配里抹掉（用户明确否决）。
      // 故顺序是：能点名 → 只拔它；点不出名但清单没变 → 才允许走 known-good 整份回滚；
      // 清单变了又点不出名 → 什么都不做（宁可不动，也不吞用户插件）。
      val patch = PluginMounts.patchFile(engine)
      val failed = PluginMounts.failedEntryOf(PluginMounts.readEngineLogTail(context))
      val hard = PluginMounts.hardNames(context)
      if (failed != null && failed.name != null && failed.name !in hard) {
        if (PluginMounts.pull(context, patch, failed)) {
          record(context, "pulled plugin=" + failed.name + " id=" + (failed.id ?: "?"))
          return UndoResult(true, "已从装配里拔出失败插件（其余插件未改动）：" + failed.name, failed.name)
        }
        record(context, "pull failed (block not located) plugin=" + failed.name)
      } else if (failed != null) {
        record(context, "pull skipped: failed entry is in the hard manifest (our own plugin) name=" + (failed.name ?: "?"))
      }
      if (!PluginMounts.mountUnchangedSinceHealthy(context, patch)) {
        record(context, "aborted mount-changed-and-unattributed plugin=" + (failed?.name ?: "?"))
        return UndoResult(false, "插件清单已变化但点名不出失败插件：不做整份回滚（避免连用户其它插件一起回退）", null)
      }
      // 2026-09-21 主修：回滚目标 = **壳侧确认健康那一刻的最新快照**（而不是 CLI 自报的 lastGood），
      // 且**只允许回滚本次安装建立的快照**（跨版本一律不回滚，见 [knownGoodUsable]）。
      // 无可用目标时返回「不执行」并写明理由——旧实现在这里退回 restore-last-good，而设备实测证明
      // 那条路会把**含坏配置的快照**（崩溃那次启动建的）或**上一次安装的配置**写回，日志却是 ok。
      val known = knownGoodUsable(knownGoodId(context), knownGoodFp(context), installFingerprint(context))
      if (known == null) {
        record(
          context,
          "aborted no-known-good-for-this-install stored=" + (knownGoodId(context) ?: "none") +
            "/" + (knownGoodFp(context)?.take(12) ?: "none") + " current=" + (installFingerprint(context)?.take(12) ?: "none"),
        )
        return UndoResult(false, "本次安装尚未建立已知良好快照：不自动回滚（避免把旧版本配置写回）", null)
      }
      if (!snapshotExists(context, engine, known)) {
        record(context, "aborted known-good pruned id=" + known)
        return UndoResult(false, "已知良好快照已被轮转回收（" + known + "）：不自动回滚", null)
      }
      val out = runCli(context, engine, cli, dsh, listOf("restore", known))
      val ok = out.any { it.contains("完成：还原") }
      val summary = out.joinToString("\n")
      if (ok) {
        markerFile(context).writeText(System.currentTimeMillis().toString())
        record(context, "executed ok snapshot=" + (restoreTarget(out) ?: known) + " via=known-good")
      } else {
        Log.e(TAG, "auto-undo failed: " + summary)
        record(context, "executed failed exitSummary=" + summary.take(160).replace('\n', ' '))
      }
      // 0.13.1 W3：急救触发即镜像现场到共享目录（引擎循环崩溃导致用户完全无法取日志的场景）。
      engine.mirrorDiagnosticsToShared("undo-gate")
      return UndoResult(ok, summary, if (ok) restoreTarget(out) else null)
    } finally {
      armFile(context).delete()
      autoUndoRunning.set(false)
    }
  }

  /** 从 CLI 输出提取恢复目标快照 id；解析失败返回 null（不阻断）。 */
  private fun restoreTarget(lines: List<String>): String? {
    val m = lines.firstOrNull { it.contains("恢复快照") }?.let { line ->
      Regex("恢复快照 (\\S+)").find(line)
    }
    return m?.groupValues?.get(1)
  }

  /** 运行急救 CLI（节点为快照内的 node，DSH_HOME 指向壳私有 .dsh）。 */
  private fun runCli(
    context: Context,
    engine: EngineManager,
    cli: File,
    dsh: File,
    args: List<String>,
  ): List<String> {
    return try {
      val cmd = listOf(
        engine.usrDir.absolutePath + "/bin/node",
        cli.absolutePath,
      ) + args
      // #118 根因2（2026-09）：直接 exec app-data ELF 在 Android 15+ 恒 Permission denied
      // （error=13），auto-undo 因此从未真正执行。与 EngineManager.startWithArgs 同款：
      // 捕获 Permission denied 后降级经 /system/bin/linker64 加载（系统库加载机制对
      // app 数据永远放行）。
      fun build(argv: List<String>): ProcessBuilder = ProcessBuilder(argv).apply {
        environment().putAll(engine.shellEnv())
        environment()["DSH_HOME"] = dsh.absolutePath
        environment()["DSH_UNDO_ROOT"] = File(dsh, "undo-snapshots").absolutePath
        environment()["DSH_UNDO_PROFILE"] = "web"
        // 2026-08-23 真机实测：快照 node 编译期 openssl.cnf 路径为 /data/data/com.termux/...（不可读），
        // 缺失该覆盖时 Node 启动致命退出（OpenSSL config error）→ CLI 无输出 → 误判「无快照可回滚」，
        // 自动回退从未执行。显式指向快照内真实 cnf（与 shellEnv 的 SSL_CERT_FILE 同域）。
        environment()["OPENSSL_CONF"] = File(engine.usrDir, "etc/tls/openssl.cnf").absolutePath
        redirectErrorStream(true)
      }
      var proc: Process
      try {
        proc = build(cmd).start()
      } catch (e: java.io.IOException) {
        if (e.message?.contains("Permission denied") != true) throw e
        Log.w(TAG, "direct exec denied, falling back to linker64: " + e.message)
        proc = build(listOf("/system/bin/linker64") + cmd).start()
      }
      // 0.13.8 #173：有界读——CLI 挂起曾令 60s 守卫失效，且 execute 的 autoUndoRunning
      // 只在 finally 复位 → 恒「已在执行」，连看门狗 DEAD 支的强制重启都被锁死。
      // 超时分支显式复位标志与 arm 文件（幂等，防御未来再引入无界读）。
      val r = ProcIo.readBounded(proc, 60)
      if (r.timedOut) {
        proc.destroyForcibly()
        try { armFile(context).delete() } catch (_: Throwable) {}
        autoUndoRunning.set(false)
        return listOf(r.timeoutText("emergency CLI timeout"))
      }
      r.text.lines()
    } catch (t: Throwable) {
      Log.e(TAG, "emergency CLI run failed", t)
      listOf("emergency CLI failed: " + (t.message ?: t.javaClass.simpleName))
    }
  }

  /** Clears a pending observation window after the engine becomes reachable. */
  fun disarm(context: Context) {
    armFile(context).delete()
  }

  /** 清除自动 undo 标记（引擎健康确认/用户手动操作后调用）。 */
  fun clearMarker(context: Context) {
    markerFile(context).delete()
    armFile(context).delete()
  }

  fun armedToDisplay(context: Context): String? = armFile(context).takeIf { it.exists() }?.let {
    "auto-undo pending: " + it.readText()
  }

  /** 快照 id 形状：`yyyyMMdd-HHmmss-rrrr`（本地时间 + 4 位随机；字典序 = 时间序）。 */
  private val SNAPSHOT_ID = Regex("^20[0-9]{6}-[0-9]{6}-[0-9a-f]{4}$")

  private fun markerFile(context: Context) = File(context.filesDir, ".undo-auto-done")
  private fun armFile(context: Context) = File(context.filesDir, ".undo-auto-armed")

  /** 壳侧确认过健康的那份快照 id（[noteHealthy] 写、[execute] 读）。 */
  private fun knownGoodFile(context: Context) = File(context.filesDir, ".undo-known-good")

  /** 上次自动 undo 时间（毫秒）；从未执行返回 null。 */
  private fun lastUndoAt(context: Context): Long? =
    markerFile(context).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()

  data class UndoResult(val executed: Boolean, val summary: String, val snapshotId: String?)
}
