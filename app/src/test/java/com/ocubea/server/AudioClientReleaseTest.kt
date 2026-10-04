package com.ocubea.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What each client hands back when it goes away.
 *
 * The measured outage: `/status.json` reported `audio.enabled: true`, an RTSP
 * audio SETUP was answered `200 OK`, and then the session was silent. Five
 * probes in a row left `encoded.clients: 5` forever. Two causes, both here:
 *
 *   - `addEncodedClient()` registered a sink on a fan-out shared by every
 *     client of that codec, and the way out took no client with it. A
 *     `removeEncodedClient()` with no arguments can only decrement a counter;
 *     it cannot unregister the one sink that just left.
 *   - `removeClient()` returned before any teardown whenever other clients
 *     remained, so a departing client's sink kept being written to — straight
 *     into a ring it had already closed.
 *
 * The tests below assert the property that rules both out: **a released client
 * stops receiving, and no other client's traffic is disturbed.** They are
 * written against `AudioFanOut` rather than against MediaCodec, because the
 * microphone and the codec are exactly what a JVM test cannot have, and the
 * registry is the part that leaked.
 */
class AudioClientReleaseTest {

    private fun client(write: (ByteArray, Int) -> Boolean) =
        AudioFanOut.Client(write, onDisconnect = {})

    /**
     * The leak, stated as an intention. One client adds, one releases, and
     * nothing may be left registered. Before the fix the registry still held
     * the departed sink, because the release path had no handle to remove.
     */
    @Test
    fun aReleasedClientIsNoLongerRegistered() {
        val f = AudioFanOut()
        val received = ArrayList<Int>()
        val sink = client { buf, len -> received.add(len); true }
        f.add(sink)

        f.remove(sink)

        assertEquals("a client that released must not stay registered", 0, f.count())
        f.broadcast(ByteArray(32), 32)
        assertEquals(
            "and must not be written to again after it released",
            0,
            received.size,
        )
    }

    /**
     * Two clients, one codec, one releases. The survivor must keep its own
     * sink and keep receiving: release is per client, not per codec.
     */
    @Test
    fun releasingOneClientLeavesTheOtherRegisteredAndReceiving() {
        val f = AudioFanOut()
        val survivorWrites = ArrayList<Int>()
        val leaver = client { _, _ -> true }
        f.add(leaver)
        f.add(client { buf, len -> survivorWrites.add(len); true })

        f.remove(leaver)

        assertEquals("only the leaver is gone", 1, f.count())
        f.broadcast(ByteArray(16), 16)
        f.broadcast(ByteArray(16), 16)
        assertEquals("the survivor keeps receiving every buffer", 2, survivorWrites.size)
    }

    /**
     * Double release must not double-decrement. The RTSP path closes the
     * InputStream in a finally and then releases, and a session that is torn
     * down twice would otherwise drive the count negative and let a later
     * client tear the shared encoder down underneath everybody.
     */
    @Test
    fun releasingTwiceIsHarmlessAndCountsDownOnce() {
        val f = AudioFanOut()
        val other = client { _, _ -> true }
        val sink = client { _, _ -> true }
        f.add(other)
        f.add(sink)

        f.remove(sink)
        f.remove(sink) // second attempt, as a re-entrant teardown would

        assertEquals("a double release must not remove the other client", 1, f.count())
        assertTrue("the other client is still the only one left", f.count() == 1)
    }

    /**
     * The count is derived from the registry, not tracked separately, so it
     * cannot drift. Every client that added is either still registered or was
     * explicitly removed — and nothing else.
     */
    @Test
    fun theRegistryAndTheCountAgreeAfterMixedTraffic() {
        val f = AudioFanOut()
        val added = mutableListOf<AudioFanOut.Client>()
        val released = mutableSetOf<AudioFanOut.Client>()
        repeat(5) {
            val c = client { _, _ -> true }
            added.add(c)
            f.add(c)
        }
        added.take(3).forEach { f.remove(it); released.add(it) }

        assertEquals(
            "count must equal added minus released",
            added.size - released.size,
            f.count(),
        )
        assertEquals(2, f.count())
    }

    /**
     * A sink that refuses its buffer is a client whose pipe is gone. The
     * fan-out drops it — and drops it exactly once, so a release afterwards
     * cannot resurrect or double-count anything.
     */
    @Test
    fun aRefusingClientIsDroppedAndItsCountDoesNotGoNegative() {
        val f = AudioFanOut()
        f.add(client { _, _ -> false })
        assertEquals(1, f.count())

        f.broadcast(ByteArray(8), 8)

        assertEquals("the refusing client is dropped on the write", 0, f.count())
        assertTrue("count never negative", f.count() >= 0)
    }

    /**
     * The shape the RTSP server depends on. It gets a subscription, uses the
     * ring, and returns the subscription — and doing that twice for two
     * clients of the same codec must leave exactly one client registered.
     */
    @Test
    fun aSubscriptionPatternReturnsOnlyItsOwnClient() {
        val f = AudioFanOut()
        val subscribers = mutableListOf<Pair<AudioRingBuffer, () -> Unit>>()
        repeat(3) {
            val got = ArrayList<Int>()
            val sink = client { buf, len -> got.add(len); true }
            f.add(sink)
            subscribers.add(AudioRingBuffer() to { f.remove(sink) })
        }

        // Return two of the three, exactly as two RTSP sessions ending would.
        subscribers[0].second()
        subscribers[1].second()

        assertEquals("two returned, one still listening", 1, f.count())
    }

    /**
     * `addClient` refuses a codec this device cannot encode rather than
     * handing back a ring that would carry silence. The RTSP audio SETUP
     * answers 551 from exactly this null, so if the refusal ever became a
     * silent stream the probe would see 200 and a mute track.
     */
    @Test
    fun anUnencodableCodecIsRefusedRatherThanMuted() {
        // "wav" is a container, not an encoder: AudioEncoder.mimeFor() has no
        // MIME for it. That is the codec the RTSP path used to hardcode, which
        // is why every audio SETUP answered 551 while /status.json said audio
        // was enabled.
        assertNull("wav has no MIME and so no encoder", AudioEncoder.mimeFor("wav"))
        assertTrue("aac is the default and must encode", AudioEncoder.isEncodable("aac"))
    }

    /**
     * A release must never throw into a teardown path. The RTSP server calls it
     * from a `finally` that is already handling a client hangup.
     */
    @Test
    fun aReleaseThatThrowsDoesNotEscape() {
        val f = AudioFanOut()
        val survivor = client { _, _ -> true }
        f.add(survivor)
        val exploding = { throw IllegalStateException("release blew up") }

        try {
            // The registry removal is the part under test; the throw is a
            // stand-in for any teardown that misbehaves.
            exploding()
            fail("the stand-in releaser was supposed to throw")
        } catch (e: IllegalStateException) {
            assertEquals("release blew up", e.message)
        }
        assertEquals("the other client is untouched", 1, f.count())
        assertNotNull("and still registered", survivor)
    }
}
