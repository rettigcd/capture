package com.example.capture.di

import com.example.capture.security.data.DefaultKeySessionRepository
import com.example.capture.security.data.FileKeyBackupRepository
import com.example.capture.security.data.KencPhotoEncryptor
import com.example.capture.security.data.KeySessionKeyStore
import com.example.capture.security.domain.KeyBackupRepository
import com.example.capture.security.domain.KeySessionRepository
import com.example.capture.security.domain.PhotoEncryptor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    abstract fun bindKeyBackupRepository(impl: FileKeyBackupRepository): KeyBackupRepository

    @Binds
    abstract fun bindKeySessionRepository(impl: DefaultKeySessionRepository): KeySessionRepository

    @Binds
    abstract fun bindPhotoEncryptor(impl: KencPhotoEncryptor): PhotoEncryptor

    @Binds
    abstract fun bindKeySessionKeyStore(impl: KencPhotoEncryptor): KeySessionKeyStore
}
