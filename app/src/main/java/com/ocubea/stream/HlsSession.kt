package com.ocubea.stream

import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import com.ocubea.stream.Fmp4Writer.Segment
import java.util.ArrayDeque

/**
 * Low-latency HLS session built on the hardware H.264 encoder.
 *
 * A fixed-size ring of recent segments is kept so a client joining now is
 * handed a few seconds of already-encoded video instead of waiting for new
 * segments. That ring is also what bounds memory: old segments are dropped
 * rather than accumulated.
 */
class HlsSession(
    val width: Int,
    val height: Int,
    fps: Int,
    bitrate: Int,
    /**
     * Segment length and keyframe interval together. They arrive as one value
     * because they are not independent: a segment can only begin on an IDR, so
     * a long GOP with a short segment would advertise durations that never
     * arrive and the playlist would drain.
     */
    val profile: com.ocubea.model.HlsProfile = com.ocubea.model.HlsProfile.DEFAULT
) {

    internal companion object {
        const val TAG = "OcuBeaHLS"
        // 20 segments at 250ms is ~5s of video retained for late joiners.
        //
        // The old value was 8 (~2s), which was too tight to survive a client
        // that is not polling instantly: hls.js reads a playlist, then requests
        // the segment it points at, and any segment older than the ring window
        // answers 404. That is what the console showed:
        // "GET /hls/seg141.m4s 404" for a sequence number the playlist had
        // itself advertised moments earlier. The ring is the client's buffer
        // and has to cover a slow poll, a stalled request and the initial
        // seek-back, so it is sized in seconds, not in "segments".
        //
        // The playlist must advertise only as much history as a client is meant
        // to have buffered: hls.js seeks to (live edge - liveSyncDuration), and
        // every extra segment in the manifest is a segment it may load before
        // it starts playing. Measured on the phone with the ring at 20 and the
        // DEFAULT profile: 4.14s on the playlist. With LOW_LATENCY (120ms
        // segments) 20 segments is nominally 2.4s, but the phone delivers frames
        // slower than 120ms, so the measured playlist was still 3.98s -- the
        // count was the binding constraint, not the length.
        //
        // So the ring is sized in SECONDS and the count follows from it. A
        // 4s window is enough to survive a slow poll plus a stalled request
        // (the reason the old fixed 8 was raised: "GET /hls/seg141.m4s 404" for
        // a sequence the playlist had just advertised), while capping how far
        // behind live a client can be parked.
        // A floor, not a target: the profile's own sync count is the target, and
        // a client only ever needs enough history to cover its seek target plus
        // one playlist refresh in flight.
        //
        // Measured on the phone with a flat 4s window: DEFAULT (sync=3) held
        // 3.90s and LOW_LATENCY (sync=1) held 3.90s too -- a single flat window
        // ignores the profile, so switching to LOW_LATENCY changed nothing an
        // end viewer could see. That is the exact complaint that started this.
        const val RING_SECONDS_FLOOR = 2

        /**
         * Seconds of history to retain for a profile: two segments per sync
         * unit, so hls.js's seek target (live edge - liveSyncDurationCount) plus
         * the refresh in flight is always inside the ring, with a floor so a
         // late joiner can still find something.
         */
        internal fun ringSecondsFor(profile: com.ocubea.model.HlsProfile): Int =
            maxOf(RING_SECONDS_FLOOR, profile.liveSyncDurationCount)

        /** Segments to retain for a profile and a MEASURED segment length. */
        internal fun ringSizeFor(profile: com.ocubea.model.HlsProfile, segmentMs: Int): Int =
            maxOf(6, (ringSecondsFor(profile) * 1000) / segmentMs.coerceAtLeast(1))

        /** Test seam: the companion is private, this reaches it from outside. */
        internal fun ringSizeForTest(segmentMs: Int): Int =
            ringSizeFor(com.ocubea.model.HlsProfile.DEFAULT, segmentMs)

        /** Test seam for the profile-driven path. */
        internal fun ringSizeForProfileTest(
            profile: com.ocubea.model.HlsProfile,
            segmentMs: Int,
        ): Int = ringSizeFor(profile, segmentMs)
    }

    private val encoder =
        H264Encoder(width, height, fps, bitrate, profile.keyFrameIntervalSec)
    // The muxer MUST get the real frame rate. It uses it for the duration of a
    // single-frame segment, and a wrong value punches holes in the playback
    // timeline: with fps defaulted to 30 while the camera actually delivers one
    // frame every ~241ms, each segment claimed 33ms of media, so six
    // consecutive segments left six separate 193ms gaps in SourceBuffer.buffered
    // and the decoder never saw a continuous range — readyState stayed at 1 and
    // nothing painted, even though every segment was accepted and buffered.
    private val muxer = Fmp4Writer(fps, profile.segmentMs.toLong())
    private val ring = ArrayDeque<Segment>()

    @Volatile private var initReady = false
    @Volatile var mediaSequence = 0
        private set
    @Volatile var lastError: String = "none"
        private set

    /**
     * Length of the most recent segment actually cut, in milliseconds.
     *
     * The profile's segmentMs is a request, not a promise. Fmp4Writer cuts on
     * the next IDR, so in low-latency mode - one IDR per frame - every segment
     * is exactly one frame long, and the request is never reached. Telemetry
     * that reports only the request describes a stream that does not exist.
     */
    @Volatile var lastSegmentDurationMs: Long = 0L
        private set

    /**
     * True once the encoder is running, regardless of whether it has produced
     * its first output yet.
     *
     * This is deliberately distinct from [isActive]: the camera needs to start
     * feeding frames *before* the first SPS/PPS arrives, so gating feeding on
     * "has produced segments" would deadlock and never start at all.
     */
    val isEncoding: Boolean get() = encoder.isRunning

    /** True when both the encoder runs and a playlist can actually be served. */
    val isActive: Boolean get() = encoder.isRunning && initReady
    val codecName: String get() = encoder.codecName
    val framesEncoded: Long get() = encoder.framesEncoded
    val framesQueued: Long get() = encoder.framesQueued
    val framesDropped: Long get() = encoder.framesDropped

    /**
     * Encoder stops that reached MediaCodec.stop()/release().
     *
     * Static on purpose -- see H264Encoder.Companion.stopsCompleted. An instance
     * counter reads zero after every profile switch, because the switch creates
     * a new encoder, and that is exactly when there is something to report.
     */
    val stopsCompleted: Long get() = H264Encoder.stopsCompleted.get()

    /**
     * Encoder stops that found the analyzer thread still inside the codec.
     *
     * Non-zero means the handshake fired: the analyzer was mid-drain and the
     * codec was kept alive instead of released under it. Zero is ambiguous on
     * its own -- it is also what a build with no handshake reports -- which is
     * why both counters are exposed together.
     */
    val stopsDeferred: Long get() = H264Encoder.stopsDeferred.get()

    /**
     * Codecs actually released. Every stop must land here exactly once, whether
     * it ran inline or was deferred to the drain thread -- the sum of
     * stopsCompleted + stopsDeferred has to match it, and that identity is the
     * only thing that catches a dropped release.
     */
    val codecsReleased: Long get() = H264Encoder.released.get()

    /** Total nanoseconds encodes held the codec handle. */
    val encodeHeldNanos: Long get() = H264Encoder.encodeHeldNanos.get()

    /** Number of encodes, so the above can be averaged. */
    val encodeCalls: Long get() = H264Encoder.encodeCalls.get()

    /**
     * Diagnostic hold, milliseconds. Default 0 = off.
     *
     * Set through /diagnostic/encoder_hold. Exists so the stop/teardown
     * handshake can be shown to fire on demand rather than inferred from
     * "it has never fired in 155 tries" -- which is equally consistent with a
     * working guard behind a now-narrow window and with dead code.
     */
    fun setDiagnosticHold(ms: Long) { encoder.diagnosticHoldMs = ms.coerceIn(0, 5000) }
    val diagnosticHoldMs: Long get() = encoder.diagnosticHoldMs.toLong()

    /**
     * The handshake counters, in one value.
     *
     * Grouped because a diagnostic endpoint wants all three and a caller reaching
     * into three separate properties is how one of them silently goes stale.
     */
    data class Telemetry(
        val stopsCompleted: Long,
        val stopsDeferred: Long,
        val encodeCalls: Long,
        val codecsReleased: Long = 0L,
    ) {
        companion object { val EMPTY = Telemetry(0, 0, 0) }
    }

    fun telemetry(): Telemetry =
        Telemetry(stopsCompleted, stopsDeferred, encodeCalls, codecsReleased)

    /** Per-step hold breakdown, each summed over all encodes. */
    val tDrain1: Long get() = H264Encoder.tDrain1.get()
    val tDequeue: Long get() = H264Encoder.tDequeue.get()
    val tCopy: Long get() = H264Encoder.tCopy.get()
    val tDrain2: Long get() = H264Encoder.tDrain2.get()

    /** pixelStride -> plane copies seen at that stride. */
    fun strideSeen(): Map<Int, Long> = H264Encoder.strideSeen.toMap()
    val segmentsWritten: Long get() = muxer.segmentsWritten

    /**
     * Bytes the encoder produced, so bitrate can be measured rather than
     * assumed. A profile that promises to spend fewer bits cannot be checked
     * without this: KEY_BIT_RATE is the request, not the result.
     */
    val bytesEncoded: Long get() = encoder.bytesEncoded

    fun start(): Boolean {
        if (!encoder.start()) {
            lastError = encoder.lastError
            return false
        }
        return true
    }

    /**
     * Frames per second actually produced, measured over a short sliding window
     * of encoded samples.
     *
     * The requested fps is a ceiling, not a fact: this camera's ImageAnalysis
     * yields well below it under load, and status.json regularly reports 15 for a
     * target of 30. The muxer needs the truth, because it stamps every
     * single-frame segment with one frame interval as its duration — too short a
     * value leaves gaps between segments and the decoder never assembles a
     * continuous range to paint.
     */
    @Volatile private var windowFrames = 0
    @Volatile private var windowStartUs = 0L
    @Volatile var measuredFps: Double = 0.0
        private set

    private fun noteFrame() {
        val now = System.nanoTime() / 1000
        if (windowStartUs == 0L) {
            windowStartUs = now
            windowFrames = 1
            return
        }
        windowFrames++
        val elapsedUs = now - windowStartUs
        if (elapsedUs >= 1_000_000L) {
            val fps = windowFrames.toDouble() * 1_000_000.0 / elapsedUs
            // Ignore absurd readings from a window that was starved by a pause.
            if (fps in 0.5..120.0) {
                measuredFps = if (measuredFps == 0.0) fps else measuredFps * 0.7 + fps * 0.3
            }
            windowStartUs = now
            windowFrames = 0
        }
    }

    /** Feeds one camera frame; segments are produced here, not on a timer. */
    fun encodeFrame(image: ImageProxy, ptsUs: Long) {
        if (!encoder.isRunning) return
        encoder.encode(image, ptsUs) { sample ->
            if (!initReady) {
                // The encoder emits SPS/PPS as a codec-config buffer; the avcC
                // record is derived from it, and the init segment cannot be
                // built without. A null result means the payload had no usable
                // SPS yet — keep waiting rather than caching a broken config.
                val cfg = encoder.codecConfig ?: return@encode
                runCatching { muxer.initSegmentFor(width, height, cfg) }
                    .onSuccess { if (it != null) initReady = true }
                    .onFailure { lastError = it.message ?: "mux init failed" }
                return@encode
            }
            noteFrame()
            if (measuredFps > 0.0) muxer.setMeasuredFps(measuredFps)
            muxer.append(sample)?.let { seg ->
                // Trim by measured duration rather than by a fixed count: the
                // profile's segmentMs is the REQUEST and the phone delivers
                // frames slower than that, so a count-based ring holds far more
                // seconds than intended on a slow device.
                val keep = ringSizeFor(profile, seg.durationMs.toInt().coerceAtLeast(1))
                synchronized(ring) {
                    ring.addLast(seg)
                    while (ring.size > keep) ring.removeFirst()
                }
                mediaSequence = seg.sequence
                lastSegmentDurationMs = seg.durationMs
            }
        }
    }

    fun initSegment(): ByteArray? =
        if (initReady) muxer.initSegmentOrNull else null

    /** Segment bytes by sequence number, or null if it has aged out of the ring. */
    fun segment(seq: Int): ByteArray? = synchronized(ring) {
        ring.firstOrNull { it.sequence == seq }?.bytes
    }

    /**
     * Renders the live media playlist.
     *
     * Short segment durations are what make playback low-latency: the player
     * only ever waits one segment, and #EXT-X-PLAYLIST-TYPE:EVENT plus a moving
     * media sequence stop it from treating this as a VOD file it can buffer.
     */
    fun playlist(): String {
        // Snapshot the metadata, not the payload. The previous version copied
        // the whole ring (megabytes of segment data) on every playlist poll —
        // hls.js polls roughly twice a second, so a few clients turned that
        // into sustained allocation and eventually an OOM.
        val entries: List<Segment> = synchronized(ring) { ring.toList() }
        val first = entries.firstOrNull()?.sequence ?: mediaSequence

        // #EXTINF must state the segment's real duration. Writing a fixed
        // segmentMs for every entry made the player compute its buffer and
        // latency from a number that did not match the bytes: the muxer cuts
        // a segment as soon as an IDR arrives, so with one IDR per frame
        // segments really are one frame long, and a playlist claiming 0.25s
        // for a 0.16s segment desynchronises the player's clock from the
        // media timeline.
        //
        // #EXT-X-TARGETDURATION has to be the largest advertised duration,
        // rounded up, or a client is allowed to reject the playlist outright.
        val longestMs = entries.maxOfOrNull { it.durationMs } ?: 0L

        val sb = StringBuilder(64 + entries.size * 32)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:")
            .append(if (longestMs <= 0L) 1 else (longestMs + 999) / 1000)
            .append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
        sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        // The playlist is served at BOTH /hls.m3u8 and /hls/index.m3u8, but
        // segments exist only under /hls/. A relative URI in the playlist is
        // resolved against the playlist's own directory, so a client that loaded
        // /hls.m3u8 asked for /seg106.m4s and got a 404 for every fragment
        // (measured 2026-09-30: 200 on the playlist, 404 on every segment).
        // Absolute URIs make the playlist correct under both paths.
        sb.append("#EXT-X-MAP:URI=\"/hls/init.mp4\"\n")
        for (seg in entries) {
            // "." not a locale separator. String.format follows the default
            // locale, and a comma here is invalid HLS: ffmpeg reports
            // "Cannot get correct #EXTINF value" for every segment and
            // substitutes 1ms, which is enough to lose sync. The playlist is
            // a wire format, so it is written in a fixed locale.
            sb.append("#EXTINF:")
                .append(formatExtInf(seg.durationMs))
                .append(",\n")
            sb.append("/hls/seg").append(seg.sequence).append(".m4s\n")
        }
        return sb.toString()
    }

    /** #EXTINF seconds, always with a dot, rounded to milliseconds. */
    private fun formatExtInf(durationMs: Long): String {
        val millis = durationMs.coerceAtLeast(0L)
        val whole = millis / 1000
        val frac = millis % 1000
        return "$whole." + frac.toString().padStart(3, '0')
    }

    /**
     * Stops the encoder once nobody is watching.
     *
     * Kept off the last client deliberately: a stopped-and-restarted encoder
     * has to renegotiate with the camera HAL, and clients routinely come and go
     * in bursts. Stopping on an idle timer instead keeps join latency flat.
     */
    fun stop() {
        encoder.stop()
        initReady = false
        synchronized(ring) { ring.clear() }
        Log.i(TAG, "stopped $codecName")
    }
}
