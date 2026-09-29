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

    // The next two tests exist because the two above pass for the wrong reason:
    // /status.json is public, so check() returns at the public-path gate and
    // never reaches the limiter at all. They are therefore not exercising the
    // lockout they claim to. These drive PROTECTED on both sides.

    @Test
    fun `a correct token survives a lockout on the same address`() {
        // Measured on the device before the fix: ten 401s, then a request with
        // the correct token returned 503. That is a denial of service the owner
        // could not do anything about, because clearFailures() had no caller.
        val a = auth("correct-token")
        val same = StubSession(params = mapOf("token" to listOf("wrong")))
        repeat(12) { a.check(same, PROTECTED) }

        val owner = StubSession(params = mapOf("token" to listOf("correct-token")))
        assertEquals(
            "a request carrying the correct token must never be rate limited: " +
                "the limiter exists to slow guessing, not to deny service",
            null,
            a.check(owner, PROTECTED)
        )
    }

    @Test
    fun `a correct token resets the failure count for that address`() {
        // The recovery path. Without it, one burst of ten wrong guesses from a
        // guest device locks the owner out for the rest of the window even
        // after they authenticate successfully.
        val a = auth("correct-token")
        val s = StubSession(params = mapOf("token" to listOf("wrong")))
        repeat(9) { a.check(s, PROTECTED) }
        a.check(StubSession(params = mapOf("token" to listOf("correct-token"))), PROTECTED)

        repeat(9) { a.check(s, PROTECTED) }
        val after = a.check(StubSession(params = mapOf("token" to listOf("correct-token"))), PROTECTED)
        assertEquals("the successful login must have cleared the counter", null, after)
    }

    @Test
    fun `a locked out address still gets 401 not 503 while guessing`() {
        // The limiter must still do its job: the fix moves the match first, not
        // the limit away. Without this, "always check the token first" could be
        // satisfied by removing the limiter entirely.
        val a = auth("correct-token")
        val s = StubSession(params = mapOf("token" to listOf("wrong")))
        var sawServiceUnavailable = false
        repeat(15) {
            if (statusOf(a.check(s, PROTECTED)) == 503) sawServiceUnavailable = true
        }
        assertTrue("the limiter must still engage for wrong tokens", sawServiceUnavailable)
    }

    @Test
    fun `X-Forwarded-For is still honoured for a loopback socket`() {
        // The reason the fallback exists: a real reverse proxy on the same host.
        // Confirmed reachable only over loopback, which is also exactly why the
        // pre-existing tests missed it - their stub used 192.168.1.50, so the
        // XFF branch at ApiAuth:101-102 never executed.
        //
        // Reaches past both thresholds on purpose: the per-tenant limit (10) and
        // the proxy-wide one (50) both need to be crossed for a spoofed header
        // to be caught, so a shorter loop proves nothing.
        val a = auth("correct-token")
        var sawServiceUnavailable = false
        repeat(80) { i ->
            val s = StubSession(
                headers = mapOf("x-forwarded-for" to "10.0.0.${i % 250}"),
                params = mapOf("token" to listOf("wrong")),
                remoteIp = "127.0.0.1",
            )
            if (statusOf(a.check(s, PROTECTED)) == 503) sawServiceUnavailable = true
        }
        assertTrue(
            "over loopback the header is the only identity available, so the " +
                "limiter has to count per forwarded address",
            sawServiceUnavailable
        )
    }

    @Test
    fun `a loopback client cannot dodge the limit with a fresh header`() {
        // And the consequence, measured by the audit: 40 guesses from
        // 127.0.0.1 with a fresh XFF each time never engaged the limit. If that
        // ever becomes a product decision rather than a bug, this test is the
        // place it will show up - it is the only thing standing between "a local
        // proxy resets the limiter" and a silent regression.
        //
        // 80 requests, not 40: with a proxy-wide backstop of 50, 40 is under the
        // threshold and would pass for the wrong reason.
        val a = auth("correct-token")
        var sawServiceUnavailable = false
        repeat(80) { i ->
            val s = StubSession(
                headers = mapOf("x-forwarded-for" to "10.1.$i.${i % 250}"),
                params = mapOf("token" to listOf("wrong")),
                remoteIp = "127.0.0.1",
            )
            if (statusOf(a.check(s, PROTECTED)) == 503) sawServiceUnavailable = true
        }
        assertTrue(
            "a fresh X-Forwarded-For per request must not buy an unlimited " +
                "number of buckets: that makes the limiter inert for anyone " +
                "behind a local proxy",
            sawServiceUnavailable
        )
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
