package com.ocubea.server

import android.content.Context
import com.ocubea.camera.CameraManager
import com.ocubea.model.OcuBeaConfig
import com.ocubea.security.MotionRecorder
import com.ocubea.sensors.DeviceSensors
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.IStatus
import fi.iki.elonen.NanoHTTPD.Response.Status as Status

/**
 * The read-only JSON endpoints: status, sensors, config and codecs.
 *
 * Split out of [StreamServer] because this block is pure rendering - it reads
 * collaborators and formats a response, and touches no socket, no session
 * lifecycle and no mutable server state of its own. That is what made it the
 * one section safe to lift out of a 1332-line file while three other audits
 * were reading the same file.
 *
 * The two pieces of live state this block reports (`torchOn`, the recorder)
 * arrive as lambdas rather than as references to the server's fields. A lambda
 * is a read that cannot be reassigned from here, so this class is structurally
 * unable to reach back into the server's internals - which is the point of
 * moving it.
 */
class TelemetryHandler(
    private val context: Context,
    private val cameraManager: CameraManager,
    private val audio: AudioStreamManager,
    private val audioCodecs: AudioCodecProbe = AudioCodecProbe,
    private val auth: ApiAuth,
    private val config: OcuBeaConfig,
    private val sensors: DeviceSensors,
    private val motionRecorder: MotionRecorder,
    private val motionDetector: com.ocubea.security.MotionDetector,
    private val connectionStats: () -> Map<String, Int>,
    private val localIpFallback: () -> String,
    private val torchOn: () -> Boolean,
    private val listeningPort: () -> Int,
    private val pipelineJson: () -> String,
    private val hlsJson: () -> String,
) {

    private val startedAt = System.currentTimeMillis()

    fun versionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "dev"
    } catch (_: Exception) { "dev" }

    fun uptimeSeconds(): Long = (System.currentTimeMillis() - startedAt) / 1000

    /**
     * Resident set size in whole megabytes, or 0 when it cannot be read.
     *
     * Zero rather than a guess: a leak hunt needs to be able to say "this
     * number is unavailable" instead of reporting 0 and being read as "the
     * process uses nothing". /proc/self/status is readable on every Android
     * version this app supports, so the fallback is only for the case where
     * something has gone wrong well enough to break file IO as well.
     */
    private fun residentMb(): Int {
        return try {
            val line = java.io.File("/proc/self/status")
                .useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
            // Format is "VmRSS:\t  123456 kB" -- a number, whitespace, a unit.
            val kb = line?.trim()?.removePrefix("VmRSS:")
                ?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull() ?: 0L
            (kb / 1024).toInt()
        } catch (_: Exception) {
            0
        }
    }

    /** Escapes a string for safe inclusion inside a JSON string literal. */
    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
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

    private fun String.escapeIt(): String = jsonEscape(this)

    private fun notFound(t: String) =
        NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found: $t")

    fun batteryLevel(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }
    // ── Telemetry ──────────────────────────────────────────────

    /**
     * `?show_avail=1` is IP Webcam's own gate for the settings dictionaries.
     *
     * Defaulted rather than required so every existing caller keeps working:
     * the WebUI polls this endpoint without the flag and must not start
     * receiving a second copy of every setting.
     */
    fun handleStatusJson(showAvail: Boolean = false): Response {
        val cfg = cameraManager.getConfiguration()
        val json = buildString {
            append("{")
            append("\"status\":\"ok\",")
            append("\"app\":\"OcuBea\",")
            append("\"version\":\"${versionName()}\",")
            append("\"uptime_s\":${uptimeSeconds()},")
            append("\"camera_active\":${cameraManager.isStreaming},")
            append("\"resolution\":\"${cfg["resolution"]}\",")
            append("\"fps\":${cfg["fps"]},")
            append("\"target_fps\":${cfg["target_fps"]},")
            append("\"frames\":${cfg["frames"]},")
            append("\"dropped\":${cfg["dropped"]},")
            append("\"pipeline\":" + pipelineJson() + ",")
            append("\"hls\":" + hlsJson() + ",")
            append("\"viewers\":${cfg["viewers"]},")
            // Refused connections are the visible half of BoundedAsyncRunner:
            // without them a device hammering the camera looks like a healthy
            // server that happens to be slow.
            append("\"connections\":{" +
                "\"active\":${connectionStats()["active"]}," +
                "\"refused\":${connectionStats()["refused"]}," +
                "\"max_threads\":${connectionStats()["max_threads"]}},")
            append("\"jpeg_quality\":${cfg["jpeg_quality"]},")
            append("\"video_bitrate_kbps\":${cfg["video_bitrate_kbps"]},")
            append("\"night_vision\":${cfg["night_vision"]},")
            append("\"effect\":\"${cfg["effect"]}\",")
            append("\"front_camera\":${cfg["front_camera"]},")
            append("\"torch\":${torchOn()},")
            append("\"zoom\":{\"level\":${cameraManager.zoomRatio()},")
            append("\"max\":${cameraManager.maxZoomRatio()}},")
            append("\"motion\":{\"enabled\":${motionDetector.enabled},")
            append("\"detected\":${motionDetector.motionDetected},")
            append("\"sensitivity\":${motionDetector.sensitivity}},")
            append("\"recording\":{\"enabled\":${motionRecorder.enabled},")
            append("\"active\":${motionRecorder.recording},")
            append("\"count\":${motionRecorder.listRecordings().size},")
            append("\"last\":\"${motionRecorder.lastRecordingFile.orEmpty()}\"},")
            append("\"audio\":{\"enabled\":${config.audioEnabled},")
            append("\"clients\":${audio.clientCount()},")
            // Which codec the endpoints serve and what this device can prove it
            // has, so a remote reader can tell a silent stream from an absent
            // encoder. `codec` is the stored preference, `default` is what a
            // client gets when it does not ask for anything.
            append("\"codec\":\"${config.audioCodecOrDefault(audioCodecs.defaultId())}\",")
            // The picker in the WebUI needs a list it can iterate, not a
            // human-readable summary: an older phone must show fewer options,
            // and building the menu from a string here would mean the browser
            // had to guess which ids were in it.
            append("\"available_list\":[")
            append(audioCodecs.options().joinToString(",") { o ->
                "{\"id\":\"${o.id}\",\"label\":\"${o.label}\"," +
                    "\"bitrate\":${o.bitrate},\"container\":\"${o.container}\"," +
                    "\"note\":\"${o.note.escapeIt()}\"}"
            })
            append("],")
            // Codecs this device encodes but cannot itself decode. They are
            // still offered -- a working encoder is worth having -- but a client
            // that picks one of these may well get silence back, and that needs
            // to be visible from outside instead of being a mystery.
            append("\"undecodable\":[")
            append(audioCodecs.probe().undecodable.joinToString(",") { "\"$it\"" })
            append("],")
            // Encoder counters, so "the stream is empty" can be told apart from
            // "the encoder is producing nothing" without a logcat. These are the
            // numbers that separate the two, and they were unreachable before:
            // feed_misses means no PCM reached the encoder at all, packets_out
            // means PCM arrived and the codec gave nothing back.
            val enc = audio.encodedStats()
            append("\"encoded\":{")
            append(enc.entries.joinToString(",") { (k, v) -> "\"$k\":$v" })
            append("},")
            append("\"available\":\"${audioCodecs.summary()}\"},")
            append("\"auth_required\":${auth.isEnabled()},")
            // Resident set size, so a leak is visible from outside. Every
            // counter in this file can be healthy while a codec, a thread or a
            // client ring is still being retained somewhere, and PSS is the one
            // number that says so without a debugger attached. Measured from
            // /proc/self/status because Debug.getMemoryInfo reports the whole
            // process, which is the same thing but slower and less precise.
            append("\"rss_mb\":${residentMb()},")
            // Written last, so it can decide whether it needs a trailing
            // comma. Doing it the other way round leaves a `,}` on the default
            // response, which is not JSON, and every client of this endpoint --
            // the WebUI poll, pydroid, anything reading /status.json -- fails to
            // parse it. That is exactly what happened: the whole status block
            // read as broken on a phone that was otherwise working.
            if (showAvail) {
                append("\"battery_level\":${batteryLevel()},")
                append(ipWebcamSettingsBlock(cfg))
            } else {
                append("\"battery_level\":${batteryLevel()}")
            }
            append("}")
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", json)
    }

    /**
     * `curvals` and `avail`, in the shape pydroid parses.
     *
     * Values are strings on both sides, deliberately: pydroid coerces numbers
     * with float() and booleans by comparing to "on"/"off", and a JSON true or
     * a bare number makes `float(True)` work by accident on one path and fail
     * on another. Strings keep every setter on the same path.
     */
    /**
     * Appends `curvals` and `avail` to the status JSON.
     *
     * Takes no StringBuilder because it is always called from inside a
     * `buildString` block, where the receiver already is the buffer.
     */
    private fun ipWebcamSettingsBlock(cfg: Map<String, Any>): String {
        val res = cfg["resolution"]?.toString() ?: "1280x720"
        val resList = listOf("640x480", "800x600", "1280x720", "1920x1080", "640x360", "960x540")
        val onOff = listOf("on", "off")
        val current = linkedMapOf(
            "resolution" to res,
            "quality" to "${cameraManager.jpegQualityOverride}",
            // Measured, not requested. Reporting the configured value is how a 10 fps
            // setting came to sit next to a 6 fps phone with nothing indicating
            // that the two disagreed.
            "fps" to "${cameraManager.measuredFps ?: cfg["fps"] ?: 15}",
            "focus" to "auto",
            "exposure" to "auto",
            "whitebalance" to "auto",
            "night_vision" to boolStr(cameraManager.nightVisionEnabled),
            "overlay" to "on",
            "ffc" to boolStr(cameraManager.isUsingFrontCamera()),
            "gps_active" to "off",
            "motion_detect" to boolStr(motionDetector.enabled),
            "scenemode" to "auto",
            "orientation" to "landscape",
            "led_torch" to "auto",
            "norecord" to "off",
            "audio" to boolStr(config.audioEnabled),
            // The chosen codec, under the name pydroid looks for. Without it in
            // curvals a client restoring settings has nothing to read the codec
            // back from, falls back to its own default, and then asks a camera
            // set to AAC for Opus -- and gets a 501.
            "audio_codec" to config.audioCodecOrDefault(audioCodecs.defaultId()),
            "coloreffect" to cameraManager.effect
        )
        val available = linkedMapOf(
            "resolution" to resList,
            "quality" to listOf("25", "50", "75", "100"),
            "fps" to listOf("1", "5", "10", "15", "20", "30"),
            "focus" to listOf("auto", "auto,continuous", "macro"),
            "exposure" to listOf("auto", "normal", "long", "short"),
            "whitebalance" to listOf("auto", "incandescent", "fluorescent", "daylight", "cloudy"),
            "night_vision" to onOff,
            "overlay" to onOff,
            "ffc" to onOff,
            "gps_active" to onOff,
            "motion_detect" to onOff,
            // Only codecs this device actually has, so a client is never offered
            // a choice that answers 501. Built from the same probe that hides
            // Opus on Android 6.
            "audio_codec" to audioCodecs.options().map { it.id },
            "scenemode" to StreamServer.SCENE_MODES,
            "orientation" to StreamServer.ORIENTATIONS,
            "led_torch" to listOf("auto", "on", "off", "flash"),
            "norecord" to onOff,
            "audio" to onOff,
            "coloreffect" to StreamServer.EFFECTS
        )
        // Hand-rolled rather than joined from a map: `curvals` and `avail` are
        // one level of nesting each and every value is a string, so the loop
        // below is shorter than the formatter that would replace it.
        val block = buildString {
            append("\"curvals\":{")
            current.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(",")
                append("\"").append(k).append("\":\"").append(v).append('"')
            }
            append("},\"avail\":{")
            available.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(",")
                append('"').append(k).append("\":[")
                v.forEachIndexed { j, item ->
                    if (j > 0) append(",")
                    append('"').append(item).append('"')
                }
                append(']')
            }
            append("}")
        }
        return block
    }

    private fun boolStr(on: Boolean) = if (on) "on" else "off"

    /**
     * GET /sensors.json                 — full nested telemetry (OcuBea extra)
     * GET /sensors.json?sense=<name>    — single value, upstream-compatible
     *
     * The single-sensor form must return a BARE value (`52`, `true`, `"auto"`),
     * not an object wrapped in a key: Home Assistant's android_ip_webcam
     * integration and long-standing user scripts both parse it that way.
     */
    fun handleSensorsJson(session: IHTTPSession): Response {
        val sense = session.parameters.mapValues { it.value.firstOrNull() ?: "" }["sense"]?.lowercase()
        if (sense.isNullOrEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.OK, "application/json",
                sensors.snapshotJson(listeningPort(), cameraManager.isStreaming)
            )
        }
        if (sense !in IpWebcamCompat.SENSOR_NAMES) {
            return notFound("unknown sensor: $sense")
        }
        val value = singleSensor(sense)
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", value)
    }

    /** Resolves one upstream sensor name to its bare JSON value. */
    fun singleSensor(name: String): String = when (name) {
        "battery_level" -> batteryLevel().toString()
        "motion", "motion_detect", "motion_active", "motion_event" ->
            (motionDetector.motionDetected || motionRecorder.recording).toString()
        "torch" -> torchOn().toString()
        "ffc" -> cameraManager.isUsingFrontCamera().toString()
        "night_vision" -> cameraManager.nightVisionEnabled.toString()
        "focus" -> "true"
        "exposure_lock" -> "false"
        "whitebalance_lock" -> "false"
        "coloreffect", "effect" -> "\"${cameraManager.effect}\""
        "quality" -> cameraManager.jpegQualityOverride.toString()
        "video_size" -> "\"${cameraManager.currentTargetWidth}x${cameraManager.currentTargetHeight}\""
        "photo_size" -> "\"${cameraManager.currentTargetWidth}x${cameraManager.currentTargetHeight}\""
        "zoom" -> cameraManager.zoomRatio().toString()
        "video_connections" -> cameraManager.frameHub.viewerCount().toString()
        "audio_connections" -> audio.clientCount().toString()
        "video_recording" -> motionRecorder.recording.toString()
        "video_chunk_len" -> (cameraManager.frameHub.getLatest()?.size ?: 0).toString()
        "audio_only" -> (!config.audioEnabled).toString()
        "ivideon_streaming" -> "false"
        "idle" -> (!cameraManager.isStreaming).toString()
        "light" -> (if (torchOn()) 255 else 0).toString()
        "gps_active" -> "false"
        "antibanding" -> "\"auto\""
        "scenemode" -> "\"${if (cameraManager.nightVisionEnabled) "night" else "auto"}\""
        "whitebalance" -> "\"auto\""
        "focusmode" -> "\"auto\""
        "flashmode" -> (if (torchOn()) "torch" else "off").toString()
            .let { "\"$it\"" }
        "focus_distance" -> "0.0"
        "motion_limit" -> motionRecorder.maxClipSeconds.toString()
        "sound", "sound_event" -> "false"
        "sound_timeout" -> "0"
        "battery_temp" -> "0.0"
        "battery_voltage" -> "0"
        "night_vision_gain" -> "0"
        "night_vision_average" -> "0"
        "focus_homing", "focus_region" -> "false"
        "orientation" -> "0"
        "overlay" -> "false"
        "proximity", "pressure" -> "false"
        "mirror_flip" -> "false"
        "adet_limit" -> motionDetector.sensitivity.toString()
        "ip_address" -> "\"${localIpFallback()}\""
        "ipv6_address" -> "\"\""
        else -> "null"
    }

    fun handleConfigJson(): Response {
        val entries = config.snapshot().entries.joinToString(",") { (k, v) ->
            val jv = when (v) {
                is String -> "\"${v.escapeIt()}\""
                is Boolean, is Int, is Long, is Float -> v.toString()
                else -> "null"
            }
            "\"$k\":$jv"
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", "{$entries}")
    }

    /**
     * GET /codecs.json — what this device can actually encode.
     *
     * Exists because codec capability claims on MediaTek devices are often
     * wrong; this reports encoders that were configured and started for real.
     */
    fun handleCodecsJson(): Response {
        val r = com.ocubea.camera.CodecProbe.probe()
        val arr: (List<String>) -> String = { xs -> xs.joinToString(",", "[", "]") { "\"${it.escapeIt()}\"" } }
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, "application/json",
            """{"encoders":${arr(r.encoders)},""" +
                """"hardware_avc":${arr(r.hardwareAvc)},""" +
                """"software_avc":${arr(r.softwareAvc)},""" +
                """"hardware_hevc":${arr(r.hardwareHevc)},""" +
                """"largest_working_avc":"${r.largestWorkingAvc.escapeIt()}",""" +
                """"avc_configurable":${r.avcConfigurable},""" +
                """"summary":"${r.summary().escapeIt()}"}"""
        )
    }
}
