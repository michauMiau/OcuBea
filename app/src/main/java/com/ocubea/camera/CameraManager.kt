package com.ocubea.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.ocubea.model.CameraConfig
import com.ocubea.model.OcuBeaConfig
import com.ocubea.stream.FrameHub
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.ocubea.perf.Metrics
import kotlin.math.roundToInt

/**
 * Headless camera manager for background streaming.
 *
 * Uses ImageAnalysis (YUV → JPEG in memory) so nothing touches disk per frame.
 * Frames are published into a [FrameHub] consumed by every MJPEG viewer.
 * All tunables come from [OcuBeaConfig] — there are no magic numbers here.
 */
class CameraManager(
    private val context: Context,
    private val config: OcuBeaConfig = OcuBeaConfig(context)
) {

    val frameHub = FrameHub()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraRef: androidx.camera.core.Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ocubea-analysis").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    @Volatile var isStreaming = false
        private set

    /**
     * What the caller asked for, as opposed to what the device is doing.
     *
     * `isStreaming` answers "is the camera open", which is what /status.json
     * must report. This answers "should it be open", which is what start(),
     * stop() and rebind() branch on. Conflating the two is how a settings change
     * arriving while the device is still PENDING_OPEN gets dropped.
     */
    @Volatile private var wantStreaming = false

    /**
     * Monotonic token for the current open attempt.
     *
     * start() is asynchronous: bindToLifecycle returns a handle immediately and
     * the device opens later, so two callers can both pass an `isStreaming`
     * guard and both bind -- and the second unbindAll() cancels the first. Every
     * async continuation carries the generation it began with and checks it
     * before touching the camera, so only the newest one wins.
     */
    private val openGeneration = AtomicInteger(0)

    /**
     * Reopen backoff, doubling from 1 s to a 30 s ceiling.
     *
     * The old watchdog reopened every 30 s with no backoff, and a stop/open
     * pair overlapping the previous device's async close is itself a way to
     * provoke another error -- a restart loop feeding itself.
     */
    @Volatile private var retryDelayMs = 1_000L
    @Volatile private var retryScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stateObserver: Observer<CameraState>? = null

    /** True while a reopen is already queued, so the watchdog does not stack one. */
    val isRetryPending: Boolean get() = retryScheduled

    /** Live values mirrored from config at bind time, safe to read from any thread. */
    @Volatile var nightVisionEnabled = config.nightVision
    @Volatile var effect = config.effect
    @Volatile var currentTargetWidth = config.resolution.width
    @Volatile var currentTargetHeight = config.resolution.height
    @Volatile var targetFps = config.frameRate

    /**
     * What the camera device actually granted, not what was asked for.
     *
     * 0 means "not known yet", which is different from 0 fps and is reported as
     * null rather than a number. Reporting [targetFps] as if it were a
     * measurement is how a 10 fps setting came to be displayed next to a
     * 6 fps phone with no hint that the two disagreed.
     */
    @Volatile var measuredFps: Int? = null
        private set

    @Volatile var jpegQualityOverride = config.jpegQuality

    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null

    /**
     * Fired for EVERY analysed frame, before any JPEG work.
     *
     * The service watchdog used to infer liveness from the JPEG callback, which
     * only fires when someone consumes MJPEG. An HLS-only client therefore looked
     * like a dead camera and the watchdog tore the encoder down every 30 seconds.
     * This heartbeat is independent of who — if anyone — is watching.
     */
    @Volatile var onFrameHeartbeat: (() -> Unit)? = null

    private var frameCounter = 0L
    private var lastFrameNanos = 0L
    private var dropDecisions = 0

    /**
     * Rolling one-second frame counter, and the measurement it produces.
     *
     * [measuredFps] used to be declared and never written: every read returned
     * null and /status.json fell through to the *requested* rate, so the page
     * showed the setting rather than the device. The window is measured on the
     * analyzer thread, after the FPS limiter, so it counts what the camera
     * actually delivered rather than what the limiter let past.
     */
    private val fpsWindowStartMs = AtomicLong(0L)
    private val fpsWindowFrames = AtomicLong(0L)

    /** Resets the window so a rebind cannot be read as a stall. */
    private fun resetFpsWindow() {
        fpsWindowStartMs.set(0L)
        fpsWindowFrames.set(0L)
    }

    /**
     * Folds one frame into the window; returns true when a window just closed.
     *
     * The return value is what keeps the governor off the per-frame path: the
     * resolution decision is made at most once a second, from a settled
     * measurement, instead of on every frame from a number that is still
     * counting up.
     */
    private fun rollFpsWindow(nowMs: Long): Boolean {
        val frames = fpsWindowFrames.incrementAndGet()
        val start = fpsWindowStartMs.get()
        if (start == 0L) {
            fpsWindowStartMs.compareAndSet(0L, nowMs)
            return false
        }
        val elapsed = nowMs - start
        if (elapsed < FPS_WINDOW_MS) return false
        // Losing this race just means another frame's window already rolled
        // over; the next frame starts a new one either way.
        if (!fpsWindowStartMs.compareAndSet(start, nowMs)) return false
        fpsWindowFrames.set(0L)
        // Never publish zero: a zero is indistinguishable from "not measured",
        // and measuredFps already uses null for that.
        if (frames <= 0L) return true
        val fps = frames * 1000.0 / elapsed
        if (fps.isFinite() && fps >= 1.0) {
            measuredFps = fps.roundToInt().coerceIn(1, 240)
        }
        return true
    }

    /**
     * The quality ladder, anchored to whatever the user asked for.
     *
     * Held outside [applyConfigToFields] on purpose: that runs on every
     * start() and every watchdog reopen, and re-anchoring there would throw
     * away the downgrade the moment the camera reopened -- which is exactly
     * when it must not be thrown away.
     */
    private val adaptive = AdaptiveResolutionGovernor { msg ->
        android.util.Log.i("OcuBeaCam", msg)
    }

    /** The resolution the camera is bound at, which may be below the setting. */
    /** Public so telemetry can show the rung in use next to the one requested. */
    val effectiveVideoWidth: Int get() = effectiveWidth
    val effectiveVideoHeight: Int get() = effectiveHeight
    private val effectiveWidth: Int get() = adaptive.rung().width
    private val effectiveHeight: Int get() = adaptive.rung().height

    /**
     * The hardware encoders are configured for one size for their whole life.
     *
     * HLS bakes width/height into the muxer and the codec config, and a clip
     * bakes them into ftyp/moov when the file is opened. Re-binding the camera
     * at a new size while either is running hands them frames whose geometry
     * contradicts their own headers, so the ladder stands down instead.
     */
    private fun adaptiveBlockedByEncoder(): Boolean =
        (hlsSession?.isEncoding == true) || (clipEncoder != null)

    /**
     * Reusable JPEG encode buffers.
     *
     * A fresh [ByteArrayOutputStream] per frame meant a fresh multi-hundred-KB
     * allocation, grown by doubling, on every single frame. `reset()` clears
     * the write position but *keeps* the backing array, so pooling the stream
     * removes that churn from the steady state entirely.
     */
    private val jpegPool = JpegBufferPool()

    /**
     * Per-thread scratch pixel array for effects.
     *
     * ThreadLocal because encoding now runs on a pool: a single shared
     * IntArray would be a data race, and allocating one per frame is what
     * caused the GC pressure in the first place. Each pooled thread reuses
     * its own buffer, grown only when the frame size changes.
     */
    // ThreadLocal.withInitial is API 26. The initialValue form works on API 23
    // and behaves identically here, because the array is empty and grow() is
    // called on the frame path anyway.
    private val pixelScratch = object : ThreadLocal<IntArray>() {
        override fun initialValue(): IntArray = IntArray(0)
    }

    /** Frames dropped because the encoder pool was already saturated. */
    @Volatile var droppedSaturated = 0L; private set

    /** Cached night-vision tone curve; built once, immutable thereafter. */
    @Volatile private var nightVisionLutCache: IntArray? = null

    /**
     * Encoder thread pool.
     *
     * Previously the entire pipeline (YUV→Bitmap, effects, JPEG encode,
     * publish) ran on the single analyzer thread, one frame at a time, which
     * is what capped the stream at ~5fps: a single `Bitmap.compress` is
     * 115-145ms of blocking JNI and nothing could overlap with it.
     *
     * Now the analyzer only does the cheap conversion and hands off to this
     * pool, so while frame N is being encoded, frame N+1 is already being
     * converted. Each task gets its own bitmap, so `Bitmap.compress` is never
     * invoked concurrently on the same pixels.
     */
    private val encodePool: java.util.concurrent.ExecutorService by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        analysisExecutorSize = n
        java.util.concurrent.Executors.newFixedThreadPool(n) { r ->
            Thread(r, "ocubea-encode-$n").apply { priority = Thread.NORM_PRIORITY }
        }
    }

    /** Frames handed to the encoder but not yet published. */
    @Volatile private var pendingEncodes = 0

    /** Last source frame size actually delivered by the camera, to verify requests are honoured. */
    @Volatile private var lastSrcW = 0
    @Volatile private var lastSrcH = 0
    @Volatile private var lastSrcFormat = 0

    /**
     * Live HLS session, or null when nobody is watching.
     *
     * The encoder is fed straight from the camera's YUV planes, so enabling it
     * costs almost nothing on the CPU — the whole reason HLS is viable here
     * where JPEG was the bottleneck.
     */
    @Volatile var hlsProfile: com.ocubea.model.HlsProfile =
        com.ocubea.model.HlsProfile.DEFAULT
    @Volatile var hlsSession: com.ocubea.stream.HlsSession? = null
        private set
    @Volatile private var hlsLastError = "none"

    /**
     * Whether any MJPEG consumer still needs frames.
     *
     * With HLS running and no MJPEG viewers, the expensive Bitmap+JPEG path is
     * skipped entirely — that is where the CPU savings come from.
     */
    private fun mjpegWanted(): Boolean = frameHub.viewerCount() > 0

    /**
     * Whether the motion chain still needs a JPEG this frame.
     *
     * Motion detection is the app's main feature, so it outranks the MJPEG
     * optimisation: while the detector or the AVI recorder is enabled, frames
     * must keep flowing to [onFrameCaptured] even with zero viewers.
     *
     * Injected rather than reached for: the detector and recorder are owned by
     * StreamService, so this is a predicate the service hands over instead of a
     * lookup that would couple the camera to the security package.
     */
    @Volatile var motionActiveProvider: () -> Boolean = { false }

    private fun motionNeedsJpeg(): Boolean = motionActiveProvider()

    /** Owner used for bindToLifecycle — set by the service, not the activity. */
    var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null

    /**
     * Tiny pool of resettable [ByteArrayOutputStream]s.
     *
     * Two is enough: a frame is fully encoded and copied out before the next
     * encode starts. Bounded so a transient resolution bump cannot retain an
     * oversized buffer forever.
     */
    private class JpegBufferPool {
        private val free = ArrayDeque<ByteArrayOutputStream>()

        fun acquire(): ByteArrayOutputStream {
            val bos = free.removeFirstOrNull() ?: ByteArrayOutputStream(256 * 1024)
            bos.reset()
            return bos
        }

        fun release(bos: ByteArrayOutputStream) {
            if (bos.size() <= 4 * 1024 * 1024) free.addLast(bos)
        }
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Bind camera to the service lifecycle so streaming survives the activity
     * being minimized or destroyed.
     */
    fun start(onError: (String) -> Unit = {}) {
        if (wantStreaming && isStreaming) return
        wantStreaming = true
        val owner = lifecycleOwner
        if (owner == null) {
            wantStreaming = false
            onError("No lifecycle owner — camera cannot be bound")
            return
        }
        val generation = openGeneration.incrementAndGet()
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                // A newer start(), or a stop(), may have run while this future
                // was in flight. The generation decides who may bind.
                if (generation != openGeneration.get() || !wantStreaming) return@addListener
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    applyConfigToFields()
                    bindAnalysis(provider, owner, generation)
                    // isStreaming is deliberately NOT set here. bindToLifecycle
                    // returns before the device is open, so setting it now is
                    // reporting a wish as a fact -- which is exactly what
                    // /status.json was reporting while the camera was dead. It
                    // is set by the state observer on CameraState.Type.OPEN.
                } catch (e: Exception) {
                    isStreaming = false
                    val msg = "Failed to start camera: ${e.message}"
                    onError(msg)
                    onCameraError?.invoke(msg)
                    scheduleReopen(msg)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            val msg = "Failed to start camera: ${e.message}"
            onError(msg)
            onCameraError?.invoke(msg)
            scheduleReopen(msg)
        }
    }

    /**
     * Reacts to CameraX's own view of the device.
     *
     * This is the only way to learn about an asynchronous device failure.
     * bindToLifecycle returns before the camera is open, so an exception from
     * it says nothing about whether the device ever opened, and a device error
     * arrives long after start() returned.
     *
     * CameraX 1.3.0 treats camera2 code 3 (ERROR_CAMERA_DISABLED) as terminal by
     * design: Camera2CameraImpl$StateCallback.handleErrorOnOpen routes only 1
     * (IN_USE), 2 (MAX_CAMERAS_IN_USE) and 4 (DEVICE) to reopenCameraAfterError,
     * and for 3 it publishes CLOSED with StateError code 5 and closes the device
     * for good. Nothing in this app ever re-opened it, so one disabled camera
     * meant `camera=false` and `HTTP 204` on /shot.jpg until the phone was
     * rebooted -- and the reboot did not help either, because the disable is
     * what persists.
     */
    private inner class CameraStateObserver(private val generation: Int) : Observer<CameraState> {
        override fun onChanged(state: CameraState) {
            // A superseded bind, or a stop() that arrived meanwhile, must not
            // make this observer act on a camera the caller no longer wants.
            if (generation != openGeneration.get() || !wantStreaming) return
            when (state?.type) {
                CameraState.Type.OPEN -> {
                    retryDelayMs = 1_000L
                    isStreaming = true
                }
                CameraState.Type.CLOSED -> {
                    val code = state.error?.code ?: 0
                    isStreaming = false
                    val msg = "Camera closed by device (code $code)"
                    onCameraError?.invoke(msg)
                    scheduleReopen(msg)
                }
                // PENDING_OPEN, OPENING and CLOSING are transient: acting on
                // them is what produced the open/close churn that provokes
                // device errors in the first place.
                else -> Unit
            }
        }
    }

    /**
     * Reopens with a growing delay.
     *
     * Backs off rather than hammering, and does nothing once the caller has
     * asked for the camera to be off.
     */
    private fun scheduleReopen(reason: String) {
        if (!wantStreaming || retryScheduled) return
        retryScheduled = true
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        mainHandler.postDelayed({
            retryScheduled = false
            val owner = lifecycleOwner ?: return@postDelayed
            if (!wantStreaming) return@postDelayed
            val generation = openGeneration.incrementAndGet()
            android.util.Log.w("OcuBeaCam", "Reopening camera after ${delay}ms: $reason")
            try {
                // Already completed after the first start(), so get() returns at
                // once rather than blocking.
                val provider = cameraProvider ?: ProcessCameraProvider.getInstance(context).get()
                cameraProvider = provider
                applyConfigToFields()
                bindAnalysis(provider, owner, generation)
            } catch (e: Exception) {
                val msg = "Camera reopen failed: ${e.message}"
                onCameraError?.invoke(msg)
                scheduleReopen(msg)
            }
        }, delay)
    }

    private fun removeStateObserver() {
        val old = stateObserver ?: return
        stateObserver = null
        val cam = cameraRef ?: return
        runCatching { cam.cameraInfo.getCameraState().removeObserver(old) }
    }

    private fun applyConfigToFields() {
        val res = config.resolution
        // Anchor BEFORE the fields are read: currentTargetWidth/Height are the
        // user's setting and stay that way, so /status.json keeps showing what
        // was asked for, while the governor decides what the camera is bound
        // at. anchor() is a no-op when the setting has not moved, which is what
        // keeps a watchdog reopen from cancelling a pending downgrade.
        adaptive.anchor(res.width, res.height)
        currentTargetWidth = res.width
        currentTargetHeight = res.height
        targetFps = config.frameRate
        effect = config.effect
        nightVisionEnabled = config.nightVision
        jpegQualityOverride = config.jpegQuality
        cameraSelector = if (config.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun bindAnalysis(
        provider: ProcessCameraProvider,
        owner: androidx.lifecycle.LifecycleOwner,
        generation: Int
    ): androidx.camera.core.Camera {
        // The frame rate is a property of the camera device, not of the
        // consumer. Without this the camera keeps whatever rate it booted with
        // and the requested fps only ever reaches the status page -- measured on
        // a Sony F3311: /status.json reported 10 fps while the phone delivered
        // 6, and the hardware was capable of 24. Nothing in the app read the
        // setting back, so the number on the screen was fiction.
        //
        // Applied before bindToLifecycle because that is where the control
        // range is fixed; setting it afterwards is documented to have no effect.

        // Remove the observer BEFORE unbindAll(). Closing the old device
        // publishes CLOSED, and a live observer would read a deliberate rebind
        // as a device failure and queue a reopen that fights the bind happening
        // right here.
        removeStateObserver()
        imageAnalysis?.clearAnalyzer()
        provider.unbindAll()
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }
        val camera = provider.bindToLifecycle(owner, cameraSelector, imageAnalysis!!)
        // The rebind this just performed IS the settle event, whether it was
        // asked for by the user or triggered by the governor. Without this, the
        // first measurement window after any rebind spans the gap in which the
        // camera was closed.
        adaptive.onRebind(System.currentTimeMillis())
        resetFpsWindow()
        applyFrameRate(camera)
        val observer = CameraStateObserver(generation)
        stateObserver = observer
        camera.cameraInfo.getCameraState().observeForever(observer)
        cameraRef = camera
        lastFrameNanos = 0
        return camera
    }

    /**
     * Builds the resolution selector for the requested output size.
     *
     * `setTargetResolution()` is deprecated and on this MediaTek build it was
     * silently ignored — the camera returned 2448x2448 when 1280x720 was
     * asked for, and JPEG-encoding six megapixels on a single thread is what
     * capped the stream at 5fps. An explicit ResolutionStrategy is honoured.
     */
    private fun resolutionSelector(): androidx.camera.core.resolutionselector.ResolutionSelector {
        // The governor's rung, not the setting. currentTargetWidth/Height stay
        // equal to what the user asked for, so that the status page keeps
        // reporting the request while the camera is bound at whatever the
        // device can actually deliver.
        val target = android.util.Size(effectiveWidth, effectiveHeight)
        val fallback = androidx.camera.core.resolutionselector.ResolutionStrategy
            .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        val strategy = androidx.camera.core.resolutionselector.ResolutionStrategy(target, fallback)
        return androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(strategy)
            .setAspectRatioStrategy(
                androidx.camera.core.resolutionselector.AspectRatioStrategy
                    .RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .build()
    }

    /**
     * Turns the torch on or off through CameraX, falling back to the legacy
     * CameraManager API.
     *
     * The legacy `CameraManager.setTorchMode()` CANNOT work while streaming: on
     * this device the camera service answers "torch mode of camera 0 is not
     * available because camera is in use" and the request throws. CameraX drives
     * the torch through the camera's own capture session instead, which is
     * exactly the state we already hold open for streaming, so the torch and the
     * preview coexist.
     *
     * Returns the reason on failure so the UI can say something useful.
     */
    fun setTorch(on: Boolean): String? {
        val cam = cameraRef
        if (cam != null) {
            // enableTorch() returns a ListenableFuture, not a Boolean: the
            // request is async and its success is only known when the future
            // completes. Treat "submitted" as success and let the future report
            // failures in the log, rather than blocking the HTTP handler.
            val submitted = runCatching {
                cam.cameraControl.enableTorch(on)
                true
            }.getOrDefault(false)
            if (submitted) return null
        }
        // Fallback: some devices expose the torch only on the front camera, or
        // CameraX has no control for it. Try the legacy path across all cameras.
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            for (id in cm.cameraIdList) {
                val avail = runCatching {
                    cm.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
                if (!avail) continue
                cm.setTorchMode(id, on)
                return null
            }
            "No camera reports a flash unit"
        } catch (e: Exception) {
            e.message ?: "setTorchMode failed"
        }
    }

    fun stop() {
        wantStreaming = false
        isStreaming = false
        // Bump the generation so an open attempt still in flight for the old
        // camera sees itself as superseded and bails instead of binding over
        // the top of this one.
        openGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        retryScheduled = false
        retryDelayMs = 1_000L
        // The observer goes first: unbindAll() publishes CLOSED, and a live
        // observer would read this deliberate stop as a device failure and
        // queue a reopen.
        removeStateObserver()
        try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
        imageAnalysis = null
        cameraRef = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        runCatching { hlsSession?.stop() }
        hlsSession = null
        frameHub.reset()
        // The measurement window is dead with the camera; leaving it would let
        // the first window after the next start() span the gap in which nothing
        // was streaming, and report about 1 fps.
        resetFpsWindow()
        measuredFps = null
    }

    /**
     * Starts the hardware H.264 encoder and the HLS muxer.
     *
     * Returns false — and leaves MJPEG untouched — when no hardware encoder
     * accepts the live resolution. HLS is an addition here, never a
     * replacement, so a device without a usable encoder still streams fine.
     */
    fun startHls(profile: com.ocubea.model.HlsProfile = hlsProfile): Boolean {
        // A running encoder was configured for the old profile; its segment
        // length and GOP are baked into the muxer and the codec config, so
        // switching has to mean a fresh session rather than a mutated field.
        if (hlsSession?.isEncoding == true) {
            return if (hlsSession?.profile == profile) true else restartHls(profile)
        }
        val w = lastSrcW.takeIf { it > 0 } ?: 1280
        val h = lastSrcH.takeIf { it > 0 } ?: 720
        val session = com.ocubea.stream.HlsSession(
            width = w, height = h, fps = targetFps.coerceIn(5, 30),
            // Configured in kbps rather than derived from the pixel count. The
            // old width*height*4 gave 8.3 Mbps at 1080p and a measured 59 MB per
            // recorded minute, which fills a phone in a couple of hours for a
            // scene that is mostly static.
            bitrate = com.ocubea.model.BitrateBounds.bpsFromKbps(config.videoBitrateKbps),
            profile = profile
        )
        if (!session.start()) {
            hlsLastError = session.lastError
            return false
        }
        hlsSession = session
        hlsLastError = "none"
        return true
    }

    fun stopHls() {
        runCatching { hlsSession?.stop() }
        hlsSession = null
    }

    /**
     * Tears the session down and builds a new one for a different profile.
     *
     * Clients connected to the old session have a playlist they are polling and
     * segments they will keep asking for, so the media sequence restarts and
     * they have to re-read the playlist. hls.js handles that — it sees a
     * sequence number going backwards and reloads — but it does mean switching
     * profiles is visible as a short hiccup rather than being seamless.
     *
     * There is no way around it: KEY_I_FRAME_INTERVAL is a codec-config value
     * and the muxer has already cut segments to the old length.
     */
    private fun restartHls(profile: com.ocubea.model.HlsProfile): Boolean {
        hlsProfile = profile
        stopHls()
        return startHls(profile)
    }

    // ─── Clip recording ────────────────────────────────────────
    //
    // A clip gets its OWN hardware encoder rather than tapping the HLS one.
    // Sharing would mean either the clip stops whenever HLS is idle (the
    // session is created lazily and torn down on the last viewer), or HLS
    // starts encoding for every user who never opened a stream tab — the
    // encoder is the most expensive thing in the pipeline, and MJPEG-only
    // users should not pay for it. Two encoders cost nothing here because
    // MediaCodec instances are cheap and the device has a spare AVC encoder.
    //
    // Both read the same YUV from the ImageAnalysis frame, so the cost of a
    // second encoder is a second hardware pass, not a second camera.

    @Volatile var clipWriter: com.ocubea.security.ClipWriter? = null
    private var clipEncoder: com.ocubea.stream.H264Encoder? = null
    private var clipMuxer: com.ocubea.stream.Fmp4Writer? = null
    private var clipStopAtMs = 0L
    private var clipFps = 30

    @Volatile var clipState: String? = null

    /**
     * Why this clip is being recorded: the user asked for it, or motion did.
     *
     * The distinction matters because the two have different endings — an
     * on-demand clip runs for the requested seconds or until stopped, while a
     * motion clip ends when the scene goes quiet. Without it, a motion clip
     * would keep running for the full duration with nothing happening in it.
     */
    @Volatile var clipReason: String? = null
        private set

    val isOnDemandClip: Boolean get() = clipReason == REASON_ON_DEMAND

    /** True while an on-demand or motion clip is being written. */
    val isRecordingClip: Boolean get() = clipWriter?.recording == true

    /** True once a clip encoder exists and is waiting for its first frame. */
    val isClipArmed: Boolean get() = clipEncoder != null

    /**
     * Arms clip recording. [seconds] <= 0 records until [stopClipRecording].
     *
     * The file is NOT created here. The init segment needs the encoder's
     * SPS/PPS, which only arrive with the first encoded frame, so the clip
     * writer opens the file on the first keyframe in analyzeFrame(). Holding an
     * ImageProxy alive across the call to open a file would leak a native
     * buffer, and guessing the geometry instead of waiting just produces a file
     * whose moov disagrees with its mdat.
     */
    fun startClipRecording(seconds: Int = 0, onDemand: Boolean = true): Boolean {
        if (clipEncoder != null) return true
        val w = lastSrcW.takeIf { it > 0 } ?: 1280
        val h = lastSrcH.takeIf { it > 0 } ?: 720
        val fps = targetFps.coerceIn(5, 30)
        clipFps = fps

        // Same configured bitrate as the HLS encoder. The old width*height*4
        // meant 8.3 Mbps at 1080p, measured at 62 MB per recorded minute —
        // the clip path is the one that fills the card, and it had no setting
        // to lower it.
        // Both GOP length and segment length travel together. The comment in
        // H264Encoder used to promise that a clip passes a longer keyframe
        // interval; nothing did, so the clip encoder got the 0 default - an
        // IDR on every frame - and paid the dense-GOP bitrate cost that
        // keyFrameIntervalSec exists to avoid.
        val enc = com.ocubea.stream.H264Encoder(
            w, h, fps, com.ocubea.model.BitrateBounds.bpsFromKbps(config.videoBitrateKbps),
            com.ocubea.model.HlsProfile.CLIP_KEY_FRAME_INTERVAL_SEC,
        )
        if (!enc.start()) {
            clipState = "encoder: ${enc.lastError}"
            return false
        }
        clipEncoder = enc
        clipMuxer = com.ocubea.stream.Fmp4Writer(fps)
        clipStopAtMs = if (seconds > 0) System.currentTimeMillis() + seconds * 1000L else 0L
        clipReason = if (onDemand) REASON_ON_DEMAND else REASON_MOTION
        clipState = null
        return true
    }

    fun stopClipRecording() {
        clipStopAtMs = 0L
        runCatching { clipWriter?.stop() }
        clipWriter = null
        // Clear the reference BEFORE releasing the codec. stop() is reachable
        // from the analyzer thread: onCameraFrame() runs on an ocubea-encode-N
        // thread and calls this once the motion tail expires, while the next
        // camera frame may already be inside feedClipFrame() holding the
        // encoder's input buffer. Releasing first and nulling second left that
        // frame writing into a freed buffer - "IllegalStateException: buffer is
        // inaccessible" out of copyPlane, which killed the encoder seconds after
        // arming. Both HLS and the clip path failed this way, and it looked
        // like a MediaCodec that could not start.
        val enc = clipEncoder
        clipEncoder = null
        clipMuxer = null
        clipReason = null
        runCatching { enc?.stop() }
        // Retention must be told, not asked: the sweep runs on its own thread
        // and the clip that was open a moment ago is no longer protected, so
        // leaving the old name in place would make that file immortal.
        runCatching { onClipClosed?.invoke() }
    }

    /** Invoked after any clip closes, on whatever thread called stop. */
    @Volatile var onClipClosed: (() -> Unit)? = null

    /** Tears down an in-progress clip, discarding the file — used on camera stop. */
    fun abortClipRecording() {
        clipStopAtMs = 0L
        runCatching { clipWriter?.abort() }
        clipWriter = null
        runCatching { clipEncoder?.stop() }
        clipEncoder = null
        clipMuxer = null
    }

    /** Current clip telemetry for /status.json and /clips/recording. */
    fun clipTelemetry(): Map<String, Any?> {
        val w = clipWriter
        return mapOf(
            "armed" to (clipEncoder != null),
            "active" to (w?.recording == true),
            "file" to w?.activeClip,
            "bytes" to (w?.bytesWritten ?: 0L),
            "frames" to (w?.framesWritten ?: 0),
            "dropped" to (w?.framesDropped ?: 0),
            "error" to (clipState ?: w?.lastError),
        )
    }

    /**
     * Feeds one frame to the clip encoder, opening the file on the first
     * keyframe.
     *
     * The file must not be created before the init segment exists: ftyp+moov
     * carries the avcC record built from the encoder's SPS/PPS, and those
     * arrive with the first encoded output. Writing a media segment first
     * produces a file whose track header contradicts its samples, and the
     * gallery shows a zero-length clip.
     */
    private fun feedClipFrame(image: ImageProxy, ptsUs: Long) {
        val enc = clipEncoder ?: return
        val muxer = clipMuxer ?: return
        val fps = clipFps
        // The writer is created on the first keyframe, but the callback keeps a
        // stable local so samples arriving before that are simply dropped
        // rather than null-checked on every frame.
        var writer: com.ocubea.security.ClipWriter? = clipWriter

        // encode() drains internally, so one callback sees every sample the
        // encoder produces for this frame — codec config, then media.
        enc.encode(image, ptsUs) { sample ->
            val w = writer
            if (w != null) {
                w.offer(sample)
                return@encode
            }
            if (!sample.keyframe) return@encode
            val cfg = enc.codecConfig ?: return@encode
            val init = runCatching { muxer.initSegmentFor(lastSrcW, lastSrcH, cfg) }.getOrNull()
                ?: return@encode
            val created = com.ocubea.security.ClipWriter(context)
            if (!created.openWith(init, fps, muxer)) {
                clipState = created.lastError ?: "cannot open clip"
                stopClipRecording()
                return@encode
            }
            clipWriter = created
            writer = created
            // The open clip must be shielded from the retention sweep, which
            // runs on its own thread and could otherwise delete the file the
            // writer is appending to.
            onClipOpened?.invoke(created.activeClip)
            // This keyframe belongs at the head of the file, not dropped.
            created.offer(sample)
        }
    }

    /** Invoked when a clip file is created, before any media lands in it. */
    @Volatile var onClipOpened: ((String?) -> Unit)? = null

    // ─── Frame pipeline ─────────────────────────────────────────

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val t = if (Metrics.enabled) Metrics.timer(Metrics.FRAME_ANALYZE) else null
        val t0 = if (t != null) t.begin() else 0L
        try {
            if (!isStreaming) { imageProxy.close(); return }

            // FPS limit. Skipping is nearly free: the buffer goes straight back.
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val minInterval = 1_000_000_000L / targetFps.coerceAtLeast(1)
                if (now - lastFrameNanos < minInterval) {
                    dropDecisions++
                    imageProxy.close()
                    return
                }
            }
            lastFrameNanos = now

            // Liveness heartbeat before any expensive work, so the watchdog sees
            // the camera as alive whether the frame goes to MJPEG, to HLS, or is
            // dropped for having no consumer at all.
            onFrameHeartbeat?.invoke()

            // Counted here, after the FPS limiter and before any consumer work,
            // so the number is what the camera delivered rather than what
            // downstream happened to want. Once per closed window, and the
            // decision that follows is the only thing here that can rebind.
            if (rollFpsWindow(now / 1_000_000)) {
                val m = measuredFps
                if (m != null) {
                    val decision = adaptive.observe(
                        System.currentTimeMillis(), m.toDouble(), targetFps
                    )
                    if (decision.action != AdaptiveResolutionGovernor.Decision.Action.NONE) {
                        applyAdaptiveDecision(decision)
                    }
                }
            }

            val srcW = imageProxy.width
            val srcH = imageProxy.height
            lastSrcW = srcW
            lastSrcH = srcH
            lastSrcFormat = imageProxy.format

            // H.264 path: the encoder copies the YUV planes out synchronously
            // inside encode(), so the proxy stays valid and the MJPEG path below
            // can still read it. This ordering matters — encoding first means
            // the hardware encoder never waits behind a JPEG compress.
            //
            // Synchronous does not mean safe against teardown: the codec can be
            // released by the HTTP thread or the motion tail on another thread
            // while this copy is in flight, so isEncoding/isRunning are the
            // gate and they are re-read immediately before each feed.
            val hls = hlsSession
            val hlsFed = hls != null && hls.isEncoding
            if (hlsFed) {
                runCatching { hls!!.encodeFrame(imageProxy, now / 1000) }
                    .onFailure { hlsLastError = it.message ?: "h264 feed failed" }
            }

            // Clip path: a second hardware encoder reading the same YUV. Fed
            // before the JPEG work for the same reason as HLS — never let a
            // software compress delay a hardware encode.
            val clipEnc = clipEncoder
            if (clipEnc != null && clipEnc.isRunning) {
                runCatching { feedClipFrame(imageProxy, now / 1000) }
                    .onFailure { clipState = it.message ?: "clip feed failed" }
                // An on-demand clip with a duration ends itself.
                val deadline = clipStopAtMs
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    stopClipRecording()
                }
            }

            // The expensive Bitmap+JPEG path is only worth paying for when
            // something downstream wants a JPEG: an MJPEG viewer, or the
            // motion detector / recorder. The detector used to be missed here,
            // which silently disabled the feature that is the main reason the
            // app exists - with no viewer open, processJpeg() was never called
            // even though the clip encoder was armed and waiting.
            val clipFed = clipEnc != null
            val jpegNeeded = !((hlsFed || clipFed) && !mjpegWanted()) || motionNeedsJpeg()
            val bitmap = if (jpegNeeded) imageProxy.toBitmap() else null
            imageProxy.close()
            // A null here after an intentional skip is expected, not a failure.
            if (bitmap == null) { if (hlsFed && !motionNeedsJpeg()) return; nullBitmaps++; return }

            // Saturation guard: if encoders are already behind, drop this frame
            // rather than queueing it. A queued frame is a stale frame, and the
            // whole point of this pipeline is low latency.
            if (pendingEncodes >= MAX_PENDING_ENCODES) { droppedSaturated++; bitmap.recycle(); return }

            pendingEncodes++
            try {
                encodePool.execute {
                    try {
                        val processed = applyEffects(bitmap)
                        // applyEffects returns a new bitmap only when an effect
                        // ran; otherwise the source is reused as-is.
                        val ownsProcessed = processed !== bitmap
                        if (ownsProcessed) bitmap.recycle()
                        try {
                            val quality = jpegQualityOverride.coerceIn(40, 100)
                            val bos = jpegPool.acquire()
                            val jpeg: ByteArray
                            try {
                                processed.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                                jpeg = bos.toByteArray()
                            } finally {
                                jpegPool.release(bos)
                            }
                            frameCounter++
                            frameHub.publish(jpeg)
                            onFrameCaptured?.invoke(jpeg, frameCounter)
                        } finally {
                            if (ownsProcessed) processed.recycle()
                        }
                    } catch (e: Exception) {
                        // Never swallow silently: a frame lost here is a frame
                        // the viewer never sees.
                        pipelineErrors++
                        lastPipelineError = "${e.javaClass.simpleName}: ${e.message}"
                        bitmap.recycle()
                    } finally {
                        pendingEncodes--
                    }
                }
            } catch (e: RejectedExecutionException) {
                pendingEncodes--
                bitmap.recycle()
            }
        } catch (e: Exception) {
            pipelineErrors++
            lastPipelineError = "${e.javaClass.simpleName}: ${e.message}"
            try { imageProxy.close() } catch (_: Exception) {}
        } finally {
            // The early returns above (not streaming, frame skipped by the FPS
            // limit) leave through here, so those frames are recorded too. A
            // dropped frame is cheap, and a report that only contains frames
            // that were kept would make the pipeline look slower than it is.
            if (t != null) t.end(t0)
        }
    }

    // ── Pipeline instrumentation (surfaced in /status.json) ────

    @Volatile var nullBitmaps = 0L; private set
    @Volatile var pipelineErrors = 0L; private set
    @Volatile var lastPipelineError: String? = null; private set



    /** Full pipeline timing breakdown, for diagnosing a frame-rate ceiling. */
    fun pipelineTiming(): Map<String, Any> = mapOf(
        "null_bitmaps" to nullBitmaps,
        "pipeline_errors" to pipelineErrors,
        "last_error" to (lastPipelineError ?: "none"),
        "dropped_saturated" to droppedSaturated,
        "pending_encodes" to pendingEncodes,
        "encode_threads" to analysisExecutorSize,
        "src_w" to lastSrcW,
        "src_h" to lastSrcH,
        "src_format" to lastSrcFormat,
        // Measured, not requested. A status page that could not tell the two
        // apart is how a 10 fps setting came to sit next to a 6 fps phone with
        // nothing indicating that they disagreed. Null until the first window
        // closes, which is the same "not known yet" the field has always used.
        "measured_fps" to (measuredFps ?: -1),
        // The ladder's current rung next to the user's own setting, so a
        // downgraded stream is visible as such rather than looking like the
        // camera quietly ignoring the resolution menu.
        "target_w" to currentTargetWidth,
        "target_h" to currentTargetHeight,
    ) + adaptive.telemetry()

    /** HLS / hardware-encoder state, for /status.json and the WebUI. */
    fun hlsStatus(): Map<String, Any> {
        val s = hlsSession
        return mapOf(
            "active" to (s?.isActive == true),
            "codec" to (s?.codecName ?: "none"),
            "frames_encoded" to (s?.framesEncoded ?: 0L),
            "frames_queued" to (s?.framesQueued ?: 0L),
            "frames_dropped" to (s?.framesDropped ?: 0L),
            "segments" to (s?.segmentsWritten ?: 0L),
            "bytes" to (s?.bytesEncoded ?: 0L),
            "measured_fps" to (s?.measuredFps ?: 0.0),
            // The profile the running session was actually built with, not the
            // one that was requested. They differ when a switch failed to
            // restart the encoder, and the difference is exactly what makes a
            // measurement look wrong.
            "profile" to when (hlsProfile) {
                com.ocubea.model.HlsProfile.LOW_LATENCY -> "low"
                com.ocubea.model.HlsProfile.HIGH_QUALITY -> "high"
                else -> "default"
            },
            // segment_ms is the profile's REQUESTED length, and in low-latency
            // mode it is not what the client gets: the muxer cuts on the next
            // IDR, and with one IDR per frame every segment is a single frame.
            // Reporting only the requested value made a working stream look
            // 25% longer than it was, which is how "HLS is broken" was
            // concluded from a healthy encoder. real_segment_ms is measured off
            // the segments the ring actually holds; null before the first one.
            "segment_ms" to hlsProfile.segmentMs,
            "real_segment_ms" to (s?.lastSegmentDurationMs ?: 0L),
            "keyframe_sec" to hlsProfile.keyFrameIntervalSec,
            "last_error" to hlsLastError
        )
    }

    @Volatile private var analysisExecutorSize = 1

    /** Frames deliberately skipped by the FPS limiter — surfaced in /status.json. */
    fun droppedFrames(): Int = dropDecisions

    // ─── Effects ────────────────────────────────────────────────

    private fun applyEffects(source: Bitmap): Bitmap {
        val eff = when {
            nightVisionEnabled && (effect == "none" || effect == "off") -> "nightvision"
            else -> effect
        }
        if (eff == "none" || eff == "off") return source

        val w = source.width
        val h = source.height
        val count = w * h
        // Reuse this thread's scratch array; only grow when the size changes.
        // At 720p this array is ~3.7MB, and allocating it per frame was the
        // single largest source of GC pressure in the pipeline.
        var pixels = pixelScratch.get()
        if (pixels.size < count) {
            pixels = IntArray(count)
            pixelScratch.set(pixels)
        }

        source.getPixels(pixels, 0, w, 0, 0, w, h)

        when (eff) {
            "mono" -> grayscale(pixels, count)
            "negative" -> invert(pixels, count)
            "sepia" -> sepia(pixels, count)
            "nightvision" -> nightVision(pixels, count)
            else -> return source // unknown → passthrough
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun grayscale(p: IntArray, n: Int) {
        for (i in 0 until n) {
            val c = p[i]
            val g = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            p[i] = Color.argb(255, g, g, g)
        }
    }

    private fun invert(p: IntArray, n: Int) {
        for (i in 0 until n) {
            val c = p[i]
            p[i] = Color.argb(255, 255 - Color.red(c), 255 - Color.green(c), 255 - Color.blue(c))
        }
    }

    private fun sepia(p: IntArray, n: Int) {
        for (i in 0 until n) {
            val c = p[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val nr = ((r * 393 + g * 769 + b * 189) shr 8).coerceAtMost(255)
            val ng = ((r * 349 + g * 686 + b * 168) shr 8).coerceAtMost(255)
            val nb = ((r * 272 + g * 534 + b * 131) shr 8).coerceAtMost(255)
            p[i] = Color.argb(255, nr, ng, nb)
        }
    }

    private fun nightVision(p: IntArray, n: Int) {
        // Precomputed 256-entry LUT: Math.pow per channel per pixel is the
        // difference between a smooth 30fps and a stuttering one.
        val lut = nightVisionLut()
        val gr = (1 - GREEN_TINT * 0.3f)
        val gg = (1 + GREEN_TINT * 0.7f)
        val gb = (1 - GREEN_TINT * 0.8f)
        for (i in 0 until n) {
            val c = p[i]
            var r = lut[Color.red(c)] * gr
            var g = lut[Color.green(c)] * gg
            var b = lut[Color.blue(c)] * gb
            p[i] = Color.argb(255, r.toInt(), g.toInt(), b.toInt())
        }
    }

    private fun nightVisionLut(): IntArray {
        val cached = nightVisionLutCache
        if (cached != null) return cached
        val lut = IntArray(256)
        for (i in 0 until 256) {
            val boosted = (i * BOOST).coerceAtMost(255f) / 255f
            lut[i] = (Math.pow(boosted.toDouble(), GAMMA).toFloat() * 255f).toInt().coerceIn(0, 255)
        }
        nightVisionLutCache = lut
        return lut
    }

    // ─── Controls ───────────────────────────────────────────────

    fun setFrontFacingCamera(front: Boolean) {
        val owner = lifecycleOwner ?: return
        config.frontCamera = front
        cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        rebind(owner)
    }

    fun setQuality(resolution: CameraConfig.Resolution) {
        config.resolution = resolution
        currentTargetWidth = resolution.width
        currentTargetHeight = resolution.height
        // The user has just stated a resolution, so the ladder is re-anchored
        // there immediately rather than after the next watchdog reopen. Without
        // this, a pick of 640x480 would not take effect until the camera happened
        // to restart.
        adaptive.anchor(resolution.width, resolution.height)
        rebind(lifecycleOwner ?: return)
    }

    /**
     * Asks the camera for [targetFps] and records what it granted.
     *
     * The frame rate belongs to the camera, not to the thing consuming its
     * frames. `setFrameRate()` only wrote to config, so nothing ever told the
     * camera: /status.json reported 10 fps while the phone delivered 6, and the
     * hardware would have done 24. The number on the status page was the
     * request, printed as though it were a measurement.
     *
     * CameraX 1.3.0 has no frame-rate control of its own -- `CameraControl`
     * exposes torch, focus, zoom and exposure and nothing else -- so this goes
     * through the camera2 interop and sets CONTROL_AE_TARGET_FPS_RANGE
     * directly. That key is a *range*, which is why the value is a fixed
     * [want, want] pair rather than a number: a range reads as "do exactly this
     * rate", and the camera clamps it to whatever it supports.
     *
     * Nothing here throws. A device that will not honour the request simply
     * keeps the rate it chose, which is a normal outcome and not a failure.
     */
    private fun applyFrameRate(camera: androidx.camera.core.Camera) {
        // targetFps is the user's setting; effectiveFps() is that plus whatever
        // the governor has added on the way down the ladder. The ceiling is
        // unchanged, so a user who asked for 30 still gets 30.
        val want = adaptive.effectiveFps(targetFps).coerceIn(5, 30)
        try {
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(want, want)
                )
                .build()
            camera.cameraControl
                .let { androidx.camera.camera2.interop.Camera2CameraControl.from(it) }
                .setCaptureRequestOptions(options)
        } catch (_: Exception) {
            // UnsupportedOperationException on builds without the interop, or
            // IllegalArgumentException from a vendor camera that rejects the
            // key. Either way the camera runs at its own rate and works.
        }
    }

    /**
     * Applies one decision from [AdaptiveResolutionGovernor].
     *
     * The split between the two actions is the whole reason the class has two
     * of them. A resolution change has to close and reopen the camera, which
     * costs the viewer a second of black and is exactly what must not happen
     * often. An fps change does not: it goes into the next capture request on
     * the camera that is already open, so the relief below the floor is free.
     *
     * Posted to the main thread because both paths touch the CameraX use case:
     * bindToLifecycle is not safe to call from the analyzer thread, which is
     * where this is invoked from.
     */
    private fun applyAdaptiveDecision(decision: AdaptiveResolutionGovernor.Decision) {
        when (decision.action) {
            AdaptiveResolutionGovernor.Decision.Action.NONE -> Unit

            AdaptiveResolutionGovernor.Decision.Action.FPS_RELIEF -> {
                // No rebind. Push the new rate straight into the open camera.
                android.util.Log.i(
                    "OcuBeaCam",
                    "adaptive: fps request now ${decision.fps} at ${effectiveWidth}x$effectiveHeight"
                )
                runCatching { cameraRef?.let { applyFrameRate(it) } }
            }

            AdaptiveResolutionGovernor.Decision.Action.RESOLUTION_DOWN,
            AdaptiveResolutionGovernor.Decision.Action.RESOLUTION_UP -> {
                if (adaptiveBlockedByEncoder()) {
                    // A hardware encoder is live and its muxer and codec config
                    // carry the old geometry. Re-binding now would feed it
                    // frames that disagree with its own headers.
                    android.util.Log.i(
                        "OcuBeaCam",
                        "adaptive: ${decision.rung} deferred, encoder running"
                    )
                    return
                }
                mainHandler.post {
                    // A stop() may have landed between the decision and this
                    // post, and a rebind against no camera is a no-op anyway.
                    if (!wantStreaming) return@post
                    if (adaptive.rung().width != effectiveWidth ||
                        adaptive.rung().height != effectiveHeight
                    ) {
                        // Another decision already moved the ladder; this one is
                        // stale and acting on it would undo that.
                        return@post
                    }
                    rebindIfPossible()
                }
            }
        }
    }

    /**
     * Sets the requested capture rate and re-binds so the camera is told.
     *
     * The re-bind is the part that was missing. `CONTROL_AE_TARGET_FPS_RANGE`
     * has to be in the request options when the use case is bound, so writing
     * the field alone left the camera running at whatever it booted with --
     * measured on a Sony F3311, where a change from 10 to 15 did nothing until
     * the rebind. Consumers of the old camera are told first so nothing reads a
     * half-closed device.
     */
    fun setFrameRate(fps: Int) {
        config.frameRate = fps
        targetFps = fps.coerceIn(1, 60)
        // An explicit rate is a decision, not a complaint about the hardware, so
        // the relief the governor added is handed straight back. Leaving it in
        // place would have the app quietly raise the number the user just set.
        adaptive.clearRelief()
        rebindIfPossible()
    }

    /** Re-binds the camera if there is a live one to re-bind. */
    private fun rebindIfPossible() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        // The current generation, not a new one: this is the same camera
        // reopening, and bumping the counter would make every pending state
        // observer believe it is looking at a stale device.
        val generation = openGeneration.get()
        try {
            bindAnalysis(provider, owner, generation)
        } catch (_: Exception) {
            // The rebind is best effort: a rate change that fails to take is
            // not worth tearing the stream down for, and the previous camera
            // keeps working.
        }
    }

    fun setJpegQuality(q: Int) {
        config.jpegQuality = q
        jpegQualityOverride = q.coerceIn(40, 100)
    }

    /**
     * Video bitrate in kbps, for the next HLS session.
     *
     * Not applied to a running encoder: reconfiguring the codec underneath a
     * live viewer drops their stream, and most people set this once.
     */
    fun setVideoBitrateKbps(kbps: Int) {
        config.videoBitrateKbps = kbps
    }

    val videoBitrateKbps: Int get() = config.videoBitrateKbps

    /** Sets the capture effect. Named differently from the [effect] property to avoid a JVM setter clash. */
    fun applyEffect(name: String) {
        config.effect = name
        effect = name
    }

    fun setNightVision(enabled: Boolean) {
        config.nightVision = enabled
        nightVisionEnabled = enabled
    }

    private fun rebind(owner: androidx.lifecycle.LifecycleOwner) {
        // wantStreaming, not isStreaming: a settings change arriving while the
        // device is still PENDING_OPEN must not be dropped.
        if (!wantStreaming) return
        val provider = cameraProvider ?: return
        // A rebind is an open/close pair and follows the same generation
        // discipline as start(): the watchdog may be restarting concurrently.
        val generation = openGeneration.incrementAndGet()
        try {
            bindAnalysis(provider, owner, generation)
        } catch (e: Exception) {
            val msg = "Rebind failed: ${e.message}"
            onCameraError?.invoke(msg)
            scheduleReopen(msg)
        }
    }

    fun setZoom(ratio: Float) {
        try {
            val cam = cameraRef ?: return
            val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(ratio.coerceIn(1f, maxZoom))
        } catch (_: Exception) {}
    }

    fun zoomRatio(): Float = try {
        cameraRef?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    } catch (_: Exception) { 1f }

    fun maxZoomRatio(): Float = try {
        cameraRef?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    } catch (_: Exception) { 1f }

    fun setFocus(xNorm: Float, yNorm: Float) {
        try {
            val cam = cameraRef ?: return
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                currentTargetWidth.toFloat(), currentTargetHeight.toFloat()
            )
            val point = factory.createPoint(xNorm * currentTargetWidth, yNorm * currentTargetHeight)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
            )
        } catch (_: Exception) {}
    }

    fun isUsingFrontCamera(): Boolean = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    fun hasFrontCamera(): Boolean = try {
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true
    } catch (_: Exception) { false }

    fun hasBackCamera(): Boolean = try {
        cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) == true
    } catch (_: Exception) { false }

    fun getConfiguration(): Map<String, Any> = mapOf(
        "resolution" to "${currentTargetWidth}x$currentTargetHeight",
        // What the camera is actually bound at, which after a downgrade is not
        // the resolution above. Reporting only the setting would leave the page
        // claiming 720p while 360p was being encoded.
        "effective_resolution" to "${effectiveWidth}x$effectiveHeight",
        "fps" to frameHub.fps,
        "target_fps" to targetFps,
        "effective_fps" to adaptive.effectiveFps(targetFps),
        // -1 until the first window closes, matching pipelineTiming(). A status
        // page must be able to say "not measured" rather than print the
        // requested number as though the device had confirmed it.
        "measured_fps" to (measuredFps ?: -1),
        "effect" to effect,
        "night_vision" to nightVisionEnabled,
        "viewers" to frameHub.viewerCount(),
        "frames" to frameCounter,
        "dropped" to dropDecisions,
        "jpeg_quality" to jpegQualityOverride,
        "video_bitrate_kbps" to config.videoBitrateKbps,
        "front_camera" to isUsingFrontCamera()
    )

    companion object {
        const val DEFAULT_PORT = 8080

        // Night-vision tone curve, hoisted to constants so the LUT is built once.
        private const val BOOST = 2.4f
        private const val GAMMA = 0.65
        private const val GREEN_TINT = 0.6f

        /**
         * Encode jobs allowed to be in flight.
         *
         * Bounded on purpose: an unbounded queue would let encodes lag further
         * and further behind, and the viewer would then be served increasingly
         * stale frames. Dropping at the door keeps latency flat instead.
         */
        private const val MAX_PENDING_ENCODES = 3

        /**
         * Width of the frame-counting window behind [measuredFps].
         *
         * One second, so the number on the status page is the frame rate a
         * person would count, and so the governor gets a decision at most once
         * a second. Longer would make the response sluggish; shorter would let
         * a single stalled frame read as a collapse.
         */
        private const val FPS_WINDOW_MS = 1_000L

        /** Why a clip is being recorded: the user asked, or motion did. */
        const val REASON_ON_DEMAND = "ondemand"
        const val REASON_MOTION = "motion"
    }
}

/**
 * Steps the capture resolution down when the device cannot keep up, and back
 * up when it can.
 *
 * The premise is that a person is watching video, not counting frames. A
 * 1280x720 stream arriving at 7 fps on a five-year-old MediaTek phone is worse
 * to watch than 640x360 arriving at 14, and the only setting that separates
 * the two is the one nobody opens: the resolution.
 *
 * Deliberately free of Android types and of a clock. Every decision is a
 * function of a timestamp and a measurement passed in, so the whole policy --
 * including the transitions that are near-impossible to provoke on real
 * hardware -- is exercisable as a JVM unit test.
 *
 * Five rules, and each exists because the obvious version is worse:
 *
 * 1. **Two thresholds, not one.** Downgrade below [downRatio] of the request,
 *    upgrade above [upRatio]. A single threshold is a comparator, and a
 *    comparator oscillates: the device sits one frame either side of the line
 *    and the camera is rebound forever.
 * 2. **Asymmetric dwell.** A shortfall must persist for [downHoldMs], a surplus
 *    for [upHoldMs]. Acting on good news as fast as bad news is what makes one
 *    lucky first second tear down a working 720p stream.
 * 3. **A dead band.** Between the two ratios nothing accumulates, so a device
 *    running at 80% of the request is left strictly alone. That is the normal
 *    state for a phone asked for 24 fps and delivering 20, and it is fine.
 * 4. **A cooldown and a settle window.** At most one rebind per [cooldownMs],
 *    and every measurement for [settleMs] afterwards is discarded, because the
 *    frames arriving right after a rebind belong to a camera that is still
 *    opening and say nothing about the one being measured.
 * 5. **A floor, then fps.** The ladder stops at
 *    [AdaptiveResolutionGovernor.FLOOR_INDEX] and the remaining relief is asked
 *    for in frames. 720p -> 480p was measured at 6.9 -> 8.0 fps on a Sony
 *    F3311, so dropping all the way to 320x180 to reach a target is trading a
 *    real quality loss for a frame rate the hardware still may not deliver.
 *    Asking the camera for more is the cheaper next move.
 *
 * On top of that, [stickyAfterOscillations] makes a downgrade sticky: a device
 * that proves it wants to go back up and cannot hold it stops being offered the
 * way up. Being permanently one rung lower is a smaller harm than being
 * permanently rebinding.
 *
 * Not thread-safe by itself. It is touched only from the analyzer thread (via
 * [observe], which is called at most once per measurement window) and from the
 * main thread at rebind time, and every field it reads for a decision is
 * re-anchored by [anchor] on the same paths that rebind, so the two cannot
 * disagree for longer than one rebind.
 */
internal class AdaptiveResolutionGovernor(
    /** Every state change is reported here, so logcat can prove what happened. */
    private val log: (String) -> Unit = {},
) {

    /** A capture size. Deliberately not [com.ocubea.model.CameraConfig.Resolution]. */
    data class Rung(val width: Int, val height: Int) {
        val pixels: Long get() = width.toLong() * height.toLong()
        override fun toString(): String = "${width}x$height"
    }

    /**
     * What the governor wants done, or that it wants nothing.
     *
     * [isRebind] is the part callers branch on: a decision either means a camera
     * is about to close and reopen, or it means the frame path is untouched.
     * That distinction is why FPS_RELIEF is not a rebind -- it can be applied to
     * the live camera without one.
     */
    data class Decision(
        val action: Action,
        val rung: Rung? = null,
        val fps: Int = 0,
        val measured: Int = 0,
        val requested: Int = 0,
    ) {
        enum class Action {
            /** Nothing to do; the frame path is untouched. */
            NONE,

            /** Close and reopen the camera at a smaller size. */
            RESOLUTION_DOWN,

            /** Close and reopen the camera at a larger size. */
            RESOLUTION_UP,

            /**
             * Ask the camera for more frames, or hand back the ones it added.
             * No rebind: the rate goes into the next capture request.
             */
            FPS_RELIEF,
        }

        val isRebind: Boolean
            get() = action == Action.RESOLUTION_DOWN || action == Action.RESOLUTION_UP

        companion object {
            val NONE = Decision(Action.NONE)
        }
    }

    // ── Policy constants ─────────────────────────────────────────
    //
    // Named rather than inlined: every one of these is a guess about hardware,
    // and a guess that cannot be pointed at cannot be argued with. They are
    // vars, not vals, so a test can drive the transitions a real phone will not
    // reproduce on demand.

    /** Below this fraction of the request, the device is judged too slow. */
    var downRatio: Double = 0.60

    /** Above this fraction, it is judged to have headroom to spare. */
    var upRatio: Double = 0.90

    /** How long a shortfall must hold before anything changes. */
    var downHoldMs: Long = 3_000L

    /** How long a surplus must hold before anything changes. Deliberately slower. */
    var upHoldMs: Long = 10_000L

    /** Minimum gap between two rebinds. */
    var cooldownMs: Long = 10_000L

    /** Measurements ignored after a change, while the camera is still opening. */
    var settleMs: Long = 3_000L

    /** How many down-after-up reversals it takes to stop offering the way up. */
    var stickyAfterOscillations: Int = 2

    // ── Ladder ──────────────────────────────────────────────────
    //
    // Sizes the camera can be asked for, widest first. Three of these are not
    // CameraConfig.Resolution members -- 960x540, 854x480 and 640x360 are not --
    // and they are exactly the rungs that matter, because they sit between 720p
    // and nothing. Encoding the ladder here rather than reusing the settings
    // enum is what makes it a ladder instead of a pair of endpoints.

    private val ladder = listOf(
        Rung(1920, 1080),   // 0
        Rung(1280, 720),    // 1
        Rung(960, 540),     // 2
        Rung(854, 480),     // 3
        Rung(640, 360),     // 4
        Rung(480, 270),     // 5  floor: the last rung the ladder will use
        Rung(320, 180),     // 6  reachable only if the user set it themselves
    )

    /** Where the user's own setting sits. The ladder never climbs above it. */
    private var anchorIndex = 2
    private var index = 2

    /** Frames-per-second the governor has added on top of the user's ask. */
    private var reliefSteps = 0

    private var badSinceMs = 0L
    private var goodSinceMs = 0L
    private var lastChangeMs = Long.MIN_VALUE
    private var settleUntilMs = 0L
    private var reversals = 0
    private var lastWasDown = false

    // ── State ───────────────────────────────────────────────────

    /** The size the camera should be bound at right now. */
    fun rung(): Rung = ladder[index]

    /** True while measurements are being ignored because the camera is settling. */
    fun isSettling(nowMs: Long): Boolean = nowMs < settleUntilMs

    /** The effective request after relief. This is what the camera is told. */
    fun effectiveFps(requestedFps: Int): Int =
        (requestedFps + reliefSteps * fpsReliefStep).coerceIn(1, MAX_EFFECTIVE_FPS)

    /** Frames the governor has added to the request, for /status.json. */
    fun reliefSteps(): Int = reliefSteps

    /**
     * Re-anchors to a new user setting.
     *
     * A genuine change of intent resets the whole machine: a user who picks
     * 480p is not a device that failed at 720p, and carrying the old
     * oscillation history into the new setting would strand it at the bottom
     * rung for a reason that no longer applies. Called from every
     * applyConfigToFields, so it must be a no-op when the size has not moved --
     * otherwise a watchdog reopen would silently cancel a pending downgrade.
     */
    fun anchor(width: Int, height: Int) {
        val target = width.toLong() * height.toLong()
        // The LARGEST rung at or below the request, found with indexOfFirst on
        // "at most this many pixels". A size that is not on the ladder (640x480,
        // 800x600) therefore anchors just under itself rather than snapping to
        // the top of the list -- binding above what was asked for would make the
        // problem this class exists to solve worse.
        //
        // indexOfLast here was a one-word bug that anchored every setting to
        // 320x180: the list is ordered widest-first, so the last entry always
        // satisfies "at most" and every rung was found to be too small.
        val found = ladder.indexOfFirst { it.pixels <= target }
        val newAnchor = if (found >= 0) found else 0
        if (newAnchor == anchorIndex) return
        anchorIndex = newAnchor
        index = newAnchor
        reliefSteps = 0
        badSinceMs = 0L
        goodSinceMs = 0L
        reversals = 0
        lastWasDown = false
        log("adaptive: anchored at ${ladder[newAnchor]} (user asked ${width}x$height)")
    }

    /**
     * Drops the fps relief, because the user has stated a rate.
     *
     * An explicit 10 fps is a decision, not a complaint about the hardware, and
     * quietly raising it to 14 would be the app overruling the one thing the
     * user just asked for.
     */
    fun clearRelief() {
        if (reliefSteps == 0) return
        reliefSteps = 0
        log("adaptive: fps relief cleared by explicit request")
    }

    /**
     * Starts the settle window after a rebind.
     *
     * Without it, the first window after a downgrade is measured across the gap
     * in which the camera was closed, reports about 2 fps, and immediately
     * downgrades again -- the cascade this class exists to prevent.
     */
    fun onRebind(nowMs: Long) {
        badSinceMs = 0L
        goodSinceMs = 0L
        lastChangeMs = nowMs
        settleUntilMs = nowMs + settleMs
    }

    /**
     * Folds one settled measurement in and returns what should happen.
     *
     * Called at most once per measurement window, never per frame: the dwell
     * timers need a decision made on a *closed* window, and a window that is
     * still filling has no opinion.
     */
    fun observe(nowMs: Long, measuredFps: Double, requestedFps: Int): Decision {
        val want = requestedFps.coerceAtLeast(1)
        // A sub-1 fps reading is a device that is not streaming, not a device
        // that is slow. Degrading a broken camera makes it slower, not faster.
        if (measuredFps < 1.0 || !measuredFps.isFinite()) return Decision.NONE
        if (isSettling(nowMs)) return Decision.NONE

        val measured = measuredFps.roundToInt()
        val ratio = measuredFps / want

        if (ratio < downRatio) {
            goodSinceMs = 0L
            if (badSinceMs == 0L) {
                badSinceMs = nowMs
                return Decision.NONE
            }
            if (nowMs - badSinceMs < downHoldMs) return Decision.NONE
        } else if (ratio > upRatio) {
            badSinceMs = 0L
            if (goodSinceMs == 0L) {
                goodSinceMs = nowMs
                return Decision.NONE
            }
            if (nowMs - goodSinceMs < upHoldMs) return Decision.NONE
        } else {
            // Dead band: neither credit nor blame. Clearing both timers is the
            // point -- otherwise a device alternating across the band banks an
            // unbounded surplus and then upgrades on the strength of a history
            // it no longer has.
            badSinceMs = 0L
            goodSinceMs = 0L
            return Decision.NONE
        }

        // lastChangeMs starts at Long.MIN_VALUE, and nowMs - MIN_VALUE
        // overflows to a negative number, which would compare as "less than the
        // cooldown" and block the very first decision forever. The explicit
        // sentinel test is the guard against that.
        if (lastChangeMs != Long.MIN_VALUE && nowMs - lastChangeMs < cooldownMs) {
            return Decision.NONE
        }

        val decision = decide(measured, want)
        if (decision.action != Decision.Action.NONE) commit(decision, nowMs)
        return decision
    }

    /** Picks the cheapest step that moves the number. Called with a settled verdict. */
    private fun decide(measured: Int, want: Int): Decision {
        if (badSinceMs != 0L) {
            // Shortfall: the ladder first, and only down to the floor.
            if (index < FLOOR_INDEX) {
                return Decision(
                    Decision.Action.RESOLUTION_DOWN, ladder[index + 1], 0, measured, want,
                )
            }
            // At the floor, the remaining lever is to ask the camera for more
            // frames, which costs nothing in image quality.
            if (reliefSteps < maxReliefSteps) {
                reliefSteps++
                return Decision(
                    Decision.Action.FPS_RELIEF, ladder[index],
                    effectiveFps(want), measured, want,
                )
            }
            return Decision.NONE
        }

        // Surplus. Climb back only if the last move was not a downgrade, or the
        // climb has already failed stickyAfterOscillations times.
        val stuck = lastWasDown && reversals >= stickyAfterOscillations
        if (index > anchorIndex && !stuck) {
            return Decision(Decision.Action.RESOLUTION_UP, ladder[index - 1], 0, measured, want)
        }
        // Nothing to climb to, or climbing is not allowed: hand back the fps
        // the governor took, which needs no rebind at all.
        if (reliefSteps > 0) {
            reliefSteps--
            return Decision(
                Decision.Action.FPS_RELIEF, ladder[index],
                effectiveFps(want), measured, want,
            )
        }
        return Decision.NONE
    }

    private fun commit(decision: Decision, nowMs: Long) {
        badSinceMs = 0L
        goodSinceMs = 0L
        lastChangeMs = nowMs
        settleUntilMs = nowMs + settleMs
        when (decision.action) {
            Decision.Action.RESOLUTION_DOWN -> {
                val from = ladder[index]
                index = ladder.indexOf(decision.rung!!)
                log(
                    "adaptive: DOWN $from -> ${ladder[index]} " +
                        "(${decision.measured} fps vs ${decision.requested} requested)"
                )
            }
            Decision.Action.RESOLUTION_UP -> {
                // A climb straight after a fall is the oscillation this class
                // exists to stop, so it is counted, and the fall becomes sticky
                // once the count passes the limit.
                if (lastWasDown) reversals++ else reversals = 0
                lastWasDown = false
                val from = ladder[index]
                index = ladder.indexOf(decision.rung!!)
                log(
                    "adaptive: UP $from -> ${ladder[index]} " +
                        "(${decision.measured} fps vs ${decision.requested} requested)" +
                        if (reversals >= stickyAfterOscillations) " [sticky]" else ""
                )
            }
            Decision.Action.FPS_RELIEF -> {
                lastWasDown = true
                log(
                    "adaptive: fps ${decision.requested} -> ${decision.fps} at ${ladder[index]} " +
                        "(${decision.measured} fps measured)"
                )
            }
            Decision.Action.NONE -> Unit
        }
    }

    /** Read-only view for /status.json, so the ladder is visible from outside. */
    fun telemetry(): Map<String, Any> = mapOf(
        "rung" to ladder[index].toString(),
        "user_rung" to ladder[anchorIndex].toString(),
        "fps_relief_steps" to reliefSteps,
        // 0 rather than the relief itself when there is none: a status page
        // showing a number the camera is not running at is the same class of
        // lie as reporting the requested fps as the measured one.
        "fps_effective" to if (reliefSteps > 0) effectiveFps(0) else 0,
        "oscillations" to reversals,
        "sticky" to (lastWasDown && reversals >= stickyAfterOscillations),
    )

    companion object {
        /** Frames added to the request per relief step. */
        const val fpsReliefStep = 4

        /** Relief stops here: a phone asked for 30 fps is not a slow phone. */
        const val maxReliefSteps = 4

        /** Ceiling matching the camera's own clamp in applyFrameRate. */
        const val MAX_EFFECTIVE_FPS = 30

        /**
         * The lowest rung the ladder will choose on its own.
         *
         * 480x270 is the point past which a person watching the stream stops
         * being able to read anything on the screen, so below it the governor
         * switches to asking for more frames instead. It is still reachable by
         * the user setting it, which is what [anchor] is for.
         */
        const val FLOOR_INDEX = 5
    }
}
