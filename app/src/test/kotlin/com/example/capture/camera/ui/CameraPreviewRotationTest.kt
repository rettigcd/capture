package com.example.capture.camera.ui

import android.view.Surface
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Plain JVM test (no Robolectric needed): [surfaceRotationFor] is pure arithmetic over an `Int`,
 * with no real Android runtime dependency beyond reading `Surface.ROTATION_*`'s literal constant
 * values.
 */
class CameraPreviewRotationTest {

    @Test
    fun `degrees near zero map to ROTATION_0`() {
        assertThat(surfaceRotationFor(0)).isEqualTo(Surface.ROTATION_0)
        assertThat(surfaceRotationFor(44)).isEqualTo(Surface.ROTATION_0)
        assertThat(surfaceRotationFor(315)).isEqualTo(Surface.ROTATION_0)
        assertThat(surfaceRotationFor(359)).isEqualTo(Surface.ROTATION_0)
    }

    @Test
    fun `degrees around 90 map to ROTATION_270`() {
        assertThat(surfaceRotationFor(45)).isEqualTo(Surface.ROTATION_270)
        assertThat(surfaceRotationFor(90)).isEqualTo(Surface.ROTATION_270)
        assertThat(surfaceRotationFor(134)).isEqualTo(Surface.ROTATION_270)
    }

    @Test
    fun `degrees around 180 map to ROTATION_180`() {
        assertThat(surfaceRotationFor(135)).isEqualTo(Surface.ROTATION_180)
        assertThat(surfaceRotationFor(180)).isEqualTo(Surface.ROTATION_180)
        assertThat(surfaceRotationFor(224)).isEqualTo(Surface.ROTATION_180)
    }

    @Test
    fun `degrees around 270 map to ROTATION_90`() {
        assertThat(surfaceRotationFor(225)).isEqualTo(Surface.ROTATION_90)
        assertThat(surfaceRotationFor(270)).isEqualTo(Surface.ROTATION_90)
        assertThat(surfaceRotationFor(314)).isEqualTo(Surface.ROTATION_90)
    }
}
