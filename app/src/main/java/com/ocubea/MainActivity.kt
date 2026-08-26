package com.ocubea

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ocubea.service.StreamService
import java.net.NetworkInterface

/**
 * Thin UI over StreamService. The service owns the camera and server;
 * the activity only starts/stops it and shows status.
 *
 * Doubles as a kiosk launcher when set as the Home app: a small button in the
 * corner exits to the system launcher, and the screen can be turned off while
 * streaming continues in the foreground service.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMS_REQUEST = 1001

        // Common AOSP/MIUI/Pixel home intents, tried in order.
        val HOME_PACKAGES = arrayOf(
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.android.launcher3",
            "com.android.launcher",
            "com.sec.android.app.launcher"
        )
    }

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvIp: TextView
    private lateinit var btnHome: View
    private lateinit var btnScreenOff: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggleStream)
        tvStatus = findViewById(R.id.tvStatus)
        tvIp = findViewById(R.id.tvIp)
        btnHome = findViewById(R.id.btnRealLauncher)
        btnScreenOff = findViewById(R.id.btnScreenOff)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnToggle.setOnClickListener { if (StreamService.instance != null) stopStreaming() else checkPermsAndStart() }

        // Exit to the real launcher — works whether OcuBea is the home app or not
        btnHome.setOnClickListener { launchSystemLauncher() }

        // Screen off without locking streaming (service keeps camera alive).
        // lockNow() requires device-admin; without it we fall back gracefully.
        btnScreenOff.setOnClickListener {
            try {
                getSystemService(android.app.admin.DevicePolicyManager::class.java).lockNow()
            } catch (_: Exception) {
                Toast.makeText(this, "Screen-off needs admin rights — enabling screen timeout instead", Toast.LENGTH_LONG).show()
                window.attributes.screenBrightness = 0f
            }
            moveTaskToBack(true)
        }

        // Auto-resume if the service is already running
        if (StreamService.instance != null) renderRunning()
        else checkPermsAndStart()

        refreshIp()
    }

    private fun refreshIp() {
        val port = getSharedPreferences("ocubea", MODE_PRIVATE).getInt("port", 8080)
        val ip = localIpAddress()
        tvIp.text = if (ip != "0.0.0.0") "http://$ip:$port" else "Wi-Fi not connected"
    }

    override fun onResume() {
        super.onResume()
        refreshIp()
    }

    private fun localIpAddress(): String {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) return addr.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return "0.0.0.0"
    }

    /** Open the device's real home/launcher, preferring known packages over ourselves. */
    private fun launchSystemLauncher() {
        // 1. Known launcher packages, first one resolvable wins
        for (pkg in HOME_PACKAGES) {
            if (pkg == packageName) continue
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
                return
            }
        }
        // 2. Any activity handling HOME other than us
        val home = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        val candidates = packageManager.queryIntentActivities(home, 0)
            .filter { it.activityInfo.packageName != packageName }
        if (candidates.isNotEmpty()) {
            val i = Intent(home).apply {
                setClassName(candidates[0].activityInfo.packageName, candidates[0].activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(i)
            return
        }
        Toast.makeText(this, "No other launcher found — set one in system settings", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun requiredPerms() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
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
        val port = getSharedPreferences("ocubea", MODE_PRIVATE).getInt("port", 8080)
        val ip = localIpAddress()
        tvStatus.text = if (ip != "0.0.0.0") "Streaming — http://$ip:$port" else "Streaming"
        btnToggle.text = getString(R.string.stop_stream)
    }

    private fun renderStopped() {
        tvStatus.text = "Stopped"
        btnToggle.text = getString(R.string.start_stream)
    }
}
