package com.example.capture.camera.data

import androidx.camera.core.ImageCapture
import com.example.capture.camera.domain.FlashTorchController
import javax.inject.Inject

/**
 * The only place `ImageCapture.flashMode` and `CameraControl.enableTorch` are touched. Both are
 * safe to call even when nothing is currently bound ([ImageCaptureUseCaseHolder]/
 * [CameraControlHolder] simply hold `null` before the camera is ready, or after it's released),
 * so this never needs its own "is the camera ready" check.
 */
class CameraXFlashTorchController @Inject constructor(
    private val imageCaptureUseCaseHolder: ImageCaptureUseCaseHolder,
    private val cameraControlHolder: CameraControlHolder,
) : FlashTorchController {

    override suspend fun disableFlashAndTorch() {
        imageCaptureUseCaseHolder.imageCapture.value?.flashMode = ImageCapture.FLASH_MODE_OFF
        cameraControlHolder.camera.value?.cameraControl?.enableTorch(false)
    }
}
