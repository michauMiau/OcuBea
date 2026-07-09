package com.ocubea.camera

import android.content.Context
import androidx.camera.core.*
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.Executors

/**
 * Manages camera access using CameraX.
 * Handles preview, video recording, and frame capture for encoding.
 */
class CameraManager(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    
    @OptIn(ExperimentalCamera2Interop::class)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    
    // Callback when a frame is ready for encoding
    var onFrameCaptured: ((ByteArray, Long) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Start preview and/or video capture */
    fun startPreview(
        lifecycleOwner: LifecycleOwner?,
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // ImageCapture for frame-by-frame capture to encoder
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1280, 720)) // Default resolution
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
        }, Handler(Looper.getMainLooper()))
    }
    
    /** Capture a single frame for encoding */
    fun captureFrame(outputFile: java.io.File? = null) {
        val imageCapture = imageCapture ?: return
        
        if (outputFile != null) {
            // Save to file (JPEG)
            val outputFileOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            imageCapture.takePicture(
                outputFileOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {}
                    override fun onError(exception: ImageCaptureException) {}
                }
            )
        } else {
            // Get raw bytes for encoder (NV21 format)
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        val buffer = imageProxy.planes[0].buffer.toByteArray()
                        onFrameCaptured?.invoke(buffer, System.currentTimeMillis())
                        imageProxy.close()
                    }
                    
                    override fun onError(exception: ImageCaptureException) {}
                }
            )
        }
    }
    
    /** Release camera resources */
    fun stop() {
        // Unbind all use cases
    }

    // ==================== CAMERA CONTROLS (IP Webcam API) ====================

    /** Set focus mode — 1.0f = locked (single shot), 0f = continuous auto */
    @OptIn(ExperimentalCamera2Interop::class)
    fun setFocus(focusValue: Float) {
        // Focus control via CameraX requires access to the underlying CameraDevice.
        // This is a simplified version — actual implementation would use
        // CaptureSession with CONTROL_AF_MODE setting.
        val afMode = if (focusValue > 0.5f) {
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO
        } else {
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        // TODO: Implement proper focus control when CameraDevice access is available
    }

    /** Apply night vision toggle */
    fun applyNightVision(enabled: Boolean) {
        // Night vision is typically a hardware feature controlled by the camera device.
        // This is a stub — real implementation requires CameraDevice access and
        // specific device capabilities (e.g., infrared LED toggle).
    }

    /** Set front-facing camera mode */
    fun setFrontFacingCamera(ffcEnabled: Boolean) {
        try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            
            // Unbind ALL current use cases from the currently bound Camera
            imageCapture?.let { capture -> 
                provider.unbindAll()
            }            
            if (ffcEnabled) {
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .build()
                provider.bindToLifecycle(null as LifecycleOwner?, selector, imageCapture)
            } else {
                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .build()
                provider.bindToLifecycle(null as LifecycleOwner?, selector, imageCapture)
            }
        } catch (_: Exception) {}
    }

    /** Set digital zoom — value between 1.0 and 5.0 */
    fun setZoom(zoomValue: Float) {
        try {
            val camera = imageCapture?.camera ?: return
            camera.cameraControl.setLinearZoom(
                (zoomValue - 1f).coerceIn(0f, 4f) / 4f // Map [1.0, 5.0] to [0.0, 1.0]
            )
        } catch (_: Exception) {}
    }
}
