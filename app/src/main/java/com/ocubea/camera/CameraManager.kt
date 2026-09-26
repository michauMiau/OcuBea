package com.ocubea.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.ocubea.model.CameraConfig
import com.ocubea.model.OcuBeaConfig
import com.ocubea.stream.FrameHub
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Live values mirrored from config at bind time, safe to read from any thread. */
    @Volatile var nightVisionEnabled = config.nightVision
    @Volatile var effect = config.effect
    @Volatile var currentTargetWidth = config.resolution.width
    @Volatile var currentTargetHeight = config.resolution.height
    @Volatile var targetFps = config.frameRate
    @Volatile var jpegQualityOverride = config.jpegQuality

    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null
    var onCameraError: ((String) -> Unit)? = null

    private var frameCounter = 0L
    private var lastFrameNanos = 0L
    private var dropDecisions = 0

    /** Owner used for bindToLifecycle — set by the service, not the activity. */
    var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Bind camera to the service lifecycle so streaming survives the activity
     * being minimized or destroyed.
     */
    fun start(onError: (String) -> Unit = {}) {
        if (isStreaming) return
        val owner = lifecycleOwner
        if (owner == null) {
            onError("No lifecycle owner — camera cannot be bound")
            return
        }
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    applyConfigToFields()
                    bindAnalysis(provider, owner)
                    isStreaming = true
                } catch (e: Exception) {
                    isStreaming = false
                    val msg = "Failed to start camera: ${e.message}"
                    onError(msg)
                    onCameraError?.invoke(msg)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            val msg = "Failed to start camera: ${e.message}"
            onError(msg)
            onCameraError?.invoke(msg)
        }
    }

    private fun applyConfigToFields() {
        val res = config.resolution
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
        owner: androidx.lifecycle.LifecycleOwner
    ): androidx.camera.core.Camera {
        provider.unbindAll()
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(currentTargetWidth, currentTargetHeight))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }
        val camera = provider.bindToLifecycle(owner, cameraSelector, imageAnalysis!!)
        cameraRef = camera
        lastFrameNanos = 0
        return camera
    }

    fun stop() {
        isStreaming = false
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
        imageAnalysis = null
        cameraRef = null
        frameHub.reset()
    }

    // ─── Frame pipeline ─────────────────────────────────────────

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!isStreaming) return

            // Frame-rate limiting: analysis can deliver faster than requested
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val minInterval = 1_000_000_000L / targetFps.coerceAtLeast(1)
                if (now - lastFrameNanos < minInterval) {
                    dropDecisions++
                    return
                }
            }
            lastFrameNanos = now

            val bitmap = imageProxy.toBitmap()
            imageProxy.close()
            if (bitmap == null) return

            val processed = applyEffects(bitmap)
            if (processed !== bitmap) bitmap.recycle()

            val quality = jpegQualityOverride.coerceIn(40, 100)
            val jpeg = ByteArrayOutputStream(processed.byteCount / 4).let { bos ->
                processed.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bos.toByteArray()
            }
            processed.recycle()

            frameCounter++
            frameHub.publish(jpeg)
            onFrameCaptured?.invoke(jpeg, frameCounter)
        } catch (e: Exception) {
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

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
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        when (eff) {
            "mono" -> grayscale(pixels)
            "negative" -> invert(pixels)
            "sepia" -> sepia(pixels)
            "nightvision" -> nightVision(pixels)
            else -> return source // unknown → passthrough
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun grayscale(p: IntArray) {
        for (i in p.indices) {
            val c = p[i]
            val g = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            p[i] = Color.argb(255, g, g, g)
        }
    }

    private fun invert(p: IntArray) {
        for (i in p.indices) {
            val c = p[i]
            p[i] = Color.argb(255, 255 - Color.red(c), 255 - Color.green(c), 255 - Color.blue(c))
        }
    }

    private fun sepia(p: IntArray) {
        for (i in p.indices) {
            val c = p[i]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val nr = ((r * 393 + g * 769 + b * 189) shr 8).coerceAtMost(255)
            val ng = ((r * 349 + g * 686 + b * 168) shr 8).coerceAtMost(255)
            val nb = ((r * 272 + g * 534 + b * 131) shr 8).coerceAtMost(255)
            p[i] = Color.argb(255, nr, ng, nb)
        }
    }

    private fun nightVision(p: IntArray) {
        val boost = 2.4f
        val gamma = 0.65
        val greenTint = 0.6f
        for (i in p.indices) {
            val c = p[i]
            var r = Color.red(c) * boost
            var g = Color.green(c) * boost
            var b = Color.blue(c) * boost
            r = Math.pow((r.coerceAtMost(255f) / 255f).toDouble(), gamma).toFloat() * 255f
            g = Math.pow((g.coerceAtMost(255f) / 255f).toDouble(), gamma).toFloat() * 255f
            b = Math.pow((b.coerceAtMost(255f) / 255f).toDouble(), gamma).toFloat() * 255f
            r *= (1 - greenTint * 0.3f); g *= (1 + greenTint * 0.7f); b *= (1 - greenTint * 0.8f)
            p[i] = Color.argb(255, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
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
        rebind(lifecycleOwner ?: return)
    }

    fun setFrameRate(fps: Int) {
        config.frameRate = fps
        targetFps = fps.coerceIn(1, 60)
    }

    fun setJpegQuality(q: Int) {
        config.jpegQuality = q
        jpegQualityOverride = q.coerceIn(40, 100)
    }

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
        if (!isStreaming) return
        val provider = cameraProvider ?: return
        try {
            bindAnalysis(provider, owner)
        } catch (e: Exception) {
            onCameraError?.invoke("Rebind failed: ${e.message}")
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
        "fps" to frameHub.fps,
        "target_fps" to targetFps,
        "effect" to effect,
        "night_vision" to nightVisionEnabled,
        "viewers" to frameHub.viewerCount(),
        "frames" to frameCounter,
        "dropped" to dropDecisions,
        "jpeg_quality" to jpegQualityOverride,
        "front_camera" to isUsingFrontCamera()
    )

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
