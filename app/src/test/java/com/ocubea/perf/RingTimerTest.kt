package com.ocubea.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * The percentile window is the whole point of [RingTimer], so the tests pin
 * the two things that can silently go wrong: a wrapped ring that reads the
 * wrong samples, and a snapshot that claims to describe a run it cannot.
 *
 * On why these use [RingTimer.record] and not `end(begin() - x)`: that helper
 * does NOT record x, because `end` measures its own `nanoTime` read and adds
 * that cost to the span. An assertion expecting 50_000_000 gets 50_000_097,
 * and loosening it to a margin produces a test that quietly stops checking
 * the value it names — it passed the mutation, then stopped meaning anything.
 * `record` takes a known duration, so every expected number here is exact and
 * these assertions are about the ring's behaviour, not the host's clock.
 */
class RingTimerTest {

    private val slow = 100_000_000L
    private val fast = 1_000L

    @Test
    fun emptyTimerReportsEmptyRatherThanZeroes() {
        val s = RingTimer(16).snapshot()
        assertEquals(0L, s.totalSamples)
        assertEquals(0, s.window)
        assertFalse("an untouched timer must not claim to have samples", s.truncated)
    }

    /**
     * Each percentile must name the sample at its own position in the sorted
     * window. Feeding 100 distinct durations and checking the exact index
     * catches off-by-one in the index arithmetic — the only part of this
     * class that can be wrong while the numbers still look plausible.
     */
    @Test
    fun percentilesNameTheirExactPositionInTheSortedWindow() {
        val t = RingTimer(1024)
        (1L..100L).forEach { t.record(it * 1_000_000L) }
        val s = t.snapshot()
        assertEquals(100L, s.totalSamples)
        assertEquals(100, s.window)
        assertFalse(s.truncated)
        // Sorted ascending over 1..100 ms; index = (n-1)*q.
        assertEquals(50_000_000L, s.p50Nanos)
        assertEquals(95_000_000L, s.p95Nanos)
        assertEquals(99_000_000L, s.p99Nanos)
        assertEquals(100_000_000L, s.maxNanos)
        assertEquals(50_500_000L, s.meanNanos)
    }

    /**
     * A wrapped ring must report the NEWEST samples. Reading forwards from
     * index 0 on a wrapped ring returns the oldest ones, which makes a
     * regression that already healed look like it is still happening — the
     * failure mode that makes people stop trusting the tool.
     */
    @Test
    fun wrappedRingReportsTheNewestWindowNotTheOldest() {
        val t = RingTimer(8)
        repeat(10) { t.record(slow) }
        repeat(8) { t.record(fast) }
        val s = t.snapshot()
        assertEquals(18L, s.totalSamples)
        assertEquals(8, s.window)
        assertTrue("a wrapped ring must say it truncated", s.truncated)
        assertNotEquals("the evicted slow samples leaked into the window", slow, s.p50Nanos)
        assertEquals("every surviving sample must be a fast one", fast, s.p50Nanos)
        assertEquals(fast, s.p99Nanos)
    }

    /**
     * `max` is cumulative over the whole run, while percentiles describe only
     * the window. That asymmetry is intentional, but it has to be visible
     * through behaviour, because a reader who assumes they match will misread
     * a recovered regression as a live one.
     */
    @Test
    fun maxIsCumulativeWhilePercentilesDescribeTheWindow() {
        val t = RingTimer(4)
        repeat(6) { t.record(slow) }
        repeat(4) { t.record(fast) }
        val s = t.snapshot()
        assertEquals(10L, s.totalSamples)
        assertEquals(4, s.window)
        assertEquals("the window is fast", fast, s.p50Nanos)
        assertEquals(
            "max must survive eviction: a one-off spike is the thing worth seeing",
            slow, s.maxNanos,
        )
    }

    /**
     * A backwards clock must be dropped, not averaged. Two reads that straddle
     * a wrap or a suspend produce a negative span; letting it through drags
     * the mean below zero and every percentile with it.
     */
    @Test
    fun negativeDeltaIsDroppedRatherThanPoisoningTheAverage() {
        val t = RingTimer(16)
        // end() with a start token from the FUTURE: a negative span.
        t.end(t.begin() + 5_000_000L)
        t.end(t.begin() + 5_000_000L)
        // and the same through the explicit entry point
        t.record(-1L)
        val s = t.snapshot()
        assertEquals("a negative span is not a measurement", 0L, s.totalSamples)
        assertEquals(0L, s.p50Nanos)
        assertEquals(0L, s.meanNanos)
    }

    @Test
    fun resetClearsEverythingIncludingTheRingContents() {
        val t = RingTimer(8)
        repeat(4) { t.record(slow) }
        t.reset()
        val s = t.snapshot()
        assertEquals(0L, s.totalSamples)
        assertEquals(0, s.window)
        assertEquals(0L, s.maxNanos)
        // The old samples must be gone from the slots, not just the counters:
        // otherwise the first snapshot after a reset reports stale percentiles.
        repeat(2) { t.record(fast) }
        val after = t.snapshot()
        assertEquals(2L, after.totalSamples)
        assertEquals("stale slow samples survived the reset", fast, after.p50Nanos)
    }

    @Test
    fun nonPowerOfTwoCapacityIsRejectedRatherThanSilentlyWrapping() {
        try {
            RingTimer(100)
            throw AssertionError("capacity 100 must be rejected: a non-maskable ring wraps wrong")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("power of two"))
        }
    }

    /** Concurrent writers must lose no samples, or the count is a lie. */
    @Test
    fun concurrentWritersLoseNothing() {
        val t = RingTimer(4096)
        val threads = 4
        val perThread = 2000
        val done = CountDownLatch(threads)
        for (i in 0 until threads) {
            Thread {
                try {
                    repeat(perThread) { t.record(fast) }
                } finally {
                    done.countDown()
                }
            }.start()
        }
        done.await()
        assertEquals((threads * perThread).toLong(), t.snapshot().totalSamples)
    }
}
