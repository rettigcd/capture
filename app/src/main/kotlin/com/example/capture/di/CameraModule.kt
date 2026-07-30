package com.example.capture.di

import com.example.capture.camera.data.AndroidHapticFeedback
import com.example.capture.camera.data.CameraXCaptureController
import com.example.capture.camera.data.DataStoreOverlayVisibilityRepository
import com.example.capture.camera.data.MediaStorePhotoStorage
import com.example.capture.camera.domain.CameraCaptureController
import com.example.capture.camera.domain.HapticFeedback
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.camera.domain.PhotoStorage
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
    abstract fun bindPhotoStorage(impl: MediaStorePhotoStorage): PhotoStorage

    @Binds
    abstract fun bindHapticFeedback(impl: AndroidHapticFeedback): HapticFeedback

    @Binds
    abstract fun bindOverlayVisibilityRepository(
        impl: DataStoreOverlayVisibilityRepository,
    ): OverlayVisibilityRepository
}
