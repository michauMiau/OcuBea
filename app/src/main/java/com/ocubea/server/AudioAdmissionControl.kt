package com.ocubea.server

/**
 * Caps how many live audio clients one server may hold at a time.
 *
 * Why this exists, measured on the phone: `/audio.wav` is a chunked response,
 * so NanoHTTPD keeps a pool thread for the whole connection. The pool is 12
 * (`BoundedAsyncRunner.DEFAULT_MAX_THREADS`) and the threshold is exact: 10
 * audio clients are fine, 11 take `/status.json` down with `ConnectionReset`.
 * 14 dumb clients that never read recovered only after 90 s.
 *
 * Two things were tried before this, and the order matters because the first
 * one looks like a fix and is not:
 *
 * 1. Removing the monitor held across `pipe.write` (AudioFanOut). Correct, and
 *    necessary -- but it only stops `addClient` from blocking. It did not
 *    change the measured threshold at all.
 * 2. Replacing the pipe with AudioRingBuffer so `offer()` drops a slow client
 *    instead of waiting. Also correct, and it did free the capture thread --
 *    after the fix `ocubea-audio` was gone from the thread list while all 12
 *    `ocubea-http` threads were still asleep. But they were asleep in
 *    `write()` on the socket, not in our code: `ClientHandler.acceptSocket` is
 *    private, so there is no way to set `SO_SNDTIMEO` or to know the path at
 *    dispatch time, and a client that never reads pins its thread no matter
 *    what the application does.
 *
 * So the thread cannot be reclaimed, only refused. Capping admissions is the
 * one lever left: it bounds how many threads streaming can ever take, which
 * leaves a known number for `/status.json` and the control endpoints.
 *
 * No Android imports, so the policy is assertable on the JVM.
 */
class AudioAdmissionControl(val maxClients: Int = DEFAULT_MAX_CLIENTS) {

    companion object {
        /**
         * How many audio clients may be connected at once.
         *
         * The pool holds 12 threads and a stream holds one for its whole
         * connection, so every admitted client is a thread the control
         * endpoints cannot use. Measured on the phone: with a cap of 4, 14
         * attacking clients left `/status.json` answering 200 throughout, and
         * the camera kept streaming at 11 fps. The margin below 12 is
         * deliberate -- 8 leaves 4 threads for the session, `/status.json` and
         * the camera, which is enough for the control UI to stay responsive
         * while a few people listen.
         */
        const val DEFAULT_MAX_CLIENTS = 8
    }

    /**
     * Whether one more client may be admitted.
     *
     * Read by the request handler before it registers anything, so a refused
     * client never allocates a buffer or a thread's worth of work.
     */
    fun canAdmit(currentClients: Int): Boolean = currentClients < maxClients
}
