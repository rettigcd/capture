package com.example.capture.camera.domain

import com.example.capture.common.DispatcherProvider
import com.example.capture.common.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place every [CaptureTrigger] - screen touch, either volume button, or a recognized
 * voice command - is turned into an actual photograph. Nothing else in the app is allowed to call
 * [CameraCaptureController] or [PhotoStorage] directly; this keeps capture behavior identical
 * regardless of which input produced the trigger.
 *
 * Deliberately has no Android or Compose imports so it can be constructed and driven from a plain
 * JUnit test with fakes for [CameraCaptureController] and [PhotoStorage].
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    private val cameraCaptureController: CameraCaptureController,
    private val photoStorage: PhotoStorage,
    private val timeProvider: TimeProvider,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    @Volatile
    private var lastAcceptedAtMillis: Long? = null

    /**
     * Requests a capture for [trigger]. Concurrent callers are serialized on [mutex] so captures
     * never overlap; once this request reaches the front of that queue it is still dropped
     * (silently, leaving [state] as-is) if it arrived within [MIN_INTERVAL_MILLIS] of the last
     * *accepted* request, which is what protects against duplicate triggers fired by the same
     * physical action (e.g. a long touch producing more than one pointer event) or rapid repeated
     * presses.
     */
    suspend fun requestCapture(trigger: CaptureTrigger) {
        withContext(dispatcherProvider.default) {
            mutex.lock()
            try {
                val now = timeProvider.currentTimeMillis()
                val last = lastAcceptedAtMillis
                if (last != null && now - last < MIN_INTERVAL_MILLIS) {
                    return@withContext
                }
                lastAcceptedAtMillis = now
                _state.value = CaptureState.Capturing(trigger)
                _state.value = CaptureState.Completed(performCapture(trigger, now))
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun performCapture(trigger: CaptureTrigger, timestampMillis: Long): CaptureResult {
        val entry = createEntryOrNull(timestampMillis)
            ?: return CaptureResult(
                CaptureOutcome.Failure(GENERIC_STORAGE_ERROR),
                trigger,
                timestampMillis,
            )

        return when (val outcome = cameraCaptureController.captureTo(entry)) {
            is CameraCaptureOutcome.Success -> finishSuccessfulCapture(entry, trigger, timestampMillis)
            is CameraCaptureOutcome.Failure -> {
                photoStorage.discardEntry(entry)
                CaptureResult(CaptureOutcome.Failure(outcome.message), trigger, timestampMillis)
            }
        }
    }

    private suspend fun finishSuccessfulCapture(
        entry: PendingPhotoEntry,
        trigger: CaptureTrigger,
        timestampMillis: Long,
    ): CaptureResult {
        val uriString = try {
            photoStorage.finalizeEntry(entry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            photoStorage.discardEntry(entry)
            return CaptureResult(CaptureOutcome.Failure(GENERIC_STORAGE_ERROR), trigger, timestampMillis)
        }
        return CaptureResult(CaptureOutcome.Success(uriString), trigger, timestampMillis)
    }

    private suspend fun createEntryOrNull(timestampMillis: Long): PendingPhotoEntry? = try {
        photoStorage.createPendingEntry(timestampMillis)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    companion object {
        private const val MIN_INTERVAL_MILLIS = 1_500L
        private const val GENERIC_STORAGE_ERROR = "Couldn't save the photo. Please try again."
    }
}
