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

    /**
     * Channel count as given to [start]. Needed later by the container: an ADTS
     * header states the channel configuration in three bits and a demuxer
     * cannot guess it, so it has to be remembered from configuration time.
     */
    private var channels: Int = 1

    companion object {
        private const val TAG = "OcuBeaAudioEnc"

        /** PCM the encoder consumes: 48 kHz mono, 16-bit. */
        const val ENCODER_SAMPLE_RATE = 48_000

        /**
         * MediaCodec hands the Opus encoder's configuration to the output queue
         * as `AOPUSHD` + version + channel count + a full OpusHead. It is not an
         * Opus packet and must not reach the stream.
         */
        internal val OPUS_CONFIG_MAGIC = "AOPUSHD".toByteArray(Charsets.US_ASCII)

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

    /**
     * True when each encoded frame needs a container header around it.
     *
     * Opus needs Ogg. AAC also needs a container, and a different one: an AAC
     * encoder emits a bare access unit, which carries neither the sample rate
     * nor the channel count nor its own length, so a raw `audio/aac` body is
     * undecodable -- ffmpeg answers "Error decoding AAC frame header". The
     * framing for AAC is ADTS, seven bytes in front of every frame, and
     * `audio/aac` is exactly the content type that means "ADTS-framed AAC".
     */
    private val wrapped: Boolean = codecId == "opus" || codecId == "aac"

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
        this.channels = channels
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitrate(bitrate))
                when (codecId) {
                    "aac" -> setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    "opus" -> {
                        // Raw mode: MediaCodec returns bare Opus packets and
                        // leaves the container to us.
                        //
                        // With the flag UNSET the Android encoder prefixes every
                        // packet with its own byte 0x78, which is not a valid
                        // TOC configuration (RFC 6716 defines 0-11), so libopus
                        // rejects the whole stream no matter how correctly the
                        // Ogg pages around it are built -- and those pages were
                        // verified byte-for-byte against a reference libopus file.
                        // MediaFormat.KEY_OPUS_FLAGS does not exist in the
                        // android-35 stubs -- checked, it is a @hide constant.
                        // The underlying key is the literal string.
                        setInteger("opus-flags", 1)
                    }
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
    /**
     * Queues one frame of PCM and returns what the encoder has produced, or an
     * empty array if it has not produced anything yet.
     *
     * The empty result is normal, not an error. A MediaCodec encoder buffers:
     * it emits a frame some time after the input was queued, usually on a later
     * dequeue than the one that followed the queue call. The first version
     * gave the whole exchange 5 ms and returned empty, so the capture loop kept
     * calling with fresh PCM, the codec kept buffering, and the stream carried
     * the header and nothing else -- an Opus file that no player would play and
     * an AAC response with a zero-length body.
     *
     * So this separates the two halves properly: the input is queued with a
     * short wait, and the output is then drained on a deadline long enough to
     * cover the encoder's own latency. 40 ms is the frame period plus slack; the
     * ring buffers return false rather than blocking, so a client that stopped
     * reading costs a dropped frame and not a stalled microphone.
     */
    fun encodeFrame(pcm: ShortArray): ByteArray {
        val codec = this.codec ?: error("encoder not started")
        require(pcm.size == FRAME_SAMPLES) {
            "expected $FRAME_SAMPLES samples, got ${pcm.size}"
        }
        val info = MediaCodec.BufferInfo()
        val out = ByteArray(8_192)

        // Queue the input. `dequeueInputBuffer` returning -1 is normal right
        // after a configure, so this is retried until the deadline rather than
        // treated as a failure.
        val queueDeadline = System.nanoTime() + 10_000_000L
        while (System.nanoTime() < queueDeadline) {
            val inIdx = codec.dequeueInputBuffer(2_000)
            if (inIdx < 0) continue
            codec.getInputBuffer(inIdx)?.let { buf ->
                buf.clear()
                // asShortBuffer() keeps the ByteBuffer's own order, which is
                // the codec's native order; reordering would be a second guess
                // about something the platform already got right.
                buf.asShortBuffer().put(pcm)
            }
            val pts = framesIn * FRAME_SAMPLES * 1_000_000L / ENCODER_SAMPLE_RATE
            codec.queueInputBuffer(inIdx, 0, FRAME_BYTES, pts, 0)
            framesIn++
            break
        }

        // Drain output until something actually comes out.
        val drainDeadline = System.nanoTime() + 40_000_000L
        while (System.nanoTime() < drainDeadline) {
            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            if (outIdx < 0) continue
            if (info.size > 0) {
                val payload = copyPayload(codec, outIdx, info, out)
                // Opus comes out of MediaCodec with a codec-configuration buffer
                // that carries "AOPUSHD" and a full copy of OpusHead, mixed into
                // the data stream. That is a Matroska/EBML-style header, not an
                // Opus packet: sending it as page payload put
                // `AOPUSHD...OpusHead...` into the audio stream, and libopus then
                // read a TOC byte of 65 (a config the codec does not support)
                // and the player refused the file. The config is not needed in
                // the Ogg stream -- OpusHead already carries it -- so it is
                // dropped here.
                if (isOpusConfigPayload(payload)) {
                    codec.releaseOutputBuffer(outIdx, false)
                    return ByteArray(0)
                }
                // Granule position is the number of 48 kHz samples that END on
                // this page, and the FIRST audio page is the exception: it ends
                // ZERO samples, because nothing has finished playing yet.
                //
                // The Ogg Opus mapping says the first audio page carries
                // granulepos == -1, and libopus indeed accepts that. ffmpeg's
                // ogg demuxer does not: it treats a -1 as "this page has no
                // granule" and rejects the stream with "Page at N is missing
                // granule". A real libopus file uses granule=0 on the first
                // audio page, which both accept, so that is what this writes --
                // verified against a reference file produced by ffmpeg's own
                // libopus encoder, byte for byte on the same field.
                val thisGranule = granule
                granule += FRAME_SAMPLES
                // The buffer MUST be released whether or not it carried a
                // payload. A MediaCodec holds a fixed pool of output buffers
                // and every one taken and not given back is gone for good: the
                // first version returned from inside this branch without
                // releasing, so after the codec's initial burst the dequeue
                // returned TRY_AGAIN forever and the stream stopped at whatever
                // the header plus one frame happened to be.
                codec.releaseOutputBuffer(outIdx, false)
                return when (codecId) {
                    "opus" -> OggPage.page(payload, thisGranule, pageSeq++)
                    "aac" -> AdtsFrame.frame(payload, sampleRate = ENCODER_SAMPLE_RATE, channels = channels)
                    else -> payload
                }
            }
            // A zero-size buffer is a codec-internal event (format change,
            // codec config); releasing it is required or the codec stalls.
            codec.releaseOutputBuffer(outIdx, false)
        }
        return ByteArray(0)
    }

    /**
     * True for the Matroska-style Opus configuration buffer, which is
     * "AOPUSHD" followed by a length and a complete copy of OpusHead.
     *
     * Pure byte inspection, no platform types, so it is testable on the JVM.
     */
    internal fun isOpusConfigPayload(payload: ByteArray): Boolean {
        // The loop bound is the magic's own length, not a literal 8: the
        // constant is "AOPUSHD", seven bytes, and indexing eight of them threw
        // ArrayIndexOutOfBoundsException: length=7; index=7 on the very first
        // frame of every stream.
        for (i in OPUS_CONFIG_MAGIC.indices) {
            if (i >= payload.size) return false
            if (payload[i] != OPUS_CONFIG_MAGIC[i]) return false
        }
        return true
    }

    private fun copyPayload(
        codec: MediaCodec,
        outIdx: Int,
        info: MediaCodec.BufferInfo,
        into: ByteArray
    ): ByteArray {
        codec.getOutputBuffer(outIdx)?.let { buf ->
            // info.offset is where the payload starts inside the buffer. The
            // Android Opus encoder writes a small amount of framing ahead of the
            // packet -- with the first byte unaccounted for, the TOC decoded as
            // configuration 15, which is not a valid Opus mode, and libopus
            // refused the stream. Skipping to info.offset is what the API means.
            val start = info.offset
            val available = buf.remaining()
            if (start >= available) return ByteArray(0)
            buf.position(buf.position() + start)
            val n = minOf(info.size, buf.remaining())
            if (n <= 0) return ByteArray(0)
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
        // BOS is page 0 and MUST consume sequence number 0, so the encoder's
        // own counter has to be advanced with it. Emitting the header as page 0
        // while the first audio page also claimed 0 produced a duplicate
        // sequence number, which every demuxer treats as a broken stream --
        // ffmpeg refused the file outright even though every CRC was valid.
        "opus" -> {
            val head = OggPage.page(
                OggPage.opusHead(channels = 1), granule = 0, pageSeq = pageSeq
            )
            pageSeq++
            head
        }
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
