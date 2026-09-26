package com.ocubea.model

import android.content.Context
import android.content.SharedPreferences
import com.ocubea.model.CameraConfig.Resolution

/**
 * Single source of truth for every tunable setting. SettingsActivity writes,
 * StreamService/StreamServer read — no duplicated defaults anywhere.
 */
class OcuBeaConfig(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    // ── Server ─────────────────────────────────────────────────

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080).coerceIn(1024, 65535)
        set(v) = prefs.edit().putInt(KEY_PORT, v.coerceIn(1024, 65535)).apply()

    /** Empty = open camera (IP Webcam compatible default). */
    var accessToken: String
        get() = prefs.getString(KEY_TOKEN, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).apply()

    // ── Video ──────────────────────────────────────────────────

    var resolution: Resolution
        get() = runCatching {
            Resolution.values()[prefs.getInt(KEY_RESOLUTION_IDX, 2)]
        }.getOrDefault(Resolution.DEFAULT)
        set(v) = prefs.edit().putInt(KEY_RESOLUTION_IDX, v.ordinal).apply()

    var frameRate: Int
        get() = prefs.getInt(KEY_FPS, 15).coerceIn(1, 60)
        set(v) = prefs.edit().putInt(KEY_FPS, v.coerceIn(1, 60)).apply()

    var jpegQuality: Int
        get() = prefs.getInt(KEY_JPEG_QUALITY, 82).coerceIn(40, 100)
        set(v) = prefs.edit().putInt(KEY_JPEG_QUALITY, v.coerceIn(40, 100)).apply()

    var effect: String
        get() = prefs.getString(KEY_EFFECT, "none").orEmpty().ifEmpty { "none" }
        set(v) = prefs.edit().putString(KEY_EFFECT, v).apply()

    var nightVision: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_VISION, false)
        set(v) = prefs.edit().putBoolean(KEY_NIGHT_VISION, v).apply()

    var frontCamera: Boolean
        get() = prefs.getBoolean(KEY_FRONT_CAMERA, false)
        set(v) = prefs.edit().putBoolean(KEY_FRONT_CAMERA, v).apply()

    // ── Audio ──────────────────────────────────────────────────

    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO, true)
        set(v) = prefs.edit().putBoolean(KEY_AUDIO, v).apply()

    // ── Security ───────────────────────────────────────────────

    var securityEnabled: Boolean
        get() = prefs.getBoolean(KEY_SECURITY, false)
        set(v) = prefs.edit().putBoolean(KEY_SECURITY, v).apply()

    var motionSensitivity: Int
        get() = prefs.getInt(KEY_MOTION_SENS, 50).coerceIn(0, 100)
        set(v) = prefs.edit().putInt(KEY_MOTION_SENS, v.coerceIn(0, 100)).apply()

    var motionRecord: Boolean
        get() = prefs.getBoolean(KEY_MOTION_RECORD, true)
        set(v) = prefs.edit().putBoolean(KEY_MOTION_RECORD, v).apply()

    var preRecordSeconds: Int
        get() = prefs.getInt(KEY_PRE_RECORD, 2).coerceIn(0, 10)
        set(v) = prefs.edit().putInt(KEY_PRE_RECORD, v.coerceIn(0, 10)).apply()

    var maxClipSeconds: Int
        get() = prefs.getInt(KEY_MAX_CLIP, 300).coerceIn(10, 3600)
        set(v) = prefs.edit().putInt(KEY_MAX_CLIP, v.coerceIn(10, 3600)).apply()

    // ── System ─────────────────────────────────────────────────

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "OcuBea")?.takeIf { it.isNotBlank() } ?: "OcuBea"
        set(v) = prefs.edit().putString(KEY_DEVICE_NAME, v.trim().ifEmpty { "OcuBea" }).apply()

    var autostart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTOSTART, v).apply()

    /** Drop quality automatically when battery is low or device is hot. */
    var powerSaving: Boolean
        get() = prefs.getBoolean(KEY_POWER_SAVING, true)
        set(v) = prefs.edit().putBoolean(KEY_POWER_SAVING, v).apply()

    // ── Live (non-persisted) toggles ────────────────────────────

    var torchOn: Boolean
        get() = prefs.getBoolean(KEY_TORCH, false)
        set(v) = prefs.edit().putBoolean(KEY_TORCH, v).apply()

    fun snapshot(): Map<String, Any> = mapOf(
        "port" to port,
        "resolution" to "${resolution.width}x${resolution.height}",
        "fps" to frameRate,
        "jpeg_quality" to jpegQuality,
        "effect" to effect,
        "night_vision" to nightVision,
        "front_camera" to frontCamera,
        "audio" to audioEnabled,
        "security" to securityEnabled,
        "motion_sensitivity" to motionSensitivity,
        "motion_record" to motionRecord,
        "pre_record_seconds" to preRecordSeconds,
        "max_clip_seconds" to maxClipSeconds,
        "device_name" to deviceName,
        "autostart" to autostart,
        "power_saving" to powerSaving,
        "auth_required" to accessToken.isNotEmpty()
    )

    companion object {
        const val PREFS = "ocubea"

        const val KEY_PORT = "port"
        const val KEY_TOKEN = "access_token"
        const val KEY_RESOLUTION_IDX = "resolution_idx"
        const val KEY_FPS = "fps"
        const val KEY_JPEG_QUALITY = "jpeg_quality"
        const val KEY_EFFECT = "effect"
        const val KEY_NIGHT_VISION = "night_vision"
        const val KEY_FRONT_CAMERA = "front_camera"
        const val KEY_AUDIO = "audio_enabled"
        const val KEY_SECURITY = "security_enabled"
        const val KEY_MOTION_SENS = "motion_sensitivity"
        const val KEY_MOTION_RECORD = "motion_record"
        const val KEY_PRE_RECORD = "pre_record_seconds"
        const val KEY_MAX_CLIP = "max_clip_seconds"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_POWER_SAVING = "power_saving"
        const val KEY_TORCH = "torch_on"
    }
}
