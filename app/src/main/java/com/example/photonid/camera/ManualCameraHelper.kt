package com.example.photonid.camera

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraControl
import kotlinx.coroutines.guava.await

object ManualCameraHelper {
    private const val TAG = "ManualCameraHelper"

    /**
     * Applies manual ISO (Sensitivity) and Exposure Time.
     * Note: This requires the camera device to support manual sensor control.
     * Fallback is to do nothing if not supported.
     *
     * @param cameraControl The CameraX CameraControl object.
     * @param iso Sensor sensitivity (e.g., 100, 400, 800).
     * @param exposureTimeMs Exposure time in milliseconds.
     */
    suspend fun setManualControls(cameraControl: CameraControl, iso: Int, exposureTimeMs: Float) {
        val camera2Control = Camera2CameraControl.from(cameraControl)
        
        // Convert ms to nanoseconds
        val exposureTimeNs = (exposureTimeMs * 1_000_000).toLong()

        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            .build()

        try {
            camera2Control.addCaptureRequestOptions(captureRequestOptions).await()
            Log.d(TAG, "Applied Manual Controls: ISO=$iso, Exp=${exposureTimeMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply manual controls", e)
        }
    }
}
