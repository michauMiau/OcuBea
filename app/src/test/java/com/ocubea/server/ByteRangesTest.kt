package com.ocubea.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

/**
 * `Range:` parsing and the 206 it produces.
 *
 * Two things are checked, and they are checked as a pair because that is how
 * the code uses them: `parse()` decides the bounds, and `respond()` writes
 * those bounds into a `Content-Range` header and streams exactly that many
 * bytes. A bug in either half shows up as a player that seeks and then stalls,
 * with no error anywhere — the media element waits for bytes that never come.
 *
 * The bounds are not re-derived here. Each test states what the client asked
 * for and what the response then promised, and asserts that the response
 * body and the `Content-Range` agree with each other and with the file.
 */
class ByteRangesTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val total = 1000L

    private var fixtureSeq = 0

    /**
     * 1000 bytes, so every offset in the header has a byte behind it.
     *
     * `TemporaryFolder` refuses to create the same name twice, and several
     * tests need more than one file, so each fixture gets a fresh name.
     */
    private fun file(): File =
        tmp.newFile("clip-${fixtureSeq++}.mp4")
            .apply { writeBytes(ByteArray(total.toInt()) { (it % 251).toByte() }) }

    // ── the response, which is the only place these numbers are used ──

    /**
     * NanoHTTPD's `Response` constructor is protected and the class is not
     * final, so a subclass is the only way to get one outside the server —
     * which is exactly why `respond()` takes the factory as a parameter.
     */
    private class TestResponse(
        status: NanoHTTPD.Response.IStatus,
        mime: String,
        data: InputStream,
        size: Long,
    ) : NanoHTTPD.Response(status, mime, data, size)

    private fun respond(f: File, range: String?, mime: String = ByteRanges.MIME_MP4): NanoHTTPD.Response =
        ByteRanges.respond(f, range, { status, m, data, size -> TestResponse(status, m, data, size) }, mime)

    private fun body(res: NanoHTTPD.Response): ByteArray =
        res.data.use { it.readBytes() }

    /**
     * Reads a `Content-Range: bytes a-b/total` header back into its parts.
     * The header is the only place the served bounds are visible to the
     * client, so it is the thing under test rather than a re-derivation of it.
     */
    private fun contentRange(res: NanoHTTPD.Response): Triple<Long, Long, Long>? {
        val h = res.getHeader("Content-Range") ?: return null
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(h) ?: return null
        return Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
    }

    // ── the invariant that ties the two halves together ─────────

    /**
     * Whatever `parse()` decides, the response has to agree with itself: the
     * `Content-Range` bounds, the number of bytes in the body, and the bytes
     * themselves have to be the same range of the file.
     *
     * This is the shape of every range test below collapsed into one
     * assertion, so no individual test has to re-derive an expected offset.
     * An off-by-one in the suffix form, in the clamp, or in the body length
     * all break it.
     */
    private fun assertServesRequestedRange(
        header: String,
        why: String,
        file: File = file(),
    ) {
        val res = respond(file, header)
        val sent = body(res)
        val requested = ByteRanges.parse(header, file.length())
        assertNotNull("$why — parse() refused the range it was asked to serve", requested)

        assertEquals(
            "$why — a 206 must be returned, or the client treats the body as the whole file",
            Status.PARTIAL_CONTENT, res.status,
        )
        val cr = contentRange(res)
        assertNotNull("$why — a 206 without Content-Range is unseekable, got: $res", cr)
        val (start, end, declaredTotal) = cr!!
        assertEquals(
            "$why — the advertised start differs from what was parsed",
            requested!!.start, start,
        )
        assertEquals(
            "$why — the advertised end differs from what was parsed",
            requested.end, end,
        )
        assertEquals(
            "$why — Content-Range must state the real file length, not the " +
                "length of the piece being sent",
            file.length(), declaredTotal,
        )
        assertEquals(
            "$why — Content-Range says ${end - start + 1} bytes but the body " +
                "carries ${sent.size}",
            end - start + 1, sent.size.toLong(),
        )
        assertArrayEquals(
            "$why — the body is not the byte range the header advertises",
            file.readBytes().copyOfRange(start.toInt(), (end + 1).toInt()), sent,
        )
    }

    @Test
    fun `a bounded range is served exactly`() {
        assertServesRequestedRange("bytes=0-99", "an explicit range at the head of the file")
    }

    @Test
    fun `a range in the middle of the file is served exactly`() {
        assertServesRequestedRange("bytes=100-199", "a range that starts mid-file")
    }

    @Test
    fun `an open-ended range runs to the end of the file`() {
        // A media element sends exactly this when the user hits the end, and
        // a one-short response leaves the last frame never delivered.
        assertServesRequestedRange("bytes=900-", "an open-ended range")
    }

    @Test
    fun `a suffix range serves the final bytes`() {
        assertServesRequestedRange("bytes=-100", "a suffix range")
    }

    @Test
    fun `a single byte range is served`() {
        assertServesRequestedRange("bytes=500-500", "a one-byte range")
        assertServesRequestedRange("bytes=0-0", "the very first byte")
        assertServesRequestedRange("bytes=999-999", "the very last byte")
    }

    /**
     * A suffix longer than the file is not an error. RFC 9110 says the whole
     * representation is returned, and this is the case a player hits when it
     * re-requests a tail it already partly has after a reconnect. Answering
     * 416 here would kill playback.
     */
    @Test
    fun `a suffix longer than the file serves the whole file`() {
        assertServesRequestedRange("bytes=-5000", "a suffix longer than the file")
    }

    /**
     * An end past the last byte is clamped, not refused. A client that asks for
     * more than exists must get what does exist; the alternative is a 416 for a
     * perfectly reasonable seek near the end of a clip.
     */
    @Test
    fun `an end past the last byte is clamped to the file`() {
        assertServesRequestedRange("bytes=0-9999", "an end beyond the end of the file")
    }

    // ── malformed and unsatisfiable ────────────────────────────

    /**
     * A start at or past the end of the file is unsatisfiable and must be
     * refused, not silently served. Serving it would answer 206 with
     * `Content-Range: bytes 1000-999/1000` — a range whose end is before its
     * start, so `end - start + 1` is 0 and the body is empty while the client
     * is told 206. The player then waits for a frame that can never arrive.
     *
     * KNOWN FAILING — the defect is live in `ByteRanges.parse()` today. Line 49
     * reads `if (start < 0 || start >= total) null`, and that `null` is an
     * expression, not a return: it is evaluated, thrown away, and control falls
     * through to `Request(start, end, true)`. Kotlin does not warn on this.
     *
     * One-word fix in app/src/main/java/com/ocubea/server/ByteRanges.kt:
     * `if (start < 0 || start >= total) null` -> `if (start < 0 || start >= total) return null`
     */
    @Test
    fun `a start at or past the end of the file is refused`() {
        val f = file()
        for (h in listOf("bytes=1000-", "bytes=1000-1000", "bytes=5000-6000")) {
            val req = ByteRanges.parse(h, f.length())
            assertNull(
                "a range starting past the end of the file cannot be " +
                    "satisfied and must be refused so the caller can answer " +
                    "416; got $req",
                req,
            )
        }
    }

    /**
     * `bytes=-0` is a suffix of zero bytes, which satisfies nothing. It has to
     * be refused rather than clamped into a range, because the two ways of
     * "handling" it both lie to the client: returning the whole file turns a
     * seek request into a full download, and honouring a zero-length range
     * answers 206 with a `Content-Range` whose end is before its start.
     */
    @Test
    fun `a zero-length suffix range is refused`() {
        val f = file()
        assertNull(
            "a suffix of 0 bytes requests no media at all and must be " +
                "refused, not turned into the whole file",
            ByteRanges.parse("bytes=-0", f.length()),
        )
        // And the response it produces must not claim to be a partial one.
        val res = respond(f, "bytes=-0")
        assertEquals(
            "a zero-length range must not be answered with 206; the client " +
                "would wait for media that does not exist",
            Status.OK, res.status,
        )
    }

    /**
     * A reversed range collapses to the single byte it names. `bytes=99-0` is
     * nonsense, and RFC 9110 says an invalid last-byte-pos is discarded,
     * leaving a range from 99 to the end. What it must never do is produce a
     * NEGATIVE length: `end - start + 1` is what sizes the body, and a negative
     * there is a Content-Length no client can satisfy.
     */
    @Test
    fun `a reversed range collapses to the single byte it names`() {
        val req = ByteRanges.parse("bytes=99-0", total)
        assertNotNull("a reversed range must not be refused outright", req)
        assertTrue(
            "a reversed range produced start=${req!!.start} end=${req.end}, " +
                "which is an invalid Content-Range and a negative body length",
            req.start <= req.end,
        )
        val res = respond(file(), "bytes=99-0")
        val sent = body(res)
        assertEquals(
            "a reversed range must still yield a consistent Content-Range and body",
            (contentRange(res)!!.second - contentRange(res)!!.first + 1), sent.size.toLong(),
        )
    }

    @Test
    fun `malformed headers are refused rather than guessed at`() {
        for (h in listOf(
            "bytes=abc-def",
            "bytes=0-abc",
            "bytes=0x10-0x20",
            "bytes 0-99",
            "items=0-99",
            "bytes=0-99 extra",
            "nonsense",
        )) {
            assertNull(
                "a header that is not a byte range must be refused so the " +
                    "caller answers 200 with the whole file: [$h]",
                ByteRanges.parse(h, total),
            )
        }
    }

    @Test
    fun `an absent or blank header means the whole file`() {
        for (h in listOf(null, "", "   ")) {
            assertNull(
                "no Range header must parse to null, which serves 200 with " +
                    "the entire file: [$h]",
                ByteRanges.parse(h, total),
            )
        }
    }

    /** An empty file has no range to serve, whatever is asked for. */
    @Test
    fun `an empty file has no servable range`() {
        val empty = tmp.newFile("empty.mp4")
        assertEquals(0L, empty.length())
        assertNull(
            "a zero-length file cannot satisfy a byte range",
            ByteRanges.parse("bytes=0-0", empty.length()),
        )
    }

    /**
     * The unit is case-insensitive. HTTP header field names are, and a player
     * is entitled to send `Bytes=0-99`; answering 200 with the whole file to a
     * seek request looks like the seek bar not working.
     */
    @Test
    fun `the bytes unit is matched case-insensitively`() {
        for (h in listOf("bytes=0-99", "BYTES=0-99", "Bytes=0-99", "bYTeS=0-99")) {
            assertServesRequestedRange(h, "a differently-cased unit: [$h]")
        }
    }

    /**
     * Only the first range of a multi-range request is honoured, which is legal
     * and avoids multipart/byteranges. The part worth pinning is that the
     * *second* range is dropped rather than partially applied — serving
     * `0-49` while the client asked for `0-49,100-199` is a 206 whose
     * Content-Range does not describe what was asked for.
     */
    @Test
    fun `only the first range of a multi-range request is served`() {
        val res = respond(file(), "bytes=0-49,100-199")
        val sent = body(res)
        assertEquals("only the first range of the request is honoured", 50, sent.size)
        assertArrayEquals(
            "the served bytes must be the FIRST range the client asked for, " +
                "not a blend of both",
            ByteArray(50) { (it % 251).toByte() }, sent,
        )
    }

    // ── the 200 path ────────────────────────────────────────────

    /**
     * No usable range means the whole file, with `Accept-Ranges` so the client
     * knows seeking is possible at all. Without that header a browser shows no
     * seek bar for the clip, which is the whole reason `respond()` exists.
     */
    @Test
    fun `no range serves the whole file and still advertises that ranges work`() {
        val f = file()
        val res = respond(f, null)
        assertEquals("a request without Range is a plain 200", Status.OK, res.status)
        assertEquals(
            "Accept-Ranges: bytes is what makes a media element offer a seek bar",
            "bytes", res.getHeader("Accept-Ranges"),
        )
        assertNull(
            "a 200 must not carry a Content-Range; it is not a partial response",
            res.getHeader("Content-Range"),
        )
        assertArrayEquals("a 200 body is the whole file", f.readBytes(), body(res))
    }

    /**
     * An unsatisfiable range must fall back to the whole file, not become a
     * partial response. The failure this guards is a client that trusted the
     * status code and waited for bytes that were never sent.
     *
     * KNOWN FAILING for the same reason as the test above, and worse: with
     * `bytes=5000-6000` the discarded guard lets `start=5000` through, and
     * then `endText.toLong().coerceIn(5000, 999)` has a minimum above its
     * maximum, so Kotlin throws `IllegalArgumentException` out of `parse()`.
     * `serveClip` catches it and answers 500 "cannot read clip", so a client
     * that seeks past the end of a clip gets a server error instead of the
     * file. Fixed by the same one-word `return null` in ByteRanges.kt.
     */
    @Test
    fun `an unsatisfiable range falls back to the whole file rather than a short body`() {
        val f = file()
        val res = respond(f, "bytes=5000-6000")
        assertEquals(
            "a range that cannot be satisfied must not become a partial response",
            Status.OK, res.status,
        )
        assertArrayEquals(
            "falling back means the whole file, not an empty or short body",
            f.readBytes(), body(res),
        )
    }

    /** A clip must be served as video/mp4, which is what the WebUI plays. */
    @Test
    fun `the clip mime type is the one the player expects`() {
        assertEquals("video/mp4", ByteRanges.MIME_MP4)
        val res = respond(file(), "bytes=0-9")
        assertEquals(
            "a partial clip response must still declare the clip's mime type",
            ByteRanges.MIME_MP4, res.mimeType,
        )
    }

    /**
     * The body must stop at the advertised end even when the caller keeps
     * reading.
     *
     * A retention sweep can delete or truncate the clip mid-download, and the
     * bounded stream is what keeps the Content-Length honest instead of
     * short: a body that ends early with a length that promised more is a
     * truncated response the client waits on until it times out.
     */
    @Test
    fun `the bounded stream ends exactly where the range says and not a byte later`() {
        val f = file()
        for (header in listOf("bytes=10-19", "bytes=-10", "bytes=990-", "bytes=0-9999")) {
            val res = respond(f, header)
            val (start, end, _) = contentRange(res)!!
            val expected = (end - start + 1).toInt()
            // Read well past the end: an unbounded stream would hand back
            // everything from start to the end of the file.
            val read = res.data.use { it.readBytes() }
            assertEquals(
                "for [$header] the stream must yield exactly the advertised " +
                    "length and then stop",
                expected, read.size,
            )
        }
    }
}
