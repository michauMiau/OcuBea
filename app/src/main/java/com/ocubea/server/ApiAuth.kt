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
        if (isRateLimited(clientIp)) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.SERVICE_UNAVAILABLE, "text/plain", "Too many failed attempts — try again in a minute"
            )
        }

        if (matches(session, token)) return null

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
     * `X-Forwarded-For` is still read as a fallback for the case of a real
     * reverse proxy, but only when the socket address is loopback — i.e. when
     * the connection genuinely came from something local.
     */
    fun clientIp(session: NanoHTTPD.IHTTPSession): String {
        val socketIp = session.remoteIpAddress?.takeIf { it.isNotBlank() && it != "0.0.0.0" }
        if (socketIp != null && !isLoopback(socketIp)) return socketIp
        val forwarded = session.headers["x-forwarded-for"]?.split(',')?.firstOrNull()?.trim()
        return forwarded?.takeIf { it.isNotEmpty() } ?: socketIp ?: "unknown"
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
    }

    private fun isRateLimited(ip: String): Boolean {
        val rec = failures[ip] ?: return false
        val now = System.currentTimeMillis()
        if (now - rec.windowStartMs > 60_000L) {
            failures.remove(ip)
            return false
        }
        return rec.count >= 10
    }

    fun clearFailures() = failures.clear()

    /** Comparison that does not leak length/content through timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ha = sha256(a)
        val hb = sha256(b)
        return MessageDigest.isEqual(ha, hb)
    }

    private fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    companion object {
        /** Short, human-typeable code derived from the token. */
        fun hintFor(token: String): String = if (token.isEmpty()) "none" else token.take(4) + "…"
    }
}
