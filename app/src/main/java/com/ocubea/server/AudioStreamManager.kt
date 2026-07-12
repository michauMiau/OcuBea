package com.ocubea.server

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue

/**
 * Real-time audio capture and streaming via HTTP.
 * Uses AudioRecord to capture PCM 16-bit mono at 44100 Hz, writes WAV-formatted chunks continuously.
 */
class AudioStreamManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )

        // WAV header fields
        private const val RIFF_HEADER = "RIFF"
        private const val WAVE_FORMAT = "WAVEfmt "
        private const val DATA_CHUNK = "data"
    }

    @Volatile private var isRecording = false
    @Volatile private var audioRecord: AudioRecord? = null

    private val executor = Executors.newSingleThreadExecutor()

    /** Check if microphone permission is granted */
    fun canRecord(): Boolean {
        return context.checkSelfPermission(
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** Get current recording state */
    fun isStreaming(): Boolean = isRecording

    /** Stop audio capture and release resources */
    fun stop() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        println("Audio streaming stopped")
    }

    /** Start continuous WAV stream to the given output stream.
     *  Writes a single complete WAV header first, then appends data chunks as they arrive. */
    fun startStream(outputStream: OutputStream) {
        if (!canRecord()) {
            println("Audio streaming denied — RECORD_AUDIO permission not granted")
            return
        }

        isRecording = true
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE.coerceAtLeast(4096)
        )

        val record = audioRecord ?: run {
            isRecording = false
            return
        }

        try {
            record.startRecording()
            println("Audio streaming started: ${SAMPLE_RATE}Hz mono PCM16")
        } catch (e: Exception) {
            isRecording = false
            println("Failed to start audio recording: ${e.message}")
            return
        }

        executor.execute {
            val buffer = ShortArray(BUFFER_SIZE.coerceAtLeast(4096))
            var totalSamplesWritten = 0L

            try {
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    // Write WAV header every ~1 second of audio (~44100 samples @ 2 bytes/sample = 88200 bytes per chunk)
                    val totalBytesSoFar = totalSamplesWritten * 2
                    val headerSize = 44 // standard WAV header
                    val fileSize = (totalBytesSoFar + read * 2 + headerSize).toInt()

                    writeWavHeader(outputStream, fileSize, SAMPLE_RATE, 1)

                    // Write PCM samples as little-endian shorts
                    for (i in 0 until read) {
                        val sample = buffer[i]
                        outputStream.write(sample.toInt().toByte())
                        outputStream.write((sample.toInt() shr 8).toByte())
                    }
                    totalSamplesWritten += read

                    // Flush periodically to avoid buffering delays
                    if (totalBytesSoFar % (SAMPLE_RATE * 2) == 0L) {
                        try { outputStream.flush() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                // Client disconnected or stream closed — normal
            } finally {
                stop()
            }
        }
    }

    /** Write a standard 44-byte WAV header */
    private fun writeWavHeader(outputStream: OutputStream, fileSize: Int, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate * channels * 2 // PCM16 = 2 bytes per sample
        val blockAlign = channels * 2

        outputStream.write(RIFF_HEADER.toByteArray())                          // "RIFF"
        writeIntLE(outputStream, fileSize - 8)                                 // file size - 8
        outputStream.write("WAVE".toByteArray())                               // "WAVE"
        outputStream.write(WAVE_FORMAT.toByteArray())                           // "fmt " (with trailing space)
        writeIntLE(outputStream, 16)                                           // PCM format chunk size = 16
        writeShortLE(outputStream, 1)                                          // PCM format tag = 1 (uncompressed)
        writeShortLE(outputStream, channels.toShort())                         // number of channels
        writeIntLE(outputStream, sampleRate)                                   // sample rate
        writeIntLE(outputStream, byteRate)                                     // byte rate
        writeShortLE(outputStream, blockAlign.toShort())                       // block align
        writeShortLE(outputStream, 16)                                         // bits per sample = 16
        outputStream.write(DATA_CHUNK.toByteArray())                            // "data"
        val dataChunkSize = fileSize - 44                                      // remaining bytes after header
        writeIntLE(outputStream, dataChunkSize)                                // data chunk size
    }

    private fun writeIntLE(os: OutputStream, value: Int) {
        os.write(value and 0xFF)
        os.write((value shr 8) and 0xFF)
        os.write((value shr 16) and 0xFF)
        os.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(os: OutputStream, value: Short) {
        os.write(value.toInt() and 0xFF)
        os.write((value.toInt() shr 8) and 0xFF)
    }
}
