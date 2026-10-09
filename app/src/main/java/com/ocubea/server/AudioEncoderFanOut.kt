package com.ocubea.server

import android.util.Log

/**
 * Encoded audio, sharing the microphone the PCM path already has open.
 *
 * A second AudioRecord was the obvious design here and the wrong one: two
 * recorders on one phone fight, and whichever loses silences the PCM clients.
 * So this does not open anything. The manager's capture loop is the only
 * reader of the hardware, and it hands the same buffer to the PCM fan-out and
 * to this, which means a WAV client and an AAC client can be connected at once
 * and both work.
 *
 * One MediaCodec per codec id, shared by every client of that codec, for the same
 * reason there is one AudioRecord: encoding per client multiplies the cost by
 * the listener count on exactly the old phones this app exists for.
 *
 * Frame alignment is the fiddly part. AudioRecord hands back whatever the driver
 * chose, 4096 bytes or 700, while MediaCodec wants 960 samples. Whatever does not
 * complete a frame is carried into the next read. Writing the read straight into
 * the codec instead would either throw BufferOverflowException on a long read or
 * quietly truncate a short one, and the second of those sounds like it works.
 */
class AudioEncoderFanOut(
    private val sampleRate: Int,
    private val channels: Int
) {

    companion object {
        private const val TAG = "OcuBeaAudioFan"
    }

    /** Per-codec client lists, keyed by codec id. */
    private val clients = mutableMapOf<String, AudioFanOut>()
    private val lock = Any()
    private val encoders = mutableMapOf<String, AudioEncoder>()

    /**
     * True while the capture thread is inside MediaCodec.
     *
     * Read by the shutdown path to decide whether it is safe to stop a codec.
     * A plain flag rather than a lock, because holding a lock across the encode
     * would serialise every audio client behind the slowest one, and because
     * what is being prevented is a race that a flag plus a bounded wait already
     * handles.
     */
    private val inCapture = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Re-frame carry. A plain array, not a ByteArrayOutputStream: draining one
     * frame out of the middle of the stream needs read(), which
     * ByteArrayOutputStream does not have, and doing it with writeTo() copies
     * the entire carry -- so a partial frame left over would be prepended to
     * the next frame and the audio would drift. A linear buffer is the right
     * shape: [carryLen] is always a count of whole bytes waiting at offset 0,
     * and the only alignment that matters is whether it has reached
     * FRAME_BYTES.
     */
    private val carry = ByteArray(8 * 1024)
    /** Whole bytes waiting, always at the front: the live region is [0, carryLen). */
    private var carryLen = 0
    private val frameBytes = ByteArray(AudioEncoder.FRAME_BYTES)
    private val frameSamples = ShortArray(AudioEncoder.FRAME_SAMPLES)

    @Volatile private var clientCount = 0

    // Diagnostics for the "header only, no packets" bug. Bounded so a real
    // stream cannot flood logcat; both reset to 0 in removeClient.
    @Volatile private var feedCalls = 0
    @Volatile private var feedMisses = 0
    @Volatile private var packetsOut = 0
    @Volatile private var emptyOut = 0

    /** Counters for /status.json, so this is visible without logcat. */
    fun stats(): Map<String, Int> = mapOf(
        "clients" to clientCount,
        "feed_calls" to feedCalls,
        "feed_misses" to feedMisses,
        "packets_out" to packetsOut,
        "empty_out" to emptyOut,
        "carry_bytes" to carryBytes()
    )

    fun clients(): Int = clientCount

    /** Codec ids that currently have at least one client. */
    fun liveCodecs(): List<String> = synchronized(lock) {
        clients.filterValues { it.count() > 0 }.keys.toList()
    }

    /**
     * Registers a client for [codecId] and returns its ring to read from, or
     * null when this device cannot encode that. A null here must not be turned
     * into PCM by the caller: a client that asked for AAC and got WAV would be
     * decoding a lie.
     */
    /** The ring to read from, and the call that hands this client's sink back. */
    class Subscription(val ring: AudioRingBuffer, val release: () -> Unit)

    fun addClient(codecId: String, bitrate: Int): Subscription? = synchronized(lock) {
        if (!ensureEncoder(codecId, bitrate)) return null
        val ring = AudioRingBuffer()
        // The header first, before any packet: for Opus that is the OpusHead
        // page, without which no player decodes a single byte of what follows.
        val header = encoders[codecId]!!.streamHeader()
        if (header.isNotEmpty() && !ring.offer(header, 0, header.size)) return null
        val sink = AudioFanOut.Client(
            write = { buf, len -> ring.offer(buf, 0, len) },
            onDisconnect = { ring.close() }
        )
        val fan = clients.getOrPut(codecId) { AudioFanOut() }
        fan.add(sink)
        clientCount++
        Subscription(ring) { fan.remove(sink) }
    }

    fun removeClient(release: (() -> Unit)? = null) {
        val dying: List<AudioEncoder>
        synchronized(lock) {
            // Hand back THIS client's sink before any early return. Without it a
            // client that simply stopped listening stayed registered on the
            // fan-out forever: encoded.clients kept counting it while the ring it
            // wrote to was closed, so the measured symptom was an RTSP audio
            // session that answered 200 and then went silent.
            release?.invoke()
            clientCount--
            if (clientCount > 0) return
            // Announce first. MediaCodec is not thread-safe, and the capture
            // thread is normally inside dequeueOutputBuffer while this runs, so
            // stopping the codec from here throws IllegalStateException out of
            // the middle of a frame. The flag tells the capture thread to leave
            // the codec alone; the wait below is what makes stopping it safe.
            dying = encoders.values.toList()
            dying.forEach { it.markStopping() }
            clients.clear()
            encoders.clear()
            synchronized(carry) {
                carryLen = 0
            }
        }
        // Outside the lock: feed() takes carry after lock, so holding lock here
        // would be a lock-order inversion waiting to happen. The wait is
        // bounded because a capture thread that never comes back would
        // otherwise hold a codec open forever -- which is the leak this whole
        // path exists to close, not reproduce.
        val gaveUp = awaitCaptureIdle(dying)
        dying.forEach { enc ->
            if (gaveUp) {
                Log.w(TAG, "stopping ${enc.codecIdForLog()} while capture may still be in it")
            }
            enc.stop()
        }
    }

    /**
     * Gives the capture thread a bounded window to leave the codecs alone.
     *
     * The flag is volatile and set before this runs, so a capture thread that
     * is between frames sees it immediately. What cannot be forced is a thread
     * that is already inside MediaCodec, and that window is one output
     * dequeue -- a few milliseconds with the deadlines the encoder uses. Ten is
     * generous; anything longer means something is wrong that logging should
     * show rather than silently hide.
     */
    private fun awaitCaptureIdle(dying: List<AudioEncoder>): Boolean {
        val deadline = System.nanoTime() + 10_000_000L * 10
        while (System.nanoTime() < deadline) {
            if (!inCapture.get()) return false
            Thread.sleep(2)
        }
        return true
    }

    private fun ensureEncoder(codecId: String, bitrate: Int): Boolean {
        encoders[codecId]?.let { return true }
        val enc = AudioEncoder.forId(codecId) ?: return false
        return try {
            enc.start(sampleRate, channels, if (bitrate > 0) bitrate else 64_000)
            encoders[codecId] = enc
            true
        } catch (e: Exception) {
            // The probe said this works and it does not. Returning false here
            // makes the handler answer 501 with a reason, which is honest;
            // carrying on would stream silence under an AAC name.
            Log.w(TAG, "encoder $codecId refused to start", e)
            false
        }
    }

    /**
     * Called from the capture loop with freshly read PCM. Re-frames and
     * encodes it into every client that asked for [codecId].
     *
     * Runs on the capture thread, so it must not block. The ring buffers return
     * false instead of waiting, and MediaCodec is given a short deadline, so a
     * client that stops reading costs a dropped frame and not a stalled
     * microphone.
     */
    fun feed(codecId: String, pcm: ByteArray, length: Int) {
        // Set for the whole of the encode below and cleared in a finally, so
        // removeClient() can tell "the capture thread is inside MediaCodec right
        // now" from "the capture thread is not running at all". Without it the
        // shutdown path has to guess, and guessing wrong means either a race or
        // a codec that is never released.
        inCapture.set(true)
        try {
            feedCalls++
            feedLocked(codecId, pcm, length)
        } finally {
            inCapture.set(false)
        }
    }

    private fun feedLocked(codecId: String, pcm: ByteArray, length: Int) {
        val target = synchronized(lock) { clients[codecId] }
        if (target == null) {
            return
        }
        val enc = synchronized(lock) { encoders[codecId] }
        if (enc == null) {
            return
        }
        // Checked once per fed buffer, not per frame: the stopping thread sets
        // this while the capture thread is mid-frame, and the point is to leave
        // the codec untouched from the next buffer onwards so it can be shut
        // down cleanly by whoever set the flag.
        if (enc.isStopping()) {
            return
        }
        synchronized(carry) {
            append(pcm, length)
            while (pending() >= AudioEncoder.FRAME_BYTES) {
                drainFrame(frameBytes)
                for (i in 0 until AudioEncoder.FRAME_SAMPLES) {
                    val lo = frameBytes[i * 2].toInt() and 0xFF
                    val hi = frameBytes[i * 2 + 1].toInt() and 0xFF
                    frameSamples[i] = ((hi shl 8) or lo).toShort()
                }
                // Re-checked inside the loop: a multi-frame buffer can keep this
                // thread inside MediaCodec for long enough for the flag to land
                // partway through, and finishing the whole buffer would race the
                // shutdown for several frames.
                if (enc.isStopping()) {
                    carryLen = 0
                    return
                }
                val out = try {
                    enc.encodeFrame(frameSamples)
                } catch (e: Exception) {
                    Log.w(TAG, "encode $codecId failed", e)
                    ByteArray(0)
                }
                if (out.isNotEmpty()) {
                    packetsOut++
                    target.broadcast(out, out.size)
                } else {
                    // encodeFrame() returns empty whenever the codec buffers
                    // instead of emitting a frame, and an empty result is
                    // indistinguishable from a frame of silence unless it is
                    // counted. These two counters existed and were published in
                    // /status.json but nothing ever incremented emptyOut, so
                    // they read 0 forever and could never show a codec that had
                    // stopped producing frames. Counted here, where the frame
                    // that failed is still in scope.
                    emptyOut++
                }
            }
        }
    }

    // ── carry ring, all of it under the caller's hold on `carry` ──────────

    /**
     * Bytes held back because they do not add up to a whole [AudioEncoder.FRAME_BYTES]
     * block yet.
     *
     * Published because this is where audio can disappear silently: the capture
     * block is whatever `getMinBufferSize()` returned and the encoded path
     * consumes only whole frames, so a remainder accumulates and is never
     * flushed. A carry that grows without bound is audio that will never be
     * encoded; one that stays under FRAME_BYTES is the expected residue.
     */
    fun carryBytes(): Int = synchronized(carry) { carryLen }

    private fun pending(): Int = carryLen

    /**
     * Copies [length] PCM bytes in.
     *
     * The invariant is the whole point: the live region is always
     * [0, carryLen). One cursor, one count, so they cannot drift apart. An
     * earlier version tracked `carryPos` separately and tried to reset it with
     * `if (carryPos == carryLen)`, comparing a position to a count; that is
     * never true, so the count grew without bound and the first read after one
     * frame had been drained died with
     * ArrayIndexOutOfBoundsException: length=-7680 -- which killed the capture
     * loop on its first iteration and made the whole thing look like a dead
     * microphone rather than a bug in a buffer.
     */
    private fun append(pcm: ByteArray, length: Int) {
        if (length > carry.size) {
            // A single read larger than the whole carry: keep its tail, which is
            // the part that can still complete a frame. Dropping the head is
            // audible, but overflowing the carry is not an option.
            val keep = carry.size - (carry.size % AudioEncoder.FRAME_BYTES)
            System.arraycopy(pcm, length - keep, carry, 0, keep)
            carryLen = keep
            return
        }
        // Linear, not a wrapping ring: the live region is always
        // [0, carryLen), so there is exactly ONE cursor and it cannot drift
        // away from the count. The previous version tracked a separate
        // `carryPos` and reset it with `if (carryPos == carryLen)`, comparing a
        // position against a count -- which is never true, so `carryLen` grew
        // without bound and the next compaction ran off the end of the array
        // (ArrayIndexOutOfBoundsException: srcPos=7936). One cursor, one
        // invariant, no second index to get wrong.
        if (carryLen + length > carry.size) {
            val keep = carryLen - (carryLen % AudioEncoder.FRAME_BYTES)
            System.arraycopy(carry, carryLen - keep, carry, 0, keep)
            carryLen = keep
        }
        System.arraycopy(pcm, 0, carry, carryLen, length)
        carryLen += length
    }

    /** Takes exactly [FRAME_BYTES] out of the front. Caller checked the size. */
    private fun drainFrame(into: ByteArray) {
        System.arraycopy(carry, 0, into, 0, AudioEncoder.FRAME_BYTES)
        carryLen -= AudioEncoder.FRAME_BYTES
        if (carryLen == 0) carryLen = 0
    }
}
