package com.ocubea

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ocubea.camera.CameraManager
import com.ocubea.model.CameraConfig
import java.util.Locale

/**
 * Settings screen — allows the user to configure camera and streaming options
 * from inside the app (complement to the WebUI controls).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var cameraManager: CameraManager
    private lateinit var streamServer: com.ocubea.server.StreamServer

    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFPS: Spinner
    private lateinit var editTextPort: EditText
    private lateinit var checkboxNightVision: CheckBox
    private lateinit var checkboxMic: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: Button
    private lateinit var tvInfoPort: TextView
    private lateinit var tvInfoWebUI: TextView
    private lateinit var tvInfoStream: TextView
    private lateinit var tvInfoSnapshot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        cameraManager = CameraManager(this)
        val savedPort = getSharedPreferences("ocubea_prefs", MODE_PRIVATE)
            .getInt("server_port", 8080)
        streamServer = com.ocubea.server.StreamServer(this, savedPort)
        streamServer.setCameraManager(cameraManager)

        // Views
        spinnerResolution = findViewById(R.id.spinnerResolution)
        spinnerFPS = findViewById(R.id.spinnerFPS)
        editTextPort = findViewById(R.id.editTextPort)
        checkboxNightVision = findViewById(R.id.checkboxNightVision)
        checkboxMic = findViewById(R.id.checkboxMic)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartStop = findViewById(R.id.btnStartStop)
        tvInfoPort = findViewById(R.id.tvInfoPort)
        tvInfoWebUI = findViewById(R.id.tvInfoWebUI)
        tvInfoStream = findViewById(R.id.tvInfoStream)
        tvInfoSnapshot = findViewById(R.id.tvInfoSnapshot)

        // Resolution options
        val resolutions = listOf("320x240 (QVGA)", "640x480 (VGA)", "1280x720 (HD720)", "1920x1080 (FullHD)")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter

        // FPS options
        val fpsOptions = listOf("10 fps", "15 fps", "24 fps", "30 fps", "60 fps")
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsOptions)
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFPS.adapter = fpsAdapter

        // Default selections
        spinnerResolution.setSelection(2) // HD 720
        spinnerFPS.setSelection(3) // 30 fps

        // Listeners
        btnStartStop.setOnClickListener {
            if (isStreaming) {
                stopStream()
            } else {
                startCameraAndServer()
            }
        }

        editTextPort.setOnEditorActionListener { _, _, _ ->
            val port = editTextPort.text.toString().toIntOrNull() ?: 8080
            getSharedPreferences("ocubea_prefs", MODE_PRIVATE).edit()
                .putInt("server_port", port).apply()
            true
        }

        // Update info labels with current port
        updateInfoLabels(editTextPort.text.toString().toIntOrNull() ?: 8080)

        checkboxNightVision.setOnCheckedChangeListener { _, isChecked ->
            cameraManager.setNightVision(isChecked)
        }

        // Check permissions and start
        checkPermissionsAndStart()
    }

    private var isStreaming = false
    private var selectedResolution = CameraConfig.Resolution.HD720

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
                updateUI()
                showToast("Stream started on port ${com.ocubea.server.StreamServer.DEFAULT_PORT}")
            }, onError = { error ->
                showToast("Error: $error")
            })
        } catch (e: Exception) {
            showToast("Error: ${e.message}")
        }
    }

    private fun stopStream() {
        try {
            streamServer.stopServer()
            cameraManager.stopPreview()
            isStreaming = false
        } catch (_: Exception) {}
        updateUI()
        showToast("Stream stopped")
    }

    private fun updateUI() {
        val status = if (isStreaming) "✅ Streaming — Tap to Stop" else "⏸ Stopped — Tap to Start"
        tvStatus.text = status
        btnStartStop.text = if (isStreaming) "⏹ Stop" else "▶ Start"
        btnStartStop.setBackgroundColor(
            if (isStreaming) ContextCompat.getColor(this, R.color.red_500)
            else ContextCompat.getColor(this, R.color.green_500)
        )

        // Update resolution spinner
        val idx = when (selectedResolution) {
            CameraConfig.Resolution.QVGA -> 0
            CameraConfig.Resolution.VGA -> 1
            CameraConfig.Resolution.HD720 -> 2
            CameraConfig.Resolution.FullHD -> 3
        }
        spinnerResolution.setSelection(idx)

        // Update info labels with current port
        val port = editTextPort.text.toString().toIntOrNull() ?: 8080
        updateInfoLabels(port)
    }

    private fun updateInfoLabels(port: Int) {
        tvInfoPort.text = "Server Port: $port"
        tvInfoWebUI.text = "Web UI: http://<phone-ip>:$port"
        tvInfoStream.text = "Stream: http://<phone-ip>:$port/video"
        tvInfoSnapshot.text = "Snapshot: http://<phone-ip>:$port/shot.jpg"
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) stopStream()
    }

    companion object {
        const val CAMERA_PERMISSION_REQUEST_CODE = 1001
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
