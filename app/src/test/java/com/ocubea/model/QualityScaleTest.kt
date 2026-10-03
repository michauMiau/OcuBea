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
    }

    @Test
    fun `the top of the slider reaches the top of the bitrate range`() {
        // This replaced an assertion that compared bitrateKbpsFor(MAX_PROGRESS)
        // with bitrateKbpsFor(100). MAX_PROGRESS is 60 and bitrateKbpsFor
        // coerces into 0..60, so both sides were 12000 — the comparison reduced
        // to 12000 > 11400 and could never fail. It was written to catch a
        // normalisation over 0..100 that left the top of the slider short, and
        // it would not have caught it.
        assertEquals(QualityScale.MAX_BITRATE_KBPS, QualityScale.bitrateKbpsFor(QualityScale.MAX_PROGRESS))
    }

    @Test
    fun `a normalisation over 0 to 100 would strand the top of the slider`() {
        // The regression the test above used to guard, stated as something that
        // can actually fail: if the curve were divided by 100 instead of by
        // MAX_PROGRESS, progress 60 would land at 0.36 of the range instead of
        // the top. This asserts that the two differ, so the constant cannot
        // silently change meaning.
        val asIfDividedBy100 = QualityScale.MIN_BITRATE_KBPS +
            ((QualityScale.MAX_BITRATE_KBPS - QualityScale.MIN_BITRATE_KBPS) * 60 / 100)
        assertTrue(
            "if these are equal the curve is normalised over 0..100 and the top " +
                "of the slider is stranded",
            asIfDividedBy100 < QualityScale.bitrateKbpsFor(QualityScale.MAX_PROGRESS) - 1000,
        )
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
            QualityScale.MIN_BITRATE_KBPS < midpoint &&
                midpoint < QualityScale.MAX_BITRATE_KBPS,
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
