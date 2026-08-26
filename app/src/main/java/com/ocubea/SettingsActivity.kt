package com.ocubea

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ocubea.service.StreamService

/**
 * Settings screen — port, device name, autostart and security-camera defaults.
 * Actual camera ownership stays in StreamService; settings only persist prefs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerResolution: Spinner
    private lateinit var editTextPort: EditText
    private lateinit var checkboxNightVision: CheckBox
    private lateinit var tvStatus: TextView
    private lateinit var btnStartStop: Button
    private lateinit var tvInfoPort: TextView
    private lateinit var tvInfoWebUI: TextView
    private lateinit var tvInfoStream: TextView
    private lateinit var tvInfoSnapshot: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("ocubea", MODE_PRIVATE)

        spinnerResolution = findViewById(R.id.spinnerResolution)
        editTextPort = findViewById(R.id.editTextPort)
        checkboxNightVision = findViewById(R.id.checkboxNightVision)
        tvStatus = findViewById(R.id.tvStatus)
        btnStartStop = findViewById(R.id.btnStartStop)
        tvInfoPort = findViewById(R.id.tvInfoPort)
        tvInfoWebUI = findViewById(R.id.tvInfoWebUI)
        tvInfoStream = findViewById(R.id.tvInfoStream)
        tvInfoSnapshot = findViewById(R.id.tvInfoSnapshot)
        val checkboxAutostartView = findViewById<CheckBox>(R.id.checkboxMic) // reuse mic row as autostart toggle

        // Resolution options
        val resolutions = listOf("320x240 (QVGA)", "640x480 (VGA)", "1280x720 (HD720)", "1920x1080 (FullHD)")
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(prefs.getInt("resolution_idx", 2))
        spinnerResolution.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                prefs.edit().putInt("resolution_idx", pos).apply()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })

        editTextPort.setText(prefs.getInt("port", 8080).toString())
        editTextPort.setOnEditorActionListener { _, _, _ ->
            val port = editTextPort.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
            prefs.edit().putInt("port", port).apply()
            updateInfoLabels(port)
            true
        }

        checkboxNightVision.isChecked = prefs.getBoolean("night_vision", false)
        checkboxNightVision.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("night_vision", checked).apply()
        }

        checkboxAutostartView.text = "Auto-start after boot"
        checkboxAutostartView.isChecked = prefs.getBoolean("autostart", true)
        checkboxAutostartView.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("autostart", c).apply() }

        btnStartStop.setOnClickListener {
            if (StreamService.instance != null) {
                startService(Intent(this, StreamService::class.java).setAction("com.ocubea.STOP_STREAM"))
                renderStopped()
            } else {
                startForegroundStream()
                renderRunning()
            }
        }

        updateInfoLabels(prefs.getInt("port", 8080))
        if (StreamService.instance != null) renderRunning() else renderStopped()
    }

    private fun startForegroundStream() {
        val intent = Intent(this, StreamService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun renderRunning() {
        tvStatus.text = "✅ Streaming — service active"
        btnStartStop.text = "⏹ Stop"
    }

    private fun renderStopped() {
        tvStatus.text = "⏸ Stopped"
        btnStartStop.text = "▶ Start"
    }

    private fun updateInfoLabels(port: Int) {
        tvInfoPort.text = "Server Port: $port"
        tvInfoWebUI.text = "Web UI: http://<phone-ip>:$port"
        tvInfoStream.text = "Stream: http://<phone-ip>:$port/video"
        tvInfoSnapshot.text = "Snapshot: http://<phone-ip>:$port/shot.jpg"
    }
}
