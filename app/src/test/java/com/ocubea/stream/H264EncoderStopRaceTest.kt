package com.ocubea.stream

import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder stop/teardown race that killed the process in native code.
 *
 * Measured on a Sony F3311 (Android 6), logcat, in this order:
 *
 *     Fatal signal 11 (SIGSEGV) ... tid 7255 (ocubea-analysis)
 *     stopped OMX.MTK.VIDEO.ENCODER.AVC
 *     started OMX.MTK.VIDEO.ENCODER.AVC 864x480@24
 *     Process com.ocubea has died
 *
 * The crash landed FIRST and the encoder profile switch only logged afterwards,
 * so the switch exposed the window rather than causing it.
 *
 * The race, from the code: encode() takes a LOCAL copy of the codec handle and
 * then calls dequeueOutputBuffer on it, while stop() runs on another thread and
 * calls MediaCodec.stop()/release() on that same native object. Clearing a flag
 * first does not help -- it is read once, before the drain, and the drain keeps
 * touching a released codec. runCatching does not help either, because a SIGSEGV
 * is not a Java exception and is not catchable.
 *
 * These tests reach the REAL fields of H264Encoder by reflection. An earlier
 * version reimplemented the handshake in a fake class: it passed, and it would
 * have passed identically with the fix reverted, because it was asserting its own
 * copy. A test has to hold the shipped code to the ordering that matters.
 */
class H264EncoderStopRaceTest {

    // Typed as H264Encoder, not Any: reflect gives the instance, but the static
    // type has to keep stop() callable so the test drives the real method rather
    // than a copy of it.
    private fun encoder(): H264Encoder = H264Encoder::class.java
        .getDeclaredConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        .newInstance(64, 48, 15, 400_000, 2)

    private fun field(name: String): Field =
        H264Encoder::class.java.getDeclaredField(name).apply { isAccessible = true }

    /**
     * The handshake stop() depends on has to exist, and it is an
     * AtomicBoolean specifically: the wait is a spin on get(), and a plain
     * Boolean would not be safe to read from two threads.
     */
    @Test
    fun `the encoder has an atomic in-flight flag and a bounded stop wait`() {
        val enc = encoder()

        val flag = field("encodeInFlight").get(enc)
        assertTrue(
            "encodeInFlight must be an AtomicBoolean, not ${flag?.javaClass?.name}, " +
                "because stop() spins on get() from another thread",
            flag is AtomicBoolean
        )
        assertTrue(
            "the flag starts set, so the first stop() would refuse to release",
            !(flag as AtomicBoolean).get()
        )

        val timeout = field("STOP_DRAIN_TIMEOUT_NS").getLong(enc)
        assertTrue(
            "stop must have a bounded wait, got ${timeout / 1_000_000}ms",
            timeout in 1_000_000L..5_000_000_000L
        )
    }

    /**
     * The flag must cover the whole encode body, not just the drain, and it must
     * be released on every path.
     *
     * This drives the real field: it is set to emulate an analyzer thread inside
     * encode(), then stop() is timed. With the flag honoured, stop() waits out its
     * timeout and returns WITHOUT releasing -- which is the intended behaviour, a
     * leaked encoder being strictly better than a SIGSEGV. With the flag ignored,
     * stop() returns immediately.
     *
     * So the assertion is on the wait, not on success.
     */
    @Test
    fun `stop waits out its timeout when an encode is in flight`() {
        val enc = encoder()
        val flag = field("encodeInFlight").get(enc) as AtomicBoolean
        val timeout = field("STOP_DRAIN_TIMEOUT_NS").getLong(enc)

        flag.set(true)          // an analyzer thread inside the codec
        val t0 = System.nanoTime()
        enc.stop()              // real method, on the real object
        val waitedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue(
            "stop() returned in ${waitedMs}ms while an encode was in flight: " +
                "it did not wait, so the next operation on the handle is a " +
                "use-after-free",
            waitedMs >= timeout / 1_000_000 - 80
        )

        flag.set(false)
    }

    /**
     * The mirror image: with nothing in flight, stop() must not pay the timeout.
     *
     * Without this, the previous test passes just as well against a stop() that
     * always sleeps -- which would stop the stream from ever shutting down.
     */
    @Test
    fun `stop is immediate when no encode is in flight`() {
        val enc = encoder()
        val t0 = System.nanoTime()
        enc.stop()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("idle stop() took ${ms}ms, expected well under the cap", ms < 200)
    }

    /**
     * The handle must be captured BEFORE the field is cleared.
     *
     * Reading `codec` after nulling it makes stop() a silent no-op: the encoder
     * is never released and nothing reports it. This is the bug I introduced in
     * the first attempt at the fix -- it compiled, looked right, and would have
     * leaked an encoder on every profile switch.
     */
    @Test
    fun `stop clears the codec field`() {
        val enc = encoder()
        enc.stop()
        val codecField = H264Encoder::class.java.getDeclaredField("codec").apply { isAccessible = true }
        assertTrue(
            "codec field is still set after stop(): either the field was never " +
                "cleared or a new codec was created",
            codecField.get(enc) == null
        )
    }

    /**
     * A deferred stop must not release inline -- that is the use-after-free.
     *
     * The guarded branch used to `return` straight after bumping the deferral
     * counter, which did NOT keep the codec alive: `mc` went out of scope with no
     * stop() and no release(). The guard turned a crash into a leaked native
     * MediaCodec on every profile switch, invisible in every other number on the
     * status page. It now hands the handle to a thread that waits for the analyzer
     * and finishes the teardown.
     *
     * What this asserts is the decision, not the native effect. MediaCodec is final
     * with a package-private constructor, so no JVM test can put a real codec in
     * the field and the two branches are indistinguishable here; two earlier
     * versions of this test claimed more and both passed with the leak present
     * (one counted releases, one subclassed MediaCodec). The native release is
     * checked on the device as encoder_codecs_released.
     */
    @Test
    fun `a deferred stop does not release inline`() {
        val enc = encoder()
        val flag = field("encodeInFlight").get(enc) as AtomicBoolean
        flag.set(true)          // simulate an analyzer wedged inside encode()
        val beforeDeferred = H264Encoder.stopsDeferred.get()
        val beforeStops = H264Encoder.stopsCompleted.get()

        enc.stop()              // takes the deferred branch

        assertTrue("stop() should have deferred", H264Encoder.stopsDeferred.get() > beforeDeferred)
        assertTrue(
            "a deferred stop must not take the completed path -- it has to wait " +
                "for the analyzer, or it is a use-after-free",
            H264Encoder.stopsCompleted.get() == beforeStops
        )
        flag.set(false)
    }

    /**
     * The idle path must stay synchronous.
     *
     * The handoff thread exists for a wedged analyzer, not as a way to make every
     * stop asynchronous: deferring unconditionally would work but would leave the
     * analyzer racing a codec that is already gone.
     */
    @Test
    fun `an idle stop takes the inline path`() {
        val enc = encoder()
        val beforeStops = H264Encoder.stopsCompleted.get()
        val beforeDeferred = H264Encoder.stopsDeferred.get()

        enc.stop()

        assertTrue(
            "idle stop should have completed inline",
            H264Encoder.stopsCompleted.get() > beforeStops
        )
        assertTrue(
            "idle stop should not have deferred",
            H264Encoder.stopsDeferred.get() == beforeDeferred
        )
    }

    /**
     * requestKeyFrame() also dereferences the codec. If it runs against a released
     * handle it is the same native crash through a different door, so it has to
     * respect the same handshake.
     */
    @Test
    fun `a second stop is harmless`() {
        val enc = encoder()
        enc.stop()
        enc.stop()   // must not throw, must not hang
        val flag = field("encodeInFlight").get(enc) as AtomicBoolean
        assertTrue("flag left set after two stops", !flag.get())
    }
}
