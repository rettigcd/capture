package com.example.capture.camera.domain

/**
 * The capture aspect ratio the user has selected (see "Capture Aspect Ratio and Preview Framing"
 * in app-spec.md). [widthRatio]/[heightRatio] are expressed in landscape orientation, matching how
 * the spec's own table defines them (e.g. 4:3 is `4, 3`, not re-ordered per current orientation).
 */
enum class CaptureAspectRatio(val widthRatio: Int, val heightRatio: Int) {
    RATIO_4_3(4, 3),
    RATIO_16_9(16, 9),
}

/**
 * The width component of the on-screen preview/viewfinder ratio right now: [widthRatio] as-is in
 * landscape orientation, or [heightRatio] (rotated) in portrait - see the "Setting / Landscape /
 * Portrait" table in "Capture Aspect Ratio and Preview Framing". Paired with [previewHeightRatio],
 * this is also what CameraX's `ViewPort` must be built from (see `CameraPreview.kt`) - a `ViewPort`
 * built from the un-rotated landscape ratio instead would crop the preview more tightly than the
 * captured photo actually is, since CameraX expects the aspect ratio of the on-screen View itself.
 */
fun CaptureAspectRatio.previewWidthRatio(isPortrait: Boolean): Int = if (isPortrait) heightRatio else widthRatio

/** The height counterpart to [previewWidthRatio] - see its doc for why this rotation matters. */
fun CaptureAspectRatio.previewHeightRatio(isPortrait: Boolean): Int = if (isPortrait) widthRatio else heightRatio

/**
 * The width/height ratio the camera preview container should use right now - see
 * [previewWidthRatio]/[previewHeightRatio].
 */
fun CaptureAspectRatio.previewRatio(isPortrait: Boolean): Float =
    previewWidthRatio(isPortrait).toFloat() / previewHeightRatio(isPortrait)
