package com.ocubea.server

/**
 * CORS policy for a camera that serves the user's own video.
 *
 * The old policy answered every request with `Access-Control-Allow-Origin: *`
 * and advertised DELETE in Allow-Methods. A page on any domain could then
 * preflight, get 200, send the DELETE and lose a recording - that sequence was
 * executed against the phone to confirm it, not inferred.
 *
 * The fix is not a narrower wildcard but no CORS at all:
 *
 *  - A same-origin request never consults CORS. The Web UI is served by this
 *    same server and uses relative URLs, so it needs no header.
 *  - A non-browser client (curl, the native app, scripts, IP Webcam apps)
 *    sends no Origin and is not subject to CORS, so it keeps working.
 *  - Therefore any request that *does* carry an Origin is by definition
 *    cross-origin here and gets no allow header - the browser blocks it.
 *
 * That includes `null` (a sandboxed iframe, a file:// page) and a literal `*`.
 * An earlier draft allowed any loopback port, which was wrong: a page served
 * from a local web server on another port is a different origin.
 *
 * [ALLOWED_METHODS] still matters - a cross-origin preflight reads it, and
 * advertising DELETE is what made the delete reachable. It is gone.
 */
object CorsPolicy {

    /**
     * True when no allow header may be sent for this request.
     *
     * False only when the request carries no Origin at all, i.e. a non-browser
     * client or a same-origin request. Every Origin-bearing request is refused.
     */
    fun needsCorsHeader(origin: String?): Boolean = origin.isNullOrBlank()

    /**
     * Preflight methods.
     *
     * DELETE is no longer advertised: clips are the user's own data and
     * nothing legitimate deletes them from another origin. The native UI and
     * the same-origin Web UI are unaffected - neither goes through CORS.
     */
    const val ALLOWED_METHODS = "GET, POST, OPTIONS"

    const val ALLOWED_HEADERS = "Authorization, Content-Type, X-Auth-Token"
}
