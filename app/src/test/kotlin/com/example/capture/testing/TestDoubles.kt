package com.example.capture.testing

import com.example.capture.camera.domain.CameraCaptureController
import com.example.capture.camera.domain.CameraCaptureMemoryOutcome
import com.example.capture.camera.domain.CameraCaptureOutcome
import com.example.capture.camera.domain.CameraDiagnosticsSnapshot
import com.example.capture.camera.domain.CaptureAspectRatio
import com.example.capture.camera.domain.CaptureAttemptId
import com.example.capture.camera.domain.CaptureAttemptIdGenerator
import com.example.capture.camera.domain.CaptureDiagnosticEvent
import com.example.capture.camera.domain.CaptureDiagnosticsLogger
import com.example.capture.camera.domain.CaptureErrorLogEntry
import com.example.capture.camera.domain.CaptureErrorLogger
import com.example.capture.camera.domain.CaptureMetadataLogEntry
import com.example.capture.camera.domain.CaptureMetadataLogger
import com.example.capture.camera.domain.CaptureMode
import com.example.capture.camera.domain.CaptureTriggerKind
import com.example.capture.camera.domain.EncryptedPhotoStorage
import com.example.capture.camera.domain.FlashTorchController
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.camera.domain.GestureDiagnosticsLogger
import com.example.capture.camera.domain.HapticFeedback
import com.example.capture.camera.domain.ImageMetadata
import com.example.capture.camera.domain.ImageMetadataReader
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.camera.domain.PendingPhotoEntry
import com.example.capture.camera.domain.PhotoStorage
import com.example.capture.camera.domain.VideoCaptureController
import com.example.capture.camera.domain.VideoStartOutcome
import com.example.capture.camera.domain.VideoStopOutcome
import com.example.capture.camera.domain.ZoomController
import com.example.capture.common.DispatcherProvider
import com.example.capture.common.TimeProvider
import com.example.capture.security.data.KeySessionKeyStore
import com.example.capture.security.domain.ChangePassphraseResult
import com.example.capture.security.domain.CreateKeyResult
import com.example.capture.security.domain.EncryptionKeyPair
import com.example.capture.security.domain.ImportKeyResult
import com.example.capture.security.domain.ImportedKeyPair
import com.example.capture.security.domain.IncorrectPassphraseException
import com.example.capture.security.domain.KeyBackupRepository
import com.example.capture.security.domain.KeySessionRepository
import com.example.capture.security.domain.KeySessionState
import com.example.capture.security.domain.KeyStatus
import com.example.capture.security.domain.PhotoEncryptor
import com.example.capture.security.domain.PrivateKeyException
import com.example.capture.security.domain.PublicKeyInfo
import com.example.capture.security.domain.SignInResult
import com.example.capture.settings.domain.AppSettings
import com.example.capture.settings.domain.OverlayImageStore
import com.example.capture.settings.domain.SettingsRepository
import com.example.capture.voice.domain.VoiceCommandRecognizer
import com.example.capture.voice.domain.VoiceRecognitionState
import java.security.PrivateKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException

/**
 * Test doubles shared by unit tests across the `camera` and `voice` packages. Kept as fakes
 * (rather than a mocking framework) per the project's testing style so behavior is easy to read
 * and assert against directly.
 */

class TestDispatcherProvider(private val dispatcher: CoroutineDispatcher) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
}

/**
 * [currentMillis] is a plain settable field so tests can simulate wall-clock time passing between
 * two separate top-level suspend calls with no real suspension in between (e.g. a debounce-window
 * test bumping it directly). It does *not* by itself track time spent inside real `delay()` calls
 * within a single suspend call - [attachScheduler] opts a test into that too, needed by any
 * production code (e.g. `CaptureCoordinator`'s burst scheduling) whose logic reads elapsed time
 * *across* multiple `delay()` calls in one call and would otherwise see a frozen clock while the
 * `TestCoroutineScheduler`'s own virtual time has moved on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeTimeProvider(startMillis: Long = 0L) : TimeProvider {
    var currentMillis: Long = startMillis
    private var scheduler: TestCoroutineScheduler? = null

    fun attachScheduler(scheduler: TestCoroutineScheduler) {
        this.scheduler = scheduler
    }

    override fun currentTimeMillis(): Long = currentMillis + (scheduler?.currentTime ?: 0L)
}

/**
 * [memoryOutcome] is placed before [outcome] (rather than after) specifically so existing
 * trailing-lambda call sites - `FakeCameraCaptureController { CameraCaptureOutcome.Failure(...) }`,
 * which drive Single-Shot Mode's [captureTo] - keep binding to [outcome] (Kotlin's trailing lambda
 * always targets the *last* parameter). Burst Mode call sites that need to drive [captureToMemory]
 * pass `memoryOutcome = { ... }` by name instead.
 */
class FakeCameraCaptureController(
    private val memoryOutcome: (Int) -> CameraCaptureMemoryOutcome = { CameraCaptureMemoryOutcome.Success(ByteArray(0)) },
    private val outcome: (PendingPhotoEntry) -> CameraCaptureOutcome = { CameraCaptureOutcome.Success },
) : CameraCaptureController {
    var captureCount: Int = 0
        private set
    val capturedEntries = mutableListOf<PendingPhotoEntry>()
    val capturedAttemptIds = mutableListOf<CaptureAttemptId>()

    var memoryCaptureCount: Int = 0
        private set
    val memoryCapturedAttemptIds = mutableListOf<CaptureAttemptId>()

    override suspend fun captureTo(entry: PendingPhotoEntry, attemptId: CaptureAttemptId): CameraCaptureOutcome {
        captureCount++
        capturedEntries += entry
        capturedAttemptIds += attemptId
        return outcome(entry)
    }

    override suspend fun captureToMemory(attemptId: CaptureAttemptId): CameraCaptureMemoryOutcome {
        memoryCaptureCount++
        memoryCapturedAttemptIds += attemptId
        return memoryOutcome(memoryCaptureCount)
    }
}

/**
 * [startOutcome]/[stopOutcome] are settable fields (rather than constructor lambdas like
 * [FakeCameraCaptureController]'s) since Video Mode tests typically only need one fixed
 * outcome per call, and a settable field reads more directly at the call site than a
 * single-value lambda would.
 */
class FakeVideoCaptureController(
    var startOutcome: VideoStartOutcome = VideoStartOutcome.Started,
    var stopOutcome: VideoStopOutcome = VideoStopOutcome.Success("content://fake/video/0"),
) : VideoCaptureController {
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    val startedAttemptIds = mutableListOf<CaptureAttemptId>()

    override suspend fun startRecording(timestampMillis: Long, attemptId: CaptureAttemptId): VideoStartOutcome {
        startCount++
        startedAttemptIds += attemptId
        return startOutcome
    }

    override suspend fun stopRecording(): VideoStopOutcome {
        stopCount++
        return stopOutcome
    }
}

class FakePhotoStorage(
    private var nextId: Int = 0,
    var failCreate: Boolean = false,
    var failWrite: Boolean = false,
    var failFinalize: Boolean = false,
) : PhotoStorage {
    val created = mutableListOf<PendingPhotoEntry>()
    val writtenBytes = mutableListOf<Pair<PendingPhotoEntry, ByteArray>>()
    val finalized = mutableListOf<PendingPhotoEntry>()
    val discarded = mutableListOf<PendingPhotoEntry>()

    override suspend fun createPendingEntry(timestampMillis: Long): PendingPhotoEntry {
        if (failCreate) throw IOException("Fake: unable to create MediaStore entry")
        val entry = PendingPhotoEntry("content://fake/photo/${nextId++}")
        created += entry
        return entry
    }

    override suspend fun writeBytes(entry: PendingPhotoEntry, bytes: ByteArray) {
        if (failWrite) throw IOException("Fake: unable to write photo bytes")
        writtenBytes += entry to bytes
    }

    override suspend fun finalizeEntry(entry: PendingPhotoEntry): String {
        if (failFinalize) throw IOException("Fake: unable to finalize MediaStore entry")
        finalized += entry
        return entry.uriString
    }

    override suspend fun discardEntry(entry: PendingPhotoEntry) {
        discarded += entry
    }
}

class FakeEncryptedPhotoStorage(
    private var nextId: Int = 0,
    var failNextWrite: Boolean = false,
) : EncryptedPhotoStorage {
    val writtenPhotos = mutableListOf<Pair<ByteArray, Long>>()

    override suspend fun writeEncryptedPhoto(jpegBytes: ByteArray, timestampMillis: Long): String {
        if (failNextWrite) {
            failNextWrite = false
            throw IOException("Fake: unable to write encrypted photo")
        }
        writtenPhotos += jpegBytes to timestampMillis
        return "content://fake/encrypted-photo/${nextId++}.kenc"
    }
}

class FakeHapticFeedback : HapticFeedback {
    var performCaptureSuccessCount: Int = 0
        private set
    val recordedDurationsMillis = mutableListOf<Long>()

    var performVideoStoppedCount: Int = 0
        private set
    val recordedVideoStoppedDurationsMillis = mutableListOf<Long>()

    override fun performCaptureSuccess(durationMillis: Long) {
        performCaptureSuccessCount++
        recordedDurationsMillis += durationMillis
    }

    override fun performVideoStopped(durationMillis: Long) {
        performVideoStoppedCount++
        recordedVideoStoppedDurationsMillis += durationMillis
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override suspend fun setVibrationDurationMillis(durationMillis: Long) {
        _settings.value = _settings.value.copy(vibrationDurationMillis = durationMillis)
    }

    override suspend fun setCoverPhotoUriStrings(uriStrings: List<String>) {
        _settings.value = _settings.value.copy(coverPhotoUriStrings = uriStrings)
    }

    override suspend fun setCaptureMode(trigger: CaptureTriggerKind, mode: CaptureMode) {
        _settings.value = _settings.value.copy(
            captureModeByTrigger = _settings.value.captureModeByTrigger + (trigger to mode),
        )
    }

    override suspend fun setBurstIntervalMillis(intervalMillis: Long) {
        _settings.value = _settings.value.copy(burstIntervalMillis = intervalMillis)
    }

    override suspend fun setCaptureAspectRatio(ratio: CaptureAspectRatio) {
        _settings.value = _settings.value.copy(captureAspectRatio = ratio)
    }

    override suspend fun setDiagnosticsFileLoggingEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(diagnosticsFileLoggingEnabled = enabled)
    }

    override suspend fun setEncryptSavedPhotos(enabled: Boolean) {
        _settings.value = _settings.value.copy(encryptSavedPhotos = enabled)
    }

    override suspend fun setEncryptedPhotosFolderUri(uriString: String?) {
        _settings.value = _settings.value.copy(encryptedPhotosFolderUriString = uriString)
    }

    override suspend fun setZoomLevel(level: Int) {
        _settings.value = _settings.value.copy(zoomLevel = level)
    }

    override suspend fun setFullScreenEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(fullScreenEnabled = enabled)
    }
}

class FakeCaptureErrorLogger : CaptureErrorLogger {
    val loggedEntries = mutableListOf<CaptureErrorLogEntry>()

    override suspend fun log(entry: CaptureErrorLogEntry) {
        loggedEntries += entry
    }
}

class FakeCaptureMetadataLogger : CaptureMetadataLogger {
    val loggedEntries = mutableListOf<CaptureMetadataLogEntry>()

    override suspend fun log(entry: CaptureMetadataLogEntry) {
        loggedEntries += entry
    }
}

/**
 * Returns [nextMetadata] (default 4032x3024, a clean 4:3 match) for every [uriString], or `null`
 * for all reads if [failNextRead] is set - simulating a saved file that can't be read back.
 */
class FakeImageMetadataReader(
    var nextMetadata: ImageMetadata? = ImageMetadata(widthPx = 4032, heightPx = 3024, exifOrientation = 1),
    var failNextRead: Boolean = false,
) : ImageMetadataReader {
    val requestedUris = mutableListOf<String>()

    override suspend fun read(uriString: String): ImageMetadata? {
        requestedUris += uriString
        if (failNextRead) {
            failNextRead = false
            return null
        }
        return nextMetadata
    }
}

class FakeFlashTorchController : FlashTorchController {
    var disableCallCount: Int = 0
        private set

    override suspend fun disableFlashAndTorch() {
        disableCallCount++
    }
}

class FakeZoomController : ZoomController {
    val appliedZoomLevels = mutableListOf<Int>()

    override suspend fun setZoomLevel(level: Int) {
        appliedZoomLevels += level
    }
}

class FakeOverlayImageStore(var failNextPersist: Boolean = false) : OverlayImageStore {
    val persistedSourceUris = mutableListOf<String>()
    val deletedUris = mutableListOf<String>()

    override suspend fun persist(sourceUriString: String): String {
        if (failNextPersist) {
            failNextPersist = false
            throw IOException("Fake: unable to persist the cover photo")
        }
        persistedSourceUris += sourceUriString
        return "file://fake/persisted/$sourceUriString"
    }

    override suspend fun delete(uriString: String) {
        deletedUris += uriString
    }
}

class FakeOverlayVisibilityRepository(initial: Boolean = false, initialActiveCoverPhotoIndex: Int = 0) : OverlayVisibilityRepository {
    private val _overlayVisible = MutableStateFlow(initial)
    override val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    override suspend fun setOverlayVisible(visible: Boolean) {
        _overlayVisible.value = visible
    }

    private val _activeCoverPhotoIndex = MutableStateFlow(initialActiveCoverPhotoIndex)
    override val activeCoverPhotoIndex: StateFlow<Int> = _activeCoverPhotoIndex.asStateFlow()

    override suspend fun setActiveCoverPhotoIndex(index: Int) {
        _activeCoverPhotoIndex.value = index
    }
}

class FakeVoiceCommandRecognizer : VoiceCommandRecognizer {
    private val _state = MutableStateFlow<VoiceRecognitionState>(VoiceRecognitionState.Idle)
    override val state: StateFlow<VoiceRecognitionState> = _state.asStateFlow()

    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override suspend fun start() {
        startCount++
        _state.value = VoiceRecognitionState.Listening
    }

    override suspend fun stop() {
        stopCount++
        _state.value = VoiceRecognitionState.Idle
    }

    fun emit(state: VoiceRecognitionState) {
        _state.value = state
    }
}

/** Deterministic ids ("attempt-0", "attempt-1", ...) instead of random UUIDs, so tests can assert on them. */
class FakeCaptureAttemptIdGenerator : CaptureAttemptIdGenerator {
    private var nextIndex = 0

    override fun generate(): CaptureAttemptId = CaptureAttemptId("attempt-${nextIndex++}")
}

class FakeCaptureDiagnosticsLogger : CaptureDiagnosticsLogger {
    val loggedEvents = mutableListOf<CaptureDiagnosticEvent>()
    val loggedCameraStates = mutableListOf<CameraDiagnosticsSnapshot>()

    override fun logEvent(event: CaptureDiagnosticEvent) {
        loggedEvents += event
    }

    override fun logCameraState(snapshot: CameraDiagnosticsSnapshot) {
        loggedCameraStates += snapshot
    }
}

class FakeGestureDiagnosticsLogger : GestureDiagnosticsLogger {
    val loggedEvents = mutableListOf<GestureDiagnosticEvent>()

    override fun log(event: GestureDiagnosticEvent) {
        loggedEvents += event
    }
}

/**
 * In-memory `.kkey` stand-in: one stored key pair + the passphrase it was last written under.
 * [failNextWith], if set, is thrown (and cleared) by the next call to any method.
 */
class FakeKeyBackupRepository : KeyBackupRepository {
    var storedKeyPair: EncryptionKeyPair? = null
    var storedPassphrase: String? = null
    var storedFingerprint: String = "fake-fingerprint"
    var failNextWith: Exception? = null

    val createKeyFileCalls = mutableListOf<String>()
    val importKeyFileBytesCalls = mutableListOf<ByteArray>()
    val changePassphraseCalls = mutableListOf<Pair<String, String>>()

    override suspend fun hasKeyFile(): Boolean = storedKeyPair != null

    override suspend fun createKeyFile(passphrase: String): EncryptionKeyPair {
        maybeFail()
        createKeyFileCalls += passphrase
        val keyPair = EncryptionKeyPair.generate()
        storedKeyPair = keyPair
        storedPassphrase = passphrase
        return keyPair
    }

    override suspend fun readPublicKeyInfo(): PublicKeyInfo? {
        maybeFail()
        val keyPair = storedKeyPair ?: return null
        return PublicKeyInfo(keyPair.public64, storedFingerprint)
    }

    override suspend fun importFromCurrentFile(passphrase: String): ImportedKeyPair {
        maybeFail()
        val keyPair = storedKeyPair ?: throw IncorrectPassphraseException()
        if (passphrase != storedPassphrase) throw IncorrectPassphraseException()
        return ImportedKeyPair(keyPair.privateKey, keyPair.public64, storedFingerprint)
    }

    override suspend fun importKeyFileBytes(bytes: ByteArray) {
        maybeFail()
        importKeyFileBytesCalls += bytes
        storedKeyPair = EncryptionKeyPair.generate()
        storedPassphrase = null // unknown to this fake - tests that need a follow-up sign-in should set storedPassphrase directly.
    }

    override suspend fun exportKeyFileBytes(): ByteArray {
        maybeFail()
        return storedKeyPair?.public64?.toByteArray() ?: ByteArray(0)
    }

    override suspend fun changePassphrase(currentPassphrase: String, newPassphrase: String) {
        maybeFail()
        if (currentPassphrase != storedPassphrase) throw IncorrectPassphraseException()
        changePassphraseCalls += currentPassphrase to newPassphrase
        storedPassphrase = newPassphrase
    }

    override fun keyFilePath(): String = "fake/KeyPair.kkey"

    private fun maybeFail() {
        failNextWith?.let {
            failNextWith = null
            throw it
        }
    }
}

/** Records every dialog-triggering call; [state] is settable directly for tests that don't go through a real sign-in flow. */
class FakeKeySessionRepository : KeySessionRepository {
    private val _state = MutableStateFlow(KeySessionState())
    override val state: StateFlow<KeySessionState> = _state.asStateFlow()

    var nextSignInResult: SignInResult = SignInResult.Success
    var nextCreateKeyResult: CreateKeyResult = CreateKeyResult.Success
    var nextImportKeyResult: ImportKeyResult = ImportKeyResult.Success
    var nextChangePassphraseResult: ChangePassphraseResult = ChangePassphraseResult.Success
    var nextVerifyPassphraseResult: Boolean = true

    var startObservingAppLifecycleCallCount = 0
        private set
    var resetInactivityTimerCallCount = 0
        private set
    var initializeCallCount = 0
        private set

    val signInPassphrases = mutableListOf<String>()
    val importedKeyFileBytes = mutableListOf<ByteArray>()
    val changePassphraseCalls = mutableListOf<Pair<String, String>>()

    fun emit(state: KeySessionState) {
        _state.value = state
    }

    override fun startObservingAppLifecycle() {
        startObservingAppLifecycleCallCount++
    }

    override fun resetInactivityTimer() {
        resetInactivityTimerCallCount++
    }

    override fun keyFilePath(): String = "fake/KeyPair.kkey"

    override suspend fun initialize() {
        initializeCallCount++
    }

    override suspend fun signIn(passphrase: String): SignInResult {
        signInPassphrases += passphrase
        return nextSignInResult
    }

    override suspend fun signOut() {
        _state.value = _state.value.copy(keyStatus = KeyStatus.PUBLIC)
    }

    override suspend fun createKey(passphrase: String): CreateKeyResult = nextCreateKeyResult

    override suspend fun importKeyFile(sourceBytes: ByteArray): ImportKeyResult {
        importedKeyFileBytes += sourceBytes
        return nextImportKeyResult
    }

    override suspend fun changePassphrase(currentPassphrase: String, newPassphrase: String): ChangePassphraseResult {
        changePassphraseCalls += currentPassphrase to newPassphrase
        return nextChangePassphraseResult
    }

    override suspend fun verifyPassphrase(passphrase: String): Boolean = nextVerifyPassphraseResult
}

/** One instance covers both roles [KencPhotoEncryptor][com.example.capture.security.data.KencPhotoEncryptor] plays: [PhotoEncryptor] and [KeySessionKeyStore]. */
class FakePhotoEncryptor : PhotoEncryptor, KeySessionKeyStore {
    override var hasPublicKey: Boolean = false
        private set
    override var hasDecryptionKey: Boolean = false
        private set

    var failNextDecryptWith: PrivateKeyException? = null

    val encryptedPlainBytes = mutableListOf<ByteArray>()
    val decryptedCipherBytes = mutableListOf<ByteArray>()

    override fun setPublicKey(publicKeyBase64: String) {
        hasPublicKey = true
        hasDecryptionKey = false
    }

    override fun clearKeys() {
        hasPublicKey = false
        hasDecryptionKey = false
    }

    override fun setPrivateKey(candidate: PrivateKey?): Boolean {
        hasDecryptionKey = candidate != null
        return true
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        check(hasPublicKey) { "Public key is not set." }
        encryptedPlainBytes += plain
        return plain
    }

    override fun encryptToKencFile(imageBytes: ByteArray, metadataJson: String): ByteArray {
        check(hasPublicKey) { "Public key is not set." }
        encryptedPlainBytes += imageBytes
        return imageBytes + metadataJson.toByteArray(Charsets.UTF_8)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        failNextDecryptWith?.let {
            failNextDecryptWith = null
            throw it
        }
        if (!hasDecryptionKey) throw PrivateKeyException(PrivateKeyException.Reason.MISSING_KEY)
        decryptedCipherBytes += data
        return data
    }
}
