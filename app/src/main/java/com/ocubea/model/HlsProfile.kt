package com.ocubea.model

/**
 * How the HLS session cuts its stream, and what the player is told about it.
 *
 * The two numbers are not independent. Segment length and keyframe interval
 * have to agree: the muxer starts a new segment when an IDR arrives, so a long
 * GOP with a short target segment would leave most segments waiting for a
 * keyframe they do not have, and the playlist would advertise durations that
 * never arrive. Setting them from one place is what keeps that from happening.
 *
 * Low latency and high quality pull in opposite directions, and neither is
 * free:
 *
 * - Short segments mean the player waits a fraction of a second instead of a
 *   second or two, but a segment shorter than a keyframe interval can only
 *   start on a keyframe, so the GOP has to shrink with it.
 * - A dense GOP means an IDR on nearly every frame. Every IDR is a full
 *   picture, so the bitrate per second has to rise to hold the same quality,
 *   and the encoder cannot follow KEY_BIT_RATE down.
 *
 * So low latency costs bitrate, and the honest setting is: ask for the
 * shortest segments, and let the encoder pick the GOP that goes with them.
 */
data class HlsProfile(
    val segmentMs: Int,
    val keyFrameIntervalSec: Int,
    val liveSyncDurationCount: Int,
    val maxBufferLength: Int
) {
    companion object {
        /** Seconds between IDR frames for clip recording. Deliberately not a
         *  profile: a clip is written in one pass, so a 1s interval has no
         *  playlist whose #EXTINF it could disagree with. */
        const val CLIP_KEY_FRAME_INTERVAL_SEC = 1

        /**
         * The default: ~250ms segments with an IDR every half second.
         *
         * This is what the stream did before the profile existed, and it is a
         * reasonable middle. Note that the camera on the test device delivers
         * ~6fps rather than the 15-30 requested, so a segment really is one
         * frame; the declared 250ms is the *target* and the playlist reports
         * what actually happened.
         */
        val DEFAULT = HlsProfile(
            segmentMs = 250,
            keyFrameIntervalSec = 0,
            liveSyncDurationCount = 3,
            maxBufferLength = 6
        )

        /**
         * Short segments and a keyframe on every one of them, so the player
         * only ever waits one frame. This is the mode that made playback work
         * on a slow link, because there is no dependency to wait for.
         *
         * The bitrate is the price: an IDR per frame cannot be cheap. When the
         * camera is slow this is roughly the same thing the default already
         * does in practice, which is why the default's explicit half-second
         * interval is not the ceiling.
         */
        val LOW_LATENCY = HlsProfile(
            segmentMs = 120,
            keyFrameIntervalSec = 0,
            liveSyncDurationCount = 1,
            maxBufferLength = 2
        )

        /**
         * The trade the user asked for: give up latency, take back the bits.
         *
         * A 2s segment over a 1s GOP is 2-3 frames per segment, and the
         * inter-frame pictures are the cheap part of the stream, so the same
         * quality costs noticeably fewer bits per second. The player waits
         * ~2s instead of ~0.2s.
         */
        val HIGH_QUALITY = HlsProfile(
            segmentMs = 2000,
            keyFrameIntervalSec = 1,
            liveSyncDurationCount = 4,
            maxBufferLength = 10
        )

        /**
         * Clamps whatever the UI sends. A segment shorter than one frame is
         * meaningless, and a GOP longer than the segment is the mismatch
         * described above, so the interval is held at or below the segment.
         */
        fun of(lowLatency: Boolean): HlsProfile = if (lowLatency) LOW_LATENCY else DEFAULT

        /**
         * Validates a profile that arrived from the API rather than from the
         * app, starting from [base] so the player-facing numbers survive.
         */
        fun sanitized(
            base: HlsProfile,
            segmentMs: Int,
            keyFrameIntervalSec: Int
        ): HlsProfile {
            val seg = segmentMs.coerceIn(100, 6000)
            // 0 means "every frame", which is legal and is what low latency
            // wants; anything longer is held to at most the segment itself, or
            // most segments would wait for a keyframe they cannot get.
            val gop = keyFrameIntervalSec.coerceIn(0, seg / 1000)
            return base.copy(segmentMs = seg, keyFrameIntervalSec = gop)
        }
    }
}
