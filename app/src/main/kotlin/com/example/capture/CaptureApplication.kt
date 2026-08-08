package com.example.capture

import android.app.Application
import com.example.capture.common.ApplicationScope
import com.example.capture.security.domain.KeySessionRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class CaptureApplication : Application() {

    @Inject lateinit var keySessionRepository: KeySessionRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Registers app-wide foreground/background observation for encryption-session auto-lock
        // once, here - not lazily when EncryptionKeyScreen first opens - so backgrounding the app
        // locks a signed-in session even if that screen was never visited this run.
        keySessionRepository.startObservingAppLifecycle()
        // Loads hasKeyFile/keyStatus at startup too, for the same reason: SettingsViewModel's
        // "Encrypt saved photos" toggle needs an accurate KeySessionRepository.state.hasKeyFile
        // from the moment Settings is first opened, not only after EncryptionKeyScreen has been
        // visited once this run. Idempotent/mutex-protected, so KeySessionViewModel's own later
        // call is just a harmless re-check.
        applicationScope.launch { keySessionRepository.initialize() }
    }
}
