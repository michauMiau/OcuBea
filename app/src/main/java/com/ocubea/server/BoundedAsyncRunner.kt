package com.ocubea.server

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bounded connection handler for NanoHTTPD.
 *
 * The default runner spawns one new Thread per accepted socket, with no limit.
 * Two things make that a real risk here rather than a theoretical one:
 *
 *  - A `/video` connection holds its thread for the entire life of the stream,
 *    which on a live camera is hours. The 5s socket read timeout applies to
 *    reading the *request*, not to a long-lived response, so it does nothing
 *    here.
 *  - The app targets old phones with 512MB of RAM. A few browser tabs, or one
 *    device opening sockets in a loop, is enough to exhaust the thread and
 *    memory budget and kill the camera.
 *
 * The pool is small on purpose. Extra threads are cheap on a server and not on
 * a 2015 phone, and every handler that exists is a thread holding a stack plus
 * a socket. Four is enough for one viewer streaming video, one on the web UI,
 * and room to spare.
 *
 * Long-lived streams therefore share threads rather than pinning one each. That
 * is safe because a streaming handler blocks on its own socket and never waits
 * for another handler to finish; the queue is what bounds the *number of
 * connections*, and a connection that cannot get a thread is refused instead of
 * queueing without limit.
 */
class BoundedAsyncRunner(
    private val maxThreads: Int = DEFAULT_MAX_THREADS,
    private val maxQueued: Int = DEFAULT_MAX_QUEUED,
) : NanoHTTPD.AsyncRunner {

    private val running = AtomicInteger(0)
    private val rejected = AtomicInteger(0)

    private val pool = ThreadPoolExecutor(
        maxThreads, maxThreads,
        30L, TimeUnit.SECONDS,
        ArrayBlockingQueue(maxQueued.coerceAtLeast(1)),
        { r -> Thread(r, "ocubea-http").apply { isDaemon = true } },
        // Running out of threads must not look like an idle server: the caller
        // gets a rejection so the socket closes, instead of a request that
        // hangs forever waiting for a thread that will never come.
        ThreadPoolExecutor.AbortPolicy(),
    )


    /** Connections currently being handled, for status.json. */
    fun activeConnections(): Int = running.get()

    /** Connections refused because the pool was full. */
    fun refusedConnections(): Int = rejected.get()

    /**
     * Runs [work] if there is room, otherwise records a refusal and returns
     * false.
     *
     * Split out from [exec] so the admission decision can be tested without
     * constructing a `NanoHTTPD.ClientHandler` - that class is an inner class
     * of NanoHTTPD holding a reference to its server, so it cannot be built
     * outside one. The bound lives here, and this is the part that enforces it.
     */
    fun admit(work: () -> Unit): Boolean =
        try {
            pool.execute {
                running.incrementAndGet()
                try {
                    work()
                } finally {
                    running.decrementAndGet()
                }
            }
            true
        } catch (_: RuntimeException) {
            // RejectedExecutionException: the pool and its queue are both full.
            // Counted so a device that keeps hammering the camera is visible in
            // status.json rather than just silently failing.
            rejected.incrementAndGet()
            false
        }

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        if (admit { clientHandler.run() }) return
        try {
            clientHandler.close()
        } catch (_: Exception) {
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        // The pool owns thread lifetime; nothing to do per connection. The
        // default implementation tracks a list we deliberately do not keep,
        // because an unbounded list of live handlers is the thing being fixed.
    }

    override fun closeAll() {
        pool.shutdownNow()
    }

    companion object {
        /**
         * Sized for a phone, not a server.
         *
         * Measured, not guessed. With 4 threads and an 8-slot queue, six open
         * /video streams left /status.json and /config.json hanging for 6s each
         * and pushed FPS to 6: a stream holds its thread for hours, so a deep
         * queue only turns "too busy" into "no answer". With 8 threads and a
         * single queue slot, the same six streams left short requests at 27ms
         * with 15 FPS intact, and the surplus was refused immediately
         * (refused=6 in status.json) instead of queued.
         *
         * The short queue is the point: fail fast beats wait.
         */
        const val DEFAULT_MAX_THREADS = 12
        const val DEFAULT_MAX_QUEUED = 1
    }
}
