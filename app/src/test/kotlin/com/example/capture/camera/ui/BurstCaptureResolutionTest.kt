package com.example.capture.camera.ui

import com.example.capture.camera.domain.CaptureAspectRatio
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Plain JVM test (no Robolectric needed): [burstCaptureResolution] returns [PixelSize], not
 * `android.util.Size`, specifically so it doesn't need one - see [PixelSize]'s kdoc.
 */
class BurstCaptureResolutionTest {

    @Test
    fun `4-3 resolution is exactly 4-3 and well under full sensor resolution`() {
        val resolution = burstCaptureResolution(CaptureAspectRatio.RATIO_4_3)

        assertThat(resolution.width.toLong() * 3).isEqualTo(resolution.height.toLong() * 4)
        assertThat(resolution.width.toLong() * resolution.height).isLessThan(4_000_000L)
    }

    @Test
    fun `16-9 resolution is exactly 16-9 and well under full sensor resolution`() {
        val resolution = burstCaptureResolution(CaptureAspectRatio.RATIO_16_9)

        assertThat(resolution.width.toLong() * 9).isEqualTo(resolution.height.toLong() * 16)
        assertThat(resolution.width.toLong() * resolution.height).isLessThan(4_000_000L)
    }
}
