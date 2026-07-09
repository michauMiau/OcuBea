package com.ocubea.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Hardware video encoder using MediaCodec.
 * Supports H.264 (AVC) and H.265 (HEVC) encoding.
 */
class VideoEncoder {
    
    private var mediaCodec: MediaCodec? = null
    private val outputBuffers = mutableListOf<ByteArray>()
    
    data class EncodingConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val frameRate: Int = 30,
        val bitrateKbps: Int = 4000,
        val codec: CodecType = CodecType.H264,
        val iFrameInterval: Int = 1 // Every second (at 30fps)
    ) {
        enum class CodecType { H264, H265 }
    }
    
    /** Start encoding with given config */
    fun start(config: EncodingConfig): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat(
                when (config.codec) {
                    EncodingConfig.CodecType.H264 -> "video/avc"
                    EncodingConfig.CodecType.H265 -> "video/hevc"
                },
                config.width,
                config.height
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000) // kbps to bps
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
            }
            
            mediaCodec = MediaCodec.createEncoderByType(
                when (config.codec) {
                    EncodingConfig.CodecType.H264 -> "video/avc"
                    EncodingConfig.CodecType.H265 -> "video/hevc"
                }
            )
            mediaCodec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                
                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val buffer = ByteBuffer.allocate(info.size)
                    codec.getOutputBuffer(index)?.get(buffer)
                    outputBuffers.add(buffer.array())
                    
                    // Reset for next use
                    buffer.clear()
                }
                
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, null)
            
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /** Feed raw frame data to encoder */
    fun encodeFrame(inputData: ByteArray): List<ByteArray> {
        val codec = mediaCodec ?: return emptyList()
        
        // Get input buffer, fill with data, queue it
        val inputIndex = codec.dequeueInputBuffer(1000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex)
            inputData.copyInto(inputBuffer.array())
            codec.queueInputBuffer(inputIndex, 0, inputData.size, System.currentTimeMillis(), 0)
        }
        
        // Collect output buffers
        val outputBuffers = mutableListOf<ByteArray>()
        var bufferInfo = MediaCodec.BufferInfo()
        
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 1000)
            
            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> break
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {} // Shouldn't happen after start
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {} // Format changed
                else -> {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = ByteBuffer.allocate(bufferInfo.size)
                        codec.getOutputBuffer(outputIndex)?.get(outputBuffer)
                        outputBuffers.add(outputBuffer.array())
                        
                        codec.releaseOutputBuffer(outputIndex, false) // Don't render to surface
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        
        return outputBuffers
    }
    
    /** Stop encoding */
    fun stop() {
        mediaCodec?.stop()
        mediaCodec?.release()
        mediaCodec = null
        outputBuffers.clear()
    }
}
