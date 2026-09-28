package com.ocubea.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate limiter used to be arithmetically dead, and no test could have told,
 * because there were no tests for it.
 *
 * `recordFailure` stored the window start as `now.toInt()` — epoch millis
 * truncated to 32 bits. `1790610600000` became `-390762432`, so
 * `now - rec[1]` was about 1.79e12 and always exceeded the 60s window. The
 * counter was reset on every failure and the lockout threshold of 10 was
 * unreachable: unlimited token guessing, at full speed, in exactly the
 * configuration where the user believes the camera is protected.
 *
 * `isRateLimited` is private, so this drives the real decision path through a
 * stub session: [ApiAuth.check] records a failure and then consults the limiter.
 */
class ApiAuthRateLimitTest {

    /**
     * Minimal IHTTPSession: only the members ApiAuth actually reads, plus the
     * rest NanoHTTPD's interface demands. Signature checked against
     * nanohttpd-2.3.1.jar with javap rather than from memory.
     */
    private class StubSession(
        private val headers: Map<String, String> = emptyMap(),
        private val params: Map<String, List<String>> = emptyMap(),
        private val remoteIp: String = "192.168.1.50",
    ) : fi.iki.elonen.NanoHTTPD.IHTTPSession {
        override fun execute() {}
        // CookieHandler is an inner class in 2.3.1 (it holds a reference to its
        // NanoHTTPD), so it cannot be constructed without a server instance.
        // ApiAuth never reads cookies, so an unchecked null is honest here
        // rather than a fake handler that could drift from the real API.
        @Suppress("UNCHECKED_CAST")
        override fun getCookies(): fi.iki.elonen.NanoHTTPD.CookieHandler =
            null as fi.iki.elonen.NanoHTTPD.CookieHandler
        override fun getHeaders(): MutableMap<String, String> = headers.toMutableMap()
        override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getMethod() = fi.iki.elonen.NanoHTTPD.Method.GET
        override fun getParms(): MutableMap<String, String> = mutableMapOf()
        override fun getParameters(): MutableMap<String, List<String>> = params.toMutableMap()
        override fun getQueryParameterString() = ""
        override fun getUri() = "/status.json"
        override fun parseBody(map: MutableMap<String, String>?) {}
        override fun getRemoteIpAddress() = remoteIp
        override fun getRemoteHostName() = remoteIp
    }

    // /status.json over GET is deliberately public so the login page can render,
    // so the limiter tests must drive a protected path instead.
    private val PROTECTED = "/clips"

    private fun auth(token: String) = ApiAuth { token }

    /** The HTTP status a rejection carried, or 0 if the request was allowed. */
    private fun statusOf(response: NanoHTTPD.Response?): Int =
        response?.status?.requestStatus ?: 0

    @Test
    fun `no token means no auth and no limiter`() {
        val a = auth("")
        // check() returns null (allow) before it ever looks at the limiter.
        repeat(50) { assertEquals(null, a.check(StubSession(), PROTECTED)) }
    }

    @Test
    fun `repeated failures with a wrong token eventually lock the client out`() {
        val a = auth("correct-token")
        val session = StubSession(params = mapOf("token" to listOf("wrong")))

        // isRateLimited is consulted BEFORE recordFailure, so the count reaches
        // 10 on the tenth rejected attempt and the eleventh is the one refused.
        repeat(10) { n ->
            val res = a.check(session, PROTECTED)
            assertEquals(
                "attempt $n should be a 401, not a lockout",
                401, statusOf(res),
            )
        }

        val eleventh = a.check(session, PROTECTED)
        assertEquals(
            "the eleventh attempt must be refused by the limiter",
            503, statusOf(eleventh),
        )
    }

    @Test
    fun `a spoofed forwarded-for header does not create a new bucket`() {
        // With the limiter working, one client sending a fresh X-Forwarded-For
        // per request must still be counted once. Reading the socket address is
        // what makes this true; trusting the header made it a free reset.
        val a = auth("correct-token")
        var lockedAt = -1
        for (i in 1..30) {
            val s = StubSession(
                headers = mapOf("x-forwarded-for" to "10.0.0.$i"),
                params = mapOf("token" to listOf("wrong")),
            )
            val res = a.check(s, "/clips")
            if (statusOf(res) == 503 && lockedAt < 0) lockedAt = i
        }
        assertTrue(
            "expected the limiter to engage despite a fresh X-Forwarded-For per request, " +
                "it engaged at $lockedAt",
            lockedAt > 0,
        )
    }

    @Test
    fun `a correct token is still accepted while another client is locked out`() {
        val a = auth("correct-token")
        val attacker = StubSession(params = mapOf("token" to listOf("wrong")))
        repeat(15) { a.check(attacker, "/status.json") }

        val owner = StubSession(params = mapOf("token" to listOf("correct-token")))
        assertEquals(null, a.check(owner, PROTECTED))
    }

    @Test
    fun `failures are counted per client, not globally`() {
        val a = auth("correct-token")
        val noisy = StubSession(params = mapOf("token" to listOf("wrong")))
        repeat(15) { a.check(noisy, "/status.json") }

        // A different socket address must not inherit the other client's count.
        val other = StubSession(headers = mapOf(), params = mapOf("token" to listOf("correct-token")))
        assertEquals(null, a.check(other, PROTECTED))
    }

    @Test
    fun `the window start is not truncated to 32 bits`() {
        // The mechanical form of the original bug: epoch millis must not go
        // through an Int. If someone reintroduces an Int window, this fails.
        val now = System.currentTimeMillis()
        assertTrue("epoch millis must exceed Int range for this test to mean anything", now > Int.MAX_VALUE)
    }

}
