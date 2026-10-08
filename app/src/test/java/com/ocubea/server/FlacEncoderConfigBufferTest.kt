package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FLAC encoder's own header arriving as a codec-config buffer.
 *
 * The defect this covers was live on an Android 16 / API 36 phone and produced
 * a file with two `fLaC` markers, at offsets 0 and 42. Measured on that
 * device, with `c2.android.flac.encoder` the selected component (logcat:
 * `CCodec: allocate(c2.android.flac.encoder)`), the encoder's output sequence
 * was:
 *
 * ```
 * flags=2 size=93  664c6143 00000022 ...   fLaC + STREAMINFO + VORBIS_COMMENT
 * flags=2 size=93  664c6143 00000022 ...   (a second one, same content)
 * flags=0 size=3160 fff8c908 00951200 ...  a bare FLAC frame
 * flags=0 size=3176 fff8c908 019212ff ...  a bare FLAC frame
 * ```
 *
 * flags=2 is `MediaCodec.BUFFER_FLAG_CODEC_CONFIG`. The codec does NOT inline
 * `fLaC` into the audio stream -- every audio buffer begins `0xFF 0xF8` -- but
 * it publishes libFLAC's metadata as CSD, and Android surfaces that CSD as an
 * ordinary output buffer. Code that drains output buffers without reading
 * `BufferInfo.flags` ships it as audio, and the endpoint's own hand-written
 * header then lands behind it.
 *
 * The bytes below are the real 93-byte payload from that capture, so this test
 * pins the actual wire format rather than a shape someone imagined.
 */
class FlacEncoderConfigBufferTest {

    /**
     * The encoder's 93-byte config payload, byte-for-byte from the capture:
     * `fLaC`, a STREAMINFO with the last-metadata-block flag CLEAR, then a
     * VORBIS_COMMENT reading "reference libFLAC git-4ff7e0f6 20230627".
     *
     * Generated from the capture rather than transcribed by hand -- two
     * hand-typed attempts were 85 and 89 bytes with the VORBIS_COMMENT header
     * misplaced, and both failed on the fixture rather than on any real
     * defect. A fixture that is nearly right is worse than none, because the
     * failures point at the code under test.
     */
    private val encoderConfig: ByteArray = byteArrayOf(
        0x66.toByte(), 0x4C.toByte(), 0x61.toByte(), 0x43.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x22.toByte(),
        0x10.toByte(), 0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0xC4.toByte(), 0x40.toByte(), 0xF0.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x84.toByte(), 0x00.toByte(), 0x00.toByte(), 0x2F.toByte(), 0x27.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x72.toByte(), 0x65.toByte(), 0x66.toByte(), 0x65.toByte(), 0x72.toByte(), 0x65.toByte(),
        0x6E.toByte(), 0x63.toByte(), 0x65.toByte(), 0x20.toByte(), 0x6C.toByte(), 0x69.toByte(), 0x62.toByte(), 0x46.toByte(),
        0x4C.toByte(), 0x41.toByte(), 0x43.toByte(), 0x20.toByte(), 0x67.toByte(), 0x69.toByte(), 0x74.toByte(), 0x2D.toByte(),
        0x34.toByte(), 0x66.toByte(), 0x66.toByte(), 0x37.toByte(), 0x65.toByte(), 0x30.toByte(), 0x66.toByte(), 0x36.toByte(),
        0x20.toByte(), 0x32.toByte(), 0x30.toByte(), 0x32.toByte(), 0x33.toByte(), 0x30.toByte(), 0x36.toByte(), 0x32.toByte(),
        0x37.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
    )

    /** A real audio buffer from the same capture: a bare FLAC frame. */
    private val audioFrame: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xF8.toByte(), 0xC9.toByte(), 0x08.toByte(),
        0x00, 0x95.toByte(), 0x12, 0x00, 0xB2.toByte(), 0x01, 0x14, 0xB4.toByte()
    )

    /** `MediaCodec.BUFFER_FLAG_CODEC_CONFIG`, spelled out so no android.jar is needed. */
    private val flagCodecConfig = 2

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    @Test
    fun theEncoderConfigBufferIsRecognisedByItsFlag() {
        // The exact case the device produced: flags=2, 93 bytes, fLaC first.
        assertEquals(93, encoderConfig.size)
        assertTrue(
            AudioEncoder.isCodecConfigPayload(flagCodecConfig, encoderConfig)
        )
    }

    /**
     * The flag alone is not the whole test. An encoder that published its
     * header without the flag -- which no AOSP release does, but which is not
     * something this app can rule out from a comment -- would otherwise put a
     * second `fLaC` in the stream exactly as it did before.
     */
    @Test
    fun aFlacSignatureIsConfigEvenWithoutTheFlag() {
        assertFalse("frame must not look like config", isConfig(audioFrame))
        assertTrue("fLaC must be config", isConfig(encoderConfig))
    }

    /** A bare FLAC frame is audio and must survive. */
    @Test
    fun aRealFrameIsNeverMistakenForConfig() {
        assertFalse(
            "flags=0 frame starting fff8 is audio",
            AudioEncoder.isCodecConfigPayload(0, audioFrame)
        )
        // And with the config flag it would be dropped, which is the platform's
        // call to make, not this app's: the flag is trusted because the
        // platform set it on a buffer it knows is configuration.
        assertTrue(AudioEncoder.isCodecConfigPayload(flagCodecConfig, audioFrame))
    }

    /**
     * `0xFF 0xF8` is the sync word a headerless FLAC stream is hunted for, and
     * it occurs inside payloads by chance. Nothing here may key on it, or
     * audio gets dropped mid-stream and the stream goes silent.
     */
    @Test
    fun theFrameSyncWordIsNotWhatMarksConfig() {
        val syncInside = audioFrame.copyOf()
        syncInside[8] = 0xFF.toByte()
        syncInside[9] = 0xF8.toByte()
        assertFalse(isConfig(syncInside))
    }

    @Test
    fun aShortBufferIsNotConfig() {
        // Three bytes cannot hold fLaC, and a one-byte payload would index
        // past the end if the length check were missing.
        assertFalse(isConfig(byteArrayOf(0x66)))
        assertFalse(isConfig(byteArrayOf(0x66, 0x4C, 0x61)))
        assertFalse(isConfig(ByteArray(0)))
    }

    /**
     * Why the config block cannot replace the endpoint's own header: the
     * STREAMINFO inside it has the last-metadata-block flag CLEAR, because
     * libFLAC emits a VORBIS_COMMENT after it. A demuxer told to expect more
     * metadata waits for the rest of the file and yields nothing.
     */
    @Test
    fun theEncoderStreamInfoDeclaresThatMoreMetadataFollows() {
        val flagBit = u8(encoderConfig, 4) shr 7
        assertEquals("last-metadata-block flag in the encoder's STREAMINFO", 0, flagBit)
        assertEquals("STREAMINFO length", 34, (u8(encoderConfig, 5) shl 16) or
            (u8(encoderConfig, 6) shl 8) or u8(encoderConfig, 7))
        // ... and the endpoint's own header sets it, which is the difference.
        assertEquals(
            "our header must set last-metadata-block",
            1,
            u8(FlacStreamHeader.header(44_100, 1), 4) shr 7
        )
    }

    /**
     * The two headers are byte-identical apart from that one flag bit, which is
     * why the duplicate was easy to miss by eye and only a byte-level gate
     * caught it.
     */
    @Test
    fun theOnlyDifferenceFromOurHeaderIsTheLastMetadataFlag() {
        val ours = FlacStreamHeader.header(44_100, 1)
        val theirs = encoderConfig.copyOfRange(0, 42)
        val differing = (0 until 42).filter { ours[it] != theirs[it] }
        assertEquals("byte offsets that differ", listOf(4), differing)
        assertEquals(0x80, u8(ours, 4))
        assertEquals(0x00, u8(theirs, 4))
    }

    /**
     * The end-to-end shape of the bug, as bytes: our header followed by the
     * encoder's config gives two signatures, and the reference decoder refuses
     * the result. This asserts the structure without a decoder; `flac -t` was
     * run on the real capture and reported
     * `FLAC__STREAM_DECODER_ERROR_STATUS_LOST_SYNC after processing 0 samples`.
     */
    @Test
    fun forwardingTheConfigAfterOurHeaderProducesTwoSignatures() {
        val ours = FlacStreamHeader.header(44_100, 1)
        val duplicated = ours + encoderConfig
        assertEquals(
            "fLaC markers when the config buffer is not dropped",
            2,
            countSignatures(duplicated)
        )
        assertEquals(
            "and one when it is dropped",
            1,
            countSignatures(ours + audioFrame)
        )
    }

    private fun isConfig(payload: ByteArray) =
        AudioEncoder.isCodecConfigPayload(0, payload)

    /**
     * The call site has to be guarded too, and this is the test that does it.
     *
     * Proven the hard way: with every test above in place, deleting the drop
     * branch from `encodeFrame` left all 8 green. They assert the RULE and this
     * asserts the WIRING, and a rule nobody calls is not a fix. A mutation
     * harness that only removes the flag check proves nothing about this.
     *
     * Reading the source is the honest option: `encodeFrame` needs a real
     * MediaCodec, which a JVM test cannot have, and this repo already accepts
     * that trade for exactly this reason (see H264EncoderLifecycleTest, which
     * guards a `MediaCodec.configure` path the same way).
     */
    @Test
    fun encodeFrameActuallyDropsTheConfigBuffer() {
        val text = readAudioEncoderSourceOrNull()
        assertTrue(
            "could not locate AudioEncoder.kt via -Docubea.srcRoot -- this guard " +
                "would silently protect nothing if it could not find its file",
            text != null
        )
        val source = text!!
        assertTrue(
            "the FLAC codec-config buffer must be tested inside encodeFrame; " +
                "without the call the 93 bytes are broadcast as audio and the " +
                "served stream carries two fLaC markers again",
            source.contains("""isCodecConfigPayload(info.flags, payload)""")
        )
        // The drop has to release the buffer as well as skip it: a MediaCodec
        // hands back a fixed pool of output buffers, and one taken and not
        // released is gone for good. The capture loop then drains nothing and
        // the stream goes silent after its first config buffer.
        //
        // The `${...}` placeholders in the log line are stripped before the
        // branch is cut at its closing brace: slicing on the first `}` stopped
        // inside the log string's own template, so the assertion below failed
        // on a fix that was present and correct.
        val branch = source.substringAfter("isCodecConfigPayload(info.flags, payload)")
            .replace(Regex("""\$\{[^}]*}"""), "")
            .substringBefore("}")
        assertTrue(
            "dropping the config buffer must release the output buffer",
            branch.contains("releaseOutputBuffer")
        )
    }

    /** Wired by the test task in build.gradle.kts. */
    private fun readAudioEncoderSourceOrNull(): String? {
        val root = System.getProperty("ocubea.srcRoot") ?: return null
        val f = java.io.File(
            root, "app/src/main/java/com/ocubea/server/AudioEncoder.kt"
        )
        if (!f.isFile) return null
        return f.readText()
    }

    private fun countSignatures(b: ByteArray): Int {
        var n = 0
        for (i in 0..b.size - 4) {
            if (u8(b, i) == 0x66 && u8(b, i + 1) == 0x4C &&
                u8(b, i + 2) == 0x61 && u8(b, i + 3) == 0x43
            ) {
                n++
            }
        }
        return n
    }
}
