package com.ocubea.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.io.File

/**
 * Real device telemetry for /sensors.json — battery, thermal, storage, memory,
 * network and camera hardware. Every value is read from the platform, nothing
 * is faked.
 */
class DeviceSensors(private val context: Context) {

    fun snapshotJson(port: Int, cameraActive: Boolean): String {
        val b = battery()
        val s = storage()
        val m = memory()
        val n = network()
        val t = thermal() ?: "n/a"
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val ids = cameraIds().joinToString(",") { "\"$it\"" }
        return buildString {
            append("{")
            append("\"battery\":{")
            append("\"level\":${b.level},")
            append("\"charging\":${b.charging},")
            append("\"temperature_c\":${fmt(b.temperatureC)},")
            append("\"voltage_mv\":${b.voltageMv},")
            append("\"health\":\"${b.health}\",")
            append("\"plugged\":\"${b.plugged}\"")
            append("},")
            append("\"storage\":{")
            append("\"total_mb\":${s.totalMb},")
            append("\"free_mb\":${s.freeMb},")
            append("\"path\":\"${s.path.escape()}\"")
            append("},")
            append("\"memory\":{")
            append("\"total_mb\":${m.totalMb},")
            append("\"available_mb\":${m.availableMb},")
            append("\"low_memory\":${m.low}")
            append("},")
            append("\"network\":{")
            append("\"ip\":\"${n.ip.escape()}\",")
            append("\"wifi\":${n.wifi},")
            append("\"metered\":${n.metered},")
            append("\"name\":\"${n.name.escape()}\"")
            append("},")
            append("\"thermal\":{\"status\":\"$t\"},")
            append("\"camera\":{")
            append("\"active\":$cameraActive,")
            append("\"ids\":[$ids],")
            append("\"torch_capable\":${hasTorch()}")
            append("},")
            append("\"device\":{")
            append("\"model\":\"${model.escape()}\",")
            append("\"android\":\"${Build.VERSION.RELEASE}\",")
            append("\"sdk\":${Build.VERSION.SDK_INT},")
            append("\"port\":$port")
            append("}")
            append("}")
        }
    }

    /** Formats a nullable float for JSON, or null when unknown. */
    private fun fmt(v: Float?): String =
        if (v == null) "null" else String.format(java.util.Locale.US, "%.1f", v)

    /** Escapes a string for safe inclusion inside a JSON string literal. */
    private fun String.escape(): String {
        val sb = StringBuilder(length + 8)
        for (c in this) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    // ── Battery ────────────────────────────────────────────────

    data class BatteryInfo(
        val level: Int,
        val charging: Boolean,
        val temperatureC: Float?,
        val voltageMv: Int,
        val health: String,
        val plugged: String
    )

    private fun battery(): BatteryInfo {
        return try {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            BatteryInfo(
                level = pct,
                charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
                    BatteryManager.BATTERY_STATUS_CHARGING ||
                    intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL,
                temperatureC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                    ?.takeIf { it != 0 }?.let { it / 10.0f },
                voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
                health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                },
                plugged = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                    0 -> "none"
                    else -> "unknown"
                }
            )
        } catch (_: Exception) {
            BatteryInfo(-1, false, null, -1, "unknown", "unknown")
        }
    }

    // ── Storage ────────────────────────────────────────────────

    data class StorageInfo(val totalMb: Int, val freeMb: Int, val path: String)

    private fun storage(): StorageInfo = try {
        val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
        val stat = StatFs(dir.absolutePath)
        StorageInfo(
            totalMb = (stat.blockCountLong * stat.blockSizeLong / 1024 / 1024).toInt(),
            freeMb = (stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024).toInt(),
            path = dir.absolutePath
        )
    } catch (_: Exception) {
        StorageInfo(-1, -1, "unknown")
    }

    // ── Memory ─────────────────────────────────────────────────

    data class MemoryInfo(val totalMb: Int, val availableMb: Int, val low: Boolean)

    private fun memory(): MemoryInfo = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        MemoryInfo(
            totalMb = (mi.totalMem / 1024 / 1024).toInt(),
            availableMb = (mi.availMem / 1024 / 1024).toInt(),
            low = mi.lowMemory
        )
    } catch (_: Exception) {
        MemoryInfo(-1, -1, false)
    }

    // ── Network ────────────────────────────────────────────────

    data class NetworkInfo(val ip: String, val wifi: Boolean, val metered: Boolean, val name: String)

    private fun network(): NetworkInfo {
        var ip = ""
        var name = ""
        try {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        ip = addr.hostAddress ?: ""
                        name = nif.name
                        break
                    }
                }
                if (ip.isNotEmpty()) break
            }
        } catch (_: Exception) {}
        var wifi = false
        var metered = false
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(net)
            wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            metered = cm.isActiveNetworkMetered
        } catch (_: Exception) {}
        return NetworkInfo(ip.ifEmpty { "0.0.0.0" }, wifi, metered, name)
    }

    // ── Thermal ────────────────────────────────────────────────

    /** Coarse thermal state — API 29+; null on older devices. */
    private fun thermal(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = pm.currentThermalStatus
            when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                else -> "emergency"
            }
        } catch (_: Exception) { null }
    }

    // ── Camera hardware ────────────────────────────────────────

    private fun cameraIds(): List<String> = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        cm.cameraIdList.toList()
    } catch (_: Exception) { emptyList() }

    fun hasTorch(): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        cm.cameraIdList.any { id ->
            runCatching {
                cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }
    } catch (_: Exception) { false }

    /** Keeps the CPU alive while streaming so the OS does not throttle capture. */
    fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OcuBea:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: Exception) { null }
}
