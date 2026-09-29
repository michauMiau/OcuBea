package com.ocubea.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status as Status
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional bearer-token auth for the HTTP API.
 *
 * Empty token = open camera (IP Webcam compatible default). When a token is
 * set, every endpoint except the login page and the status probe requires it.
 * Failed attempts are rate-limited per client to slow brute force.
 */
class ApiAuth(private val tokenProvider: () -> String) {

    /**
     * Failed attempts per client, for rate limiting.
     *
     * A mutable holder class rather than an `IntArray` because the window start
     * is epoch milliseconds and does not fit in an Int: `now.toInt()` truncated
     * 1790610600000 to -390762432, so `now - rec[1]` was ~1.79e12 and always
     * exceeded the 60s window. The counter was therefore reset on every single
     * failure and the lockout threshold was unreachable — the limiter existed
     * and did nothing, in the one configuration where the user believes they
     * are protected.
     *
     * `computeIfAbsent` needs API 24, so the entry is created with a plain
     * get/put instead.
     */
    private class Attempts {
        var count: Int = 0
        var windowStartMs: Long = 0L
    }

    private val failures = ConcurrentHashMap<String, Attempts>()

    /**
     * The loopback backstop. Not a map, because there is only ever one proxy
     * key - the loopback address - and a single field cannot be swapped out
     * from under a concurrent caller the way a map entry can.
     */
    @Volatile private var proxyFailures: Attempts? = null

    private fun attemptsFor(ip: String, now: Long): Attempts {
        val existing = failures[ip]
        if (existing != null) return existing
        val fresh = Attempts().also { it.windowStartMs = now }
        // putIfAbsent is API 24 too; a plain put can race and overwrite a
        // concurrently created entry, which only costs a count, never security.
        val prior = failures.put(ip, fresh)
        return prior ?: fresh
    }

    fun token(): String = tokenProvider().trim()

    fun isEnabled(): Boolean = token().isNotEmpty()

    /**
     * Check a session. Returns a response to send back on failure, or null
     * when the request may proceed.
     */
    fun check(session: NanoHTTPD.IHTTPSession, path: String): NanoHTTPD.Response? {
        val token = token()
        if (token.isEmpty()) return null

        // Public endpoints — needed to render the login form itself
        if (path == "/login" || path == "/login.html" || path == "/status.json" && session.method == NanoHTTPD.Method.GET) {
            return null
        }

        val clientIp = clientIp(session)

        // Order matters, and it used to be wrong: the limiter ran *before* the
        // token was checked, so ten wrong guesses from any other host on the
        // LAN locked the owner out for up to 60s - including the moment they
        // were logging in. Measured on the device: after ten 401s, a request
        // carrying the correct token got 503.
        //
        // A rate limit is there to slow guessing at the token. Refusing a
        // request that already presented the right token does not slow that
        // down at all - it just denies service, which is the only thing an
        // unauthenticated attacker can actually do to a camera. So the match
        // is checked first, and the limit only gates a request that already
        // failed it.
        if (matches(session, token)) {
            // A correct token from an address with a failure history is
            // evidence the guesser gave up, not that the owner is an attacker.
            // clearFailures() used to exist with no caller, which is why a
            // single burst could not be recovered from by anyone.
            clearFailures(clientIp)
            return null
        }

        if (isRateLimited(clientIp) || isProxyWideLimited(System.currentTimeMillis())) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE, "text/plain", "Too many failed attempts — try again in a minute"
            )
        }

        recordFailure(clientIp)
        return NanoHTTPD.newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain", "Unauthorized")
    }

    /** Accepts `?token=`, `Authorization: Bearer …` or `X-Auth-Token:` */
    private fun matches(session: NanoHTTPD.IHTTPSession, token: String): Boolean {
        val supplied = session.parameters["token"]?.firstOrNull()
            ?: session.headers["authorization"]?.lowercase()?.removePrefix("bearer ")?.trim()
            ?: session.headers["x-auth-token"]?.trim()
            ?: return false
        return constantTimeEquals(supplied, token)
    }

    /**
     * The address to count failures against.
     *
     * The socket's own remote address, NOT `X-Forwarded-For`. There is no proxy
     * in front of this server, so that header is attacker-controlled: a
     * brute-forcer sending a fresh value per request previously got a brand-new
     * rate-limit bucket every time, which made the limiter inert even once the
     * arithmetic was fixed. NanoHTTPD fills `remoteIp` from the accepted socket.
     *
     * `X-Forwarded-For` is honoured only when the socket address is loopback,
     * and it counts under a key that also names the proxy. Measured before the
     * fix: 40 guesses from 127.0.0.1, each with a fresh header, never engaged
     * the limit - a local proxy turned the limiter off entirely.
     *
     * So the bucket is `<loopback>|<header>` rather than the header alone. The
     * header still separates tenants behind a real proxy (one guest device
     * cannot lock out another), but a spoofed header can no longer mint an
     * unbounded number of buckets, because every spoof shares the proxy part
     * of the key and the limit that matters is enforced there too.
     */
    fun clientIp(session: NanoHTTPD.IHTTPSession): String {
        val socketIp = session.remoteIpAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        if (socketIp != null && !isLoopback(socketIp)) return socketIp
        val forwarded = session.headers["x-forwarded-for"]?.split(',')?.firstOrNull()?.trim()
        return if (!forwarded.isNullOrEmpty()) "loopback|$forwarded" else socketIp ?: "unknown"
    }

    private fun isLoopback(ip: String): Boolean =
        ip == "127.0.0.1" || ip == "::1" || ip.startsWith("127.")

    private fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val rec = attemptsFor(ip, now)
        synchronized(rec) {
            // 60s window, compared as Long. Truncating to Int here is what made
            // the reset fire on every failure.
            if (now - rec.windowStartMs > 60_000L) {
                rec.count = 0
                rec.windowStartMs = now
            }
            rec.count++
        }
        // Only for the loopback path, where the per-tenant key is a header the
        // client chose. A LAN client is already keyed on an address it cannot
        // forge, so a second counter there would only punish a shared NAT.
        if (ip.startsWith("loopback|")) recordProxyFailure(now)
    }

    private fun recordProxyFailure(now: Long) {
        val rec = proxyFailures ?: Attempts().also {
            it.windowStartMs = now
            proxyFailures = it
        }
        synchronized(rec) {
            if (now - rec.windowStartMs > 60_000L) {
                rec.count = 0
                rec.windowStartMs = now
            }
            rec.count++
        }
    }

    private fun isRateLimited(ip: String): Boolean {
        val rec = failures[ip] ?: return false
        val now = System.currentTimeMillis()
        if (now - rec.windowStartMs > 60_000L) {
            failures.remove(ip)
            return false
        }
        return rec.count >= FAILURE_LIMIT
    }

    /**
     * The proxy-wide failure count, counting every tenant behind it.
     *
     * This is what makes the loopback key meaningful. The per-tenant bucket
     * alone is spoofable — the header decides which bucket you get — so without
     * a second, header-independent count a client just sends a fresh
     * `X-Forwarded-For` and starts from zero forever. Keyed on the loopback
     * address itself, which an attacker cannot influence.
     *
     * The limit here is looser (5× the per-tenant one) on purpose: with several
     * people behind one NAT, the shared bucket is a backstop, not the everyday
     * limiter. A correct token still clears nothing — see check(), which runs
     * the match first and never rate limits it.
     */
    private fun isProxyWideLimited(now: Long): Boolean {
        val rec = proxyFailures ?: return false
        if (now - rec.windowStartMs > 60_000L) {
            proxyFailures = null
            return false
        }
        return rec.count >= PROXY_LIMIT
    }

    /**
     * Clears the failure history for one address.
     *
     * Per-IP rather than clear-all: a guest device that burned the limit
     * should not be able to reset the owner's counter, and a successful login
     * should only ever forgive the address that logged in.
     */
    fun clearFailures(ip: String) {
        failures.remove(ip)
    }

    /** Comparison that does not leak length/content through timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ha = sha256(a)
        val hb = sha256(b)
        return MessageDigest.isEqual(ha, hb)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    companion object {
        /**
         * Wrong guesses per 60s per address before the address is refused.
         *
         * 10 at 60s is still 14,400 guesses a day against one address, which is
         * a lot for a token a human chose - see the length question in
         * docs/SECURITY_CAMERA.md. It is the compromise between an NVR that
         * retries and a brute-forcer with time.
         */
        const val FAILURE_LIMIT = 10

        /**
         * Wrong guesses per 60s from everything behind the loopback proxy
         * combined. 5x the per-address limit: the per-tenant key there is a
         * header, so this is the count that cannot be side-stepped. Looser than
         * the per-tenant one because a house behind a NAT legitimately shares
         * the address.
         */
        const val PROXY_LIMIT = 50

        /** Short, human-typeable code derived from the token. */
        fun hintFor(token: String): String = if (token.isEmpty()) "none" else token.take(4) + "…"
    }
}
