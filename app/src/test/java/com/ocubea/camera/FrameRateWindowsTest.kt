package com.ocubea.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rate windows, pinned against the defect they were extracted to fix.
 *
 * The bug this exists for: `CameraManager` had one window whose documentation
 * said it counted "after the FPS limiter ... what the camera actually delivered
 * rather than what the limiter let past", but whose only call site was after the
 * limiter. At `target_fps=5` it reported ~4.25 while the camera delivered ~17,
 * `AdaptiveResolutionGovernor` read that as a slow device, and the resolution
 * walked down to the 480x270 floor on a phone with headroom to spare.
 *
 * The load-bearing test here is `arrival counts the frames the limiter refused`:
 * every test fails if the two windows are merged back into one, and that merge
 * is the regression.
 *
 * A note on why the assertions carry a tolerance. Production delivery is NOT
 * exactly the target: the limiter measures from the last accepted frame, so its
 * window is offset by one interval and a limit of 5 measures 5 frames over
 * ~1176 ms -- 4.25 fps. Asserting exact equality on the delivery rate would mean
 * asserting a number the real pipeline cannot produce, and the test would then
 * pass or fail for reasons unrelated to the behaviour under test. What matters is
 * that arrival reports the camera rate, delivery reports the limiter rate, and
 * the two are distinguishable -- so those are what is asserted.
 */
class FrameRateWindowsTest {

    private val WINDOW = 1_000L

    private fun windows() = FrameRateWindows(WINDOW)

    /** Asserts [actual] is within [tolerance] of [expected]. */
    private fun assertNear(expected: Int, actual: Int?, tolerance: Int, what: String) {
        assertNotEquals("$what must be measured, not null", null, actual)
        val got = actual!!
        assertTrue(
            "$what: expected ~$expected, got $got (tolerance $tolerance)",
            kotlin.math.abs(got - expected) <= tolerance,
        )
    }

    /**
     * Folds several windows of a camera delivering [rate] frames/s through a
     * limiter set to [limit] fps -- the production shape. The camera hands every
     * frame to the analyzer and the limiter refuses some of them there, so the
     * refused frames arrive and are never delivered. That asymmetry is the only
     * reason the two windows can differ, and therefore the only reason there are
     * two.
     *
     * The limiter admits one frame every 1000 / [limit] ms measured from the last
     * accepted frame, which is why delivery lands a fraction below [limit] rather
     * than on it. [windows] windows are folded so both samples are settled.
     */
    private fun FrameRateWindows.foldWindows(
        rate: Int,
        limit: Int,
        windows: Int = 6,
        startMs: Long = 0L,
    ): Long {
        val arrivalStep = 1000L / rate
        val limitStep = 1000L / limit
        var now = startMs
        // A nullable "never accepted" rather than Long.MIN_VALUE: `now - MIN_VALUE`
        // overflows Long and wraps negative, which would refuse the first frame
        // and leave the window unable to open at all.
        var lastAccepted: Long? = null
        // Fold enough frames to span `windows` windows for both.
        val total = rate * windows + 8
        repeat(total) {
            val accepted = lastAccepted == null || (now - lastAccepted!!) >= limitStep
            if (accepted) lastAccepted = now
            fold(now, arrived = true, delivered = accepted)
            now += arrivalStep
        }
        return now
    }

    // ── The property that makes two windows necessary ──────────

    @Test
    fun `arrival counts the frames the limiter refused`() {
        // Measured on the F3311 at target_fps=5: the camera handed the analyzer
        // ~17/s while the limiter passed ~4.25. These are the two numbers the
        // governor was confusing.
        val w = windows()
        w.foldWindows(rate = 17, limit = 5)

        assertNear(17, w.arrivedFps, 1, "arrival must report the camera rate")
        assertNear(5, w.deliveredFps, 1, "delivery must report the limiter rate")
        assertNotEquals(
            "delivery and arrival must differ when the limiter refuses frames -- " +
                "if these were equal the two windows would be redundant",
            w.arrivedFps, w.deliveredFps,
        )
    }

    @Test
    fun `delivery and arrival agree when nothing is refused`() {
        val w = windows()
        w.foldWindows(rate = 12, limit = 12)

        assertNear(12, w.deliveredFps, 1, "delivery with no refusal")
        assertNear(
            12, w.arrivedFps, 1,
            "with no refusal there is nothing to distinguish, so arrival must match",
        )
    }

    @Test
    fun `the arrival window sees refused frames but the delivered window does not`() {
        val w = windows()
        w.foldWindows(rate = 20, limit = 3)

        assertNear(20, w.arrivedFps, 1, "every frame arrives")
        assertNear(3, w.deliveredFps, 1, "only the limiter's frames are delivered")
    }

    // ── Winding-down frames are not rate samples ───────────────

    @Test
    fun `a frame delivered while winding down is not counted as an arrival`() {
        val w = windows()
        w.foldWindows(rate = 10, limit = 10)
        val beforeDelivered = w.deliveredFps
        val beforeArrived = w.arrivedFps
        assertNotEquals("precondition: a real window was measured", null, beforeDelivered)

        // A frame the camera pushed into a pipeline that was shutting down. It
        // describes no rate, so neither window may move.
        w.fold(900_000L, arrived = false, delivered = false)

        assertEquals("a wind-down frame must not move delivery", beforeDelivered, w.deliveredFps)
        assertEquals("a wind-down frame must not move arrival", beforeArrived, w.arrivedFps)
    }

    // ── Never publish a number that means "not measured" ────────

    @Test
    fun `no rate is published before a full window has closed`() {
        val w = windows()
        assertNull("nothing measured yet", w.deliveredFps)
        assertNull("nothing measured yet", w.arrivedFps)

        // A single frame opens the window but must not publish anything.
        w.fold(0L, arrived = true)
        assertNull("one frame is not a measurement", w.deliveredFps)
        assertNull("one frame is not a measurement", w.arrivedFps)
    }

    @Test
    fun `a window shorter than its width publishes nothing`() {
        val w = windows()
        w.fold(0L, arrived = true)
        w.fold(100L, arrived = true)
        w.fold(200L, arrived = true)
        assertNull(w.deliveredFps)
        assertNull(w.arrivedFps)
    }

    @Test
    fun `the first frame at timestamp zero still opens the window`() {
        // A zero start timestamp was once used as the "not started" sentinel, which
        // silently swallowed a first frame arriving at nowMs == 0 and left the
        // window unable to ever open. Production uses currentTimeMillis so it
        // never hits this -- which is exactly why it needed a test.
        val w = windows()
        w.fold(0L, arrived = true)
        assertNull("the opening frame publishes nothing", w.arrivedFps)

        // Enough real time must pass for the window to close: 18 more frames at
        // 58 ms reach 1044 ms, and the published window spans 1044 ms.
        (1..18).forEach { i -> w.fold(i * 58L, arrived = true) }
        assertNear(17, w.arrivedFps, 1, "the window opened on a frame at t=0")
    }

    // ── The boundary frame belongs to the window it closed ─────

    @Test
    fun `the frame that closes a window is counted in the next one`() {
        // Off-by-one at the boundary: the frame arriving at nowMs sits at the end
        // of [start, nowMs), not inside it. Counting it would divide N frames by a
        // window only N-1 of them span and report N/(N-1) too high -- about +6% at
        // 17 fps, the same order as the gaps the governor acts on.
        val w = windows()
        // Frames at 0..928 open the window; the one at 986 does not yet close it
        // (986 < 1000), and the one at 1044 does. The published window therefore
        // spans 1044 ms and holds 18 frames, i.e. 17.24 -> 17.
        (0..18).forEach { i -> w.fold(i * 58L, arrived = true) }
        assertEquals(
            "the closing frame must not be counted in the window it closes",
            17, w.arrivedFps,
        )
    }

    @Test
    fun `the fold return value marks the delivered window closing`() {
        val w = windows()
        // 10 frames at 100 ms span 900 ms, so a 1000 ms window cannot close in
        // them. Exactly zero closes -- one would mean the width is ignored.
        val results = (0 until 10).map { w.fold(it * 100L, arrived = true) }
        assertTrue("the first frame only opens the window", !results[0])
        assertEquals(
            "no 1000 ms window can close inside a 900 ms span",
            0, results.count { it },
        )
        assertTrue(
            "the frame at the window width closes the delivered window",
            w.fold(1_000L, arrived = true),
        )
    }

    // ── Reset, which is what a rebind relies on ────────────────

    @Test
    fun `reset clears both windows and both published rates`() {
        val w = windows()
        w.foldWindows(rate = 15, limit = 15)
        assertNotEquals("precondition: delivery measured", null, w.deliveredFps)
        assertNotEquals("precondition: arrival measured", null, w.arrivedFps)

        w.reset()

        assertNull("a rebind must not report the pre-rebind rate", w.deliveredFps)
        assertNull("a rebind must not report the pre-rebind rate", w.arrivedFps)
    }

    @Test
    fun `after reset the next window is measured from scratch`() {
        val w = windows()
        w.foldWindows(rate = 15, limit = 15)
        val first = w.deliveredFps
        assertNotEquals("precondition: delivery measured", null, first)
        w.reset()

        // A fresh rate must report itself, not blend with the previous window.
        w.foldWindows(rate = 8, limit = 8)
        assertNear(
            8, w.deliveredFps, 1,
            "the window after a reset reports the new rate, not the old one",
        )
        assertNear(8, w.arrivedFps, 1, "arrival after a reset")
    }

    @Test
    fun `reset part way through a window discards the partial count`() {
        // A rebind does not land on a window boundary -- it lands wherever the
        // old camera happened to stop. If reset kept the partial count, the next
        // window would report it as part of its own and overstate the rate for up
        // to a second after every rebind.
        val w = windows()
        // 7 arrivals into a window that needs 1000 ms: nothing published yet, but
        // a real count is held.
        (0..6).forEach { i -> w.fold(i * 100L, arrived = true) }
        assertNull("precondition: the window has not closed", w.arrivedFps)

        w.reset()

        // Exactly ONE window after the reset -- not a run of them. Folding six
        // windows would let the stale count wash out after the first close and
        // the test would pass on a broken reset. 10 arrivals at 100 ms open at
        // 10000 ms and the 11th at 11000 ms closes, publishing 10 over 1000 ms.
        (0..10).forEach { i -> w.fold(10_000L + i * 100L, arrived = true) }
        assertNear(10, w.arrivedFps, 1, "the partial count must not leak past a reset")
    }

    @Test
    fun `a reset mid-flight does not lose the frame that followed it`() {
        val w = windows()
        w.foldWindows(rate = 10, limit = 10)
        w.reset()
        // A rebind delivers its first frame right after the reset.
        w.fold(100_000L, arrived = true)
        w.foldWindows(rate = 6, limit = 6, startMs = 100_166L)

        assertNear(6, w.arrivedFps, 1, "the window after a rebind reports its own rate")
    }

    // ── The sample the governor actually reads ──────────────────

    @Test
    fun `delivery is sampled on the window the governor reads`() {
        // The governor compares delivery against the request. If this ever
        // reports the arrival rate again, the 2026-10-02 defect is back -- so
        // this is the measured scenario: 17 arriving, limiter at 5.
        val w = windows()
        w.foldWindows(rate = 17, limit = 5)
        val delivered = w.deliveredFps!!
        val arrived = w.arrivedFps!!
        assertNear(17, arrived, 1, "arrival is the camera rate")
        assertNear(5, delivered, 1, "delivery is the limiter rate")
        assertTrue(
            "delivery ($delivered) is what gets judged against the request (5), " +
                "and must never be the arrival rate",
            delivered < arrived,
        )
    }

    // ── The call site, which is where the production bug actually was ──

    @Test
    fun `CameraManager folds arrival in the refusal arm and delivery after it`() {
        // The class was correct and its 13 tests passed while production still
        // reported arrived_fps == measured_fps == 11, because the bug was never
        // in fold -- it was that a limiter-refused frame returns from
        // analyzeFrame before reaching the fold call, so the arrival window only
        // ever saw accepted frames. A class-level test cannot see that; this one
        // reads the call sites so the wiring cannot be silently reverted.
        val src = java.io.File("src/main/java/com/ocubea/camera/CameraManager.kt")
            .takeIf { it.exists() }
            ?: java.io.File("../app/src/main/java/com/ocubea/camera/CameraManager.kt")
        assertTrue("CameraManager.kt not found from ${src.absolutePath}", src.exists())
        val text = src.readText()

        val arriveAt = text.indexOf("arrived = true")
        val deliverAt = text.indexOf("arrived = false")
        assertTrue(
            "CameraManager must fold an arrival somewhere (arrived = true)",
            arriveAt >= 0,
        )
        assertTrue(
            "CameraManager must fold a delivery somewhere (arrived = false)",
            deliverAt >= 0,
        )
        assertTrue(
            "the arrival fold must sit BEFORE the delivery fold: a frame the " +
                "limiter refuses returns from analyzeFrame, so folding arrival " +
                "after the limiter would drop exactly the frames arrival exists " +
                "to count (arrival at $arriveAt, delivery at $deliverAt)",
            arriveAt < deliverAt,
        )
        assertTrue(
            "the delivery fold must sit after the limiter's when block, not " +
                "inside its refusal arm",
            text.indexOf("LIMITED_BY_FPS") < deliverAt,
        )
    }
}
