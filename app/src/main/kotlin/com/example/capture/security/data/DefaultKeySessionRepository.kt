package com.example.capture.security.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.capture.common.ApplicationScope
import com.example.capture.security.domain.ChangePassphraseResult
import com.example.capture.security.domain.CreateKeyResult
import com.example.capture.security.domain.ImportKeyResult
import com.example.capture.security.domain.IncorrectPassphraseException
import com.example.capture.security.domain.KeyBackupRepository
import com.example.capture.security.domain.KeySessionRepository
import com.example.capture.security.domain.KeySessionState
import com.example.capture.security.domain.KeyStatus
import com.example.capture.security.domain.SignInResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [KeyBackupRepository]'s `.kkey` format operations and [KencPhotoEncryptor]'s in-memory key
 * state with sign-in/out and key-management operations, ported from the keibler app's
 * `data/keysession/KeySessionRepository.kt`.
 *
 * Auto-lock: locks (same path as the manual lock action - [signOut]) when the app is backgrounded
 * ([onStop], via [ProcessLifecycleOwner] - registered once, from a real process, by
 * [startObservingAppLifecycle]), and after [INACTIVITY_TIMEOUT] with no auth-state-changing call.
 * A cancel-and-relaunch `delay()` job matches the pattern `CaptureCoordinator`'s Burst Mode already
 * uses for `StandardTestDispatcher`-driven tests.
 *
 * [mutex] serializes every state-mutating method: auto-lock can fire from a background
 * timer/lifecycle callback concurrently with an explicit user-driven call (e.g. the inactivity
 * timer elapsing at the same moment as a manual sign-out), and both racing to read-modify-write
 * [photoEncryptor]'s key state and [_state] could otherwise interleave and corrupt the result.
 */
@Singleton
class DefaultKeySessionRepository @Inject constructor(
    private val keyBackupRepository: KeyBackupRepository,
    private val photoEncryptor: KeySessionKeyStore,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : KeySessionRepository, DefaultLifecycleObserver {

    private val _state = MutableStateFlow(KeySessionState())
    override val state: StateFlow<KeySessionState> = _state.asStateFlow()

    private var inactivityJob: Job? = null

    /** Not reentrant - internal callers (e.g. [signOut] calling [initializeLocked]) call the `*Locked` variant directly, never the public `mutex.withLock`-wrapped one. */
    private val mutex = Mutex()

    override fun startObservingAppLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        applicationScope.launch { lockNow() }
    }

    override fun onStart(owner: LifecycleOwner) {
        resetInactivityTimer()
    }

    override fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = applicationScope.launch {
            delay(INACTIVITY_TIMEOUT)
            lockNow()
        }
    }

    private suspend fun lockNow() {
        if (_state.value.keyStatus == KeyStatus.PRIVATE) signOut()
    }

    override fun keyFilePath(): String = keyBackupRepository.keyFilePath()

    override suspend fun initialize() = mutex.withLock { initializeLocked() }

    private suspend fun initializeLocked() {
        if (!keyBackupRepository.hasKeyFile()) {
            _state.value = KeySessionState()
            return
        }
        try {
            val info = keyBackupRepository.readPublicKeyInfo()
            if (info == null) {
                _state.value = KeySessionState()
                return
            }
            photoEncryptor.setPublicKey(info.publicKeyBase64)
            refreshState(info.fingerprintSha256)
        } catch (e: Exception) {
            // Key file exists but is unreadable/corrupted - surface as "no usable key", not a crash.
            photoEncryptor.clearKeys()
            _state.value = KeySessionState(hasKeyFile = true)
        }
    }

    override suspend fun signIn(passphrase: String): SignInResult = mutex.withLock {
        if (!keyBackupRepository.hasKeyFile()) return@withLock SignInResult.NoKeyFile
        try {
            val imported = keyBackupRepository.importFromCurrentFile(passphrase)
            photoEncryptor.setPublicKey(imported.publicKeyBase64)
            if (!photoEncryptor.setPrivateKey(imported.privateKey)) {
                return@withLock SignInResult.Failure("Key file may be corrupted.")
            }
            refreshState(imported.fingerprintSha256)
            SignInResult.Success
        } catch (e: IncorrectPassphraseException) {
            SignInResult.IncorrectPassphrase
        } catch (e: Exception) {
            SignInResult.Failure(e.message ?: "Failed to unlock key.")
        }
    }

    /** Clears the private key only, then reloads the public-key-only (locked) state. */
    override suspend fun signOut() = mutex.withLock {
        if (_state.value.keyStatus == KeyStatus.NONE) return@withLock
        photoEncryptor.clearKeys()
        initializeLocked()
    }

    override suspend fun createKey(passphrase: String): CreateKeyResult = mutex.withLock {
        try {
            val keyPair = keyBackupRepository.createKeyFile(passphrase)
            photoEncryptor.setPublicKey(keyPair.public64)
            if (!photoEncryptor.setPrivateKey(keyPair.privateKey)) {
                CreateKeyResult.Failure("Failed to activate new key.")
            } else {
                val info = keyBackupRepository.readPublicKeyInfo()
                refreshState(info?.fingerprintSha256.orEmpty())
                CreateKeyResult.Success
            }
        } catch (e: Exception) {
            CreateKeyResult.Failure(e.message ?: "Failed to create key.")
        }
    }

    /** [sourceBytes] is the raw contents of a `.kkey` file the user picked - already read by the caller. */
    override suspend fun importKeyFile(sourceBytes: ByteArray): ImportKeyResult = mutex.withLock {
        try {
            keyBackupRepository.importKeyFileBytes(sourceBytes)
            val info = keyBackupRepository.readPublicKeyInfo() ?: error("Key file missing after import.")
            photoEncryptor.clearKeys()
            photoEncryptor.setPublicKey(info.publicKeyBase64)
            refreshState(info.fingerprintSha256)
            ImportKeyResult.Success
        } catch (e: Exception) {
            ImportKeyResult.Failure(e.message ?: "Failed to import key.")
        }
    }

    override suspend fun changePassphrase(currentPassphrase: String, newPassphrase: String): ChangePassphraseResult = mutex.withLock {
        try {
            keyBackupRepository.changePassphrase(currentPassphrase, newPassphrase)
            ChangePassphraseResult.Success
        } catch (e: IncorrectPassphraseException) {
            ChangePassphraseResult.IncorrectCurrentPassphrase
        } catch (e: Exception) {
            ChangePassphraseResult.Failure(e.message ?: "Failed to change passphrase.")
        }
    }

    /** Checks [passphrase] against the key file without changing sign-in state - not mutex-protected, matching the seam this exists for: a read-only check independent of the active session. */
    override suspend fun verifyPassphrase(passphrase: String): Boolean =
        try {
            keyBackupRepository.importFromCurrentFile(passphrase)
            true
        } catch (e: Exception) {
            false
        }

    private suspend fun refreshState(fingerprint: String) {
        _state.value = KeySessionState(
            keyStatus = when {
                !photoEncryptor.hasPublicKey -> KeyStatus.NONE
                photoEncryptor.hasDecryptionKey -> KeyStatus.PRIVATE
                else -> KeyStatus.PUBLIC
            },
            keyFingerprint = fingerprint,
            hasKeyFile = keyBackupRepository.hasKeyFile(),
        )
        // Matches every auth-state change (sign in, key create/import, passphrase change)
        // resetting the timer, not just navigation - see this class's kdoc for why navigation
        // itself isn't wired up as a reset trigger in this app.
        resetInactivityTimer()
    }

    private companion object {
        val INACTIVITY_TIMEOUT = 5.minutes
    }
}
