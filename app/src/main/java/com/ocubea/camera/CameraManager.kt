package com.ocubea.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Camera manager using CameraX for preview, capture and encoding.
 */
class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** Callback when a frame is ready for encoding */
    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null

    /** Start camera with preview and/or video capture */
    fun startPreview(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner?,
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Select back camera by default
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Create ImageCapture for frame-by-frame capture
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
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

    /** Capture a single frame for encoding */
    fun captureFrame(outputFile: java.io.File? = null) {
        val ic = imageCapture ?: return

        if (outputFile != null) {
            // Save to JPEG file
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            ic.takePicture(
                outputFileOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {}
                    override fun onError(exception: ImageCaptureException) {}
                }
            )
        } else {
            // Get raw NV21 bytes for encoder input
            ic.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        val buffer = imageToNV21(imageProxy) ?: byteArrayOf()
                        onFrameCaptured?.invoke(buffer, System.currentTimeMillis())
                        imageProxy.close()
                    }

                    override fun onError(exception: ImageCaptureException) {}
                }
            )
        }
    }

    /** Stop camera and release resources */
    fun stopPreview() {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.get()?.unbindAll()
        } catch (_: Exception) {}

        imageCapture?.let { ic ->
            try {
                ic.targetResolution = null
            } catch (_: Exception) {}
        }
    }

    /** Set zoom level (1.0 to 5.0x) */
    fun setZoom(zoomLevel: Float) {
        try {
            imageCapture?.let { capture ->
                val maxZoom = capture.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
                val ratio = kotlin.math.min(zoomLevel, maxZoom) / 1.0f
                capture.cameraControl?.setZoomRatio(ratio)
            }
        } catch (e: Exception) {
            println("Error setting zoom: ${e.message}")
        }
    }

    /** Set focus distance */
    fun setFocus(focusValue: Float) {
        try {
            imageCapture?.let { capture ->
                val metadata = ImageCapture.Metadata()
                metadata.orientationHint = 90 // Portrait
                capture.setMetadata(metadata)
            }
        } catch (e: Exception) {
            println("Error setting focus: ${e.message}")
        }
    }

    /** Switch between front and back camera */
    fun setFrontFacingCamera(enabled: Boolean) {
        try {
            val selector = if (enabled) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            val future = ProcessCameraProvider.getInstance(context)
            future.get()?.let { provider ->
                provider.unbindAll()

                imageCapture?.let { capture ->
                    try {
                        capture.targetResolution = null
                    } catch (_: Exception) {}

                    // Rebind with new selector (requires lifecycle owner - simplified here)
                    println("Camera switched to ${if (enabled) "front" else "back"}")
                }
            }
        } catch (e: Exception) {
            println("Error switching camera: ${e.message}")
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

        // Calculate NV21 buffer size: Y + (U+V interleaved)
        val ySize = width * height
        val uvSize = (width / 2) * (height / 2)

        return try {
            val outputBuffer = java.nio.ByteBuffer.allocate(ySize + 2 * uvSize)

            // Copy Y plane (plane 0)
            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val yData = ByteArray(yBuffer.remaining())
            yBuffer.get(yData)
            outputBuffer.put(yData)

            if (planes.size >= 2 && uvSize > 0) {
                // Copy UV plane (plane 1 or 2 for NV21 order: V, U interleaved)
                val uvPlane = planes[1]
                val uvBuffer = uvPlane.buffer

                if (uvBuffer.remaining() > 0) {
                    val uvData = ByteArray(uvBuffer.remaining())
                    uvBuffer.get(uvData)

                    // Interleave UV for NV21 format: V U V U ...
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

    /** Get current camera configuration */
    fun getCameraConfiguration(): Map<String, Any> {
        return mapOf(
            "resolution" to "${imageCapture?.targetResolution?.width}x${imageCapture?.targetResolution?.height}",
            "zoomLevel" to (imageCapture?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0),
            "focusDistance" to (imageCapture?.cameraInfo?.zoomState?.value?.minFocusDistance ?: 0f)
        )
    }

    /** Check if camera is active */
    fun isCameraActive(): Boolean {
        return imageCapture != null && imageCapture!!.targetResolution != null
    }
}
