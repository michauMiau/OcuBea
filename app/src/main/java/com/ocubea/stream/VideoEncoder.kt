package com.ocubea.stream

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Video encoder using MediaCodec API for H.264 encoding with software fallback.
 */
class VideoEncoder {

    data class EncodingConfig(
        val width: Int = 1920,
        val height: Int = 1080,
        val frameRate: Int = 30,
        val bitrateKbps: Int = 5000,
        val codec: CodecType = CodecType.H264
    )

    enum class CodecType { H264 }

    private var mediaCodec: MediaCodec? = null
    private val inputBuffers: Array<ByteBuffer> by lazy { emptyArray() }
    private val outputBuffers: Array<ByteBuffer> by lazy { emptyArray() }
    private val codecLock = Object()
    private var isRunning = false

    /** Start the encoder */
    fun start(config: EncodingConfig): Boolean {
        return try {
            val mime = "video/avc" // H.264
            val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000) // convert kbps to bps
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10) // I-frame every 10s
            }

            val codecName = getCodecForMime(mime)
            if (codecName != null) {
                println("Using hardware codec: $codecName")
            } else {
                println("No specific codec found, using default decoder for $mime")
            }

            mediaCodec = MediaCodec.createByCodecName(codecName ?: "OMX.google.h264.encoder")
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // Start the encoder
            mediaCodec?.start()
            isRunning = true
            
            println("Video encoder started with config: ${config.width}x${config.height}, ${config.frameRate}fps, ${config.bitrateKbps}kbps")
            true
        } catch (e: Exception) {
            println("Failed to start video encoder: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /** Encode a single frame */
    fun encodeFrame(inputData: ByteArray): List<ByteArray> {
        val encodedFrames = mutableListOf<ByteArray>()
        
        try {
            mediaCodec?.let { codec ->
                // Get input buffer (if available)
                val inputBufferIndex = codec.dequeueInputBuffer(10000)
                if (inputBufferIndex >= 0) {
                    // Copy data to input buffer
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return@let
                    inputBuffer.clear()
                    
                    // Ensure we don't exceed buffer size
                    val availableSpace = inputBuffer.remaining()
                    val copyLength = minOf(availableSpace, inputData.size)
                    inputBuffer.put(inputData, 0, copyLength)
                    inputBuffer.flip()
                    
                    // Send to encoder with presentation timestamp (PTS)
                    val ptsUs = System.currentTimeMillis() * 1000 // convert ms to us
                    codec.queueInputBuffer(inputBufferIndex, 0, copyLength, ptsUs, 0)
                }

                // Process output buffers
                val bufferInfo = MediaCodec.BufferInfo()
                
                while (true) {
                    // Dequeue output buffer with timeout
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 16_000) // 16ms timeout
                    
                    when (outputBufferIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            outputBuffers.clear()
                            // Get new output buffers from codec
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            println("Output format changed: ${format.mimeType} - ${format.width}x${format.height}")
                        }
                        else -> if (outputBufferIndex >= 0) {
                            // Get output buffer content as byte array
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                            
                            // Create a new byte array for the frame data
                            val frameData = ByteArray(bufferInfo.size)
                            
                            // Copy from ByteBuffer to byte array using get(byte[])
                            outputBuffer.position(bufferInfo.offset + bufferInfo.flags)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(frameData, 0, bufferInfo.size)
                            
                            encodedFrames.add(frameData)
                            
                            // Release the output buffer
                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                    
                    // Check if we've processed enough frames or timed out
                    if (bufferInfo.presentationTimeUs == 0L && outputBufferIndex < 0) {
                        break // No more data to process
                    }
                }
            }
        } catch (e: Exception) {
            println("Error encoding frame: ${e.message}")
            e.printStackTrace()
        }
        
        return encodedFrames
    }

    /** Stop the encoder */
    fun stop() {
        try {
            mediaCodec?.let { codec ->
                // Signal end of stream
                codec.signalEndOfInputStream()
                
                // Wait for remaining output
                val bufferInfo = MediaCodec.BufferInfo()
                while (true) {
                    val index = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10s timeout
                    
                    if (index >= 0) {
                        codec.releaseOutputBuffer(index, false)
                        
                        // Stop when we receive EOS or buffer is empty
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || 
                            bufferInfo.size == 0) break
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break // No more output available
                    }
                }
                
                codec.stop()
            }
            
            mediaCodec?.release()
            mediaCodec = null
            
            isRunning = false
            println("Video encoder stopped")
        } catch (e: Exception) {
            println("Error stopping encoder: ${e.message}")
            e.printStackTrace()
        }
    }

    /** Get available codecs for a given mime type */
    private fun getCodecForMime(mime: String): String? {
        val codecCount = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.size
        
        // Iterate through available codecs
        for (i in 0 until codecCount) {
            val codecInfo = MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfoAt(i) ?: continue
            
            try {
                if (mime in codecInfo.supportedTypes) {
                    return codecInfo.name
                }
            } catch (_: Exception) {}
        }
        
        return null
    }

    /** Check if the encoder is currently running */
    fun isEncoderRunning(): Boolean = isRunning
}
