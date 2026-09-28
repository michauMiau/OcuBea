package com.ocubea

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ocubea.model.CameraConfig
import com.ocubea.model.OcuBeaConfig
import com.ocubea.model.QualityScale
import com.ocubea.service.StreamService
import java.net.NetworkInterface

/**
 * Full configuration screen.
 *
 * This screen only persists settings into [OcuBeaConfig]; the camera itself is
 * owned by [StreamService]. Changes that only affect the encoder (quality, fps,
 * effects) are pushed to the running service immediately — port, token and
 * autostart need a restart, which the screen says plainly.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var config: OcuBeaConfig

    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerFPS: Spinner
    private lateinit var editTextPort: EditText
    private lateinit var editTextToken: EditText
    private lateinit var editTextDeviceName: EditText

    private lateinit var seekQuality: SeekBar
    private lateinit var labelQuality: TextView
    private lateinit var seekSensitivity: SeekBar
    private lateinit var labelSensitivity: TextView
    private lateinit var seekPreRecord: SeekBar
    private lateinit var seekMaxClip: SeekBar

    private lateinit var checkboxNightVision: CheckBox
    private lateinit var checkboxMic: CheckBox
    private lateinit var checkboxSecurity: CheckBox
    private lateinit var checkboxAutostart: CheckBox
    private lateinit var checkboxPowerSaving: CheckBox

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnRestartCamera: Button
    private lateinit var btnShowLog: Button
    private lateinit var tvInfoWebUI: TextView
    private lateinit var tvInfoStream: TextView
    private lateinit var tvInfoSnapshot: TextView
    private lateinit var tvInfoAudio: TextView
    private lateinit var tvInfoOnvif: TextView

    private val RESOLUTIONS = listOf("320×240 (QVGA)", "640×480 (VGA)", "1280×720 (HD)", "1920×1080 (FullHD)")
    private val FPS_VALUES = listOf(5, 10, 15, 20, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        config = OcuBeaConfig(this)

        bindViews()
        loadValues()
        wireListeners()
        renderServiceState()
        updateInfoLabels()
    }

    private fun bindViews() {
        spinnerResolution = findViewById(R.id.spinnerResolution)
        spinnerFPS = findViewById(R.id.spinnerFPS)
        editTextPort = findViewById(R.id.editTextPort)
        editTextToken = findViewById(R.id.editTextToken)
        editTextDeviceName = findViewById(R.id.editTextDeviceName)

        seekQuality = findViewById(R.id.seekQuality)
        labelQuality = findViewById(R.id.labelQuality)
        seekSensitivity = findViewById(R.id.seekSensitivity)
        labelSensitivity = findViewById(R.id.labelSensitivity)
        seekPreRecord = findViewById(R.id.seekPreRecord)
        seekMaxClip = findViewById(R.id.seekMaxClip)

        checkboxNightVision = findViewById(R.id.checkboxNightVision)
        checkboxMic = findViewById(R.id.checkboxMic)
        checkboxSecurity = findViewById(R.id.checkboxSecurity)
        checkboxAutostart = findViewById(R.id.checkboxAutostart)
        checkboxPowerSaving = findViewById(R.id.checkboxPowerSaving)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnStartStop = findViewById(R.id.btnStartStop)
        btnRestartCamera = findViewById(R.id.btnRestartCamera)
        btnShowLog = findViewById(R.id.btnShowLog)
        tvInfoWebUI = findViewById(R.id.tvInfoWebUI)
        tvInfoStream = findViewById(R.id.tvInfoStream)
        tvInfoSnapshot = findViewById(R.id.tvInfoSnapshot)
        tvInfoAudio = findViewById(R.id.tvInfoAudio)
        tvInfoOnvif = findViewById(R.id.tvInfoOnvif)
    }

    private fun loadValues() {
        val resAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, RESOLUTIONS)
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(config.resolution.ordinal)

        val fpsLabels = FPS_VALUES.map { "$it fps" }
        val fpsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fpsLabels)
        fpsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFPS.adapter = fpsAdapter
        spinnerFPS.setSelection(FPS_VALUES.indexOf(config.frameRate).coerceAtLeast(0))

        editTextPort.setText(config.port.toString())
        editTextToken.setText(config.accessToken)
        editTextDeviceName.setText(config.deviceName)

        // SeekBar works 0..max, config works in real units — map between them.
        // The slider drives both encoders: JPEG quality for /video and
        // /shot, and HLS/clip bitrate through QualityScale.
        seekQuality.max = QualityScale.MAX_PROGRESS
        seekQuality.progress = (config.jpegQuality - QualityScale.MIN_QUALITY)
            .coerceIn(0, seekQuality.max)
        labelQuality.text = qualityLabel(seekQuality.progress)

        seekSensitivity.max = 100
        seekSensitivity.progress = config.motionSensitivity
        labelSensitivity.text = getString(R.string.sensitivity_label, config.motionSensitivity)

        seekPreRecord.max = 10
        seekPreRecord.progress = config.preRecordSeconds
        seekMaxClip.max = 360
        seekMaxClip.progress = (config.maxClipSeconds / 10).coerceIn(1, 360)

        checkboxNightVision.isChecked = config.nightVision
        checkboxMic.isChecked = config.audioEnabled
        checkboxSecurity.isChecked = config.securityEnabled
        checkboxAutostart.isChecked = config.autostart
        checkboxPowerSaving.isChecked = config.powerSaving
    }

    private fun wireListeners() {
        // Spinners fire on initial bind too, so only persist (never restart) here
        spinnerResolution.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                config.resolution = CameraConfig.Resolution.values()[pos]
                pushLive("quality", config.resolution.height.toString())
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        spinnerFPS.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                config.frameRate = FPS_VALUES[pos]
                pushLive("fps", FPS_VALUES[pos].toString())
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        seekQuality.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                labelQuality.text = qualityLabel(progress)
                if (fromUser) config.jpegQuality = QualityScale.jpegQualityFor(progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                val p = sb.progress
                config.jpegQuality = QualityScale.jpegQualityFor(p)
                config.videoBitrateKbps = QualityScale.bitrateKbpsFor(p)
                // Both go out together: a quality setting that silently left
                // the recorded bitrate behind would look like the slider only
                // half worked.
                pushLive("jpeg_quality", config.jpegQuality.toString())
                pushLive("video_bitrate_kbps", config.videoBitrateKbps.toString())
            }
        })

        seekSensitivity.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                labelSensitivity.text = getString(R.string.sensitivity_label, progress)
                if (fromUser) config.motionSensitivity = progress
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                pushLive("motion_sensitivity", config.motionSensitivity.toString())
            }
        })

        seekPreRecord.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) config.preRecordSeconds = progress
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                pushLive("pre_record_seconds", config.preRecordSeconds.toString())
            }
        })

        seekMaxClip.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) config.maxClipSeconds = progress * 10
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                pushLive("max_clip_seconds", config.maxClipSeconds.toString())
            }
        })

        checkboxNightVision.setOnCheckedChangeListener { _, c ->
            config.nightVision = c
            pushLive("night_vision", if (c) "on" else "off")
        }
        checkboxMic.setOnCheckedChangeListener { _, c ->
            config.audioEnabled = c
            pushLive("audio_enabled", if (c) "on" else "off")
        }
        checkboxSecurity.setOnCheckedChangeListener { _, c ->
            config.securityEnabled = c
            pushLive("motion_detection", if (c) "on" else "off")
        }
        checkboxAutostart.setOnCheckedChangeListener { _, c -> config.autostart = c }
        checkboxPowerSaving.setOnCheckedChangeListener { _, c -> config.powerSaving = c }

        editTextPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val port = editTextPort.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8080
                config.port = port
                editTextPort.setText(port.toString())
                updateInfoLabels()
                if (StreamService.instance != null) {
                    toast(getString(R.string.port_saved))
                }
            }
        }

        editTextToken.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                config.accessToken = editTextToken.text.toString()
                if (config.accessToken.isEmpty()) editTextToken.setText("")
                updateInfoLabels()
                if (StreamService.instance != null) {
                    toast(getString(R.string.token_saved))
                }
            }
        }

        editTextDeviceName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                config.deviceName = editTextDeviceName.text.toString()
                editTextDeviceName.setText(config.deviceName)
            }
        }

        btnStartStop.setOnClickListener {
            if (StreamService.instance != null) {
                startService(
                    Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP)
                )
                renderStopped()
            } else {
                val intent = Intent(this, StreamService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                renderRunning()
            }
        }

        btnRestartCamera.setOnClickListener {
            val svc = StreamService.instance
            if (svc == null) {
                toast(getString(R.string.stream_not_running))
            } else {
                svc.restartCameraNow()
                toast(getString(R.string.camera_restarting))
            }
        }

        btnShowLog.setOnClickListener {
            if (tvLog.visibility == android.view.View.VISIBLE) {
                tvLog.visibility = android.view.View.GONE
            } else {
                val log = CrashLogger.lastLog(this, 6000)
                tvLog.text = log?.trim()?.ifEmpty { "Log is empty — no crashes or errors recorded." }
                    ?: "No log file yet."
                tvLog.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * Shows what the slider actually changes.
     *
     * One control drives two encoders, and a label reading only "JPEG quality"
     * would leave the bitrate looking like a separate, unrelated setting.
     * Both numbers are shown because they move independently: JPEG quality is
     * a per-frame setting for /video and /shot, bitrate is for the HLS and clip
     * encoders.
     */
    private fun qualityLabel(progress: Int): String =
        getString(
            R.string.quality_label,
            QualityScale.jpegQualityFor(progress),
            QualityScale.bitrateKbpsFor(progress) / 1000,
        )

    /** Push a setting to the running service; harmless when streaming is off. */
    private fun pushLive(name: String, value: String) {
        val svc = StreamService.instance ?: return
        Thread {
            val ip = localIpAddress()
            if (ip == "0.0.0.0") return@Thread
            try {
                val conn = java.net.URL(
                    "http://$ip:${config.port}/settings/$name?set=$value"
                ).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 2000
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun renderServiceState() {
        if (StreamService.instance != null) renderRunning() else renderStopped()
    }

    private fun renderRunning() {
        tvStatus.text = getString(R.string.streaming_started_short)
        btnStartStop.text = getString(R.string.stop_stream)
    }

    private fun renderStopped() {
        tvStatus.text = getString(R.string.status_stopped)
        btnStartStop.text = getString(R.string.start_stream)
    }

    private fun updateInfoLabels() {
        val ip = localIpAddress()
        val base = if (ip == "0.0.0.0") getString(R.string.no_wifi) else ip
        val port = config.port
        val q = if (config.accessToken.isEmpty()) "" else "?token=${config.accessToken}"
        tvInfoWebUI.text = getString(R.string.addr_web_ui_value, base, port, q)
        tvInfoStream.text = getString(R.string.addr_stream_value, base, port, q)
        tvInfoSnapshot.text = getString(R.string.addr_snapshot_value, base, port, q)
        tvInfoAudio.text = getString(R.string.addr_audio_value, base, port, q)
        tvInfoOnvif.text = getString(R.string.onvif_value, base, port)
    }

    private fun localIpAddress(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Boilerplate-free SeekBar listener. */
    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
    }
}
