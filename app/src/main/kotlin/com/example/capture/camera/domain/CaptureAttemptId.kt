package com.example.capture.camera.domain

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Correlates every diagnostic log entry produced while processing one capture request (see
 * "Capture Request Processing" / "Diagnostic Correlation" in app-spec.md) - generated once, before
 * [CaptureCoordinator.requestCapture] validates the request, and threaded through everything that
 * request produces (a whole burst shares one id, matching "generate a unique Capture Attempt ID
 * before validation begins").
 */
@JvmInline
value class CaptureAttemptId(val value: String)

/** Indirection so tests can assert on a deterministic id instead of a random [UUID]. */
interface CaptureAttemptIdGenerator {
    fun generate(): CaptureAttemptId
}

@Singleton
class RandomCaptureAttemptIdGenerator @Inject constructor() : CaptureAttemptIdGenerator {
    override fun generate(): CaptureAttemptId = CaptureAttemptId(UUID.randomUUID().toString())
}
