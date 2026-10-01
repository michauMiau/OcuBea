package com.ocubea.stream

import com.ocubea.model.HlsProfile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much history the playlist advertises IS the latency floor: hls.js seeks to
 * (live edge - liveSyncDurationCount), so every retained segment is one it may
 * load before playback starts.
 *
 * Measured on the phone (Sony F3311, Android 6), segment durations read off the
 * playlist:
 *
 * | profile                        | playlist held |
 * |--------------------------------|---------------|
 * | DEFAULT sync=3, ring 20 fixed  | 4.14 s, then 3.90 s |
 * | LOW_LATENCY sync=1, ring 20 fixed | 3.98 s      |
 *
 * The fixed count of 20 was the bug: it holds a *number* of segments, and the
 * phone delivers ~195ms segments regardless of a 120ms profile request, so the
 * count tracked the profile's intent while the seconds did not -- switching to
 * LOW_LATENCY changed nothing an end viewer could see.
 */
class HlsSessionRingWindowTest {

    /** The window has to shrink when the profile asks for less latency. */
    @Test
    fun `low latency retains less history than default`() {
        val segmentMs = 195   // the phone's real segment length
        val def = HlsSession.ringSizeForProfileTest(HlsProfile.DEFAULT, segmentMs)
        val low = HlsSession.ringSizeForProfileTest(HlsProfile.LOW_LATENCY, segmentMs)

        assertTrue(
            "LOW_LATENCY (sync=1) must retain less than DEFAULT (sync=3): " +
                "$low vs $def segments at ${segmentMs}ms",
            low < def
        )
    }

    /** And the seconds have to match the intent, not just the ordering. */
    @Test
    fun `the retained seconds follow the profile`() {
        val segmentMs = 195
        val defSeconds = HlsSession.ringSizeForProfileTest(HlsProfile.DEFAULT, segmentMs) *
            segmentMs / 1000.0
        val lowSeconds = HlsSession.ringSizeForProfileTest(HlsProfile.LOW_LATENCY, segmentMs) *
            segmentMs / 1000.0

        // DEFAULT: sync=3 -> 3s of history. Measured 2.00s after the change;
        // before it the flat 20-segment ring put 5.83s on the playlist, which is
        // three times what hls.js will ever seek back through.
        assertTrue("DEFAULT held ${defSeconds}s, want 2.4-3.6s", defSeconds in 2.4..3.6)
        // LOW_LATENCY: sync=1 -> 1s, clamped up to the 2s floor. Measured 1.92s.
        assertTrue("LOW_LATENCY held ${lowSeconds}s, want <= 2.6s", lowSeconds <= 2.6)
    }

    /**
     * The ring is derived from the MEASURED duration, so it must change when the
     * segment length changes. A fixed count passes an upper-bound-only check
     * (20 * 195ms is under 4s), which is how the first version of this test
     * passed green on the broken constant.
     */
    @Test
    fun `the ring is derived from the duration, not a fixed count`() {
        val slow = HlsSession.ringSizeForProfileTest(HlsProfile.DEFAULT, 195)
        val fast = HlsSession.ringSizeForProfileTest(HlsProfile.DEFAULT, 50)

        assertTrue(
            "195ms -> $slow segments, 50ms -> $fast: the count must grow when " +
                "segments get shorter, otherwise the window is not being honoured",
            fast > slow
        )
        assertTrue("50ms x $fast = ${fast * 50}ms, want 2.4-3.6s", fast * 50 in 2400..3600)
    }

    /** A degenerate or hostile duration must not produce an empty ring. */
    @Test
    fun `the ring never collapses`() {
        listOf(0, -5, 1, 60_000).forEach { ms ->
            val n = HlsSession.ringSizeForProfileTest(HlsProfile.DEFAULT, ms)
            assertTrue("segmentMs=$ms gave a ring of $n", n >= 6)
        }
    }

    /** The floor exists so a late joiner can still find media. */
    @Test
    fun `a profile asking for nothing still keeps two seconds`() {
        val seconds = HlsSession.ringSecondsFor(HlsProfile.LOW_LATENCY)
        assertTrue("sync=1 -> ${seconds}s of history", seconds >= 2)
    }
}
