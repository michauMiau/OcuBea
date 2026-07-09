package com.ocubea

import android.app.Application
import com.ocubea.camera.CameraManager
import com.ocubea.stream.VideoEncoder
import com.ocubea.server.StreamServer

/**
 * Global application class for OcuBea.
 * Manages shared components: Camera, Encoder, Stream Server.
 */
class OcuBeaApplication : Application() {
    
    lateinit var cameraManager: CameraManager
        private set
    
    lateinit var videoEncoder: VideoEncoder
        private set
    
    lateinit var streamServer: StreamServer
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize components
        cameraManager = CameraManager(this)
        videoEncoder = VideoEncoder()
        streamServer = StreamServer()
    }
}
