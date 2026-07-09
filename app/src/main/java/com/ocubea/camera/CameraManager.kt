package com.ocubea.camera

import android.content.Context
import android.graphics.ImageFormat
import android.os.Handler
import android.os.Looper
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
                
                // Preview use case (for UI display) - simplified for now
                // In production, bind to a SurfaceView or TextureView
                
                // ImageCapture for frame-by-frame capture to encoder
                imageCapture = ImageCapture.Builder()
                    .setTargetResolution(Size(1280, 720)) // Default resolution
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val camera = cameraProvider.bindToLifecycle(
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
}
