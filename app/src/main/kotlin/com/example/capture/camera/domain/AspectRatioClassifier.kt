package com.example.capture.camera.domain

import kotlin.math.abs

/**
 * Classifies actual saved-image dimensions against the supported [CaptureAspectRatio]s using a
 * tolerance rather than exact floating-point equality, and treating a portrait/landscape rotated
 * pair as the same ratio - see "Captured image metadata and validation" in app-spec.md.
 */
object AspectRatioClassifier {

    /** Two ratios within this fraction of each other are considered the same. */
    private const val TOLERANCE = 0.02

    /**
     * Returns the [CaptureAspectRatio] [widthPx]x[heightPx] matches within [TOLERANCE], or `null`
     * if it doesn't match any supported ratio closely enough.
     */
    fun classify(widthPx: Int, heightPx: Int): CaptureAspectRatio? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val normalizedRatio = maxOf(widthPx, heightPx).toDouble() / minOf(widthPx, heightPx)
        return CaptureAspectRatio.entries.firstOrNull { ratio ->
            val target = ratio.widthRatio.toDouble() / ratio.heightRatio
            abs(normalizedRatio - target) <= TOLERANCE
        }
    }

    /** Whether [widthPx]x[heightPx] classifies as [requested] within [TOLERANCE]. */
    fun matches(widthPx: Int, heightPx: Int, requested: CaptureAspectRatio): Boolean =
        classify(widthPx, heightPx) == requested
}
