package com.ocubea.stream

import com.ocubea.stream.Fmp4Writer.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The HLS playlist is a wire format, so it is checked by reading the produced
 * text back rather than by comparing it to a string typed out here.
 *
 * Every assertion in this file parses `HlsSession.playlist()` and checks a
 * relationship between two things the playlist itself says. Nothing re-derives
 * the production formatting, which is the trap this file exists to replace: the
 * earlier `HlsPlaylistFormatTest` mirrored `HlsSession.formatExtInf` into its
 * own three lines and asserted against that copy, so all four of its tests
 * passed with the production function deleted. A mirror guards nothing.
 *
 * `HlsSession` reaches `android.media.MediaFormat` through `H264Encoder`, but
 * it constructs on the JVM: the encoder only touches the platform inside
 * `start()`, which these tests never call. The segment ring is filled through
 * reflection because `encodeFrame` is the only public writer and it needs a
 * live `ImageProxy`.
 */
class HlsSessionPlaylistTest {

    private fun session(): HlsSession = HlsSession(width = 1920, height = 1080, fps = 30, bitrate = 8_000_000)

    /**
     * The ring behind `HlsSession.playlist()`. The field is
     * `private val ring = ArrayDeque<Segment>()`; adding to it directly is what
     * lets a test drive the playlist without a hardware encoder. `playlist()`
     * synchronises on the very same instance, so this is the production object,
     * not a stand-in.
     */
    @Suppress("UNCHECKED_CAST")
    private fun HlsSession.ring(): java.util.ArrayDeque<Segment> {
        val f = HlsSession::class.java.getDeclaredField("ring")
        f.isAccessible = true
        return f.get(this) as java.util.ArrayDeque<Segment>
    }

    /** The real production formatter, reached without widening its visibility. */
    private fun extInf(durationMs: Long): String {
        val m = HlsSession::class.java.getDeclaredMethod(
            "formatExtInf", java.lang.Long.TYPE,
        )
        m.isAccessible = true
        return m.invoke(session(), durationMs) as String
    }

    private fun HlsSession.withSegments(vararg seqAndMs: Pair<Int, Long>): HlsSession {
        val r = ring()
        for ((seq, ms) in seqAndMs) r.addLast(Segment(seq, byteArrayOf(seq.toByte()), ms))
        return this
    }

    // ── playlist parsing ───────────────────────────────────────
    //
    // Reads the produced text rather than asserting on a literal. An
    // #EXTINF line is only meaningful together with the segment URI that
    // follows it, so the parser keeps the pair: that is what lets a test say
    // "this URI's duration is the one that was muxed for it".

    private class Entry(val extinfMs: Long, val uri: String)

    /** #EXTINF value in whole milliseconds, or -1 if it is not a number. */
    private fun extinfToMs(value: String): Long {
        val parts = value.split('.')
        if (parts.size != 2) return -1
        val whole = parts[0].toLongOrNull() ?: return -1
        val frac = parts[1]
        if (frac.isEmpty() || frac.any { !it.isDigit() }) return -1
        return whole * 1000 + frac.padEnd(3, '0').take(3).toLong()
    }

    private fun entries(playlist: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var pendingMs: Long? = null
        for (line in playlist.split('\n')) {
            when {
                line.startsWith("#EXTINF:") -> {
                    val raw = line.removePrefix("#EXTINF:").substringBefore(',')
                    pendingMs = extinfToMs(raw)
                }
                line.isEmpty() -> Unit
                line.startsWith("#") -> Unit
                else -> {
                    out.add(Entry(pendingMs ?: -1L, line))
                    pendingMs = null
                }
            }
        }
        return out
    }

    private fun tag(playlist: String, name: String): String? =
        playlist.split('\n').firstOrNull { it.startsWith("$name") }

    private fun targetDuration(playlist: String): Long? =
        tag(playlist, "#EXT-X-TARGETDURATION:")?.removePrefix("#EXT-X-TARGETDURATION:")?.toLongOrNull()

    private fun mediaSequence(playlist: String): Long? =
        tag(playlist, "#EXT-X-MEDIA-SEQUENCE:")?.removePrefix("#EXT-X-MEDIA-SEQUENCE:")?.toLongOrNull()

    // ── the real regression: one #EXTINF per segment, each its own ──

    /**
     * Every segment in the ring must be advertised, exactly once, with the
     * duration the muxer actually gave it.
     *
     * The defect: the playlist used to write the profile's fixed `segmentMs`
     * into every #EXTINF. The muxer cuts a segment the moment an IDR arrives,
     * so with one IDR per frame the real segments are one frame long while the
     * playlist claimed 250 ms — and a player that computes its buffer and its
     * latency from the number is computing from a timeline the media does not
     * have. The symptom was a stream that loaded, showed a seek bar, and
     * desynchronised from its own clock.
     *
     * Asserted by matching each advertised URI to the segment it names, so a
     * fix that writes the right number to the wrong segment still fails.
     */
    @Test
    fun `each advertised segment carries the duration the muxer gave it`() {
        val session = session().withSegments(7 to 183L, 8 to 1250L, 9 to 1L)
        val entries = entries(session.playlist())

        assertEquals("every segment in the ring must be advertised", 3, entries.size)
        val expected = mapOf("seg7.m4s" to 183L, "seg8.m4s" to 1250L, "seg9.m4s" to 1L)
        for (e in entries) {
            assertTrue(
                "playlist advertises an unknown segment ${e.uri}",
                expected.containsKey(e.uri),
            )
            assertEquals(
                "#EXTINF for ${e.uri} must be the duration that segment was " +
                    "muxed with, not a fixed per-profile constant",
                expected[e.uri], e.extinfMs,
            )
        }
    }

    /**
     * An #EXTINF line and its segment URI are one unit. A #EXTINF emitted
     * without a URI — or a URI with no #EXTINF — makes the player pair a
     * duration with the wrong media, and the failure is a stall with no error,
     * so the pairing is asserted directly.
     */
    @Test
    fun `every segment URI is immediately preceded by its own EXTINF`() {
        val session = session().withSegments(1 to 240L, 2 to 260L, 3 to 219L)
        val playlist = session.playlist()
        val lines = playlist.split('\n')
        val uriIndices = lines.withIndex().filter { (_, l) -> l.startsWith("seg") && l.endsWith(".m4s") }
        assertEquals("all three URIs must be present", 3, uriIndices.size)
        for ((i, _) in uriIndices) {
            val previous = lines[i - 1]
            assertTrue(
                "line $i is ${lines[i]} but is not preceded by an #EXTINF line " +
                    "— a duration belonging to another segment desyncs the player",
                previous.startsWith("#EXTINF:"),
            )
        }
    }

    // ── TARGETDURATION ─────────────────────────────────────────

    /**
     * #EXT-X-TARGETDURATION must be the largest advertised duration, rounded
     * UP. A client is allowed to reject the whole playlist when it is not, and
     * the rounding is the part that breaks quietly: 1250 ms needs 2, and
     * truncating the division yields 1, so the playlist declares every segment
     * longer than the file promises and the player sizes its buffer against a
     * segment that does not exist.
     */
    @Test
    fun `target duration is the longest segment rounded up`() {
        val session = session().withSegments(1 to 183L, 2 to 1250L, 3 to 240L)
        val playlist = session.playlist()
        val target = targetDuration(playlist)
        assertNotNull("#EXT-X-TARGETDURATION must be present", target)
        val targetSec = target!!
        val longest = entries(playlist).maxOf { it.extinfMs }
        assertTrue(
            "TARGETDURATION $target s cannot hold the longest advertised " +
                "segment ${longest} ms — a client may reject the playlist outright",
            targetSec * 1000 >= longest,
        )
    }

    /** 1.001 s must not be advertised as a 1 s target duration. */
    @Test
    fun `target duration rounds up rather than truncating`() {
        val session = session().withSegments(1 to 1001L)
        val playlist = session.playlist()
        assertEquals(
            "1001 ms of media needs a 2 s target duration; truncating leaves " +
                "the playlist advertising more video per segment than it has",
            2L, targetDuration(playlist),
        )
    }

    /**
     * The empty ring still has to be a valid playlist. A session between
     * segments is the state every client polls through twice a second, so a
     * missing or zero TARGETDURATION here is not an edge case — it is the
     * first response a joining client gets.
     */
    @Test
    fun `a session with no segments yet still emits a complete playlist`() {
        val playlist = session().playlist()
        assertTrue("the playlist must start with #EXTM3U on its own line", playlist.startsWith("#EXTM3U\n"))
        assertEquals(
            "an empty playlist cannot advertise a 0 s target duration",
            1L, targetDuration(playlist),
        )
        assertEquals(
            "no segments means no segment lines, but the tags must remain",
            emptyList<Entry>(), entries(playlist),
        )
    }

    // ── MEDIA-SEQUENCE ─────────────────────────────────────────

    /**
     * The moving media sequence is what tells a player the stream is live and
     * stops it treating the playlist as a VOD file. It has to name the FIRST
     * segment still in the ring: pointing it at the newest makes a client
     * re-request a sequence it has already discarded, and the segment answers
     * 404 — the exact line in the console this ring was sized to fix
     * ("GET /hls/seg141.m4s 404" for a number the playlist itself advertised).
     */
    @Test
    fun `media sequence names the first segment still in the ring`() {
        val session = session().withSegments(41 to 250L, 42 to 250L, 43 to 250L)
        val playlist = session.playlist()
        assertEquals(
            "MEDIA-SEQUENCE must be the oldest segment the ring can still serve",
            41L, mediaSequence(playlist),
        )
        assertEquals(
            "the first advertised URI must be the one MEDIA-SEQUENCE names",
            "seg41.m4s", entries(playlist).first().uri,
        )
    }

    /** Segments are served oldest first and their numbers only ever go up. */
    @Test
    fun `segments are advertised in ascending sequence order`() {
        val session = session().withSegments(5 to 250L, 6 to 250L, 7 to 250L, 8 to 250L)
        val uris = entries(session.playlist()).map { it.uri }
        assertEquals(listOf("seg5.m4s", "seg6.m4s", "seg7.m4s", "seg8.m4s"), uris)
    }

    // ── EXT-X-MAP and the rest of the header ───────────────────

    /**
     * A media segment is fMP4 and cannot be decoded without the init segment
     * that #EXT-X-MAP points at. Dropping the tag, or losing the quotes that
     * make the URI a quoted-string, produces a playlist hls.js accepts and a
     * player that never paints a frame.
     *
     * The URI itself is deliberately not spelled out here: it is a route owned
     * by the server, and pinning the literal in this test would make a
     * legitimate route rename look like a muxer bug.
     */
    @Test
    fun `the map tag points at a quoted init segment URI`() {
        val playlist = session().withSegments(1 to 250L).playlist()
        val map = tag(playlist, "#EXT-X-MAP:")
        assertNotNull("#EXT-X-MAP is required for an fMP4 segment to be decodable", map)
        val uri = Regex("""URI="([^"]*)"""").find(map!!)?.groupValues?.get(1)
        assertNotNull("#EXT-X-MAP must use a quoted URI, got: $map", uri)
        assertTrue("#EXT-X-MAP URI must not be empty, got: $map", uri!!.isNotEmpty())
    }

    /**
     * `#EXT-X-VERSION` is the version of the playlist FORMAT the client should
     * assume, and it is the only tag that states it at all. Version 7 is what
     * marks the floating-point `#EXTINF` and the `EXT-X-MAP`/independent-
     * segments handling this stream depends on; a client handed a lower number
     * is entitled to parse the rest of the file under rules that predate the
     * tags below it.
     *
     * Asserted as a floor rather than an equality so a future bump to a higher
     * protocol version does not break the test, while a downgrade still does.
     */
    @Test
    fun `the playlist declares a format version new enough for its own tags`() {
        val playlist = session().withSegments(1 to 183L).playlist()
        val line = tag(playlist, "#EXT-X-VERSION:")
        assertNotNull("#EXT-X-VERSION must be present", line)
        val version = line!!.removePrefix("#EXT-X-VERSION:").trim().toIntOrNull()
        assertNotNull("#EXT-X-VERSION must be a number, got: $line", version)
        assertTrue(
            "declared version $version is too low for a floating-point " +
                "#EXTINF and an EXT-X-MAP, which this playlist uses",
            version!! >= 7,
        )
    }

    /**
     * The tags that keep a client treating this as a live EVENT stream rather
     * than a downloadable VOD. Together they are what stops hls.js from
     * buffering the whole playlist before it shows anything, which is the
     * latency this stream exists to avoid.
     */
    @Test
    fun `the playlist declares itself a live event`() {
        val playlist = session().withSegments(1 to 250L).playlist()
        assertTrue(
            "PLAYLIST-TYPE:EVENT is what stops the player buffering a live " +
                "stream as if it were a finished file: $playlist",
            playlist.contains("#EXT-X-PLAYLIST-TYPE:EVENT\n"),
        )
        assertTrue(
            "INDEPENDENT-SEGMENTS must be declared for CMAF, where every " +
                "segment opens on an IDR: $playlist",
            playlist.contains("#EXT-X-INDEPENDENT-SEGMENTS\n"),
        )
    }

    /**
     * Line endings, not just line content. A stray CR is what a
     * platform-dependent line separator introduces, and it makes the parser in
     * hls.js see "0.183,\r" as the duration — enough to lose sync on every
     * segment while the file still looks right in a text editor.
     */
    @Test
    fun `lines are terminated with a bare newline`() {
        val playlist = session().withSegments(1 to 183L, 2 to 187L).playlist()
        assertTrue("a CR in an HLS playlist shifts the duration the player reads", !playlist.contains('\r'))
        assertTrue("the playlist must end with a newline", playlist.endsWith("\n"))
    }

    // ── #EXTINF formatting, against the production function ────

    /**
     * A dot is a dot, in every locale the phone might be set to.
     *
     * This now calls `HlsSession.formatExtInf` itself. The previous version of
     * this check copied those three lines into the test, which is why it stayed
     * green after the production copy was deleted — and it is why a real
     * regression, swapping the manual formatting for `String.format(...)`
     * without a locale, would now go red here: under a comma locale
     * `String.format` writes "0,183", ffmpeg reports "Cannot get correct
     * #EXTINF value" for every segment and substitutes 1 ms.
     */
    @Test
    fun `uses a dot regardless of the default locale`() {
        val original = Locale.getDefault()
        try {
            for (loc in listOf(Locale("pl", "PL"), Locale.GERMANY, Locale("tr", "TR"))) {
                Locale.setDefault(loc)
                val line = extInf(183L)
                assertTrue(
                    "locale $loc produced '$line' with no decimal separator",
                    line.contains('.'),
                )
                assertEquals(
                    "a comma decimal separator is invalid HLS: locale $loc produced '$line'",
                    183L, extinfToMs(line),
                )
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * The digits matter, and this is the check the mirror version could not
     * make. 250 ms of media that a player reads as "0.025" is a tenth of the
     * real duration, so the fractional part is padded to exactly three places
     * and the value survives the trip through the text unchanged.
     */
    @Test
    fun `the duration survives the trip through the playlist text`() {
        for (ms in listOf(1L, 9L, 99L, 100L, 183L, 250L, 999L, 1000L, 1250L, 59_999L)) {
            assertEquals(
                "#EXTINF for $ms ms must read back as $ms ms — a truncated " +
                    "fractional part makes the player's clock drift from the media",
                ms, extinfToMs(extInf(ms)),
            )
        }
    }

    /**
     * A zero-length segment is invalid HLS, and a negative one is rejected
     * outright. A segment can only reach those values through a bug, so the
     * formatter hides it rather than emitting a duration that would desync
     * every following segment.
     */
    @Test
    fun `a non-positive duration never reaches the wire as a negative or zero value`() {
        assertEquals("0.000", extInf(-5L))
        assertEquals(
            "a negative duration must be clamped, not written with a minus sign",
            0L, extinfToMs(extInf(-5L)),
        )
        assertTrue(
            "a 1 ms segment must not be written as 0.000 s, which is a " +
                "zero-length segment and is invalid HLS",
            extinfToMs(extInf(1L)) > 0L,
        )
    }

    // ── the ring the playlist is a view of ─────────────────────

    /**
     * A client that read a playlist and then asks for a segment must get
     * those bytes, and a sequence the ring has dropped must answer null rather
     * than the wrong segment. The URI in the playlist is built from exactly
     * this number, so a mismatch here is a 404 for a URL the server printed
     * moments earlier.
     */
    @Test
    fun `a segment named by the playlist can be fetched, and an unknown one cannot`() {
        val session = session().withSegments(11 to 250L, 12 to 250L)
        val advertised = entries(session.playlist()).map { it.uri }
        for (uri in advertised) {
            val seq = uri.removePrefix("seg").removeSuffix(".m4s").toInt()
            assertNotNull(
                "the playlist advertises $uri but segment($seq) returns null, " +
                    "so the client that just read it gets a 404",
                session.segment(seq),
            )
        }
        assertEquals(
            "a sequence the ring never held must not resolve to anything",
            null, session.segment(999),
        )
    }
}
