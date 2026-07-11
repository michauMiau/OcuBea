package com.ocubea.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.FocusMeteringAction
import androidx.core.content.ContextCompat
import com.ocubea.model.CameraConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Camera manager using CameraX for preview, capture and MJPEG streaming.
 */
class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var currentTargetWidth = 1280
    private var currentTargetHeight = 720

    // Streaming state — volatile so worker threads see updates from main thread
    @Volatile private var isStreaming = false
    private val streamDirectory = File(context.cacheDir, "mjpeg_stream")
    private var latestFrameFile: File? = null
    private var frameCounter = 0L

    /** Callback when a frame is ready */
    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null

    init {
        streamDirectory.mkdirs()
    }

    // ─── Public API ──────────────────────────────────────────────

    /** Start camera with streaming enabled */
    fun startPreview(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(currentTargetWidth, currentTargetHeight))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner ?: return@addListener,
                    cameraSelector,
                    imageCapture
                )

                onStarted()
            } catch (e: Exception) {
                onError("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Start MJPEG streaming — captures frames continuously */
    fun startStreaming(onError: (String) -> Unit = {}) {
        if (isStreaming) return
        isStreaming = true
        try {
            imageCapture?.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        try {
                            val frameFile = saveFrameAsJpeg(imageProxy)
                            latestFrameFile = frameFile
                            frameCounter++

                            // Re-trigger next frame only if still streaming and camera is bound
                            if (isStreaming && imageCapture != null) {
                                imageCapture?.takePicture(cameraExecutor, this)
                            }
                        } finally {
                            // ALWAYS close ImageProxy — CameraX requires it to release resources
                            imageProxy.close()
                        }
                    }

                    override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                        println("Frame capture error: ${exception.message}")
                    }
                }
            )
        } catch (e: Exception) {
            isStreaming = false
            onError("Failed to start streaming: ${e.message}")
        }
    }

    /** Stop MJPEG streaming */
    fun stopStreaming() {
        isStreaming = false
        println("MJPEG streaming stopped")
    }

    /** Get the latest captured frame as JPEG bytes (for snapshot) */
    fun getLatestFrame(): ByteArray? {
        val file = latestFrameFile ?: return null
        if (!file.exists()) return null
        return file.readBytes()
    }

    // ─── Camera switching & quality control ──────────────────────

    /** Switch between front and back camera — stops streaming, rebinds with new selector */
    fun setFrontFacingCamera(enabled: Boolean) {
        val wasStreaming = isStreaming
        stopStreaming()

        try {
            val newSelector = if (enabled) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            cameraSelector = newSelector
        } catch (e: Exception) {
            println("Error switching camera selector: ${e.message}")
            return
        }

        // Rebind with the new camera selector and restart streaming if needed
        rebindWithCallback(null, object : RebindCallback {
            override fun onReady(provider: ProcessCameraProvider) {
                if (wasStreaming) startStreaming()
            }
        })
    }

    /** Set target resolution/quality — stops streaming, rebinding with new config */
    fun setQuality(resolution: CameraConfig.Resolution) {
        currentTargetWidth = resolution.width
        currentTargetHeight = resolution.height

        val wasStreaming = isStreaming
        stopStreaming()

        rebindWithCallback(null, object : RebindCallback {
            override fun onReady(provider: ProcessCameraProvider) {
                if (wasStreaming) startStreaming()
            }
        })
    }

    /** Set zoom level using CameraX ZoomState */
    fun setZoom(zoomLevel: Float) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            val provider = future.get()
            if (provider.hasActiveCamera(cameraSelector)) {
                val camera = provider.getCamera(cameraSelector)
                val zoomState = camera?.cameraInfo?.zoomState?.value
                if (zoomState != null) {
                    // CameraX zoom is 1.0x = 1:1, max is device-dependent
                    val clampedZoom = zoomLevel.coerceIn(1f, zoomState.maxZoomRatio)
                    camera.cameraControl.setZoomRatio(clampedZoom)
                }
            }
        } catch (e: Exception) {
            println("Error setting zoom: ${e.message}")
        }
    }

    /** Set focus using CameraX FocusMeteringAction */
    fun setFocus(focusValue: Float) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            val provider = future.get()
            if (provider.hasActiveCamera(cameraSelector)) {
                val camera = provider.getCamera(cameraSelector)
                // Use MeteringPointFactory for distance-based focus
                val meteringPoint = camera?.cameraInfo?.meteringPointFactory?.createPoint(0.5f, 0.5f) ?: return
                val action = FocusMeteringAction.Builder(meteringPoint)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
        } catch (e: Exception) {
            println("Error setting focus: ${e.message}")
        }
    }

    /** Get current camera configuration */
    fun getCameraConfiguration(): Map<String, Any> {
        return mapOf(
            "resolution" to "${currentTargetWidth}x$currentTargetHeight",
            "zoomLevel" to 1.0,
            "focusDistance" to 0f
        )
    }

    /** Check if camera is active */
    fun isCameraActive(): Boolean = imageCapture != null

    /** Get total number of captured frames */
    fun getFrameCount(): Long = frameCounter

    // ─── Internal helpers ────────────────────────────────────────

    /** Stop camera and release resources, clean up temp files */
    fun stopPreview() {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.get()?.unbindAll()
        } catch (_: Exception) {}
        stopStreaming()
        cleanupOldFrames()
    }

    /** Rebind camera with current selector and lifecycle, invoking callback when ready */
    private fun rebindWithCallback(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
        callback: RebindCallback? = null
    ) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    provider.unbindAll()

                    imageCapture = ImageCapture.Builder()
                        .setTargetResolution(android.util.Size(currentTargetWidth, currentTargetHeight))
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    provider.bindToLifecycle(
                        lifecycleOwner ?: return@addListener,
                        cameraSelector,
                        imageCapture
                    )

                    callback?.onReady(provider)
                } catch (e: Exception) {
                    println("Error in rebind callback: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            println("Error rebinding camera: ${e.message}")
        }
    }

    /** Convert ImageProxy to NV21 byte array for encoder */
    private fun imageToNV21(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != android.graphics.ImageFormat.YUV_420_888 &&
            imageProxy.format != android.graphics.ImageFormat.NV21
        ) return null

        val planes = imageProxy.planes
        val width = imageProxy.width
        val height = imageProxy.height

        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)

        return try {
            val outputBuffer = java.nio.ByteBuffer.allocate(ySize + 2 * uvSize)

            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val yData = ByteArray(yBuffer.remaining())
            yBuffer.get(yData)
            outputBuffer.put(yData)

            if (planes.size >= 2 && uvSize > 0) {
                val uvPlane = planes[1]
                val uvBuffer = uvPlane.buffer

                if (uvBuffer.remaining() > 0) {
                    val uvData = ByteArray(uvBuffer.remaining())
                    uvBuffer.get(uvData)

                    val interleaved = ByteArray(uvSize * 2)
                    var j = 0
                    for (i in 0 until uvSize) {
                        interleaved[j++] = if ((i % 2) == 0) uvData[i] else uvData[i - 1]
                        interleaved[j++] = if ((i % 2) == 0) uvData[i - 1] else uvData[i]
                    }

                    outputBuffer.put(interleaved)
                }
            }

            outputBuffer.flip()
            val result = ByteArray(outputBuffer.remaining())
            outputBuffer.get(result)
            result
        } catch (e: Exception) {
            null
        }
    }

    /** Save ImageProxy as JPEG file for streaming — bitmap.recycle() always in finally */
    private fun saveFrameAsJpeg(imageProxy: ImageProxy): File {
        val fileName = "frame_%06d.jpg".format(frameCounter)
        val file = File(streamDirectory, fileName)

        val bitmap = imageProxy.toBitmap()
        try {
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
        } catch (e: Exception) {
            println("Error saving frame as JPEG: ${e.message}")
            // Fallback: return empty file
            file.writeBytes(ByteArray(0))
        } finally {
            bitmap.recycle()
        }

        return file
    }

    /** Limit streaming directory to prevent disk exhaustion */
    private fun cleanupOldFrames() {
        val files = streamDirectory.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= 10) return
        // Delete oldest frames, keep newest 10
        files.take(files.size - 10).forEach { it.delete() }
    }

    /** Callback interface for rebind operations */
    private interface RebindCallback {
        fun onReady(provider: ProcessCameraProvider)
    }
}
