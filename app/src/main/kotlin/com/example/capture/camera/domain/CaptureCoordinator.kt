package com.example.capture.camera.domain

import com.example.capture.common.DispatcherProvider
import com.example.capture.common.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place every [CaptureTrigger] - screen touch, shutter button, either volume button, or
 * a recognized voice command - is turned into an actual photograph, whether that's one image
 * (Single-Shot Mode) or [BURST_IMAGE_COUNT] of them spaced by a configurable interval (Burst Mode).
 * Nothing else in the app is allowed to call [CameraCaptureController] or [PhotoStorage] directly;
 * this keeps capture behavior identical regardless of which input produced the trigger.
 *
 * Every call to [requestCapture] gets its own [CaptureAttemptId], generated before any validation
 * happens, and every diagnostic event that request produces - accepted, rejected, or any stage of
 * an in-flight capture - carries that same id (see "Capture Request Processing" / "Diagnostic
 * Correlation" in app-spec.md). A whole burst shares one id: it is one capture *attempt* that
 * happens to produce several images, each individually distinguished by
 * [CaptureDiagnosticEvent.CameraXRequestSubmitted.burstImageNumber].
 *
 * Deliberately has no Android or Compose imports so it can be constructed and driven from a plain
 * JUnit test with fakes for [CameraCaptureController], [PhotoStorage], [CaptureErrorLogger],
 * [ImageMetadataReader], [CaptureMetadataLogger], [CaptureAttemptIdGenerator], and
 * [CaptureDiagnosticsLogger].
 */
@Singleton
class CaptureCoordinator @Inject constructor(
    private val cameraCaptureController: CameraCaptureController,
    private val photoStorage: PhotoStorage,
    private val timeProvider: TimeProvider,
    private val dispatcherProvider: DispatcherProvider,
    private val errorLogger: CaptureErrorLogger,
    private val imageMetadataReader: ImageMetadataReader,
    private val metadataLogger: CaptureMetadataLogger,
    private val captureAttemptIdGenerator: CaptureAttemptIdGenerator,
    private val diagnosticsLogger: CaptureDiagnosticsLogger,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    @Volatile
    private var lastAcceptedAtMillis: Long? = null

    /** Which [CaptureMode] currently holds [mutex], so a busy rejection can report the right [CaptureRejectionReason]. */
    @Volatile
    private var activeCaptureMode: CaptureMode? = null

    /**
     * Requests a capture for [trigger]. [mutex.tryLock] (rather than a blocking `lock`) is used so
     * a request that arrives while a capture - or an entire burst - is already in flight is
     * dropped immediately instead of queuing to run afterward; this is what satisfies "a capture
     * command received mid-burst does not start an overlapping burst" without a separate flag.
     * A request that does acquire the lock is still dropped (silently, leaving [state] as-is) if
     * it arrived within [MIN_INTERVAL_MILLIS] of the last *accepted* request, which is what
     * protects against duplicate triggers fired by the same physical action (e.g. a long touch
     * producing more than one pointer event) or rapid repeated presses.
     */
    suspend fun requestCapture(
        trigger: CaptureTrigger,
        captureMode: CaptureMode = CaptureMode.SINGLE_SHOT,
        burstIntervalMillis: Long = 0L,
        captureAspectRatio: CaptureAspectRatio = CaptureAspectRatio.RATIO_4_3,
    ) {
        withContext(dispatcherProvider.default) {
            val attemptId = captureAttemptIdGenerator.generate()
            diagnosticsLogger.logEvent(
                CaptureDiagnosticEvent.Requested(
                    attemptId,
                    timeProvider.currentTimeMillis(),
                    trigger.toDiagnosticSource(),
                    captureMode,
                    captureAspectRatio,
                ),
            )
            if (!mutex.tryLock()) {
                val reason = if (activeCaptureMode == CaptureMode.BURST) {
                    CaptureRejectionReason.BURST_ALREADY_RUNNING
                } else {
                    CaptureRejectionReason.CAPTURE_ALREADY_RUNNING
                }
                diagnosticsLogger.logEvent(
                    CaptureDiagnosticEvent.Rejected(attemptId, timeProvider.currentTimeMillis(), reason),
                )
                return@withContext
            }
            try {
                activeCaptureMode = captureMode
                val now = timeProvider.currentTimeMillis()
                val last = lastAcceptedAtMillis
                if (last != null && now - last < MIN_INTERVAL_MILLIS) {
                    diagnosticsLogger.logEvent(
                        CaptureDiagnosticEvent.Rejected(attemptId, now, CaptureRejectionReason.UNKNOWN),
                    )
                    return@withContext
                }
                lastAcceptedAtMillis = now
                diagnosticsLogger.logEvent(CaptureDiagnosticEvent.Accepted(attemptId, now))
                when (captureMode) {
                    CaptureMode.SINGLE_SHOT -> performSingleShot(trigger, now, captureAspectRatio, attemptId)
                    CaptureMode.BURST -> performBurst(trigger, burstIntervalMillis, captureAspectRatio, attemptId)
                }
            } finally {
                activeCaptureMode = null
                mutex.unlock()
            }
        }
    }

    private suspend fun performSingleShot(
        trigger: CaptureTrigger,
        timestampMillis: Long,
        captureAspectRatio: CaptureAspectRatio,
        attemptId: CaptureAttemptId,
    ) {
        _state.value = CaptureState.Capturing(trigger, attemptId)
        val context = CaptureContext(CaptureMode.SINGLE_SHOT, burstImageNumber = null, burstIntervalMillis = null, captureAspectRatio, attemptId)
        val result = performCapture(trigger, timestampMillis, context)
        diagnosticsLogger.logEvent(
            CaptureDiagnosticEvent.Completed(attemptId, timeProvider.currentTimeMillis(), result.outcome is CaptureOutcome.Success),
        )
        _state.value = CaptureState.Completed(result)
    }

    /**
     * Fires [CaptureState.BurstStarted] before any image is actually captured - this is what a
     * collector uses to trigger the single burst-accepted vibration described in "Burst Feedback",
     * independent of how many of the [BURST_IMAGE_COUNT] images ultimately succeed. An error on
     * one image does not stop the remaining ones (see "Error Handling"): each is attempted
     * regardless of prior results, and the coordinator only gives up early if capturing itself
     * becomes impossible would require throwing, which [performCapture] does not do for ordinary
     * per-image failures.
     */
    private suspend fun performBurst(
        trigger: CaptureTrigger,
        burstIntervalMillis: Long,
        captureAspectRatio: CaptureAspectRatio,
        attemptId: CaptureAttemptId,
    ) {
        _state.value = CaptureState.BurstStarted(trigger, attemptId)
        val results = mutableListOf<CaptureResult>()
        for (imageNumber in 1..BURST_IMAGE_COUNT) {
            val timestampMillis = timeProvider.currentTimeMillis()
            val context = CaptureContext(CaptureMode.BURST, imageNumber, burstIntervalMillis, captureAspectRatio, attemptId)
            results += performCapture(trigger, timestampMillis, context)
            if (imageNumber < BURST_IMAGE_COUNT) delay(burstIntervalMillis)
        }
        diagnosticsLogger.logEvent(
            CaptureDiagnosticEvent.Completed(
                attemptId,
                timeProvider.currentTimeMillis(),
                results.any { it.outcome is CaptureOutcome.Success },
            ),
        )
        _state.value = CaptureState.BurstCompleted(results, trigger)
    }

    private suspend fun performCapture(
        trigger: CaptureTrigger,
        timestampMillis: Long,
        context: CaptureContext,
    ): CaptureResult {
        val entry = createEntryOrNull(timestampMillis, context)
            ?: return CaptureResult(CaptureOutcome.Failure(GENERIC_STORAGE_ERROR), trigger, timestampMillis, context.attemptId)

        diagnosticsLogger.logEvent(
            CaptureDiagnosticEvent.CameraXRequestSubmitted(context.attemptId, timeProvider.currentTimeMillis(), context.burstImageNumber),
        )
        return when (val outcome = cameraCaptureController.captureTo(entry, context.attemptId)) {
            is CameraCaptureOutcome.Success -> {
                val result = finishSuccessfulCapture(entry, trigger, timestampMillis, context)
                val successOutcome = result.outcome
                if (successOutcome is CaptureOutcome.Success) {
                    logMetadata(context, successOutcome.uriString, timestampMillis)
                }
                result
            }
            is CameraCaptureOutcome.Failure -> {
                photoStorage.discardEntry(entry)
                logError(timestampMillis, context, entry.uriString, "CameraCaptureFailure", outcome.message, outcome.cause)
                CaptureResult(CaptureOutcome.Failure(outcome.message), trigger, timestampMillis, context.attemptId)
            }
        }
    }

    private suspend fun finishSuccessfulCapture(
        entry: PendingPhotoEntry,
        trigger: CaptureTrigger,
        timestampMillis: Long,
        context: CaptureContext,
    ): CaptureResult {
        val uriString = try {
            photoStorage.finalizeEntry(entry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            photoStorage.discardEntry(entry)
            logError(timestampMillis, context, entry.uriString, "StorageFinalizeFailure", GENERIC_STORAGE_ERROR, error)
            return CaptureResult(CaptureOutcome.Failure(GENERIC_STORAGE_ERROR), trigger, timestampMillis, context.attemptId)
        }
        diagnosticsLogger.logEvent(
            CaptureDiagnosticEvent.ImageSaved(context.attemptId, timeProvider.currentTimeMillis(), uriString),
        )
        return CaptureResult(CaptureOutcome.Success(uriString), trigger, timestampMillis, context.attemptId)
    }

    private suspend fun createEntryOrNull(timestampMillis: Long, context: CaptureContext): PendingPhotoEntry? = try {
        photoStorage.createPendingEntry(timestampMillis)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        logError(timestampMillis, context, outputDestination = null, "StorageCreateFailure", GENERIC_STORAGE_ERROR, error)
        null
    }

    private suspend fun logError(
        timestampMillis: Long,
        context: CaptureContext,
        outputDestination: String?,
        errorType: String,
        errorMessage: String,
        cause: Throwable?,
    ) {
        errorLogger.log(
            CaptureErrorLogEntry(
                timestampMillis = timestampMillis,
                captureAttemptId = context.attemptId.value,
                captureMode = context.captureMode,
                burstImageNumber = context.burstImageNumber,
                burstIntervalMillis = context.burstIntervalMillis,
                outputDestination = outputDestination,
                errorType = errorType,
                errorMessage = errorMessage,
                exceptionDetails = cause?.stackTraceToString(),
            ),
        )
    }

    /**
     * Best-effort: reads back the actual saved dimensions/orientation and logs them alongside the
     * requested ratio (see "Captured image metadata and validation" in app-spec.md). If the file
     * can't be read back, nothing is logged - the capture itself already succeeded from the user's
     * perspective, so this is diagnostic only, not another failure to report.
     */
    private suspend fun logMetadata(context: CaptureContext, uriString: String, timestampMillis: Long) {
        val metadata = imageMetadataReader.read(uriString) ?: return
        val actualAspectRatio = AspectRatioClassifier.classify(metadata.widthPx, metadata.heightPx)
        metadataLogger.log(
            CaptureMetadataLogEntry(
                timestampMillis = timestampMillis,
                captureAttemptId = context.attemptId.value,
                widthPx = metadata.widthPx,
                heightPx = metadata.heightPx,
                requestedAspectRatio = context.captureAspectRatio,
                actualAspectRatio = actualAspectRatio,
                matchesTolerance = actualAspectRatio == context.captureAspectRatio,
                outputDestination = uriString,
                exifOrientation = metadata.exifOrientation,
            ),
        )
    }

    /** Just the per-capture metadata [logError]/[logMetadata]/diagnostics need; not part of the public API. */
    private data class CaptureContext(
        val captureMode: CaptureMode,
        val burstImageNumber: Int?,
        val burstIntervalMillis: Long?,
        val captureAspectRatio: CaptureAspectRatio,
        val attemptId: CaptureAttemptId,
    )

    companion object {
        private const val MIN_INTERVAL_MILLIS = 1_500L
        private const val GENERIC_STORAGE_ERROR = "Couldn't save the photo. Please try again."
    }
}
