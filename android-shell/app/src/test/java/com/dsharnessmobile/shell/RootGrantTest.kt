package com.dsharnessmobile.shell

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「AI root 权限」授权门（issue #262 方案 A）的决策回归。
 *
 * 纯决策与执行面源码契约；SharedPreferences/Activity 薄壳不进 JVM 测试。
 * 0.14.3：Shizuku-root 与显式授权的应用 su 是替代路径，不是叠加条件。
 *  - Shizuku 路径只认真实通道 uid==0，应用 su 路径只认显式授权；
 *  - 打开前必须先过免责确认门（同意与 versionCode 绑定）；
 *  - 关闭永远允许（撤销不是需要资格的动作）。
 */
class RootGrantTest {

  private fun decision(
    channelUid: Int,
    consentVersionCode: Int,
    currentVersionCode: Int = 100,
    wantOn: Boolean = true,
    rootGranted: Boolean = false,
    granted: Boolean = false,
  ): JSONObject? = RootGrant.decision(
    channelUid = channelUid,
    rootGranted = rootGranted,
    granted = granted,
    consentVersionCode = consentVersionCode,
    currentVersionCode = currentVersionCode,
    wantOn = wantOn,
  )

  @Test
  fun `root channel with valid consent allows turning on`() {
    assertNull(decision(channelUid = 0, consentVersionCode = 100))
  }

  @Test
  fun `non-root channel without alternate su is refused even with consent`() {
    // 通道身份 ≠ 设备是否 root：uid 2000（Shizuku 以 ADB 启动）与读不到（-1）都算非 root，
    // 假绿（已 root 设备上按设备判据放行）是 issue 点名的缺陷形态。
    for (uid in listOf(2000, -1, 10241)) {
      val refusal = decision(channelUid = uid, consentVersionCode = 100)
      assertNotNull("uid=$uid 必须拒绝", refusal)
      assertEquals(RootGrant.CODE_NOT_ROOT_CHANNEL, refusal!!.opt("code"))
    }
  }

  @Test
  fun `missing or stale consent is refused on root channel`() {
    val never = decision(channelUid = 0, consentVersionCode = 0)
    assertNotNull(never)
    assertEquals(RootGrant.CODE_CONSENT_REQUIRED, never!!.opt("code"))

    // 同意与 versionCode 绑定：升级（currentVersionCode 变化）后旧同意失效，需重新确认。
    val upgraded = decision(channelUid = 0, consentVersionCode = 99, currentVersionCode = 100)
    assertNotNull(upgraded)
    assertEquals(RootGrant.CODE_CONSENT_REQUIRED, upgraded!!.opt("code"))
  }

  @Test
  fun `turning off is always allowed regardless of channel or consent`() {
    assertNull(decision(channelUid = 2000, consentVersionCode = 0, wantOn = false))
    assertNull(decision(channelUid = -1, consentVersionCode = 0, wantOn = false))
    assertNull(decision(channelUid = 0, consentVersionCode = 0, wantOn = false))
    assertNull(decision(channelUid = 0, consentVersionCode = 100, wantOn = false, rootGranted = false))
  }

  @Test
  fun `root transports are alternatives not cumulative requirements`() {
    assertNull(decision(channelUid = 0, consentVersionCode = 100, rootGranted = false))
    assertNull(decision(channelUid = -1, consentVersionCode = 100, rootGranted = true))
    assertNull(decision(channelUid = 2000, consentVersionCode = 100, rootGranted = true))
    assertNotNull(decision(channelUid = -1, consentVersionCode = 100, rootGranted = false))
    assertEquals(RootGrant.CODE_CONSENT_REQUIRED,
      decision(channelUid = -1, consentVersionCode = 99, rootGranted = true)!!.opt("code"))
  }

  @Test
  fun `effective execution grant rejects absent revoked and stale consent`() {
    for (current in listOf(-1, 0, 1, 100)) {
      for (granted in listOf(false, true)) {
        for (consent in listOf(-1, 0, 1, 99, 100, 101)) {
          assertEquals("grant=$granted consent=$consent current=$current",
            granted && consent > 0 && consent == current,
            RootGrant.effectiveGrant(granted, consent, current))
        }
      }
    }
  }

  @Test
  fun `fallback accepts only known pre-dispatch unavailability`() {
    for (code in listOf("root-grant-required", "consent-required", "shell-transport-failed",
      "shizuku-unknown", "shizuku-exec-failed", "shizuku-configuration-required",
      "shizuku-identity-failed", "root-maintenance-busy", "repair-result-unknown", "", "exit=1")) {
      assertEquals(code, false, ShellOps.canFallbackBeforeDispatch(code))
    }
    for (code in listOf("shizuku-absent", "shizuku-not-running", "shizuku-denied", "shizuku-prev11",
      "shizuku-user-service-not-bound", "shizuku-user-service-too-old")) {
      assertTrue(code, ShellOps.canFallbackBeforeDispatch(code))
    }
  }

  @Test
  fun `refusals carry guidance`() {
    // 结构化拒绝必须带人话 guidance（页面直接展示，不静默）。
    val refusal = decision(channelUid = 0, consentVersionCode = 0)
    assertTrue(refusal!!.optString("guidance").isNotBlank())
    assertEquals(false, refusal.opt("ok"))
  }

  // ── 源码契约：门的接线不得被无声拆除 ────────────────────────────

  private fun source(name: String): String = listOf(
    File("src/main/java/com/dsharnessmobile/shell", name),
    File("app/src/main/java/com/dsharnessmobile/shell", name),
  ).firstOrNull { it.isFile }?.readText()?.lineSequence()?.filterNot {
    val line = it.trimStart()
    line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")
  }?.joinToString("\n") ?: throw AssertionError("找不到源码 $name")

  private fun body(name: String, signature: String): String {
    val text = source(name)
    val start = text.indexOf(signature)
    if (start < 0) throw AssertionError("找不到成员 $name: $signature")
    val next = Regex("(?m)^  (?:(?:private|internal|override) )?fun ")
      .find(text, start + signature.length)?.range?.first ?: text.length
    return text.substring(start, next)
  }

  private fun assertBefore(text: String, first: String, second: String) {
    val a = text.indexOf(first)
    val b = text.indexOf(second)
    assertTrue("$first 必须在 $second 前，且两处都必须在执行代码中", a >= 0 && b > a)
  }

  @Test
  fun enableRequiresEitherTransportAndCurrentConsentRegardlessOfOldSwitch() {
    for (uid in listOf(-1, 0, 2000, 10241)) {
      for (su in listOf(false, true)) {
        for (oldGrant in listOf(false, true)) {
          for (consent in listOf(-1, 0, 99, 100, 101)) {
            val refusal = decision(uid, consent, rootGranted = su, granted = oldGrant)
            val expectedCode = when {
              uid != 0 && !su -> RootGrant.CODE_NOT_ROOT_CHANNEL
              consent != 100 -> RootGrant.CODE_CONSENT_REQUIRED
              else -> null
            }
            assertEquals("uid=$uid su=$su oldGrant=$oldGrant consent=$consent",
              expectedCode, refusal?.optString("code"))
            assertNull(decision(uid, consent, wantOn = false, rootGranted = su, granted = oldGrant))
          }
        }
      }
    }
  }

  @Test
  fun allShizukuCommandSurfacesAreFencedAndGatedBeforeDispatch() {
    val ready = body("ShizukuTransport.kt", "private fun readyService(")
    assertBefore(ready, "if (applyGate) rootGateRefusal(context)", "ensureBound(context)")
    assertBefore(ready, "if (pv < ShizukuUserServiceBridge.PROTOCOL_VERSION)", "configureIfNeeded(context)")
    assertBefore(ready, "configureIfNeeded(context)", "if (applyGate) actualIdentityRefusal(context, remote)")
    assertBefore(ready, "actualIdentityRefusal(context, remote)", "return remote to null")
    for (name in listOf("runShell", "pullFile", "pushFile", "removeRemote", "runController")) {
      val entry = body("ShizukuTransport.kt", "fun $name(")
      assertTrue("$name 必须在命令 fence 内调用对应内部入口",
        entry.contains("= RootExecutionFence.command(context) {") && entry.contains(name + "Internal(context,"))
    }
    val controller = body("ShizukuTransport.kt", "private fun runControllerInternal(")
    // Controller uses the same pre-bind, v4-ack and actual-identity gateway already checked above.
    assertBefore(controller, "readyService(context)", "lease.beforeRpc(remote)")
    assertTrue("Controller must not bypass the common acknowledged gateway", !controller.contains("ensureBound(context)"))
    assertBefore(controller, "lease.beforeRpc(remote)", "remote.exec(argv,")
  }

  @Test
  fun v4ConfigurationAcknowledgementBindsExactAppIdentityBeforeReady() {
    assertTrue(source("ShizukuUserService.kt").contains("const val PROTOCOL_VERSION = 4"))
    val configure = body("ShizukuTransport.kt", "private fun configureIfNeeded(")
    assertBefore(configure, "remote.protocolVersion() < ShizukuUserServiceBridge.PROTOCOL_VERSION", "remote.configure(uid, root)")
    assertBefore(configure, "remote.configure(uid, root)", "val ack = remote.configuration()")
    for (check in listOf("""ack.getBoolean("ok")""", """ack.getInt("appUid", -1) == uid""",
      """ack.getString("appDataDir") == root""",
      """ack.getInt("protocolVersion", -1) >= ShizukuUserServiceBridge.PROTOCOL_VERSION""")) {
      assertBefore(configure, check, "if (valid) configuredForAge = age")
    }
    assertTrue("未确认或异常不得记为就绪", configure.contains("false"))
  }

  @Test
  fun onlyFixedNativeOwnershipMaintenanceMayBypassAiGate() {
    val text = source("ShizukuTransport.kt")
    assertEquals(1, Regex("""readyService\(context, applyGate = false\)""").findAll(text).count())
    val repair = body("ShizukuTransport.kt", "fun repairOwnership(")
    assertBefore(repair, "RootExecutionFence.maintenance", "readyService(context, applyGate = false)")
    assertBefore(repair, "configureIfNeeded(context)", "remote.repairOwnership(path, maxEntries)")
    val direct = body("ShizukuTransport.kt", "internal fun autoHealOwnershipDirect(")
    assertTrue(direct.contains("= RootExecutionFence.maintenance"))
    assertTrue(direct.contains("""java.io.File(app.applicationInfo.dataDir, "files")"""))
  }

  @Test
  fun effectiveCurrentConsentFeedsBothPrebindAndActualIdentityGates() {
    val grant = body("RootGrant.kt", "fun isGranted(")
    assertTrue(grant.contains("= effectiveGrant("))
    assertTrue(grant.contains("KEY_GRANTED") && grant.contains("KEY_CONSENT_VC") && grant.contains("BuildConfig.VERSION_CODE"))
    val state = body("RootGrant.kt", "fun state(")
    assertTrue(state.contains(""".put("granted", isGranted(context))"""))
    assertTrue(body("ShizukuTransport.kt", "internal fun rootGateRefusal(").contains("RootGrant.isGranted(context)"))
    val actual = body("ShizukuTransport.kt", "private fun actualIdentityRefusal(")
    assertTrue(actual.contains("dispatchIdentity(context, remote).second"))
    val identity = body("ShizukuTransport.kt", "private fun dispatchIdentity(")
    assertTrue(identity.contains("remote.uid()") && identity.contains("!RootGrant.isGranted(context)"))
    assertTrue(identity.contains("uid != 0 && uid != 2000"))
  }

  @Test
  fun suModelDispatchIsFencedAndConsumesEffectiveAiConsentBeforeProcessStart() {
    val entry = body("RootAccess.kt", "fun execRoot(")
    assertTrue(entry.contains("RootExecutionFence.command(context) {"))
    assertTrue(entry.contains("requireAiGrant = true"))
    val dispatch = body("RootAccess.kt", "private fun execPrivileged(")
    assertBefore(dispatch, "if (!isGranted(context))", "ProcessBuilder(su,")
    assertBefore(dispatch, "if (requireAiGrant && !RootGrant.isGranted(context))", "ProcessBuilder(su,")
    val repair = body("RootAccess.kt", "fun repairOwnership(")
    assertTrue(repair.contains("= RootExecutionFence.maintenance"))
  }

  @Test
  fun consentRevokeAndReconsentNeedNoRootAndCannotReviveExpiredGrant() {
    val consent = body("RootGrant.kt", "fun setConsent(")
    assertBefore(consent, "val keepGranted = isGranted(app)", "putBoolean(KEY_GRANTED, keepGranted)")
    assertTrue(consent.contains("putInt(KEY_CONSENT_VC, 0).putBoolean(KEY_GRANTED, false)"))
    assertTrue(consent.contains("""put("ok", true)"""))
    assertTrue("同意记录不是资格探测或 root 执行", !consent.contains("decision(") &&
      !consent.contains("RootAccess.requestGrant(") && !consent.contains("RootAccess.isGranted("))
  }

  @Test
  fun nativeUiRepairSubmitsSingleFlightAndRootGrantReadsSharedSettlement() {
    val bridge = body("AndroidBridge.kt", "fun repairRootOwnership(")
    assertTrue(bridge.contains("RootOwnershipJobs.request(app)"))
    assertTrue("UI 桥不能等 Binder 维护完成", !bridge.contains("runBlocking(") && !bridge.contains("autoHealOwnership("))
    assertTrue(body("RootGrant.kt", "fun state(").contains(""".put("ownership", RootOwnershipJobs.state(context))"""))
    val request = body("RootOwnershipJobs.kt", "fun request(")
    assertTrue(request.contains("val started = start(context)"))
    assertTrue(request.contains("\"repair-started\"") && request.contains("\"repair-running\""))
    assertTrue(!request.contains("await(") && !request.contains("join("))
  }

  /**
   * 免责文档必须在 APK assets 里真实存在（LocalDocs 登记表指向的路径打包漏了 = 白屏，
   * fail-closed 的 missing-asset 分支只有用户真打开时才会暴露——打包期就该钉住）。
   */
  @Test
  fun `disclaimer asset exists in the apk`() {
    val asset = listOf(
      File("src/main/assets/docs/root-disclaimer.html"),
      File("app/src/main/assets/docs/root-disclaimer.html"),
    ).firstOrNull { it.isFile }
    assertNotNull("assets/docs/root-disclaimer.html 必须随包（issue #262 免责门）", asset)
    assertTrue("免责声明不能是空文档", (asset!!.length() > 1024L))
  }

  // ── 回包形状契约（2026-09-30 用户实测：撤销同意显示「失败：原因未在本版登记」）─────

  /**
   * [RootGrant.setConsent] 的回包必须带 `ok: true`：页面结算（settleLinkCall）只认
   * `ok === true`，缺字段会把已成功的写入渲染成失败。持久化面无法在 JVM 直测
   * （SharedPreferences 需要 Context），以源码契约钉住形状——与包名同源契约同款。
   */
  @Test
  fun `setConsent answer carries ok true`() {
    val source = listOf(
      File("src/main/java/com/dsharnessmobile/shell/RootGrant.kt"),
      File("app/src/main/java/com/dsharnessmobile/shell/RootGrant.kt"),
    ).firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 RootGrant.kt")
    val text = source.readText()
    val body = Regex("fun setConsent[\\s\\S]*?\\n  \\}")
      .find(text)?.value ?: throw AssertionError("找不到 setConsent 函数体")
    assertTrue(
      "setConsent 的 return 必须显式 put(\"ok\", true)——页面结算只认 ok===true",
      body.contains("put(\"ok\", true)"),
    )
  }

  /**
   * [RootGrant.setGranted] 的拒绝回包必须同时带 `reason`（= code）：人话翻译只读 reason
   * （user-copy.ts 的 CALL_REASON 唯一真源），只带 code 会落「未在本版登记」兜底。
   */
  @Test
  fun `setGranted refusal carries reason for the copy table`() {
    val source = listOf(
      File("src/main/java/com/dsharnessmobile/shell/RootGrant.kt"),
      File("app/src/main/java/com/dsharnessmobile/shell/RootGrant.kt"),
    ).firstOrNull { it.isFile }
      ?: throw AssertionError("找不到 RootGrant.kt")
    val text = source.readText()
    val body = Regex("fun setGranted[\\s\\S]*?\\n  \\}")
      .find(text)?.value ?: throw AssertionError("找不到 setGranted 函数体")
    assertTrue(
      "拒绝分支必须 put(\"reason\", ...)——CALL_REASON 表按 reason 翻译",
      body.contains("put(\"reason\""),
    )
  }
}
