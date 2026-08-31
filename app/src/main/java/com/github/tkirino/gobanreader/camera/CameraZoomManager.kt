package com.github.tkirino.gobanreader.camera

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner

class CameraZoomManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("goban_reader_camera_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_DEFAULT_ZOOM = "default_zoom_ratio"
        val SUPPORTED_ZOOMS = listOf(0.7f, 0.8f, 0.9f, 1.0f)
        private const val TAG = "CameraZoomManager"
    }

    var defaultZoomRatio: Float
        get() = prefs.getFloat(KEY_DEFAULT_ZOOM, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DEFAULT_ZOOM, value).apply()

    /**
     * 指定された倍率でカメラをバインドする（Pixel等のシームレスズーム対応機種向け）
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun bindCameraWithZoom(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        useCaseGroup: UseCaseGroup,
        targetZoomRatio: Float,
        onCameraBound: (Camera) -> Unit
    ) {
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            useCaseGroup
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                val zoomRange = camera2Info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                )
                if (zoomRange != null && targetZoomRatio in zoomRange.lower..zoomRange.upper) {
                    camera.cameraControl.setZoomRatio(targetZoomRatio)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ズーム適用エラー: ${e.message}")
        }

        onCameraBound(camera)
    }

    /**
     * 現在の端末（標準バックカメラ）が 1.0 未満のズーム（超広角領域）をサポートしているかどうかを判定する
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun isZoomOutSupported(cameraProvider: ProcessCameraProvider): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }
        try {
            for (info in cameraProvider.availableCameraInfos) {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue

                val zoomRange = c2.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                )
                if (zoomRange != null && zoomRange.lower < 1.0f) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ズームサポート判定エラー: ${e.message}")
        }
        return false
    }
}
