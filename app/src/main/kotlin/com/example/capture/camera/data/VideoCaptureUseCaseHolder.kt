package com.example.capture.camera.data

import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges CameraX's Compose-bound `VideoCapture<Recorder>` use case (created and bound to the
 * lifecycle inside `CameraPreview.kt`) to [CameraXVideoCaptureController], which is invoked from
 * [com.example.capture.camera.domain.CaptureCoordinator] and knows nothing about Compose or
 * `LifecycleOwner`s - the video-mode counterpart of [ImageCaptureUseCaseHolder].
 */
@Singleton
class VideoCaptureUseCaseHolder @Inject constructor() {
    private val _videoCapture = MutableStateFlow<VideoCapture<Recorder>?>(null)
    val videoCapture: StateFlow<VideoCapture<Recorder>?> = _videoCapture.asStateFlow()

    fun attach(useCase: VideoCapture<Recorder>?) {
        _videoCapture.value = useCase
    }
}
