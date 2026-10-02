package com.dsharnessmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单式回滚的纯逻辑回归（2026-09-21 用户拍板的设计）。
 *
 * 用户口径：「如果用户装了很多插件，整份回滚可能会全部丢失……采用清单式：随版本更新的硬清单强制保留，
 * 启动后校验出的软清单记录当前可用状态；哪个插件崩溃了，把那个插件拔掉就行，不影响其他插件的状态。」
 *
 * 因此本文件守的三件事：① 能从挂载清单里**认全**插件名（含上游与用户条目）；② 拔除**只删那一块**，
 * 其余条目与文档注释一字不动；③ 能**精确点名**引擎报错的那个插件（点名不出就不许动配置）。
 *
 * 固件取自设备实读的 `profiles/web/cordis.patch.yml`（MuMu x86_64 / 0.14.1-SN-1-17）形态。
 */
class PluginMountsTest {

  /** 设备实读形状的缩略固件：含我们的、上游的、用户的条目 + 块内注释 + 空 insert 残留。 */
  private val fixture = """
    # 顶层说明注释：属于它后面紧邻的那个条目，拔别的块时不得被误删。
    - insert:
        - id: shell-termux
          name: '@dsh-android/dsh-shell-termux'
          config:
            # 块内注释：随本块一起删除
            writeMode: workspace-write
    - insert:
        - id: dsh-undo-savepoint
          name: dsh-undo-savepoint
    # 插件市场（用户装的）
    - insert:
        - id: dshmarketplace
          name: dshmarketplace-plugin
    - id: open-in-app
      disabled: true
    - insert:
        - id: android-bridge
          name: '@dsh-android/dsh-android-bridge'
    - insert:
        - id: agent-default-model-mobile
          name: '@deepseek-ai/dsh-agent-default-model'
    - insert:
    # 0.14.1：已摘除 dsh-model-sync（升级迁移自动清理；本行由 SnapshotTransaction 写入）
  """.trimIndent()

  @Test
  fun 挂载清单必须认全三类条目() {
    val names = PluginMounts.mountedNames(fixture)
    assertTrue("必须是五条 name（id/disabled 行不算）", names.size == 5)
    assertTrue("我们的（带 scope）", names.contains("@dsh-android/dsh-android-bridge"))
    assertTrue("我们的（无 scope，所以前缀区分不了）", names.contains("dsh-undo-savepoint"))
    assertTrue("上游的", names.contains("@deepseek-ai/dsh-agent-default-model"))
    assertTrue("用户装的", names.contains("dshmarketplace-plugin"))
    assertFalse("引号必须剥掉", names.any { it.contains("'") })
    assertFalse("id 行不得当成插件名", names.contains("open-in-app"))
  }

  @Test
  fun 拔除只删那一块且不动别人的块与注释() {
    val after = PluginMounts.removeEntry(fixture, "dshmarketplace-plugin", "dshmarketplace")
    assertNotNull(after)
    val text = after!!
    assertFalse("目标块必须消失", text.contains("dshmarketplace"))
    assertTrue("别的条目一条不少", PluginMounts.mountedNames(text).containsAll(
      listOf("@dsh-android/dsh-shell-termux", "dsh-undo-savepoint", "@dsh-android/dsh-android-bridge", "@deepseek-ai/dsh-agent-default-model"),
    ))
    assertTrue("顶层说明注释不得被误删（它属于后面的块）", text.contains("顶层说明注释"))
    assertTrue("disabled 条目不得受影响", text.contains("disabled: true"))
    assertTrue("空 insert 残留不得受影响", text.contains("# 0.14.1：已摘除 dsh-model-sync"))
    // 只删了它自己：条目数 5 -> 4
    assertEquals(4, PluginMounts.mountedNames(text).size)
  }

  @Test
  fun 块内缩进注释随块删除_顶层注释保留() {
    // 这一条专门钉「注释边界」：块**内**的缩进注释属于该块（随之删除），
    // 块**前**的列 0 注释属于后面那个条目（必须保留，否则会把下一块的文档吃掉）。
    val after = PluginMounts.removeEntry(fixture, "@dsh-android/dsh-shell-termux", "shell-termux")!!
    assertFalse("块内缩进注释必须随块删除", after.contains("块内注释"))
    assertTrue("顶层说明注释必须保留", after.contains("顶层说明注释"))
    assertTrue("同块内的配置字段一并删除", !after.contains("writeMode"))
    assertTrue("其它条目不受影响", after.contains("dshmarketplace-plugin") && after.contains("@dsh-android/dsh-android-bridge"))
    assertEquals(4, PluginMounts.mountedNames(after).size)
  }

  @Test
  fun 拔我们自己的条目也走同一条路() {
    // 硬清单保护发生在调用方（UndoGate 先查 hard 集合）；本函数只管「按名拔块」这一动作。
    val after = PluginMounts.removeEntry(fixture, "@dsh-android/dsh-android-bridge", "android-bridge")
    assertNotNull(after)
    assertFalse(after!!.contains("dsh-android-bridge"))
    assertTrue(after.contains("dshmarketplace-plugin"))
  }

  @Test
  fun 点不出名或定位不到时必须什么都不做() {
    assertNull("名字不在清单里 → null（调用方据此拒绝回滚）", PluginMounts.removeEntry(fixture, "@x/not-there", null))
    assertNull("名与 id 都为空 → null", PluginMounts.removeEntry(fixture, null, null))
    assertNull("空文本 → null", PluginMounts.removeEntry("", "a", null))
    // 只有 name、没有 id 时也能拔（日志里名字最可靠）
    val renamed = fixture.replace("- id: android-bridge", "- id: something-else")
    assertNotNull(PluginMounts.removeEntry(renamed, "@dsh-android/dsh-android-bridge", null))
  }

  @Test
  fun 引擎报错必须能精确点名插件() {
    // 设备实读原文（files/engine.log.1）
    val line = "Error: dsh: plugin tree failed to load: failed to apply loader entry include (cordis:include): " +
      "failed to import loader entry dsh-bad-probe (@dsh-android/dsh-bad-probe): INJECTED-BAD-PLUGIN"
    val e = PluginMounts.failedEntryOf(line)
    assertNotNull(e)
    assertEquals("@dsh-android/dsh-bad-probe", e!!.name)
    assertEquals("dsh-bad-probe", e.id)
    // 只有 id 的退化形态（包名解析不出时引擎照样会打这一句）
    val idOnly = PluginMounts.failedEntryOf("... failed to import loader entry dsh-bad-probe ...")
    assertNotNull(idOnly)
    assertNull("包名解析不出时必须为 null（调用方退回按 id 拔）", idOnly!!.name)
    assertEquals("dsh-bad-probe", idOnly.id)
    // 无关日志不得点名
    assertNull(PluginMounts.failedEntryOf("engine listening on 3080\nall plugins mounted"))
  }

  @Test
  fun 指纹必须稳定且随内容变化() {
    val a = PluginMounts.digest(fixture)
    assertEquals("同内容同指纹", a, PluginMounts.digest(fixture))
    assertTrue("内容变化指纹必须变", a != PluginMounts.digest(fixture + "\n# x"))
    assertEquals("sha256 十六进制 64 位", 64, a.length)
  }

  // ── 0.14.2 D12：显示名不得进入插件集合 ────────────────────────────────

  /** 设备实读形态的 llm-pi-ai 条目：36 个模型显示名，一个都不得进条目名集合。 */
  private val displayNameFixture = """
    - id: llm-pi-ai
      name: "@deepseek-ai/dsh-llm-pi-ai"
      config:
        providers:
          opencode-go:
            models:
              - id: mimo-v2.5
                name: MiMo 2.5
              - id: glm-5.3
                name: GLM-5.3
          xiaomi-token-plan-cn:
            models:
              - id: mimo-v2.5
                name: MiMo-V2.5
    - insert:
        - id: android-manage
          name: '@dsh-android/dsh-android-manage'
    - id: ui-theme
      name: "@deepseek-ai/dsh-client-ui-theme"
      config:
        preference: dark
  """.trimIndent() + "\n"

  @Test
  fun 模型显示名不得进入插件条目名集合() {
    val entries = PluginMounts.entryNames(displayNameFixture)
    assertEquals(
      "只认 - name: 条目（顶层 + insert 子条目）",
      listOf("@deepseek-ai/dsh-llm-pi-ai", "@dsh-android/dsh-android-manage", "@deepseek-ai/dsh-client-ui-theme"),
      entries,
    )
    assertFalse("模型显示名不是插件名", entries.contains("MiMo 2.5"))
    assertFalse(entries.contains("GLM-5.3"))
    assertFalse(entries.contains("MiMo-V2.5"))
    // 宽松口径仍然认得出它们（兼容展示/诊断用途，但不得用于「插件在不在场」判定）
    assertTrue(PluginMounts.mountedNames(displayNameFixture).contains("GLM-5.3"))
  }

  @Test
  fun 必需插件判定用条目名且能抓出缺席() {
    val required = listOf("@dsh-android/dsh-android-manage", "@dsh-android/dsh-android-bridge")
    val missing = PluginMounts.missingRequired(displayNameFixture, required)
    assertEquals("bridge 不在场", listOf("@dsh-android/dsh-android-bridge"), missing)
    assertTrue(PluginMounts.missingRequired(displayNameFixture, listOf("@dsh-android/dsh-android-manage")).isEmpty())
    assertTrue("显示名不得伪造在场", PluginMounts.missingRequired(displayNameFixture, listOf("GLM-5.3")).isNotEmpty())
  }

  // ── 0.14.2 D11：摘除只作用于目标条目，不连坐 ──────────────────────────

  /**
   * D11 反证：两个子条目同处一个 \`- insert:\` 组时，摘除其中一个**不得**连带删掉另一个。
   * 旧实现按「缩进区间里还有没有兄弟」判定，误判即整组消失（同组里我们自己的硬清单插件一起没了）。
   */
  @Test
  fun 同组摘除不得连坐删除兄弟条目() {
    val two = """
      - id: bash-sandbox
        disabled: true
      - insert:
          - id: shell-termux
            name: '@dsh-android/dsh-shell-termux'
          - id: host-web-compat
            name: '@dsh-android/dsh-host-web-compat'
      - id: open-in-app
        disabled: true
    """.trimIndent() + "\n"

    val after = PluginMounts.removeEntry(two, "@dsh-android/dsh-host-web-compat", "host-web-compat")

    assertNotNull(after)
    assertFalse("目标条目必须消失", after!!.contains("host-web-compat"))
    assertTrue("同组兄弟必须一字不动（D11）", after.contains("shell-termux"))
    assertTrue("组包装行必须保留（还剩一个子条目）", after.contains("- insert:"))
    assertTrue(after.contains("bash-sandbox") && after.contains("open-in-app"))
    assertEquals(listOf("@dsh-android/dsh-shell-termux"), PluginMounts.entryNames(after))
  }

  /** 摘掉组内**最后一个**子条目时，\`- insert:\` 空壳必须一并清理（YAML 里它是 null 条目）。 */
  @Test
  fun 摘除组内最后一条时清掉空壳() {
    val one = """
      - insert:
          - id: only-child
            name: 'some-plugin'
      - id: keep-me
        disabled: true
    """.trimIndent() + "\n"

    val after = PluginMounts.removeEntry(one, "some-plugin", "only-child")

    assertNotNull(after)
    assertFalse("空壳 - insert: 必须消失", after!!.contains("- insert:"))
    assertFalse(after.contains("only-child"))
    assertTrue(after.contains("keep-me"))
  }

  @Test
  fun 必需进场集合与缺失集合互补() {
    val required = listOf("@dsh-android/dsh-android-manage", "@dsh-android/dsh-android-bridge", "GLM-5.3")
    val present = PluginMounts.requiredPresent(displayNameFixture, required)
    val missing = PluginMounts.missingRequired(displayNameFixture, required)
    assertEquals(setOf("@dsh-android/dsh-android-manage"), present)
    assertEquals(listOf("@dsh-android/dsh-android-bridge", "GLM-5.3"), missing)
    assertEquals("两口径必须互补", required.toSet(), present + missing.toSet())
  }
}
