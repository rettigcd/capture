package com.example.capture.camera.data

import androidx.camera.core.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the `Camera` handle CameraX returns from `bindToLifecycle` (created inside
 * `CameraPreview.kt`) to [CameraXFlashTorchController], which is invoked from
 * [com.example.capture.camera.ui.CameraViewModel] and knows nothing about Compose or
 * `LifecycleOwner`s - the same pattern [ImageCaptureUseCaseHolder] uses for the `ImageCapture`
 * use case.
 */
@Singleton
class CameraControlHolder @Inject constructor() {
    private val _camera = MutableStateFlow<Camera?>(null)
    val camera: StateFlow<Camera?> = _camera.asStateFlow()

    fun attach(camera: Camera?) {
        _camera.value = camera
    }
}
