package com.ocubea.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The registry exists to be turned on by a button and off again, and it sits
 * on a path that must not allocate. These tests pin the bound and the
 * enable/disable contract, because both are the difference between a useful
 * tool and a new leak in an app that has to defend against those.
 */
class MetricsTest {

    @Before
    fun clean() {
        Metrics.setEnabled(false)
        Metrics.reset()
    }

    @Test
    fun disabledByDefault() {
        assertFalse(
            "a phone running a camera must not pay for diagnostics nobody reads",
            Metrics.enabled,
        )
    }

    @Test
    fun timerIsReusableAndKeepsSamplesAcrossLookups() {
        Metrics.setEnabled(true)
        val a = Metrics.timer("x")
        val b = Metrics.timer("x")
        assertNotNull(a)
        assertTrue("the same name must return the same timer", a === b)
        a!!.record(1_000L)
        assertEquals(1L, Metrics.snapshot()["x"]!!.totalSamples)
    }

    /**
     * The cap is the whole reason this class cannot leak. A caller that
     * interpolates an id into a span name would otherwise grow the map once
     * per frame for the rest of the session — the exact failure the registry
     * was added to catch.
     */
    @Test
    fun spanNamesAreBoundedAndOverflowIsCounted() {
        Metrics.setEnabled(true)
        repeat(Metrics.MAX_SPANS) { i ->
            assertNotNull("span $i must be accepted", Metrics.timer("span$i"))
        }
        assertNull("span beyond the cap must be refused, not created", Metrics.timer("overflow"))
        assertNull(Metrics.timer("overflow2"))
        assertEquals(2L, Metrics.rejectedSpans())
        assertEquals(Metrics.MAX_SPANS, Metrics.names().size)
    }

    @Test
    fun turningItOffDropsEverythingSoAStaleNumberIsNeverShown() {
        Metrics.setEnabled(true)
        Metrics.timer("x")!!.record(5_000L)
        assertTrue(Metrics.snapshot().isNotEmpty())
        Metrics.setEnabled(false)
        assertTrue(
            "a report rendered after switching off must be empty, not stale",
            Metrics.snapshot().isEmpty(),
        )
        assertEquals(0L, Metrics.rejectedSpans())
    }

    @Test
    fun resetClearsSamplesButKeepsTheTimers() {
        Metrics.setEnabled(true)
        Metrics.timer("x")!!.record(5_000L)
        Metrics.reset()
        assertEquals(0L, Metrics.snapshot()["x"]!!.totalSamples)
        assertTrue("reset must not force every call site to re-register", Metrics.names().contains("x"))
    }
}
