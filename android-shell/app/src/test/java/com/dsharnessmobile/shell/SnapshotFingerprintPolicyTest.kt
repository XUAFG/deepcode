package com.dsharnessmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFingerprintPolicyTest {
  private val sha = "0123456789abcdef".repeat(4)

  @Test fun missingShaNeverMakesAnExistingRuntimeFresh() {
    for (raw in listOf(null, "", "  \n")) {
      val bundled = SnapshotFingerprintPolicy.read(raw)
      assertEquals("snapshot-bundled-sha-missing", bundled.failureCode)
      assertNull(bundled.fingerprint)
      assertFalse(SnapshotFingerprintPolicy.fresh(true, bundled, sha))
      assertFalse(SnapshotFingerprintPolicy.fresh(true, bundled, ""))
    }
  }

  @Test fun malformedShaCannotBecomeTheTransactionFingerprint() {
    for (raw in listOf("not-a-hash", "g".repeat(64), sha.dropLast(1), sha + "  snapshot.tar.xz", sha + "\n" + sha)) {
      val bundled = SnapshotFingerprintPolicy.read(raw)
      assertEquals("snapshot-bundled-sha-invalid", bundled.failureCode)
      assertNull(bundled.fingerprint)
      assertFalse(SnapshotFingerprintPolicy.fresh(true, bundled, raw))
    }
  }

  @Test fun validShaNeedsBothNodeAndTheMatchingDurableCommit() {
    val bundled = SnapshotFingerprintPolicy.read("  " + sha.uppercase() + "\n")
    assertNull(bundled.failureCode)
    assertEquals(sha, bundled.fingerprint)
    assertTrue(SnapshotFingerprintPolicy.fresh(true, bundled, sha))
    assertTrue(SnapshotFingerprintPolicy.fresh(true, bundled, sha.uppercase() + "\n"))
    assertFalse(SnapshotFingerprintPolicy.fresh(false, bundled, sha))
    assertFalse(SnapshotFingerprintPolicy.fresh(true, bundled, null))
    assertFalse(SnapshotFingerprintPolicy.fresh(true, bundled, "f".repeat(64)))
  }

  @Test fun invalidBundledMetadataHasNoLegacyFreshnessOrDegradedStartupBypass() {
    fun source(name: String): String = listOf(
      java.io.File("src/main/java/com/dsharnessmobile/shell", name),
      java.io.File("app/src/main/java/com/dsharnessmobile/shell", name),
    ).first { it.isFile }.readText()
    val manager = source("EngineManager.kt")
    assertTrue(manager.contains("fun snapshotFresh(): Boolean = SnapshotFingerprintPolicy.fresh("))
    assertTrue(manager.contains("fun shouldDegradeRefresh(): Boolean = bundledSnapshotFingerprint.fingerprint != null"))
    assertFalse(manager.contains("if (fp.isEmpty()) return true"))
    val refresh = manager.substringAfter("fun refreshSnapshot(").substringBefore("private fun refreshSnapshotInternal(")
    assertTrue(refresh.contains("snapshotFingerprintProblem()"))
    assertTrue(refresh.contains("refreshSnapshotInternal(onProgress"))
    assertTrue(refresh.indexOf("snapshotFingerprintProblem()") < refresh.indexOf("refreshSnapshotInternal(onProgress"))
    assertTrue(refresh.contains("lastRefreshFailureCode = problem.failureCode"))
    val spawn = manager.substringAfter("fun startEngine(").substringBefore("private fun ")
    assertTrue(spawn.contains("snapshotFingerprintProblem()"))
    assertTrue(spawn.indexOf("snapshotFingerprintProblem()") < spawn.indexOf("snapshotRefreshing.get()"))
    val presentation = source("MainActivity.kt").substringAfter("internal fun showWeb()").substringBefore("internal fun reloadEnginePage()")
    assertTrue(presentation.contains("engineManager.snapshotFingerprintProblem() != null"))
  }
}
