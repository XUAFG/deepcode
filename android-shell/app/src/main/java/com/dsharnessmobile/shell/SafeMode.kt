package com.dsharnessmobile.shell

import android.content.Context
import java.io.File

/**
 * 启动失败页「安全模式启动」的状态机（缺陷 D / fx-2）。
 *
 * ── 它和 `undo-emergency.mjs safe-mode` 是什么关系 ────────────────────────────
 * 同一个状态文件、同一套备份命名（`<autoDir>/safe-mode.json`、`autoDir/safe-mode-backup-<id>.yml` …），
 * 所以壳内按钮与离线 CLI 看到的是**同一份状态**，不会各说各话。差别只在策略：
 *  - 离线 CLI（vendor 插件同款）把装配最小化成**只剩 undo 一条**；
 *  - 本状态机按用户口径**保留我们自己的插件**——只摘掉第三方插件条目。
 * 用户原话要的是「以 safe 状态运行」且「确保保留我们自己的插件」，
 * 「只剩 undo」会把手机操控/虚拟屏/模型能力一起摘掉，那是把可用性也一起修没了，故不采用。
 *
 * ── 摘谁、留谁（判据故意保守）────────────────────────────────────────────────
 * 只动 `- insert:` 组里的**子条目**，且只摘掉 `name:` 不属于产品白名单的那些。
 * 组外的一切（上游行禁用位、config-only 条、llm-pi-ai 的用户 providers）**一字不动**——
 * 它们不是「插件」，动它们等于改语义而不是降风险。
 * 白名单与 `scripts/patches/apply-patches.mjs` 的 `DSH_MOBILE_SHIPPED_PLUGIN_*` 同源
 * （该文件自述「名单就是『谁算我们自己的插件』的单一真源」）。
 *
 * ── 边界（如实写清，不假装能治百病）──────────────────────────────────────────
 * 安全模式只摘**第三方插件的装配条目**；它不修我们自己的插件坏掉、不修快照损坏、
 * 不修引擎二进制缺失。那些情况按钮救不了——文案如实说，不承诺「一定能修复」。
 *
 * ── 事务纪律（既有缺口的修法）────────────────────────────────────────────────
 * 旧实现在「已最小化 patch」与「写状态文件」之间有一个崩溃窗口：那一刻 patch 已被改写、
 * 状态却不存在 ⇒ 之后 `off` 认为「未开启」而拒绝还原，用户的插件**永久消失**。
 * 本实现把状态文件写在**改 patch 之前**（先落「pending 但备份已就位」的事实，再动 patch），
 * 于是任何时刻崩溃，`off` 都能拿备份整份还原。
 * 备份不成功就**绝不进入**；退出前校验全部备份在位才动任何文件；还原一律整份 copyFile。
 */
internal object SafeMode {

  /** 状态文件名（与离线 CLI / vendor 插件同名同目录，三方共享同一份状态）。 */
  const val STATE_FILE = "safe-mode.json"

  /**
   * 产品自有插件的白名单（与 `scripts/patches/apply-patches.mjs` 的
   * `DSH_MOBILE_SHIPPED_PLUGIN_PREFIXES` / `DSH_MOBILE_SHIPPED_PLUGIN_NAMES` 同口径）。
   *
   * 为什么在 Kotlin 里复述一遍而不是去读那个 .mjs：那个文件在 assets 之外、随补丁脚本分发，
   * 引擎侧的 `PluginMounts`/`FactoryProfilePatch` 同样各自持有本仓口径的常量副本——
   * 这是既有约定。漂移由 `SafeModeTest.productWhitelistMatchesThePatchScriptSource` 钉住
   * （它按源码文本解析那两行，两边不一致即判红）。
   */
  internal val SHIPPED_PREFIXES = listOf("@deepseek-ai/", "@dsh-android/")
  internal val SHIPPED_NAMES = listOf("dsh-undo-savepoint", "dshmarketplace-plugin")

  /** 某个包名是否属于产品自有插件。 */
  internal fun isShippedPackage(name: String): Boolean {
    val v = name.trim().trim('\'', '"')
    if (v.isEmpty()) return false
    if (SHIPPED_PREFIXES.any { v.startsWith(it) }) return true
    return SHIPPED_NAMES.contains(v)
  }

  // ── 纯逻辑：装配清单过滤 ────────────────────────────────────────────────────

  /**
   * 过滤第三方插件的 **insert 子条目**，其余内容逐字保留（纯函数，JVM 可测）。
   *
   * 算法（按行，不做 YAML 解析——与 `PluginMounts.removeEntry` 同风格，避免引入 YAML 依赖）：
   *  - 定位列 0 的 `- insert:` 组；
   *  - 组内子条目 = 组内**最浅缩进**的 `- ` 列表项；
   *  - 子条目若含 `name:` 且该 name 不在白名单 → 整条（含其 config）删除；
   *  - 子条目若**没有** `name:`（例如只有 `- id: xyz`）→ **保留**（无法判定是第三方，
   *    宁可不摘也不误伤；摘错一个我们能跑的东西比漏摘一个更糟）；
   *  - 组内条目全被摘光 → 连同 `- insert:` 包装行一起删（空 insert 会让引擎 boot 抛）。
   *
   * @param patchText 装配清单全文。
   * @returns 过滤后全文；无改动时与输入**逐字节相同**（调用方据此跳过写盘）。
   */
  internal fun filterThirdPartyInserts(patchText: String): String {
    val lines = patchText.split("\n").toMutableList()
    val out = ArrayList<String>(lines.size)
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      if (!TOP_INSERT.containsMatchIn(line)) { out.add(line); i += 1; continue }
      // 收集本组：从 insert 行到下一个列 0 非空行之前。
      var end = i + 1
      while (end < lines.size && !TOP_LEVEL.containsMatchIn(lines[end])) end += 1
      val body = lines.subList(i + 1, end)
      // 组内子条目缩进 = 组内第一个列表项的缩进。
      val itemIndent = body.firstOrNull { ITEM.containsMatchIn(it) }
        ?.let { it.indexOfFirst { c -> !c.isWhitespace() } }
      if (itemIndent == null) { out.add(line); out.addAll(body); i = end; continue }
      val kept = ArrayList<String>()
      for (item in insertChildChunks(body, itemIndent)) {
        val name = itemName(item.lines)
        if (name != null && !isShippedPackage(name)) continue // 第三方条目：整条摘掉
        kept.addAll(item.lines)
      }
      if (kept.none { ITEM.containsMatchIn(it) }) {
        // 组里已无任何子条目：整组（含 insert 行）删掉（空 insert 会让引擎 boot 抛）。
        i = end
      } else {
        out.add(line); out.addAll(kept); i = end
      }
    }
    return out.joinToString("\n")
  }

  private val TOP_LEVEL = Regex("""^-(?:\s|$)""")
  private val TOP_INSERT = Regex("""^- insert:\s*$""")
  private val ITEM = Regex("""^(\s*)-\s+(id|name):""")
  private val NAME_KEY = Regex("""^\s*-?\s*name:\s*['"]?([^'"\s]+)""")
  // ── 事务：enter / exit / status（只依赖 File，JVM 可测）──────────────────────

  /** 操作结果：ok=false 时 message 是给用户看的人话（不得为空）。 */
  internal class Result(val ok: Boolean, val message: String, val id: String? = null)

  /**
   * 进入安全模式：备份成功后**先写状态文件**，再改 patch（修既有崩溃窗口）。
   *
   * 顺序是本方法唯一的承重设计，不得调换：
   *   ① 建 autoDir；② 整份备份 patch（不存在则备份 `[]`）与 home patch（存在才备份）；
   *   ③ **校验备份真的可读且逐字节等于原文件**；④ 写状态文件（此刻起 off 一定能把东西还原回去）；
   *   ⑤ 最后才写过滤后的 patch。
   * 旧实现在 ③ 与 ⑤ 之间崩溃 → patch 已被最小化而状态不存在 → `off` 判「未开启」→ 用户插件永久消失。
   * 本方法任何一步失败都**不写状态文件、不改 patch**，并如实回执。
   *
   * @param patch live 装配清单（`profiles/web/cordis.patch.yml`）。
   * @param homePatch home 级清单（设备上通常不存在——该分支为空操作，但必须保留：
   *   某些布局会写它，漏处理会让 `on` 改了 patch、`off` 却还原不了 home 级）。
   * @param autoDir 快照/急救共用目录（`<home>/.dsh/undo-snapshots/auto`，平铺）。
   * @param id 本次入档 id（生产传时间戳；测试传固定值以便逐字节比对）。
   */
  internal fun enter(patch: File, homePatch: File, autoDir: File, id: String): Result {
    if (!autoDir.exists() && !autoDir.mkdirs()) return Result(false, "无法创建安全模式目录：" + autoDir.absolutePath)
    val backup = File(autoDir, "safe-mode-backup-$id.yml")
    val homeBackup = File(autoDir, "safe-mode-home-backup-$id.yml")
    val stateFile = File(autoDir, STATE_FILE)
    val homeExisted = homePatch.isFile
    return try {
      val original = if (patch.isFile) patch.readBytes() else "[]\n".toByteArray()
      backup.writeBytes(original)
      // ③ 校验：备份必须真的落盘且与原文件逐字节相同（只信 readBytes 的结果，不信任 writeBytes 没抛）。
      if (!backup.isFile || !backup.readBytes().contentEquals(original)) {
        return Result(false, "安全模式备份校验失败（备份与原文不一致），已放弃进入——未改动任何文件")
      }
      if (homeExisted) {
        val homeBytes = homePatch.readBytes()
        homeBackup.writeBytes(homeBytes)
        if (!homeBackup.readBytes().contentEquals(homeBytes)) {
          return Result(false, "安全模式 home 级备份校验失败，已放弃进入——未改动任何文件")
        }
      }
      // ④ 先落状态：此后无论何时崩溃，off 都能凭备份整份还原。
      stateFile.writeText(
        org.json.JSONObject()
          .put("active", true)
          .put("enteredAt", java.time.Instant.now().toString())
          .put("by", "shell-guide-button")
          .put("backup", backup.absolutePath)
          .put("homeBackup", homeBackup.absolutePath)
          .put("homeExisted", homeExisted)
          .put("snapshotId", id)
          .toString(2),
      )
      // ⑤ 最后改 patch。
      val filtered = filterThirdPartyInserts(String(original, Charsets.UTF_8))
      patch.parentFile?.mkdirs()
      patch.writeText(filtered)
      if (homeExisted) homePatch.writeText("# dsh safe mode (home level)\n[]\n")
      val removed = removedPluginNames(String(original, Charsets.UTF_8))
      Result(
        true,
        if (removed.isEmpty())
          "已进入安全模式（本次装配清单里没有第三方插件条目，故清单本身未变；改配置后重启应用生效）。"
        else "已进入安全模式：已摘除 " + removed.size + " 个第三方插件条目（" + removed.joinToString("、") + "），重启应用生效。",
        id,
      )
    } catch (t: Throwable) {
      // 失败回执不得吞掉真因（用户要拿它去修）。
      Result(false, "进入安全模式失败：" + t.javaClass.simpleName + ": " + (t.message ?: "无消息"))
    }
  }

  /**
   * 退出安全模式：**先校验全部备份在位**，再整份 copyFile 还原（唯一防线）。
   *
   * 任一备份缺失/不可读 ⇒ 拒绝退出且**不动任何文件**（宁可停在安全模式，也不做一次
   * 「patch 已写、备份没了」的半还原——那正是用户插件永久消失的形态）。
   */
  internal fun exit(patch: File, homePatch: File, autoDir: File): Result {
    val stateFile = File(autoDir, STATE_FILE)
    if (!stateFile.isFile) return Result(false, "安全模式未开启（没有状态文件），无需退出")
    val st = try { org.json.JSONObject(stateFile.readText()) } catch (t: Throwable) {
      return Result(false, "安全模式状态文件损坏（" + t.javaClass.simpleName + "），已拒绝退出以免误改文件；备份仍在 " + autoDir.absolutePath)
    }
    val backupPath = st.optString("backup", "")
    if (backupPath.isEmpty()) return Result(false, "状态文件缺少 backup 字段，已拒绝退出")
    val backup = File(backupPath)
    val homeExisted = st.optBoolean("homeExisted", false)
    val homeBackup = File(st.optString("homeBackup", ""))
    if (!backup.isFile) {
      return Result(false, "安全模式备份缺失（" + backup.absolutePath + "），已拒绝退出：现在退出会让 patch 停在安全模式内容且无从还原。备份找回后再试。")
    }
    if (homeExisted && !homeBackup.isFile) {
      return Result(false, "安全模式 home 级备份缺失（" + homeBackup.absolutePath + "），已拒绝退出（不动任何文件）")
    }
    return try {
      patch.parentFile?.mkdirs()
      // 整份还原 —— 不用「合并/只删我们加的」，因为任何增量还原都可能留下半态。
      backup.copyTo(patch, overwrite = true)
      if (homeExisted) homeBackup.copyTo(homePatch, overwrite = true)
      stateFile.delete()
      Result(true, "已退出安全模式：装配清单已整份还原到进入前的状态，重启应用生效。")
    } catch (t: Throwable) {
      Result(false, "退出安全模式失败：" + t.javaClass.simpleName + ": " + (t.message ?: "无消息"))
    }
  }

  /** 当前状态。未开启与「状态文件损坏」是两件事，回执必须分开（否则用户以为没开）。 */
  internal fun status(autoDir: File): Result {
    val stateFile = File(autoDir, STATE_FILE)
    if (!stateFile.isFile) return Result(true, "安全模式：未开启")
    return try {
      val st = org.json.JSONObject(stateFile.readText())
      val id = st.optString("snapshotId", "?")
      val at = st.optString("enteredAt", "?")
      Result(true, "安全模式：开启中（进入于 " + at + "，档 " + id + "）")
    } catch (t: Throwable) {
      Result(true, "安全模式：状态文件存在但无法解析（" + t.javaClass.simpleName + "）——请查看 " + stateFile.absolutePath)
    }
  }

  // ── Android 面薄封装（路径解析 + 剪贴板；逻辑全在上面可测的部分）────────────

  /** live 装配清单：`<home>/.dsh/profiles/web/cordis.patch.yml`（与 [PluginMounts.patchFile] 同源）。 */
  internal fun patchFile(engine: EngineManager): File = PluginMounts.patchFile(engine)

  /** home 级清单：`<home>/.dsh/cordis.patch.yml`（设备上通常不存在——空操作分支，但必须处理）。 */
  internal fun homePatchFile(engine: EngineManager): File = File(File(engine.homeDir, ".dsh"), "cordis.patch.yml")

  /** 急救/快照共用目录：`<home>/.dsh/undo-snapshots/auto`（平铺，与 `UndoGate` 同源）。 */
  internal fun autoDir(engine: EngineManager): File = File(File(File(engine.homeDir, ".dsh"), "undo-snapshots"), "auto")

  /** 入档 id（生产用；时间戳 + 短随机，与离线 CLI 同形）。 */
  internal fun newId(): String =
    java.time.Instant.now().toString().replace(Regex("[^0-9]"), "").take(14) +
      "-" + Integer.toHexString(java.util.Random().nextInt(0x10000)).padStart(4, '0')

  /**
   * 读 `boot-fail.log` 尾巴（启动失败的唯一现场快照；失败即回空串，不抛）。
   *
   * 为什么取 boot-fail 而不是 engine.log 优先：前者是「一次启动被**宣判失败**时」的结构化终态
   * （阶段 + 异常栈 + 配置指纹），正是 prompt 需要的上下文；engine.log 是兜底。
   */
  internal fun readFailureContext(context: Context, bytes: Int = 4_000): Pair<String, String> {
    val bootFail = runCatching {
      val f = File(context.filesDir, "boot-fail.log")
      if (!f.isFile) "" else f.readText().takeLast(bytes)
    }.getOrDefault("")
    if (bootFail.isNotBlank()) {
      val stage = Regex("dsh-boot-fail\\s+stage=(\\S+)").find(bootFail)?.groupValues?.get(1) ?: ""
      return stage to bootFail
    }
    val engineTail = runCatching { PluginMounts.readEngineLogTail(context, bytes) }.getOrDefault("")
    return "engine-log-tail" to engineTail
  }

  /**
   * 写完剪贴板（失败返回 false，不抛）。prompt 的成功复制是按钮的**主要交付物**之一，
   * 故调用方必须把 false 如实回执给用户，不得当成无事发生。
   */
  internal fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh-safe-prompt", text))
    true
  }.getOrDefault(false)

  /** 被摘掉的第三方插件名（供回执如实报数；纯函数）。 */
  internal fun removedPluginNames(patchText: String): List<String> {
    val kept = entryNamesOfInsertChildren(patchText)
    return kept.filter { !isShippedPackage(it) }.distinct()
  }

  /** insert 组内的一个子条目（原始行片段）。 */
  private class InsertChild(val lines: List<String>)

  /**
   * 按**条目**切分 insert 组的子条目（组内最浅缩进起始，含其 config 与嵌套内容）。
   *
   * 抽成共用实现是刻意的：过滤（[filterThirdPartyInserts]）与回执（[removedPluginNames]）
   * 必须用**同一套条目边界**，否则会出现「报 0 个但实际摘了 3 个」这种自相矛盾的回执。
   */
  private fun insertChildChunks(body: List<String>, itemIndent: Int): List<InsertChild> {
    val out = ArrayList<InsertChild>()
    var k = 0
    while (k < body.size) {
      val b = body[k]
      val indent = b.indexOfFirst { c -> !c.isWhitespace() }
      if (indent != itemIndent || !ITEM.containsMatchIn(b)) { k += 1; continue }
      var itemEnd = k + 1
      while (itemEnd < body.size) {
        if (body[itemEnd].isBlank()) { itemEnd += 1; continue }
        if (body[itemEnd].indexOfFirst { c -> !c.isWhitespace() } <= itemIndent) break
        itemEnd += 1
      }
      while (itemEnd > k + 1 && body[itemEnd - 1].isBlank()) itemEnd -= 1
      out.add(InsertChild(body.subList(k, itemEnd).toList()))
      k = itemEnd
    }
    return out
  }

  /** 条目的包名：取条目片段里**第一个** `name:`（条目自身的 name 行总在 config 之前）。 */
  private fun itemName(chunk: List<String>): String? =
    chunk.firstNotNullOfOrNull { NAME_KEY.find(it)?.groupValues?.get(1)?.trim('\'', '"') }

  /** insert 组内所有子条目的 `name:`（纯函数；配置块内的显示名不计）。 */
  internal fun entryNamesOfInsertChildren(patchText: String): List<String> {
    val lines = patchText.split("\n")
    val out = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
      if (!TOP_INSERT.containsMatchIn(lines[i])) { i += 1; continue }
      var end = i + 1
      while (end < lines.size && !TOP_LEVEL.containsMatchIn(lines[end])) end += 1
      val body = lines.subList(i + 1, end)
      val itemIndent = body.firstOrNull { ITEM.containsMatchIn(it) }
        ?.let { it.indexOfFirst { c -> !c.isWhitespace() } }
      if (itemIndent != null) {
        for (child in insertChildChunks(body, itemIndent)) itemName(child.lines)?.let { out.add(it) }
      }
      i = end
    }
    return out
  }
}
// ── 剪贴板 prompt 组装（缺陷 D / fx-2；**纯函数**，JVM 可测）────────────────────

/**
 * 组装一段「可直接粘贴给 AI」的修复 prompt（用户口径：进去之后直接复制就能修）。
 *
 * 为什么要壳侧组装而不是让用户自己描述：现场用户手里只有一块失败的屏幕，
 * 让他先看懂 `boot-fail.log`、再想出指令、再描述环境——三件事都做不到才是他卡住的真因。
 * 壳侧同时知道「哪一步失败」（stage）、「原始报错」（detail/栈）与「安全模式已开」（事实），
 * 因此由它拼好一段带完整上下文的指令，是目前唯一不依赖引擎的可靠路径。
 *
 * 纪律（逐条对应既有教训）：
 *  - **报错原文必须整段进 prompt**（不得截断成一句摘要）：反复读日志是上一轮的已知病，
 *    「先定位真因、不要反复重读」这条约束也一并写进去；
 *  - 明确写出**已经被摘掉什么**（安全模式已生效），否则 agent 会把「插件不见了」当成新缺陷；
 *  - 明确要求**保留产品自有插件**（用户口径第二条），防止 agent 顺手把手机操控也删了；
 *  - **不承诺一定能修复**：prompt 只描述事实与约束，不替 agent 下结论；
 *  - 无数字/百分比口径与本轮其它进度文案一致（不涉及进度，但同样避免编造量）。
 *
 * @param stage 失败阶段（`LogCollector.writeBootFail` 的 stage，如 engine-start-false）。
 * @param detail 失败详情（含异常类名/消息；可为空）。
 * @param logTail `boot-fail.log` / `engine.log` 的尾巴（可为空字符串）。
 * @param safeModeActive 生成 prompt 时安全模式是否已开启（决定写「已开启」还是「即将开启」）。
 * @returns 可直接写入剪贴板的 prompt；**非空**（即使输入全空也给出可用的最小指令）。
 */
internal fun buildSafeModePrompt(
  stage: String?,
  detail: String?,
  logTail: String?,
  safeModeActive: Boolean,
): String {
  val sb = StringBuilder()
  sb.append("DSH 启动失败，我已进入安全模式（仅摘除第三方插件，产品自有插件全部保留）。请帮我定位并修复。").append('\n')
  sb.append('\n')
  sb.append("【约束】").append('\n')
  sb.append("1. 先定位真因再动手：读下面的报错现场，确认根因后再改，不要反复重读同一份日志。").append('\n')
  sb.append("2. 必须保留产品自有插件（@dsh-android/* 十个 + dsh-undo-savepoint + dshmarketplace-plugin），不得为了启动成功而删除它们。").append('\n')
  sb.append("3. 安全模式当前：").append(if (safeModeActive) "已开启（第三方插件条目已被摘除，配置改动重启应用后生效）。" else "即将开启。").append('\n')
  sb.append("4. 修好后请告诉我在哪里改了什么、以及如何退出安全模式（dsh safe off）。").append('\n')
  sb.append('\n')
  sb.append("【启动失败现场】").append('\n')
  sb.append("stage: ").append(stage?.takeIf { it.isNotBlank() } ?: "(未记录)").append('\n')
  sb.append("detail: ").append(detail?.takeIf { it.isNotBlank() } ?: "(未记录)").append('\n')
  val tail = logTail?.trim().orEmpty()
  sb.append('\n')
  sb.append("【日志尾巴】").append('\n')
  sb.append(if (tail.isEmpty()) "(未取到日志尾巴；可打开控制台执行 dsh safe status 后再取一次)" else tail).append('\n')
  return sb.toString()
}
