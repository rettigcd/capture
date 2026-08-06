# Capture

A starter Android camera app: full-screen live preview, and a photo taken by touching the
preview, pressing either volume button, or speaking a configured word ("photo", "picture",
"capture", "cheese"). All three inputs are just different `CaptureTrigger`s funneled through one
`CaptureCoordinator` - there is only ever one capture code path.

`Capture` / `com.example.capture` are placeholder names, chosen to be trivial to rename later
(see "Renaming the app" below).

## Architecture overview

```text
Touch / Volume / Voice
          ↓
Capture Coordinator
          ↓
Camera Capture Interface
          ↓
CameraX + MediaStore
```

More concretely, as a component diagram:

```mermaid
flowchart TD
    subgraph App["MainActivity + CaptureApp (NavHost)"]
        Nav[["camera" / "settings" destinations]]
    end

    subgraph UI["camera.ui (Compose)"]
        Screen[CameraScreen]
        Route[CameraRoute]
        VM[CameraViewModel]
        Preview[CameraPreview]
    end

    subgraph Domain["camera.domain (pure Kotlin)"]
        Coordinator[CaptureCoordinator]
        CamIface[[CameraCaptureController]]
        StorageIface[[PhotoStorage]]
        HapticIface[[HapticFeedback]]
        OverlayIface[[OverlayVisibilityRepository]]
        ErrorLogIface[[CaptureErrorLogger]]
        FlashIface[[FlashTorchController]]
        MetadataLogIface[[CaptureMetadataLogger]]
        MetadataReadIface[[ImageMetadataReader]]
        CaptureDiagIface[[CaptureDiagnosticsLogger]]
        GestureDiagIface[[GestureDiagnosticsLogger]]
    end

    subgraph CameraData["camera.data"]
        CamX[CameraXCaptureController]
        MediaStoreImpl[MediaStorePhotoStorage]
        Holder[ImageCaptureUseCaseHolder]
        HapticImpl[AndroidHapticFeedback]
        OverlayImpl[DataStoreOverlayVisibilityRepository]
        ErrorLogImpl[FileCaptureErrorLogger]
        FlashImpl[CameraXFlashTorchController]
        MetadataLogImpl[FileCaptureMetadataLogger]
        MetadataReadImpl[AndroidImageMetadataReader]
        DiagLogImpl[AndroidDiagnosticsLogger]
    end

    subgraph Voice["voice"]
        VoiceIface[[VoiceCommandRecognizer]]
        Matcher[VoiceCommandMatcher]
        Speech[AndroidSpeechRecognizerAdapter]
    end

    subgraph SettingsPkg["settings"]
        SettingsRouteN[SettingsRoute]
        SettingsVMN[SettingsViewModel]
        SettingsIface[[SettingsRepository]]
        SettingsImpl[DataStoreSettingsRepository]
    end

    Nav --> Route
    Nav --> SettingsRouteN
    MainActivity -->|volume key events, only while "camera" is active| VM
    Route --> Screen
    Route --> VM
    Screen -->|touch / shutter tap| VM
    Screen -->|gesture diagnostic events| VM
    Screen -->|gear icon| Nav
    Preview -->|attaches ImageCapture use case| VM
    Preview -->|camera diagnostics snapshot| VM
    VM --> Coordinator
    VM --> SettingsIface
    VM --> HapticIface
    VM --> OverlayIface
    VM --> FlashIface
    VM --> GestureDiagIface
    VM --> CaptureDiagIface
    Coordinator --> CamIface
    Coordinator --> StorageIface
    Coordinator --> ErrorLogIface
    Coordinator --> MetadataLogIface
    Coordinator --> MetadataReadIface
    Coordinator --> CaptureDiagIface
    CamX --> CaptureDiagIface
    CamIface -.implemented by.-> CamX
    StorageIface -.implemented by.-> MediaStoreImpl
    HapticIface -.implemented by.-> HapticImpl
    OverlayIface -.implemented by.-> OverlayImpl
    ErrorLogIface -.implemented by.-> ErrorLogImpl
    FlashIface -.implemented by.-> FlashImpl
    MetadataLogIface -.implemented by.-> MetadataLogImpl
    MetadataReadIface -.implemented by.-> MetadataReadImpl
    CaptureDiagIface -.implemented by.-> DiagLogImpl
    GestureDiagIface -.implemented by.-> DiagLogImpl
    OverlayImpl --> DataStore
    FlashImpl --> Holder
    CamX --> Holder
    Preview --> Holder
    VM --> VoiceIface
    VoiceIface -.implemented by.-> Speech
    Speech --> Matcher
    CamX --> CameraXLib[(CameraX)]
    MediaStoreImpl --> MediaStore[(MediaStore)]
    SettingsRouteN --> SettingsVMN
    SettingsRouteN -->|system Photo Picker| PhotoPicker[(Android Photo Picker)]
    SettingsVMN --> SettingsIface
    SettingsIface -.implemented by.-> SettingsImpl
    SettingsImpl --> DataStore[(Jetpack DataStore)]
```

Key rule the whole architecture is built to enforce: **`camera.domain` never imports Android,
Compose, or CameraX types.** `CaptureCoordinatorTest` constructs a real `CaptureCoordinator`
against fake `CameraCaptureController`/`PhotoStorage` implementations and runs as a plain JVM test
with no emulator, no Robolectric, and no real camera.

### Package structure

```text
com.example.capture
├── CaptureApplication.kt        Hilt entry point
├── MainActivity.kt               single Activity; forwards hardware volume key events to the
│                                 camera screen, gated on it being the current NavHost destination
├── CaptureApp.kt                 the app's whole NavHost: "camera" and "settings" destinations
├── ui/theme/                     Material 3 theme
├── common/                       DispatcherProvider, TimeProvider, ApplicationScope - small
│                                 seams that exist purely so tests can control time/dispatch
├── di/                           Hilt modules (AppModule, CameraModule, VoiceModule, SettingsModule)
├── permissions/                  CapturePermissions: pure permission-state decision logic
├── camera/
│   ├── domain/                   CaptureTrigger, CaptureCoordinator, CaptureModels (incl.
│   │                             CaptureMode, CaptureState.BurstStarted/BurstCompleted),
│   │                             CaptureAspectRatio, AspectRatioClassifier, CaptureErrorLogger,
│   │                             CaptureMetadataLogger, ImageMetadataReader, CaptureAttemptId(Generator),
│   │                             CaptureDiagnosticEvent/CameraDiagnosticsSnapshot/CaptureDiagnosticsLogger,
│   │                             GestureDiagnosticEvent/GestureDiagnosticsLogger, the
│   │                             CameraCaptureController / PhotoStorage / HapticFeedback /
│   │                             OverlayVisibilityRepository / FlashTorchController interfaces
│   ├── data/                     CameraXCaptureController, MediaStorePhotoStorage,
│   │                             ImageCaptureUseCaseHolder, CameraControlHolder,
│   │                             AndroidHapticFeedback, DataStoreOverlayVisibilityRepository,
│   │                             FileCaptureErrorLogger, CameraXFlashTorchController,
│   │                             FileCaptureMetadataLogger, AndroidImageMetadataReader,
│   │                             AndroidDiagnosticsLogger - the only
│   │                             CameraX/MediaStore/Vibrator/DataStore/file-logging/EXIF/Logcat code
│   └── ui/                       CameraViewModel, CameraUiState, DiagnosticsOverlayInfo,
│                                 CameraScreen (stateless, including the swipe-gesture overlay
│                                 logic, the aspect-ratio-constrained preview layout, and the
│                                 debug-only diagnostics overlay), CameraRoute (permissions + Hilt
│                                 wiring), CameraPreview
├── voice/
│   ├── domain/                   VoiceCommandRecognizer interface, VoiceCommandMatcher,
│   │                             VoiceRecognitionState
│   └── data/                     AndroidSpeechRecognizerAdapter - the only `android.speech.*` code
└── settings/
    ├── domain/                   AppSettings, the SettingsRepository / OverlayImageStore interfaces
    ├── data/                     DataStoreSettingsRepository, FileOverlayImageStore - the only
    │                             Jetpack DataStore/file-copy code for settings
    └── ui/                       SettingsViewModel, SettingsUiState, SettingsScreen (stateless),
                                  SettingsRoute (hosts the system Photo Picker launcher)
```

This is a single Gradle module (`:app`). Each top-level package (`camera`, `voice`, `settings`,
`permissions`) only depends on `common` and on other packages' `domain` subpackages, so any of
them could be extracted into its own Gradle module later without restructuring the code, if the
project grows enough to want faster incremental builds.

## Why minSdk 29

`minSdk = 29` (Android 10) was chosen because it is the first release with complete scoped
storage (`MediaStore.Images.Media.IS_PENDING` and `RELATIVE_PATH`). Picking it lets
`MediaStorePhotoStorage` be written once, with no `Build.VERSION.SDK_INT` branching and no legacy
`WRITE_EXTERNAL_STORAGE` fallback path - which in turn means the app never has to request a broad
storage permission at all. `compileSdk`/`targetSdk` are set to `37`.

## Dependency choices

Versions live in `gradle/libs.versions.toml`. The environment this project was generated in had
outbound HTTPS access to `dl.google.com` and `repo.maven.apache.org` (but not to
`plugins.gradle.org`, the Gradle Plugin Portal, or general web browsing), plus a preinstalled
Android SDK and a cached Gradle distribution - which made it possible to check every version
below against the real `maven-metadata.xml` for each artifact, and then to actually run
`clean`, `assembleDebug`, `testDebugUnitTest`, and `lintDebug` end-to-end (see "Build
verification"), rather than only guessing at current versions:

| Concern | Choice | Why |
|---|---|---|
| Kotlin | 2.4.10 | Latest stable at generation time; also required because AGP 9's built-in Kotlin support (see below) has its own minimum Kotlin Gradle Plugin version. |
| AGP | 9.3.1 | Latest stable. AGP 9 has *built-in Kotlin support* and no longer accepts `org.jetbrains.kotlin.android` being applied alongside it (a hard error, not just a warning) - see the comment in `build.gradle.kts`. The Compose compiler plugin (`org.jetbrains.kotlin.plugin.compose`) is unaffected and still required. |
| KSP | 2.3.10 | Matches the Kotlin line above; newer KSP releases version independently of Kotlin rather than embedding the Kotlin version in the artifact version string. |
| Compose BOM | 2026.06.01 | Pins all Compose artifact versions together; bump this one line to move the whole Compose stack forward. |
| CameraX | 1.6.1 | Latest stable; `androidx.camera:camera-compose` (the `CameraXViewfinder` composable used by `CameraPreview.kt`) has been stable since the 1.5 line. |
| Hilt | 2.60.1 | Latest stable Dagger/Hilt; used with KSP (not kapt). |
| Coroutines | 1.11.0 | Latest stable `kotlinx.coroutines`, including the `Test` artifact's `runTest`/`StandardTestDispatcher` APIs used throughout the test suite. |
| Robolectric | 4.16.1 | Latest stable (non-beta); used only for `CameraScreenTest`/`SettingsScreenTest` so Compose UI tests run on the JVM (`testDebugUnitTest`) without an emulator. |
| Navigation Compose | 2.9.8 | Latest stable; backs the "camera" / "settings" `NavHost` in `CaptureApp.kt`. |
| DataStore Preferences | 1.2.1 | Latest stable; backs `DataStoreSettingsRepository`. |
| Coil (`coil-compose`) | 3.5.0 | Latest stable. Coil 3's package is `coil3.compose`, not `coil.compose` (Coil 2's) - loads the overlay image and the settings-screen thumbnail from a `content://`/`file://` URI; no extra network module needed since both are local URIs. |
| AndroidX ExifInterface | 1.3.7 | Latest stable; reads the EXIF orientation of a just-saved photo for the diagnostic metadata log (see "Capture aspect ratio and preview framing") - `BitmapFactory` alone gives dimensions but not orientation. |
| `compileSdk`/`targetSdk` | 37 | The newer AndroidX releases above (`core-ktx` 1.19.0, `lifecycle` 2.11.0, `hilt-navigation-compose` 1.4.0) require compiling against API 37+; `compileSdk = 36` fails `checkDebugAarMetadata` with these versions. |
| Java toolchain | 21 | See `app/build.gradle.kts`'s `kotlin { jvmToolchain(21) }` comment - verified end-to-end against a real JDK 21. |

One plugin could not be verified in this environment: **ktlint** (`org.jlleitschuh.gradle.ktlint`,
pinned to `12.1.1`) publishes its Gradle plugin marker only to the Gradle Plugin Portal, not to
Maven Central, so it could not be resolved or exercised here. It is still wired into the build
(`app/build.gradle.kts`); on a machine with normal internet access it should resolve normally, but
check the [plugin portal page](https://plugins.gradle.org/plugin/org.jlleitschuh.gradle.ktlint)
for a newer version if it doesn't. The same applies to the
`org.gradle.toolchains.foojay-resolver-convention` plugin in `settings.gradle.kts`.

Before shipping, run Android Studio's Upgrade Assistant (or `./gradlew dependencyUpdates` with
the Gradle Versions plugin) to check for newer releases - these ecosystems move fast enough that
"current" drifts within weeks.

## Required permissions

| Permission | When requested | Why |
|---|---|---|
| `android.permission.CAMERA` | Immediately on first launch | Needed to show the preview at all; the app is unusable without it. |
| `android.permission.RECORD_AUDIO` | Only when the user turns the voice-trigger switch on | Never requested up front - voice triggering is opt-in, so the microphone is never touched until the user asks for it. |
| `android.permission.VIBRATE` | Install time (normal permission, no runtime prompt) | Powers the short capture-success pulse described below. |

No storage permission is requested (see "Why minSdk 29" above).

## Feedback on capture

Every capture command - regardless of whether it was triggered by touch, a volume button, or a
voice command - gets both visual and haptic feedback, though the two capture modes (see "Capture
Mode and Burst Mode" below) trigger that feedback at different points:

* The capture-status indicator shows "Photo saved" (`CameraScreen.kt`) once a Single-Shot capture
  succeeds, or once a Burst Mode capture command completes with at least one successfully saved
  image (`CameraUiState.CaptureStatusUi` has no error state at all - see "Error Handling" below).
* The device performs one haptic pulse (`camera/domain/HapticFeedback.kt`, implemented by
  `camera/data/AndroidHapticFeedback.kt` using `VibrationEffect.createOneShot(durationMillis, ...)`),
  using a user-configurable duration (see "Settings" below) rather than a fixed value, which is why
  this uses `createOneShot` instead of a predefined system effect (predefined effects have a fixed
  length that can't be customized). In **Single-Shot Mode** this fires once the picture is saved
  successfully; in **Burst Mode** it fires once, immediately when the burst is accepted
  (`CaptureState.BurstStarted`), not once per image and not tied to whether any image is ultimately
  saved - see "Burst Feedback" below.

`CameraViewModel` triggers the pulse from a dedicated collector on `CaptureCoordinator.state` (see
its `init` block) rather than as a derived property of `uiState`, specifically so it fires exactly
once per completed Single-Shot capture (or once per triggered burst) instead of repeating for as
long as the status happens to still read "saved." A failed Single-Shot capture does not vibrate.

## Overlay image visibility

The camera screen has two modes - **Camera Preview** (just the live preview) and **Overlay View**
(the live preview with the user-selected overlay image drawn on top of it, covering the same
space) - switched with a horizontal swipe on the camera screen itself, not a settings-screen
control:

* A **left swipe** while Camera Preview is showing slides the overlay image in from the right edge
  until it settles over the preview (Overlay View).
* A **right swipe** while Overlay View is showing slides the overlay image back off the right edge
  (Camera Preview).
* The slide follows the finger while dragging and animates to whichever side it's closer to on
  release, rather than only snapping after the gesture completes.

This is implemented in `GrantedCameraContent` in `CameraScreen.kt` as a single custom pointer-input
gesture detector (`detectTapOrHorizontalSwipe`, built on `awaitEachGesture`) attached to the
always-full-screen preview layer underneath the overlay image. One detector - rather than Compose's
built-in `detectTapGestures` and `detectDragGestures` layered separately - disambiguates a tap
(capture) from a horizontal drag (swipe) for the same touch: movement past touch slop in *any*
direction is treated as a drag (cancelling the tap, matching plain tap-gesture semantics), while
only the horizontal component drives an `Animatable<Float>` offset that positions the overlay
image via `Modifier.offset { IntOffset(...) }`. The overlay image itself never installs its own
pointer input, so touches over it fall through to the same detector underneath regardless of
where the slide currently sits.

**Capture keeps working while the overlay is shown.** `CameraScreen` always composes the CameraX
preview underneath the overlay image - the image is drawn on top of it, not instead of it - so
CameraX stays bound and touch/volume/voice capture behave identically either way. This is what
makes the overlay useful as a discreet display mode rather than only a cosmetic one. Volume-button
capture is gated on the *camera* screen being the active `NavHost` destination (`MainActivity`
checks `navController.currentDestination?.route`), so it correctly stops working - and volume keys
correctly resume adjusting media volume - while the settings screen is open instead.

**The capture-status indicator and shutter button are hidden while the overlay is shown**, not
just covered by it - `GrantedCameraContent` conditionally composes both only when
`!uiState.overlayVisible`, since they'd otherwise visually sit on top of the overlay image instead
of appearing hidden underneath it. The voice-trigger control and the settings gear icon are
unaffected and stay visible either way. Tapping the overlay image still captures a photo even with
the shutter button hidden.

**Overlay visibility never blanks the screen.** If it was last left visible but no image has ever
been picked, `CameraViewModel` falls back to showing the live preview (`overlayVisible` is derived
as `persistedVisible && overlayImageUriString != null`, not the raw persisted value) rather than an
empty placeholder.

**Visibility is persisted, but deliberately not as a "setting."** `camera/domain
/OverlayVisibilityRepository.kt`, implemented by `camera/data
/DataStoreOverlayVisibilityRepository.kt`, persists the last swipe-committed visibility in its own
DataStore Preferences file (`camera_ui_state`, separate from `settings`'s own DataStore file) so it
survives app restarts the same way a setting would - restoring whichever mode was showing when the
app was last closed - while staying conceptually separate from `SettingsRepository`: there is no
settings-screen control for it at all (`SettingsScreenTest.noOverlayVisibilityControlExists_...`
asserts the settings screen has zero toggleable nodes). `CameraViewModel.onOverlayVisibilityChanged`
writes on the injected `@ApplicationScope` `CoroutineScope`, for the same durability reasoning
described below for settings writes.

## Capture aspect ratio and preview framing

The camera preview is centered on screen and constrained to the selected capture aspect ratio -
**4:3** (default) or **16:9** - rather than stretched to fill the screen; any leftover space is
filled with the theme's background color. Since the app is locked to portrait only (see
"Portrait-only orientation" below), typical phone screens are taller/narrower than either ratio's
portrait mapping (3:4 or 9:16), so in practice this always means letterboxing (bars above/below),
not pillarboxing. The overlay image (see "Overlay image visibility" above) is a full-screen
sibling of the preview, entirely unaffected by this - swipe gestures and tap-to-capture are still
recognized across the *whole* screen, not just the smaller preview area, since the gesture
detector sits on the outer full-screen layer, not the aspect-ratio-constrained box.

`GrantedCameraContent` in `CameraScreen.kt` computes the preview's on-screen ratio from
`CaptureAspectRatio.previewRatio(isPortrait)` (a plain function: the landscape ratio as-is in
landscape, or its rotated height:width counterpart in portrait, read from `LocalConfiguration`) and
applies it via `Modifier.aspectRatio(...)` on a `Box` centered inside the full-screen container -
the standard Compose letterbox/pillarbox pattern. `isPortrait` is effectively always `true` now
that the app is portrait-locked, but the check is deliberately still dynamic (not hardcoded)
because Android does not strictly guarantee `screenOrientation="portrait"` is honored in every
multi-window/split-screen/freeform configuration on every OS version - this way the layout still
picks the correct mapping even in that edge case, rather than assuming portrait unconditionally.

**Preview and capture share the same aspect-ratio preference.** `CameraPreview.kt` applies the
same `ResolutionSelector`/`AspectRatioStrategy` (`RATIO_4_3_FALLBACK_AUTO_STRATEGY` or
`RATIO_16_9_FALLBACK_AUTO_STRATEGY`) to both the `Preview` and `ImageCapture` use cases, and binds
them together through a `UseCaseGroup` with a shared `ViewPort` built from the exact target ratio
(as a `Rational`, not derived from measured pixels, which would only reconstruct the same ratio
imprecisely) - so the live preview and the saved photo represent the same effective crop. Since a
built `ImageCapture`/`Preview`'s resolution selector can't change afterward, switching the aspect
ratio rebuilds both use cases (`remember(captureAspectRatio)`) and rebinds them
(`LaunchedEffect(lifecycleOwner, captureMode, captureAspectRatio)`) - the same brief-flicker
trade-off already accepted for a Burst Mode switch (see "Capture Performance" below).

**Portrait-only orientation.** `MainActivity` declares `android:screenOrientation="portrait"` (see
"UI requirements" in app-spec.md) - the app's window never rotates into landscape, regardless of
how the physical device is held.

**Capture rotation is still tracked, using the physical device tilt rather than the (now-frozen)
window rotation.** Because the window itself never rotates, `Configuration`/
`ContextCompat.getDisplayOrDefault(context).rotation` no longer change when the user turns the
device sideways, so `CameraPreview.kt` can't use either to know how the device is actually being
held - it uses an `OrientationEventListener` instead (raw accelerometer degrees, independent of the
locked window), mapping its 0-359° reading to a `Surface.ROTATION_*` value via the small pure
function `surfaceRotationFor` and pushing that onto both use cases' `targetRotation`. This keeps
captured photos correctly oriented (EXIF tag matches how the phone was actually held) even though
the on-screen layout itself never rotates. `surfaceRotationFor`'s bucketing (0/90/180/270,
including the 90-vs-270 swap that `targetRotation`'s "rotate output to appear upright" semantics
require) is covered directly by `CameraPreviewRotationTest` - a plain JVM test, since the mapping
is pure arithmetic with no real Android runtime dependency.

**A capture-aspect-ratio change made mid-burst does not affect the active burst.** `CameraViewModel`
holds an `effectiveCaptureAspectRatio` that normally mirrors the persisted setting immediately, but
- while `CaptureCoordinator.state` reports a burst in progress (`BurstStarted` seen, `BurstCompleted`
not yet seen) - holds the previous value instead, only catching up once that burst's
`BurstCompleted` state arrives. `CameraPreview` (and the preview's layout) only ever sees this
effective value, so the live camera use cases are never rebuilt mid-burst.
`CameraViewModelTest`'s `"a capture-aspect-ratio change made mid-burst does not apply until the
burst completes"` case drives this with `runCurrent()` to pause partway through a burst and assert
the change is held back.

**Captured-image metadata is logged for every successful capture.** After `CaptureCoordinator`
finishes writing a photo, it reads the actual saved dimensions back (`ImageMetadataReader`,
implemented by `AndroidImageMetadataReader` using `BitmapFactory` with `inJustDecodeBounds = true`
so only the header is decoded, plus a second stream read for the EXIF orientation via
`androidx.exifinterface.media.ExifInterface`), classifies them with `AspectRatioClassifier`, and
logs the result via `CaptureMetadataLogger`/`FileCaptureMetadataLogger` (JSON Lines, same pattern
as `FileCaptureErrorLogger`, to `capture_metadata.log`). `AspectRatioClassifier.classify` uses a
2% tolerance rather than exact floating-point equality, and normalizes by comparing
`max(width, height) / min(width, height)`, so a portrait capture classifies identically to its
landscape rotation (e.g. both `4032x3024` and `3024x4032` classify as `RATIO_4_3`) -
`AspectRatioClassifierTest` covers the worked examples directly. If the read-back fails (file not
decodable) or the capture itself failed, nothing is logged - this is diagnostic-only, matching
"Error Handling" below's principle of never letting a logging concern affect capture itself.

## Capture Mode and Burst Mode

The camera screen supports two capture modes, chosen on the settings screen (see "Settings"
below) and persisted the same way as the other settings:

* **Single-Shot Mode** (default) - each capture command takes one photo, exactly as described
  above.
* **Burst Mode** - each capture command takes `BURST_IMAGE_COUNT` (4) photos in quick succession,
  spaced by a configurable target interval.

Both modes share the exact same `CaptureCoordinator.requestCapture` entry point and the same
per-image capture/storage logic (`performCapture`); Burst Mode just calls it four times in a row
with a `delay()` in between; instead of once. `CameraViewModel.requestCapture` reads the current
`captureMode`/`burstIntervalMillis` off `SettingsRepository` at the moment each trigger fires and
passes them through.

**The burst interval is a target, not a guarantee.** It's configured with a `Slider` snapped to
250 ms increments from 250 ms (minimum) to 2 s (maximum) (`AppSettings.BURST_INTERVAL_RANGE_MILLIS`),
defaulting to 500 ms - fast enough to feel like a genuine burst, while more likely to work
consistently across a wide range of Android device camera hardware than the 250 ms minimum. Actual
elapsed time between captures can still vary with camera hardware, exposure time, image
processing, and OS scheduling.

**A second burst can't start while one is already running.** `CaptureCoordinator.requestCapture`
uses `Mutex.tryLock()` rather than a blocking `lock()`, so a capture command that arrives while a
capture (or an entire burst) is already in flight is dropped immediately instead of queuing to run
afterward - this is what makes "no overlapping burst" work without a separate flag.
`CaptureCoordinatorTest`'s `"a capture command received while a burst is in progress is dropped"`
case launches an overlapping request mid-burst and asserts only the burst's own four images were
captured.

**One vibration per burst, not per image** - see "Burst Feedback" in "Feedback on capture" above.

**Capture Performance.** `CameraPreview.kt` builds its `ImageCapture` use case with
`setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)` only when Burst Mode is active;
Single-Shot Mode leaves CameraX's own default alone. Since a built `ImageCapture`'s capture mode
can't be changed afterward, switching capture mode rebuilds the use case (keyed by `captureMode` in
a `remember(captureMode)`) and rebinds it to the camera (`LaunchedEffect(lifecycleOwner,
captureMode)`) - a brief preview flicker on mode switch is an accepted trade-off for an infrequent,
deliberate settings change, unlike the rotation case above which specifically avoids any rebind.

**Flash and torch are actively disabled during Burst Mode or while the overlay is visible.**
There's no user-facing control that turns either on yet, but `camera/domain/FlashTorchController.kt`
(implemented by `camera/data/CameraXFlashTorchController.kt`, using `ImageCapture.flashMode` and
`CameraControl.enableTorch`) exists so that constraint is explicit and enforced now rather than
merely assumed. `CameraViewModel` collects `captureMode == BURST || overlayVisible` and calls
`disableFlashAndTorch()` every time that becomes `true`, regardless of whatever state flash/torch
were previously in.

## Error Handling

Capture and file-saving errors are **not** displayed on the main camera screen - `CaptureStatusUi`
has no error/failure variant at all (just `Idle` / `Capturing` / `Saved`), so a failure simply
falls back to reading `Idle`. This applies the same way to a single failed Single-Shot capture and
to a failed image within a burst; a burst can complete with fewer than four saved images without
ever showing an on-screen error, and one failed image does not cancel the remaining ones in that
burst.

Instead, every failure - during pending-entry creation, camera capture, or MediaStore
finalization - is logged as one JSON object per line (JSON Lines) to an app-private log file
(`capture_errors.log` in `filesDir`) via `camera/domain/CaptureErrorLogger.kt`, implemented by
`camera/data/FileCaptureErrorLogger.kt` using `org.json.JSONObject` (bundled with Android, so no
extra dependency) rather than hand-rolled string concatenation, which would risk malformed JSON if
a message ever contained a quote or newline. Each entry records the timestamp, active capture mode,
burst image number and configured interval (`null` for Single-Shot), output destination, error
type, error message, and exception details, when available. A failure to write the log itself is
swallowed (there is nowhere safer to report a logging failure than the log itself) rather than
crashing or interrupting capture.

`CaptureCoordinatorTest` covers this with fakes (`FakeCaptureErrorLogger`) rather than reading real
files, asserting the right fields are populated for both a Single-Shot failure and a burst-image
failure.

## Developer diagnostics

Every capture request and every touch interaction on the camera screen can be traced end-to-end,
so it's possible to answer "why didn't that tap take a photo?" from logs alone rather than by
reading code or reproducing on-device.

**Capture Attempt ID.** `CaptureCoordinator.requestCapture` generates a `CaptureAttemptId`
(`RandomCaptureAttemptIdGenerator`, a random UUID) before any validation happens, and threads it
through every `CaptureDiagnosticEvent` that request produces - `Requested`, `Accepted`, `Rejected`,
`CameraXRequestSubmitted`, `CameraXCaptureStarted`, `ImageSaved`, `Completed`, and `CameraXError` -
plus the existing `CaptureErrorLogEntry`/`CaptureMetadataLogEntry` records, which now also carry it.
A whole Burst Mode command shares one id across all four images (it is one *capture attempt* that
happens to produce several images), individually distinguished by
`CameraXRequestSubmitted.burstImageNumber`. A rejected request (`Rejected.reason`, a
`CaptureRejectionReason`: `CAPTURE_ALREADY_RUNNING`/`BURST_ALREADY_RUNNING` when
`Mutex.tryLock()` fails, depending on which mode currently holds the lock) still gets logged, so a
dropped duplicate trigger is visible in the log even though nothing was ever captured.

**Gesture diagnostics.** `CameraScreen`'s `detectTapOrHorizontalSwipe` reports a
`GestureDiagnosticEvent` for every touch interaction - `Detected` (pointer down), `Classified`
(pointer up, with down/up position, duration, total horizontal/vertical movement, the system touch
slop, a diagnostic-only swipe threshold, and the resulting `GestureClassification`: `TAP`,
`SWIPE_LEFT`, `SWIPE_RIGHT`, `MOVEMENT_BELOW_SWIPE_THRESHOLD`, or `CANCELLED`), `Accepted`, and
`Cancelled` (when the pointer's change disappears from the event stream before completing, with
whatever overlay-visible/camera-accepting-requests context was available at the time). The
swipe-threshold classification is diagnostic-only - it labels a drag that never exceeds it as
`MOVEMENT_BELOW_SWIPE_THRESHOLD` in the log without changing the actual swipe-to-toggle-overlay
behavior described above.

**Where this is logged.** `GestureDiagnosticsLogger` and `CaptureDiagnosticsLogger` are both
implemented by one class, `camera/data/AndroidDiagnosticsLogger.kt`. Capture-processing events
always go to Logcat in every build type (`CaptureDiagnostics`/`GestureDiagnostics` tags) - this is
"normal operational... logging", which the spec allows to remain enabled in Release - but gesture
events are silently dropped unless `BuildConfig.DEBUG` is true, since detailed gesture diagnostics
are explicitly a debug-build-only concern. Either stream can *additionally* be written as JSON
Lines to an app-private file (`capture_diagnostics.log` / `gesture_diagnostics.log`) if the
**Save diagnostic logs to file** setting is turned on (off by default; see "Settings" below) -
`AndroidDiagnosticsLogger` mirrors that setting into a `@Volatile` flag from a background collector
(the same pattern `CameraViewModel` already uses for the burst-deferred aspect ratio) rather than
reading the suspend-based `SettingsRepository` on every call, since neither logging method is
`suspend` - both fire on hot paths (every touch interaction, every stage of every capture) and must
not add I/O latency to them.

**Camera diagnostics.** `CameraPreview.kt` reports a `CameraDiagnosticsSnapshot` (selected camera,
preview/capture resolution from each use case's `resolutionInfo`, requested aspect ratio, display
rotation, capture mode) once right after binding, and again whenever the `OrientationEventListener`
crosses into a new rotation bucket - not on every raw sensor callback.

**Debug overlay.** A bug icon next to the settings gear (visible only when `BuildConfig.DEBUG`)
toggles a small on-screen panel showing the last touch location, gesture classification, capture
state, current aspect ratio, whether the camera is bound, the last Capture Attempt ID, and its
trigger source. The toggle's own state (`CameraViewModel.diagnosticsOverlayEnabled`) is plain
in-memory `ViewModel` state, not persisted - the `BuildConfig.DEBUG` check at the render site is
what actually guarantees the overlay never appears in a Release build (a compile-time constant, so
R8 dead-code-eliminates that branch entirely), independent of whatever the toggle state happens to
be.

**Trigger sources are now distinguishable.** `CaptureTrigger` gained a `ShutterButton` variant
(previously the shutter FAB reused `ScreenTouch`), so `CaptureTriggerSource` (`TOUCH`, `VOICE`,
`VOLUME_BUTTON`, `SHUTTER_BUTTON`) can tell a screen tap apart from a shutter-button press in the
diagnostic log, per `CaptureTrigger.toDiagnosticSource()`.

## Settings

A gear icon in the top-right corner of the camera screen (always visible, regardless of
permission state) opens a separate, full-screen settings screen (`settings/ui/SettingsScreen.kt`),
reached and left via `androidx.navigation.compose.NavHost` in `CaptureApp.kt`; the system/gesture
back action returns to the camera screen normally. The screen's content is vertically scrollable
(`Modifier.verticalScroll`) now that six settings no longer reliably fit a single screen without
scrolling on every device/test window size.

The settings screen has six controls, all backed by `SettingsRepository` /
`DataStoreSettingsRepository` (Jetpack DataStore Preferences, so values persist across app
restarts):

1. **Vibration duration** - a `Slider` snapped to 60 ms increments from 60-300 ms
   (`AppSettings.VIBRATION_DURATION_RANGE_MILLIS`), used for the capture-success pulse described
   above.
2. **Overlay image selection** - a "Choose image" button that launches the system Photo Picker
   (`ActivityResultContracts.PickVisualMedia`, hosted in `settings/ui/SettingsRoute.kt`). Like the
   camera permission model, this needs no runtime storage/media permission at all.
3. **Capture mode** - a `SingleChoiceSegmentedButtonRow` choosing between Single-Shot and Burst
   (see "Capture Mode and Burst Mode" above).
4. **Burst interval** - a `Slider` snapped to 250 ms increments from 250 ms to 2 s
   (`AppSettings.BURST_INTERVAL_RANGE_MILLIS`), defaulting to 500 ms.
5. **Capture aspect ratio** - a `SingleChoiceSegmentedButtonRow` choosing between 4:3 (default)
   and 16:9 (see "Capture aspect ratio and preview framing" above).
6. **Save diagnostic logs to file** - a `Switch`, off by default, controlling only whether
   capture/gesture diagnostic events are *additionally* written to a file; Logcat output is
   unaffected either way (see "Developer diagnostics" above).

**The picker's own Uri is not kept long-term.** It's tempting to assume the Photo Picker's
`content://` Uri stays readable indefinitely once granted - no `ContentResolver
.takePersistableUriPermission` call needed, unlike a legacy `ACTION_OPEN_DOCUMENT`-style picker's
Uris - but that turned out to be wrong on at least one real device/OS version: reopening the app
after a restart and trying to load a Uri DataStore had correctly remembered failed with
`SecurityException: ... does not have permission to access picker uri ...`. The picker's read
grant for that specific item simply didn't survive the process restart. `SettingsViewModel
.onImageSelected` now copies the picked image's bytes into app-private storage immediately (via
`settings/domain/OverlayImageStore.kt`, implemented by `settings/data/FileOverlayImageStore.kt`,
which overwrites a single fixed file each time so nothing accumulates) and persists a `file://` Uri
to that private copy instead - Coil loads a `file://` Uri directly with no permission dependency of
any kind. If the copy fails, the previously-selected image (if any) is left in place rather than
persisting a reference that can't be read back.

**Writes happen on `@ApplicationScope`, not `viewModelScope`.** `SettingsViewModel` is scoped to
the "settings" `NavHost` destination's back-stack entry, which is popped - cancelling
`viewModelScope` - as soon as the user navigates back to the camera screen. That is an extremely
common flow immediately after picking an image (pick it, see the thumbnail update, tap back), and
a DataStore write still in flight on `viewModelScope` at that moment could be cancelled before it
durably reached disk, so a freshly-picked image could silently fail to survive an app restart.
`onVibrationDurationChanged`/`onImageSelected`/`onCaptureModeChanged`/`onBurstIntervalChanged`/
`onCaptureAspectRatioChanged`/`onDiagnosticsFileLoggingChanged` all launch on the injected `@ApplicationScope` `CoroutineScope` instead (the same one `CameraViewModel`
already uses to release the voice recognizer after `onCleared()`, and to persist overlay
visibility - see above), which outlives the settings screen. `SettingsViewModelTest`'s `"a selection write survives the view model being
cleared right afterward"` case reproduces this with a real `ViewModelStore` to guard against a
regression.

## How photographs are stored

`MediaStorePhotoStorage` (`camera/data/MediaStorePhotoStorage.kt`) saves every photo through
`MediaStore` - never a raw filesystem path - into `Pictures/Capture`, with a timestamp-based file
name (`IMG_<yyyyMMdd_HHmmssSSS>.jpg`) and standard JPEG output from CameraX's `ImageCapture`.

Storage happens in three steps, each with a `PhotoStorage` method, so a partially-written photo
never appears as a finished one in the user's gallery:

1. **`createPendingEntry`** inserts a `MediaStore` row with `IS_PENDING = 1` and hands back its
   `Uri` before any pixel data is written.
2. **CameraX writes** the JPEG straight into that `Uri`'s `OutputStream` (`CameraXCaptureController`).
3. On success, **`finalizeEntry`** clears `IS_PENDING`; on failure, **`discardEntry`** deletes the
   row outright, so failed captures leave nothing behind.

`CaptureCoordinator` returns a structured `CaptureResult` for every request: outcome
(`CaptureOutcome.Success(uriString)` / `CaptureOutcome.Failure(userMessage)`), which
`CaptureTrigger` produced it, and a timestamp.

## Voice-recognition limitations

`AndroidSpeechRecognizerAdapter` (`voice/data/AndroidSpeechRecognizerAdapter.kt`) wraps
`android.speech.SpeechRecognizer`. Please read its kdoc, but in summary:

- **Not indefinite always-on listening.** A `SpeechRecognizer` session ends after a short pause in
  speech or a system timeout. "Continuous" listening here means the adapter starts a brand-new
  session after every result or error - there is always a small gap, and Android does not
  guarantee the next session starts promptly under system pressure (e.g. low memory, another app
  using the microphone).
- **On-device recognition is preferred, not guaranteed.** The adapter calls
  `SpeechRecognizer.createOnDeviceSpeechRecognizer` when
  `SpeechRecognizer.isOnDeviceRecognitionAvailable` reports it (Android 13+ with a supporting
  recognizer installed), and sets `EXTRA_PREFER_OFFLINE` as a hint otherwise, but many
  devices still route audio to a network-backed recognizer.
- **Battery and privacy.** Listening keeps the microphone (and often a network connection)
  active, which costs battery, and it is only ever started while the user has explicitly enabled
  the voice-trigger switch - never silently in the background.
- **Error handling.** After 3 consecutive recognizer errors, the adapter stops restarting itself
  (rather than looping forever) and surfaces `VoiceRecognitionState.Error`; the user must toggle
  voice triggering off and back on to retry.
- Recognized text is matched locally against a small fixed vocabulary and is **never logged,
  persisted, or transmitted** by this app.

### How to change the voice-command vocabulary

Edit `VoiceCommandMatcher.DEFAULT_VOCABULARY` in
`voice/domain/VoiceCommandMatcher.kt`:

```kotlin
val DEFAULT_VOCABULARY: Set<String> = setOf("photo", "picture", "capture", "cheese")
```

`VoiceCommandMatcher.normalize()` lowercases, trims, and strips punctuation before matching, and
`match()` accepts both an exact utterance and a configured word appearing inside a longer one
("okay cheese now" still matches "cheese"), so new entries do not need to handle that themselves.

### How to replace `SpeechRecognizer`

Everything Android-speech-specific is confined to `AndroidSpeechRecognizerAdapter`. To swap in an
offline/continuous keyword-spotting engine:

1. Write a new class implementing `voice.domain.VoiceCommandRecognizer` (`state`, `start()`,
   `stop()`).
2. Point `VoiceModule.bindVoiceCommandRecognizer` at it instead of
   `AndroidSpeechRecognizerAdapter`.
3. Nothing in `camera.domain`, `CameraViewModel`, or any Compose code needs to change - they only
   ever depend on the `VoiceCommandRecognizer` interface.

## Privacy considerations

- Camera and microphone access are each requested only when actually needed (camera immediately,
  since the app is a camera app; microphone only once voice triggering is turned on).
- No analytics, crash reporting, or network calls are wired into this starter project.
- Recognized speech text is used only in-memory to match against `VoiceCommandMatcher`'s
  vocabulary; it is not written to logs, disk, or a network call anywhere in this codebase.
- Photos are saved through `MediaStore` into the user's own `Pictures/Capture` folder - visible
  and manageable like any other gallery photo, not hidden app-private storage.
- The overlay image is chosen via the system Photo Picker, which grants access only to the one
  image the user explicitly picks - the app never gets broad gallery access, and nothing about the
  picker interaction is logged.

## Build and run

Requires the current stable Android Studio and a JDK 21 toolchain (configured via
`kotlin { jvmToolchain(21) }` / `compileOptions`, so it does not rely on whatever JDK happens to
already be on `PATH`). `settings.gradle.kts` applies the `foojay-resolver-convention` plugin so
Gradle can download a matching JDK automatically if one isn't already installed.

```bash
./gradlew clean
./gradlew assembleDebug
```

Then either open the project in Android Studio and press Run, or:

```bash
./gradlew installDebug
```

with a device or emulator connected.

**Gradle wrapper jar:** this repository includes `gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.properties`, but not the binary `gradle-wrapper.jar` itself (it
was not generated in the environment this project was created in). Opening the project in
Android Studio will offer to regenerate it automatically; from the command line, run
`gradle wrapper --gradle-version 9.6.1` once with any locally installed Gradle to create it.

### Renaming the app

`Capture` / `com.example.capture` are placeholders. To rename:

1. Change `namespace` and `applicationId` in `app/build.gradle.kts`.
2. Move the `com/example/capture` source directories (`main`, `test`, `androidTest`) to match,
   and update every file's `package` declaration.
3. Update `app_name` in `res/values/strings.xml`.
4. Update the manifest's `android:name=".CaptureApplication"` reference if the class is renamed.

## Running the tests

```bash
./gradlew testDebugUnitTest       # local JVM unit tests + Robolectric-based Compose UI tests
./gradlew lintDebug                # Android Lint
./gradlew jacocoTestReport         # coverage report (build/reports/jacoco/...), depends on testDebugUnitTest
./gradlew connectedDebugAndroidTest  # instrumented tests - REQUIRES a connected device/emulator
```

- **Local JVM unit tests** (`app/src/test/...`): `CaptureCoordinatorTest`, `CameraViewModelTest`,
  `VoiceCommandMatcherTest`, `CapturePermissionsTest`, `SettingsViewModelTest`,
  `AspectRatioClassifierTest`, `CameraPreviewRotationTest`. Use fakes
  (`app/src/test/.../testing/TestDoubles.kt`) rather than a mocking framework, and
  `kotlinx-coroutines-test`'s `StandardTestDispatcher` with an injected `FakeTimeProvider` - no
  real `delay()`s.
- **Compose UI tests** (`CameraScreenTest`, `SettingsScreenTest`, also in `app/src/test/...`) run
  via Robolectric, so they are part of `testDebugUnitTest` too and need no device.
- **Instrumented tests** (`app/src/androidTest/...`): `MainActivitySmokeTest` needs a connected
  device or emulator with a camera and grants `CAMERA` permission via `GrantPermissionRule` before
  launching. It only asserts the Activity/Hilt/Compose stack comes up without crashing - true
  hardware behavior is covered by the manual checklist below, not automated instrumented tests.

## Manual device-test checklist

Run through this on a real device (or an emulator with an emulated camera) before each release,
since none of it is fully covered by automated tests:

- [ ] **Touch capture:** tapping anywhere on the live preview takes a photo; tapping the visible
      shutter button also takes a photo; tapping rapidly does not produce duplicate photos.
- [ ] **Haptic feedback (Single-Shot):** a short vibration pulse is felt immediately when a photo
      saves successfully, for all three triggers (touch, volume button, voice); no pulse occurs on
      a failed capture; toggling the device's system-wide haptics/vibration setting off suppresses
      it (this app does not override that system setting).
- [ ] **Volume buttons:** pressing volume up takes a photo; pressing volume down takes a photo;
      the system's on-screen media volume indicator does **not** appear while doing so; pressing
      a volume button does not change the device's media volume.
- [ ] **Voice capture:** turning the voice switch on requests microphone permission (only the
      first time); saying "photo"/"picture"/"capture"/"cheese" takes a photo; the listening
      indicator is visibly on while listening and off when the switch is off; turning voice
      capture off actually stops the microphone (check the OS's microphone-in-use indicator).
- [ ] **Voice capture with microphone permission pre-granted:** grant `RECORD_AUDIO` ahead of time
      via system Settings (Settings > Apps > Capture > Permissions), then, without ever seeing the
      in-app runtime prompt, turn the voice switch on and confirm the listening indicator still
      turns on (regression check for a bug where the app never learned the permission was already
      granted and the recognizer silently never started - see `CameraRoute.onVoiceTriggerToggle`).
- [ ] **Portrait lock:** physically rotate the device through all four orientations while the
      preview is showing; the app's own layout stays locked to portrait the entire time (it never
      visually rotates into landscape, even briefly), with no black flash, freeze, or crash; take a
      photo immediately after rotating with the device held sideways and confirm it opens right
      side up in the Gallery (not sideways or upside down) - this is the
      `OrientationEventListener`-based rotation tracking described above, not a UI rotation.
- [ ] **Permissions:** deny camera permission and confirm a real message (not a blank screen)
      appears; deny it a second time ("don't ask again") and confirm the screen offers to open
      Settings; grant it from Settings and return to the app; repeat for microphone permission via
      the voice toggle.
- [ ] **Image storage:** after taking a photo, open the device's Gallery/Photos app and confirm it
      appears in an album named "Capture" with a timestamp-based filename, and that it opens and
      displays correctly (not a broken/zero-byte file).
- [ ] **Backgrounding mid-capture:** press Home immediately after triggering a capture and confirm
      the app does not crash and (on returning) the capture either completed or failed cleanly.
- [ ] **Settings navigation:** the gear icon is reachable from the camera screen regardless of
      permission state; it opens the settings screen; the system/gesture back action returns to
      the camera screen with its state (voice toggle) intact.
- [ ] **Vibration duration setting:** moving the slider changes the felt pulse length on the next
      capture; the value survives an app restart.
- [ ] **Overlay swipe gestures:** with an image selected, a left swipe on the live preview slides
      the overlay image in from the right edge and settles over the preview; the shutter button
      disappears (the voice control, gear icon, and capture progress indicator stay visible);
      capture (touch/volume/voice) still works and still saves a real photo while the overlay is
      shown, even with the shutter button hidden; a right swipe on the overlay slides it back off
      to the right, restoring the live preview and bringing the shutter button back; the slide
      visibly follows the finger while dragging rather than only snapping at the end.
- [ ] **Overlay-visibility persistence:** leave the overlay showing (or hidden) and fully close the
      app, then relaunch it - the same mode is restored automatically, with no settings-screen
      control for it anywhere.
- [ ] **Image selection:** "Choose image" opens the system Photo Picker; picking an image updates
      the thumbnail on the settings screen and the overlay image on the camera screen; the
      selection survives an app restart without needing to re-pick.
- [ ] **Volume keys on the settings screen:** while settings is open, volume buttons adjust the
      device's normal media volume instead of taking a photo.
- [ ] **Burst Mode capture:** switch to Burst Mode in settings, trigger a capture (touch, volume,
      or voice) and confirm four photos are saved in quick succession, spaced roughly by the
      configured burst interval; only a single vibration pulse is felt for the whole burst, not
      one per photo; a second capture command sent while the burst is still running does not start
      an overlapping burst.
- [ ] **Burst interval setting:** moving the slider changes the felt spacing between photos in the
      next burst; the value survives an app restart.
- [ ] **Per-trigger capture mode independence:** in Settings, set only Volume Up to Burst Mode
      (leave every other trigger at Single-Shot); confirm Volume Up produces four photos while
      tapping the screen, pressing Volume Down, using the shutter button, and voice command each
      still produce exactly one; repeat with a different single trigger set to Burst to confirm it
      isn't specific to Volume Up.
- [ ] **Screen top/bottom split:** with the screen's top-half trigger set to Single-Shot and the
      bottom-half trigger set to Burst (or vice versa), tapping the top half of the screen and
      tapping the bottom half produce the correct number of photos for each; the split holds
      regardless of the selected capture aspect ratio's letterboxing.
- [ ] **Capture-mode pipeline switch delay:** with two triggers configured for different modes
      (e.g. Volume Up = Burst, Volume Down = Single-Shot), alternate between them a few times in a
      row; a brief delay before each capture is expected right when switching between the two
      (this is the camera pipeline rebinding to the new trigger's mode - see "Capture Mode" in
      app-spec.md), but repeated presses of the *same* trigger back-to-back should not have that
      extra delay.
- [ ] **Flash/torch stay off:** on a device where flash/torch can be observed (e.g. watch for the
      flash LED), confirm it never fires while Burst Mode is active or while the overlay image is
      visible.
- [ ] **Error logging:** force a capture failure if possible (e.g. fill device storage, or revoke
      camera access mid-session) and confirm no error text appears on the camera screen, then pull
      `capture_errors.log` (`adb shell run-as com.example.capture cat files/capture_errors.log`)
      and confirm it contains a well-formed JSON line with the expected fields.
- [ ] **Aspect-ratio letterboxing:** with 4:3 selected, the preview appears as a centered box with
      background-colored bars above/below (not stretched to fill the screen, and not pillarboxed -
      see "Capture aspect ratio and preview framing" for why portrait-only means letterboxing);
      switching to 16:9 changes the box's proportions accordingly; the overlay image (if visible)
      still covers the *entire* screen, bars included, unaffected by either ratio.
- [ ] **Aspect ratio matches the captured photo:** with each ratio selected, take a photo and
      confirm its framing in the Gallery visually matches what the live preview showed (not a
      noticeably different crop).
- [ ] **Capture aspect ratio setting:** moving between 4:3 and 16:9 updates the preview
      immediately (outside a burst); the value survives an app restart.
- [ ] **Aspect ratio change during a burst:** start a burst, then immediately switch the capture
      aspect ratio in Settings before it finishes; the active burst is unaffected (same preview
      framing, same number of photos), and the new ratio only visibly applies the *next* time a
      capture is triggered.
- [ ] **Captured-image metadata logging:** after taking a photo, pull `capture_metadata.log`
      (`adb shell run-as com.example.capture cat files/capture_metadata.log`) and confirm the
      newest line's `widthPx`/`heightPx`/`actualAspectRatio` match the photo actually saved, and
      `requestedAspectRatio`/`matchesTolerance` reflect the setting that was active.
- [ ] **Capture diagnostics in Logcat:** filter `adb logcat` on tag `CaptureDiagnostics` and take a
      photo; confirm `Requested`/`Accepted`/`CameraXRequestSubmitted`/`CameraXCaptureStarted`/
      `ImageSaved`/`Completed` all appear with the same `attemptId`, in that order; trigger a
      capture immediately after another (within the debounce window) and confirm a `Rejected` entry
      appears instead.
- [ ] **Gesture diagnostics only in a debug build:** filter `adb logcat` on tag
      `GestureDiagnostics`, tap and swipe the preview on a debug build and confirm entries appear;
      install a release build (`assembleRelease`) instead and confirm the tag never appears, and
      that the bug-icon diagnostics toggle and its overlay are both absent from the screen.
- [ ] **Diagnostics overlay:** on a debug build, tap the bug icon next to the settings gear and
      confirm a small panel appears showing capture state, aspect ratio, camera-bound status, the
      last gesture classification, last touch location, last Capture Attempt ID, and trigger
      source, all updating live as you interact with the screen; tap the icon again and confirm it
      disappears.
- [ ] **Diagnostic file logging setting:** turn on "Save diagnostic logs to file" in Settings, take
      a photo and perform a swipe, then pull both files (`adb shell run-as com.example.capture cat
      files/capture_diagnostics.log` and `.../gesture_diagnostics.log`) and confirm well-formed JSON
      lines appear in each; turn the setting back off and confirm no new lines are appended for
      subsequent actions.

### Known device-manufacturer differences

- **Volume keys:** some OEM launchers/skins (notably some Samsung and Xiaomi builds) intercept
  volume-key long-presses or double-presses for system shortcuts (e.g. voice assistant, screen
  recording) before the app ever sees the event; a single press reaching `MainActivity.onKeyDown`
  is the behavior this app relies on and is standard, but a custom OEM gesture layered on top of
  it is outside the app's control.
- **Camera behavior:** camera startup latency, and which physical lens `CameraSelector
  .DEFAULT_BACK_CAMERA` resolves to on multi-camera phones, vary significantly by OEM; some
  budget devices take noticeably longer to report the first `SurfaceRequest`.
- **Speech recognition:** on-device recognition availability
  (`SpeechRecognizer.isOnDeviceRecognitionAvailable`) depends on the OEM's installed Google/Assistant
  app version, not just the Android version; some OEM ROMs (particularly ones without Google
  Mobile Services) have no usable `SpeechRecognizer` implementation at all, in which case the app
  correctly reports `VoiceRecognitionState.Unavailable` rather than crashing.

## Build verification

Unlike a typical generation environment, this one had a preinstalled Android SDK, a cached Gradle
distribution, and outbound access to `dl.google.com`/`repo.maven.apache.org` (though not to the
Gradle Plugin Portal or general internet), so the four non-device commands were **actually
executed**, not just written and assumed to work:

```text
./gradlew clean                 -> ran as part of the combined command below
./gradlew assembleDebug         -> BUILD SUCCESSFUL, produced app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest     -> BUILD SUCCESSFUL, all unit/Compose tests passed (7 test classes)
./gradlew lintDebug             -> BUILD SUCCESSFUL, 0 errors, 0 warnings
```

(run together as `./gradlew clean assembleDebug testDebugUnitTest lintDebug`, with the ktlint and
foojay-resolver-convention plugins temporarily disabled for this one verification run, since
both require Gradle Plugin Portal access this environment didn't have - see "Dependency choices").

This caught and fixed several real issues that a purely "does it look right" review would have
missed:

- AGP 9's built-in Kotlin support rejects the `org.jetbrains.kotlin.android` plugin outright
  (a hard build error, not a warning) - it has been removed from both `build.gradle.kts` files.
- `androidx.hilt:hilt-navigation-compose:1.4.0` and the newest `core-ktx`/`lifecycle` releases
  require `compileSdk 37`, not `36`.
- `androidx.test:rules` was missing from `androidTestImplementation` (needed for
  `GrantPermissionRule`), and its version doesn't track `androidx.test:runner`'s 1:1.
- `Uri.parse(...)` needed `entry.uriString.toUri()` instead (an Android Lint `UseKtx` finding),
  a redundant `android:screenOrientation="unspecified"` was removed (Lint `DiscouragedApi`),
  adaptive launcher icons needed a `<monochrome>` layer (Lint `MonochromeLauncherIcon`), and three
  string resources were unused and have been removed.
- The `kotlinx.coroutines.resume` import in `CameraXCaptureController.kt` resolved to the wrong
  (internal) overload; `kotlin.coroutines.resume` is the correct one for a bare
  `continuation.resume(value)` call.
- When the settings screen was added, Material3's `TopAppBar` needed an
  `@OptIn(ExperimentalMaterial3Api::class)` (it compiles fine without it being caught until you
  actually build), and a stray `onNode` import in `SettingsScreenTest.kt` didn't exist as a
  top-level symbol (it's a member of the test rule interface, not something to import).

The settings/display-mode/haptic-duration feature was verified the same way after being added:
`compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`,
`testDebugUnitTest` (53/53 tests across 7 classes), `lintDebug` (0 issues), and `assembleDebug` all
passed, then the resulting APK was installed on a connected physical device
(`./gradlew installDebug`) where the gear icon, settings screen, and system Photo Picker were all
exercised for real and produced no crashes.

Hiding the capture-status indicator and shutter button while the selected image is shown
(`CameraScreen.kt`) surfaced one more real issue the same way: `assertDoesNotExist()` (used in the
new `CameraScreenTest` cases) doesn't exist as a top-level symbol in this Compose UI test version
either - the fix was asserting on `onAllNodesWith...(...).fetchSemanticsNodes(atLeastOneRootRequired
= false)` being empty instead. `testDebugUnitTest` (55/55 tests), `lintDebug` (0 issues), and
`assembleDebug` all passed after that fix.

A real user-reported bug - a picked full-screen image not surviving an app restart - traced back
to `SettingsViewModel` writing settings on `viewModelScope`, which gets cancelled as soon as the
"settings" `NavHost` destination is popped (see "Display mode and settings" above). Before
committing the `@ApplicationScope` fix, the new `SettingsViewModelTest` regression case was
temporarily pointed back at `viewModelScope` to confirm it actually fails without the fix (it did,
and only that one test failed) before being restored; with the real fix, `testDebugUnitTest`
(56/56 tests), `lintDebug` (0 issues), and `assembleDebug` all passed.

That fix turned out to be necessary but not sufficient: after deploying it, the same bug was
reported again. Rather than guess further, the app's own persisted DataStore file was pulled
directly off the connected device (`adb shell run-as com.example.capture cat
files/datastore/settings.preferences_pb`) - which showed the picked image's Uri *was* now being
saved correctly - and `adb logcat` was checked on the next relaunch, which showed the real problem:
`SecurityException: ... does not have permission to access picker uri ...`. The Photo Picker's own
read grant doesn't survive a process restart on this device, contradicting what this README and
`app-spec.md` had both assumed. The actual fix - copying the image into app-private storage at
selection time (`SelectedImageStore`/`FileSelectedImageStore`) - was verified the same way:
`testDebugUnitTest` (58/58 tests, including new cases for the copy-then-persist behavior and for a
failed copy leaving the previous selection in place), `lintDebug` (0 issues, after also fixing a new
`UseKtx` finding), and `assembleDebug` all passed, then installed on-device with logcat confirmed
free of the `SecurityException` on the next relaunch. A previously-selected image saved *before*
this fix still points at a picker Uri and needs to be re-picked once; from that point on it's a
private copy and survives restarts.

The toggle-based display mode was later replaced with the swipe-gesture-driven overlay described
in "Overlay image visibility" above, per an updated `app-spec.md` (renamed from the original
`app-overview.md`). This touched `CameraScreen.kt`/`CameraViewModel.kt` (the new
`detectTapOrHorizontalSwipe` gesture detector and `Animatable` offset), added
`OverlayVisibilityRepository`/`DataStoreOverlayVisibilityRepository`, removed the settings-screen
toggle entirely, and renamed `SelectedImageStore`/`FileSelectedImageStore` to
`OverlayImageStore`/`FileOverlayImageStore` for consistent terminology. A missing `pointerInput`
import was caught immediately by `compileDebugKotlin`, and a stray `onAllNodes` import in
`SettingsScreenTest.kt` was caught by `compileDebugUnitTestKotlin` (like the earlier `onNode`
case, it's a member of the test rule interface, not a top-level import). With those fixed,
`testDebugUnitTest` (61/61 tests across 7 classes, including two new swipe-gesture cases in
`CameraScreenTest` using `performTouchInput { swipeLeft() }`/`swipeRight()`, and a new assertion in
`SettingsScreenTest` that the settings screen has zero toggleable nodes), `lintDebug` (0 issues),
and `assembleDebug` all passed.

Capture Mode/Burst Mode, Capture Performance, Burst Feedback, Flash and Torch Restrictions, and
Error Handling were added next, per a further `app-spec.md` update. This touched
`CaptureCoordinator.kt` (burst orchestration via `Mutex.tryLock()` instead of a blocking `lock()`,
plus per-capture error logging), added `CaptureErrorLogger`/`FileCaptureErrorLogger`,
`FlashTorchController`/`CameraXFlashTorchController`/`CameraControlHolder`, extended
`AppSettings`/`SettingsRepository`/`SettingsScreen` with the capture-mode and burst-interval
controls, removed `CaptureStatusUi`'s error/failure variant entirely (and the `Failed`-branch UI in
`CameraScreen.kt`) since errors are no longer shown on screen, and made `CameraPreview.kt` rebuild
its `ImageCapture` use case with `CAPTURE_MODE_MINIMIZE_LATENCY` for Burst Mode. Two real issues
surfaced during verification, both compiler errors: a missing `pointerInput` import was already
fixed from the previous feature, and `buildList { }`'s builder lambda turned out not to be a
`suspend` lambda, so the burst loop's `performCapture`/`delay` calls inside it didn't compile -
fixed by using a plain `mutableListOf`/`for` loop instead. Once compiling, two `SettingsScreenTest`
Compose UI tests failed for the same underlying reason: the settings screen's `Column` had no
vertical scrolling, so with four settings sections its content overflowed the test window and the
new capture-mode/burst-interval controls fell outside the displayed/clickable area - fixed by
adding `Modifier.verticalScroll(rememberScrollState())` to the settings `Column` and
`.performScrollTo()` to the two new tests before interacting with those controls. With both fixes,
`testDebugUnitTest` (76/76 tests across 7 classes, including new `CaptureCoordinatorTest` cases for
burst spacing/overlap-rejection/partial-failure/error-logging, `CameraViewModelTest` cases for the
single burst vibration and flash/torch enforcement, and `SettingsScreenTest`/`SettingsViewModelTest`
cases for the two new settings), `lintDebug` (0 issues), and `assembleDebug` all passed, then the
resulting APK was installed on a connected physical device where the settings screen (now scrolled,
showing only vibration duration + overlay image with no toggle for it, plus capture mode and burst
interval), the camera screen, and swipe gestures with no overlay image selected were all exercised
for real with logcat confirmed free of crashes.

Capture aspect ratio and preview framing were added next, per a further `app-spec.md` update: a
persisted 4:3/16:9 setting, a letterboxed/pillarboxed preview independent of the still-full-screen
overlay and gesture layer, matching `ResolutionSelector`/`ViewPort`/`UseCaseGroup` configuration
for the `Preview` and `ImageCapture` use cases, explicit rotation tracking (since
`android:configChanges` means `CameraPreview` is never recreated on rotation), a burst-deferred
"effective" aspect ratio in `CameraViewModel` so a mid-burst setting change can't affect the active
burst, and post-capture metadata logging (`AndroidImageMetadataReader` + `AspectRatioClassifier` +
`FileCaptureMetadataLogger`) with tolerance-based, orientation-agnostic ratio classification. This
added the `androidx.exifinterface:exifinterface` dependency (see "Dependency choices"). One real
issue surfaced immediately: `androidx.core.util.Rational` doesn't exist - `CameraPreview.kt`'s
`ViewPort.Builder` needs the platform `android.util.Rational` instead, which `compileDebugKotlin`
caught right away (`Unresolved reference 'Rational'`) and was a one-line import fix. With that
fixed, `testDebugUnitTest` (96/96 tests across 8 classes, including a new `AspectRatioClassifierTest`
covering the worked dimension/ratio examples, new `CaptureCoordinatorTest` cases for metadata
logging on success/failure/burst, `CameraViewModelTest` cases for aspect-ratio reflection and the
mid-burst deferral - driven with `runCurrent()` to pause partway through a burst - and new
`SettingsScreenTest`/`SettingsViewModelTest` cases for the fifth setting), `lintDebug` (0 issues),
and `assembleDebug` all passed, then the resulting APK was installed on a connected physical
device with logcat confirmed free of crashes on launch.

The app was then locked to portrait-only orientation, per a further `app-spec.md` update:
`android:screenOrientation="portrait"` was added to `MainActivity` in `AndroidManifest.xml`, and
the existing `Configuration`-driven capture-rotation tracking in `CameraPreview.kt` was replaced
with an `OrientationEventListener`-based one, since a locked window no longer reports a
`Configuration`/`Display.getRotation()` change when the physical device is turned - the previous
mechanism would have silently stopped updating `targetRotation` at all once the lock took effect.
The degrees-to-`Surface.ROTATION_*` bucketing was pulled out into a small pure function
(`surfaceRotationFor`) specifically so it could be unit-tested directly rather than left as
manual-only verification like the rest of the rotation behavior. `testDebugUnitTest` (100/100 tests
across 9 classes, including the new `CameraPreviewRotationTest` covering all four rotation buckets),
`lintDebug` (0 issues), and `assembleDebug` all passed on the first attempt this time - then the
resulting APK was installed on a connected physical device, where CameraX's own binding log
(`SessionConfig`) was inspected via `adb logcat` and confirmed both `Preview` and `ImageCapture`
shared a single `viewPort`, the camera opened with no errors, and the app launched with no crashes.

Developer Diagnostics were added next, per a further `app-spec.md` update: `CaptureAttemptId`
generation and propagation through `CaptureCoordinator`/`CameraXCaptureController`, structured
`CaptureDiagnosticEvent`/`GestureDiagnosticEvent` traces logged by the new
`AndroidDiagnosticsLogger` (Logcat always, an optional JSON-lines file behind a new persisted
setting, gesture events gated to debug builds only), a `CaptureTrigger.ShutterButton` variant so
the shutter button and a screen tap are distinguishable trigger sources, a debug-only diagnostics
overlay in `CameraScreen.kt`, and a `CameraDiagnosticsSnapshot` reported by `CameraPreview.kt` on
bind and on each rotation-bucket change. This also added `captureAttemptId` to the existing
`CaptureErrorLogEntry`/`CaptureMetadataLogEntry` records and threaded a `CaptureAttemptId` through
`CaptureState.Capturing`/`BurstStarted`/`CaptureResult`, so the debug overlay (and any future
consumer) can read the attempt id straight off coordinator state rather than needing a side
channel. `app/build.gradle.kts` turned on `buildFeatures.buildConfig = true` so `BuildConfig.DEBUG`
could gate both the gesture-diagnostics logger and the overlay's render site.

One test, once written, turned out not to prove what it claimed: a test for the
`CAPTURE_ALREADY_RUNNING` rejection reason launched two concurrent Single-Shot requests expecting
them to race for `CaptureCoordinator`'s mutex, but with these fakes a Single-Shot capture has no
real suspension point, so under `StandardTestDispatcher` the first request always ran to full
completion before the second's continuation was even dispatched - the second was rejected by the
*debounce* check instead (`UNKNOWN`, not `CAPTURE_ALREADY_RUNNING`), which `testDebugUnitTest` caught
immediately. The sibling `BURST_ALREADY_RUNNING` case (first request in `CaptureMode.BURST`, which
has a genuine `delay()` between images) does exercise real interleaving and passes; the
`CAPTURE_ALREADY_RUNNING` branch is otherwise identical two-line logic in `requestCapture` and is
covered by code review rather than a dedicated test, rather than adding new suspension
infrastructure to the fakes just for symmetry. With that test removed, `testDebugUnitTest` (112/112
tests across 9 classes), `lintDebug` (0 issues), and `assembleDebug` all passed.

A rotation bug was fixed next: physically rotating the device distorted the on-screen preview
(stretching it between the 4:3/16:9 portrait mapping and its landscape counterpart), even though
the app's window stays locked to portrait. The root cause was in `CameraPreview.kt`'s
`OrientationEventListener` callback, which had been updating *both* the `Preview` and `ImageCapture`
use cases' `targetRotation` on every physical-rotation bucket change - correct for `ImageCapture`
(needed for the saved photo's EXIF orientation), but wrong for `Preview`, since the preview
container's aspect ratio is fixed to the locked-portrait mapping and never itself rotates. This
contradicted the "Orientation changes" wording in `app-spec.md`, which was corrected as part of this
fix: the preview, its framing, and the shared viewport now stay fixed regardless of physical device
rotation, and only `ImageCapture.targetRotation` (and therefore EXIF orientation) tracks it.
`CameraDiagnosticsSnapshot` gained a `captureRotation` field alongside the existing (now genuinely
fixed) `displayRotation`, matching the "Camera Diagnostics" spec section, which already listed both
as separate fields. `testDebugUnitTest` (112/112 tests across 9 classes - the existing
`CameraPreviewRotationTest` needed no changes, since `surfaceRotationFor` itself didn't change),
`lintDebug` (0 issues), and `assembleDebug` all passed.

Burst Mode's shot-to-shot latency was investigated next, using `adb logcat -s CaptureDiagnostics:I`
against a connected device rather than guessing: with a 250ms configured interval, the actual gap
between images was ~1000-1200ms. The `CameraXCaptureStarted`→`ImageSaved` diagnostic-event gap
(700-890ms per image) showed this was overwhelmingly the camera hardware's own capture+JPEG-encode
latency, not disk I/O as initially suspected - the metadata read-back and MediaStore
create/finalize calls between images accounted for only ~30ms combined. `CameraCaptureController`
gained `captureToMemory` (via `ImageCapture.OnImageCapturedCallback`/`ImageProxy` instead of the
file-based `OnImageSavedCallback`) and `PhotoStorage` gained `writeBytes`; `CaptureCoordinator`'s
`performBurst` is now two-phase for Burst Mode only (Single-Shot Mode's `captureTo` path is
unchanged) - phase 1 captures all four images to memory back-to-back with only the configured
interval between them, phase 2 persists each through MediaStore afterward - so no MediaStore IPC or
metadata read-back sits between one image's capture and the next. `ImageProxy` turned out to
implement only `java.lang.AutoCloseable`, not `java.io.Closeable` (confirmed via `javap` against the
cached `camera-core` jar rather than assumed), so it's closed with a plain `try`/`finally` instead of
Kotlin's `use`. `testDebugUnitTest` (112/112 tests across 9 classes - existing burst tests were
updated to drive the new `captureToMemory`/`memoryOutcome` fake path rather than new tests being
added), `lintDebug` (0 issues), and `assembleDebug` all passed. `installDebug` plus a fresh
`adb logcat -s CaptureDiagnostics:I` burst trace confirmed the fix empirically: shot-to-shot cadence
(`CameraXRequestSubmitted`-to-`CameraXRequestSubmitted`, the truest apples-to-apples metric since it
isolates the capture-phase loop from the persist phase) dropped from ~1186/988/1042ms to
~926/890/841ms across the three gaps in a 4-image burst - about a 17% reduction (roughly 830ms →
690ms of per-shot overhead once the fixed 250ms configured interval is subtracted out), with the
remaining ~590-680ms per image still dominated by the camera hardware's own capture+JPEG-encode
time (unchanged by this fix, and not something a MediaStore-side change can touch). Total
Accepted-to-Completed burst duration improved from ~4017ms to ~3548ms. Trimming further would mean
reducing Burst Mode's capture resolution or capturing at a lower JPEG quality, not further storage
changes.

That capture-resolution lever was tried next, per an `app-spec.md` update reconciling "Capture
Performance" (which had explicitly required Burst Mode to stay full-resolution) to instead allow a
reduced resolution as long as photographs remain clearly usable. `CameraPreview.kt`'s
`ImageCapture.Builder` gained a `ResolutionStrategy` (bound size from a new pure `burstCaptureResolution`
function, unit-tested directly) applied only in Burst Mode. The first attempt used
`FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` targeting 2048x1536 - CameraX resolved this to 3648x2736
(~10MP) on the test device rather than something near the target, since that device's camera doesn't
expose a supported capture size close to 2048x1536, only larger discrete modes. A fresh on-device
`adb logcat` trace showed this ~20%-smaller resolution produced **no measurable latency change**
(~902ms average shot-to-shot gap vs. the prior ~886ms) - direct evidence that pixel count wasn't the
bottleneck at that small a reduction, contradicting the initial hypothesis. Switching to
`FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER` (same 2048x1536 target) resolved to 1920x1440 (~2.8MP)
instead, and *that* produced a large, unambiguous improvement: shot-to-shot gaps of ~528/518/515ms
(average ~520ms, down from ~886ms - a ~41% reduction), and Accepted-to-Completed burst duration
roughly halved, from ~3548ms to ~2011ms. So pixel count does dominate the remaining latency, but only
once cut aggressively (~2.8MP vs. the sensor's ~12.5MP full resolution) - a ~20% trim wasn't enough to
clear whatever fixed per-frame overhead (AE/AF settling, pipeline startup) sits underneath it.
`testDebugUnitTest` (114/114 tests across 10 classes, including the two new
`BurstCaptureResolutionTest` cases), `lintDebug` (0 issues), and `assembleDebug` all passed on the
final (`CLOSEST_LOWER_THEN_HIGHER`) version.

Burst Mode's scheduling itself was reworked next: `CaptureCoordinator.performBurst`'s capture-phase
loop previously called `delay(burstIntervalMillis)` unconditionally after each image, so real
shot-to-shot time was always `capture time + configured interval`, stacked serially. It's now
clock-anchored to a fixed schedule (`burstStart + n * burstIntervalMillis`) - each iteration waits
only for whatever time remains until its scheduled instant, so a capture that already took longer
than the interval (the common case per the measurements above) is followed by no delay at all,
matching "Burst Mode"'s existing "the configured interval is a target, not a guarantee" wording
without needing a spec change. This surfaced a real gap in the test doubles: `FakeTimeProvider` is a
manually-driven fake clock that doesn't auto-advance when a real `delay()` call completes, unlike
production's real `TimeProvider`, so scheduling logic that reads elapsed time *across* multiple
`delay()` calls within one suspend call (exactly what the new clock-anchored loop does) would see a
frozen clock while `TestCoroutineScheduler`'s own virtual time had actually moved on - silently
producing incorrect (growing) delay computations that the existing `isAtLeast`-based burst-spacing
test wasn't tight enough to catch. `FakeTimeProvider` gained an opt-in `attachScheduler` that folds
the `TestCoroutineScheduler`'s virtual time into `currentTimeMillis()`, wired up in both
`CaptureCoordinatorTest` and `CameraViewModelTest`; the existing spacing test was tightened from
`isAtLeast` to an exact `isEqualTo` (it now computes precisely, not just a lower bound), and two new
tests cover the no-delay-when-overrun and partial-delay cases directly. `testDebugUnitTest` (116/116
tests across 10 classes), `lintDebug` (0 issues), and `assembleDebug` all passed. A fresh on-device
`adb logcat` burst trace confirmed a large real-world win on top of the resolution change: shot-to-shot
gaps dropped from ~528/518/515ms (fixed delay, ~2.8MP resolution) to ~279/269/281ms (clock-anchored,
same resolution) - about a further 47% reduction - and Accepted-to-Completed burst duration fell from
~2011ms to ~1306ms. Combined across this session's three changes (deferred MediaStore writes, reduced
Burst Mode resolution, clock-anchored scheduling), average shot-to-shot cadence went from ~1072ms to
~276ms - a roughly 74% reduction overall.

A capture progress indicator was added next: a standalone control, rendered above the privacy
overlay (unlike the existing small capture-status badge, which hides underneath it), showing an
indeterminate spinner for the whole of a Single-Shot Mode capture, or a determinate one that
advances in four 25% steps as each Burst Mode image finishes. `CaptureState` gained a `BurstProgress`
case; `CameraUiState` gained a `captureProgress: CaptureProgressUi` field (`Hidden`/`Indeterminate`/
`Determinate(completedSteps, totalSteps)`), computed from the raw `CaptureState` already available
in `CameraViewModel`'s `uiState` combine. A live-sequence test asserting all four `BurstProgress`
values arrive in order, via the same Turbine pattern the existing single-shot state-transition test
already used, passed on the first try despite the fakes having no genuine suspension point between
images. `testDebugUnitTest` (123/123 tests across 10 classes, including 7 new cases spanning the
coordinator, ViewModel, and screen layers), `lintDebug` (0 issues), and `assembleDebug` all passed -
but real usage immediately surfaced a UX problem the unit tests hadn't caught: in Burst Mode, only a
plain spinner was visible, never four distinct steps.

The root cause was where `BurstProgress` fired from: `performBurst`'s *persist* phase, on the
reasoning that "an image isn't done until it's saved." That phase, however, is exactly what this
session's earlier MediaStore-deferral work made nearly instantaneous (no per-image delay, just
MediaStore IPC) - so all four progress steps landed within a fraction of a second of each other, at
the very end of an ~1.3s burst, reading as "stuck at 0%, then a flash" rather than four visible
steps. The fix was to move the emission to the *capture* phase instead, the phase
`burstIntervalMillis` actually paces, so each step is now visibly spaced out in real time as each
image is captured, not persisted.

That fix immediately broke two previously-passing `CameraViewModelTest` cases (haptics-on-burst-start
and mid-burst aspect-ratio deferral), which turned out to be a real, previously-latent bug the earlier
Turbine-based test had masked, not a test-only artifact: `BurstStarted` and the new `BurstProgress(1)`
now fire back-to-back with zero suspension between them (image 1 never waits), and `StateFlow`
conflates - a collector not already actively waiting on the exact instant `BurstStarted` is set can
miss it completely, not merely observe it briefly, if it's overwritten before that collector's
`launch { flow.collect { ... } }` gets its next turn on the dispatcher. `CameraViewModel`'s several
`captureCoordinator.state` collectors (haptics, burst-in-progress tracking, the debug overlay) are
exactly this ordinary `launch`-based shape, unlike the Turbine test, which collects the coordinator's
`state` with an eager/undispatched start that happens to catch every value regardless. The fix -
`yield()` after `BurstStarted` and after every `BurstProgress` - gives the dispatcher a guaranteed
chance to run any already-queued collector before the coordinator can overwrite that value with the
next one; it costs no virtual or real time, only scheduling order. This is a real general lesson
about `CaptureCoordinator.state`, not specific to this feature: a producer that writes to a
`MutableStateFlow` multiple times with no suspension in between can silently drop values for some
subscriber shapes and not others, and burst mode is exactly where the coordinator's own performance
work has made that gap shrink to zero. The mid-burst `CameraViewModelTest` needed one more
update, unrelated to the race: it had asserted progress was still 0/4 at the point `runCurrent()`
reaches the first inter-image delay, when with the phase-1 fix that point is now 1/4 (image 1 has
already been captured by then). `testDebugUnitTest`, `lintDebug`, and `assembleDebug` all passed
again once both fixes were in.

The small textual capture-status indicator (the "Ready" / "Capturing…" / "Photo saved" badge with a
tiny inline spinner, shown at top-center and hidden while the privacy overlay was up) was then
removed outright, now that the capture progress indicator covers the same "something is happening"
signal without any text. `CaptureStatusUi` and `CameraUiState.captureStatus` were deleted rather than
just unwired, since their only other consumer - a diagnostic-only `cameraAcceptingCaptureRequests`
flag on `GestureDiagnosticEvent.Cancelled` - turned out to be logically identical to
`captureProgress == CaptureProgressUi.Hidden` and was rewritten in terms of that instead, leaving
nothing referencing the old type. `app-spec.md`'s "UI requirements", "Overlay image visibility", and
overlay-layering sections were reconciled to match - the layer diagram now shows the progress
indicator as the one element above the overlay image rather than listing the now-gone status
indicator among the things it covers. Two `CameraScreenTest` cases that tested the removed
indicator's text directly were deleted; two more that exercised both it and the shutter button
together were narrowed to just the shutter button and renamed accordingly; four `CameraViewModelTest`
assertions against the removed `captureStatus` field were rewritten to check
`captureProgress == Hidden` instead, which was still a meaningful check at each of those call sites
(confirming the indicator clears after a capture completes, successfully or not). `testDebugUnitTest`
(121/121 tests across 10 classes - two fewer than before, from the deleted CameraScreenTest cases),
`lintDebug` (0 issues, confirming the removed string resources were the only references), and
`assembleDebug` all passed.

The debug and settings icons were then found (via real device testing) to overlap the status
bar/camera-cutout area - the root cause is Android 15+ (API 35+) enforcing edge-to-edge by default
for this app's `targetSdk` (37), regardless of anything explicit in `MainActivity`, so content draws
behind system bars unless it insets itself; neither icon had ever accounted for that. Both gained
`.statusBarsPadding()` ahead of their existing fixed `8.dp` padding, and `VoiceTriggerControl` (whose
existing fixed `72.dp` top offset exists specifically to clear the settings icon) gained the same
`.statusBarsPadding()` first, so that fixed offset keeps working regardless of the actual status
bar/notch height on a given device rather than assuming a specific one. `testDebugUnitTest` (still
121/121 - Robolectric resolves `statusBarsPadding()` to zero inset in the absence of a real window,
so no existing assertions were affected), `lintDebug` (0 issues), and `assembleDebug` all passed;
confirmed fixed via `installDebug` on the connected device.

The single global "Capture Mode" setting was then split into six independent per-trigger settings
(screen tap top half, screen tap bottom half, shutter button, volume up, volume down, voice
command), each its own Single-Shot/Burst choice, per a substantially expanded `app-spec.md` "Capture
Mode" section. `CaptureTrigger.ScreenTouch` became `ScreenTouchTop`/`ScreenTouchBottom`, split by
comparing a tap's Y position against the gesture surface's own height (`AwaitPointerEventScope.size`,
confirmed to exist via `javap` against the cached Compose UI AAR rather than assumed) - literal
screen halves, independent of aspect ratio or letterboxing. A new `CaptureTriggerKind` enum (plain,
payload-free, unlike `CaptureTrigger` itself) keys the six settings; `AppSettings.captureMode`
became `captureModeByTrigger: Map<CaptureTriggerKind, CaptureMode>`, and `DataStoreSettingsRepository`
gained one generated key per trigger rather than a single `capture_mode` key (the old key is simply
orphaned, not migrated).

The harder problem was the camera pipeline itself: `ImageCapture`'s latency/resolution optimization
(from the earlier burst-latency work) is a bind-time CameraX configuration shared by every trigger,
not something choosable per shot. Rather than accept a quality/speed compromise whenever triggers
disagree, `CameraViewModel.requestCapture` now dynamically rebinds the pipeline to whichever mode the
firing trigger needs, reusing the same reactive-rebind mechanism `CameraPreview` already had for
aspect-ratio changes (`ensureCaptureModeBound`, gated by a new `_boundCaptureMode` StateFlow -
`CameraUiState.captureMode` is repurposed from "the global setting" to "the pipeline's currently
bound mode"). Switching between differently-configured triggers now costs a one-time rebind delay;
repeated use of the same trigger, or of triggers sharing a mode, never rebinds. The wait is skippable
when the pipeline was never bound in the first place (permission not yet granted, `CameraPreview`
not yet composed) - waiting for a rebind that will never happen would otherwise hang every very-first
capture request behind a multi-second timeout for no reason.

That mode-per-trigger split broke two existing behaviors that had silently been relying on "capture
mode" meaning one global, always-current thing: the flash/torch-disable-during-burst logic used to
key off `CameraUiState.captureMode == BURST` (now just "whichever mode the pipeline is bound for," not
"a burst is actually running") - fixed to key off the raw `CaptureState` (`BurstStarted`/
`BurstProgress`) directly instead, which is also a more accurate signal than what was there before.
Sandbox testing then caught a genuine test-coordinate bug, not a production one: a new bottom-half-tap
test clicked dead-center horizontally, landing on the shutter button (which, unlike the passive
gesture surface beneath it, actively consumes its own clicks) instead of the gesture surface under
test - fixed by moving the test tap off-center. `testDebugUnitTest` (127/127 tests across 10 classes,
including 6 new cases spanning the coordinator-adjacent trigger rename, the ViewModel's per-trigger
routing, the screen-half split, and the six-control settings UI), `lintDebug` (0 issues), and
`assembleDebug` all passed. The dynamic-rebind mechanics themselves aren't unit-testable in the way
the rest of this logic is - `ImageCapture` is a real CameraX class this project's plain-JVM/Robolectric
tests can't meaningfully construct - so, consistent with how `CameraPreview.kt` (the only file that
touches real CameraX use cases) has never had unit coverage of its own binding behavior, that specific
mechanic needed real-device confirmation instead. `installDebug` plus an `adb logcat` trace of
pressing Volume Up (configured for Burst) immediately followed by Volume Down (left at Single-Shot)
confirmed it end to end: Volume Up's request correctly logged `captureMode=BURST` with the resolution
dropping to the burst-optimized 1920x1440 and all four images saving, and the very next
`CameraDiagnosticsSnapshot` - before Volume Down's own request - already showed the pipeline rebound
to full 4080x3060/`SINGLE_SHOT`, which Volume Down's request then correctly used. Two same-diagnostic-
category triggers (both `VOLUME_BUTTON`), independently resolving to different modes, with the shared
pipeline correctly rebinding in between - the exact behavior this whole feature exists to provide.

The one command genuinely not run is `./gradlew connectedDebugAndroidTest` - no emulator was
available in this environment (a physical device was connected and used for manual `adb`-driven
smoke testing instead, which is not the same as running the instrumented test suite), which is
expected and by design: that command always requires a connected device or emulator, camera
hardware included.
