package com.example.capture.di

import com.example.capture.camera.data.AndroidDiagnosticsLogger
import com.example.capture.camera.data.AndroidHapticFeedback
import com.example.capture.camera.data.AndroidImageMetadataReader
import com.example.capture.camera.data.CameraXCaptureController
import com.example.capture.camera.data.CameraXFlashTorchController
import com.example.capture.camera.data.CameraXVideoCaptureController
import com.example.capture.camera.data.CameraXZoomController
import com.example.capture.camera.data.DataStoreOverlayVisibilityRepository
import com.example.capture.camera.data.FileCaptureErrorLogger
import com.example.capture.camera.data.FileCaptureMetadataLogger
import com.example.capture.camera.data.MediaStorePhotoStorage
import com.example.capture.camera.data.SafEncryptedPhotoStorage
import com.example.capture.camera.domain.CameraCaptureController
import com.example.capture.camera.domain.CaptureAttemptIdGenerator
import com.example.capture.camera.domain.CaptureDiagnosticsLogger
import com.example.capture.camera.domain.CaptureErrorLogger
import com.example.capture.camera.domain.CaptureMetadataLogger
import com.example.capture.camera.domain.EncryptedPhotoStorage
import com.example.capture.camera.domain.FlashTorchController
import com.example.capture.camera.domain.GestureDiagnosticsLogger
import com.example.capture.camera.domain.HapticFeedback
import com.example.capture.camera.domain.ImageMetadataReader
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.camera.domain.PhotoStorage
import com.example.capture.camera.domain.RandomCaptureAttemptIdGenerator
import com.example.capture.camera.domain.VideoCaptureController
import com.example.capture.camera.domain.ZoomController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    abstract fun bindCameraCaptureController(impl: CameraXCaptureController): CameraCaptureController

    @Binds
    abstract fun bindVideoCaptureController(impl: CameraXVideoCaptureController): VideoCaptureController

    @Binds
    abstract fun bindPhotoStorage(impl: MediaStorePhotoStorage): PhotoStorage

    @Binds
    abstract fun bindEncryptedPhotoStorage(impl: SafEncryptedPhotoStorage): EncryptedPhotoStorage

    @Binds
    abstract fun bindHapticFeedback(impl: AndroidHapticFeedback): HapticFeedback

    @Binds
    abstract fun bindOverlayVisibilityRepository(
        impl: DataStoreOverlayVisibilityRepository,
    ): OverlayVisibilityRepository

    @Binds
    abstract fun bindCaptureErrorLogger(impl: FileCaptureErrorLogger): CaptureErrorLogger

    @Binds
    abstract fun bindFlashTorchController(impl: CameraXFlashTorchController): FlashTorchController

    @Binds
    abstract fun bindZoomController(impl: CameraXZoomController): ZoomController

    @Binds
    abstract fun bindImageMetadataReader(impl: AndroidImageMetadataReader): ImageMetadataReader

    @Binds
    abstract fun bindCaptureMetadataLogger(impl: FileCaptureMetadataLogger): CaptureMetadataLogger

    @Binds
    abstract fun bindCaptureAttemptIdGenerator(impl: RandomCaptureAttemptIdGenerator): CaptureAttemptIdGenerator

    @Binds
    abstract fun bindCaptureDiagnosticsLogger(impl: AndroidDiagnosticsLogger): CaptureDiagnosticsLogger

    @Binds
    abstract fun bindGestureDiagnosticsLogger(impl: AndroidDiagnosticsLogger): GestureDiagnosticsLogger
}
