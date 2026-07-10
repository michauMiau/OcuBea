package com.ocubea.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraManager
import android.os.Environment
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
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

    // Streaming state
    private var isStreaming = false
    private val streamDirectory = File(context.cacheDir, "mjpeg_stream")
    private var latestFrameFile: File? = null
    private var frameCounter = 0L

    /** Callback when a frame is ready */
    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null

    init {
        streamDirectory.mkdirs()
    }

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
                        val frameFile = saveFrameAsJpeg(imageProxy)
                        latestFrameFile = frameFile
                        frameCounter++

                        // Re-trigger next frame
                        if (isStreaming) {
                            imageCapture?.takePicture(cameraExecutor, this)
                        } else {
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

    /** Stop camera and release resources */
    fun stopPreview() {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.get()?.unbindAll()
        } catch (_: Exception) {}
        stopStreaming()
        // Clean up temp files
        streamDirectory.listFiles()?.forEach { it.delete() }
    }

    /** Set zoom level */
    fun setZoom(zoomLevel: Float) {
        println("setZoom($zoomLevel) - stub")
    }

    /** Set focus distance */
    fun setFocus(focusValue: Float) {
        println("setFocus($focusValue) - stub")
    }

    /** Switch between front and back camera */
    fun setFrontFacingCamera(enabled: Boolean) {
        try {
            val newSelector = if (enabled) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            cameraSelector = newSelector
        } catch (e: Exception) {
            println("Error switching camera: ${e.message}")
        }
    }

    /** Rebind camera with current selector and lifecycle owner */
    fun rebind(lifecycleOwner: androidx.lifecycle.LifecycleOwner?) {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.get()?.let { provider ->
                provider.unbindAll()

                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(currentTargetWidth, currentTargetHeight))
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                provider.bindToLifecycle(
                    lifecycleOwner ?: return@let,
                    cameraSelector,
                    imageCapture
                )
            }
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

    /** Save ImageProxy as JPEG file for streaming */
    private fun saveFrameAsJpeg(imageProxy: ImageProxy): File {
        val fileName = "frame_%06d.jpg".format(frameCounter)
        val file = File(streamDirectory, fileName)

        // Use Android's built-in JPEG encoding via MediaStore or direct conversion
        try {
            val bitmap = imageProxy.toBitmap()
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            println("Error saving frame as JPEG: ${e.message}")
            // Fallback: return empty file
            file.writeBytes(ByteArray(0))
        }

        imageProxy.close()
        return file
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
    fun isCameraActive(): Boolean {
        return imageCapture != null
    }
}
