package com.ocubea.model

/**
 * Camera and streaming configuration for OcuBea.
 */
data class CameraConfig(
    val resolution: Resolution = Resolution.HD720,
    val fps: Int = 30,
    val bitrateKbps: Int = 4000,
    val codec: VideoCodec = VideoCodec.H264,
    val httpPort: Int = 8080
) {
    enum class Resolution(val width: Int, val height: Int) {
        HD720(1280, 720),
        FullHD(1920, 1080),
        QVGA(320, 240),
        VGA(640, 480)
    }

    enum class VideoCodec(val mime: String) {
        H264("video/avc"),
        H265("video/hevc")
    }
}
