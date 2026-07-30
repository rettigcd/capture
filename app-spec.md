Create a production-quality starter Android camera application using Kotlin and the current stable Android development tools.

Do not merely describe the project. Generate the complete project structure and source files so that I can open it in the current stable Android Studio, sync Gradle, run the tests, and deploy it to an Android phone.

## Application purpose

The app displays a full-screen live camera preview and takes a photograph when the user performs any of these actions:

1. Touches anywhere on the camera preview.
2. Presses either hardware volume button.
3. Speaks a configured command such as “photo,” “picture,” “capture,” or “cheese.”

All three inputs must invoke the same central capture operation. Do not implement three separate camera-capture paths.

Each of these actions requests either a single photograph or a burst of four, depending on the currently active capture mode (see "Capture Mode").

Use a temporary application name such as `Capture` and package name `com.example.capture`. Keep names easy to change later.

## Technology requirements

Use:

* Kotlin
* Gradle Kotlin DSL
* A Gradle version catalog in `libs.versions.toml`
* Jetpack Compose
* Material 3
* CameraX
* CameraX Compose viewfinder APIs when the current stable CameraX release supports them
* A single-activity architecture
* Android ViewModel
* Kotlin coroutines
* StateFlow
* Lifecycle-aware Compose state collection
* Dependency injection with Hilt
* Structured concurrency
* Current stable, mutually compatible dependency versions
* Java toolchains rather than relying implicitly on the computer’s installed JDK

Do not use:

* XML layouts
* Fragments
* The deprecated Camera API
* Camera2 unless CameraX cannot provide a required capability
* Global mutable state
* Business logic directly inside composables
* Hardcoded dispatchers that prevent unit testing
* Experimental or alpha dependencies unless a required feature has no stable alternative

Set reasonable modern `minSdk`, `targetSdk`, and `compileSdk` values. Explain the selected `minSdk` in the README.

## Initial application behavior

When the app starts:

1. Request camera permission.
2. Request microphone permission only when voice triggering is enabled or about to start.
3. Show a useful permission-denied state instead of a blank screen.
4. Display a full-screen rear-camera preview.
5. Allow the user to take a picture by touching anywhere on the preview.
6. Capture a picture when either volume-up or volume-down is pressed.
7. Capture a picture when a supported spoken command is recognized.
8. Display brief visual feedback when capture begins and when the picture is saved.
9. Trigger a brief haptic vibration with a user-configurable duration (see "Settings"), so the user can tell a capture request was handled without having to look at the screen: in Single-Shot Mode this fires when the picture is saved successfully; in Burst Mode this fires once when the burst is triggered, not per image and not tied to save success (see "Burst Feedback").
10. Prevent accidental duplicate captures caused by the same trigger or rapid repeated triggers.
11. Remain responsive while the image is being saved.

Consume volume-button events only while the camera screen is active. Do not change the device’s media volume when those buttons are being used as the shutter.

Touch-to-capture should not interfere with any accessibility controls or visible controls that are added later. Structure the input handling so pinch-to-zoom and tap-to-focus can be added separately.

## Photograph storage

Save pictures using `MediaStore`, not direct filesystem paths.

Save photographs into a recognizable Pictures subfolder such as:

`Pictures/Capture`

Use a timestamp-based filename and a standard image format supported by CameraX.

Correctly handle scoped storage and pending MediaStore entries. Clean up incomplete entries after a failed capture.

Return a structured capture result containing at least:

* Success or failure
* Saved content URI when successful
* Trigger source
* Timestamp
* Safe user-facing error information

Do not require broad storage permissions.

## Voice-trigger architecture

Create a voice-recognition abstraction such as:

```kotlin
interface VoiceCommandRecognizer {
    val state: StateFlow<VoiceRecognitionState>
    suspend fun start()
    suspend fun stop()
}
```

Use Android’s `SpeechRecognizer` for the initial implementation, but isolate every Android-specific speech API behind the interface.

Requirements:

* Recognize only a small configured vocabulary.
* Normalize recognized text before matching.
* Make command matching separately unit-testable.
* Handle recognizer errors without crashing.
* Release the recognizer when it is no longer needed.
* Do not automatically restart in a tight error loop.
* Do not claim that Android `SpeechRecognizer` supports reliable indefinite always-on listening.
* Clearly document its battery, privacy, network, and lifecycle limitations.
* Prefer on-device recognition when the OS and installed recognizer support it.
* Add all required manifest permissions and Android 11+ recognition-service queries.
* Make it possible to replace the implementation later with an offline continuous keyword or command-recognition engine.

Provide an obvious UI indication whenever the microphone is actively listening. Voice triggering should be user-controllable rather than silently recording.

## Capture coordination

Create one capture coordinator or use case that receives commands similar to:

```kotlin
sealed interface CaptureTrigger {
    data object ScreenTouch : CaptureTrigger
    data object VolumeUp : CaptureTrigger
    data object VolumeDown : CaptureTrigger
    data class VoiceCommand(val phrase: String) : CaptureTrigger
}
```

Every trigger must flow through this coordinator.

The coordinator must:

* Serialize capture requests.
* Reject or debounce duplicate rapid requests.
* Expose capture state.
* Record which trigger initiated each capture.
* Remain independent of Compose.
* Be testable without a physical camera.
* Avoid keeping an Activity or Compose context in a ViewModel.

Use interfaces around camera capture, media storage, voice recognition, time, and coroutine dispatchers where those abstractions improve deterministic testing.

Do not add abstraction layers that serve no practical testing or separation purpose.

## Suggested package structure

Use a clear structure similar to:

```text
com.example.voicecamera
├── app
├── camera
│   ├── data
│   ├── domain
│   └── ui
├── voice
│   ├── data
│   └── domain
├── storage
├── permissions
├── di
└── common
```

A single application module is acceptable for this startup project. Organize the code so features could later be extracted into Gradle modules without a major rewrite.

## UI requirements

The initial camera screen should include:

* Full-screen camera preview
* Small capture-status indicator (hidden while Overlay View is shown - see "Overlay image visibility")
* Voice-listening indicator
* Voice-trigger enable/disable control
* Permission and permission-related error messages (capture and file-saving errors are not shown on this screen - see "Error Handling")
* Optional visible shutter button for accessibility, even though touching the preview also captures (also hidden while Overlay View is shown)
* Content descriptions and usable semantics for interactive controls
* A swipeable overlay image that slides over the camera preview and back off-screen in response to horizontal swipe gestures (see "Overlay image visibility")
* A gear icon in the top-right corner that opens the settings screen (see "Settings")

Keep the UI intentionally simple. The objective is a clean architectural foundation, not a polished commercial camera interface.

Support portrait and landscape orientation without recreating unsafe camera state.

## Overlay image visibility

The main camera screen supports two viewing modes:

* **Camera Preview** - displays the live camera preview.
* **Overlay View** - displays the live camera preview with the selected overlay image drawn on
  top of it, covering the same space.

The user switches between these modes using horizontal swipe gestures on the main camera screen,
not a settings-screen control:

* When Camera Preview is visible, a left swipe causes the overlay image to slide in from the right
  edge of the screen until it reaches its normal position over the camera preview.
* When Overlay View is visible, a right swipe causes the overlay image to slide off the right edge
  of the screen, revealing only the camera preview.
* Swipe animations should be smooth and follow the user's gesture where practical (for example, a
  partial drag reveals a partial slide rather than only snapping at the end of the gesture).

Switching to Overlay View is display-only: it does not pause or stop the camera. All three capture
triggers (touch, volume buttons, voice command) must keep working exactly as before while the
overlay image is shown in place of the preview, so this doubles as a discreet/privacy display
mode, not merely a cosmetic one.

While Overlay View is shown, the capture-status indicator (the "Capturing…" / "Photo saved"
control) and the visible shutter button must both be hidden - conceptually they sit
underneath the overlay image, so the image fully covers them, not just the camera preview. Capture
must still work by tapping anywhere on the overlay image even though the shutter button isn't
visible. Switching back to Camera Preview must make both controls visible again.

The application shall remember whether Overlay View or Camera Preview was showing when the app was
last closed, and restore that same mode automatically the next time the app is launched. This
overlay-visibility state must persist the same way a setting would (e.g. surviving process death,
not just configuration change), even though it is not exposed as one.

Overlay visibility is controlled exclusively through the swipe gestures described above. No
user-facing setting or configuration option - on the settings screen or anywhere else - shall
exist to enable or disable the overlay.

## Capture Mode

The application supports two capture modes:

* **Single-Shot Mode** - each capture command (touch, volume button, or voice command) requests
  one image, matching the behavior described in "Application purpose" and "Initial application
  behavior" above.
* **Burst Mode** - each capture command requests four images in quick succession (see "Burst Mode"
  below).

The active capture mode is selected from the Settings page (see "Settings") rather than the camera
screen itself. The selected mode is persisted and restored automatically when the application
restarts, the same way the other settings are.

## Burst Mode

When Burst Mode is active, a single capture command - the same touch, volume-button press, or
voice command that would take one photo in Single-Shot Mode - initiates a sequence of four
image-capture requests spaced by a configurable target interval, rather than a separate capture
path. All four requests still flow through the one central capture operation described in "Capture
coordination"; Burst Mode issues that same operation four times in sequence instead of once.

The target interval between capture requests is configured on the Settings page using a slider
with discrete snap points every 250 ms. The permitted range is 250 ms (minimum) to 2 seconds
(maximum):

* 250 ms
* 500 ms
* 750 ms
* 1.0 s
* 1.25 s
* 1.5 s
* 1.75 s
* 2.0 s

The default burst interval is 500 ms - fast enough to feel like a genuine burst, while being more
likely to work consistently across a wide range of Android device camera hardware than the 250 ms
minimum would.

The selected interval is persisted and restored automatically when the application restarts, the
same way the other settings are.

The configured interval is a target, not a guarantee: actual time between captures may vary by
device due to camera hardware, exposure time, image processing, and operating-system scheduling.
Do not assume exact timing, and do not treat a slower-than-configured burst as an error on its own.

A second burst must not begin while a burst is already in progress. This is an extension of the
existing "reject or debounce duplicate rapid requests" responsibility described in "Capture
coordination," not a separate mechanism - a capture command received mid-burst is simply ignored
rather than starting an overlapping burst.

## Capture Performance

Burst Mode is intended to prioritize responsiveness over maximum image quality. During Burst Mode, the implementation should favor the lowest practical capture latency that still produces normal full-resolution photographs.

Single-Shot Mode should use the implementation's default capture behavior unless there is a demonstrated benefit to using a higher-quality capture mode.

## Burst Feedback

When a burst is successfully triggered, the device vibrates once, using the same configured
vibration duration as Single-Shot Mode's capture-success pulse (see "Settings").

This single vibration indicates only that the application accepted and started the burst request -
it does not indicate that all four images were successfully captured or saved. The application must
not vibrate separately for each image in the burst.

## Flash and Torch Restrictions

The camera flash and torch must not be used:

* While Burst Mode is active, or
* While an overlay image is visible (see "Overlay image visibility").

Whenever either condition applies, the application must ensure the flash and torch are disabled
regardless of whatever state they were previously in - actively turn them off if they were already
on, rather than merely skipping turning them on.

## Error Handling

Capture and file-saving errors must not be displayed on the main camera screen.

Instead, log errors to an application log file, in either JSON or plain-text format. Each logged
error should include, when available:

* Date and time
* Active capture mode (Single-Shot or Burst)
* Burst image number (1-4), when applicable
* Configured burst interval, when applicable
* Output filename or destination
* Error type
* Error message
* Relevant exception details

An error affecting one image in a burst must not automatically cancel the remaining capture
requests in that burst, unless continuing is not technically possible (for example, the camera
session itself has failed). A burst may therefore complete with fewer than four successfully saved
images without displaying an on-screen error.

## Settings

The main screen has a gear icon in its top-right corner that opens a separate, full-screen
settings screen (standard back navigation returns to the camera screen).

The settings screen lets the user configure:

1. **Capture-success vibration duration** - a slider with discrete snap points every 60 ms (for
   example 60, 120, 180, 240, and 300 ms).
2. **Overlay image selection** - lets the user pick which image is used as the overlay, using the
   system Photo Picker (e.g. `ActivityResultContracts.PickVisualMedia`), which requires no
   runtime storage/media permission. The selected image must be remembered across app restarts,
   not just for the lifetime of the current process, so the user is not required to re-pick it
   every time the app is launched. Do not assume the picker's own returned `content://` Uri stays
   readable after a restart - on at least one real device/OS version tested, reopening the app
   after a restart and trying to load a Uri that had otherwise been correctly remembered failed
   with `SecurityException: ... does not have permission to access picker uri ...`, meaning the
   picker's read grant for that specific item did not survive. Copy the picked image's bytes into
   app-private storage at selection time (while the picker's grant is still valid) and persist a
   reference to that private copy instead, so the durability of the setting does not depend on the
   picker's grant lifetime at all.
3. **Capture mode** - a control (for example, a two-option segmented button) for choosing between
   Single-Shot Mode and Burst Mode (see "Capture Mode").
4. **Burst interval** - a slider with discrete snap points every 250 ms from 250 ms to 2 seconds,
   defaulting to 500 ms (see "Burst Mode").

Persist all four settings, and the overlay-visibility state described above, across app restarts
(e.g. with Jetpack DataStore). Keep the settings screen testable the same way as the camera screen:
stateless composables driven by state and callbacks, with the actual persistence mechanism behind
an interface.

A setting or overlay-visibility write must be durably persisted even if the screen that triggered
it is torn down immediately afterward. If the settings screen has its own ViewModel scoped to a
navigation destination (e.g. a Navigation Compose back-stack entry), that ViewModel - and any
coroutine scope tied to its lifecycle, such as `viewModelScope` - will be cancelled as soon as that
destination is popped, which happens routinely right after picking a value (pick an image, see it
update, tap back). Do not perform the actual write on a scope that can be cancelled this way; use a
scope whose lifetime outlives the triggering screen (e.g. an injected application-level coroutine
scope) for the write itself, even if the screen's own ViewModel scope is used for everything else.

## Testing requirements

Include useful tests from the beginning.

### Local JVM unit tests

Write unit tests for:

* Voice-command normalization and matching
* Accepted and rejected voice phrases
* Capture coordinator behavior
* Multiple simultaneous trigger requests
* Rapid duplicate requests
* Capture success
* Capture failure
* State transitions
* Trigger-source recording
* ViewModel behavior
* Permission-state decision logic
* Settings persistence (defaults, reading back a saved value, and updates to each of the four settings)
* Overlay-visibility persistence (defaults, reading back a saved value, and that it correctly reflects the last swipe-driven state) even though it is not presented on the settings screen
* A setting or overlay-visibility write is durably persisted even if the owning ViewModel is cleared immediately after the write is triggered (not just that the in-memory/observed state updates) - this is the specific failure mode a screen-scoped ViewModel's coroutine scope being cancelled by navigation would otherwise hide
* Selecting an overlay image copies it into app-private storage (and persists a reference to that copy) rather than persisting the picker's own returned Uri directly - test this by asserting what gets persisted is not simply the source Uri unchanged, and that a failed copy leaves the previous selection in place instead of persisting an unreadable reference
* Burst Mode issues exactly four capture requests per capture command, spaced by the configured interval, through the same central capture operation used by Single-Shot Mode
* A capture command received while a burst is already in progress does not start a second, overlapping burst
* A successfully triggered burst vibrates exactly once, regardless of how many of the four images ultimately succeed or fail, and Single-Shot Mode's own vibration behavior is unaffected
* An error on one image within a burst does not cancel the remaining requests in that burst unless continuing is technically impossible, and the burst's capture result reflects fewer than four successes without raising an on-screen error
* Logged error entries include the documented fields (date/time, active capture mode, burst image number and configured interval when applicable, output filename/destination, error type, error message, exception details) when available
* Flash/torch requests are suppressed (and any already-on flash/torch is turned off) while Burst Mode is active and while the overlay image is visible

Use fakes rather than mocks when practical.

Use coroutine test utilities and injected test dispatchers. Do not use real delays in tests.

### Compose UI tests

Write Compose tests verifying:

* Permission-denied content is displayed correctly
* Camera UI appears when permission is granted
* Touching the preview dispatches a screen-touch capture trigger
* Pressing the visible shutter control dispatches a capture trigger
* Voice-listening state is visibly represented
* Capture-in-progress state is represented
* A capture or file-saving error does not display any error text or detail on the main camera screen (see "Error Handling")
* A left swipe on the camera preview shows the overlay image (Overlay View), and a right swipe on the overlay image hides it again (Camera Preview)
* The restored overlay-visibility state (from the last time the app was closed) is reflected correctly on launch
* No settings-screen control exists for enabling or disabling the overlay
* The settings screen reflects and updates each of the four stored settings, including the capture-mode selector and the burst-interval slider

The composables must accept state and callbacks so they can be tested without starting a real camera.

### Instrumented tests

Add a small, meaningful instrumented-test foundation. Do not attempt to make normal CI tests depend on a physical camera.

Separate CameraX integration from the majority of the application so ordinary tests can use a fake camera controller.

Where hardware behavior cannot be reliably automated, document an explicit manual device-test checklist.

## Quality requirements

Enable and configure:

* Android Lint
* Kotlin compiler warnings
* Compose compiler reports or metrics only when they can be enabled without making normal builds cumbersome
* Ktlint or an equivalent Kotlin formatter/linter
* Unit-test coverage reporting
* A CI-friendly command that builds and runs local tests

Treat warnings reasonably strictly, but do not enable settings that make generated code or known framework behavior impractical.

All production code should:

* Avoid `GlobalScope`
* Avoid blocking the main thread
* Close or release owned resources
* Handle cancellation correctly
* Use immutable UI state
* Avoid exposing mutable flows
* Avoid swallowing exceptions
* Log technical information without exposing private recognized speech or sensitive image information

## Build verification

After generating the files, verify the project logically against these commands and correct any obvious incompatibilities:

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

The first four commands should not require a connected device. Explain that the final command requires an emulator or device.

Do not state that the project builds successfully unless you actually ran the commands. When execution is unavailable, say that the project was generated but not executed.

## Documentation

Create a useful `README.md` containing:

* Project purpose
* Architecture overview
* Dependency choices
* Required permissions
* Voice-recognition limitations
* Privacy considerations
* How photographs are stored
* How to build and run
* How to run each test category
* How to change the voice-command vocabulary
* How to replace `SpeechRecognizer`
* Manual test checklist for touch, voice, volume buttons, rotation, permissions, image storage, overlay swipe gestures (smooth gesture-following animation is hard to unit test), and Burst Mode (actual device timing between captures, flash/torch staying off, and the single burst-triggered vibration)
* Known device-manufacturer differences involving volume keys, camera behavior, and speech recognition

Include a Mermaid component or flow diagram showing:

```text
Touch / Volume / Voice
          ↓
Capture Coordinator
          ↓
Camera Capture Interface
          ↓
CameraX + MediaStore
```

## Implementation sequence

Build the project in these stages:

1. Gradle project and quality configuration
2. Domain models and interfaces
3. Capture coordinator and unit tests
4. Compose camera-screen state and UI tests
5. CameraX preview and image capture
6. MediaStore saving
7. Volume-button handling
8. SpeechRecognizer implementation
9. Hilt dependency wiring
10. Permission handling
11. Instrumented-test foundation
12. README and manual-test checklist
13. Final review for lifecycle, threading, cleanup, and testability

At each stage, keep the project internally consistent. Do not leave pseudocode, TODO-only methods, placeholder imports, or source files that knowingly fail compilation.

When a design decision is uncertain, choose the simplest production-sensible implementation and record the decision in the README rather than stopping to ask me minor questions.

Begin by showing the final file tree. Then provide every required file in a clearly labeled code block with its full relative path.
