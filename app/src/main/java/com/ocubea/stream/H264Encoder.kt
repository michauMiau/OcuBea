package com.ocubea.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed straight from the camera's YUV planes.
 *
 * The MJPEG path pays for two very expensive steps per frame: a software
 * YUV→ARGB conversion (~30ms) and a blocking JPEG compress (~140ms). This
 * encoder skips both. The camera already hands us YUV_420_888, which is exactly
 * what the hardware encoder wants, so frames go camera→encoder with no
 * conversion at all. Everything CPU-bound disappears from the hot path and the
 * work moves into the MediaTek video encoder block.
 *
 * Only an encoder that was *executed* at startup is used — see
 * [com.ocubea.camera.CodecProbe], because codec capability claims on MediaTek
 * devices are frequently wrong.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    /**
     * Seconds between IDR frames. 0 means every frame is an IDR, which is what
     * HLS needs when a segment must open on a random-access point.
     *
     * A dense GOP is expensive under VBR: with an IDR on every frame the
     * encoder cannot spend bits on inter-prediction, so it refuses to go below
     * its floor and KEY_BIT_RATE is only an upper bound. Measured on the Redmi,
     * 1080p, clip recording, 12000 kbps requested, two samples each:
     * GOP=0 gave 94.4 and 95.4 MB/min (12.6-12.7 Mbps); GOP=1 gave 60.8 and
     * 61.1 MB/min (8.1 Mbps) - 36% less. The encoder reports "max input
     * interval 204ms" and a frame arrives every ~185ms, so it never idles, but
     * a dense GOP still leaves it no frames to predict from.
     *
     * HLS cannot use this: a segment must open on a random-access point, so a
     * GOP longer than the segment leaves most segments waiting for an IDR that
     * does not come, and the playlist advertises durations it cannot deliver.
     * HlsProfile.sanitized() keeps the two in agreement.
     */
    private val keyFrameIntervalSec: Int = 0
) {

    data class Sample(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    private val TAG = "OcuBeaH264"

    @Volatile var codecName: String = "none"
        private set

    private var codec: MediaCodec? = null
    private var colorFormat = COLOR_YUV420_FLEXIBLE

    /** SPS/PPS from the codec config output; required to build the fMP4 init segment. */
    @Volatile var codecConfig: ByteArray? = null
        private set

    @Volatile private var started = false
    @Volatile var framesEncoded: Long = 0L
        private set
    @Volatile var bytesEncoded: Long = 0L
        private set
    @Volatile var lastError: String = "none"
        private set
    @Volatile var framesQueued: Long = 0L
        private set
    @Volatile var framesDropped: Long = 0L
        private set

    /** Reused row buffers for plane copies; see rowY()/rowC(). */
    private var scratchY = ByteArray(0)
    private var scratchC = ByteArray(0)

    val isRunning: Boolean get() = started && codec != null

    /**
     * Configures the hardware AVC encoder.
     *
     * Returns false when no hardware encoder accepts the requested size, in
     * which case the caller must keep serving MJPEG — this is a genuine
     * fallback, not a degraded mode, because HLS needs real H.264.
     */
    fun start(): Boolean {
        // Codec discovery is inside the guard, not just configure(). These
        // calls touch MediaCodecList, which can throw on its own - a locked
        // encoder service, or a stubbed android.jar under JVM test. Letting that
        // escape start() meant the caller got an exception where the whole
        // contract promises a false return plus a readable lastError, and
        // CameraManager.startHls() had nothing to report to /status.json.
        val info = try {
            pickHardwareAvcEncoder()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "encoder discovery failed", e)
            started = false
            return false
        } ?: run {
            lastError = "no hardware AVC encoder"
            started = false
            return false
        }
        codecName = info.name

        // COLOR_FormatYUV420Flexible is the honest choice: it tells the encoder
        // "I will give you a YUV buffer with arbitrary strides", which is what
        // lets us copy the ImageProxy planes in directly.
        colorFormat = pickColorFormat(info)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // An IDR per frame by default. Every HLS segment must open on a
            // random-access point, and with a 250ms target a longer keyframe
            // interval means most segments would have to wait for the next
            // IDR, so the segment length and the advertised #EXTINF
            // disagree and the playlist drains. The profile exists to keep
            // the two in agreement - HlsProfile.sanitized() refuses a GOP
            // longer than the segment, so the muxer never advertises a
            // duration it cannot deliver.
            //
            // An IDR per frame was previously fatal only because Fmp4Writer
            // treated an in-segment keyframe as "discard what I have", so every
            // frame threw away the previous one and nothing was ever flushed.
            // Now the keyframe closes the open segment and opens the next one,
            // so the cost of a dense GOP is only CPU, which the high profile
            // measurably recovers.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        // mc is declared outside the try so a failed configure() still has a
        // handle to release. MediaCodec has no finalizer reclaiming the native
        // encoder, so dropping the last reference leaks it for the life of the
        // process - and handleHlsPlaylist retries startHls() on every playlist
        // poll, which turns one failed start into a leak every half second.
        var mc: MediaCodec? = null
        return try {
            mc = MediaCodec.createByCodecName(info.name)
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mc.start()
            codec = mc
            started = true
            lastError = "none"
            Log.i(TAG, "started ${info.name} ${width}x$height@$fps color=$colorFormat bitrate=$bitrate")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "configure failed", e)
            started = false
            codec = null
            // Release the half-built codec; swallowing a release failure is
            // deliberate - the configure error is the one worth reporting.
            try { mc?.release() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Copies one camera frame into the encoder's input buffer.
     *
     * Returns the number of bytes written, or -1 if the frame was rejected.
     *
     * [onSample] is called from this thread, so the HLS writer must not block.
     * The ImageProxy is *not* closed here — the caller owns its lifetime.
     */
    fun encode(image: ImageProxy, ptsUs: Long, onSample: (Sample) -> Unit) {
        val mc = codec ?: return
        if (!started) return

        try {
            // Drain first, unconditionally.
            //
            // The encoder has only a handful of input slots; if this method ever
            // returns without draining, the slots fill up, dequeueInputBuffer
            // starts returning -1 forever, and the stream dies silently with
            // zero output. Recovering the slots is the whole reason to call
            // drain before asking for a new one.
            drain(mc, onSample)

            val inputIndex = mc.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                // Encoder is back-pressured. Dropping the frame is correct:
                // queueing it would only add latency.
                framesDropped++
                return
            }
            val buf = mc.getInputBuffer(inputIndex) ?: return
            val size = copyPlanes(buf, image)
            if (size <= 0) {
                framesDropped++
                return
            }
            mc.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
            framesQueued++
            // Output may already be waiting; collect it now so the ring buffer
            // in HlsSession stays current.
            drain(mc, onSample)
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "encode failed", e)
        }
    }

    /** Forces the next frame to be an IDR, e.g. right after a client joins. */
    fun requestKeyFrame() {
        val mc = codec ?: return
        runCatching {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mc.setParameters(params)
        }
    }

    fun stop() {
        val mc = codec
        codec = null
        started = false
        if (mc != null) {
            runCatching { mc.stop() }
            runCatching { mc.release() }
        }
    }

    // ─── internals ──────────────────────────────────────────────

    private fun drain(mc: MediaCodec, onSample: (Sample) -> Unit) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> {
                    // Every dequeued output index MUST be released, on every
                    // path. A leaked native buffer never comes back, the encoder
                    // runs out of slots within seconds at 15fps, and the process
                    // dies in native code. So the release lives in a finally
                    // rather than at the end of the happy path.
                    try {
                        val buf = mc.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && info.offset >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)

                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // SPS/PPS. Kept for the avcC box.
                                codecConfig = bytes
                            } else {
                                framesEncoded++
                                bytesEncoded += bytes.size
                                onSample(
                                    Sample(
                                        data = bytes,
                                        ptsUs = info.presentationTimeUs,
                                        keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        Log.w(TAG, "drain failed", e)
                    } finally {
                        runCatching { mc.releaseOutputBuffer(outIndex, false) }
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                // Nothing more available right now. This is the normal exit.
                else -> return
            }
        }
    }

    /**
     * Copies Y, U and V into the encoder's input buffer.
     *
     * Stride and pixel-stride must both be respected: a vendor encoder that
     * silently reads a packed buffer from a padded one produces green-and-magenta
     * garbage rather than an error, so this is done per row with an explicit
     * row buffer instead of a bulk put.
     *
     * Every write is bounds-checked against what the encoder actually handed us.
     * That is not defensive padding: overrunning a MediaCodec input buffer is a
     * hard SIGSEGV inside GetPrimitiveArrayRegion, not an exception, so a
     * mismatch between the configured size and the delivered frame would take
     * the whole app down with no recoverable catch.
     *
     * Returns the byte count written, or -1 if the frame was rejected. The count
     * is returned explicitly rather than read back from the buffer: after flip()
     * the position is back at 0 and the length lives in the limit, so callers
     * that read position() get a silent zero and drop every frame.
     */
    private fun copyPlanes(dst: ByteBuffer, image: ImageProxy): Int {
        val w = image.width
        val h = image.height
        val planes = image.planes
        if (planes.size < 3) return -1

        // The encoder was configured for a fixed size. If the camera starts
        // delivering something else, stop rather than overrun the buffer.
        if (w != width || h != height) {
            Log.w(TAG, "frame ${w}x$h does not match encoder ${width}x$height; skipping")
            return -1
        }

        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val needed = w * h + 2 * cw * ch
        if (dst.capacity() < needed) {
            Log.w(TAG, "input buffer ${dst.capacity()} < required $needed; skipping")
            return -1
        }

        dst.clear()
        if (!copyPlane(dst, planes[0], rowY(), w, h)) return -1
        if (!copyPlane(dst, planes[1], rowC(), cw, ch)) return -1
        if (!copyPlane(dst, planes[2], rowC(), cw, ch)) return -1
        return dst.position()
    }

    // Row buffers reused across frames. Encoding is driven from a single
    // analyzer thread, so these need no synchronisation. Reusing them avoids
    // ~2.5KB of garbage per frame, which at 15fps is a steady drip into the
    // heap while the session is live.
    private fun rowY(): ByteArray {
        var r = scratchY
        if (r.size < width) { r = ByteArray(width); scratchY = r }
        return r
    }

    private fun rowC(): ByteArray {
        val need = (width + 1) / 2
        var r = scratchC
        if (r.size < need) { r = ByteArray(need); scratchC = r }
        return r
    }

    /** Copies one plane row by row. Returns false if the source runs out early. */
    private fun copyPlane(dst: ByteBuffer, plane: ImageProxy.PlaneProxy, row: ByteArray, w: Int, h: Int): Boolean {
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buf = src.duplicate()
        val base = buf.position()
        val limit = buf.limit()

        for (y in 0 until h) {
            var idx = base + y * rowStride
            // Guard the source: a row that starts inside the buffer but runs
            // past its end would also fault rather than throw.
            if (idx + (w - 1) * pixelStride >= limit) {
                Log.w(TAG, "plane row $y exceeds buffer (idx=$idx limit=$limit)")
                return false
            }
            for (x in 0 until w) {
                row[x] = buf.get(idx)
                idx += pixelStride
            }
            dst.put(row, 0, w)
        }
        return true
    }

    private fun pickHardwareAvcEncoder(): MediaCodecInfo? {
        @Suppress("DEPRECATION")
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && isHardware(it) && runCatching {
                it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }.getOrDefault(false)
        }
    }

    // The API-29 call and its guard now live in one place:
    // com.ocubea.camera.isHardwareAvcCapable().
    private fun isHardware(info: MediaCodecInfo): Boolean =
        com.ocubea.camera.isHardwareAvcCapableOf(info)

    /**
     * Chooses the color format whose input buffer can actually hold a frame.
     *
     * COLOR_FormatYUV420Flexible sounds ideal but is a trap: MediaCodec sizes
     * that buffer at w*h*3/2 - 1, one byte short of a full frame. Writing the
     * last luma byte overruns the allocation and SIGSEGVs inside
     * GetPrimitiveArrayRegion with no Java-level catch. Planar 420 (19) and
     * semi-planar (21) both allocate the full size, and the encoder is told the
     * format it will actually receive.
     */
    private fun pickColorFormat(info: MediaCodecInfo): Int {
        val caps = runCatching {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }.getOrNull() ?: return COLOR_YUV420_SEMI_PLANAR
        val formats = runCatching { caps.colorFormats }.getOrNull()
            ?: return COLOR_YUV420_SEMI_PLANAR
        return when {
            formats.contains(COLOR_YUV420_PLANAR) -> COLOR_YUV420_PLANAR
            formats.contains(COLOR_YUV420_SEMI_PLANAR) -> COLOR_YUV420_SEMI_PLANAR
            formats.contains(COLOR_YUV420_FLEXIBLE) -> COLOR_YUV420_FLEXIBLE
            else -> formats.firstOrNull() ?: COLOR_YUV420_SEMI_PLANAR
        }
    }

    companion object {
        const val COLOR_YUV420_FLEXIBLE = 0x7F420888
        const val COLOR_YUV420_PLANAR = 19
        const val COLOR_YUV420_SEMI_PLANAR = 21
        private const val TIMEOUT_US = 10_000L
    }
}
