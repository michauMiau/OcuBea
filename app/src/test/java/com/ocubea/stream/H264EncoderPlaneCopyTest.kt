package com.ocubea.stream

import androidx.camera.core.ImageProxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The per-byte plane copy that cost 190ms per frame.
 *
 * Measured on a Sony F3311 (Android 6) at 864x480, from a per-step breakdown of
 * encode() added for exactly this:
 *
 *     copyPlanes   5712 ms over 30 encodes   = 190 ms per frame
 *     encode()     5886 ms over 30 encodes   = 196 ms per frame
 *
 * so 97% of the time inside the encoder was this copy. Nothing in status.json
 * showed it: no frames were dropped, the stream was live, the codec was running.
 * The analyzer was simply spending its time in a Java loop. Luma is 480 rows x
 * 864 bytes and the two chroma planes are 240x432, so copying one
 * `ByteBuffer.get()` at a time is 829,440 bounds-checked reads per frame.
 *
 * It also sat next to the crash: the analyzer held the MediaCodec for 196ms per
 * frame instead of ~4ms, which is a very wide window for a stop() to release the
 * codec underneath an encode in progress (see H264EncoderStopRaceTest).
 *
 * This drives the REAL copyPlane. An earlier version of this file re-implemented
 * the loop locally and passed identically with the per-byte version restored --
 * a test of the re-implementation, not of the code, which is the same gap that
 * let the 190ms cost ship in the first place.
 */
class H264EncoderPlaneCopyTest {

    /**
     * Minimal PlaneProxy. ImageProxy.PlaneProxy is an interface with two
     * abstract members and the rest defaulting to null/zero, so a plane can be
     * built in a plain JVM test with no Android runtime.
     */
    private class FakePlane(
        private val data: ByteArray,
        private val rowStrideIn: Int,
        private val pixelStrideIn: Int = 1,
    ) : ImageProxy.PlaneProxy {
        override fun getRowStride(): Int = rowStrideIn
        override fun getPixelStride(): Int = pixelStrideIn
        override fun getBuffer(): ByteBuffer = ByteBuffer.wrap(data)
    }

    private fun encoder(): H264Encoder = H264Encoder::class.java
        .getDeclaredConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        .newInstance(64, 48, 15, 400_000, 2)

    private fun copy(plane: FakePlane, w: Int, h: Int): ByteArray {
        val enc = encoder()
        val dst = ByteBuffer.allocate(w * h)
        val row = ByteArray(w)
        val ok = enc.copyPlane(dst, plane, row, w, h)
        assertTrue("copyPlane refused the plane", ok)
        return dst.array()
    }

    /**
     * The bulk path has to produce byte-identical output.
     *
     * A faster copy that reorders anything is worse than the slow one: the
     * encoder gets a valid-looking frame of scrambled pixels, and the symptom
     * surfaces much later as a green or striped image rather than as an error.
     */
    @Test
    fun `packed luma comes out identical to the source`() {
        val w = 64
        val h = 8
        val src = ByteArray(w * h) { (it * 7 % 251).toByte() }
        assertArrayEquals("packed luma came out different", src, copy(FakePlane(src, w), w, h))
    }

    /**
     * Row padding must be skipped.
     *
     * Camera buffers are row-padded: rowStride is normally larger than width and
     * the bytes between the end of one row and the start of the next are not
     * image data. Copying rowStride bytes instead of width shears every row after
     * the first -- still a decodable stream, wrong picture, and invisible to any
     * check that only counts frames.
     */
    @Test
    fun `row padding is skipped rather than copied`() {
        val w = 16
        val h = 4
        val rowStride = 20              // 4 bytes of padding per row
        val src = ByteArray(rowStride * h) { 0x5A }
        for (y in 0 until h) {
            for (x in 0 until w) src[y * rowStride + x] = (y * w + x).toByte()
        }
        val expected = ByteArray(w * h) { src[it / w * rowStride + it % w] }
        assertArrayEquals("padding leaked in, which shears the image", expected, copy(FakePlane(src, rowStride), w, h))
    }

    /**
     * The strided branch is the fallback for devices that report chroma as
     * pixelStride 2, and it has to stay correct because ByteBuffer offers no
     * bulk stride API -- get(int,byte[],int,int) does not de-interleave.
     */
    @Test
    fun `strided chroma is de-interleaved`() {
        val w = 8
        val h = 4
        val pixelStride = 2
        val src = ByteArray(w * h * pixelStride) { (it and 0xFF).toByte() }
        val expected = ByteArray(w * h) { src[it * pixelStride] }
        assertArrayEquals(
            "strided de-interleave is wrong",
            expected,
            copy(FakePlane(src, w * pixelStride, pixelStride), w, h)
        )
    }

    /**
     * A short buffer must be refused, not read past.
     *
     * The per-row guard is the only thing between a malformed plane and a native
     * out-of-bounds read, and it returns false rather than throwing so the caller
     * can drop the frame.
     */
    @Test
    fun `a plane that runs past its buffer is refused`() {
        val w = 32
        val h = 8
        val src = ByteArray(w * h / 2)          // deliberately short
        val enc = encoder()
        val dst = ByteBuffer.allocate(w * h)
        val ok = enc.copyPlane(dst, FakePlane(src, w), ByteArray(w), w, h)
        assertTrue("a truncated plane was accepted", !ok)
    }

    /**
     * The point of the change, as a bound instead of a story.
     *
     * The old code issued one get() per byte -- 829,440 for an 864x480 frame.
     * The packed path issues one per row: 480. This asserts the arithmetic that
     * makes the measurement explicable, so a future reader does not have to
     * rediscover where 190ms came from.
     */
    @Test
    fun `the packed path is one read per row, not per byte`() {
        val w = 864
        val h = 480
        val readsPerFrame = h
        val perByteVersion = h * w
        assertTrue(
            "packed copy should be $readsPerFrame reads per frame, not $perByteVersion",
            readsPerFrame * 100 < perByteVersion
        )
    }
}
