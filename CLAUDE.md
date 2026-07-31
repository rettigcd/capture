# CLAUDE.md

Orientation notes for working in this repo. `README.md` and `app-spec.md` are the authoritative
docs for *what* the app does and *why*; this file is about *how work gets done here* - the
workflow, conventions, and build quirks that aren't obvious from scanning the code.

## What this is

`Capture` (`com.example.capture`) is a starter Android camera app: full-screen live preview, photo
taken by touch / either volume button / a voice command. `Capture`/`com.example.capture` are
placeholder names (see README's "Renaming the app"). Single Gradle module (`:app`).

`app-spec.md` is the living spec - the source of truth for intended behavior. `README.md` documents
the actual implementation plus a running "Build verification" narrative log (see below).

## The spec → implement → verify → document workflow

This project has been built up over many sessions via a consistent pattern - follow it unless told
otherwise:

1. **"Add to the spec"** (or "add to the spec document") is documentation-only: edit `app-spec.md`
   only, no code changes. Proactively remove/reconcile any existing spec text the new addition now
   contradicts - don't leave stale prose behind.
2. **"Implement it"** means implement whatever was most recently added to the spec (not a re-read
   of the whole spec).
3. **After implementing**, verify with a real sandboxed Gradle build before calling it done (see
   "Sandbox build workflow" below) - fix any real compile/test errors found, don't just assume it
   compiles.
4. **Update `README.md`** to reflect the verified implementation, including appending a new
   paragraph to the "Build verification" section's narrative log describing what changed, what
   broke and was fixed, and the resulting test count.
5. **Deploy only when explicitly asked.** When a device is connected, verify empirically via
   `adb logcat` / `dumpsys` (grep for the app's own log tags, check `mCurrentFocus`, check for
   `FATAL EXCEPTION`) rather than assuming correctness.
   - **Avoid screenshotting the device for verification.** A screenshot can capture whatever app
     happens to be frontmost at that instant (including other apps with personal data) if the
     device's focus changes around the capture - logcat/dumpsys give the same verification without
     that risk.
6. **Only commit when explicitly asked** ("commit it"). Stage files explicitly by path (never
   `git add -A`/`git add .`), never amend, review `git status` before committing, and end commit
   messages with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>` via heredoc.

## Sandbox build workflow

This environment has a preinstalled Android SDK and a cached Gradle distribution, but **no access
to the Gradle Plugin Portal** (`plugins.gradle.org`) - only `dl.google.com`/`repo.maven.apache.org`.
Two plugins can't resolve here as a result: `org.jlleitschuh.gradle.ktlint` and
`org.gradle.toolchains.foojay-resolver-convention`. To run a real build in this environment:

1. Create `local.properties` with `sdk.dir=...` (escape drive-letter colons and backslashes on
   Windows, e.g. `sdk.dir=C\:/path/to/Sdk` - Android Lint's `PropertyEscape` check fails otherwise).
   It's gitignored; delete it again when done.
2. Temporarily comment out with a `// TEMP:` prefix (not delete): the ktlint plugin alias in both
   `build.gradle.kts` and `app/build.gradle.kts`, the `ktlint { ... }` block in
   `app/build.gradle.kts`, and the `foojay-resolver-convention` plugin id in `settings.gradle.kts`.
3. Run via the cached Gradle distribution directly (not `./gradlew`, since the wrapper jar isn't
   committed to this repo - see README). Typical target: `testDebugUnitTest lintDebug assembleDebug`.
4. **Afterward, always restore every `// TEMP:` line** (`grep -rn TEMP -- '*.kts'` should return
   nothing when done) and clean up: `gradle --stop`, then delete `app/build build .gradle .kotlin
   local.properties`.
5. If `lintDebug`/`testDebugUnitTest` report stale results after a fix, Gradle's configuration cache
   can serve an old report - rerun with `--rerun-tasks` (or delete the relevant intermediate dir) to
   confirm before trusting a green result.

Connected device for on-device verification: adb sees it as a normal Android device; use
`adb devices -l`, then `adb shell dumpsys window | grep mCurrentFocus`, `adb logcat`, etc.
`installDebug` needs the same sandbox setup as above (steps 1-2), then just `installDebug` instead
of the full test/lint/assemble set.

## Architecture rules (enforced by test structure, not just convention)

- **`camera.domain`, `voice.domain`, `settings.domain` never import Android, Compose, or CameraX
  types.** They're plain Kotlin, runnable in a plain JUnit test with no Robolectric/emulator. Any
  new domain type or interface must keep this property.
- Each feature package (`camera`, `voice`, `settings`, `permissions`) follows `domain` (interfaces +
  pure logic) / `data` (the one place touching the real Android/CameraX/DataStore API) / `ui`
  (Compose, stateless screens + a ViewModel) - `di/` wires `data` impls to `domain` interfaces via
  Hilt `@Binds`.
- Compose screens (`CameraScreen`, `SettingsScreen`) are stateless: every value comes from a
  `UiState` param, every action is a callback param - this is what lets them be exercised in
  Robolectric tests with hand-built state and counting lambdas, no Hilt/real dependencies.
- Indirection seams exist specifically for testability: `common/TimeProvider`,
  `common/DispatcherProvider`, `common/ApplicationScope` (a qualifier for a process-lifetime
  `CoroutineScope`, for writes that must outlive a ViewModel being cleared mid-flight - see
  `SettingsViewModel`/`CameraViewModel`).
- All capture triggers (`CaptureTrigger`: `ScreenTouch`, `ShutterButton`, `VolumeUp`, `VolumeDown`,
  `VoiceCommand`) funnel through one place, `CaptureCoordinator.requestCapture` - there is
  intentionally no separate capture code path per input source.

## Testing conventions

- Fakes, not a mocking framework (`app/src/test/.../testing/TestDoubles.kt` - shared across
  `camera`/`voice`/`settings` tests). Add new fakes there, following the existing naming
  (`FakeXxx`) and style (record calls in a public `MutableList`/count field for assertions).
- `kotlinx-coroutines-test`'s `StandardTestDispatcher` + `FakeTimeProvider`, no real `delay()`s.
  `runCurrent()` is used to pause mid-operation (e.g. mid-burst) and assert in-flight state.
- **A `StandardTestDispatcher`-driven fake with no real suspension point runs a whole
  `requestCapture` call to completion in one scheduler tick** - two concurrent launches don't
  actually race for `CaptureCoordinator`'s mutex unless the first one has a genuine suspend point
  (e.g. Burst Mode's `delay()`). Keep this in mind before writing a "concurrent requests contend for
  the lock" test with Single-Shot Mode; it won't reproduce the race.
- Compose UI tests (`CameraScreenTest`, `SettingsScreenTest`) run via Robolectric as part of
  `testDebugUnitTest`, no emulator needed. `BuildConfig.DEBUG` is true under this test task, so
  debug-only UI (e.g. the diagnostics overlay toggle) renders and is testable there.
- Pure logic gets a plain JUnit test with no Robolectric when possible (e.g.
  `AspectRatioClassifierTest`, `CameraPreviewRotationTest` for `surfaceRotationFor`) - extract a
  small top-level/internal function specifically to make this possible when the logic is otherwise
  embedded in Android-facing code.
- Instrumented tests (`app/src/androidTest/...`) need a real device/emulator and are not run as part
  of routine verification in this environment (no emulator available) - `connectedDebugAndroidTest`
  is the one command that's genuinely never been executed here.

## Logging conventions

Two parallel patterns, pick based on how the entry is produced:

- **Terminal, rare events** (one per capture/error - `CaptureErrorLogger`, `CaptureMetadataLogger`):
  `suspend fun log(entry)`, JSON Lines (`org.json.JSONObject`, one object per line) appended to an
  app-private file in `filesDir`, `IOException` swallowed silently (a logging failure must never
  crash or interrupt capture).
- **Frequent, hot-path events** (`CaptureDiagnosticsLogger`, `GestureDiagnosticsLogger` - many
  events per capture attempt or per touch interaction): plain non-`suspend` `fun log(...)`, always
  written to Logcat synchronously, optionally fire-and-forget onto `@ApplicationScope` for file
  writes - never block the caller on I/O.
- A persisted "enabled" setting that a hot-path logger needs to check is mirrored into a
  `@Volatile` field via a background collector (see `AndroidDiagnosticsLogger`,
  `CameraViewModel.burstInProgress`/`effectiveCaptureAspectRatio`) rather than read from the
  suspend-based `SettingsRepository` on every call.

## Where things live (quick map)

```
camera/domain/  CaptureTrigger (+ ShutterButton, CaptureTriggerSource), CaptureCoordinator,
                CaptureModels (CaptureMode, CaptureState, CaptureResult, CaptureOutcome),
                CaptureAspectRatio, AspectRatioClassifier, CaptureAttemptId(Generator),
                CaptureErrorLogger, CaptureMetadataLogger, ImageMetadataReader,
                CaptureDiagnostics.kt (CaptureDiagnosticEvent, CameraDiagnosticsSnapshot,
                CaptureRejectionReason, CaptureDiagnosticsLogger),
                GestureDiagnostics.kt (GestureDiagnosticEvent, GestureClassification,
                GestureCancellationReason, GestureDiagnosticsLogger),
                CameraCaptureController / PhotoStorage / HapticFeedback /
                OverlayVisibilityRepository / FlashTorchController interfaces
camera/data/    CameraXCaptureController, MediaStorePhotoStorage, ImageCaptureUseCaseHolder,
                CameraControlHolder, AndroidHapticFeedback, DataStoreOverlayVisibilityRepository,
                FileCaptureErrorLogger, FileCaptureMetadataLogger, AndroidImageMetadataReader,
                CameraXFlashTorchController, AndroidDiagnosticsLogger
camera/ui/      CameraViewModel, CameraUiState, DiagnosticsOverlayInfo, CameraScreen (gesture
                detector + letterboxed preview + debug overlay), CameraRoute, CameraPreview
                (the only file touching CameraX Preview/ImageCapture use cases directly)
voice/          domain: VoiceCommandRecognizer, VoiceCommandMatcher, VoiceRecognitionState
                data: AndroidSpeechRecognizerAdapter (only android.speech.* code)
settings/       domain: AppSettings, SettingsRepository, OverlayImageStore
                data: DataStoreSettingsRepository, FileOverlayImageStore
                ui: SettingsViewModel, SettingsUiState, SettingsScreen, SettingsRoute
common/         DispatcherProvider, TimeProvider, ApplicationScope
di/             AppModule, CameraModule, VoiceModule, SettingsModule (Hilt @Binds wiring)
```

Settings screen currently has 6 controls (vibration duration, overlay image, capture mode, burst
interval, capture aspect ratio, diagnostic file logging) - check `SettingsScreen.kt`/`AppSettings.kt`
before assuming this number if it matters to a change.

## Known non-obvious gotchas

- `androidx.core.util.Rational` doesn't exist - use `android.util.Rational` for CameraX's
  `ViewPort.Builder`.
- `onNode`/`onAllNodes` (Compose UI testing) are members of `SemanticsNodeInteractionsProvider`
  (implemented by the compose test rule), not top-level imports from `androidx.compose.ui.test` -
  don't add an import for them.
- `PointerInputChange.isConsumed`/`.consume()` are members of the class, not extensions requiring
  import (unlike `changedToUpIgnoreConsumed()`/`positionChange()`, which do need one).
- `BuildConfig.DEBUG` requires `buildFeatures.buildConfig = true` in `app/build.gradle.kts`
  (not on by default in this AGP version).
- The app is locked to portrait (`android:screenOrientation="portrait"`), so `Configuration`/
  `Display.getRotation()` never change - capture-rotation tracking uses a raw
  `OrientationEventListener` instead (see `CameraPreview.kt`/`surfaceRotationFor`).
- `MainActivity` declares `android:configChanges` for orientation, so `CameraPreview`'s binding
  survives rotation instead of being torn down/rebuilt.
