package com.ocubea.server

import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * A bounded byte buffer for one live audio client, with a writer that cannot
 * block.
 *
 * This replaces `PipedOutputStream` + `PipedInputStream(64 KB)` for
 * `/audio.wav`. It exists because of a measured outage, and the shape is forced
 * by that measurement:
 *
 * - `/audio.wav` is a chunked response, so NanoHTTPD keeps one pool thread
 *   occupied for the whole connection. The pool is 12 threads
 *   (`BoundedAsyncRunner.DEFAULT_MAX_THREADS`) and the measured threshold is
 *   exact: 10 audio clients are fine, 11 take `/status.json` down with
 *   `ConnectionReset`. A per-path thread pool is not reachable, because
 *   `ClientHandler.inputStream` is private and the `AsyncRunner` only ever
 *   receives a socket -- there is no way to know the path at dispatch time.
 * - So the fix cannot be "move audio elsewhere". It has to be "make a client
 *   cheap to abandon". With a pipe, `write` blocks until the client drains 64 KB
 *   -- forever, if the client never reads. That blocks `captureLoop` for every
 *   other client and pins the server thread for the whole session.
 *
 * The rule here: the writer never waits. If the buffer is full the client is
 * behind by [capacity] bytes and is dropped. At 44.1 kHz 16-bit mono, 64 KB is
 * about 0.74 s of audio, so a client gets dropped after falling that far
 * behind, and the server thread unwinds immediately. A healthy reader consumes
 * in real time and never sees a drop.
 *
 * [read] does block, and that is correct: it is the stream side, and a stream
 * reader waiting for the next sample is the normal case, not a stall.
 *
 * No Android imports, so the policy is assertable on the JVM. That is the whole
 * point -- the bug that caused the outage was invisible to the build, to lint
 * and to every test, because reaching it needed a real `AudioRecord`.
 */
class AudioRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /**
         * 64 KB, the size the pipe used to have. At 44.1 kHz 16-bit mono that
         * is ~0.74 s of audio, so a client is dropped once it has fallen about
         * three quarters of a second behind.
         */
        const val DEFAULT_CAPACITY = 64 * 1024

        private fun checkCapacity(capacity: Int) {
            require(capacity > 0) { "capacity must be positive, was $capacity" }
        }
    }

    private val buf = ByteArray(capacity)
    private var head = 0
    private var count = 0
    private var closed = false
    private val lock = ReentrantLock()
    private val notFull = lock.newCondition()
    private val notEmpty = lock.newCondition()

    init {
        checkCapacity(capacity)
    }

    /**
     * Appends [len] bytes, or drops the client if they will not fit.
     *
     * @return false when the buffer is full or closed -- the caller must treat
     *   that as a dead client and unregister it.
     */
    fun offer(data: ByteArray, offset: Int, len: Int): Boolean {
        lock.lock()
        try {
            if (closed) return false
            if (len > capacity - count) {
                // A chunk larger than the whole buffer could never be served,
                // and waiting for room would mean blocking on this client.
                // Drop it rather than deadlock the capture loop.
                return false
            }
            while (count == capacity) {
                // Unreachable with the check above, but if `count` ever reaches
                // capacity through another path this keeps the invariant that
                // offer() never returns without either space or a drop.
                if (closed) return false
                notFull.await()
            }
            var w = head + count
            if (w >= capacity) w -= capacity
            val first = minOf(len, capacity - w)
            System.arraycopy(data, offset, buf, w, first)
            if (first < len) System.arraycopy(data, offset + first, buf, 0, len - first)
            count += len
            notEmpty.signalAll()
            return true
        } finally {
            lock.unlock()
        }
    }

    /** How many bytes are buffered and unread. */
    fun available(): Int = lock.withLockInt { count }

    /**
     * The stream NanoHTTPD reads from. Blocks until data or [close], and
     * returns -1 at end of stream so the chunked response terminates cleanly.
     */
    fun asInputStream(): InputStream = object : InputStream() {
        private val one = ByteArray(1)

        override fun read(): Int {
            val got = read(one, 0, 1)
            return if (got <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            lock.lock()
            try {
                while (count == 0 && !closed) {
                    try {
                        notEmpty.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                }
                if (count == 0) return -1
                val n = minOf(len, count)
                val first = minOf(n, capacity - head)
                System.arraycopy(buf, head, b, off, first)
                if (first < n) System.arraycopy(buf, 0, b, off + first, n - first)
                head += n
                if (head >= capacity) head -= capacity
                count -= n
                notFull.signalAll()
                return n
            } finally {
                lock.unlock()
            }
        }

        override fun available() = this@AudioRingBuffer.available()

        override fun close() {
            this@AudioRingBuffer.close()
        }
    }

    /**
     * Ends the stream and wakes any blocked reader.
     *
     * Called when the client is dropped or capture stops. Without the wakeup a
     * reader would sit on [notEmpty] until the server thread pool timed it out,
     * which is the failure this class is meant to prevent.
     */
    fun close() {
        lock.lock()
        try {
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun isClosed(): Boolean = lock.withLockInt { if (closed) 1 else 0 } == 1

    /** A tiny helper so the lock is released on every path, including throws. */
    private inline fun <T> ReentrantLock.withLockInt(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
