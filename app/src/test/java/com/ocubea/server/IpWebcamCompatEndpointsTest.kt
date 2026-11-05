package com.ocubea.server

import android.content.Context
import android.content.SharedPreferences
import com.ocubea.camera.CameraManager
import com.ocubea.model.OcuBeaConfig
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import com.ocubea.stream.FrameHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.HttpURLConnection
import java.net.URL

class IpWebcamCompatEndpointsTest {

    private lateinit var context: Context
    private lateinit var camera: CameraManager
    private lateinit var config: OcuBeaConfig
    private var server: StreamServer? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        camera = mock(CameraManager::class.java)
        `when`(camera.frameHub).thenReturn(FrameHub())
        val recorder = mock(MotionRecorder::class.java)
        val discovery = mock(OnvifDiscovery::class.java)
        config = OcuBeaConfig(prefs())
        val s = StreamServer(context, camera, MotionDetector(), recorder, discovery, config, 0)
        s.start(2000, true)
        port = s.listeningPort
        server = s
    }

    @After
    fun tearDown() {
        runCatching { server?.stopServer() }
        server = null
    }

    private fun prefs(): SharedPreferences {
        val store = HashMap<String, Any?>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?): SharedPreferences.Editor = also { store[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor = also { store[k] = v }
            override fun putInt(k: String, v: Int): SharedPreferences.Editor = also { store[k] = v }
            override fun putLong(k: String, v: Long): SharedPreferences.Editor = also { store[k] = v }
            override fun putFloat(k: String, v: Float): SharedPreferences.Editor = also { store[k] = v }
            override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor = also { store[k] = v }
            override fun remove(k: String): SharedPreferences.Editor = also { store.remove(k) }
            override fun clear(): SharedPreferences.Editor = also { store.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = HashMap(store)
            override fun getString(k: String?, d: String?): String? = store[k] as? String ?: d
            override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? = d
            override fun getInt(k: String?, d: Int): Int = store[k] as? Int ?: d
            override fun getLong(k: String?, d: Long): Long = store[k] as? Long ?: d
            override fun getFloat(k: String?, d: Float): Float = store[k] as? Float ?: d
            override fun getBoolean(k: String?, d: Boolean): Boolean = store[k] as? Boolean ?: d
            override fun contains(k: String?): Boolean = store.containsKey(k)
            override fun edit(): SharedPreferences.Editor = editor
            override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        }
    }

    private fun get(path: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Connection", "close")
        return try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            code to (stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "")
        } finally { conn.disconnect() }
    }

    @Test fun toggleTorch() { assertEquals(200, get("/toggletorch").first) }
    @Test fun crossDomain() { assertTrue(get("/crossdomain.xml").second.contains("cross-domain-policy")) }
    @Test fun checkPublic() { assertEquals(200, get("/check_public").first) }
    @Test fun version() { assertTrue(get("/version").second.contains("OcuBea")) }
    @Test fun videoStatus() { assertTrue(get("/videostatus").second.contains("recording")) }
    @Test fun motionData() { assertTrue(get("/motiondata").second.contains("motion")) }
    @Test fun motionAreas() { assertTrue(get("/modet_areas.json").second.contains("areas")) }
    @Test fun nowJpgNoFrame() { assertEquals(204, get("/now.jpg").first) }
}
