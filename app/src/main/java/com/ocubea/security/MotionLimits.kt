package com.ocubea.security

/**
 * Bounds for the two recording lengths, in one place.
 *
 * Both were previously read straight from the HTTP request with no upper bound,
 * so any device on the LAN could set a value that made the camera allocate an
 * arbitrary amount of memory. The pre-roll buffer is
 * `preRecordSeconds * fps` JPEGs held in RAM, which is the sharp edge:
 *
 *   2s    ->    30 frames ->     3 MB
 *   30s   ->   450 frames ->    40 MB
 *   300s  ->  4500 frames ->   401 MB
 *   2000s -> 30000 frames ->  2673 MB
 *
 * The app targets old phones with 512MB of RAM, so a value a user cannot
 * physically want is also a value that kills the process. Limits are expressed
 * in seconds because that is what the setting means, and
 * [MAX_PRE_ROLL_BYTES] exists to catch the case a seconds-only bound misses:
 * the same seconds cost far more at a higher resolution, which the user can
 * change independently.
 */
object MotionLimits {

    const val DEFAULT_PRE_RECORD_SECONDS = 2
    const val MIN_PRE_RECORD_SECONDS = 0
    const val MAX_PRE_RECORD_SECONDS = 30

    const val DEFAULT_MAX_CLIP_SECONDS = 300
    const val MIN_MAX_CLIP_SECONDS = 5
    const val MAX_MAX_CLIP_SECONDS = 1800

    /**
     * Hard ceiling on the pre-roll, in bytes, whatever the resolution.
     *
     * 48MB of JPEG history buys about 9 seconds at 1080p and far more at 720p,
     * which is the trade a low-RAM phone should be making: shorter history at
     * high resolution, longer history at low resolution.
     */
    const val MAX_PRE_ROLL_BYTES = 48L * 1024 * 1024

    /**
     * The pre-roll length actually used, given the requested seconds and the
     * size of one frame.
     *
     * Called on the frame path, so it must stay cheap and allocation-free.
     */
    fun effectivePreRecordSeconds(
        requestedSeconds: Int,
        fps: Int,
        frameBytes: Int,
    ): Int {
        val safeFps = fps.coerceAtLeast(1)
        val safeFrameBytes = frameBytes.coerceAtLeast(1)
        val bySeconds = requestedSeconds.coerceIn(MIN_PRE_RECORD_SECONDS, MAX_PRE_RECORD_SECONDS)
        val framesThatFit = (MAX_PRE_ROLL_BYTES / safeFrameBytes).toInt()
        val secondsThatFit = framesThatFit / safeFps
        return minOf(bySeconds, secondsThatFit)
    }
}
