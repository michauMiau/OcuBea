package com.ocubea.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import com.ocubea.perf.Metrics
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder fed straight from the camera's YUV planes.
 *
 * The MJPEG path pays for two very expensive steps per frame: a software
 * YUV→ARGB conversion (~30ms) and a blocking JPEG compress (~140ms). This
 * encoder skips both. The camera already hands us YUV_420_888, which is exactly
 * what the hardware encoder wants, so frames go camera→encoder with no
 * conversion at all. Everything CPU-bound disappears from the hot path and the
 * work moves into the MediaTek video encoder block.
 *
 * Only an encoder that was *executed* at startup is used — see
 * [com.ocubea.camera.CodecProbe], because codec capability claims on MediaTek
 * devices are frequently wrong.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    /**
     * Seconds between IDR frames. 0 means every frame is an IDR, which is what
     * HLS needs when a segment must open on a random-access point.
     *
     * A dense GOP is expensive under VBR: with an IDR on every frame the
     * encoder cannot spend bits on inter-prediction, so it refuses to go below
     * its floor and KEY_BIT_RATE is only an upper bound. Measured on the Redmi,
     * 1080p, clip recording, 12000 kbps requested, two samples each:
     * GOP=0 gave 94.4 and 95.4 MB/min (12.6-12.7 Mbps); GOP=1 gave 60.8 and
     * 61.1 MB/min (8.1 Mbps) - 36% less. The encoder reports "max input
     * interval 204ms" and a frame arrives every ~185ms, so it never idles, but
     * a dense GOP still leaves it no frames to predict from.
     *
     * HLS cannot use this: a segment must open on a random-access point, so a
     * GOP longer than the segment leaves most segments waiting for an IDR that
     * does not come, and the playlist advertises durations it cannot deliver.
     * HlsProfile.sanitized() keeps the two in agreement.
     */
    private val keyFrameIntervalSec: Int = 0
) {

    data class Sample(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

    private val TAG = "OcuBeaH264"

    @Volatile var codecName: String = "none"
        private set

    private var codec: MediaCodec? = null
    private var colorFormat = COLOR_YUV420_FLEXIBLE

    /** SPS/PPS from the codec config output; required to build the fMP4 init segment. */
    @Volatile var codecConfig: ByteArray? = null
        private set

    @Volatile private var started = false

    /**
     * True while encode() holds a MediaCodec handle.
     *
     * The flag stop() waits on. See stop() for the crash this prevents: a
     * released MediaCodec touched from the analyzer thread is a native
     * SIGSEGV, and no amount of runCatching contains it.
     */
    private val encodeInFlight = AtomicBoolean(false)

    /**
     * Diagnostic only, default 0. Parks INSIDE drain(), i.e. while the native
     * codec is genuinely mid-use -- the only place a concurrent stop() can
     * produce the use-after-free.
     *
     * It exists because "deferred: 0 after 155 profile switches" cannot tell a
     * working guard behind a now-narrow window apart from dead code, and the two
     * call for opposite conclusions.
     *
     * A first version held after the encode body instead, and the negative
     * control proved it was worthless: guard removed, 900ms hold, still no
     * SIGSEGV -- by then encode() no longer touched MediaCodec. Holding inside
     * the drain is what actually exercises the race.
     *
     * It can only hold, never crash: a stop() that finds the analyzer busy keeps
     * the codec alive rather than releasing it, which is the designed behaviour.
     */
    @Volatile var diagnosticHoldMs: Long = 0

    /** How long stop() waits for the analyzer to leave the codec. */
    private val STOP_DRAIN_TIMEOUT_NS = 500_000_000L   // 500 ms
    @Volatile var framesEncoded: Long = 0L
        private set
    @Volatile var bytesEncoded: Long = 0L
        private set
    @Volatile var lastError: String = "none"
        private set
    @Volatile var framesQueued: Long = 0L
        private set
    @Volatile var framesDropped: Long = 0L
        private set

    /** Reused row buffers for plane copies; see rowY()/rowC(). */
    private var scratchY = ByteArray(0)
    private var scratchC = ByteArray(0)

    val isRunning: Boolean get() = started && codec != null

    /**
     * Configures the hardware AVC encoder.
     *
     * Returns false when no hardware encoder accepts the requested size, in
     * which case the caller must keep serving MJPEG — this is a genuine
     * fallback, not a degraded mode, because HLS needs real H.264.
     */
    fun start(): Boolean {
        // Codec discovery is inside the guard, not just configure(). These
        // calls touch MediaCodecList, which can throw on its own - a locked
        // encoder service, or a stubbed android.jar under JVM test. Letting that
        // escape start() meant the caller got an exception where the whole
        // contract promises a false return plus a readable lastError, and
        // CameraManager.startHls() had nothing to report to /status.json.
        val info = try {
            pickHardwareAvcEncoder()
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "encoder discovery failed", e)
            started = false
            return false
        } ?: run {
            lastError = "no hardware AVC encoder"
            started = false
            return false
        }
        codecName = info.name

        // COLOR_FormatYUV420Flexible is the honest choice: it tells the encoder
        // "I will give you a YUV buffer with arbitrary strides", which is what
        // lets us copy the ImageProxy planes in directly.
        colorFormat = pickColorFormat(info)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // An IDR per frame by default. Every HLS segment must open on a
            // random-access point, and with a 250ms target a longer keyframe
            // interval means most segments would have to wait for the next
            // IDR, so the segment length and the advertised #EXTINF
            // disagree and the playlist drains. The profile exists to keep
            // the two in agreement - HlsProfile.sanitized() refuses a GOP
            // longer than the segment, so the muxer never advertises a
            // duration it cannot deliver.
            //
            // An IDR per frame was previously fatal only because Fmp4Writer
            // treated an in-segment keyframe as "discard what I have", so every
            // frame threw away the previous one and nothing was ever flushed.
            // Now the keyframe closes the open segment and opens the next one,
            // so the cost of a dense GOP is only CPU, which the high profile
            // measurably recovers.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        // mc is declared outside the try so a failed configure() still has a
        // handle to release. MediaCodec has no finalizer reclaiming the native
        // encoder, so dropping the last reference leaks it for the life of the
        // process - and handleHlsPlaylist retries startHls() on every playlist
        // poll, which turns one failed start into a leak every half second.
        var mc: MediaCodec? = null
        return try {
            mc = MediaCodec.createByCodecName(info.name)
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mc.start()
            codec = mc
            started = true
            lastError = "none"
            Log.i(TAG, "started ${info.name} ${width}x$height@$fps color=$colorFormat bitrate=$bitrate")
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "configure failed", e)
            started = false
            codec = null
            // Release the half-built codec; swallowing a release failure is
            // deliberate - the configure error is the one worth reporting.
            try { mc?.release() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Copies one camera frame into the encoder's input buffer.
     *
     * Returns the number of bytes written, or -1 if the frame was rejected.
     *
     * [onSample] is called from this thread, so the HLS writer must not block.
     * The ImageProxy is *not* closed here — the caller owns its lifetime.
     */
    fun encode(image: ImageProxy, ptsUs: Long, onSample: (Sample) -> Unit) {
        // Measured, not optimised. copyPlanes does three memcpys of a full
        // frame per call and is the most likely place for a regression to
        // appear, so the cost is recorded rather than guessed at. The lookup
        // is skipped entirely while Metrics is off, which is the default, so
        // an unmeasured build pays one volatile read.
        val t = if (Metrics.enabled) Metrics.timer(Metrics.ENCODE) else null
        val t0 = if (t != null) t.begin() else 0L

        // Inside the try, both of them. A guard that returns before the try
        // skips the finally that ends the span, so the timer is never closed
        // and the call vanishes from the profile -- silently under-counting
        // exactly when the encoder is broken, which is the only time anyone
        // looks at the profiler. The comment two lines below claims this class
        // of leak was designed out; these two returns were simply missed.
        // Claimed for the WHOLE body, not just the drain: stop() releases the
        // native object, so the flag has to stay set until the last
        // dequeueOutputBuffer and queueInputBuffer have finished. Setting it
        // around the drain alone would reopen the window between the drain and
        // the queue, which is exactly where the crash landed.
        encodeInFlight.set(true)
        // Set before the try so every return still clears it via the finally.
        val heldFrom = System.nanoTime()
        var mDrain1 = 0L; var mDequeue = 0L; var mCopy = 0L; var mDrain2 = 0L
        try {
            val mc = codec ?: return
            if (!started) return
            // Drain first, unconditionally.
            //
            // The encoder has only a handful of input slots; if this method ever
            // returns without draining, the slots fill up, dequeueInputBuffer
            // starts returning -1 forever, and the stream dies silently with
            // zero output. Recovering the slots is the whole reason to call
            // drain before asking for a new one.
            val b1 = System.nanoTime()
            drain(mc, onSample)
            mDrain1 = System.nanoTime() - b1

            // The handshake check lives in stop(), but this is the only place
            // worth measuring from: this call blocks for up to TIMEOUT_US (10ms)
            // holding the handle, which is the whole of the exposure window.

            val b2 = System.nanoTime()
            val inputIndex = mc.dequeueInputBuffer(TIMEOUT_US)
            mDequeue = System.nanoTime() - b2
            if (inputIndex < 0) {
                // Encoder is back-pressured. Dropping the frame is correct:
                // queueing it would only add latency.
                framesDropped++
                return
            }
            val buf = mc.getInputBuffer(inputIndex) ?: return
            val b3 = System.nanoTime()
            val size = copyPlanes(buf, image)
            mCopy = System.nanoTime() - b3
            if (size <= 0) {
                framesDropped++
                return
            }
            mc.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
            framesQueued++
            // Output may already be waiting; collect it now so the ring buffer
            // in HlsSession stays current.
            val b4 = System.nanoTime()
            drain(mc, onSample)
            mDrain2 = System.nanoTime() - b4
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "encode failed", e)
        } finally {
            // A `return` above skips the rest of the try, so the span has to be
            // closed in a finally or a back-pressured frame would never be
            // recorded and the report would understate the cost exactly when
            // the encoder is struggling.
            if (t != null) t.end(t0)
            // Bracket the WHOLE body here, not just dequeueInputBuffer. The stamp
            // used to sit right before that one call, which ignored the drain
            // before it and the queueInputBuffer after it -- i.e. it measured a
            // fraction of the region stop() actually races against, and made the
            // window look far smaller than it is.
            //
            // Own clock, not t0: t0 is the profiler's begin() marker and is 0
            // when Metrics is disabled, so subtracting it measured machine uptime
            // instead of the hold (191s per encode against a 10ms ceiling --
            // an impossible number that pointed straight at the zero).
            encodeHeldNanos.addAndGet(System.nanoTime() - heldFrom)
            encodeCalls.incrementAndGet()
            tDrain1.addAndGet(mDrain1); tDequeue.addAndGet(mDequeue)
            tCopy.addAndGet(mCopy); tDrain2.addAndGet(mDrain2)
            encodeInFlight.set(false)
        }
    }

    /**
     * Forces the next frame to be an IDR, e.g. right after a client joins.
     *
     * setParameters() on a codec that stop() is releasing underneath it is the
     * same native use-after-free as dequeueOutputBuffer, through a different
     * door, so it reads the handle and calls under the same handshake encode()
     * uses. Reading the field and calling straight through -- which is what this
     * did -- has no protection at all.
     *
     * Nothing in main/ calls this today (keyframes come from the GOP the profile
     * sets, and HlsSession starts a segment on the keyframe it observes), so the
     * missing guard never had a chance to fire. The guard belongs with the
     * function rather than with whoever eventually wires it up.
     */
    fun requestKeyFrame() {
        val mc = codec ?: return
        if (!started) return
        // Bounded wait, same policy as stop(): better to skip a keyframe request
        // than to block a client-facing call indefinitely. Nothing currently calls
        // this, so the bound is insurance rather than a measured requirement.
        val deadline = System.nanoTime() + STOP_DRAIN_TIMEOUT_NS
        while (System.nanoTime() < deadline && encodeInFlight.get()) Thread.sleep(1)
        if (encodeInFlight.get()) {
            Log.w(TAG, "requestKeyFrame: encoder busy, skipping the request")
            return
        }
        runCatching {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            mc.setParameters(params)
        }
    }

    /**
     * Stops the codec, but only after the encode thread is out of it.
     *
     * This was a straight data race and it killed the process in native code.
     * encode() holds a LOCAL copy of the codec handle:
     *
     *     val mc = codec ?: return      <- analyze thread
     *     drain(mc, ...)                <- dequeueOutputBuffer on mc
     *
     * while stop() ran on an HTTP thread and did stop()/release() on the same
     * native object. `started = false` does not help: it is read once, before
     * the drain, and drain() then keeps touching a released MediaCodec.
     *
     * `runCatching` cannot save this either. The failure is a SIGSEGV inside
     * libstagefright, not a Java exception -- measured on a Sony F3311 as
     *
     *     Fatal signal 11 (SIGSEGV) ... tid (ocubea-analysis)
     *     stopped OMX.MTK.VIDEO.ENCODER.AVC
     *     started OMX.MTK.VIDEO.ENCODER.AVC 864x480@24
     *     Process com.ocubea has died
     *
     * i.e. the crash landed first and the profile switch only logged afterwards.
     *
     * So the two parties have to agree: encode() marks itself busy for the whole
     * time it holds the handle, and stop() waits for that to be clear before it
     * touches the native object. A bounded wait, because a stop that never
     * returns is worse than the race it prevents.
     */
    fun stop() {
        // Capture FIRST, then clear the field. Reading `codec` after nulling it
        // would make the whole method a no-op -- the handle has to be taken
        // before the analyzer is told there is none, and the field is what stops
        // a NEW encode from starting while the wait runs.
        val mc = codec
        codec = null
        started = false
        val deadline = System.nanoTime() + STOP_DRAIN_TIMEOUT_NS
        while (System.nanoTime() < deadline) {
            if (!encodeInFlight.get()) break
            Thread.sleep(1)
        }
        if (encodeInFlight.get()) {
            // The analyzer is still inside the codec after the full budget.
            // Releasing it now would be a use-after-free, so hand it to a thread
            // that waits for the analyzer to finish and then tears down.
            //
            // It used to `return` here, which did NOT keep the codec alive -- it
            // dropped `mc` on the floor. Nothing released it, and HlsSession kept
            // the dead instance, so every deferral leaked a native MediaCodec.
            // The guard traded a crash for a leak and called the leak
            // recoverable; on a profile switch it happens routinely.
            Log.w(TAG, "stop: analyzer still inside the codec after " +
                "${STOP_DRAIN_TIMEOUT_NS / 1_000_000}ms, " +
                "handing off to a drain thread")
            stopsDeferred.incrementAndGet()
            releaseWhenIdle(mc)
            return
        }
        // Counter, not just the warning: "it never fired" and "it cannot fire"
        // look identical in a log, and tools/encoder_race_verify.sh needs to
        // tell them apart on a device where MediaCodec cannot be faked.
        stopsCompleted.incrementAndGet()
        if (mc != null) {
            if (!claimRelease(mc)) return
            runCatching { mc.stop() }
            runCatching { mc.release() }
            released.incrementAndGet()
        }
    }

    /**
     * Claims the right to release this handle, exactly once.
     *
     * Both teardown paths funnel through here because they can overlap: the
     * inline path and the drain thread started by releaseWhenIdle() race whenever
     * a stop() lands while the analyzer is inside the codec and the flag clears a
     * moment later. Whichever claims first releases; the other must not, since a
     * double release is the native fault the whole handshake is here to prevent.
     *
     * Returns false when someone else already owns the release.
     */
    private fun claimRelease(mc: MediaCodec): Boolean {
        val id = System.identityHashCode(mc)
        if (!releasedIds.add(id)) {
            Log.w(TAG, "release: handle ${Integer.toHexString(id)} already " +
                "released, skipping a double free")
            return false
        }
        return true
    }

    /**
     * Finishes a teardown that could not wait for the analyzer in time.
     *
     * Waits for encodeInFlight to clear and then does what stop() would have
     * done, on its own thread, so the release is never lost. The wait is
     * unbounded on purpose: the alternative is a leaked native object per
     * deferral, and an analyzer that never clears its flag is a bug that should
     * surface as a stuck thread rather than as a slowly growing leak.
     */
    private fun releaseWhenIdle(mc: MediaCodec?) {
        if (mc == null) return
        val t = Thread({
            while (encodeInFlight.get()) Thread.sleep(2)
            // Re-check after the wait: stop() may have completed on another path
            // while this thread was parked, and releasing a handle twice is the
            // fault this whole handshake exists to prevent. `codec` is null in
            // both cases, so the identity is what tells the two apart.
            if (!claimRelease(mc)) return@Thread
            runCatching { mc.stop() }
            runCatching { mc.release() }
            released.incrementAndGet()
        }, "ocubea-release")
        t.isDaemon = true
        try {
            t.start()
        } catch (e: Exception) {
            // Thread creation can fail under memory pressure. Fall back to
            // releasing on this thread -- worse than leaking, but this is a
            // best-effort cleanup path either way.
            //
            // It claims first, like every other release site: falling back to an
            // unclaimed release would reintroduce exactly the double free the
            // claim exists to prevent, on the one path nobody tests because it
            // only runs when the thread could not start at all.
            Log.w(TAG, "could not start release thread, releasing now", e)
            if (claimRelease(mc)) {
                runCatching { mc.stop() }
                runCatching { mc.release() }
                released.incrementAndGet()
            }
        }
    }

    // ─── internals ──────────────────────────────────────────────

    private fun drain(mc: MediaCodec, onSample: (Sample) -> Unit) {
        val info = MediaCodec.BufferInfo()
        if (diagnosticHoldMs > 0) {
            // Still holding: encodeInFlight is deliberately NOT cleared. A stop()
            // landing here has to find the analyzer busy and defer. Keeps calling
            // dequeueOutputBuffer so the codec is not merely idle but genuinely
            // mid-use -- an idle hold would pass the guard without ever
            // reproducing the crash.
            val until = System.currentTimeMillis() + diagnosticHoldMs
            while (System.currentTimeMillis() < until) {
                mc.dequeueOutputBuffer(info, 0)
                Thread.sleep(2)
            }
        }
        while (true) {
            val outIndex = mc.dequeueOutputBuffer(info, 0)
            when {
                outIndex >= 0 -> {
                    // Every dequeued output index MUST be released, on every
                    // path. A leaked native buffer never comes back, the encoder
                    // runs out of slots within seconds at 15fps, and the process
                    // dies in native code. So the release lives in a finally
                    // rather than at the end of the happy path.
                    try {
                        val buf = mc.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && info.offset >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)

                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // SPS/PPS. Kept for the avcC box.
                                codecConfig = bytes
                            } else {
                                framesEncoded++
                                bytesEncoded += bytes.size
                                onSample(
                                    Sample(
                                        data = bytes,
                                        ptsUs = info.presentationTimeUs,
                                        keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        Log.w(TAG, "drain failed", e)
                    } finally {
                        runCatching { mc.releaseOutputBuffer(outIndex, false) }
                    }
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                // Nothing more available right now. This is the normal exit.
                else -> return
            }
        }
    }

    /**
     * Copies Y, U and V into the encoder's input buffer.
     *
     * Stride and pixel-stride must both be respected: a vendor encoder that
     * silently reads a packed buffer from a padded one produces green-and-magenta
     * garbage rather than an error, so this is done per row with an explicit
     * row buffer instead of a bulk put.
     *
     * Every write is bounds-checked against what the encoder actually handed us.
     * That is not defensive padding: overrunning a MediaCodec input buffer is a
     * hard SIGSEGV inside GetPrimitiveArrayRegion, not an exception, so a
     * mismatch between the configured size and the delivered frame would take
     * the whole app down with no recoverable catch.
     *
     * Returns the byte count written, or -1 if the frame was rejected. The count
     * is returned explicitly rather than read back from the buffer: after flip()
     * the position is back at 0 and the length lives in the limit, so callers
     * that read position() get a silent zero and drop every frame.
     */
    private fun copyPlanes(dst: ByteBuffer, image: ImageProxy): Int {
        val w = image.width
        val h = image.height
        val planes = image.planes
        if (planes.size < 3) return -1

        // The encoder was configured for a fixed size. If the camera starts
        // delivering something else, stop rather than overrun the buffer.
        if (w != width || h != height) {
            Log.w(TAG, "frame ${w}x$h does not match encoder ${width}x$height; skipping")
            return -1
        }

        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val needed = w * h + 2 * cw * ch
        if (dst.capacity() < needed) {
            Log.w(TAG, "input buffer ${dst.capacity()} < required $needed; skipping")
            return -1
        }

        dst.clear()
        if (!copyPlane(dst, planes[0], rowY(), w, h)) return -1
        if (!copyPlane(dst, planes[1], rowC(), cw, ch)) return -1
        if (!copyPlane(dst, planes[2], rowC(), cw, ch)) return -1
        return dst.position()
    }

    // Row buffers reused across frames. Encoding is driven from a single
    // analyzer thread, so these need no synchronisation. Reusing them avoids
    // ~2.5KB of garbage per frame, which at 15fps is a steady drip into the
    // heap while the session is live.
    private fun rowY(): ByteArray {
        var r = scratchY
        if (r.size < width) { r = ByteArray(width); scratchY = r }
        return r
    }

    private fun rowC(): ByteArray {
        val need = (width + 1) / 2
        var r = scratchC
        if (r.size < need) { r = ByteArray(need); scratchC = r }
        return r
    }

    /**
     /**
      * Copies one plane row by row. Returns false if the source runs out early.
      *
      * Visible for testing so H264EncoderPlaneCopyTest can drive the real
      * implementation. A test that re-implements this loop proves only that the
      * re-implementation is correct -- it passed identically with the per-byte
      * version restored, which is exactly the gap that let the 190ms cost ship.
      */
     *
     * The per-byte loop this replaces was the single most expensive thing in the
     * encoder, and it was not visible anywhere: status.json showed a perfectly
     * healthy stream, because nothing was dropping frames -- the analyzer was
     * simply spending its time in here.
     *
     * Measured on a Sony F3311 (Android 6) at 864x480:
     *
     *     copyPlanes   5712 ms over 30 encodes   = 190 ms per frame
     *     everything   5886 ms over 30 encodes   = 196 ms per frame
     *
     * so 97% of the time inside encode() was this copy. The count explains it:
     * 480 rows x 864 bytes of luma plus two 240x432 chroma planes with
     * pixelStride 2 is 829,440 separate ByteBuffer.get() calls per frame, each
     * one a bounds check against a direct camera buffer. ~230ns per byte is
     * exactly the cost of doing it one byte at a time.
     *
     * The fix is bulk transfer per row: position once, get(w) or get(w, stride,
     * row) in one call. 829,440 calls become 1,200.
     *
     * This also narrowed the stop/teardown race rather than just costing CPU.
     * The analyzer held the MediaCodec for 196ms per frame instead of ~4ms, so
     * a stop() had a wide window to release the codec underneath an encode in
     * progress -- which is the SIGSEGV in OMX.MTK.VIDEO.ENCODER.AVC.
     */
    internal fun copyPlane(dst: ByteBuffer, plane: ImageProxy.PlaneProxy, row: ByteArray, w: Int, h: Int): Boolean {
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val buf = src.duplicate()
        val base = buf.position()
        val limit = buf.limit()

        // pixelStride 1 is the packed case and a straight row copy. 2 is the
        // semi-planar chroma layout, where a bulk get with a stride does the
        // de-interleave in the JNI layer instead of a Java loop.
        val packed = pixelStride == 1
        // Which case each plane actually takes. The speedup depends entirely on
        // luma being packed, and on this device that is an assumption until it
        // is read off a real frame: the planes are reported with a
        // semi-planar layout often enough that guessing wrong would make the
        // whole change a no-op that still looks like it worked.
        // Not getOrDefault: that is API 24, minSdk is 23 and the phone runs
        // Android 6, so it throws NoSuchMethodError at runtime. That is an Error,
        // not an Exception, so it sails past catch (_: Exception) and killed
        // every encode -- measured frames_encoded 0, frames_dropped 854.
        // Already paid for twice in this project.
        val n = strideSeen[pixelStride] ?: 0L
        strideSeen[pixelStride] = n + 1

        for (y in 0 until h) {
            var idx = base + y * rowStride
            // Guard the source: a row that starts inside the buffer but runs
            // past its end would also fault rather than throw. Checked once per
            // row, not per byte -- which is why the strided loop below can index
            // directly without its own check.
            if (idx + (w - 1) * pixelStride >= limit) {
                Log.w(TAG, "plane row $y exceeds buffer (idx=$idx limit=$limit)")
                return false
            }
            if (packed) {
                // Bulk row copy. position() once, then a single get(array) --
                // one bounds check and one JNI call per row instead of w of
                // them, which is the entire point of this change.
                //
                // Written as position+get rather than the four-argument
                // get(index, array, off, len) because that overload does not
                // resolve in this build despite existing in android.jar, and a
                // form the compiler rejects is worth nothing.
                buf.position(idx)
                buf.get(row, 0, w)
            } else {
                // No bulk stride API exists on ByteBuffer -- there is
                // get(byte[],int,int) and get(int,byte[],int,int), neither of
                // which de-interleaves. So the semi-planar chroma planes still
                // need a per-byte loop, and they are exactly half the bytes.
                //
                // What can be avoided is the bounds check per access, which is
                // the expensive half on a direct camera buffer: the row is
                // checked once above, so index directly.
                for (x in 0 until w) {
                    row[x] = buf.get(idx)
                    idx += pixelStride
                }
            }
            dst.put(row, 0, w)
        }
        return true
    }

    private fun pickHardwareAvcEncoder(): MediaCodecInfo? {
        @Suppress("DEPRECATION")
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            it.isEncoder && isHardware(it) && runCatching {
                it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }.getOrDefault(false)
        }
    }

    // The API-29 call and its guard now live in one place:
    // com.ocubea.camera.isHardwareAvcCapable().
    private fun isHardware(info: MediaCodecInfo): Boolean =
        com.ocubea.camera.isHardwareAvcCapableOf(info)

    /**
     * Chooses the color format whose input buffer can actually hold a frame.
     *
     * COLOR_FormatYUV420Flexible sounds ideal but is a trap: MediaCodec sizes
     * that buffer at w*h*3/2 - 1, one byte short of a full frame. Writing the
     * last luma byte overruns the allocation and SIGSEGVs inside
     * GetPrimitiveArrayRegion with no Java-level catch. Planar 420 (19) and
     * semi-planar (21) both allocate the full size, and the encoder is told the
     * format it will actually receive.
     */
    private fun pickColorFormat(info: MediaCodecInfo): Int {
        val caps = runCatching {
            info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        }.getOrNull() ?: return COLOR_YUV420_SEMI_PLANAR
        val formats = runCatching { caps.colorFormats }.getOrNull()
            ?: return COLOR_YUV420_SEMI_PLANAR
        return when {
            formats.contains(COLOR_YUV420_PLANAR) -> COLOR_YUV420_PLANAR
            formats.contains(COLOR_YUV420_SEMI_PLANAR) -> COLOR_YUV420_SEMI_PLANAR
            formats.contains(COLOR_YUV420_FLEXIBLE) -> COLOR_YUV420_FLEXIBLE
            else -> formats.firstOrNull() ?: COLOR_YUV420_SEMI_PLANAR
        }
    }

    companion object {
        /**
         * Encoder stops that reached MediaCodec.stop()/release().
         *
         * In the companion rather than on the instance, and for a measured
         * reason: a profile switch runs stopHls() -> hlsSession = null -> a NEW
         * encoder, so an instance counter is already back to zero by the time
         * anything can read it. status.json reports the live session, so it
         * would read zero after every single stop -- verified: eight profile
         * switches with HLS actively encoding, encoder_stops still 0.
         *
         * This is the measuredFps bug from this same file, repeated: a counter
         * declared, read by the status page, and reset by the lifetime of the
         * very object it was counting. Cumulative across instances on purpose --
         * the question is how often the handshake waited over the life of the
         * process, not per encoder.
         */
        val stopsCompleted = AtomicLong(0)

        /**
         * How often the stop/teardown handshake had to wait for the analyzer
         * instead of releasing the codec underneath it. Zero is ambiguous on its
         * own -- it is also what a build with no handshake reports -- so it is
         * only meaningful next to stopsCompleted.
         */
        val stopsDeferred = AtomicLong(0)

    /** Codecs actually handed back to the driver. Deferred ones must land here. */
    val released = AtomicLong(0)

    /**
     * Identities of handles already released.
     *
     * A double release is the fault this whole handshake exists to prevent, and
     * a counter cannot catch it: `codec` is null after both the inline path and
     * the deferred one, so only the handle's own identity separates "this thread
     * is the one that must release it" from "someone else already did".
     */
    val releasedIds: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

        /**
         * How long each encode held the codec, summed.
         *
         * Sum rather than max, because the question is "how much of the wall
         * clock is a stop() racing against" -- a single long encode and many
         * short ones answer it differently. Read next to an encode count so it
         * can be turned into an average.
         */
        val encodeHeldNanos = AtomicLong(0)

        /** How many encodes ran at all, so the average is computable. */
        val encodeCalls = AtomicLong(0)

        // Per-step breakdown of the hold. 195.8ms total on a Sony F3311 is far
        // more than dequeueInputBuffer's 10ms limit, so something else in the
        // body blocks, and "something else" is not actionable. Four separate sums
        // so the one that owns the time can be named -- measured, not reasoned
        // about.
        val tDrain1 = AtomicLong(0)

        /**
         * pixelStride -> how many plane copies took that stride.
         *
         * {1=N} means the bulk path runs; {2=N} alone means it never does.
         */
        val strideSeen = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val tDequeue = AtomicLong(0)
        val tCopy = AtomicLong(0)
        val tDrain2 = AtomicLong(0)

        const val COLOR_YUV420_FLEXIBLE = 0x7F420888
        const val COLOR_YUV420_PLANAR = 19
        const val COLOR_YUV420_SEMI_PLANAR = 21
        private const val TIMEOUT_US = 10_000L
    }
}
