package com.ocubea.model

/** Camera configuration model. */
object CameraConfig {
    /** Supported resolutions mapped to width×height. */
    enum class Resolution(val width: Int, val height: Int) {
        QVGA(320, 240),
        VGA(640, 480),
        HD720(1280, 720),
        FullHD(1920, 1080);

        companion object {
            /** Default resolution. */
            val DEFAULT: Resolution = HD720
        }
    }
}
