package com.ocubea.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Runtime probe for hardware video encoders.
 *
 * The vendor codec XML is not a reliable oracle — some MediaTek builds ship a
 * hardware AVC encoder that the XML omits or mislabels, and some advertise
 * hardware while failing to allocate at the requested size. So this asks
 * MediaCodecList first, then confirms each claim by actually configuring and
 * starting the encoder.
 */
object CodecProbe {

    private const val TAG = "OcuBeaCodec"

    /** What the device can actually do, as opposed to what it claims. */
    data class Report(
        val encoders: List<String>,
        val hardwareAvc: List<String>,
        val softwareAvc: List<String>,
        val hardwareHevc: List<String>,
        val largestWorkingAvc: String,
        val avcConfigurable: Boolean
    ) {
        /** One line suitable for /status.json or the WebUI. */
        fun summary(): String =
            "avc_hw=[${hardwareAvc.joinToString(",")}] " +
                "avc_sw=[${softwareAvc.joinToString(",")}] " +
                "hevc=[${hardwareHevc.joinToString(",")}] " +
                "largest_working=$largestWorkingAvc configurable=$avcConfigurable"
    }

    fun probe(fps: Int = 15): Report {
        val all = mutableListOf<String>()
        val hwAvc = mutableListOf<String>()
        val swAvc = mutableListOf<String>()
        val hwHevc = mutableListOf<String>()

        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            val mime = mimeOf(info) ?: continue
            if (!mime.startsWith("video/")) continue
            val isHw = isHardware(info)
            all += "${info.name} [$mime] ${if (isHw) "hw" else "sw"}"

            when {
                mime.equals(MIME_AVC, true) -> if (isHw) hwAvc += info.name else swAvc += info.name
                mime.equals(MIME_HEVC, true) && isHw -> hwHevc += info.name
            }
        }

        // Confirm the hardware AVC path really starts, not just that it is listed.
        // Walk sizes downwards: the first success is the encoder's true maximum.
        var largest = "none"
        for ((w, h) in CANDIDATE_SIZES) {
            if (tryConfigureAvc(w, h, fps)) {
                largest = "${w}x$h"
                break
            }
        }

        return Report(
            encoders = all,
            hardwareAvc = hwAvc,
            softwareAvc = swAvc,
            hardwareHevc = hwHevc,
            largestWorkingAvc = largest,
            avcConfigurable = largest != "none"
        )
    }

    private fun isHardware(info: MediaCodecInfo): Boolean = isHardwareAvcCapableOf(info)

    /** Tried largest first. */
    private val CANDIDATE_SIZES = listOf(
        1920 to 1080, 1280 to 720, 960 to 540, 640 to 480, 320 to 240
    )

    private fun mimeOf(info: MediaCodecInfo): String? = try {
        info.supportedTypes.firstOrNull()
    } catch (_: Exception) { null }

    /**
     * Actually configures and starts a hardware AVC encoder.
     *
     * Returns true only when both configure() and start() succeed, which is the
     * only way to know the encoder is real and not just advertised.
     */
    private fun tryConfigureAvc(width: Int, height: Int, fps: Int): Boolean = try {
        val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos.firstOrNull {
                it.isEncoder && isHardwareAvcCapableOf(it) &&
                    it.supportedTypes.any { t -> t.equals(MIME_AVC, true) }
            }
        if (codec == null) {
            false
        } else {
            val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_YUV420_FLEXIBLE)
                setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val mc = android.media.MediaCodec.createByCodecName(codec.name)
            try {
                mc.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                mc.start()
                true
            } finally {
                runCatching { mc.stop() }
                runCatching { mc.release() }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "hardware AVC not configurable", e)
        false
    }

    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"
    private const val COLOR_YUV420_FLEXIBLE = 0x7F420888
}
