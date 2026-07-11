package com.ocubea.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Controls camera flashlight/torch via Camera2 API.
 */
class TorchController(private val context: Context) {

    private var enabled = false
    private var currentCameraId: String? = null

    /** Check if a camera supports torch */
    fun hasTorch(cameraId: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val info = cm.getCameraCharacteristics(cameraId)
            info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } catch (_: Exception) {
            false
        }
    }

    /** Enable torch on the given camera ID */
    fun enable(cameraId: String): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (!hasTorch(cameraId)) {
                println("No torch available for camera $cameraId")
                false
            } else {
                cm.setTorchMode(cameraId, true)
                enabled = true
                currentCameraId = cameraId
                println("Torch enabled on $cameraId")
                true
            }
        } catch (e: Exception) {
            println("Error enabling torch: ${e.message}")
            false
        }
    }

    /** Disable torch */
    fun disable(): Boolean {
        val camId = currentCameraId ?: return false
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(camId, false)
            enabled = false
            println("Torch disabled on $camId")
            true
        } catch (e: Exception) {
            println("Error disabling torch: ${e.message}")
            false
        }
    }

    /** Toggle torch state */
    fun toggle(cameraId: String): Boolean {
        val newState = !enabled
        return try {
            if (!hasTorch(cameraId)) {
                println("No torch available for camera $cameraId")
                false
            } else {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(cameraId, newState)
                enabled = newState
                currentCameraId = cameraId
                println("Torch ${if (newState) "enabled" else "disabled"} on $cameraId")
                true
            }
        } catch (e: Exception) {
            println("Error toggling torch: ${e.message}")
            false
        }
    }

    /** Find the first back camera that supports torch */
    fun findBackCameraWithTorch(): String? {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (cameraId in cm.cameraIdList) {
                val info = cm.getCameraCharacteristics(cameraId)
                val hasFlash: Boolean = info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing: Int? = info.get(CameraCharacteristics.LENS_FACING)

                if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    println("Back camera with torch: $cameraId")
                    return cameraId
                }
            }
            null
        } catch (e: Exception) {
            println("Error finding back camera: ${e.message}")
            null
        }
    }

    val isEnabled: Boolean get() = enabled
}
