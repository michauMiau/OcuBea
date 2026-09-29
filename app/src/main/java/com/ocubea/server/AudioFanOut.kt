package com.ocubea.server

import java.util.ArrayList

/**
 * The client fan-out, with no microphone and no Android imports, so the part
 * that caused an outage is testable off-device.
 *
 * Why this is separate: the bug it fixes was not visible in a build, a lint
 * run, or any test, because the code that held a monitor across a blocking
 * pipe write needed a real `AudioRecord` to reach. Extracting the fan-out
 * means the locking contract can be asserted instead of described.
 *
 * The contract, and the reason it exists:
 *
 * - **Never hold the monitor across a call into a client.** A client's write
 *   blocks when its pipe is full, and a client controls how fast it reads.
 *   Measured on the phone: 14 slow `/audio.wav` readers blocked
 *   `captureLoop` while it held the client list, and every other endpoint —
 *   `/status.json` included — returned `ConnectionReset` for 90 seconds. The
 *   same 14 connections to `/status.json` cost nothing, so the HTTP pool was
 *   not the limit; the monitor was. So: snapshot under the lock, write
 *   outside it.
 * - **A client that fails is dropped, not retried.** A write that returns
 *   false or throws removes the client for good. Retrying would spin on a
 *   client that can never accept data.
 * - **The snapshot may be stale and that is fine.** A client added after the
 *   copy waits for the next buffer, and one removed after it is written to
 *   once more — which fails harmlessly, because its pipe is already closed.
 */
class AudioFanOut {

    /** Notified once per client after it is gone, so the caller can count. */
    var onDropped: ((Client) -> Unit)? = null

    private val clients = ArrayList<Client>()

    fun add(client: Client) {
        synchronized(clients) { clients.add(client) }
    }

    fun count(): Int = synchronized(clients) { clients.size }

    fun remove(client: Client) {
        val removed = synchronized(clients) { clients.remove(client) }
        if (removed) notifyDropped(client)
    }

    fun removeAll(): List<Client> {
        val gone = synchronized(clients) {
            val copy = ArrayList(clients)
            clients.clear()
            copy
        }
        gone.forEach { notifyDropped(it) }
        return gone
    }

    /**
     * Hands [data] to every client and drops the ones that will not take it.
     *
     * The lock is taken once, for the copy, and released before the first
     * write. Asserting that here is the whole point of the class: see
     * AudioFanOutTest, which fails if the write loop moves back inside
     * `synchronized`.
     *
     * @return how many clients were dropped by this call.
     */
    fun broadcast(data: ByteArray, length: Int): Int {
        val targets: List<Client> = synchronized(clients) {
            // A plain copy, not a view: a live iteration would hold the
            // monitor, and this is the line the fix exists to move.
            ArrayList(clients)
        }
        if (targets.isEmpty()) return 0
        val dead = ArrayList<Client>(2)
        for (c in targets) {
            val ok = try {
                c.write(data, length)
            } catch (_: Throwable) {
                false
            }
            if (!ok) dead.add(c)
        }
        if (dead.isEmpty()) return 0
        synchronized(clients) { clients.removeAll(dead) }
        dead.forEach { notifyDropped(it) }
        return dead.size
    }

    private fun notifyDropped(client: Client) {
        try {
            client.onDisconnect()
        } catch (_: Throwable) {
            // A disconnect hook that throws must not take the fan-out with it.
        }
        onDropped?.invoke(client)
    }

    /** Per-client sink. Returning false (or throwing) from [write] drops it. */
    class Client(val write: (ByteArray, Int) -> Boolean, val onDisconnect: () -> Unit)
}
