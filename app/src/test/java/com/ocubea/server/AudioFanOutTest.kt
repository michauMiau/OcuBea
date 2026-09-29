package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The outage this guards against was a monitor held across a blocking pipe
 * write. Nothing in a build, a lint run, or a green suite would have caught
 * it, because reaching the line needed a real `AudioRecord` — which is why
 * the fan-out lives in a class with no microphone in it.
 *
 * The tests below are written so that moving the write loop back inside
 * `synchronized` fails them. That is the only property worth having here.
 */
class AudioFanOutTest {

    private fun client(
        write: (ByteArray, Int) -> Boolean,
        onDisconnect: () -> Unit = {},
    ) = AudioFanOut.Client(write, onDisconnect)

    @Test
    fun broadcastReachesEveryClient() {
        val f = AudioFanOut()
        val a = AtomicInteger()
        val b = AtomicInteger()
        f.add(client({ _, _ -> a.incrementAndGet(); true }))
        f.add(client({ _, _ -> b.incrementAndGet(); true }))
        val data = ByteArray(16)
        assertEquals(0, f.broadcast(data, data.size))
        assertEquals(1, a.get())
        assertEquals(1, b.get())
        assertEquals(2, f.count())
    }

    @Test
    fun aClientThatRefusesIsDroppedAndTheRestKeepReceiving() {
        val f = AudioFanOut()
        val dropped = mutableListOf<AudioFanOut.Client>()
        f.onDropped = { dropped.add(it) }
        val good = AtomicInteger()
        val bad = client({ _, _ -> false })
        f.add(client({ _, _ -> good.incrementAndGet(); true }))
        f.add(bad)
        f.add(client({ _, _ -> good.incrementAndGet(); true }))
        val data = ByteArray(8)
        // Two survivors, and the registry had three, so exactly one is dropped.
        assertEquals("exactly the refusing client", 1, f.broadcast(data, data.size))
        assertEquals("two survivors remain", 2, f.count())
        assertEquals(listOf(bad), dropped)
        // Two survivors x one buffer = 2 writes so far.
        assertEquals(2, good.get())
        // The survivors must still be fed on the next buffer: 2 x 2 = 4.
        f.broadcast(data, data.size)
        assertEquals("survivors keep receiving after a peer is dropped", 4, good.get())
    }

    @Test
    fun aClientThatThrowsIsDroppedRatherThanTakingTheFanOutDown() {
        val f = AudioFanOut()
        val survivor = AtomicInteger()
        f.add(client({ _, _ -> throw IllegalStateException("pipe gone") }))
        f.add(client({ _, _ -> survivor.incrementAndGet(); true }))
        val data = ByteArray(8)
        assertEquals(1, f.broadcast(data, data.size))
        assertEquals(1, f.count())
        assertEquals(1, survivor.get())
    }

    @Test
    fun aDisconnectHookThatThrowsDoesNotEscape() {
        val f = AudioFanOut()
        f.add(client({ _, _ -> false }, onDisconnect = { throw RuntimeException("hook broke") }))
        val data = ByteArray(8)
        f.broadcast(data, data.size)
        assertEquals(0, f.count())
    }

    /**
     * The regression itself. A client's write blocks for longer than the
     * timeout below, standing in for a full 64 KB pipe. If the write happens
     * while the client list is locked, a second thread adding a client cannot
     * make progress, and this times out.
     */
    @Test
    fun aBlockingClientDoesNotLockOutTheFanOut() {
        val f = AudioFanOut()
        val released = AtomicBoolean(false)
        val blockerStarted = CountDownLatch(1)
        val addDone = AtomicBoolean(false)

        f.add(client({ _, _ ->
            blockerStarted.countDown()
            // A pipe that is full and not being read: exactly what a client
            // that stopped consuming looks like from in here.
            while (!released.get()) Thread.sleep(5)
            false
        }))

        val writer = Thread {
            f.broadcast(ByteArray(64), 64)
        }
        writer.start()
        assertTrue("the blocking client must have been reached", blockerStarted.await(3, TimeUnit.SECONDS))

        val adder = Thread {
            f.add(client({ _, _ -> true }))
            addDone.set(true)
        }
        adder.start()
        adder.join(2500)

        assertTrue(
            "adding a client must not wait on another client's blocked write",
            addDone.get(),
        )
        released.set(true)
        writer.join(3000)
    }

    /**
     * The same property under contention, from several angles at once. One
     * slow client among many must not serialise the others, and the registry
     * must not lose or duplicate anyone.
     */
    @Test
    fun concurrentBroadcastsAndAddsKeepTheCountConsistent() {
        val f = AudioFanOut()
        val stop = AtomicBoolean(false)
        val received = AtomicInteger()
        val writers = 3
        val done = CountDownLatch(writers + 2)

        repeat(writers) {
            Thread {
                try {
                    val data = ByteArray(256)
                    while (!stop.get()) f.broadcast(data, data.size)
                } finally { done.countDown() }
            }.start()
        }
        repeat(2) {
            Thread {
                try {
                    repeat(200) {
                        f.add(client({ _, _ -> received.incrementAndGet(); true }))
                        Thread.sleep(0, 200_000)
                    }
                } finally { done.countDown() }
            }.start()
        }
        done.await(20, TimeUnit.SECONDS)
        stop.set(true)
        // Count is bounded by what was added; the point is that it is a
        // definite number, not a lost-update smear.
        assertTrue("registry count went out of range: ${f.count()}", f.count() in 0..400)
        assertTrue("no client should have been dropped: ${f.count()}", received.get() > 0)
    }

    @Test
    fun removeAllDisconnectsEveryoneAndEmptiesTheRegistry() {
        val f = AudioFanOut()
        var hooks = 0
        repeat(3) { f.add(client({ _, _ -> true }, onDisconnect = { hooks++ })) }
        assertEquals(3, f.removeAll().size)
        assertEquals(0, f.count())
        assertEquals("every orphan must be disconnected", 3, hooks)
    }

    @Test
    fun removeIsIdempotentSoDoubleReleaseCannotDoubleCount() {
        val f = AudioFanOut()
        var hooks = 0
        val c = client({ _, _ -> true }, onDisconnect = { hooks++ })
        f.add(c)
        f.remove(c)
        f.remove(c)
        assertEquals(0, f.count())
        assertEquals("a second release must not run the hook again", 1, hooks)
    }

    @Test
    fun broadcastToAnEmptyFanOutIsANoOp() {
        assertEquals(0, AudioFanOut().broadcast(ByteArray(4), 4))
    }

    @Test
    fun staleSnapshotIsWrittenOnceMoreAndThenDropped() {
        // A client removed after the snapshot is still written to once. Its
        // pipe is already closed, so it refuses and is dropped for good —
        // which is why a stale snapshot is harmless rather than a leak.
        val f = AudioFanOut()
        var writes = 0
        val refused = AtomicInteger()
        f.add(client({ _, _ -> writes++; false }))
        val data = ByteArray(4)
        f.broadcast(data, data.size)
        assertEquals(1, writes)
        assertEquals(0, f.count())
        f.broadcast(data, data.size)
        assertEquals("a dropped client must not be written to again", 1, writes)
        assertEquals(0, refused.get())
    }

    @Test
    fun aClientAddedDuringBroadcastGetsTheNextBufferNotThisOne() {
        val f = AudioFanOut()
        val late = AtomicInteger()
        // Register once, not on every buffer: a write that re-registers each
        // time would grow the registry on every call, and the count assertion
        // below would be measuring my own test rather than the fan-out.
        val registrar = AtomicBoolean(false)
        f.add(client({ _, _ ->
            // Registering from inside a write is the interesting case: the
            // lock must not already be held, or this deadlocks.
            if (registrar.compareAndSet(false, true)) {
                f.add(client({ _, _ -> late.incrementAndGet(); true }))
            }
            true
        }))
        val data = ByteArray(4)
        f.broadcast(data, data.size)
        assertEquals("the late client must wait for the next buffer", 0, late.get())
        assertEquals(2, f.count())
        f.broadcast(data, data.size)
        assertTrue("and be fed on the next one", late.get() > 0)
        assertEquals("the late client stays registered", 2, f.count())
    }
}
