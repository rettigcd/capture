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
    end

    subgraph CameraData["camera.data"]
        CamX[CameraXCaptureController]
        MediaStoreImpl[MediaStorePhotoStorage]
        Holder[ImageCaptureUseCaseHolder]
        HapticImpl[AndroidHapticFeedback]
        OverlayImpl[DataStoreOverlayVisibilityRepository]
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
    Screen -->|gear icon| Nav
    Preview -->|attaches ImageCapture use case| VM
    VM --> Coordinator
    VM --> SettingsIface
    VM --> HapticIface
    VM --> OverlayIface
    Coordinator --> CamIface
    Coordinator --> StorageIface
    CamIface -.implemented by.-> CamX
    StorageIface -.implemented by.-> MediaStoreImpl
    HapticIface -.implemented by.-> HapticImpl
    OverlayIface -.implemented by.-> OverlayImpl
    OverlayImpl --> DataStore
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
│   ├── domain/                   CaptureTrigger, CaptureCoordinator, CaptureModels, the
│   │                             CameraCaptureController / PhotoStorage / HapticFeedback /
│   │                             OverlayVisibilityRepository interfaces
│   ├── data/                     CameraXCaptureController, MediaStorePhotoStorage,
│   │                             ImageCaptureUseCaseHolder, AndroidHapticFeedback,
│   │                             DataStoreOverlayVisibilityRepository - the only
│   │                             CameraX/MediaStore/Vibrator/overlay-DataStore code
│   └── ui/                       CameraViewModel, CameraUiState, CameraScreen (stateless,
│                                 including the swipe-gesture overlay logic),
│                                 CameraRoute (permissions + Hilt wiring), CameraPreview
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

Every successful capture - regardless of whether it was triggered by touch, a volume button, or a
voice command - gets both visual and haptic feedback:

* The capture-status indicator shows "Photo saved" (`CameraScreen.kt`).
* The device performs one haptic pulse (`camera/domain/HapticFeedback.kt`, implemented by
  `camera/data/AndroidHapticFeedback.kt` using `VibrationEffect.createOneShot(durationMillis, ...)`),
  so a successful capture can be felt without having to look at the screen. Its duration is a
  user setting (see "Settings" below) rather than a fixed value, which is why this uses
  `createOneShot` instead of a predefined system effect (predefined effects have a fixed length
  that can't be customized).

`CameraViewModel` triggers the pulse from a dedicated collector on `CaptureCoordinator.state` (see
its `init` block) rather than as a derived property of `uiState`, specifically so it fires exactly
once per completed capture instead of repeating for as long as the status happens to still read
"saved." Only successful captures vibrate; a failed capture does not.

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

## Settings

A gear icon in the top-right corner of the camera screen (always visible, regardless of
permission state) opens a separate, full-screen settings screen (`settings/ui/SettingsScreen.kt`),
reached and left via `androidx.navigation.compose.NavHost` in `CaptureApp.kt`; the system/gesture
back action returns to the camera screen normally.

The settings screen has two controls, both backed by `SettingsRepository` /
`DataStoreSettingsRepository` (Jetpack DataStore Preferences, so values persist across app
restarts):

1. **Vibration duration** - a `Slider` snapped to 60 ms increments from 60-300 ms
   (`AppSettings.VIBRATION_DURATION_RANGE_MILLIS`), used for the capture-success pulse described
   above.
2. **Overlay image selection** - a "Choose image" button that launches the system Photo Picker
   (`ActivityResultContracts.PickVisualMedia`, hosted in `settings/ui/SettingsRoute.kt`). Like the
   camera permission model, this needs no runtime storage/media permission at all.

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
`onVibrationDurationChanged`/`onImageSelected` both launch on the injected `@ApplicationScope`
`CoroutineScope` instead (the same one `CameraViewModel` already uses to release the voice
recognizer after `onCleared()`, and to persist overlay visibility - see above), which outlives the
settings screen. `SettingsViewModelTest`'s `"a selection write survives the view model being
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
  `VoiceCommandMatcherTest`, `CapturePermissionsTest`, `SettingsViewModelTest`. Use fakes
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
- [ ] **Haptic feedback:** a short vibration pulse is felt immediately when a photo saves
      successfully, for all three triggers (touch, volume button, voice); no pulse occurs on a
      failed capture; toggling the device's system-wide haptics/vibration setting off suppresses it
      (this app does not override that system setting).
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
- [ ] **Rotation:** rotate the device through all four orientations while the preview is showing;
      the preview keeps running without a black flash, freeze, or crash; take a photo immediately
      after rotating.
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
      the camera screen with its state (capture status, voice toggle) intact.
- [ ] **Vibration duration setting:** moving the slider changes the felt pulse length on the next
      capture; the value survives an app restart.
- [ ] **Overlay swipe gestures:** with an image selected, a left swipe on the live preview slides
      the overlay image in from the right edge and settles over the preview; the capture-status
      indicator and shutter button both disappear at the same time (the voice control and gear
      icon stay visible); capture (touch/volume/voice) still works and still saves a real photo
      while the overlay is shown, even with the shutter button hidden; a right swipe on the overlay
      slides it back off to the right, restoring the live preview and bringing the status
      indicator and shutter button back; the slide visibly follows the finger while dragging rather
      than only snapping at the end.
- [ ] **Overlay-visibility persistence:** leave the overlay showing (or hidden) and fully close the
      app, then relaunch it - the same mode is restored automatically, with no settings-screen
      control for it anywhere.
- [ ] **Image selection:** "Choose image" opens the system Photo Picker; picking an image updates
      the thumbnail on the settings screen and the overlay image on the camera screen; the
      selection survives an app restart without needing to re-pick.
- [ ] **Volume keys on the settings screen:** while settings is open, volume buttons adjust the
      device's normal media volume instead of taking a photo.

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

The one command genuinely not run is `./gradlew connectedDebugAndroidTest` - no emulator was
available in this environment (a physical device was connected and used for manual `adb`-driven
smoke testing instead, which is not the same as running the instrumented test suite), which is
expected and by design: that command always requires a connected device or emulator, camera
hardware included.
