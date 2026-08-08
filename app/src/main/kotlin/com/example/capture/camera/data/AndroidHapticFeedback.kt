package com.example.capture.camera.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.capture.camera.domain.HapticFeedback
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The user-configurable duration setting rules out `VibrationEffect.createPredefined` (its
 * built-in effects have fixed, non-configurable lengths); this uses `createOneShot` with the
 * default amplitude instead, which still respects the device's overall haptic intensity setting,
 * just not its per-effect tuning.
 */
class AndroidHapticFeedback @Inject constructor(
    @ApplicationContext context: Context,
) : HapticFeedback {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun performCaptureSuccess(durationMillis: Long) {
        if (!vibrator.hasVibrator()) return
        val safeDuration = durationMillis.coerceAtLeast(1L)
        vibrator.vibrate(VibrationEffect.createOneShot(safeDuration, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun performVideoStopped(durationMillis: Long) {
        if (!vibrator.hasVibrator()) return
        val safeDuration = durationMillis.coerceAtLeast(1L)
        // off, on, off, on: a genuine two-pulse pattern rather than one long buzz.
        val timings = longArrayOf(0L, safeDuration, GAP_MILLIS, safeDuration)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    private companion object {
        const val GAP_MILLIS = 100L
    }
}
