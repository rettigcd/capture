package com.example.capture.camera.data

import androidx.camera.core.ImageCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges CameraX's Compose-bound `ImageCapture` use case (created and bound to the lifecycle
 * inside `CameraPreview.kt`) to [CameraXCaptureController], which is invoked from
 * [com.example.capture.camera.domain.CaptureCoordinator] and knows nothing about Compose or
 * `LifecycleOwner`s. This is the only shared mutable state CameraX integration needs; everything
 * else flows through constructor parameters.
 */
@Singleton
class ImageCaptureUseCaseHolder @Inject constructor() {
    private val _imageCapture = MutableStateFlow<ImageCapture?>(null)
    val imageCapture: StateFlow<ImageCapture?> = _imageCapture.asStateFlow()

    fun attach(useCase: ImageCapture?) {
        _imageCapture.value = useCase
    }
}
