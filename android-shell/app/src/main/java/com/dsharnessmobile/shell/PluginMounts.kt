package com.dsharnessmobile.shell

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件挂载清单（清单式回滚；2026-09-21 用户拍板的设计，取代此前「整份配置快照回滚」）。
 *
 * ## 为什么不做整份回滚
 *
 * 旧修法（`UndoGate` 的 known-good 快照）把整份 `profiles/web/cordis.patch.yml` 写回。用户实测口径的
 * 异议成立：**用户在「最后一次健康启动」之后装的插件会全部从装配里消失**——用一个坏插件换掉用户
 * 全部插件的状态，代价不可接受。挂载清单里我们的 `@dsh-android/<插件名>`、上游的 `@deepseek-ai/<插件名>`、
 * 市场装的 `dshmarketplace-plugin` 是**同形同级**的条目（设备实读确认），所以「按名字前缀区分」不可行。
 *
 * ## 两份清单（只有两份）
 *
 * - **硬清单**（[hardFile]）：**随版本走**——安装/升级那一刻（`.snapshot-fingerprint` 变化时）把当时
 *   patch 里的插件集合记为硬清单，此后**只增不减**。它是「我们自己插入的、肯定没问题、强制保留」的集合，
 *   外科修复**绝不**动它。
 * - **软清单**（[softFile]）：**启动后校验出来的当前可用状态**——只在「挂载清单有变化」且「本次启动
 *   被壳侧探活确认健康」时更新（清单没变直接跳过，不做无谓写入）。它用来回答一句话：
 *   **这次的故障是不是插件清单变化引起的**。
 *
 * ## 决策（在 `UndoGate.execute` 里，先于任何整份回滚）
 *
 * 1. 引擎日志点名了失败的 loader entry（`failed to import loader entry <id> (<包名>)`）→ 该插件不在硬清单
 *    ⇒ **只拔掉它**（删掉承载它的整块），其余条目一字不动；
 * 2. 拔不掉 / 点不出名字，但**挂载清单与软清单一致**（没变过）⇒ 故障与插件无关，才允许走 known-good
 *    整份回滚（此时回滚不会丢任何插件——清单没变）；
 * 3. 清单变了又点不出名字 ⇒ **不自动回滚**，写明理由交给用户（宁可不动，也不做一次会吞掉用户插件的写回）。
 *
 * 纯逻辑（[mountedNames] / [failedEntryOf] / [removeEntry] / [digest]）全部 JVM 可测，见 `PluginMountsTest`。
 */
object PluginMounts {

  /** 硬清单文件名（`files/` 下；指纹变化即重建，只增不减）。 */
  const val HARD_FILE = ".plugin-hard-manifest.json"

  /** 软清单文件名（`files/` 下；健康 + 清单变化才写）。 */
  const val SOFT_FILE = ".plugin-soft-manifest.json"

  /** 壳侧使用的 profile 名（与 `UndoGate` 传给急救 CLI 的 `DSH_UNDO_PROFILE` 同源）。 */
  const val PROFILE = "web"

  /** 挂载清单文件的相对路径（相对 `.dsh/`）。 */
  const val PATCH_REL = "profiles/$PROFILE/cordis.patch.yml"

  /** 日志里点名失败 loader entry 的两种形态（带括号包名 / 只有 id）。 */
  private val LOADER_FAIL_WITH_NAME = Regex("""failed to import loader entry\s+(\S+)\s+\(([^()]+)\)""")
  private val LOADER_FAIL_ID_ONLY = Regex("""failed to import loader entry\s+(\S+)""")

  /** `name: 'x'` / `name: x`（挂载条目的包名行）。 */
  private val NAME_LINE = Regex("""(?m)^\s*name:\s*['"]?([^'"\s][^'"]*?)['"]?\s*$""")

  /**
   * 0.14.2（D12）：**插件条目**的 id 行与 name 行。
   *
   * 真因：清单里的 `name:` 不只有包名——我们的 patch 里 `- id: llm-pi-ai` 这一条带 36 个模型定义，
   * 每个模型都有 `name: MiMo 2.5` 之类的**显示名**；设备实读 59 个 `name:` 里 42 个是显示名、
   * 只有 17 个是包名。旧实现按任意深度的 `name:` 收集，于是「我们的插件集合」被 42 个显示名污染
   * （硬清单里躺着 `DeepSeek V4 Flash` 这种条目，任何「必需插件是否在场」的判定都会被带偏）。
   *
   * 条目判据（与 [FactoryProfilePatch] 的条目模型同源）：
   * - 插件只以 `- id:` / `- name:` （**列表项**）的形式成为装配条目；
   * - 顶层条目为列 0 的 `- `，insert 组的一层子条目为组内**最浅缩进**的 `- `；
   * - 配置块内的 `name:`（任意深度、无 `- ` 前缀）**不是条目**，不计入。
   */

  /** 条目首行（`- id:` / `- name:` 列表项）与条目内 `name` 键行。 */
  private val ITEM_LINE = Regex("""^(\s*)-\s+(id|name):\s*(.*)$""")
  private val ENTRY_NAME_KEY = Regex("""^(\s*)name:\s*(.*)$""")

  /** 顶层条目起始行正则（列 0 的 `- `，后跟空白或行尾）。 */
  private val TOP_ITEM = Regex("""^-(?:\s|$)""")

  /** 顶层条目起始行（`- insert:` / `- id: x` / `-`）。 */
  private val TOP_LEVEL = Regex("""^-\s.*|^-$""")

  fun hardFile(context: Context): File = File(context.filesDir, HARD_FILE)

  fun softFile(context: Context): File = File(context.filesDir, SOFT_FILE)

  /** 当前 profile 的挂载清单文件（引擎 home 下）。 */
  fun patchFile(engine: EngineManager): File = File(File(engine.homeDir, ".dsh"), PATCH_REL)

  /** 一次点名的失败条目：loader entry 的 id（`- id:`）与包名（`name:`），任一可为 null。 */
  data class FailedEntry(val id: String?, val name: String?)

  // ── 纯逻辑 ──────────────────────────────────────────────────────────────

  /**
   * 纯逻辑：挂载清单里的**插件条目名**（`- name:` 列表项，剥掉引号）。
   *
   * 0.14.2（D12）：与 [mountedNames] 的区别是**只认条目**。判据见 [parseEntryNames] 的注释：
   * 配置块内的 `name:`（`llm-pi-ai` 的模型显示名）不是插件条目，不得进入插件集合。
   *
   * 用途：硬/软清单（[`hardNames`][hardNames] / [`softNames`][softNames]）与「必需插件在场」判定。
   */
  fun entryNames(patchText: String): List<String> =
    parseEntryNames(patchText).mapNotNull { it.second }.filter { it.isNotEmpty() }

  /**
   * 条目扫描（与 `FactoryProfilePatch` 的条目模型同源）：
   * - 顶层块 = 列 0 的 `- ` 起头；insert 组的一层子条目 = 组内**最浅缩进**的 `- id:` / `- name:`；
   * - 条目名取「条目首行是 `- name:`」或「条目内缩进 = 条目缩进 + 2 的 `name:` 键」；
   * - 配置块内更深的 `id:` / `name:` 不是条目。
   *
   * @param patchText 清单全文。
   * @returns 条目 (id, name) 列表（id 或 name 可为 null）。
   */
  private fun parseEntryNames(patchText: String): List<Pair<String?, String?>> {
    val lines = patchText.split("\n")
    val blocks = ArrayList<Pair<String, List<String>>>()
    var head: String? = null
    var body = ArrayList<String>()
    for (raw in lines) {
      val line = raw.trimEnd('\r')
      if (TOP_ITEM.containsMatchIn(line)) {
        if (head != null) blocks += head!! to body
        head = line
        body = ArrayList()
      } else if (head != null) {
        body += line
      }
    }
    if (head != null) blocks += head!! to body
    val out = ArrayList<Pair<String?, String?>>()
    for ((headLine, bodyLines) in blocks) {
      val isInsert = Regex("""^- insert:\s*$""").containsMatchIn(headLine)
      val all = ArrayList<String>()
      all += headLine
      all += bodyLines
      var entryIndent = 0
      if (isInsert) {
        val widths = all.mapNotNull { ITEM_LINE.find(it)?.groupValues?.get(1)?.length?.takeIf { w -> w > 0 } }
        if (widths.isEmpty()) continue
        entryIndent = widths.min()
      } else if (ITEM_LINE.find(headLine) == null) {
        continue
      }
      var id: String? = null
      var name: String? = null
      var open = false
      for (line in all) {
        val item = ITEM_LINE.find(line)
        if (item != null && item.groupValues[1].length == entryIndent) {
          if (open) out += id to name
          open = true
          id = if (item.groupValues[2] == "id") item.groupValues[3].trim().trim('\'', '"') else null
          name = if (item.groupValues[2] == "name") item.groupValues[3].trim().trim('\'', '"') else null
          continue
        }
        if (!open) continue
        val key = ENTRY_NAME_KEY.find(line) ?: continue
        if (key.groupValues[1].length == entryIndent + 2 && name == null) {
          name = key.groupValues[2].trim().trim('\'', '"')
        }
      }
      if (open) out += id to name
    }
    return out
  }

  /**
   * 0.14.2（D12）：**必需插件条目是否全部在场**（清单级别，无设备探活）。
   *
   * 在 [entryNames] 上做集合包含判定——配置显示名不会命中（它们不是 `- name:` 列表项），
   * 因此「模型显示名躺在硬清单里」不再能伪造「插件在场」。
   *
   * @param patchText 挂载清单全文。
   * @param required 必需包名集合（注入集 @dsh-android 下的全部包 + vendor 两个固化包）。
   * @returns 缺失的必需包名（空集 = 全部在场）。
   */
  fun missingRequired(patchText: String, required: Collection<String>): List<String> {
    val present = entryNames(patchText).toHashSet()
    return required.filter { it.isNotEmpty() && it !in present }
  }

  /**
   * 0.14.2（D12）：D12 条目里点名的 `requiredPresent` 口径 —— 必需的 **插件条目** 里，哪些已经**在场**。
   *
   * 与 [missingRequired] 互为补集（`present ∪ missing = required`），供调用方按「已满足」正向叙述。
   * 判据同源：只看 [entryNames]（`- name:` 条目），模型显示名不参与。
   *
   * @param patchText 挂载清单全文。
   * @param required 必需包名集合。
   * @returns 已到场的必需包名（集合，便于直接做差）。
   */
  fun requiredPresent(patchText: String, required: Collection<String>): Set<String> {
    val names = entryNames(patchText).toHashSet()
    return required.filter { it.isNotEmpty() && it in names }.toSet()
  }

  /**
   * 纯逻辑：挂载清单里出现的全部 `name:` 值（含配置块内的显示名），剥掉引号。
   *
   * 兼容保留：本函数是**宽松**口径（任意深度的 `name:`），只用于展示/诊断与既有回归断言；
   * 「插件在不在场」一律用 [entryNames] / [missingRequired]。设备实读：59 个 `name:` 里
   * 42 个是 `llm-pi-ai` 的模型显示名。
   */
  fun mountedNames(patchText: String): List<String> =
    NAME_LINE.findAll(patchText)
      .map { it.groupValues[1].trim().trim('\'', '"') }
      .filter { it.isNotEmpty() }
      .toList()

  /** 纯逻辑：内容指纹（sha256 十六进制）。 */
  fun digest(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  }

  /**
   * 纯逻辑：从引擎日志文本里点名失败的 loader entry。
   *
   * 认的是引擎自己的报错原文（设备实读）：
   * `failed to import loader entry dsh-bad-probe (@dsh-android/dsh-bad-probe): INJECTED-BAD-PLUGIN`
   * ——括号里是包名（能精确定位条目），没有括号时退化为只有 entry id（同样能定位 `- id:` 行）。
   */
  fun failedEntryOf(logText: String): FailedEntry? {
    LOADER_FAIL_WITH_NAME.find(logText)?.let {
      return FailedEntry(id = it.groupValues[1], name = it.groupValues[2].trim())
    }
    LOADER_FAIL_ID_ONLY.find(logText)?.let {
      return FailedEntry(id = it.groupValues[1], name = null)
    }
    return null
  }

  /**
   * 纯逻辑：删掉承载指定插件（按包名，退回按 entry id）的**整块**，返回新文本；无法唯一定位返回 null。
   *
   * 块边界：从该 `name:` 行向上找到最近的一条**顶层条目**（`- insert:` / `- id: x`，列 0 起），
   * 向下到下一个顶层条目之前。块内自带注释（缩进行）随之删除；块**之前**的说明注释（列 0 的 `#`）
   * 保留——那些注释属于其后紧邻的条目，误删会破坏下一块的文档。
   *
   * 为什么要按块删而不是删那一行：只删 `name:` 会留下悬空的 `- id:`，引擎仍然按那条装配去 import
   * （坏插件照旧被挂载），等于没拔。
   */
  fun removeEntry(patchText: String, name: String?, id: String?): String? {
    val wanted = listOfNotNull(name?.trim()?.trim('\'', '"'), id?.trim()).filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return null
    val lines = patchText.split("\n").toMutableList()
    val hits = ArrayList<Int>()
    for (i in lines.indices) {
      val trimmed = lines[i].trim()
      if (trimmed.startsWith("name:")) {
        val v = trimmed.removePrefix("name:").trim().trim('\'', '"')
        if (wanted.contains(v)) hits.add(i)
      } else if (trimmed.startsWith("- id:")) {
        val v = trimmed.removePrefix("- id:").trim().trim('\'', '"')
        if (name == null && wanted.contains(v)) hits.add(i)
      }
    }
    if (hits.isEmpty()) return null
    var removed = 0
    // 从后往前删，索引不失效；同一条目命中多次也只删一次（用已删区间去重）。
    for (hit in hits.sortedDescending()) {
      var start = hit
      while (start >= 0 && !TOP_LEVEL.matches(lines[start])) start--
      if (start < 0) return null // 找不到顶层起点：宁可不删，也不猜
      // 0.14.2（D11 同源）：命中行若不是**顶层条目首行**，它就在某个 `- insert:` 组里 —— 此时
      // 删的必须是**那一条子条目**（上溯到同组最近的同层 `- id:` / `- name:` 行），不是整个组。
      // 旧实现无条件删整组 ⇒ 同组里我们自己的硬清单插件被连坐摘掉（实测反证：2 子组里摘
      // host-web-compat 会连带删掉 shell-web-compat 的兄弟 shell-termux）。
      if (start != hit) {
        // 条目边界 = 组内承载命中行的那一条：从 start 之后找到**最后一个** `- id:` / `- name:`
        // 列表项行（就是本条目的首行），条目末行 = 其后第一个缩进不深于它的非空行。
        // 旧实现用命中行自身的缩进当边界：命中 `name:` 行时只删 name 行、留下悬空的 `- id:`
        // （引擎照旧 import）；命中组首 `- id:` 行时又按整组删（连坐）。
        var itemStart = hit
        for (probe in hit downTo start + 1) {
          if (ITEM_LINE.containsMatchIn(lines[probe])) { itemStart = probe; break }
        }
        val itemIndent = lines[itemStart].indexOfFirst { !it.isWhitespace() }
        var itemEnd = itemStart + 1
        while (itemEnd < lines.size) {
          val candidate = lines[itemEnd]
          if (candidate.isBlank()) { itemEnd += 1; continue }
          if (candidate.indexOfFirst { !it.isWhitespace() } <= itemIndent) break
          itemEnd += 1
        }
        while (itemEnd > itemStart + 1 && lines[itemEnd - 1].isBlank()) itemEnd -= 1
        lines.subList(itemStart, itemEnd).clear()
        removed++
        continue
      }
      var end = start + 1
      while (end < lines.size && !TOP_LEVEL.matches(lines[end])) end++
      lines.subList(start, end).clear()
      removed++
    }
    if (removed == 0) return null
    return dropEmptyInsertWrappers(lines).joinToString("\n")
  }

  /**
   * 清理因人肉摘除条目而变空的 `- insert:` 包装行（YAML 会把它解析成 null 条目，引擎 boot 期会抛）。
   * 判据：`- insert:` 行之后、下一个同级或更浅的非空行之前，是否已无任何更深缩进行。
   */
  private fun dropEmptyInsertWrappers(lines: MutableList<String>): MutableList<String> {
    val indent = { line: String -> line.indexOfFirst { !it.isWhitespace() } }
    var index = 0
    while (index < lines.size) {
      if (lines[index].trim() != "- insert:") { index += 1; continue }
      val wrapperIndent = indent(lines[index])
      var hasChild = false
      var probe = index + 1
      while (probe < lines.size) {
        val candidate = lines[probe]
        if (candidate.isBlank()) { probe += 1; continue }
        if (indent(candidate) <= wrapperIndent) break
        hasChild = true
        break
      }
      if (hasChild) { index += 1; continue }
      var end = index + 1
      while (end < lines.size && lines[end].isBlank()) end += 1
      lines.subList(index, end).clear()
      while (index > 0 && lines[index - 1].isBlank()) lines.removeAt(index - 1)
      if (index > 0) index -= 1
    }
    return lines
  }

  // ── 清单读写 ────────────────────────────────────────────────────────────

  /** 硬清单里的插件名（读不到/损坏返回空集：空集时外科修复会拒绝拔任何东西，fail-closed）。 */
  fun hardNames(context: Context): Set<String> = readNames(hardFile(context))

  /** 软清单里的插件名（无软清单返回 null——「从没确认过健康状态」与「确认过且为空」必须可区分）。 */
  fun softNames(context: Context): Set<String>? =
    softFile(context).takeIf { it.exists() }?.let { readNames(it) }

  /** 软清单记录的挂载清单指纹（无则 null）。 */
  fun softDigest(context: Context): String? =
    softFile(context).takeIf { it.exists() }?.let { f ->
      try { JSONObject(f.readText()).optString("digest", "").takeIf { it.isNotEmpty() } } catch (_: Throwable) { null }
    }

  private fun readNames(f: File): Set<String> {
    if (!f.exists()) return emptySet()
    return try {
      val arr = JSONObject(f.readText()).optJSONArray("names") ?: JSONArray()
      (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Throwable) {
      emptySet()
    }
  }

  private fun writeNames(f: File, names: Collection<String>, fingerprint: String?, digest: String?, at: Long) {
    val o = JSONObject()
    o.put("names", JSONArray(names.sorted()))
    if (fingerprint != null) o.put("fingerprint", fingerprint)
    if (digest != null) o.put("digest", digest)
    o.put("at", at)
    f.writeText(o.toString())
  }

  /**
   * 硬清单维护：**安装指纹变化**（新装/升级）时，把当前清单里的插件并入硬清单（只增不减）。
   *
   * 取「并入」而不是「替换」：升级时 patch 里可能已经混着用户自装条目，而把用户条目误判成
   * 「可以拔」是危险方向——宁可少拔（保留原样、交给用户判断），不可错拔。返回值 = 是否发生了更新。
   *
   * 0.14.2（D12）：名单来源从 [mountedNames]（任意深度 `name:`，会把 36 个模型显示名写成
   * 「插件」）改为 [entryNames]（只认 `- name:` 条目）。
   */
  fun ensureHard(context: Context, patch: File, fingerprint: String?): Boolean {
    val fp = fingerprint ?: return false
    val stored = try { JSONObject(hardFile(context).readText()).optString("fingerprint", "") } catch (_: Throwable) { "" }
    if (stored == fp) return false
    val current = try { entryNames(patch.readText()) } catch (_: Throwable) { emptyList() }
    val merged = (hardNames(context) + current).sorted()
    writeNames(hardFile(context), merged, fp, null, System.currentTimeMillis())
    return true
  }

  /**
   * 软清单维护：**只在挂载清单相对上次记录发生变化时**写入（没变化直接跳过）。
   *
   * 调用点必须是「壳侧探活确认健康」的那一拍——软清单的语义是「当前这份清单被证明可用」，
   * 在崩溃的启动上写它等于把坏状态记为良好（这正是旧修法踩过的坑）。
   */
  fun noteHealthy(context: Context, patch: File): Boolean {
    val text = try { patch.readText() } catch (_: Throwable) { return false }
    val d = digest(text)
    if (d == softDigest(context)) return false
    writeNames(softFile(context), entryNames(text), null, d, System.currentTimeMillis())
    return true
  }

  /** 挂载清单是否与软清单一致（一致 = 这次的故障不是插件清单变化引起的）。 */
  fun mountUnchangedSinceHealthy(context: Context, patch: File): Boolean {
    val recorded = softDigest(context) ?: return false
    val text = try { patch.readText() } catch (_: Throwable) { return false }
    return digest(text) == recorded
  }

  /**
   * 外科修复：把点名失败的插件从装配里拔掉（删掉承载它的整块并写回）。
   *
   * @return true = 已拔掉并写回（调用方随后重启引擎）；false = 没有改动（点不出名字/块定位不到/写回失败）。
   */
  fun pull(context: Context, patch: File, failed: FailedEntry): Boolean {
    val text = try { patch.readText() } catch (_: Throwable) { return false }
    val next = removeEntry(text, failed.name, failed.id) ?: return false
    if (next == text) return false
    return try {
      patch.writeText(next)
      true
    } catch (_: Throwable) {
      false
    }
  }

  /** 引擎日志尾部 4KB（loader 失败原文只在这份日志里；与 `WatchdogV2` 同口径）。 */
  fun readEngineLogTail(context: Context, bytes: Int = 4096): String {
    return try {
      val f = File(context.filesDir, "engine.log")
      if (!f.exists()) return ""
      RandomAccessFile(f, "r").use { raf ->
        val len = raf.length()
        val off = (len - bytes).coerceAtLeast(0)
        raf.seek(off)
        val buf = ByteArray((len - off).toInt().coerceAtMost(bytes))
        val n = raf.read(buf)
        String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
      }
    } catch (_: Throwable) {
      ""
    }
  }
}
