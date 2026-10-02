package com.ocubea.camera

/**
 * Which outcome a delivered frame had, decided before any of the expensive
 * work happens.
 *
 * This exists as its own object because the performance pass of 2026-10-02
 * could not find ~half the frames the HAL delivered, and the reason was
 * structural: the counters lived as bare `counter++` statements scattered
 * through a 2500-line `CameraManager`, so nothing tied "entered" to "returned
 * early" to "published" and no JVM test could reach any of them.
 *
 * The accounting must add up to exactly one outcome per entered frame. That is
 * the whole invariant, and it is what makes the gap detectable at all: if
 * `entered - published` is not explained by the early returns, the difference
 * is frames dying somewhere nobody is counting.
 *
 * Pure Kotlin on purpose — no Android types, no clock. `observe` takes the
 * timestamps and the state as arguments, which is the same rule
 * `AdaptiveResolutionGovernor` follows and the only reason this is testable.
 */
class FrameArrivalAccount {

    /** Frames the analyzer was handed, counted before any other decision. */
    var entered: Long = 0L
        private set

    /** Frames dropped by the fps limiter, or by a camera that is winding down. */
    var returnedEarly: Long = 0L
        private set

    /** Frames whose JPEG reached `frameHub.publish`. */
    var published: Long = 0L
        private set

    /**
     * Frames skipped on purpose because no consumer wanted a JPEG.
     *
     * Deliberately separate from [returnedEarly]: the existing
     * `null_bitmaps` counter reports these, and they are not a fault. Keeping
     * them apart is what stopped them being read as one.
     *
     * This is an *outcome* of a frame that [observe] already accepted, not a
     * second entry. So it is deliberately NOT added to [entered].
     */
    var skippedNoConsumer: Long = 0L
        private set

    /**
     * Frames refused because the encoder pool was already behind.
     *
     * Like [skippedNoConsumer] this is the fate of a frame `observe` already
     * counted, so it must not increment [entered] either.
     */
    var droppedSaturated: Long = 0L
        private set

    fun reset() {
        entered = 0L
        returnedEarly = 0L
        published = 0L
        skippedNoConsumer = 0L
        droppedSaturated = 0L
    }

    /**
     * Records one delivered frame and returns what should happen to it.
     *
     * Arguments are explicit rather than read from a singleton so the caller
     * cannot pass a stale frame: [streamingNow] is sampled per frame, and
     * [nowNanos] is the same clock reading the limiter uses, so the decision
     * here and the decision in `analyzeFrame` cannot disagree.
     */
    fun observe(
        streamingNow: Boolean,
        nowNanos: Long,
        lastAcceptedNanos: Long,
        targetFps: Int,
    ): Outcome {
        entered++
        if (!streamingNow) {
            returnedEarly++
            return Outcome.NOT_STREAMING
        }
        // Same arithmetic as the limiter: a zero or negative target would
        // divide by zero, and lastFrameNanos == 0 means "no frame yet", which
        // must pass rather than be compared against an interval.
        if (lastAcceptedNanos != 0L) {
            val interval = 1_000_000_000L / targetFps.coerceAtLeast(1)
            if (nowNanos - lastAcceptedNanos < interval) {
                returnedEarly++
                return Outcome.LIMITED_BY_FPS
            }
        }
        return Outcome.ACCEPTED
    }

    fun recordPublished() {
        published++
    }

    fun recordSkippedNoConsumer() {
        skippedNoConsumer++
    }

    fun recordSaturated() {
        droppedSaturated++
    }

    enum class Outcome {
        /** Passed the limiter; the caller does the JPEG work. */
        ACCEPTED,

        /** Rejected by the fps limiter. */
        LIMITED_BY_FPS,

        /** The camera was winding down. */
        NOT_STREAMING,
    }
    /**
     * Frames that entered and never reached any fate at all.
     *
     * This is the number the 2026-10-02 pass could not compute. It should be 0
     * in a healthy pipeline: a non-zero value means a frame reached the
     * analyzer, was not refused by the limiter or the winding-down check, and
     * then vanished before anyone recorded what became of it -- the one region
     * no counter previously covered.
     *
     * All four fates are subtracted, and every entered frame must land in
     * exactly one of them, so the four terms have to be disjoint. An earlier
     * draft of this file subtracted only three of them, on the reasoning that
     * [skippedNoConsumer] and [droppedSaturated] "describe frames already
     * counted as entered". They do describe such frames, but each frame still
     * reaches only ONE fate: a frame that is skipped is never published, and a
     * frame refused for saturation is never counted as skipped. Omitting two
     * terms reported the frames still in the encoder pool as missing, which is
     * the opposite of what this number is for. Verified against physically
     * reachable counter combinations, not against a hand-written total.
     *
     * Because a frame whose encoding is still in flight has not been recorded
     * yet, this is a *level* and must be read as a rate: a value growing
     * steadily while nothing publishes is a finding. [lost] bounds the whole
     * non-published region for context.
     */
    fun unaccounted(): Long =
        entered - published - returnedEarly - skippedNoConsumer - droppedSaturated

    /**
     * The `frames_lost` field as reported in `/status.json`.
     *
     * Everything the analyzer saw that did not become an output frame, so this
     * includes the deliberate skips and is expected to be large. It is only
     * interesting next to [returnedEarly] and the post-acceptance skips.
     */
    fun lost(): Long = entered - published
}
