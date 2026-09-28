package com.ocubea.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera answers with no cross-origin access at all. These tests pin the
 * rule that removed a measured delete-and-read hole, so a future
 * "just add CORS back for convenience" change fails here.
 */
class CorsPolicyTest {

    @Test
    fun `a request with no Origin needs no CORS header`() {
        // curl, the native app, scripts: not subject to CORS, must work.
        assertTrue(CorsPolicy.needsCorsHeader(null))
        assertTrue(CorsPolicy.needsCorsHeader(""))
        assertTrue(CorsPolicy.needsCorsHeader("   "))
    }

    @Test
    fun `any real origin is refused, including loopback on another port`() {
        assertFalse(CorsPolicy.needsCorsHeader("http://evil.example"))
        assertFalse(CorsPolicy.needsCorsHeader("https://evil.example"))
        assertFalse(CorsPolicy.needsCorsHeader("http://192.168.1.50:8099"))
        // A local web server on another port is a different origin.
        assertFalse(CorsPolicy.needsCorsHeader("http://localhost:8099"))
        assertFalse(CorsPolicy.needsCorsHeader("http://127.0.0.1:3000"))
    }

    @Test
    fun `null and wildcard origins are refused`() {
        // A sandboxed iframe or file:// page sends "null"; "*" is never a
        // real browser Origin and must not be treated as one.
        assertFalse(CorsPolicy.needsCorsHeader("null"))
        assertFalse(CorsPolicy.needsCorsHeader("*"))
    }

    @Test
    fun `DELETE is no longer advertised`() {
        assertFalse(CorsPolicy.ALLOWED_METHODS.contains("DELETE"))
        assertTrue(CorsPolicy.ALLOWED_METHODS.contains("GET"))
        assertTrue(CorsPolicy.ALLOWED_METHODS.contains("POST"))
    }
}
