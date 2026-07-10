package com.ocubea.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Video encoder using MediaCodec API for H.264 encoding with software fallback.
 */
class VideoEncoder {

    data class EncodingConfig(
        val width: Int = 1280,
        val height: Int = 720,
        val frameRate: Int = 30,
        val bitrateKbps: Int = 4000,
        val codec: CodecType = CodecType.H264
    )

    enum class CodecType { H264 }

    private var mediaCodec: MediaCodec? = null
    @Volatile
    private var isRunning = false

    /** Start the encoder */
    fun start(config: EncodingConfig): Boolean {
        return try {
            val mime = "video/avc" // H.264
            val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000) // kbps -> bps
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // I-frame every second
            }

            val codecName = findCodecForMime(mime) ?: "OMX.google.h264.encoder"
            println("Using codec: $codecName")

            mediaCodec = MediaCodec.createByCodecName(codecName)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            isRunning = true
            println("Video encoder started: ${config.width}x${config.height}, ${config.frameRate}fps, ${config.bitrateKbps}kbps")
            true
        } catch (e: Exception) {
            println("Failed to start video encoder: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /** Encode a single YUV frame into H.264 NAL units */
    fun encodeFrame(inputData: ByteArray): List<ByteArray> {
        val encodedFrames = mutableListOf<ByteArray>()

        try {
            mediaCodec?.let { codec ->
                if (!isRunning) return emptyList()

                // Get input buffer
                val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: return@let
                    inputBuffer.clear()

                    val copyLength = minOf(inputBuffer.remaining(), inputData.size)
                    if (copyLength > 0) {
                        inputBuffer.put(inputData, 0, copyLength)
                    }

                    // Queue with presentation timestamp in microseconds
                    val ptsUs = System.nanoTime() / 1_000
                    codec.queueInputBuffer(inputBufferIndex, 0, copyLength, ptsUs, 0)
                }

                // Drain output
                val bufferInfo = MediaCodec.BufferInfo()
                var iterations = 0
                while (iterations < 5 && isRunning) {
                    val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 16_000)

                    when {
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> println("Output buffers changed")
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = codec.outputFormat
                            println("Output format: ${format.mimeType} ${format.width}x${format.height}")
                        }
                        else -> if (outputBufferIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue

                            // Create NAL-URAF encoded frame with SPS/PPS header
                            val frameData = buildFrameWithHeader(
                                bufferInfo.size,
                                isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            )

                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(frameData, frameData.size - bufferInfo.size, bufferInfo.size)

                            encodedFrames.add(frameData)

                            codec.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                    iterations++
                }
            }
        } catch (e: Exception) {
            println("Error encoding frame: ${e.message}")
            e.printStackTrace()
        }

        return encodedFrames
    }

    /** Build a NAL-URAF frame with SPS/PPS headers */
    private fun buildFrameWithHeader(payloadSize: Int, isKeyframe: Boolean): ByteArray {
        // H.264 NAL unit types: 7=SPS, 8=PPS, 5=IDR, 1=non-IDR
        val nalType = if (isKeyframe) 0x65 else 0x01
        val headerSize = if (isKeyframe) 24 else 0

        return ByteArray(payloadSize + headerSize).also { buf ->
            var offset = 0

            // Start code: 0x00 0x00 0x00 0x01
            buf[offset++] = 0x00.toByte()
            buf[offset++] = 0x00.toByte()
            buf[offset++] = 0x00.toByte()
            buf[offset++] = 0x01.toByte()

            // NAL unit header: forbidden_zero_bit(1) | nal_ref_idc(2) | nal_unit_type(5)
            if (isKeyframe) {
                // IDR slice with high priority
                val refIdc = 0x60 // highest reference level
                buf[offset++] = (refIdc or nalType).toByte()
            } else {
                // Non-IDR slice
                buf[offset++] = nalType.toByte()
            }

            if (isKeyframe) {
                // SPS NAL unit: 0x00 0x00 0x00 0x01 | nal_unit_type=7
                offset += writeSp nal(
                    buf, offset, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )

                // PPS NAL unit: 0x00 0x00 0x00 0x01 | nal_unit_type=8
                offset += writeSps(
                    buf, offset,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                )

                // IDR slice NAL unit: 0x00 0x00 0x00 0x01 | nal_unit_type=5
                val refIdc = 0x60
                buf[offset++] = (refIdc or 0x65).toByte()
            } else {
                // Non-IDR slice NAL unit: 0x00 0x00 0x00 0x01 | nal_unit_type=1
                buf[offset++] = nalType.toByte()
            }
        }
    }

    /** Write SPS data */
    private fun writeSps(
        buffer: ByteArray,
        offset: Int,
        profileIdc: Int,
        levelIdc: Int
    ): Int {
        val sps = byteArrayOf(
            0x67.toByte(), // SPS NAL type
            (profileIdc and 0xFF).toByte(), // profile_idc
            0x42.toByte(), // constraint flags
            0xA0.toByte(), // level_idc
            0x01.toByte(), // lengthSizeMinusOne
            0xE9.toByte(), // avc30
            (0x4D.toByte()),
            (0x40).toByte()
        )

        val copyLength = minOf(sps.size, buffer.size - offset)
        if (copyLength > 0) {
            System.arraycopy(sps, 0, buffer, offset, copyLength)
        }
        return copyLength
    }

    /** Stop the encoder */
    fun stop() {
        try {
            mediaCodec?.let { codec ->
                codec.signalEndOfInputStream()

                val bufferInfo = MediaCodec.BufferInfo()
                var iterations = 0
                while (iterations < 10 && isRunning) {
                    val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                    if (index >= 0) {
                        codec.releaseOutputBuffer(index, false)

                        // Stop when we receive EOS or buffer is empty
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 ||
                            bufferInfo.size == 0
                        ) break
                    } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    }
                    iterations++
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

    /** Find available codec for MIME type */
    private fun findCodecForMime(mime: String): String? {
        val mediaCodecList = MediaCodecList(MediaCodecList.ALL_CODECS)

        for (codecInfo in mediaCodecList.codecInfos) {
            if (!codecInfo.isEncoder) continue

            try {
                if (mime in codecInfo.supportedTypes) {
                    return codecInfo.name
                }
            } catch (_: Exception) {}
        }

        return null
    }

    /** Check if encoder is running */
    fun isRunning(): Boolean = isRunning
}
