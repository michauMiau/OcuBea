package com.ocubea.server

/**
 * IP Webcam 1:1 API compatibility table.
 *
 * Reference surface is the upstream "IP Webcam for Android" HTTP API, as
 * consumed by real clients (Home Assistant `android_ip_webcam`, the official
 * cheatsheet, v4l2loopback/ffmpeg pipelines, custom scripts people already
 * have written against it).
 *
 * The upstream app exposes a single generic convention — `/settings/<name>?set=<v>`
 * — so almost all of its API is a *name table*, not a set of bespoke routes.
 * This file is that table, kept in one place so adding or auditing a name is
 * a single-line change rather than a hunt through the router.
 *
 * OcuBea keeps its own endpoints alongside these (never replacing them), so
 * existing IP Webcam scripts keep working while OcuBea-specific routes stay
 * available.
 */
object IpWebcamCompat {

    /** Names accepted by `/settings/<name>` that OcuBea has no honest answer for. */
    private val SUPPORTED = linkedSetOf(
        // image / video
        "quality", "resolution", "video_size", "photo_size", "video_resolution",
        "fps", "jpeg_quality", "effect", "coloreffect", "rotate", "rotation", "mirror_flip",
        // camera
        "ffc", "front_camera", "torch", "flashmode", "focus", "focusmode", "focus_distance",
        "exposure", "exposure_lock", "antibanding", "whitebalance", "whitebalance_lock",
        "scenemode", "night_vision", "overlay",
        // audio
        "audio", "audio_enabled", "audio_only", "sound", "sound_event", "sound_timeout",
        // motion / recording
        "motion_detection", "motion_active", "motion_event", "motion_limit", "motion_sensitivity",
        "recording", "video_recording", "pre_record_seconds", "max_clip_seconds", "norecord",
        // service / power
        "awake", "overlay", "idle", "power_saving", "autostart", "gps_active",
        "noremote", "login", "password", "port", "device_name",
        // OcuBea service control
        "force_start", "force_stop", "start", "stop"
    )

    fun isKnown(name: String): Boolean = name.lowercase() in SUPPORTED

    /**
     * Sensor names from upstream `/sensors.json?sense=<name>`.
     *
     * Upstream returns a BARE value for a single-sensor query (not an object),
     * e.g. `battery_level=52` or `{"motion":true}`. Home Assistant depends on
     * that shape, so it must not be wrapped in JSON.
     */
    val SENSOR_NAMES = linkedSetOf(
        "adet_limit", "antibanding", "audio_connections", "audio_only", "battery_level",
        "battery_temp", "battery_voltage", "coloreffect", "exposure", "exposure_lock",
        "ffc", "flashmode", "focus", "focus_homing", "focus_region", "focusmode",
        "gps_active", "idle", "ip_address", "ipv6_address", "ivideon_streaming", "light",
        "mirror_flip", "motion", "motion_active", "motion_detect", "motion_event",
        "motion_limit", "night_vision", "night_vision_average", "night_vision_gain",
        "orientation", "overlay", "photo_size", "pressure", "proximity", "quality",
        "scenemode", "sound", "sound_event", "sound_timeout", "torch",
        "video_connections", "video_chunk_len", "video_recording", "video_size",
        "whitebalance", "whitebalance_lock", "zoom"
    )

    /** Upstream capability-discovery flag: `/status.json?show_avail=1`. */
    fun availableSettings(): String = SUPPORTED.sorted().joinToString(",")
}
