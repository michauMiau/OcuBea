package com.ocubea

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ocubea.service.StreamService

/**
 * Thin UI over StreamService. The service owns the camera and server;
 * the activity only starts/stops it and shows status.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMS_REQUEST = 1001
    }

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggleStream)
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnToggle.setOnClickListener { if (StreamService.instance != null) stopStreaming() else checkPermsAndStart() }

        // Auto-resume if the service is already running
        if (StreamService.instance != null) renderRunning()
        else checkPermsAndStart()
    }

    private fun requiredPerms() = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.RECORD_AUDIO)
        else add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun checkPermsAndStart() {
        val missing = requiredPerms().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startStreaming()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMS_REQUEST)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == PERMS_REQUEST && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startStreaming()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startStreaming() {
        val intent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        renderRunning()
    }

    private fun stopStreaming() {
        startService(Intent(this, StreamService::class.java).setAction("com.ocubea.STOP_STREAM"))
        renderStopped()
    }

    private fun renderRunning() {
        tvStatus.text = "Streaming — open http://<phone-ip>:8080"
        btnToggle.text = getString(R.string.stop_stream)
    }

    private fun renderStopped() {
        tvStatus.text = "Stopped"
        btnToggle.text = getString(R.string.start_stream)
    }
}
