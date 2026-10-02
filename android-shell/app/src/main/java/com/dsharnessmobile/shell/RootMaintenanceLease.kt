package com.dsharnessmobile.shell

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/** Durable admission quarantine, not proof that a su client kill terminated a privileged helper. */
internal object RootMaintenanceLease {
  private const val PREFS = "dsh_root_execution_lease"
  private const val KEY_EPOCH = "epoch"
  private const val KEY_STARTED = "startedAt"
  private const val KEY_OPERATION = "operation"
  private val lock = Any()
  private var initialized = false
  private var pendingEpoch: String? = null
  private var startedAt = 0L
  private var operation = ""
  private var owner: Thread? = null
  private var unknown = false

  /** A missing epoch cannot authorize clearing an earlier process's pending work. */
  internal fun newBoot(saved: String?, current: String?): Boolean =
    !saved.isNullOrBlank() && !current.isNullOrBlank() &&
      saved.substringBefore(':') in setOf("boot-id", "boot-count") &&
      saved.substringBefore(':') == current.substringBefore(':') && saved != current

  private fun bootEpoch(context: Context): String? {
    val uuid = try {
      File("/proc/sys/kernel/random/boot_id").inputStream().use { input ->
        val bytes = ByteArray(80)
        val count = input.read(bytes)
        if (count > 0) String(bytes, 0, count, Charsets.US_ASCII).trim() else ""
      }
    } catch (_: Exception) { "" }
    if (Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(uuid)) return "boot-id:" + uuid.lowercase()
    val count = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
    catch (_: Exception) { -1 }
    return count.takeIf { it >= 0 }?.let { "boot-count:$it" }
  }

  private fun restore(context: Context) {
    if (initialized) return
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    pendingEpoch = prefs.getString(KEY_EPOCH, null)
    startedAt = prefs.getLong(KEY_STARTED, 0L)
    operation = prefs.getString(KEY_OPERATION, "privileged-operation").orEmpty()
    if (pendingEpoch != null && newBoot(pendingEpoch, bootEpoch(context)) && prefs.edit().clear().commit()) {
      pendingEpoch = null; startedAt = 0L; operation = ""
    }
    unknown = pendingEpoch != null
    initialized = true
  }

  /** Persist before dispatch. If durability cannot be established, do not launch privileged work. */
  fun begin(context: Context, operation: String): JSONObject? = synchronized(lock) {
    restore(context)
    if (pendingEpoch != null) return@synchronized refusalLocked()
    val epoch = bootEpoch(context) ?: return@synchronized JSONObject().put("ok", false)
      .put("code", "root-maintenance-epoch-unavailable").put("reason", "root-maintenance-epoch-unavailable")
      .put("guidance", "无法确认设备启动标识，本次不派发特权工作。")
    val started = SystemClock.elapsedRealtime().coerceAtLeast(1L)
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!prefs.edit().putString(KEY_EPOCH, epoch).putLong(KEY_STARTED, started)
        .putString(KEY_OPERATION, operation.take(64)).commit()) {
      return@synchronized JSONObject().put("ok", false).put("code", "root-maintenance-lease-unavailable")
        .put("reason", "root-maintenance-lease-unavailable").put("guidance", "特权执行租约不能持久化，本次不派发。")
    }
    pendingEpoch = epoch; startedAt = started; this.operation = operation.take(64)
    owner = Thread.currentThread(); unknown = false
    null
  }

  /** Only the original acknowledged caller may clear its lease; ordinary timeout/error is not acknowledgement. */
  fun finish(context: Context): Boolean = synchronized(lock) {
    restore(context)
    if (pendingEpoch == null || unknown || owner !== Thread.currentThread()) return@synchronized false
    val cleared = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    if (cleared) { pendingEpoch = null; startedAt = 0L; operation = ""; owner = null; unknown = false }
    else unknown = true
    cleared
  }

  fun markUnknown(context: Context, detail: String): JSONObject = synchronized(lock) {
    restore(context)
    unknown = true
    refusalLocked().put("detail", detail.take(96))
  }

  /** null means no pending lease. Same-boot app recreation/restart never proves helper termination. */
  fun outstanding(context: Context?): JSONObject? = synchronized(lock) {
    if (context == null) return@synchronized if (pendingEpoch != null) refusalLocked() else null
    restore(context)
    if (pendingEpoch == null) null else refusalLocked()
  }

  private fun refusalLocked(): JSONObject = JSONObject().put("ok", false)
    .put("code", if (unknown) "repair-result-unknown" else "root-maintenance-busy")
    .put("reason", if (unknown) "repair-result-unknown" else "root-maintenance-busy")
    .put("operation", operation).put("startedAt", startedAt).put("unknown", unknown)
    .put("failures", 1).put("remaining", -1)
    .put("guidance", if (unknown) "特权工作结果不明，已暂停新派发与维护；不能以重启应用或杀 su 客户端当作结算。请重启设备后再试。"
      else "特权工作尚未结算，请等待；本次不重复派发。")
}
