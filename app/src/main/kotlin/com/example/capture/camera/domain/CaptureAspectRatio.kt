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
 * The width/height ratio the camera preview container should use right now: the landscape ratio
 * as-is in landscape orientation, or its rotated (height:width) counterpart in portrait - see the
 * "Setting / Landscape / Portrait" table in "Capture Aspect Ratio and Preview Framing".
 */
fun CaptureAspectRatio.previewRatio(isPortrait: Boolean): Float =
    if (isPortrait) heightRatio.toFloat() / widthRatio else widthRatio.toFloat() / heightRatio
