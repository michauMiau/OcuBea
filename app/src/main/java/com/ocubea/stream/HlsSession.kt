package com.ocubea.stream

import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
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
    private val segmentMs: Int = 250
) {

    private companion object {
        const val TAG = "OcuBeaHLS"
        const val RING_SIZE = 8          // ~2s of video retained for late joiners
    }

    private val encoder = H264Encoder(width, height, fps, bitrate)
    private val muxer = Fmp4Writer()
    private val ring = ArrayDeque<Pair<Int, ByteArray>>()

    @Volatile private var initReady = false
    @Volatile var mediaSequence = 0
        private set
    @Volatile var clients = 0
        private set
    @Volatile var lastError: String = "none"
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
    val segmentsWritten: Long get() = muxer.segmentsWritten

    fun start(): Boolean {
        if (!encoder.start()) {
            lastError = encoder.lastError
            return false
        }
        return true
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
            muxer.append(sample)?.let { seg ->
                synchronized(ring) {
                    ring.addLast(seg.sequence to seg.bytes)
                    while (ring.size > RING_SIZE) ring.removeFirst()
                }
                mediaSequence = seg.sequence
            }
        }
    }

    fun initSegment(): ByteArray? =
        if (initReady) muxer.initSegmentOrNull else null

    /** Segment bytes by sequence number, or null if it has aged out of the ring. */
    fun segment(seq: Int): ByteArray? = synchronized(ring) {
        ring.firstOrNull { it.first == seq }?.second
    }

    /**
     * Renders the live media playlist.
     *
     * Short segment durations are what make playback low-latency: the player
     * only ever waits one segment, and #EXT-X-PLAYLIST-TYPE:EVENT plus a moving
     * media sequence stop it from treating this as a VOD file it can buffer.
     */
    fun playlist(): String {
        // Snapshot just the sequence numbers, not the payload. The previous
        // version copied the whole ring (megabytes of segment data) on every
        // playlist poll — hls.js polls roughly twice a second, so a few clients
        // turned that into sustained allocation and eventually an OOM.
        val seqs: List<Int> = synchronized(ring) { ring.map { it.first } }
        val first = seqs.firstOrNull() ?: mediaSequence

        val sb = StringBuilder(64 + seqs.size * 32)
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:").append(Math.max(1, segmentMs / 1000)).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(first).append('\n')
        sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("#EXT-X-MAP:URI=\"init.mp4\"\n")
        for (seq in seqs) {
            sb.append("#EXTINF:").append(segmentMs / 1000f).append(",\n")
            sb.append("seg").append(seq).append(".m4s\n")
        }
        return sb.toString()
    }

    fun clientJoined() { clients++ }

    fun clientLeft() { if (clients > 0) clients-- }

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
        clients = 0
        Log.i(TAG, "stopped $codecName")
    }
}
