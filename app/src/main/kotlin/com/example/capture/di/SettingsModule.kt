package com.example.capture.di

import com.example.capture.settings.data.DataStoreSettingsRepository
import com.example.capture.settings.data.FileOverlayImageStore
import com.example.capture.settings.domain.OverlayImageStore
import com.example.capture.settings.domain.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindOverlayImageStore(impl: FileOverlayImageStore): OverlayImageStore
}
