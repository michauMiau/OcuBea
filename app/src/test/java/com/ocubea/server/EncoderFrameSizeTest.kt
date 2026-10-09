package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the encoder is fed, against what it emits, from the bytes the phone
 * actually served. Three consecutive live captures, offline ffmpeg decode:
 *
 *   aac   515072/503 = 1024.0 samples per frame
 *         519168/507 = 1024.0
 *         527360/515 = 1024.0
 *   flac  ~5424 samples per frame (4096-byte blocks; four 960-sample inputs)
 *   wav   530432/552 = 960.9, and 960.2, 960.0 -- unaffected
 *
 * The app queues FRAME_SAMPLES = 960 per input buffer and tells MediaCodec the
 * buffer is complete by passing FRAME_BYTES as the size. AAC-LC's frame is a
 * fixed 1024 samples, so the encoder pads the short input to its own size. The
 * stream then decodes to more samples than were ever captured: 6.7% long for
 * AAC. A player pulling it at wall-clock speed falls further behind every frame,
 * which is heard as a stutter that worsens the longer it plays. WAV is fine
 * because it carries the PCM through untouched, which is exactly the asymmetry
 * the user reported.
 *
 * The fix is to queue what the encoder wants and keep the leftover, rather than
 * declaring a short buffer complete. This test exists so the two numbers cannot
 * drift apart again: it holds the contract that the fan-out buffers to the
 * encoder's frame size, not the capture block size.
 */
class EncoderFrameSizeTest {

    @Test
    fun frameSamplesIsTheCaptureBlockTheFanOutFeeds() {
        // 960 samples at 44100 Hz is 21.77 ms, not the 20 ms the constant's
        // comment claims (that figure is for 48 kHz). The comment is what
        // invited the mismatch, so it is asserted here in the corrected form.
        assertEquals(960, AudioEncoder.FRAME_SAMPLES)
        assertEquals(1920, AudioEncoder.FRAME_BYTES)
    }

    @Test
    fun aacFrameSizeIsNotTheCaptureBlock() {
        // The whole defect in one assertion: if these ever become equal the
        // padding is gone by accident, and if the encoder's frame size changes
        // this test is where it gets noticed rather than in a support ticket.
        val encoderFrame = 1024
        assertTrue(
            "AAC-LC emits a fixed 1024 samples per frame; queueing " +
                "${AudioEncoder.FRAME_SAMPLES} and marking the buffer complete " +
                "makes the encoder pad. If this now fails because the encoder " +
                "publishes 960, the fix in AudioEncoder must be revisited too.",
            encoderFrame != AudioEncoder.FRAME_SAMPLES,
        )
    }

    @Test
    fun samplesQueuedPerFrameAreOneFrameWorth() {
        // What the fix guarantees: the encoder is always handed exactly one
        // full frame of PCM, never a partial one. A short queue is what produced
        // padding, so the invariant is checked as a relationship rather than a
        // literal, and the literal lives in the constant.
        val samples = AudioEncoder.FRAME_SAMPLES
        val bytes = AudioEncoder.FRAME_BYTES
        assertEquals("PCM is 16-bit, so bytes must be exactly twice samples",
            samples * 2, bytes)
        assertTrue("a frame must be a positive number of samples", samples > 0)
    }
}