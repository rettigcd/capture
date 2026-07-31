package com.example.capture.camera.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AspectRatioClassifierTest {

    @Test
    fun `landscape 4-3 dimensions classify as RATIO_4_3`() {
        assertThat(AspectRatioClassifier.classify(4032, 3024)).isEqualTo(CaptureAspectRatio.RATIO_4_3)
    }

    @Test
    fun `portrait 4-3 dimensions classify the same as their landscape rotation`() {
        assertThat(AspectRatioClassifier.classify(3024, 4032)).isEqualTo(CaptureAspectRatio.RATIO_4_3)
    }

    @Test
    fun `landscape 16-9 dimensions classify as RATIO_16_9`() {
        assertThat(AspectRatioClassifier.classify(4000, 2250)).isEqualTo(CaptureAspectRatio.RATIO_16_9)
    }

    @Test
    fun `portrait 16-9 dimensions classify the same as their landscape rotation`() {
        assertThat(AspectRatioClassifier.classify(2250, 4000)).isEqualTo(CaptureAspectRatio.RATIO_16_9)
    }

    @Test
    fun `dimensions that do not match any supported ratio within tolerance classify as null`() {
        assertThat(AspectRatioClassifier.classify(1000, 1000)).isNull() // 1:1, not 4:3 or 16:9
    }

    @Test
    fun `zero or negative dimensions classify as null instead of throwing`() {
        assertThat(AspectRatioClassifier.classify(0, 100)).isNull()
        assertThat(AspectRatioClassifier.classify(100, 0)).isNull()
        assertThat(AspectRatioClassifier.classify(-100, 100)).isNull()
    }

    @Test
    fun `a small deviation within tolerance still classifies, not just an exact ratio`() {
        // 1.34 is close to but not exactly 4:3 (1.3333...) - real camera hardware rarely produces
        // an exact ratio, which is why classification uses a tolerance rather than equality.
        assertThat(AspectRatioClassifier.classify(4020, 3000)).isEqualTo(CaptureAspectRatio.RATIO_4_3)
    }

    @Test
    fun `matches returns true only when the classified ratio equals the requested one`() {
        assertThat(AspectRatioClassifier.matches(4032, 3024, CaptureAspectRatio.RATIO_4_3)).isTrue()
        assertThat(AspectRatioClassifier.matches(4032, 3024, CaptureAspectRatio.RATIO_16_9)).isFalse()
    }

    @Test
    fun `previewRatio returns the landscape ratio in landscape and its rotation in portrait`() {
        assertThat(CaptureAspectRatio.RATIO_4_3.previewRatio(isPortrait = false)).isEqualTo(4f / 3f)
        assertThat(CaptureAspectRatio.RATIO_4_3.previewRatio(isPortrait = true)).isEqualTo(3f / 4f)
        assertThat(CaptureAspectRatio.RATIO_16_9.previewRatio(isPortrait = false)).isEqualTo(16f / 9f)
        assertThat(CaptureAspectRatio.RATIO_16_9.previewRatio(isPortrait = true)).isEqualTo(9f / 16f)
    }
}
