package com.ocubea.server

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes captured PCM into one encoded audio stream, once, shared by every client.
 *
 * The shape mirrors the video path: capture is expensive and shared, encoding is
 * expensive and shared, and only the write to each socket is per client. So this
 * sits behind the same fan-out the PCM path uses, and N viewers of `/audio.aac`
 * cost one microphone and one MediaCodec, not N.
 *
 * AAC and Opus are deliberately NOT interchangeable inside this class. They share
 * a MediaCodec skeleton, but they differ in what a client needs before it can
 * decode the first byte:
 *
 *  - AAC over HTTP arrives as a bare ADTS stream. Each frame carries its own
 *    header, so a client can start decoding frame 1 and never has to seek.
 *  - Opus over HTTP has no per-frame header to speak of, and Ogg is a container
 *    that expects to be told where the stream ends. Truncated and never
 *    finalised it is not decodable, so it gets a header written by hand here and
 *    a zero-length final page written by hand when the client goes away.
 *
 * Hand-rolling Ogg pages is well-trodden (OggS, one page per encoded buffer,
 * granule position plus serial number) and it means a browser can play it with no
 * demuxer library. The alternative -- muxing with MediaMuxer -- cannot express
 * "a stream that ends when the client disconnects", which is exactly this.
 *
 * A client asking for something this device cannot encode gets a thrown
 * exception, never a silent fallback to PCM: a client that asked for AAC and
 * quietly received WAV would be lying about the stream it is decoding.
 */
class AudioEncoder private constructor(
    private val codecId: String,
    private val mime: String
) {

    companion object {
        private const val TAG = "OcuBeaAudioEnc"

        /** PCM the encoder consumes: 48 kHz mono, 16-bit. */
        const val ENCODER_SAMPLE_RATE = 48_000

        /**
         * One 20 ms frame at 48 kHz mono, in samples.
         *
         * 960 is what every encoder measured on the validation phone wanted
         * (AAC and Opus both emitted their first bytes on the second such
         * frame), and it is 1/50 s, so PTS arithmetic is just `frame * 960`.
         */
        const val FRAME_SAMPLES = 960
        const val FRAME_BYTES = FRAME_SAMPLES * 2

        /** The mime a codec id means, or null when the id is not a real codec. */
        fun mimeFor(codecId: String): String? = when (codecId) {
            "aac" -> AudioCodecProbe.MIME_AAC
            "opus" -> AudioCodecProbe.MIME_OPUS
            "amrnb" -> AudioCodecProbe.MIME_AMR_NB
            "flac" -> AudioCodecProbe.MIME_FLAC
            else -> null
        }

        fun isEncodable(codecId: String): Boolean = mimeFor(codecId) != null

        fun forId(codecId: String): AudioEncoder? =
            mimeFor(codecId)?.let { AudioEncoder(codecId, it) }
    }

    /** What the client gets in Content-Type. */
    val contentType: String = when (codecId) {
        "aac" -> "audio/aac"
        "opus" -> "audio/ogg"
        "amrnb" -> "audio/amr"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    /** True when each encoded frame needs a container header around it. */
    private val wrapped: Boolean = codecId == "opus"

    private var codec: MediaCodec? = null
    private var framesIn = 0L

    // Ogg state, all meaningless unless the codec is Opus. The serial number
    // lives in OggPage; only the two counters are per-encoder.
    private var granule = 0L
    private var pageSeq = 0

    /**
     * Configures the encoder. Throws when the device cannot do this codec: the
     * caller is expected to have run the probe first, and a failure here is a bug
     * worth surfacing rather than hiding behind a fallback.
     */
    fun start(sampleRate: Int, channels: Int, bitrate: Int) {
        check(codec == null) { "encoder already started" }
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate(bitrate))
                when (codecId) {
                    "aac" -> setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    // No frame-duration key for Opus: MediaFormat has no
                    // KEY_FRAME_SIZE_IN_MICROSECONDS (checked against the
                    // android-35 stubs), so the frame size is conveyed by
                    // feeding fixed 960-sample buffers instead.
                }
            }
        codec = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun effectiveBitrate(bitrate: Int): Int = when (codecId) {
        "amrnb" -> 12_200 // AMR-NB has exactly one legal bitrate here
        "flac" -> 0 // lossless: the encoder picks, KEY_BIT_RATE is meaningless
        else -> if (bitrate > 0) bitrate else 64_000
    }

    /**
     * Feeds exactly one frame of PCM and returns whatever it encoded, or an empty
     * array when the encoder is still buffering. `pcm` must be
     * [FRAME_SAMPLES] long: audio capture does not promise frame alignment, so
     * the caller re-frames and keeps the leftover.
     */
    fun encodeFrame(pcm: ShortArray): ByteArray {
        val codec = this.codec ?: error("encoder not started")
        require(pcm.size == FRAME_SAMPLES) {
            "expected $FRAME_SAMPLES samples, got ${pcm.size}"
        }
        val info = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 5_000_000L // 5 ms: a 20 ms frame is cheap
        val out = ByteArray(8_192)

        while (System.nanoTime() < deadline) {
            val inIdx = codec.dequeueInputBuffer(2_000)
            if (inIdx >= 0) {
                codec.getInputBuffer(inIdx)?.let { buf ->
                    buf.clear()
                    // asShortBuffer() keeps the ByteBuffer's own order, which is
                    // the codec's native order; reordering would be a second
                    // guess about something the platform already got right.
                    buf.asShortBuffer().put(pcm)
                }
                codec.queueInputBuffer(inIdx, 0, FRAME_BYTES, framesIn * FRAME_SAMPLES * 1_000_000L / 48_000, 0)
                framesIn++
            }
            val outIdx = codec.dequeueOutputBuffer(info, 2_000)
            if (outIdx >= 0) {
                val payload = copyPayload(codec, outIdx, info, out)
                if (info.size > 0) {
                    // One frame in, one frame out, so the granule advances by
                    // exactly one frame. Deriving it from BufferInfo instead
                    // would break on the codecs that report a padded size.
                    granule += FRAME_SAMPLES
                    return if (wrapped) OggPage.page(payload, granule, pageSeq++) else payload
                }
            }
        }
        return ByteArray(0)
    }

    private fun copyPayload(
        codec: MediaCodec,
        outIdx: Int,
        info: MediaCodec.BufferInfo,
        into: ByteArray
    ): ByteArray {
        codec.getOutputBuffer(outIdx)?.let { buf ->
            val n = minOf(info.size, into.size, buf.remaining())
            buf.get(into, 0, n)
            return into.copyOf(n)
        }
        return ByteArray(0)
    }

    /**
     * The first bytes a client needs: for Opus, page 0 carrying OpusHead, which
     * every player requires before it will decode anything. AAC, AMR and FLAC
     * are self-describing from byte one, so they get nothing here.
     */
    fun streamHeader(): ByteArray = when (codecId) {
        "opus" -> OggPage.page(OggPage.opusHead(channels = 1), granule = 0, pageSeq = 0)
        else -> ByteArray(0)
    }

    /**
     * Ends the stream. Opus gets a zero-payload page carrying the final granule
     * position, which is what makes a stream that was cut short still playable
     * up to the cut. AAC, AMR and FLAC need nothing: the client's connection
     * close is the end.
     */
    fun streamTrailer(): ByteArray = when (codecId) {
        "opus" -> OggPage.endPage(granule = granule, pageSeq = pageSeq)
        else -> ByteArray(0)
    }

    fun stop() {
        codec?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "encoder $codecId stop was not clean", e)
            }
            it.release()
        }
        codec = null
    }

}
