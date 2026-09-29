package com.ocubea.security

import java.util.concurrent.atomic.AtomicReference

/**
 * The previous-frame luma grid, swapped indivisibly.
 *
 * Extracted from [MotionDetector] so the race is testable on the JVM: the
 * detector's own entry points need a real [android.graphics.Bitmap], which the
 * unit test runtime only stubs.
 *
 * The bug this exists to prevent: `previous` used to be a plain `var` written
 * as "read it, compare, then store the new one". `process()` runs on the encode
 * pool, 2-4 frames deep, so two threads read the same predecessor and both
 * stored their own frame. A frame that moved was then compared against itself
 * and reported no motion, and frames reached the recorder in a non-deterministic
 * order. `getAndSet` makes read-and-replace one operation, so every frame is
 * diffed against exactly one predecessor and each predecessor is used once.
 */
class FrameDiffer(
    private val minChangedFraction: Float = 0.02f,
) {
    private val previous = AtomicReference<IntArray?>(null)

    /**
     * Installs [cur] and returns the frame it displaced, or null when this is
     * the first frame. The returned grid must not be mutated by the caller -
     * it is the array another thread may already be reading.
     */
    fun swap(cur: IntArray): IntArray? = previous.getAndSet(cur)

    /** Drops the baseline, so the next frame is treated as the first. */
    fun reset() = previous.set(null)

    /** True when [cur] differs from [prev] by more than [threshold] in enough cells. */
    fun differs(cur: IntArray, prev: IntArray, threshold: Int): Boolean {
        if (cur.size != prev.size) return false
        var changed = 0
        for (i in cur.indices) if (Math.abs(cur[i] - prev[i]) > threshold) changed++
        return changed.toFloat() / cur.size >= minChangedFraction
    }
}
