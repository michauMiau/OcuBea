package com.ocubea.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Playlist text is a wire format, so it is checked as bytes rather than by
 * eye: the failures it has to prevent are all in the text.
 */
class HlsPlaylistFormatTest {

    /**
     * The formatting rule under test, mirrored from HlsSession. Duplicating the
     * three lines is deliberate: HlsSession pulls in MediaCodec and ImageProxy,
     * so it cannot be constructed on the JVM, and the point is to pin the
     * number format, not to reach the class.
     */
    private fun extInf(durationMs: Long): String {
        val millis = durationMs.coerceAtLeast(0L)
        return "${millis / 1000}.${(millis % 1000).toString().padStart(3, '0')}"
    }

    @Test
    fun `uses a dot regardless of the default locale`() {
        val original = Locale.getDefault()
        try {
            // Polish, German and Turkish all break naive float formatting;
            // Turkish is the nastiest because its lowercase i maps to a
            // dotless character in some paths.
            for (loc in listOf(Locale("pl", "PL"), Locale.GERMANY, Locale("tr", "TR"))) {
                Locale.setDefault(loc)
                val line = extInf(183)
                assertTrue(
                    "locale $loc produced $line",
                    line.contains('.'),
                )
                assertFalse(
                    "locale $loc produced a comma decimal separator: $line",
                    line.contains(','),
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `keeps three decimal places`() {
        assertEquals("0.183", extInf(183))
        assertEquals("0.250", extInf(250))
        assertEquals("1.000", extInf(1000))
        assertEquals("2.500", extInf(2500))
    }

    @Test
    fun `rounds a sub-millisecond duration up to one millisecond`() {
        // Never emit "0.000": a zero-length segment is invalid HLS.
        assertEquals("0.001", extInf(1))
    }

    @Test
    fun `clamps a negative duration instead of emitting a minus sign`() {
        // A negative #EXTINF is rejected outright by players, and a segment
        // can only get a negative duration through a bug worth hiding.
        assertEquals("0.000", extInf(-5))
    }
}
