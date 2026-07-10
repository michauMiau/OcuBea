package com.ocubea

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ocubea.camera.CameraManager
import com.ocubea.server.StreamServer

class MainActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var streamServer: StreamServer
    private var isStreaming = false
    
    companion object {
        const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraManager = CameraManager(this)
        streamServer = StreamServer(this)
        streamServer.setCameraManager(cameraManager)

        val btnToggle = findViewById<Button>(R.id.btnToggleStream)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnToggle.setOnClickListener {
            if (isStreaming) stopStream() else startCameraAndServer()
        }

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraAndServer()
        } else {
        ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) startCameraAndServer()
    }

    private fun startCameraAndServer() {
        try {
            cameraManager.startPreview(this, onStarted = {
                streamServer.start()
                isStreaming = true
                findViewById<TextView>(R.id.tvStatus).text = "Streaming"
                (findViewById<Button>(R.id.btnToggleStream) as Button).text = "Stop Stream"
            }, onError = { error ->
                println("Camera start error: $error")
            })
        } catch (e: Exception) {
            println("Error starting camera and server: ${e.message}")
        }
    }

    private fun stopStream() {
        try { 
            streamServer.stopServer() 
            cameraManager.stopPreview()
            isStreaming = false 
        } catch (_: Exception) {}
        findViewById<TextView>(R.id.tvStatus).text = "Stopped"
        (findViewById<Button>(R.id.btnToggleStream) as Button).text = "Start Stream"
    }

    override fun onDestroy() { super.onDestroy(); if (isStreaming) stopStream() }
}
