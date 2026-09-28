package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ByteRanges.parse() is pure arithmetic and had two defects that a build and
 * lint cannot see:
 *
 * 1. The unsatisfiable check was written as `if (cond) null` - an expression
 *    whose value is thrown away - instead of `return null`. A range that starts
 *    past the end of the file therefore fell through into
 *    `endText.toLong().coerceIn(start, total - 1)` with start > total - 1,
 *    which throws IllegalArgumentException out of parse(). serveClip() catches
 *    it, so the symptom was an HTTP 500 on a seek past the end of a clip.
 * 2. The docstring promised a 416 for an unsatisfiable range. No 416 existed
 *    anywhere; the word appeared only in the comment.
 */
class ByteRangesUnsatisfiableTest {

    @Test
    fun `a range starting past the end of the file is rejected, not thrown on`() {
        val r = ByteRanges.parse("bytes=5000-6000", total = 1000)
        assertNull("a start past the end is unsatisfiable, not a crash: $r", r)
    }

    @Test
    fun `a range starting exactly at the end of the file is rejected`() {
        val r = ByteRanges.parse("bytes=1000-1500", total = 1000)
        assertNull("start == total is one past the last byte: $r", r)
    }

    @Test
    fun `a negative start is rejected`() {
        val r = ByteRanges.parse("bytes=-5-10", total = 1000)
        assertNull("a negative start is malformed, not a crash: $r", r)
    }

    @Test
    fun `the last valid range still parses`() {
        val r = ByteRanges.parse("bytes=999-", total = 1000)
        assertTrue("the final byte is requestable, got $r", r != null)
        assertEquals(999L, r!!.start)
        assertEquals(999L, r.end)
    }

    @Test
    fun `a reversed range is clamped rather than thrown on`() {
        val r = ByteRanges.parse("bytes=800-200", total = 1000)
        assertTrue("a reversed range should be clamped, got $r", r != null)
        assertTrue("end must not precede start: $r", r!!.end >= r.start)
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        val r = ByteRanges.parse("bytes=500-", total = 1000)
        assertEquals(500L, r!!.start)
        assertEquals(999L, r.end)
    }

    @Test
    fun `a suffix range returns the tail`() {
        val r = ByteRanges.parse("bytes=-100", total = 1000)
        assertEquals(900L, r!!.start)
        assertEquals(999L, r.end)
    }

    @Test
    fun `malformed and non-numeric specs are rejected without throwing`() {
        for (spec in listOf("bytes=abc-def", "bytes=", "nonsense", "bytes=1-2-3", "bytes=--5")) {
            val r = try {
                ByteRanges.parse(spec, total = 1000)
            } catch (e: Exception) {
                throw AssertionError("parse(\"$spec\") threw ${e.javaClass.simpleName}: ${e.message}")
            }
            assertNull("parse(\"$spec\") should be null, got $r", r)
        }
    }
}
