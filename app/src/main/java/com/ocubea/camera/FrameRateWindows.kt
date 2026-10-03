package com.ocubea.camera

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.round

/**
 * The two rolling frame-rate windows, and why they are separate.
 *
 * The 2026-10-02 pass found a real defect here. `CameraManager` had ONE window,
 * documented as counting "after the FPS limiter ... what the camera actually
 * delivered rather than what the limiter let past", but its only call site sat
 * *after* the limiter's `when` block. At `target_fps=5` it therefore reported
 * ~4.5 while the camera delivered ~17. `AdaptiveResolutionGovernor` reads that
 * number, compared it against the request, and walked the resolution down to
 * the 480x270 floor on a phone whose only hot thread was the camera HAL at 26%
 * of 8 cores. A limiter doing exactly its job was read as a slow device.
 *
 * One window cannot serve both purposes, because the two questions have
 * different answers and the limiter is what makes them differ:
 *
 * - **delivered** — frames the limiter let past. This is what a viewer sees and
 *   what "is the stream meeting the request" means, so it is what the governor
 *   must judge.
 * - **arrived** — every frame the camera handed the analyzer, limiter or not.
 *   This is the device's ceiling. It is what makes a ceiling in the hardware
 *   visible instead of something you have to infer.
 *
 * Pure Kotlin, no Android types, injected clock: the same rule
 * [AdaptiveResolutionGovernor] and [FrameArrivalAccount] follow, and the reason
 * they are testable at all.
 */
class FrameRateWindows(
    /** Width of one window. One second, as production. */
    private val windowMs: Long = 1_000L,
) {

    private val delivered = RollingWindow()
    private val arrived = RollingWindow()

    /**
     * Frames the limiter let past, per second.
     *
     * Null until a full window has closed. Zero is not published: a zero is
     * indistinguishable from "not measured", which is what null means here.
     */
    val deliveredFps: Int? get() = delivered.fps

    /**
     * Frames the camera handed the analyzer, per second, limiter or not.
     *
     * Null on the same convention as [deliveredFps]. Null until a window closes.
     */
    val arrivedFps: Int? get() = arrived.fps

    /** When the current delivery window opened, or 0 if none has. */
    val deliveredWindowStartMs: Long get() = delivered.startOfWindowMs

    /**
     * Folds one frame in. Returns true when the DELIVERED window just closed.
     *
     * That return value is what keeps the governor off the per-frame path: the
     * resolution decision is made at most once a second, from a settled
     * measurement, rather than on every frame from a number still counting up.
     *
     * [arrived] is false only for a frame the camera pushed into a pipeline that
     * was winding down and therefore describes no rate. Frames the *limiter*
     * refused must pass `arrived = true`, which is the whole reason the arrival
     * window is separate: it has to see the frames the limiter threw away.
     *
     * [delivered] is false for exactly those refused frames, and is what keeps
     * the delivery window from being a second copy of the arrival window. It
     * defaults to true so a caller with no limiter in the path needs no flag.
     */
    fun fold(nowMs: Long, arrived: Boolean, delivered: Boolean = true): Boolean {
        if (arrived) this.arrived.fold(nowMs)

        // A frame the limiter refused returns before the delivery window is
        // touched at all. The arrival window has already counted it; that
        // asymmetry is the entire point of the two windows.
        if (!delivered) return false
        return this.delivered.fold(nowMs)
    }

    /** Clears both windows so a rebind cannot be read as a stall. */
    fun reset() {
        delivered.reset()
        arrived.reset()
    }

    /** One window: a frame count over the span since its first frame. */
    private inner class RollingWindow {
        private val started = AtomicBoolean(false)
        private val startMs = AtomicLong(0L)
        private val frames = AtomicLong(0L)

        @Volatile var fps: Int? = null
            private set

        /**
         * When the current window opened, i.e. when its first frame arrived.
         *
         * Published so a consumer can tell a full window from one that opened
         * moments ago. The FPS alone cannot: a window that opened 300 ms ago and
         * saw 9 frames reports the same 9 fps as a full second that saw 9, and
         * the difference between the two is exactly the difference between "the
         * device is slow" and "the device is still starting".
         */
        @Volatile var startOfWindowMs: Long = 0L
            private set

        /** Returns true when this window just closed and published a rate. */
        fun fold(nowMs: Long): Boolean {
            val seen = frames.incrementAndGet()
            if (started.compareAndSet(false, true)) {
                // The window opens on its first frame. A separate flag rather
                // than a zero start timestamp, because nowMs can legitimately be
                // 0 and a zero-as-"not started" sentinel would silently swallow
                // that first frame -- leaving the window unable to ever open.
                startMs.set(nowMs)
                startOfWindowMs = nowMs
                return false
            }
            val s = startMs.get()
            if (nowMs - s < windowMs) return false
            // Losing this race means a concurrent frame already rolled the window
            // over and reset the count, so there is nothing left to publish.
            if (!startMs.compareAndSet(s, nowMs)) return false
            publish(seen - 1, nowMs - s)
            frames.set(1L)
            startOfWindowMs = nowMs
            return true
        }

        fun reset() {
            started.set(false)
            startMs.set(0L)
            frames.set(0L)
            fps = null
            startOfWindowMs = 0L
        }

        /**
         * Publishes [frames] counted over [elapsedMs].
         *
         * The caller passes one less than it holds: the frame that arrived at
         * `nowMs` sits at the end of `[start, nowMs)`, not inside it. Counting it
         * would divide N frames by a window only N-1 of them span and report
         * N/(N-1) too high -- about +6% at 17 fps, the same order as the gaps the
         * governor acts on. It opens the next window instead, which loses nothing:
         * it is still a frame.
         */
        private fun publish(frames: Long, elapsedMs: Long) {
            if (frames <= 0L || elapsedMs <= 0L) return
            val rate = frames * 1000.0 / elapsedMs
            if (!rate.isFinite() || rate < 1.0) return
            fps = round(rate).toInt().coerceIn(1, 240)
        }
    }
}
