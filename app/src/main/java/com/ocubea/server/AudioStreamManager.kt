package com.ocubea.server

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared microphone capture: ONE AudioRecord instance fans out PCM to any number
 * of connected clients, so a second viewer never fights the first for the mic.
 *
 * Each client receives a proper streaming WAV — 44-byte header with sizes set to
 * 0xFFFFFFFF (the standard "length unknown" marker for endless streams), sent once
 * at connect rather than per chunk.
 */
class AudioStreamManager(private val context: Context) {

    companion object {
        /** WAV size marker for a stream whose length is not known. */
        const val LIVE_SIZE = 0xFFFFFFFFL

        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        fun getMinBufferSize(): Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val activeClients = AudioFanOut()
    private val clients = AtomicInteger(0)
    private var audioRecord: AudioRecord? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ocubea-audio").apply { isDaemon = true }
    }
    @Volatile private var capturing = false

    /**
     * The encoded fan-out, attached by [attachEncoder] when a client asks for
     * a compressed codec. Null means nobody has: the PCM path does not need it
     * and building a MediaCodec for a WAV-only session would be waste.
     */
    @Volatile private var encoded: AudioEncoderFanOut? = null

    /** Codec ids with at least one live encoded client. */
    fun encodedCodecIds(): List<String> = encoded?.liveCodecs() ?: emptyList()

    /**
     * Adds a compressed client. Returns the ring to stream from, or null when
     * the device cannot encode [codecId] -- the caller must then answer 501
     * rather than fall back to PCM.
     */
    /**
     * One encoded-audio client: the ring to read, and the call that returns it.
     *
     * The releaser travels with the ring because ownership has to be per client,
     * not per codec. The fan-out is shared by every client of a given codec, so
     * a `removeEncodedClient()` with no arguments could only ever decrement a
     * counter -- it could not unregister the one sink that had gone away, which
     * is where the leak was.
     */
    data class EncodedSubscription(
        val ring: AudioRingBuffer,
        private val fanOut: AudioEncoderFanOut,
        private val releaseSink: () -> Unit,
    ) {
        fun release() = runCatching { fanOut.removeClient(releaseSink) }.let { }
    }

    fun addEncodedClient(codecId: String, bitrate: Int): EncodedSubscription? {
        val fanOut = synchronized(this) {
            // CHANNEL_CONFIG is AudioFormat.CHANNEL_IN_MONO (16), which is a
            // bitmask, not a channel count. MediaCodec wants the count: 1.
            encoded ?: AudioEncoderFanOut(SAMPLE_RATE, 1).also { encoded = it }
        }
        val sub = fanOut.addClient(codecId, bitrate) ?: return null
        // The recorder has to be running for feed() to ever be called, and it
        // only starts for PCM clients. This is the same ordering rule as
        // addClient: register first, then touch the hardware.
        ensureCapture()
        return EncodedSubscription(sub.ring, fanOut, sub.release)
    }

    fun removeEncodedClient() {
        encoded?.removeClient()
    }

    /**
     * Encoder counters, empty when nothing has ever been encoded.
     *
     * Exposed so an empty stream can be diagnosed from outside. "feed_misses"
     * climbing means the capture loop never handed PCM to the encoder;
     * "packets_out" staying at zero while it climbs means the codec itself is
     * silent. Those are completely different bugs and neither is visible from
     * the outside without these numbers.
     */
    fun encodedStats(): Map<String, Int> = encoded?.stats() ?: emptyMap()

    /** Per-client sink. Returning false (or throwing) from [write] drops it. */
    class Client(val write: (ByteArray, Int) -> Boolean, val onDisconnect: () -> Unit) {
        // AudioFanOut owns the same shape. This alias keeps call sites in
        // StreamServer constructing AudioStreamManager.Client, which is what
        // the public surface of this class has always been.
        fun asFanOutClient(): AudioFanOut.Client = AudioFanOut.Client(write, onDisconnect)
    }

    fun canRecord(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun clientCount(): Int = clients.get()

    fun isCapturing(): Boolean = capturing

    /** Register a client and start shared capture if this is the first one. */
    fun addClient(client: Client) {
        // Register BEFORE starting capture. `ensureCapture` opens an AudioRecord,
        // and if the client is then rejected the counter falls back to zero and
        // `stop()` tears the recorder down again -- measured on the phone as an
        // AudioRecord created and destroyed roughly 15 times a second when
        // clients that cannot keep up kept arriving. That thrash is far more
        // expensive than the rejection it was reacting to. Registering first
        // also means the WAV header goes to a client that is already on the
        // list, so the first captured buffer cannot outrun it.
        val fanout = client.asFanOutClient()
        activeClients.add(fanout)
        clients.incrementAndGet()
        try {
            ensureCapture()
            // WAV header first so the client can start decoding immediately
            val header = wavHeader(0xFFFFFFFFL)
            if (!fanout.write(header, header.size)) {
                // The client took no bytes at all: it is already gone, and
                // leaving it registered would keep the recorder alive for
                // nothing.
                removeClient(client)
            }
        } catch (e: Exception) {
            removeClient(client)
            throw e
        }
    }

    fun removeClient(client: Client) {
        activeClients.remove(client.asFanOutClient())
        decrementClients()
    }

    private fun decrementClients() {
        // updateAndGet is API 24. The compare-and-set loop below is the same
        // thing on API 23, and unlike decrementAndGet it cannot drive the
        // counter negative when a client is dropped twice.
        while (true) {
            val cur = clients.get()
            val next = if (cur > 0) cur - 1 else 0
            if (clients.compareAndSet(cur, next)) break
        }
        // Same rule as the capture loop: a session that is encoded-only has no
        // PCM clients, and stopping the recorder there would silence the
        // encoded stream too.
        if (clients.get() <= 0 && encodedCodecIds().isEmpty()) stop()
    }

    // canRecord() below checks the permission and throws SecurityException if
    // it is not held, which is exactly the handling lint asks for. The check is
    // a separate function, so lint cannot see the guard and reports
    // MissingPermission on the AudioRecord constructor.
    @SuppressLint("MissingPermission")
    private fun ensureCapture() {
        if (capturing) return
        if (!canRecord()) throw SecurityException("RECORD_AUDIO permission not granted")

        val bufSize = getMinBufferSize().coerceAtLeast(4096)
        // The capture block size decides everything downstream, and nothing
        // states what it actually is: `getMinBufferSize()` returns a
        // device-dependent value the app never learns, and the encoded path
        // re-blocks those bytes into fixed FRAME_SAMPLES regardless. Logged so
        // the block sizes can be compared against each other instead of
        // assumed equal.
        Log.i(
            "OcuBeaAudio",
            "capture bufSize=$bufSize B (${bufSize / 2} samples, " +
                "${bufSize * 1000 / (SAMPLE_RATE * 2)} ms) at $SAMPLE_RATE Hz"
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            audioRecord = null
            throw IllegalStateException("AudioRecord could not start: ${e.message}")
        }
        capturing = true
        executor.execute { captureLoop(record, bufSize) }
    }

    private fun captureLoop(record: AudioRecord, bufSize: Int) {
        val buffer = ByteArray(bufSize)
        try {
            while (capturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                // Snapshot the client list under the lock, then write OUTSIDE
                // it. Holding a monitor across a blocking pipe write is what
                // took the whole HTTP server down: measured on the phone, 14
                // slow /audio.wav readers filled the 64 KB pipe, `write`
                // blocked while the client list was held, and every other
                // endpoint — /status.json included — returned
                // ConnectionReset for 90 s, recovering only when the readers
                // went away. The same 14 connections to /status.json cost
                // nothing, so the HTTP pool was not the limit; the monitor was.
                //
                // The fan-out takes the client list under its own lock and
                // writes with the lock released; see AudioFanOut for the
                // measurement that forced that split. Here it is one call.
                val dropped = activeClients.broadcast(buffer, read)
                // The encoded path gets the same bytes, from the same read, so
                // a WAV client and an AAC client work at once without either
                // opening a second microphone -- two AudioRecords on one phone
                // fight, and the loser silences the other.
                for (codecId in encodedCodecIds()) {
                    encoded?.feed(codecId, buffer, read)
                }
                if (dropped > 0) {
                    while (true) {
                        val cur = clients.get()
                        val next = if (cur > dropped) cur - dropped else 0
                        if (clients.compareAndSet(cur, next)) break
                    }
                    // Only the PCM count may stop the recorder. An encoded-only
                    // session leaves clients.get() at zero, so testing it alone
                    // tore down the capture loop on the very first read and
                    // every stream carried nothing after its header.
                    if (clients.get() <= 0 && encodedCodecIds().isEmpty()) stop()
                }
            }
        } catch (e: Exception) {
            // Logged, not swallowed: the first version had a bare
            // `catch (_: Exception)` here and the loop died silently on its
            // first read, which looks exactly like a dead microphone.
            android.util.Log.w("OcuBeaAudio", "capture loop died", e)
        } finally {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            if (audioRecord === record) audioRecord = null
            // Disconnect everyone still waiting on the dead recorder
            activeClients.removeAll()
            clients.set(0)
            capturing = false
        }
    }

    fun stop() {
        if (capturing) {
            Log.i("OcuBeaAudio", "stop() from ${Thread.currentThread().stackTrace.drop(1).take(4).joinToString(" <- ")}")
        }
        capturing = false
        activeClients.removeAll()
        clients.set(0)
    }

    /** Standard 44-byte WAV header. Pass 0xFFFFFFFF for data size when streaming live. */
    private fun wavHeader(dataSize: Long): ByteArray {
        // The live marker is 0xFFFFFFFF. Adding 36 to it overflows the field:
        // (0xFFFFFFFF + 36).toInt() is 35, so the RIFF size claimed a 35-byte
        // file inside a 44-byte header. A live stream has no known length, so
        // both size fields keep the marker rather than being computed from it.
        val total = if (dataSize == LIVE_SIZE) LIVE_SIZE else dataSize + 36
        val out = java.io.ByteArrayOutputStream(44)
        fun w32(v: Long) {
            out.write(v.toInt() and 0xFF); out.write((v.toInt() shr 8) and 0xFF)
            out.write((v.toInt() shr 16) and 0xFF); out.write((v.toInt() shr 24) and 0xFF)
        }
        fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        val byteRate = SAMPLE_RATE * 2
        out.write("RIFF".toByteArray()); w32(total)
        out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(1)
        w32(SAMPLE_RATE.toLong()); w32(byteRate.toLong()); w16(2); w16(16)
        out.write("data".toByteArray()); w32(dataSize)
        return out.toByteArray()
    }
}
