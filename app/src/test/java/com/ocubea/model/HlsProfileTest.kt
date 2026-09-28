package com.ocubea.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HlsProfile encodes a trade, so the tests check the shape of the trade rather
 * than just that the fields hold their defaults.
 */
class HlsProfileTest {

    /**
     * The invariant that matters: a segment can only start on an IDR, so a GOP
     * longer than the segment means most segments wait for a keyframe they
     * never get, the playlist advertises durations that do not arrive, and the
     * player drains. This is the one property that, if broken, produces a
     * stream that looks configured and plays nothing.
     */
    @Test
    fun `gop never exceeds the segment it has to fit in`() {
        val all = listOf(
            HlsProfile.DEFAULT,
            HlsProfile.LOW_LATENCY,
            HlsProfile.HIGH_QUALITY,
            HlsProfile.sanitized(HlsProfile.DEFAULT, 500, 10),
            HlsProfile.sanitized(HlsProfile.LOW_LATENCY, 3000, 0)
        )
        for (p in all) {
            val gopMs = if (p.keyFrameIntervalSec == 0) 0 else p.keyFrameIntervalSec * 1000
            assertTrue(
                "segment=${p.segmentMs} gop=${p.keyFrameIntervalSec} — segment krotszy niz GOP",
                gopMs <= p.segmentMs
            )
        }
    }

    /**
     * The point of the high-quality profile is a longer GOP than low latency
     * has. If sanitized() ever collapses them to the same value, the profile
     * exists but does nothing, and the user pays latency for no bitrate.
     */
    @Test
    fun `high quality has a sparser gop than low latency`() {
        assertTrue(
            "high quality GOP ${HlsProfile.HIGH_QUALITY.keyFrameIntervalSec}s " +
                "powinien byc dluzszy niz low latency " +
                "${HlsProfile.LOW_LATENCY.keyFrameIntervalSec}s",
            HlsProfile.HIGH_QUALITY.keyFrameIntervalSec >
                HlsProfile.LOW_LATENCY.keyFrameIntervalSec
        )
        assertTrue(
            "high quality powinno miec dluzsze segmenty",
            HlsProfile.HIGH_QUALITY.segmentMs > HlsProfile.LOW_LATENCY.segmentMs
        )
    }

    /**
     * Low latency has to mean lower latency, not just different numbers. hls.js
     * waits liveSyncDurationCount segments before playing, so a profile that
     * keeps the default count while shortening segments does not get the
     * player to the live edge any sooner.
     */
    @Test
    fun `low latency is actually more aggressive than default`() {
        assertTrue(
            HlsProfile.LOW_LATENCY.segmentMs < HlsProfile.DEFAULT.segmentMs
        )
        assertTrue(
            HlsProfile.LOW_LATENCY.liveSyncDurationCount <
                HlsProfile.DEFAULT.liveSyncDurationCount
        )
        assertTrue(
            HlsProfile.LOW_LATENCY.maxBufferLength < HlsProfile.DEFAULT.maxBufferLength
        )
    }

    /** Nonsensical input from the API is clamped, not accepted. */
    @Test
    fun `sanitized clamps a segment below one frame`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 1, keyFrameIntervalSec = 0)
        assertTrue("1ms to nie jest segment, tylko blad", p.segmentMs >= 100)
    }

    @Test
    fun `sanitized clamps an absurd segment`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 999_999, keyFrameIntervalSec = 5)
        assertTrue("6s to maksimum, wiecej to juz bufor", p.segmentMs <= 6000)
    }

    /** A GOP longer than the segment is the failure mode described above. */
    @Test
    fun `sanitized holds a gop that outruns the segment`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 400, keyFrameIntervalSec = 9)
        assertTrue(
            "GOP 9s w segmencie 400ms — segmenty nigdy nie dostana IDR",
            p.keyFrameIntervalSec * 1000 <= p.segmentMs
        )
    }

    /**
     * 0 is legal and means "keyframe on every frame", which is exactly what low
     * latency asks for. Clamping it away would silently turn a short-segment
     * stream into a slow one.
     */
    @Test
    fun `sanitized keeps a zero gop as zero`() {
        val p = HlsProfile.sanitized(HlsProfile.DEFAULT, segmentMs = 500, keyFrameIntervalSec = 0)
        assertEquals(0, p.keyFrameIntervalSec)
    }

    /** The player-facing numbers come from the base, not from a reset. */
    @Test
    fun `sanitized keeps the player settings of its base`() {
        val p = HlsProfile.sanitized(
            base = HlsProfile.LOW_LATENCY,
            segmentMs = 900,
            keyFrameIntervalSec = 0
        )
        assertEquals(
            "sanitized nie moze cofnac ustawien odtwarzacza",
            HlsProfile.LOW_LATENCY.liveSyncDurationCount, p.liveSyncDurationCount
        )
        assertEquals(HlsProfile.LOW_LATENCY.maxBufferLength, p.maxBufferLength)
    }

    /** The API takes one word, so of() must map exactly the two we accept. */
    @Test
    fun `of maps the toggle to a real profile`() {
        assertEquals(HlsProfile.LOW_LATENCY, HlsProfile.of(true))
        assertEquals(HlsProfile.DEFAULT, HlsProfile.of(false))
    }

    /**
     * A profile that survives a restart unchanged, so a client reading it back
     * does not see a value that differs from what was set.
     */
    @Test
    fun `a profile round trips through the api unchanged`() {
        for (p in listOf(HlsProfile.DEFAULT, HlsProfile.LOW_LATENCY, HlsProfile.HIGH_QUALITY)) {
            val again = HlsProfile.sanitized(
                base = p,
                segmentMs = p.segmentMs,
                keyFrameIntervalSec = p.keyFrameIntervalSec
            )
            assertEquals(p, again)
        }
    }
}
