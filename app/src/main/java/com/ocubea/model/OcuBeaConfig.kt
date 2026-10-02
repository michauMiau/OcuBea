package com.ocubea.model

import android.content.Context
import android.content.SharedPreferences
import com.ocubea.model.CameraConfig.Resolution
import com.ocubea.security.MotionSoundPolicy

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

    /**
     * Stream orientation, as pydroid-ipcam names it.
     *
     * The four values are the library's own list, because a client validates
     * against it before sending and a value it offers must not be refused here.
     */
    var orientation: String
        get() = prefs.getString(KEY_ORIENTATION, ORIENTATIONS.first()) ?: ORIENTATIONS.first()
        set(v) = prefs.edit().putString(
            KEY_ORIENTATION, if (v in ORIENTATIONS) v else ORIENTATIONS.first()
        ).apply()

    /** Whether the stream is mirrored horizontally, for `mirror_flip`. */
    var mirrorFlip: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_FLIP, false)
        set(v) = prefs.edit().putBoolean(KEY_MIRROR_FLIP, v).apply()

    /**
     * HLS video bitrate in kbps.
     *
     * The old value was width * height * 4 bits per pixel per second, which
     * for 1080p is 8.3 Mbps. A clip recorded from that stream came to 59 MB per
     * minute — 8.5 GB an hour — for a security camera pointed at a wall, where
     * almost every frame is identical and the encoder was spending bits on noise
     * it invented.
     *
     * 4 Mbps at 1080p is roughly 30 MB a minute and still well above what a
     * 720p-upscale of a static scene needs. The default is expressed in kbps so
     * it survives a resolution change; the encoder is told bits per second.
     */
    var videoBitrateKbps: Int
        get() = BitrateBounds.clampKbps(
            prefs.getInt(KEY_VIDEO_BITRATE_KBPS, DEFAULT_VIDEO_BITRATE_KBPS)
        )
        set(v) = prefs.edit()
            .putInt(KEY_VIDEO_BITRATE_KBPS, BitrateBounds.clampKbps(v))
            .apply()

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

    /**
     * RTSP, off unless the user turns it on.
     *
     * RTSP opens a second listening socket on the LAN, so "off" has to mean
     * there is no port to connect to at all -- not a port that answers and
     * refuses. The socket is only constructed when this is true and closed when
     * it goes false, so switching it off removes the surface instead of hiding
     * it.
     *
     * Default false, unlike audioEnabled, because this one opens a door.
     */
    var rtspEnabled: Boolean
        get() = prefs.getBoolean(KEY_RTSP_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_RTSP_ENABLED, v).apply()

    /**
     * The port the RTSP listener binds.
     *
     * 554 is the registered RTSP port, but an Android app cannot bind below 1024
     * without root, so the default is 8554 -- the de facto RTSP port every camera
     * and player falls back to. pydroid-ipcam takes the port from the URL it is
     * given, so any port works as long as both agree.
     */
    var rtspPort: Int
        get() = prefs.getInt(KEY_RTSP_PORT, DEFAULT_RTSP_PORT)
        set(v) = prefs.edit().putInt(KEY_RTSP_PORT, v.coerceIn(1024, 65535)).apply()

    /**
     * Which audio codec `/audio.<ext>` serves.
     *
     * Stored as an id from `AudioCodecProbe.options`. An empty string means
     * "the user has not chosen", which is different from `"none"`, which means
     * "no audio" -- the first resolves to the device's default, the second
     * makes `/audio.*` answer 404 so a client stops asking. Storing an id
     * rather than a MIME string is what lets a device degrade: the handler
     * resolves the id against the menu the device actually proved it has, and
     * an id that is not in that menu falls back to the default instead of
     * failing.
     */
    var audioCodec: String
        get() = prefs.getString(KEY_AUDIO_CODEC, "") ?: ""
        set(v) = prefs.edit().putString(KEY_AUDIO_CODEC, v).apply()

    /** The stored id, or [defaultId] when the user has not chosen one. */
    fun audioCodecOrDefault(defaultId: String): String {
        val stored = audioCodec
        return if (stored.isBlank()) defaultId else stored
    }

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

    // ── Motion notification sound ────────────────────────────────────
    //
    // Persisted because the tone is played by StreamService from a motion edge,
    // and a flag that lives only in the HTTP handler would be gone the moment
    // the process restarts -- a setting that reverts on its own is the same
    // defect as one that never applied.
    //
    // Default false: a phone that starts chirping on every footstep after an
    // upgrade is a worse outcome than one that stays silent until asked, and
    // the API's own default has always been silent.

    /** Master switch for the audible motion notification (`sound`). */
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, false)
        set(v) = prefs.edit().putBoolean(KEY_SOUND, v).apply()

    /** Whether a motion *edge* emits a tone (`sound_event`). */
    var soundEventEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_EVENT, true)
        set(v) = prefs.edit().putBoolean(KEY_SOUND_EVENT, v).apply()

    /**
     * Shortest gap between two notifications, in seconds (`sound_timeout`).
     *
     * 0 = every event. Bounded by [MotionSoundPolicy] rather than here, so the
     * HTTP layer and this getter cannot accept different ranges.
     */
    var soundTimeoutSeconds: Int
        get() = prefs.getInt(KEY_SOUND_TIMEOUT, 0)
            .coerceIn(MotionSoundPolicy.MIN_TIMEOUT_SECONDS, MotionSoundPolicy.MAX_TIMEOUT_SECONDS)
        set(v) = prefs.edit().putInt(
            KEY_SOUND_TIMEOUT,
            v.coerceIn(MotionSoundPolicy.MIN_TIMEOUT_SECONDS, MotionSoundPolicy.MAX_TIMEOUT_SECONDS),
        ).apply()

    // ── Clip retention ─────────────────────────────────────────
    //
    // Three independent limits, any of which can evict. See ClipRetention for
    // why they are OR-ed rather than combined.

    /** Total bytes clips may occupy. */
    var clipMaxSpaceMb: Int
        get() = prefs.getInt(KEY_CLIP_MAX_MB, 4096).coerceIn(64, 1024 * 64)
        set(v) = prefs.edit().putInt(KEY_CLIP_MAX_MB, v.coerceIn(64, 1024 * 64)).apply()

    /** Age limit in hours. */
    var clipMaxAgeHours: Int
        get() = prefs.getInt(KEY_CLIP_MAX_AGE_H, 24 * 7).coerceIn(1, 24 * 90)
        set(v) = prefs.edit().putInt(KEY_CLIP_MAX_AGE_H, v.coerceIn(1, 24 * 90)).apply()

    /** File-count limit — a directory of 5000 two-second clips is unusable. */
    var clipMaxFiles: Int
        get() = prefs.getInt(KEY_CLIP_MAX_FILES, 500).coerceIn(10, 5000)
        set(v) = prefs.edit().putInt(KEY_CLIP_MAX_FILES, v.coerceIn(10, 5000)).apply()

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

    // ── Behaviour toggles that used to have no storage at all ──────────
    //
    // Each of these answered HTTP 200 and wrote nothing anywhere. There was no
    // field to write to, which is the mechanical reason the setting could not
    // take effect -- and the reason it could not even be reported afterwards,
    // since /config.json had no entry to read.

    /**
     * `awake`: keep the screen on (FLAG_KEEP_SCREEN_ON).
     *
     * Default true, matching the manifest's `android:keepScreenOn="true"` on
     * MainActivity, so a fresh install behaves as it always has. The window flag
     * and this preference are applied together in onResume; the preference alone
     * is what survives a process restart.
     */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, v).apply()

    /** `overlay`: draw time/date/device name onto each video frame. */
    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY, v).apply()

    /**
     * `motion_event`: the motion pipeline armed -- detection, and recording when
     * the user has motion recording on.
     *
     * Default true, because `securityEnabled` defaults false and a false here
     * would make `motion_event=on` a no-op on a fresh install: the setting would
     * store true, report true, and detect nothing, which is the same class of
     * lie this field exists to end.
     */
    var motionEventEnabled: Boolean
        get() = prefs.getBoolean(KEY_MOTION_EVENT, true)
        set(v) = prefs.edit().putBoolean(KEY_MOTION_EVENT, v).apply()

    // ── Live (non-persisted) toggles ────────────────────────────

    var torchOn: Boolean
        get() = prefs.getBoolean(KEY_TORCH, false)
        set(v) = prefs.edit().putBoolean(KEY_TORCH, v).apply()

    fun snapshot(): Map<String, Any> = mapOf(
        "port" to port,
        "resolution" to "${resolution.width}x${resolution.height}",
        "fps" to frameRate,
        "jpeg_quality" to jpegQuality,
        "orientation" to orientation,
        "mirror_flip" to mirrorFlip,
        "video_bitrate_kbps" to videoBitrateKbps,
        "effect" to effect,
        "night_vision" to nightVision,
        "front_camera" to frontCamera,
        "audio" to audioEnabled,
        "audio_codec" to audioCodec,
        "security" to securityEnabled,
        "motion_sensitivity" to motionSensitivity,
        "motion_record" to motionRecord,
        "pre_record_seconds" to preRecordSeconds,
        "max_clip_seconds" to maxClipSeconds,
        // /config.json is where a client reads back what it wrote. These three
        // were absent, so sound=on was applied to nothing and then could not be
        // confirmed from outside -- the same class of lie as device_name.
        "sound" to soundEnabled,
        "sound_event" to soundEventEnabled,
        "sound_timeout" to soundTimeoutSeconds,
        "device_name" to deviceName,
        "rtsp_enabled" to rtspEnabled,
        "rtsp_port" to rtspPort,
        "autostart" to autostart,
        "power_saving" to powerSaving,
        // Read back by /config.json so a client can confirm what it wrote.
        // Before this, `sound=on` applied to a field that did not exist and
        // then could not be read back from anywhere.
        "awake" to keepScreenOn,
        "overlay" to overlayEnabled,
        "motion_event" to motionEventEnabled,
        "auth_required" to accessToken.isNotEmpty()
    )

    companion object {
        const val PREFS = "ocubea"

        const val KEY_PORT = "port"
        const val KEY_TOKEN = "access_token"
        const val KEY_RESOLUTION_IDX = "resolution_idx"
        const val KEY_FPS = "fps"
        const val KEY_JPEG_QUALITY = "jpeg_quality"
    const val KEY_ORIENTATION = "orientation"
    const val KEY_MIRROR_FLIP = "mirror_flip"
        const val KEY_VIDEO_BITRATE_KBPS = "video_bitrate_kbps"

        /** 1080p15 on the measured hardware lands near 4 Mbps, not 8.3. */
        const val DEFAULT_VIDEO_BITRATE_KBPS = 4000
        const val KEY_EFFECT = "effect"
        const val KEY_NIGHT_VISION = "night_vision"
        const val KEY_FRONT_CAMERA = "front_camera"
        const val KEY_AUDIO = "audio_enabled"
        const val KEY_RTSP_ENABLED = "rtsp_enabled"
        const val KEY_RTSP_PORT = "rtsp_port"

        /** 554 is the registered RTSP port and is unreachable from an app on Android. */
        const val DEFAULT_RTSP_PORT = 8554
        const val KEY_AUDIO_CODEC = "audio_codec"
        const val KEY_SECURITY = "security_enabled"
        const val KEY_MOTION_SENS = "motion_sensitivity"
        const val KEY_MOTION_RECORD = "motion_record"
        const val KEY_PRE_RECORD = "pre_record_seconds"
        const val KEY_MAX_CLIP = "max_clip_seconds"
        const val KEY_SOUND = "motion_sound"
        const val KEY_SOUND_EVENT = "motion_sound_event"
        const val KEY_SOUND_TIMEOUT = "motion_sound_timeout"
        const val KEY_CLIP_MAX_MB = "clip_max_space_mb"
        const val KEY_CLIP_MAX_AGE_H = "clip_max_age_hours"
        const val KEY_CLIP_MAX_FILES = "clip_max_files"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_AUTOSTART = "autostart"
        const val KEY_POWER_SAVING = "power_saving"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_OVERLAY = "video_overlay"
        const val KEY_MOTION_EVENT = "motion_event"
        const val KEY_TORCH = "torch_on"

    /**
     * The orientation values pydroid-ipcam accepts.
     *
     * It validates against exactly this list before sending, so it has to be
     * the source of truth for both validation and the `avail` list. Spelled
     * "upsidedown", not IP Webcam's "reverse_*", which the library does not
     * send.
     */
    val ORIENTATIONS = listOf(
        "landscape", "portrait", "upsidedown", "upsidedown_portrait"
    )
    }
}
