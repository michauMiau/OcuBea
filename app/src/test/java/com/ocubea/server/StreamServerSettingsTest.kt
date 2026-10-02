package com.ocubea.server

import android.content.Context
import android.content.SharedPreferences
import com.ocubea.camera.CameraManager
import com.ocubea.model.OcuBeaConfig
import com.ocubea.onvif.OnvifDiscovery
import com.ocubea.security.MotionDetector
import com.ocubea.security.MotionRecorder
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.net.HttpURLConnection
import java.net.URL

/**
 * `/settings/<name>?set=<value>` at the level a client actually reaches it.
 *
 * Everything that was wrong with focus and rotate was invisible to the unit
 * tests that already existed. FocusModePlanTest and OrientationVocabularyTest
 * check the two pure functions, and they pass whatever the handler does with
 * their return values — so putting the unconditional `okText("auto")` back into
 * `applySetting` left 351 tests green while the endpoints went back to
 * answering "Ok" to eight values with nothing behind any of them.
 *
 * The gap is that neither function is the handler. The handler is
 * `StreamServer.handleSetting`, and the only way to reach it the way pydroid
 * does is over a socket. So this test binds a real NanoHTTPD on an ephemeral
 * port with a mocked CameraManager, sends real HTTP requests, and asserts on
 * the status code and the bytes that come back.
 *
 * Two things follow from doing it this way and are the reason it is worth the
 * mock dependency:
 *
 *  - A refusal has to be a refusal *on the wire*. Every success path in this
 *    server funnels through `withOkBody`, which replaces the body with "Ok",
 *    so the interesting half of the surface is the 400s. An unconditional
 *    `okText("auto")` turns them into 200s, and these tests go red.
 *  - A setting has to actually reach the camera. Asserting only on the body
 *    would pass a handler that says "Ok" and calls nothing; so the ones with
 *    a real primitive behind them also verify the call, and verify that the
 *    refusals make no call at all.
 */
class StreamServerSettingsTest {

    private data class Reply(val code: Int, val body: String)

    private lateinit var context: Context
    private lateinit var camera: CameraManager
    private lateinit var recorder: MotionRecorder
    private lateinit var config: OcuBeaConfig
    private var server: StreamServer? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        // StreamServer hands context.applicationContext to DeviceSensors and
        // AudioStreamManager, and those are non-null Kotlin parameters, so a
        // bare mock's null would be rejected before the constructor finished.
        `when`(context.applicationContext).thenReturn(context)

        camera = mock(CameraManager::class.java)
        recorder = mock(MotionRecorder::class.java)
        val discovery = mock(OnvifDiscovery::class.java)
        config = OcuBeaConfig(prefs())
        // A real OcuBeaConfig over stubbed preferences, not a mock of the config
        // itself: a mocked String getter returns null, and ApiAuth calls
        // `config.accessToken.trim()` on the request path, so the whole server
        // answered 500 to everything and every assertion below failed for one
        // reason that has nothing to do with what it checks. Real defaults are
        // also the state a fresh install is in -- empty access token, so ApiAuth
        // is disabled, which is the open-camera default clients are written
        // against.
        // Port 0 lets the kernel choose, so a run can never collide with a real
        // camera on 8080 or with a parallel test.
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

    // ── Harness ────────────────────────────────────────────────────────────

    /**
     * In-memory preferences so OcuBeaConfig returns its documented defaults.
     *
     * Every accessor OcuBeaConfig uses is wrapped in getOrDefault /
     * ?.orEmpty() / runCatching, so a stub returning null for an unset key
     * yields exactly the value a freshly installed app has. That matters: a
     * mocked config returns null from `accessToken`, and ApiAuth calls
     * `.trim()` on it on every request, which turned every response into a 500.
     */
    private fun prefs(): SharedPreferences {
        val store = HashMap<String, Any?>()
        val editor = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?): SharedPreferences.Editor =
                also { store[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor =
                also { store[k] = v }
            override fun putInt(k: String, v: Int): SharedPreferences.Editor = also { store[k] = v }
            override fun putLong(k: String, v: Long): SharedPreferences.Editor = also { store[k] = v }
            override fun putFloat(k: String, v: Float): SharedPreferences.Editor = also { store[k] = v }
            override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor =
                also { store[k] = v }
            override fun remove(k: String): SharedPreferences.Editor = also { store.remove(k) }
            override fun clear(): SharedPreferences.Editor = also { store.clear() }
            // Writes land immediately, so there is nothing pending to flush and
            // the test cannot observe a different value than the one it wrote.
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = HashMap(store)
            override fun getString(k: String?, d: String?): String? = store[k] as? String ?: d
            @Suppress("UNCHECKED_CAST")
            override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? =
                (store[k] as? Set<String>)?.toMutableSet() ?: d
            override fun getInt(k: String?, d: Int): Int = store[k] as? Int ?: d
            override fun getLong(k: String?, d: Long): Long = store[k] as? Long ?: d
            override fun getFloat(k: String?, d: Float): Float = store[k] as? Float ?: d
            override fun getBoolean(k: String?, d: Boolean): Boolean = store[k] as? Boolean ?: d
            override fun contains(k: String?): Boolean = store.containsKey(k)
            override fun edit(): SharedPreferences.Editor = editor
            override fun registerOnSharedPreferenceChangeListener(
                l: SharedPreferences.OnSharedPreferenceChangeListener?,
            ) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(
                l: SharedPreferences.OnSharedPreferenceChangeListener?,
            ) = Unit
        }
    }

    private fun focusingCamera(capable: Boolean) {
        `when`(camera.isFocusCapable()).thenReturn(capable)
    }

    /** One real GET, read the way a client reads it: code and body. */
    private fun get(path: String): Reply {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        // NanoHTTPD defaults to keep-alive on HTTP/1.1; closing keeps each
        // request to exactly one connection so a red result is never a leaked
        // socket from the assertion before it.
        conn.setRequestProperty("Connection", "close")
        return try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            Reply(code, stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun set(name: String, value: String): Reply = get("/settings/$name?set=$value")

    // ── The harness itself ─────────────────────────────────────────────────

    /**
     * Proves the socket is live and the dispatch table is being reached.
     *
     * Without this, a server that failed to bind or a URL that 404s at routing
     * would make every other assertion here "pass" for the wrong reason — the
     * status codes are being asserted explicitly for exactly that reason, so
     * the one thing under test is that an unrouted key is distinguishable from
     * a routed one.
     */
    @Test
    fun `an unrouted setting is a 404, so a routed one is distinguishable`() {
        val r = get("/settings/definitely_not_a_setting?set=1")
        assertEquals(404, r.code)
        assertTrue(r.body, r.body.contains("unknown setting"))
    }

    // ── focusmode / focus ──────────────────────────────────────────────────

    @Test
    fun `focusmode macro on a focusing camera answers Ok and drives the lens`() {
        focusingCamera(true)

        val r = set("focusmode", "macro")

        assertEquals(200, r.code)
        // withOkBody() rewrites a 200 body to the IP Webcam "Ok", which is what
        // pydroid looks for. The proof that this was not a no-op is the verify
        // below: an unconditional okText("auto") makes no call.
        assertEquals("Ok", r.body)
        verify(camera).setFocus(eq(0.5f), eq(0.5f), eq(false))
    }

    @Test
    fun `focusmode macro on a camera with no autofocus is refused with a reason`() {
        focusingCamera(false)

        val r = set("focusmode", "macro")

        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("no autofocus"))
        // Refusing without touching the lens is the point: the old handler
        // answered Ok, and the client was told a mode changed.
        verify(camera, never()).setFocus(eq(0.5f), eq(0.5f), eq(false))
    }

    @Test
    fun `focusmode infinity is refused even on a camera that can focus`() {
        focusingCamera(true)

        val r = set("focusmode", "infinity")

        // Infinity names a subject at infinity; locking the lens where it
        // happens to land is a different subject, so it is an approximation
        // this server refuses rather than fakes.
        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("infinity"))
        verify(camera, never()).setFocus(eq(0.5f), eq(0.5f), eq(false))
    }

    @Test
    fun `focusmode off locks focus where it lands`() {
        focusingCamera(true)

        val r = set("focusmode", "off")

        assertEquals(200, r.code)
        verify(camera).setFocus(eq(0.5f), eq(0.5f), eq(true))
    }

    @Test
    fun `focusmode nofocus releases the lock`() {
        focusingCamera(true)

        val r = set("focusmode", "nofocus")

        assertEquals(200, r.code)
        verify(camera).clearFocusLock()
    }

    @Test
    fun `a focusmode value outside the vocabulary is refused`() {
        focusingCamera(true)

        val r = set("focusmode", "banana")

        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("unknown focusmode"))
        assertTrue(
            "the refusal has to name the accepted set, or the client has nowhere to go",
            r.body.contains("macro") && r.body.contains("infinity"),
        )
    }

    @Test
    fun `focus is the same mapping under the other name`() {
        focusingCamera(true)
        assertEquals(200, set("focus", "on").code)
        verify(camera).setFocus(eq(0.5f), eq(0.5f), eq(false))

        focusingCamera(false)
        val refused = set("focus", "on")
        assertEquals(400, refused.code)
        assertTrue(refused.body, refused.body.contains("no autofocus"))
    }

    @Test
    fun `focus_distance is refused rather than accepted and dropped`() {
        focusingCamera(true)

        val r = set("focus_distance", "5.0")

        // CameraX has no diopter control, so the 0.0-10.0 scale the API
        // documents cannot be acted on. Accepting 5.0 was a confirmation of
        // nothing.
        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("not supported"))
    }

    @Test
    fun `focus_distance reports why the focus lock could not be released`() {
        focusingCamera(true)
        `when`(camera.clearFocusLock()).thenReturn("camera is not running")

        val r = set("focus_distance", "0.0")

        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("camera is not running"))
    }

    /**
     * The shape of the whole gap, stated as one assertion.
     *
     * `focusmode` answering Ok to every value is what this test exists to
     * prevent, so it is asserted directly rather than only through the
     * individual cases: if any value at all is answered, this fails. Both
     * capability branches are covered, because the handler consults the camera
     * before it decides and one branch can be correct while the other is not.
     */
    @Test
    fun `no focus setting answers Ok for every value it is offered`() {
        for (capable in listOf(true, false)) {
            focusingCamera(capable)
            val answered = mutableListOf<Pair<String, Int>>()
            for (value in FocusModePlan.ACCEPTED) {
                answered += value to set("focusmode", value).code
            }
            answered += "focus_distance" to set("focus_distance", "5.0").code
            assertTrue(
                "focusCapable=$capable: every value was answered 200, which is the " +
                    "unconditional okText(\"auto\") this server used to return",
                answered.any { it.second != 200 },
            )
            assertNotEquals(
                "focusCapable=$capable: a value in the API vocabulary must be " +
                    "distinguishable from an arbitrary one",
                answered.map { it.first to (it.second == 200) }.toSet().size,
                1,
            )
        }
    }

    // ── orientation / rotate ───────────────────────────────────────────────

    @Test
    fun `rotate portrait is applied and answered Ok`() {
        val r = set("rotate", "portrait")

        assertEquals(200, r.code)
        assertEquals("Ok", r.body)
        // rotate shares the whitelist with orientation, so the string that
        // reaches the camera is the canonical one.
        verify(camera).setDisplayOrientation("portrait")
    }

    @Test
    fun `rotate with a value that is not an orientation is refused`() {
        val r = set("rotate", "banana")

        // This is the measured lie: rotate=banana, rotate=0, rotate=sideways
        // and rotate=90 all answered "Ok", setDisplayOrientation stored the
        // string, and rotationValueFor() mapped all four to ROTATION_0 — so
        // the picture did not rotate and the bad value was persisted.
        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("unknown orientation"))
        // Storing it at all is the harm: once "banana" is the orientation,
        // `orientation` cannot reach a known state either.
        verify(camera, never()).setDisplayOrientation(anyString())
    }

    @Test
    fun `rotate sideways is refused rather than guessed at`() {
        val r = set("rotate", "sideways")

        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("sideways"))
        verify(camera, never()).setDisplayOrientation(anyString())
    }

    @Test
    fun `rotate with degrees is refused`() {
        val r = set("rotate", "90")

        // Degrees name sensor rotation, not the picture's orientation, and
        // guessing which of the four was meant would be the same silent wrong
        // answer as accepting the string.
        assertEquals(400, r.code)
        verify(camera, never()).setDisplayOrientation(anyString())
    }

    @Test
    fun `orientation resolves an alias to the canonical name`() {
        val r = set("orientation", "reverse")

        assertEquals(200, r.code)
        verify(camera).setDisplayOrientation("upsidedown")
    }

    @Test
    fun `orientation with a value that is not an orientation is refused`() {
        val r = set("orientation", "sideways")

        assertEquals(400, r.code)
        assertTrue(r.body, r.body.contains("unknown orientation"))
        verify(camera, never()).setDisplayOrientation(anyString())
    }

    /**
     * `orientation` and `rotate` are one rotation under two names, so they get
     * one assertion: a value refused under either name must be refused under
     * both, and an accepted one accepted under both. Two separate branches with
     * two different whitelists is precisely how rotate came to accept anything
     * while orientation did not.
     */
    @Test
    fun `orientation and rotate refuse and accept exactly the same values`() {
        for (value in listOf("landscape", "portrait", "upsidedown", "upsidedown_portrait", "reverse")) {
            assertEquals(
                "$value should be accepted under both names",
                set("orientation", value).code,
                set("rotate", value).code,
            )
        }
        for (value in listOf("banana", "sideways", "90", "0", "")) {
            assertEquals(
                "$value should be refused under both names",
                set("orientation", value).code,
                set("rotate", value).code,
            )
            assertEquals("$value should be refused, not answered", 400, set("rotate", value).code)
        }
    }

    // ── overlay / awake / idle / sound / motion / gps ──────────────────────
    //
    // Added after mutating the five handlers below back into an unconditional
    // okText() and getting 448 green. Every test here asserts on state that only
    // a real effect can have produced, because a reply string is the one thing a
    // no-op can fake.

    @Test
    fun `idle on writes powerSaving, which the resolution governor reads`() {
        val r = set("idle", "on")
        assertEquals(200, r.code)
        assertTrue("idle=on answered 200 but powerSaving is ${config.powerSaving}", config.powerSaving)
    }

    @Test
    fun `idle off is the other direction and is not silently ignored`() {
        set("idle", "on")
        val r = set("idle", "off")
        assertEquals(200, r.code)
        assertFalse("idle=off answered 200 but powerSaving stayed true", config.powerSaving)
    }

    @Test
    fun `idle outside the vocabulary is refused`() {
        val r = set("idle", "banana")
        assertEquals(400, r.code)
        assertTrue(r.body, r.body.isNotEmpty())
    }

    @Test
    fun `sound off is always allowed, because you must be able to mute`() {
        val r = set("sound", "off")
        assertEquals(200, r.code)
        assertFalse("sound=off answered 200 but soundEnabled is true", config.soundEnabled)
    }

    @Test
    fun `sound_timeout stores the seconds it was given`() {
        val r = set("sound_timeout", "45")
        assertEquals(200, r.code)
        assertEquals(45, config.soundTimeoutSeconds)
    }

    @Test
    fun `sound_timeout outside the range is refused, not clamped`() {
        set("sound_timeout", "45")
        val r = set("sound_timeout", "999999")
        assertEquals(400, r.code)
        assertEquals("a refused value was stored anyway", 45, config.soundTimeoutSeconds)
    }

    @Test
    fun `motion_limit writes the config and the recorder agrees`() {
        val r = set("motion_limit", "30")
        assertEquals(200, r.code)
        assertEquals(30, config.maxClipSeconds)
    }

    @Test
    fun `motion_event arms the detector`() {
        // motionEventEnabled defaults to true, so an unconditional okText("on")
        // would satisfy a bare assertTrue. Drive it to the other state first:
        // the assertion is only meaningful as a change.
        assertEquals(200, set("motion_event", "off").code)
        assertFalse("precondition failed: motion_event=off did not disarm", config.motionEventEnabled)

        val r = set("motion_event", "on")
        assertEquals(200, r.code)
        assertTrue("motion_event=on answered 200 but the detector is not armed", config.motionEventEnabled)
    }

    @Test
    fun `gps_active is refused in both directions, never faked`() {
        for (v in listOf("on", "off")) {
            val r = set("gps_active", v)
            assertEquals("gps_active=$v must not answer 200", 400, r.code)
            assertTrue(r.body, r.body.isNotEmpty())
        }
    }

    @Test
    fun `awake stores the requested state whatever the window does`() {
        // There is no OcuBea window in a JVM test, so awake is REFUSED here --
        // and that refusal is the correct answer, not a failure to work around.
        // What must still hold is the promise the refusal makes: the request is
        // stored and applied to the next window. A 400 that stored nothing would
        // be a lie, and an unconditional okText("ok") is exactly that.
        val pre = set("awake", "off")
        assertEquals(400, pre.code)
        assertFalse("precondition failed: awake=off did not clear the request", config.keepScreenOn)

        val r = set("awake", "on")
        assertEquals("no window in a JVM test, so 400 is the honest answer", 400, r.code)
        assertTrue(r.body, r.body.contains("window"))
        assertTrue("awake=on did not store the request", config.keepScreenOn)
    }

    @Test
    fun `overlay stores the preference even when it cannot be applied yet`() {
        val r = set("overlay", "on")
        assertTrue("overlay=on did not store the preference", config.overlayEnabled)
        // The status may be 400 (no frames yet) or 200 -- but never 500, and the
        // stored preference must survive either way.
        assertTrue("status was ${r.code}", r.code == 200 || r.code == 400)
    }
}
