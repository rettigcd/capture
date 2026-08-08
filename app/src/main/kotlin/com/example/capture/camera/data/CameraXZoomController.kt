package com.example.capture.camera.data

import com.example.capture.camera.domain.ZoomController
import javax.inject.Inject

/**
 * The only place `CameraControl.setZoomRatio` is touched. [level] (1-5, see "Camera zoom" in
 * app-spec.md) is used directly as the zoom ratio, since CameraX linearly maps a zoom ratio of
 * `1f` to no zoom. Safe to call even when nothing is currently bound
 * ([CameraControlHolder] simply holds `null` before the camera is ready, or after it's released),
 * the same as [CameraXFlashTorchController].
 */
class CameraXZoomController @Inject constructor(
    private val cameraControlHolder: CameraControlHolder,
) : ZoomController {

    override suspend fun setZoomLevel(level: Int) {
        cameraControlHolder.camera.value?.cameraControl?.setZoomRatio(level.toFloat())
    }
}
