package com.ocubea.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.ocubea.model.CameraConfig
import com.ocubea.stream.FrameHub
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless camera manager for background streaming.
 * Uses ImageAnalysis (YUV → JPEG in memory) — no disk writes per frame.
 * Frames are published into a FrameHub consumed by MJPEG viewers.
 */
class CameraManager(private val context: Context) {

    val frameHub = FrameHub()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var imageAnalysis: ImageAnalysis? = null

    @Volatile var isStreaming = false
        private set

    @Volatile var nightVisionEnabled = false

    /** Video effect: none | mono | negative | sepia | nightvision */
    @Volatile var effect: String = "none"

    @Volatile var currentTargetWidth = 1280
    @Volatile var currentTargetHeight = 720

    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null
    private var frameCounter = 0L
    private val busy = AtomicBoolean(false)

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Bind camera to the given lifecycle owner (use a foreground-service lifecycle,
     * NOT an activity, so streaming survives app minimization).
     */
    fun start(lifecycleOwner: androidx.lifecycle.LifecycleOwner, onError: (String) -> Unit = {}) {
        if (isStreaming) return
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindAnalysis(provider)
                    isStreaming = true
                } catch (e: Exception) {
                    onError("Failed to start camera: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            onError("Failed to start camera: ${e.message}")
        }
    }

    private fun bindAnalysis(provider: ProcessCameraProvider): androidx.camera.core.Camera {
        provider.unbindAll()
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(currentTargetWidth, currentTargetHeight))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeFrame) }
        val owner = context as? androidx.lifecycle.LifecycleOwner
            ?: throw IllegalStateException("CameraManager requires a LifecycleOwner context")
        val camera = provider.bindToLifecycle(owner, cameraSelector, imageAnalysis!!)
        cameraRef = camera
        return camera
    }

    fun stop() {
        isStreaming = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        imageAnalysis?.clearAnalyzer()
    }

    private var cameraRef: androidx.camera.core.Camera? = null

    // ─── Frame pipeline ─────────────────────────────────────────

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!isStreaming) return
            val bitmap = imageProxy.toBitmap()
            imageProxy.close()
            if (bitmap == null) return

            val processed = applyEffects(bitmap)
            if (processed !== bitmap) bitmap.recycle()

            val jpeg = ByteArrayOutputStream(processed.byteCount / 4).let { bos ->
                processed.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos)
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

    private val jpegQuality: Int
        get() = if (nightVisionEnabled || effect != "none") 88 else 82

    // ─── Effects ────────────────────────────────────────────────

    private fun applyEffects(source: Bitmap): Bitmap {
        val hasEffect = effect != "none" && effect != "off"
        if (!hasEffect && !nightVisionEnabled) return source

        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val eff = if (nightVisionEnabled && !hasEffect) "nightvision" else effect
        when (eff) {
            "mono" -> grayscale(pixels)
            "negative" -> invert(pixels)
            "sepia" -> sepia(pixels)
            "nightvision" -> nightVision(pixels)
            else -> {} // unknown → passthrough
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
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
        val gt = 0.6f
        for (i in p.indices) {
            val c = p[i]
            var r = Color.red(c) * boost
            var g = Color.green(c) * boost
            var b = Color.blue(c) * boost
            r = Math.pow((r.coerceAtMost(255f) / 255f).toDouble(), gamma).toFloat() * 255f
            g = Math.pow((g.coerceAtMost(255f) / 255f).toDouble(), gamma).toFloat() * 255f
            b = Math.pow((b.coerceAtMost(255f) / 255f).toDouble(), gamma).toFloat() * 255f
            r *= (1 - gt * 0.3f); g *= (1 + gt * 0.7f); b *= (1 - gt * 0.8f)
            p[i] = Color.argb(255, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
    }

    // ─── Controls ───────────────────────────────────────────────

    fun setFrontFacingCamera(front: Boolean, onDone: () -> Unit = {}) {
        val wasStreaming = isStreaming
        stop()
        cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val provider = cameraProvider ?: run { onDone(); return }
        try {
            bindAnalysis(provider)
            isStreaming = wasStreaming
        } catch (_: Exception) {}
        onDone()
    }

    fun setQuality(resolution: CameraConfig.Resolution) {
        currentTargetWidth = resolution.width
        currentTargetHeight = resolution.height
        if (isStreaming) {
            val provider = cameraProvider ?: return
            try { bindAnalysis(provider) } catch (_: Exception) {}
        }
    }


    fun setZoom(level: Float) {
        try {
            val cam = cameraRef ?: return
            val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            cam.cameraControl.setZoomRatio(level.coerceIn(1f, maxZoom))
        } catch (_: Exception) {}
    }

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

    fun isUsingFrontCamera(): Boolean =
        cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA

    fun getConfiguration(): Map<String, Any> = mapOf(
        "resolution" to "${currentTargetWidth}x$currentTargetHeight",
        "fps" to frameHub.fps,
        "effect" to effect,
        "night_vision" to nightVisionEnabled,
        "viewers" to frameHub.viewerCount(),
        "frames" to frameCounter
    )

    companion object {
        const val DEFAULT_PORT = 8080
    }
}
