package com.example.capture.security.domain

import kotlinx.coroutines.flow.StateFlow

/**
 * Sign-in/out and key-management orchestration, layered on top of [KeyBackupRepository]'s file
 * format and a photo-encryption key store. Never shows a dialog itself - callers pass an
 * already-collected passphrase and get back a typed result; `security.ui.KeySessionViewModel`
 * owns the multi-step passphrase-prompt flow instead.
 *
 * Auto-lock: locks (same path as the manual lock action - [signOut]) when the app is
 * backgrounded, and after 5 minutes of no auth-state-changing activity. See
 * [startObservingAppLifecycle]'s kdoc for why it's a separate call from construction.
 */
interface KeySessionRepository {

    val state: StateFlow<KeySessionState>

    /**
     * Registers this repository to observe app-wide foreground/background transitions for
     * auto-lock. Call once, from a real process (e.g. `CaptureApplication.onCreate`) - not from
     * a constructor, so plain unit tests that build this repository directly don't need a real
     * Android process.
     */
    fun startObservingAppLifecycle()

    /** Restarts the 5-minute inactivity timer. */
    fun resetInactivityTimer()

    /** The key file's path, for building a share intent. */
    fun keyFilePath(): String

    /** Loads the public key (only) from the key file, without a passphrase. Call once at startup, and again after [signOut]. */
    suspend fun initialize()

    suspend fun signIn(passphrase: String): SignInResult

    /** Clears the private key only, then reloads the public-key-only (locked) state. */
    suspend fun signOut()

    suspend fun createKey(passphrase: String): CreateKeyResult

    /** [sourceBytes] is the raw contents of a `.kkey` file the user picked - reading a picked file's bytes is a caller (UI-layer) concern. */
    suspend fun importKeyFile(sourceBytes: ByteArray): ImportKeyResult

    suspend fun changePassphrase(currentPassphrase: String, newPassphrase: String): ChangePassphraseResult

    /** Checks [passphrase] against the key file without changing sign-in state. */
    suspend fun verifyPassphrase(passphrase: String): Boolean
}
