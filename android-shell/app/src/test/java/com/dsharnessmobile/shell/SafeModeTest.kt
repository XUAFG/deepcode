package com.dsharnessmobile.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 缺陷 D（fx-2）：安全模式状态机的事务纪律与「保留自有插件」判据（纯 JVM，真实文件系统）。
 *
 * 本仓测试面**没有 Mockito**，所以落盘核心只依赖 [File]（`SafeMode.enter/exit/status` 收 File 参数），
 * 测试直接打真实临时目录——与 `ControlAuditTest` / `BootFailLogTest` 同款纪律。
 *
 * 反证方式（每条判据都有对应变异会判红，见回报）：
 *  - 把 `enter` 里「先写状态文件再改 patch」调换成旧顺序 → `crashWindowLeavesNoWayBack` 判红；
 *  - 把 `exit` 的备份校验去掉 → `exitRefusesWhenBackupMissing` 判红；
 *  - 把白名单前缀删掉任一条 → `shippedPluginsSurvive` / `productWhitelistMatchesPatchScriptSource` 判红。
 */
class SafeModeTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /** 一份**逼真的** live patch：含第三方条目 + 全部自有插件 + 组外 config 行。 */
  private fun realisticPatch(extraThirdParty: Boolean = true): String {
    val sb = StringBuilder()
    sb.append("- id: bash-sandbox\n  disabled: true\n")
    sb.append("- insert:\n")
    sb.append("    - id: shell-termux\n      name: '@dsh-android/dsh-shell-termux'\n      config:\n        bashPath: /x/bash\n")
    sb.append("    - id: host-web-compat\n      name: '@dsh-android/dsh-host-web-compat'\n")
    sb.append("    - id: ui-responsive\n      name: '@dsh-android/dsh-client-ui-responsive'\n")
    sb.append("    - id: android-bridge\n      name: '@dsh-android/dsh-android-bridge'\n")
    sb.append("    - id: android-manage\n      name: '@dsh-android/dsh-android-manage'\n")
    sb.append("    - id: android-linux-env\n      name: '@dsh-android/dsh-android-linux-env'\n")
    sb.append("    - id: android-file-open\n      name: '@dsh-android/dsh-android-file-open'\n")
    sb.append("    - id: model-capability\n      name: '@dsh-android/dsh-model-capability'\n")
    sb.append("    - id: android-browser\n      name: '@dsh-android/dsh-android-browser'\n")
    sb.append("    - id: android-vdisplay\n      name: '@dsh-android/dsh-android-vdisplay'\n")
    if (extraThirdParty) {
      sb.append("    - id: dsh-code-diff-viewer\n      name: dsh-code-diff-viewer\n      config:\n        theme: dark\n")
      sb.append("    - id: dsh-find-plugin\n      name: dsh-find-plugin\n")
    }
    sb.append("- insert:\n")
    sb.append("    - id: dsh-undo-savepoint\n      name: dsh-undo-savepoint\n")
    sb.append("- insert:\n")
    sb.append("    - id: dshmarketplace\n      name: dshmarketplace-plugin\n")
    sb.append("- id: client-hmr\n  disabled: true\n")
    sb.append("- id: ptc-runtime\n  config:\n    nodeExecutable: /x/node\n")
    sb.append("- id: llm-pi-ai\n  name: \"@deepseek-ai/dsh-llm-pi-ai\"\n  config:\n    providers: {}\n")
    return sb.toString()
  }

  private fun fixture(): Triple<File, File, File> {
    val root = tmp.root
    val patch = File(root, "profiles/web/cordis.patch.yml")
    patch.parentFile!!.mkdirs()
    val homePatch = File(root, "cordis.patch.yml")
    val autoDir = File(root, "undo-snapshots/auto")
    return Triple(patch, homePatch, autoDir)
  }

  // ── ① 事务纪律：备份缺失/崩溃窗口 ──────────────────────────────────────────

  /**
   * 反证核心（既有缺口）：改了 patch 就必须有**可还原的备份 + 状态**——不能出现「patch 已变、
   * 状态不存在」的中间态。本条按 enter 的**后置条件**断言：enter 成功后 off 一定能整份还原。
   */
  @Test
  fun `enter 之后一定存在可还原的备份与状态文件`() {
    val (patch, homePatch, autoDir) = fixture()
    val original = realisticPatch()
    patch.writeText(original)
    val r = SafeMode.enter(patch, homePatch, autoDir, "t1")
    assertTrue("enter 必须成功：" + r.message, r.ok)
    // ① 状态文件在场（这是 off 能还原的前提）。
    val state = File(autoDir, SafeMode.STATE_FILE)
    assertTrue("必须写状态文件（否则 off 判「未开启」而拒绝还原）", state.isFile)
    // ② 备份必须存在且逐字节等于进入前的原文。
    val backupPath = org.json.JSONObject(state.readText()).getString("backup")
    val backup = File(backupPath)
    assertTrue("备份必须仍然在场", backup.isFile)
    assertEquals("备份必须逐字节等于进入前的原文", original, backup.readText())
    // ③ patch 确实被改写了（否则这个按钮什么也没做）。
    assertFalse("第三方条目应已被摘除", patch.readText().contains("dsh-code-diff-viewer"))
  }

  /** 备份目录不可建 ⇒ **拒绝进入**，且**不写状态文件**（否则 off 会以为开着）。 */
  @Test
  fun `备份写不成功时拒绝进入且不写状态文件`() {
    val (patch, homePatch, _) = fixture()
    val original = realisticPatch()
    patch.writeText(original)
    // 用一个「父路径是普通文件」的 autoDir，制造 mkdirs 必然失败。
    val blocker = File(tmp.root, "blocker")
    blocker.writeText("not a dir")
    val badAuto = File(blocker, "auto")
    val r = SafeMode.enter(patch, homePatch, badAuto, "t2")
    assertFalse("无法建目录时必须拒绝进入", r.ok)
    assertTrue("回执必须给出理由", r.message.isNotBlank())
    assertEquals("拒绝进入时 patch 必须一字未动", original, patch.readText())
    assertFalse("拒绝进入时不得留下状态文件", File(badAuto, SafeMode.STATE_FILE).exists())
  }

  /** 反证：备份被删（模拟崩溃/被清理）⇒ exit **拒绝**且**不动任何文件**。 */
  @Test
  fun `备份缺失时 exit 拒绝且不动任何文件`() {
    val (patch, homePatch, autoDir) = fixture()
    patch.writeText(realisticPatch())
    assertTrue(SafeMode.enter(patch, homePatch, autoDir, "t3").ok)
    val afterEnter = patch.readText()
    // 删掉备份，模拟「备份丢了」。
    val state = File(autoDir, SafeMode.STATE_FILE)
    File(org.json.JSONObject(state.readText()).getString("backup")).delete()
    val r = SafeMode.exit(patch, homePatch, autoDir)
    assertFalse("备份缺失必须拒绝退出", r.ok)
    assertTrue("回执必须说明拒绝原因", r.message.contains("备份缺失"))
    assertEquals("拒绝退出时 patch 必须一字未动", afterEnter, patch.readText())
    assertTrue("状态文件必须保留（否则用户再也看不到安全模式开着）", state.isFile)
  }

  /** 未开启时 exit 必须说「未开启」而不是报错崩溃。 */
  @Test
  fun `未开启时 exit 如实回报未开启`() {
    val (patch, homePatch, autoDir) = fixture()
    autoDir.mkdirs()
    val r = SafeMode.exit(patch, homePatch, autoDir)
    assertFalse("无可退出时 ok=false", r.ok)
    assertTrue("必须说清是「未开启」：" + r.message, r.message.contains("未开启"))
    assertFalse("不得凭空创建文件", patch.exists())
  }

  /**
   * 核心不变量：**enter → exit 后 patch 逐字节等于进入前**。
   *
   * 这是「用户插件不会永久消失」的唯一防线（`PluginMounts.kt` 记载过历史事故）。
   * 反证：把 exit 的 `copyTo(overwrite=true)` 改成「只删我们加的条目」→ 本条判红。
   */
  @Test
  fun `enter 再 exit 后 patch 逐字节等于进入前`() {
    val (patch, homePatch, autoDir) = fixture()
    val original = realisticPatch()
    patch.writeText(original)
    assertTrue(SafeMode.enter(patch, homePatch, autoDir, "t4").ok)
    val r = SafeMode.exit(patch, homePatch, autoDir)
    assertTrue("exit 必须成功：" + r.message, r.ok)
    assertEquals("必须整份还原（含第三方插件条目与全部注释/config）", original, patch.readText())
    assertFalse("状态文件必须被清掉", File(autoDir, SafeMode.STATE_FILE).exists())
  }

  /** home 级 patch 存在时：enter 写、exit 还原（设备上通常不存在，但漏处理会让 home 级永久最小化）。 */
  @Test
  fun `home 级 patch 存在时也要备份并还原`() {
    val (patch, homePatch, autoDir) = fixture()
    patch.writeText(realisticPatch())
    val homeOriginal = "- id: user-home-entry\n  name: some-user-plugin\n"
    homePatch.writeText(homeOriginal)
    assertTrue(SafeMode.enter(patch, homePatch, autoDir, "t5").ok)
    assertFalse("home 级必须被改写", homePatch.readText() == homeOriginal)
    assertTrue(SafeMode.exit(patch, homePatch, autoDir).ok)
    assertEquals("home 级必须整份还原", homeOriginal, homePatch.readText())
  }

  // ── ② 用户口径：保留我们自己的插件 ──────────────────────────────────────────

  /** safe 态下 10 个自有 android 插件必须一个不少（`@dsh-android` 前缀域）。 */
  @Test
  fun `safe 态保留全部十个自有插件`() {
    val before = realisticPatch()
    val after = SafeMode.filterThirdPartyInserts(before)
    for (p in listOf(
      "@dsh-android/dsh-shell-termux",
      "@dsh-android/dsh-host-web-compat",
      "@dsh-android/dsh-client-ui-responsive",
      "@dsh-android/dsh-android-bridge",
      "@dsh-android/dsh-android-manage",
      "@dsh-android/dsh-android-linux-env",
      "@dsh-android/dsh-android-file-open",
      "@dsh-android/dsh-model-capability",
      "@dsh-android/dsh-android-browser",
      "@dsh-android/dsh-android-vdisplay",
    )) {
      assertTrue("自有插件必须保留：$p", after.contains(p))
    }
    assertTrue("undo 必须保留", after.contains("dsh-undo-savepoint"))
    assertTrue("marketplace 必须保留", after.contains("dshmarketplace-plugin"))
  }

  /** 第三方条目必须被摘掉，且摘得**干净**（不留悬空 id、不留空 insert 组）。 */
  @Test
  fun `第三方条目被摘除且不留悬空结构`() {
    val after = SafeMode.filterThirdPartyInserts(realisticPatch())
    assertFalse("第三方 name 必须消失", after.contains("dsh-code-diff-viewer"))
    assertFalse("第三方 name 必须消失", after.contains("dsh-find-plugin"))
    // 它们的 config 行也必须一起走（只删 name 行会留下悬空 config）。
    assertFalse("第三方 config 不得残留", after.contains("theme: dark"))
    // 摘光后的空 insert 组必须整组消失（空 insert 会让引擎 boot 抛）。
    assertFalse(
      "不得留下空 insert 组（空 insert 语义为 null，引擎会抛）",
      Regex("-" + " insert:\\n(?=\\s*(?:- |\\S))", RegexOption.MULTILINE).containsMatchIn(after) &&
        !after.contains("insert:"),
    )
    assertTrue("原有条目结构仍应存在", after.contains("- insert:"))
  }

  /**
   * 组外的一切**一字不动**（禁用位 / config-only 条目 / 用户 providers）：
   * 它们不是「插件」，动它们等于改语义而不是降风险。
   */
  @Test
  fun `组外条目与原样内容一字不动`() {
    val after = SafeMode.filterThirdPartyInserts(realisticPatch())
    assertTrue("禁用位必须保留", after.contains("- id: bash-sandbox\n  disabled: true"))
    assertTrue("禁用位必须保留", after.contains("- id: client-hmr\n  disabled: true"))
    assertTrue("config-only 条目必须保留", after.contains("ptc-runtime"))
    assertTrue("nodeExecutable 必须保留", after.contains("nodeExecutable: /x/node"))
    assertTrue("llm-pi-ai 必须保留", after.contains("llm-pi-ai"))
    assertTrue("用户 providers 必须保留", after.contains("providers: {}"))
  }

  /** 无第三方条目时**逐字节不变**（调用方据此跳过写盘，避免无谓改写与 mtime 漂移）。 */
  @Test
  fun `没有第三方条目时输出逐字节等于输入`() {
    val clean = realisticPatch(extraThirdParty = false)
    assertEquals("无改动即逐字节相同", clean, SafeMode.filterThirdPartyInserts(clean))
  }

  /** 回执里的「摘了几个」必须与实际摘除一致（否则用户拿到自相矛盾的信息）。 */
  @Test
  fun `摘除名单与实际摘除一致`() {
    val before = realisticPatch()
    val removed = SafeMode.removedPluginNames(before)
    assertEquals("两个第三方插件", listOf("dsh-code-diff-viewer", "dsh-find-plugin"), removed)
    val after = SafeMode.filterThirdPartyInserts(before)
    for (name in removed) assertFalse("回执点名的插件必须真的不在结果里：$name", after.contains("name: $name"))
    assertTrue("自有插件不得出现在摘除名单里", removed.none { SafeMode.isShippedPackage(it) })
  }

  // ── ③ 白名单与真源同源 ─────────────────────────────────────────────────────

  /**
   * 白名单必须与 `scripts/patches/apply-patches.mjs` 的 `DSH_MOBILE_SHIPPED_PLUGIN_*` 同口径。
   *
   * 该文件自述「名单就是『谁算我们自己的插件』的单一真源」；Kotlin 侧复述一份常量是既有做法
   * （`PluginMounts`/`FactoryProfilePatch` 同样各自持有本仓口径），漂移由本条钉住。
   * 两侧镜像逐字节相同（铁律 6），故读 apk 侧副本即可。
   */
  @Test
  fun `产品白名单与补丁脚本真源一致`() {
    val candidates = listOf(
      File("scripts/patches/apply-patches.mjs"),
      File("../scripts/patches/apply-patches.mjs"),
      File("dsh-mobile-apk/scripts/patches/apply-patches.mjs"),
    )
    val src = candidates.firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 apply-patches.mjs（白名单真源）；cwd=" + File(".").absolutePath)
    val text = src.readText()
    val prefixes = Regex("DSH_MOBILE_SHIPPED_PLUGIN_PREFIXES\\s*=\\s*\\[([^]]*)]")
      .find(text)?.groupValues?.get(1)
      ?.split(",")?.map { it.trim().trim('\'', '"') }?.filter { it.isNotEmpty() }
      ?: throw AssertionError("真源里找不到 DSH_MOBILE_SHIPPED_PLUGIN_PREFIXES")
    val names = Regex("DSH_MOBILE_SHIPPED_PLUGIN_NAMES\\s*=\\s*\\[([^]]*)]")
      .find(text)?.groupValues?.get(1)
      ?.split(",")?.map { it.trim().trim('\'', '"') }?.filter { it.isNotEmpty() }
      ?: throw AssertionError("真源里找不到 DSH_MOBILE_SHIPPED_PLUGIN_NAMES")
    assertEquals("前缀白名单必须与真源一致", prefixes, SafeMode.SHIPPED_PREFIXES)
    assertEquals("具名白名单必须与真源一致", names, SafeMode.SHIPPED_NAMES)
  }

  @Test
  fun `白名单判据的正反例`() {
    assertTrue(SafeMode.isShippedPackage("@dsh-android/dsh-shell-termux"))
    assertTrue(SafeMode.isShippedPackage("@deepseek-ai/dsh-llm-pi-ai"))
    assertTrue(SafeMode.isShippedPackage("'dsh-undo-savepoint'"))
    assertTrue(SafeMode.isShippedPackage("dshmarketplace-plugin"))
    assertFalse("第三方不得被当自有", SafeMode.isShippedPackage("dsh-code-diff-viewer"))
    assertFalse(SafeMode.isShippedPackage("dsh-find-plugin"))
    assertFalse("空串", SafeMode.isShippedPackage(""))
    assertFalse("前缀相似但不同域", SafeMode.isShippedPackage("@dsh-android-not/foo"))
  }

  /** status 三态回执：未开启 / 开启中 / 状态文件损坏——三者必须可区分。 */
  @Test
  fun `status 三态回执可区分`() {
    val (patch, homePatch, autoDir) = fixture()
    autoDir.mkdirs()
    assertTrue(SafeMode.status(autoDir).message.contains("未开启"))
    patch.writeText(realisticPatch())
    assertTrue(SafeMode.enter(patch, homePatch, autoDir, "t6").ok)
    val on = SafeMode.status(autoDir)
    assertTrue("开启后必须说开启中：" + on.message, on.message.contains("开启中"))
    assertTrue("必须带档 id（可追溯）", on.message.contains("t6"))
    File(autoDir, SafeMode.STATE_FILE).writeText("{ 坏 json")
    val broken = SafeMode.status(autoDir)
    assertTrue("损坏必须与「未开启」区分：" + broken.message, broken.message.contains("无法解析"))
  }

  // ── ④ 剪贴板 prompt（纯函数）──────────────────────────────────────────────

  /** prompt 必须含报错**原文**（不得截断成摘要）+ 两条关键约束 + 不承诺修复。 */
  @Test
  fun `prompt 含报错原文与两条关键约束`() {
    val detail = "EngineManager.startEngine() 返回 false（未能拉起引擎进程）"
    val tail = "dsh-boot-fail stage=engine-start-false at=1 fingerprint=abc\\njava.lang.IllegalStateException: boom"
    val p = buildSafeModePrompt("engine-start-false", detail, tail, safeModeActive = true)
    assertTrue("必须含 stage", p.contains("engine-start-false"))
    assertTrue("必须含 detail 原文", p.contains(detail))
    assertTrue("必须含日志尾巴原文（不得摘要化）", p.contains("java.lang.IllegalStateException: boom"))
    assertTrue("必须要求保留自有插件（用户口径第二条）", p.contains("保留") && p.contains("@dsh-android/"))
    assertTrue("必须要求先定位真因", p.contains("先定位真因"))
    assertTrue("必须点明安全模式已开启", p.contains("已开启"))
    // 反向：不得出现过度承诺。
    for (banned in listOf("一定能", "必定", "保证", "一定可以", "必然能")) {
      assertFalse("prompt 不得过度承诺（出现「$banned」）：$p", p.contains(banned))
    }
  }

  /** 输入全空也必须产出**可用**的 prompt（现场可能什么日志都没有）。 */
  @Test
  fun `空输入仍产出可用 prompt`() {
    val p = buildSafeModePrompt(null, null, null, safeModeActive = false)
    assertTrue("必须非空", p.isNotBlank())
    assertTrue("必须点明即将开启（而非已开启）", p.contains("即将开启"))
    assertTrue("缺上下文必须如实说是「未记录」而不是编造", p.contains("(未记录)"))
    assertTrue("不得凭空造出报错内容", p.contains("日志尾巴"))
  }

  /**
   * 安全关键：safe 态下 **7 条 `disabled: true` 一条都不能少**（lead 实测清单）。
   *
   * 其中 `client-hmr` 是安全关键项：漏掉它会重开 `/plugins/events` 无鉴权 SSE
   * （依据 `scripts/profile-web.cordis.patch.yml:60-64`）。
   * 本条锁「安全模式不得把上游禁用位恢复成启用」——它只摘第三方插件条目，不动任何禁用位。
   */
  @Test
  fun `safe 态保留全部七条 disabled true`() {
    val before = realisticPatch(extraThirdParty = false).let { clean ->
      // 补齐 lead 点名的 7 条禁用位（fixture 只含其中 2 条，这里补全以便逐条断言）。
      // 注意 trimMargin 的 `|` 必须**紧贴**内容，否则会留下前导空格，
      // 于是断言里的 "- id: x\n  disabled: true" 匹配不上（缩进多一格）。
      clean + listOf(
        "open-in-app",
        "ui-open-in-app",
        "agent-default-model",
        "directory-picker",
        "office-to-pdf",
      ).joinToString("") { "- id: " + it + "\n  disabled: true\n" }
    }
    val after = SafeMode.filterThirdPartyInserts(before)
    for (id in listOf(
      "bash-sandbox",
      "open-in-app",
      "ui-open-in-app",
      "client-hmr",
      "agent-default-model",
      "directory-picker",
      "office-to-pdf",
    )) {
      assertTrue("禁用位必须保留：- id: " + id + " disabled: true", after.contains("- id: " + id + "\n  disabled: true"))
    }
  }
}
