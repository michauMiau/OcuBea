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

    private val failures = ConcurrentHashMap<String, IntArray>() // ip -> [count, windowStart]

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

    fun clientIp(session: NanoHTTPD.IHTTPSession): String =
        session.headers["x-forwarded-for"]?.split(',')?.firstOrNull()?.trim()
            ?: session.headers["http-client-ip"]?.substringBefore(':')
            ?: "unknown"

    private fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val rec = failures.computeIfAbsent(ip) { intArrayOf(0, now.toInt()) }
        synchronized(rec) {
            // 60s window
            if (now - rec[1] > 60_000) {
                rec[0] = 0
                rec[1] = now.toInt()
            }
            rec[0]++
        }
    }

    private fun isRateLimited(ip: String): Boolean {
        val rec = failures[ip] ?: return false
        val now = System.currentTimeMillis()
        if (now - rec[1] > 60_000) {
            failures.remove(ip)
            return false
        }
        return rec[0] >= 10
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
