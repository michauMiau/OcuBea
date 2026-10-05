package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AAC container facts that the ADTS path gets wrong when nothing holds
 * them still, pinned against bytes that came off a real device.
 *
 * Everything here calls a production function directly rather than
 * re-implementing a rule: [AdtsFrame] deliberately has no Android imports so
 * that the container logic is assertable on the JVM, and a test that asserted
 * a copy of the rule instead would be asserting itself.
 */
class AacContainerTest {

    // ── the AudioSpecificConfig must never be framed as audio ─────────────

    /**
     * The exact two bytes the Sony F3311's AAC encoder emitted as its first
     * output buffer, read off `/audio.aac` on the phone.
     *
     * Decoded: audioObjectType 2 (AAC LC), samplingFrequencyIndex 4 (44 100 Hz),
     * channelConfiguration 1 (mono). The app wrapped this in a 7-byte ADTS
     * header and shipped it as frame 1 -- a 9-byte frame whose payload is not a
     * decodable AAC frame. ffmpeg named it:
     *   `Input buffer exhausted before END element found`
     *   `Error submitting packet to decoder: Invalid data found when processing input`
     * and both messages vanish when those nine bytes are removed. It was the
     * only frame under 16 bytes out of 282 in that capture.
     */
    private val realAsc = byteArrayOf(0x12, 0x08)

    @Test
    fun `the device's AudioSpecificConfig is recognised as config not audio`() {
        assertTrue(
            "0x12 0x08 is the AAC-LC/44.1kHz/mono AudioSpecificConfig this " +
                "device emits first; it must be dropped, not framed as audio",
            AdtsFrame.isAudioSpecificConfig(realAsc, channels = 1)
        )
        // And the bytes decode to what the comment claims, field by field, so
        // the fixture cannot quietly become something else.
        assertEquals("audioObjectType", 2, (realAsc[0].toInt() and 0xF8) shr 3)
        assertEquals(
            "samplingFrequencyIndex -> 44100 Hz",
            4, ((realAsc[0].toInt() and 0x07) shl 1) or ((realAsc[1].toInt() and 0x80) shr 7)
        )
        assertEquals("channelConfiguration", 1, (realAsc[1].toInt() and 0x78) shr 3)
    }

    @Test
    fun `a real AAC frame is never mistaken for config`() {
        // A plausible access unit from the same capture: far longer than an
        // ASC, and starting with whatever bits the bitstream happens to hold.
        val accessUnit = ByteArray(197) { 0x5A }
        accessUnit[0] = 0x12
        accessUnit[1] = 0x08
        assertFalse(
            "an access unit is audio even when its first two bytes look like " +
                "an ASC; dropping it would punch a hole in the audio",
            AdtsFrame.isAudioSpecificConfig(accessUnit, channels = 1)
        )
    }

    @Test
    fun `audio is never dropped just for being short`() {
        // The smallest real frames an AAC encoder emits on a quiet room. A
        // length-only check would eat these; the structural check must not.
        for (size in listOf(3, 4, 6, 8, 12, 16)) {
            val frame = ByteArray(size) { 0x11 }
            assertFalse(
                "a $size byte AAC frame is audio, not config: dropping it " +
                    "would silence part of the stream",
                AdtsFrame.isAudioSpecificConfig(frame, channels = 1)
            )
        }
    }

    @Test
    fun `a config for a different channel count is not this stream's config`() {
        // 0x12 0x10 is AAC-LC / 44.1 kHz / STEREO. `0x12 0x40` looks like the
        // same thing with one bit set and is not: byte 1 bits 3..6 are the
        // channel configuration, and 0x40 puts channelConfiguration at 8 --
        // not even a legal value in the ASC table. Asserting against it would
        // have "passed" for the wrong reason.
        val stereo = byteArrayOf(0x12, 0x10)
        assertEquals("fixture sanity: channel config is 2", 2, (stereo[1].toInt() and 0x78) shr 3)
        assertFalse(
            "a stereo ASC is not this mono stream's config",
            AdtsFrame.isAudioSpecificConfig(stereo, channels = 1)
        )
        assertTrue(
            "...and the same bytes are the config when stereo is what is set up",
            AdtsFrame.isAudioSpecificConfig(stereo, channels = 2)
        )
        // An escape-coded channel configuration (8..15) would need a further
        // field, so a two-byte payload carrying one is not an ASC at all.
        assertFalse(
            AdtsFrame.isAudioSpecificConfig(byteArrayOf(0x12, 0x40), channels = 8)
        )
    }

    @Test
    fun `an ASC that escaped into a six bit object type is not this endpoint's`() {
        // audioObjectType 31 is the escape value: the real type follows in more
        // bits, so the encoding is longer than two bytes and this is not the
        // two-byte LC config an Android AAC encoder writes.
        assertFalse(
            AdtsFrame.isAudioSpecificConfig(byteArrayOf(0xF9.toByte(), 0x08), channels = 1)
        )
    }

    @Test
    fun `every AAC-LC rate this app can produce is recognised as its own ASC`() {
        // The point of the rate-index bound: every legal index yields a
        // two-byte config that must be recognised, so changing the capture
        // rate cannot make the app frame its own config as audio.
        for (index in listOf(3, 4, 5, 8)) {
            val asc = byteArrayOf(
                ((2 shl 3) or (index shr 1)).toByte(),
                (((index and 1) shl 7) or (1 shl 3)).toByte()
            )
            assertEquals("object type for index $index", 2, (asc[0].toInt() and 0xF8) shr 3)
            assertEquals(
                "channel config for index $index", 1, (asc[1].toInt() and 0x78) shr 3
            )
            assertTrue(
                "the ${AdtsFrame.RATES[index]} Hz ASC (index $index) must be recognised",
                AdtsFrame.isAudioSpecificConfig(asc, channels = 1)
            )
        }
    }

    // ── the ADTS header must state the rate the encoder really ran at ──────

    @Test
    fun `the ADTS rate index agrees with the rate the encoder is fed`() {
        // The bug this pins: ENCODER_SAMPLE_RATE was 48000 while the capture
        // path feeds the encoder 44100, so every ADTS header declared a rate
        // 8.8% above the samples behind it. A player trusts the header and
        // plays each 1024-sample AAC-LC frame at the wrong speed.
        //
        // Proof from the wire, not from the code: the encoder's own ASC said
        // 44100 (index 4) while the header wrapped around it said 48000
        // (index 3). There is now one constant, and the header derives from it.
        assertEquals(
            "the ADTS header rate must be the rate the encoder is actually " +
                "configured with, not a separate constant that can drift",
            AudioStreamManager.SAMPLE_RATE, AudioEncoder.ENCODER_SAMPLE_RATE
        )
        assertEquals(
            "44100 must be expressible as an ADTS rate index, or the header " +
                "cannot describe this stream at all",
            4, AdtsFrame.indexFor(AudioEncoder.ENCODER_SAMPLE_RATE)
        )
    }

    @Test
    fun `an ADTS header built from the encoder rate names that same rate`() {
        val payload = ByteArray(180) { 0x5A }
        val h = AdtsFrame.frame(payload, AudioEncoder.ENCODER_SAMPLE_RATE, channels = 1)
        assertEquals(
            "the header's rate index must resolve back to the encoder's rate",
            AudioEncoder.ENCODER_SAMPLE_RATE,
            AdtsFrame.RATES[AdtsFrame.rateIndexOf(h)]
        )
        // The frame length must include the 7 header bytes, or a demuxer
        // walking the stream lands a byte-per-frame out for the rest of it.
        assertEquals(
            "frame length must cover header plus payload",
            7 + payload.size, AdtsFrame.frameLengthOf(h)
        )
    }

    /**
     * A rate with no entry in the ADTS table cannot be described in a header, so
     * the frame must go out unwrapped rather than under a wrong index.
     *
     * 22050 is NOT one of them -- it is index 7 -- and the first draft of this test
     * assumed it was, so it asserted that a perfectly legal header had not been
     * written. The list is built from [AdtsFrame.RATES] instead of typed out, so a
     * rate that is in the table can never be mistaken for one that is not.
     */
    @Test
    fun `no rate outside the ADTS table can produce a header`() {
        for (rate in listOf(-1, 0, 1, 44100 + 1, 22050 - 1, 192_000)) {
            assertFalse(
                "$rate must not be in the ADTS rate table or this test is wrong",
                rate in AdtsFrame.RATES
            )
            val framed = AdtsFrame.frame(ByteArray(64) { 0x5A }, rate, channels = 1)
            assertEquals(
                "a rate with no ADTS index must be sent unwrapped, not under a " +
                    "wrong index ($rate)",
                64, framed.size
            )
        }
        // And every rate that IS in the table does get a header.
        for (rate in AdtsFrame.RATES) {
            val framed = AdtsFrame.frame(ByteArray(64) { 0x5A }, rate, channels = 1)
            assertEquals("$rate must be framed", 71, framed.size)
        }
    }

    /**
     * The readers used to check the served headers, exercised against the exact
     * seven bytes the phone sent.
     *
     * `ff f1 4c 40 01 3f fc` was the head of the AAC capture: index 3 (48 kHz),
     * channel configuration 1, frame length 9. These helpers exist so the gate
     * and these tests read a header the way a demuxer does, and a wrong bit mask
     * in a *checker* is as silent as one in a writer -- the version of
     * [rateIndexOf] that masked `0x0C` returned 49 for this header, which is not
     * a table position, and would have "verified" a stream nobody could play.
     */
    @Test
    fun `the header field readers agree with the bytes the device sent`() {
        val wire = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x4C, 0x40, 0x01, 0x3F, 0xFC.toByte()
        )
        assertEquals("rate index on the wire was 3 (48 kHz)", 3, AdtsFrame.rateIndexOf(wire))
        assertEquals("rate index must resolve to a real rate", 48000, AdtsFrame.RATES[AdtsFrame.rateIndexOf(wire)])
        assertEquals("channel configuration was mono", 1, AdtsFrame.channelConfigOf(wire))
        assertEquals("frame length was 9 (7 header + 2 config)", 9, AdtsFrame.frameLengthOf(wire))

        // And every rate this app can produce round-trips through writer+reader.
        for (rate in listOf(44100, 48000)) {
            val h = AdtsFrame.frame(ByteArray(100) { 0x5A }, rate, channels = 1)
            assertEquals(
                "rate $rate must survive the writer/reader round trip",
                rate, AdtsFrame.RATES[AdtsFrame.rateIndexOf(h)]
            )
            assertEquals("channel config round trip", 1, AdtsFrame.channelConfigOf(h))
            assertEquals("frame length round trip", 107, AdtsFrame.frameLengthOf(h))
        }
    }
}