package com.ocubea.server

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 44-byte WAV header every audio client is handed on connect.
 *
 * This header is the first thing written to a `/audio.wav` response and it is
 * never revisited: the stream that follows is raw PCM, so a field that is
 * wrong here is not a glitch, it is a client that either refuses the stream
 * or decodes it at the wrong pitch and speed until the user closes the tab.
 *
 * `wavHeader` is `private`, and unlike `ByteRanges.parse` it is not reachable
 * from a public entry point on the JVM — `addClient` needs a live
 * `AudioRecord` and a granted RECORD_AUDIO permission. It is called here
 * reflectively rather than through a copy of the logic: a copy would be a test
 * that asserts itself, which is the failure mode this file exists to avoid
 * (see the note on `formatExtInf` in HlsSessionPlaylistTest).
 *
 * The one-word production change that would let this be a direct call:
 * `private fun wavHeader(dataSize: Long)` -> `internal fun wavHeader(dataSize: Long)`
 * in app/src/main/java/com/ocubea/server/AudioStreamManager.kt. The reflection
 * helper below can then be deleted in favour of `AudioStreamManager.wavHeader(...)`.
 */
class AudioStreamManagerWavHeaderTest {

    private val manager = AudioStreamManager(
        object : ContextWrapper(null) {
            override fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"))
        },
    )

    /** The production function, not a copy of it. */
    private fun wavHeader(dataSize: Long): ByteArray {
        val m = AudioStreamManager::class.java
            .getDeclaredMethod("wavHeader", java.lang.Long.TYPE)
        m.isAccessible = true
        return m.invoke(manager, dataSize) as ByteArray
    }

    /**
     * RIFF fields are LITTLE-endian. This is the first thing a reader gets
     * wrong, because every box format in this project — `tkhd`, `mvhd`,
     * `mdat` — is big-endian, so the instinct carried over from the fMP4 code
     * produces plausible-looking garbage: 44100 read as 1152122880, the
     * streaming marker read as 587202560. Both look like plausible values,
     * which is why the mistake survives a careful reading of the bytes.
     */
    private fun le32(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (b[off + 3 - i].toLong() and 0xFF)
        return v
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, off: Int, len: Int) =
        String(b, off, len, Charsets.US_ASCII)

    // The chunk layout of a canonical RIFF/WAVE header, read back from the
    // bytes rather than compared against a literal. Offsets are the spec's.
    private companion object {
        const val OFF_RIFF_SIZE = 4
        const val OFF_WAVE = 8
        const val OFF_FMT = 12
        const val OFF_FMT_SIZE = 16
        const val OFF_AUDIO_FORMAT = 20
        const val OFF_CHANNELS = 22
        const val OFF_SAMPLE_RATE = 24
        const val OFF_BYTE_RATE = 28
        const val OFF_BLOCK_ALIGN = 32
        const val OFF_BITS = 34
        const val OFF_DATA = 36
        const val OFF_DATA_SIZE = 40
        const val HEADER_BYTES = 44
    }

    // ── chunk structure ────────────────────────────────────────

    /**
     * The chunk walk. A WAV parser does not care that the header is 44 bytes;
     * it cares that each chunk id, size and body is where it says it is. One
     * field written at the wrong width shifts everything after it, and the
     * symptom is a player that reports "unsupported format" with no detail —
     * so the structure is asserted end to end.
     */
    @Test
    fun `the chunks are laid out where the format puts them`() {
        val h = wavHeader(0xFFFFFFFFL)
        assertEquals("a WAV header is exactly 44 bytes, got ${h.size}", HEADER_BYTES, h.size)
        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals("WAVE", ascii(h, OFF_WAVE, 4))
        assertEquals("fmt ", ascii(h, OFF_FMT, 4))
        assertEquals("data", ascii(h, OFF_DATA, 4))
    }

    /**
     * `fmt ` must declare PCM, 16 bits, and the sample rate the manager
     * actually captures at. The rate is read from the class rather than typed
     * in here: a test that hardcodes 44100 would still pass after the capture
     * rate changed, and the stream would then be labelled with a rate it is
     * not — which plays back an octave out.
     */
    @Test
    fun `the format chunk describes the stream the manager actually captures`() {
        val h = wavHeader(0xFFFFFFFFL)
        assertEquals(
            "format 1 is uncompressed PCM; anything else and a browser will " +
                "not play the stream at all",
            1, le16(h, OFF_AUDIO_FORMAT),
        )
        assertEquals(
            "the header must advertise the rate the manager captures at, " +
                "imported from the class so a change to SAMPLE_RATE cannot " +
                "leave the header stale",
            AudioStreamManager.SAMPLE_RATE.toLong(), le32(h, OFF_SAMPLE_RATE),
        )
        assertEquals(
            "one channel: the manager captures mono and a client that is told " +
                "stereo will play the samples at half speed",
            1, le16(h, OFF_CHANNELS),
        )
        assertEquals(
            "16-bit samples, matching ENCODING_PCM_16BIT",
            16, le16(h, OFF_BITS),
        )
    }

    /**
     * The two derived fields a WAV player uses to size its buffers, and the
     * two it uses to find the samples. They are redundant with the rest of the
     * header, which is exactly why they are dangerous: a header can be
     * self-consistent about the rate and still carry a byte rate that implies
     * a different sample duration, and the result is playback at the wrong
     * speed with no error.
     *
     * The relationships are asserted, not the numbers, so the test keeps
     * working if the sample rate is ever changed.
     */
    @Test
    fun `byte rate and block align agree with the rate and channel count`() {
        val h = wavHeader(0xFFFFFFFFL)
        val rate = le32(h, OFF_SAMPLE_RATE)
        val channels = le16(h, OFF_CHANNELS)
        val bits = le16(h, OFF_BITS)
        val blockAlign = le16(h, OFF_BLOCK_ALIGN)
        assertEquals(
            "block align is channels x bytes-per-sample; a mismatch makes the " +
                "player frame the samples on the wrong boundary",
            channels * (bits / 8), blockAlign,
        )
        assertEquals(
            "byte rate is rate x block align; a mismatch plays at the wrong " +
                "speed even though the sample rate field is right",
            rate * blockAlign, le32(h, OFF_BYTE_RATE),
        )
        assertEquals(
            "the fmt chunk is PCM, whose body after the format tag is a fixed " +
                "18 bytes; a different length makes every later field misaligned",
            16L, le32(h, OFF_FMT_SIZE),
        )
    }

    // ── the live-stream length marker ───────────────────────────

    /**
     * The live case, which is the only one the app actually uses: a stream
     * that never ends cannot state its length.
     *
     * Both size fields must carry the 0xFFFFFFFF marker. Getting this wrong is
     * the classic "plays the first 5 seconds then stops" bug: a player reads
     * the data size, waits for that many bytes, gets a short read and ends the
     * stream.
     *
     * KNOWN FAILING — the defect is live in `wavHeader()` today, and only for
     * the RIFF field. The `data` field is written from `dataSize.toInt()` and
     * is correct at 0xFFFFFFFF, but the RIFF field is written from
     * `total = dataSize + 36`, i.e. 0x100000023, and `.toInt()` on that is
     * **35**. The header therefore claims the whole file is 35 bytes — shorter
     * than the 44-byte header it is inside. `w32()` only masks 32 bits, so
     * nothing looks wrong in the code.
     *
     * Fix in app/src/main/java/com/ocubea/server/AudioStreamManager.kt: the
     * marker has to be propagated instead of incremented. Either
     *   `val total = if (dataSize == 0xFFFFFFFFL) 0xFFFFFFFFL else dataSize + 36`
     * or write the marker into the RIFF slot directly. Both are in the private
     * `wavHeader`; the sized (non-live) path is correct and must stay so,
     * which is what the test below covers.
     */
    @Test
    fun `a live stream declares an unknown length in both size fields`() {
        val h = wavHeader(0xFFFFFFFFL)
        assertEquals(
            "the data chunk size must be the streaming marker, or the player " +
                "stops reading after N bytes and the live stream appears to end",
            0xFFFFFFFFL, le32(h, OFF_DATA_SIZE),
        )
        assertEquals(
            "the RIFF size must be the same marker, not 0 and not a real " +
                "length; a reader that trusts RIFF over data truncates the " +
                "stream or refuses to open it",
            0xFFFFFFFFL, le32(h, OFF_RIFF_SIZE),
        )
    }

    /**
     * The two markers are computed from one value, so a change to the marker
     * cannot leave one of them stale. This is the check that catches a
     * `w32(0)` written into the RIFF slot while the data slot stayed correct.
     *
     * KNOWN FAILING for the same reason: the two fields disagree by 35.
     */
    @Test
    fun `both size fields are the same value rather than one being left at zero`() {
        val h = wavHeader(0xFFFFFFFFL)
        assertEquals(
            "RIFF and data sizes must not disagree, or a reader that uses one " +
                "of them gets a different length from the other",
            le32(h, OFF_DATA_SIZE), le32(h, OFF_RIFF_SIZE),
        )
        assertTrue(
            "a zero size is what a forgotten field looks like; the streaming " +
                "marker is never zero",
            le32(h, OFF_RIFF_SIZE) != 0L,
        )
    }

    /**
     * The sized case, which is the path that is correct today, pinned so the
     * fix for the streaming marker cannot regress it.
     *
     * A RIFF size is "everything after the first 8 bytes", so for a real
     * payload of [dataSize] bytes it is [dataSize] + 36. Asserted as a
     * relationship between the two size fields the header itself carries,
     * which is what a real parser uses, rather than as a computed constant.
     */
    @Test
    fun `a sized stream states a length the header can actually contain`() {
        val dataSize = 4_000L
        val h = wavHeader(dataSize)
        assertEquals(
            "the data chunk size must be the real payload length",
            dataSize, le32(h, OFF_DATA_SIZE),
        )
        assertEquals(
            "the RIFF size covers everything after its own 8 bytes, so it " +
                "must be the data size plus the remaining 36 header bytes",
            dataSize + 36, le32(h, OFF_RIFF_SIZE),
        )
        assertTrue(
            "a sized header must not claim more than a 32-bit field can hold",
            le32(h, OFF_RIFF_SIZE) < 0xFFFFFFFFL,
        )
    }

    /**
     * Every length field has to fit the 4 bytes it is written into. A RIFF
     * size is `dataSize + 36`, so the largest payload this header can
     * describe is `0xFFFFFFFF - 36`; above that the addition wraps and the
     * header describes a file that does not exist. The live marker sits
     * exactly there, which is the case the two tests above are about.
     *
     * Stated as a property of the format rather than as a magic number, so the
     * test says what the constraint is and not one value of it.
     */
    @Test
    fun `a sized header states a length the 32 bit fields can hold`() {
        val maxRepresentable = 0xFFFFFFFFL - 36
        for (dataSize in listOf(0L, 1L, 44L, 4_000L, maxRepresentable / 2, maxRepresentable)) {
            val h = wavHeader(dataSize)
            assertEquals(
                "a data size of $dataSize must be stated back exactly",
                dataSize, le32(h, OFF_DATA_SIZE),
            )
            assertEquals(
                "the RIFF size for a payload of $dataSize must be the payload " +
                    "plus the 36 header bytes that follow the RIFF field",
                dataSize + 36, le32(h, OFF_RIFF_SIZE),
            )
        }
        assertTrue(
            "a payload of $maxRepresentable is the largest this header can " +
                "describe, and it must still be stated exactly",
            maxRepresentable + 36 == 0xFFFFFFFFL,
        )
    }

    /**
     * Everything else in the header is fixed, so the marker is the only thing
     * that varies with the argument. A header that changed size, or shifted
     * its chunks, between the live call and a sized one would mean the size
     * argument is being written somewhere it should not be.
     */
    @Test
    fun `only the size fields depend on the length argument`() {
        val live = wavHeader(0xFFFFFFFFL)
        val sized = wavHeader(4_000L)
        assertEquals("the header length must not depend on the payload size", live.size, sized.size)
        for (i in 0 until live.size) {
            val isSizeField = (i in OFF_RIFF_SIZE until OFF_RIFF_SIZE + 4) ||
                (i in OFF_DATA_SIZE until OFF_DATA_SIZE + 4)
            if (isSizeField) continue
            assertEquals(
                "byte $i changed when only the data size changed; the chunk " +
                    "layout must be identical for every length",
                live[i], sized[i],
            )
        }
    }
}
