package com.example.capture

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.capture.camera.ui.CameraViewModel
import com.example.capture.ui.theme.CaptureTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The app's single Activity, hosting two Compose destinations ([CaptureApp]: camera and
 * settings). `AndroidManifest.xml` declares `android:configChanges` for orientation so this
 * Activity - and the CameraX binding tied to it - is not recreated on rotation; Compose still
 * recomposes the layout for the new orientation on its own.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Passed explicitly into CaptureApp/CameraRoute instead of letting that composable resolve
    // its own hiltViewModel() default, so this Activity-scoped instance and the one the camera
    // screen observes are guaranteed to be the same object - see CameraRoute's kdoc.
    private val cameraViewModel: CameraViewModel by viewModels()

    // Updated via SideEffect on every recomposition of CaptureApp's NavHost so onKeyDown/onKeyUp
    // (plain Activity callbacks, not Compose) can check which destination is currently showing.
    private var navController: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CaptureTheme {
                val controller = rememberNavController()
                SideEffect { navController = controller }
                CaptureApp(cameraViewModel = cameraViewModel, navController = controller)
            }
        }
    }

    /**
     * Volume buttons act as an additional shutter trigger only while the camera screen is the
     * active destination - never while the settings screen is showing, where they should behave
     * as ordinary volume keys.
     *
     * Returning `true` marks the event as consumed, which is what prevents Android's default
     * media-volume-adjustment behavior from also firing for these key presses.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isCameraScreenActive()) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                cameraViewModel.onVolumeUpPressed()
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                cameraViewModel.onVolumeDownPressed()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isCameraScreenActive()) return super.onKeyUp(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun isCameraScreenActive(): Boolean = navController?.currentDestination?.route == CAMERA_ROUTE
}
