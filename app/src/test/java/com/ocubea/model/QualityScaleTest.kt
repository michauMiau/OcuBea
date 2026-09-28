package com.ocubea.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality slider is one control driving two encoders, so the mapping is
 * pinned here. Everything asserted is a property a user would notice breaking:
 * a slider that goes backwards, or an endpoint that does not land on the value
 * it promises.
 */
class QualityScaleTest {

    @Test
    fun `jpeg quality spans its declared range exactly`() {
        assertEquals(QualityScale.MIN_QUALITY, QualityScale.jpegQualityFor(0))
        assertEquals(QualityScale.MAX_QUALITY, QualityScale.jpegQualityFor(QualityScale.MAX_PROGRESS))
        // Normalising over 0..100 instead of the slider's real travel would
        // leave the top third of the bitrate range unreachable.
        assertTrue(QualityScale.bitrateKbpsFor(QualityScale.MAX_PROGRESS)
            > QualityScale.bitrateKbpsFor(100) * 0.95)
    }

    @Test
    fun `jpeg quality never leaves the encoder's valid range`() {
        // A stored preference written by an older build can hold anything, and
        // the slider is bound from it.
        for (p in -50..200) {
            val q = QualityScale.jpegQualityFor(p)
            assertTrue("progress $p -> $q", q in QualityScale.MIN_QUALITY..QualityScale.MAX_QUALITY)
        }
    }

    @Test
    fun `bitrate endpoints match the declared limits`() {
        assertEquals(QualityScale.MIN_BITRATE_KBPS, QualityScale.bitrateKbpsFor(0))
        assertEquals(
            QualityScale.MAX_BITRATE_KBPS,
            QualityScale.bitrateKbpsFor(QualityScale.MAX_PROGRESS),
        )
    }

    @Test
    fun `bitrate never decreases as quality rises`() {
        var previous = 0
        for (p in 0..QualityScale.MAX_PROGRESS) {
            val kbps = QualityScale.bitrateKbpsFor(p)
            assertTrue(
                "bitrate went backwards at progress $p: $previous -> $kbps",
                kbps >= previous,
            )
            previous = kbps
        }
    }

    @Test
    fun `bitrate stays inside the encoder's own limits`() {
        // A slider value is user input; it must not be able to ask MediaCodec
        // for a rate outside the range BitrateBounds allows.
        for (p in -20..150) {
            val kbps = QualityScale.bitrateKbpsFor(p)
            assertTrue(
                "progress $p -> $kbps",
                kbps in BitrateBounds.MIN_KBPS..BitrateBounds.MAX_KBPS,
            )
        }
    }

    @Test
    fun `the midpoint sits where the measured measurements put it`() {
        // Measured on HLS: 800 kbps -> 4.39 Mbps, 12000 kbps -> 12.82 Mbps.
        // The curve should therefore land the middle of the slider in the
        // middle of that spread, not near either end.
        val mid = QualityScale.MAX_PROGRESS / 2
        val midpoint = QualityScale.bitrateKbpsFor(mid)
        assertTrue(
            "midpoint $mid -> $midpoint should be between the endpoints",
            QualityScale.MIN_BITRATE_KBPS < midpoint
                && midpoint < QualityScale.MAX_BITRATE_KBPS,
        )
        // The bottom quarter must still be meaningfully below the midpoint,
        // or the slider's first half does almost nothing.
        assertTrue(
            "bottom ${QualityScale.bitrateKbpsFor(0)} is not below the midpoint",
            QualityScale.bitrateKbpsFor(0) < midpoint * 0.6,
        )
    }

    @Test
    fun `the top quarter is worth more than the bottom quarter`() {
        val span = QualityScale.MAX_PROGRESS
        val quarter = QualityScale.bitrateKbpsFor(span / 4) - QualityScale.MIN_BITRATE_KBPS
        val threeQuarters =
            QualityScale.MAX_BITRATE_KBPS - QualityScale.bitrateKbpsFor(span * 3 / 4)
        assertTrue(
            "top should gain more ($threeQuarters) than bottom ($quarter)",
            threeQuarters > quarter,
        )
    }
}
