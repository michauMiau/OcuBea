package com.ocubea.server

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
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
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        fun getMinBufferSize(): Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val activeClients = ArrayList<Client>()
    private val clients = AtomicInteger(0)
    private var audioRecord: AudioRecord? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ocubea-audio").apply { isDaemon = true }
    }
    @Volatile private var capturing = false

    /** Per-client sink. Returning false (or throwing) from [write] drops the client. */
    class Client(val write: (ByteArray, Int) -> Boolean, val onDisconnect: () -> Unit)

    fun canRecord(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun clientCount(): Int = clients.get()

    fun isCapturing(): Boolean = capturing

    /** Register a client and start shared capture if this is the first one. */
    fun addClient(client: Client) {
        ensureCapture()
        // WAV header first so the client can start decoding immediately
        try {
            val header = wavHeader(0xFFFFFFFFL)
            client.write(header, header.size)
        } catch (_: Exception) {}
        synchronized(activeClients) { activeClients.add(client) }
        clients.incrementAndGet()
    }

    fun removeClient(client: Client) {
        val removed = synchronized(activeClients) { activeClients.remove(client) }
        if (removed) dropClient(client, alreadyCounted = true)
    }

    private fun dropClient(client: Client, alreadyCounted: Boolean) {
        if (!alreadyCounted) clients.decrementAndGet()
        // updateAndGet is API 24. The compare-and-set loop below is the same
        // thing on API 23, and unlike decrementAndGet it cannot drive the
        // counter negative when a client is dropped twice.
        while (true) {
            val cur = clients.get()
            val next = if (cur > 0) cur - 1 else 0
            if (clients.compareAndSet(cur, next)) break
        }
        try { client.onDisconnect() } catch (_: Exception) {}
        if (clients.get() <= 0) stop()
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
                val dead = ArrayList<Client>()
                synchronized(activeClients) {
                    for (c in activeClients) {
                        try {
                            if (!c.write(buffer, read)) dead.add(c)
                        } catch (_: Exception) { dead.add(c) }
                    }
                    for (c in dead) activeClients.remove(c)
                }
                for (c in dead) dropClient(c, alreadyCounted = true)
            }
        } catch (_: Exception) {
            // recorder died — fall through to cleanup
        } finally {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            if (audioRecord === record) audioRecord = null
            // Disconnect everyone still waiting on the dead recorder
            val orphans = synchronized(activeClients) {
                val copy = ArrayList(activeClients)
                activeClients.clear()
                copy
            }
            for (c in orphans) {
                try { c.onDisconnect() } catch (_: Exception) {}
            }
            clients.set(0)
            capturing = false
        }
    }

    fun stop() {
        capturing = false
        val orphans = synchronized(activeClients) {
            val copy = ArrayList(activeClients)
            activeClients.clear()
            copy
        }
        for (c in orphans) {
            try { c.onDisconnect() } catch (_: Exception) {}
        }
        clients.set(0)
    }

    /** Standard 44-byte WAV header. Pass 0xFFFFFFFF for data size when streaming live. */
    private fun wavHeader(dataSize: Long): ByteArray {
        val total = dataSize + 36
        val out = java.io.ByteArrayOutputStream(44)
        fun w32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        val byteRate = SAMPLE_RATE * 2
        out.write("RIFF".toByteArray()); w32(total.toInt())
        out.write("WAVEfmt ".toByteArray())
        w32(16); w16(1); w16(1)
        w32(SAMPLE_RATE); w32(byteRate); w16(2); w16(16)
        out.write("data".toByteArray()); w32(dataSize.toInt())
        return out.toByteArray()
    }
}
