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
import com.example.capture.camera.domain.FlashTorchController
import com.example.capture.camera.domain.GestureDiagnosticEvent
import com.example.capture.camera.domain.GestureDiagnosticsLogger
import com.example.capture.camera.domain.HapticFeedback
import com.example.capture.camera.domain.ImageMetadata
import com.example.capture.camera.domain.ImageMetadataReader
import com.example.capture.camera.domain.OverlayVisibilityRepository
import com.example.capture.camera.domain.PendingPhotoEntry
import com.example.capture.camera.domain.PhotoStorage
import com.example.capture.common.DispatcherProvider
import com.example.capture.common.TimeProvider
import com.example.capture.settings.domain.AppSettings
import com.example.capture.settings.domain.OverlayImageStore
import com.example.capture.settings.domain.SettingsRepository
import com.example.capture.voice.domain.VoiceCommandRecognizer
import com.example.capture.voice.domain.VoiceRecognitionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class FakeTimeProvider(startMillis: Long = 0L) : TimeProvider {
    var currentMillis: Long = startMillis
    override fun currentTimeMillis(): Long = currentMillis
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

class FakeHapticFeedback : HapticFeedback {
    var performCaptureSuccessCount: Int = 0
        private set
    val recordedDurationsMillis = mutableListOf<Long>()

    override fun performCaptureSuccess(durationMillis: Long) {
        performCaptureSuccessCount++
        recordedDurationsMillis += durationMillis
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override suspend fun setVibrationDurationMillis(durationMillis: Long) {
        _settings.value = _settings.value.copy(vibrationDurationMillis = durationMillis)
    }

    override suspend fun setOverlayImageUri(uriString: String?) {
        _settings.value = _settings.value.copy(overlayImageUriString = uriString)
    }

    override suspend fun setCaptureMode(mode: CaptureMode) {
        _settings.value = _settings.value.copy(captureMode = mode)
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

class FakeOverlayImageStore(var failNextPersist: Boolean = false) : OverlayImageStore {
    val persistedSourceUris = mutableListOf<String>()

    override suspend fun persist(sourceUriString: String): String {
        if (failNextPersist) {
            failNextPersist = false
            throw IOException("Fake: unable to persist the overlay image")
        }
        persistedSourceUris += sourceUriString
        return "file://fake/persisted/$sourceUriString"
    }
}

class FakeOverlayVisibilityRepository(initial: Boolean = false) : OverlayVisibilityRepository {
    private val _overlayVisible = MutableStateFlow(initial)
    override val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    override suspend fun setOverlayVisible(visible: Boolean) {
        _overlayVisible.value = visible
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
