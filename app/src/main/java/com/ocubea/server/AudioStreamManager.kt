package com.ocubea.server

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared microphone capture: ONE AudioRecord instance fans out PCM to any number
 * of connected clients. Each client gets a proper streaming WAV (header with
 * unknown length 0xFFFFFFFF — standard for endless streams).
 */
class AudioStreamManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        fun getMinBufferSize(): Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val clients = AtomicInteger(0)
    private var audioRecord: AudioRecord? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var capturing = false

    /** Per-client sink; onDisconnect is invoked on the capture thread when the pipe breaks. */
    class Client(val write: (ByteArray, Int) -> Boolean, val onDisconnect: () -> Unit)

    fun canRecord(): Boolean = context.checkSelfPermission(
        android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun clientCount(): Int = clients.get()

    /** Register a client; starts shared capture if this is the first one. */
    fun addClient(client: Client) {
        ensureCapture()
        clients.incrementAndGet()
        synchronized(activeClients) { activeClients.add(client) }
        // immediately send header to the new client
        try {
            val header = wavHeader(0xFFFFFFFFL)
            client.write(header, header.size)
        } catch (_: Exception) {}
    }

    fun removeClient(client: Client) {
        synchronized(activeClients) { activeClients.remove(client) }
        if (clients.decrementAndGet() <= 0) stop()
    }

    private val activeClients = ArrayList<Client>()

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
        record.startRecording()
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
                    activeClients.removeAll(dead)
                }
                for (c in dead) {
                    clients.decrementAndGet()
                    try { c.onDisconnect() } catch (_: Exception) {}
                }
            }
        } finally {
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            if (audioRecord === record) audioRecord = null
        }
    }

    fun stop() {
        capturing = false
    }

    /** Standard 44-byte WAV header. Pass 0xFFFFFFFF for data size when streaming live. */
    private fun wavHeader(dataSize: Long): ByteArray {
        val total = dataSize + 36
        val out = java.io.ByteArrayOutputStream(44)
        fun w32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
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
