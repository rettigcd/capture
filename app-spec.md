Create a production-quality starter Android camera application using Kotlin and the current stable Android development tools.

Do not merely describe the project. Generate the complete project structure and source files so that I can open it in the current stable Android Studio, sync Gradle, run the tests, and deploy it to an Android phone.

## Application purpose

The app displays a full-screen live camera preview and takes a photograph when the user performs any of these actions:

1. Touches anywhere on the camera preview.
2. Presses either hardware volume button.
3. Speaks a configured command such as “photo,” “picture,” “capture,” or “cheese.”

All three inputs must invoke the same central capture operation. Do not implement three separate camera-capture paths.

Each of these actions requests a single photograph, a burst of four, or a video recording, depending on that specific action's own independently-configured capture mode (a screen touch is itself split into a top-half and a bottom-half trigger for this purpose - see "Capture Mode").

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
4. Display a rear-camera preview, centered on screen and constrained to the selected capture aspect ratio rather than stretched to fill the screen (see "Capture Aspect Ratio and Preview Framing").
5. Allow the user to take a picture by touching anywhere on the preview.
6. Capture a picture when either volume-up or volume-down is pressed.
7. Capture a picture when a supported spoken command is recognized.
8. Display brief visual feedback when capture begins and when the picture is saved.
9. Trigger a brief haptic vibration with a user-configurable duration (see "Settings"), so the user can tell a capture request was handled without having to look at the screen: in Single-Shot Mode this fires when the picture is saved successfully; in Burst Mode this fires once when the burst is triggered, not per image and not tied to save success (see "Burst Feedback"); in Video Mode this fires once when the recording starts and twice when it stops (see "Video Mode").
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

Save videos (see "Video Mode") the same way - through `MediaStore`, not direct filesystem paths -
into a recognizable Movies subfolder such as `Movies/Capture`, using a timestamp-based filename and
a standard video format.

Return a structured capture result containing at least:

* Success or failure
* Saved content URI when successful
* Trigger source
* Timestamp
* Safe user-facing error information

See "Captured image metadata and validation" for the additional diagnostic fields (actual
dimensions, aspect ratio, tolerance match, EXIF orientation) recorded after each successful
capture.

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
* The speech-recognition engine and the recognized command vocabulary shall be independently replaceable. The remainder of the application shall not depend on whether commands originate from Android SpeechRecognizer, an offline keyword engine, or another future implementation.

Provide an obvious UI indication whenever the microphone is actively listening. Voice triggering should be user-controllable rather than silently recording.

## Future Voice Engines

The architecture should permit future implementations including:

Android SpeechRecognizer (initial implementation)
On-device continuous command-recognition engines
Offline keyword spotting
Vendor-specific speech SDKs
Custom machine-learning inference engines

No changes should be required to the capture coordinator, camera logic, or UI when replacing one voice engine with another.

Posible future implementations might include:
* openWakeWord
* Vosk
* Picovoice Porcupine

## Capture coordination

Create one capture coordinator or use case that receives commands similar to:

```kotlin
sealed interface CaptureTrigger {
    data object ScreenTouchTop : CaptureTrigger
    data object ScreenTouchBottom : CaptureTrigger
    data object ShutterButton : CaptureTrigger
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

* A camera preview centered within the camera screen and constrained to the selected capture aspect ratio, letterboxed or pillarboxed as needed rather than stretched to fill the screen (see "Capture Aspect Ratio and Preview Framing")
* Capture progress indicator - spinner for Single-Shot Mode, four-step progress for Burst Mode - shown above Overlay View rather than hidden by it (see "Capture Progress Indicator"). No textual capture status ("Capturing…" / "Photo saved" or similar) is shown anywhere on screen.
* No voice-listening indicator or voice-trigger enable/disable control on the camera screen - the
  control is hidden (voice triggering remains implemented underneath but is not currently reachable
  from the UI)
* Permission and permission-related error messages (capture and file-saving errors are not shown on this screen - see "Error Handling")
* Optional visible shutter button for accessibility, even though touching the preview also captures (also hidden while Overlay View is shown)
* Compact camera zoom and capture aspect ratio controls at the bottom of the screen (see "Camera
  zoom" in "Settings" and "Capture Aspect Ratio and Preview Framing") - sized to take up as little
  screen space as practical rather than a full-width control, since they sit directly over the live
  preview, and hidden while Overlay View is shown, matching the shutter button
* Content descriptions and usable semantics for interactive controls
* A swipeable cover photo that slides over the camera preview and back off-screen in response to horizontal swipe gestures (see "Cover photo visibility")
* A gear icon in the top-right corner that opens the settings screen (see "Settings")

Keep the UI intentionally simple. The objective is a clean architectural foundation, not a polished commercial camera interface.

The app shall be locked to portrait orientation only (e.g. `android:screenOrientation="portrait"` on the main Activity). The system shall never rotate the app's layout into landscape, regardless of how the physical device is held or which capture aspect ratio is selected - see "Orientation changes" in "Capture Aspect Ratio and Preview Framing" for how this interacts with the aspect-ratio preview mapping.

## Cover photo visibility

The user configures a list of up to three **cover photos** on the settings screen (see "Cover
Photos" in "Settings"). The main camera screen supports two viewing modes built on that list:

* **Camera Preview** - displays the live camera preview.
* **Overlay View** - displays the live camera preview together with the *active* cover photo, i.e.
  the one at the current cover-photo index. The active cover photo always covers the full screen -
  not just the aspect-ratio-constrained preview area - regardless of the selected capture aspect
  ratio or any letterboxing/pillarboxing around the preview (see "Overlay sizing").

The user switches between these modes, and cycles between cover photos while already in Overlay
View, using horizontal swipe gestures recognized across the full camera screen, not just within
the preview area, and not a settings-screen control:

* When Camera Preview is visible and at least one cover photo is configured, a left swipe causes
  the active cover photo (the one at the current cover-photo index) to slide in from the right
  edge of the screen until it reaches its normal position over the camera preview. When no cover
  photos are configured, a left swipe has no effect - there is nothing to show, and Overlay View
  cannot be entered.
* When Overlay View is already visible and more than one cover photo is configured, a left swipe
  advances the cover-photo index to the next cover photo and swaps it in without leaving Overlay
  View; the index wraps from the last cover photo back to the first. Repeatedly swiping left cycles
  through every configured cover photo in order. When zero or one cover photo is configured, a left
  swipe while already in Overlay View has no effect.
* When Overlay View is visible, a right swipe (regardless of how many cover photos are configured)
  causes the active cover photo to slide off the right edge of the screen, revealing only the
  camera preview. A right swipe never changes the cover-photo index, only visibility.
* Swipe animations should be smooth and follow the user's gesture where practical (for example, a
  partial drag reveals a partial slide rather than only snapping at the end of the gesture).

The cover-photo index defaults to 0 (the first cover photo ever added) and persists across app
restarts and across leaving/re-entering Overlay View, the same way overlay-visibility itself
persists (see below) - swiping right to dismiss and later left to reopen resumes on the same cover
photo rather than resetting to the first one. Deleting a cover photo on the settings screen may
also adjust the index; see "Cover Photos" in "Settings" for the exact rule.

Switching to Overlay View is display-only: it does not pause or stop the camera. All three capture
triggers (touch, volume buttons, voice command) must keep working exactly as before while the
active cover photo is shown in place of the preview, so this doubles as a discreet/privacy display
mode, not merely a cosmetic one.

While Overlay View is shown, the visible shutter button must be hidden - conceptually it sits
underneath the active cover photo, so the image fully covers it, not just the camera preview.
Capture must still work by tapping anywhere on the active cover photo even though the shutter
button isn't visible. Switching back to Camera Preview must make it visible again.

The capture progress indicator (see "Capture Progress Indicator") is a deliberate exception to
this: it sits above the active cover photo, not underneath it, and stays visible during a capture
regardless of which mode the screen is in.

The application shall remember whether Overlay View or Camera Preview was showing when the app was
last closed, and restore that same mode automatically the next time the app is launched. This
overlay-visibility state must persist the same way a setting would (e.g. surviving process death,
not just configuration change), even though it is not exposed as one.

Overlay visibility is controlled exclusively through the swipe gestures described above. No
user-facing setting or configuration option - on the settings screen or anywhere else - shall
exist to enable or disable Overlay View directly (the settings screen only manages which images
are available to it - see "Cover Photos" in "Settings").

## Capture Aspect Ratio and Preview Framing

### Overview

The camera preview shall display the same aspect ratio and effective sensor crop as the image
that will be captured.

The camera preview and the active cover photo (see "Cover photo visibility") are independent
visual layers:

* The camera preview is constrained to the selected capture aspect ratio.
* The active cover photo always covers the entire screen.
* Changing the capture aspect ratio shall not change the size or layout of the active cover photo.

### Supported capture aspect ratios

The application shall support the following capture aspect ratios:

* **4:3**
* **16:9**

The selected capture aspect ratio shall be persisted using DataStore and restored when the
application starts (see "Settings").

The default capture aspect ratio shall be **4:3**.

Aspect ratios are defined using landscape orientation:

| Setting | Landscape | Portrait |
| ------- | --------: | -------: |
| 4:3     |       4:3 |      3:4 |
| 16:9    |      16:9 |     9:16 |

For example, when the device is held in portrait orientation and the selected capture ratio is
4:3, the visible camera preview shall use a 3:4 display area.

### Camera preview layout

The camera preview shall be centered within the available camera screen.

The preview container shall use the selected capture aspect ratio.

The preview shall not be stretched or distorted to fill the entire screen.

When the device screen has a different aspect ratio than the selected capture ratio, unused
screen space may appear above and below the preview or to the left and right of the preview.

Unused space outside the preview shall use the application's configured background color.

Example of a 4:3 capture ratio on a portrait device:

```text
┌─────────────────────────┐
│                         │
│    unused background    │
│                         │
├─────────────────────────┤
│                         │
│                         │
│     CAMERA PREVIEW      │
│          3:4            │
│                         │
│                         │
├─────────────────────────┤
│                         │
│    unused background    │
│                         │
└─────────────────────────┘
```

The application shall prefer letterboxing or pillarboxing over cropping the preview merely to
match the screen's aspect ratio.

### Preview and capture consistency

The visible camera preview shall represent the same framing as the resulting captured image as
closely as CameraX and the device hardware permit.

The application shall:

1. Apply the same selected aspect-ratio preference to both the CameraX `Preview` and
   `ImageCapture` use cases.
2. Use a shared CameraX viewport and use-case group so that preview and capture use the same
   effective sensor crop.
3. Bind the camera use cases only after the preview view has been measured and its viewport is
   available.
4. Rebuild or rebind the affected CameraX use cases when the selected capture aspect ratio
   changes.
5. Avoid independently scaling or cropping the preview in a way that causes the captured image to
   contain materially different framing.

The application shall treat the selected aspect ratio as a preference rather than an absolute
hardware guarantee. If the requested aspect ratio is unavailable for a particular camera or
use-case combination, CameraX may select the closest supported configuration.

Any fallback shall be logged (see "Captured image metadata and validation"). The log entry shall
include:

* requested aspect ratio
* actual preview resolution
* actual capture resolution
* actual preview aspect ratio
* actual capture aspect ratio
* selected camera
* device orientation
* fallback reason, when known

### Preview scaling

The camera preview shall preserve the camera stream's aspect ratio.

The preview shall fill its aspect-ratio-constrained container without distortion.

Minor cropping within the preview container is acceptable only when required because the selected
camera stream differs slightly from the requested ratio.

The application shall not scale the preview to fill the entire device screen when doing so would
change the visible framing relative to the captured image.

### Orientation changes

The application is locked to portrait orientation only (see "UI requirements") - there is no
landscape display case to support. The camera preview therefore always uses each ratio's portrait
mapping:

* 4:3 capture shall display as 3:4.
* 16:9 capture shall display as 9:16.

This mapping never changes at runtime, since the app's own layout orientation never changes. The
physical device can still be rotated in the user's hand while the app's layout stays locked to
portrait, but the physical camera sensor's long axis, the app's locked-portrait layout, and the
preview container are always aligned with the phone body's long axis regardless of how it is held -
so physical rotation shall never change what the preview displays. The on-screen preview - its
framing, aspect ratio, and viewport - shall stay fixed at all times, independent of physical device
rotation; only the `ImageCapture` use case's target rotation (and therefore the captured photo's
EXIF orientation tag) shall track the physical device's rotation, so a captured photo still records
the orientation it was actually taken in even though nothing on screen changes when the phone is
rotated.

The active cover photo shall continue to fill the entire screen regardless of the physical
device's rotation.

## Overlay sizing

### Independence from capture aspect ratio

The active cover photo shall be independent of the camera preview and capture aspect ratio.

The active cover photo shall not be placed inside the aspect-ratio-constrained camera preview
container.

The active cover photo and camera preview shall be sibling layers within a full-screen parent
container.

Conceptual layer order:

```text
Full-screen root container
├── centered camera preview constrained to capture aspect ratio
├── other camera controls
├── full-screen active cover photo
└── capture progress indicator
```

When Overlay View is shown, the active cover photo shall appear above:

* the camera preview
* unused preview background areas
* the visible shutter control
* all other camera-screen content

except the capture progress indicator (see "Capture Progress Indicator"), which is the one element
that appears above the active cover photo instead.

The camera shall remain active underneath the active cover photo, and capture shall remain
available through the supported touch, voice, and volume-button triggers - consistent with "Cover
photo visibility" above.

### Full-screen overlay behavior

The active cover photo shall cover the full available screen regardless of:

* selected capture aspect ratio
* preview dimensions
* camera resolution
* device orientation
* letterboxing or pillarboxing around the preview

Changing between 4:3 and 16:9 capture shall not resize, reposition, or rebind the active cover
photo. Cycling to a different cover photo (see "Cover photo visibility") shall not resize,
reposition, or rebind the camera preview.

Example with Overlay View shown:

```text
┌─────────────────────────┐
│                         │
│                         │
│      COVER PHOTO        │
│                         │
│      FULL SCREEN        │
│                         │
│                         │
└─────────────────────────┘
```

### Cover photo scaling

Each cover photo shall preserve its own original aspect ratio.

By default, the active cover photo shall use center-crop scaling:

* The image shall fill the entire screen.
* The image shall not be stretched or distorted.
* Portions of the image may be cropped when its aspect ratio differs from the screen.
* Cropping shall be centered unless another focal-position feature is added later.

A cover photo shall not use the selected camera capture ratio when determining its size or crop.

The active cover photo shall be scaled against the full-screen overlay bounds.

### Overlay gestures

The swipe gestures described in "Cover photo visibility" shall operate across the full screen,
not only within the camera preview area.

A left swipe shall display the active cover photo by sliding it in from the right, when at least
one cover photo is configured.

An additional left swipe while Overlay View is already shown shall advance to the next cover
photo, wrapping from the last back to the first, when more than one cover photo is configured.
Because the cover photo gives no visual feedback that tracks the finger during this swipe (unlike
showing/dismissing it), advancing to the next cover photo shall additionally require the swipe's
horizontal distance to exceed the same swipe threshold used for gesture diagnostics
classification, not merely enough movement to be recognized as a drag - so a mostly-vertical swipe
with a little incidental horizontal drift does not unintentionally advance the cover photo.

A right swipe shall dismiss the active cover photo by sliding it off to the right, without
changing the cover-photo index.

Gesture thresholds and tap-versus-swipe detection shall remain unchanged regardless of the
selected capture aspect ratio or the number of configured cover photos.

## Captured image metadata and validation

After each successful capture, the application shall record the actual saved image dimensions.

The capture result or diagnostic log shall include:

* image width in pixels
* image height in pixels
* normalized aspect ratio
* requested aspect ratio
* whether the actual ratio matched the requested ratio within an allowed tolerance
* image URI or destination identifier
* EXIF orientation, when available

Aspect-ratio comparisons shall use a tolerance rather than exact floating-point equality.

Portrait and landscape versions of the same ratio shall be treated as equivalent.

Examples:

| Dimensions  | Classified Ratio |
| ----------- | ----------------- |
| 4032 × 3024 | 4:3               |
| 3024 × 4032 | 4:3               |
| 4000 × 2250 | 16:9              |
| 2250 × 4000 | 16:9              |

If the captured image does not match the requested ratio within the configured tolerance, the
application shall log the discrepancy but shall not display an on-screen error (consistent with
"Error Handling").

## Acceptance criteria for aspect ratio and preview framing

This part of the application is complete when all of the following are true:

1. A 4:3 capture setting produces a centered 3:4 preview (the app is locked to portrait only -
   see "UI requirements").
2. A 16:9 capture setting produces a centered 9:16 preview.
3. The preview does not stretch to match the phone screen.
4. The preview framing closely matches the resulting captured image.
5. The same viewport or crop region is used for preview and capture.
6. Changing the capture ratio resizes and rebinds the camera preview and capture use cases.
7. Changing the capture ratio does not change the active cover photo's bounds.
8. The active cover photo covers the entire screen, including unused space surrounding the preview.
9. Each cover photo preserves its own aspect ratio and uses center-crop scaling.
10. Touch, voice, and volume-button capture continue to work while a cover photo is visible.
11. The selected capture ratio persists across application restarts.
12. Actual captured dimensions and aspect ratio are recorded in diagnostic logs.
13. The app stays locked to portrait orientation regardless of how the physical device is
    rotated, and the active cover photo's full-screen behavior is unaffected by physical device
    rotation.

## Capture Mode

Each of the six ways a capture can be triggered has its own independently-configured capture mode,
selected from the Settings page (see "Settings") rather than the camera screen itself:

* Screen tap - top half
* Screen tap - bottom half
* Shutter button
* Volume up
* Volume down
* Voice command

The screen-tap trigger described elsewhere in this document (see "Application purpose" and
"Initial application behavior") is split into these two independent triggers by vertical screen
position: a tap landing in the top half of the full screen (not just the aspect-ratio-constrained
preview area) is the "top half" trigger, and a tap landing in the bottom half is the "bottom half"
trigger - the same split applies regardless of whether the live preview or a privacy cover photo
is currently shown (see "Cover photo visibility").

Each of the six triggers is independently set to one of three capture modes:

* **Single-Shot Mode** - that trigger requests one image, matching the behavior described in
  "Application purpose" and "Initial application behavior" above.
* **Burst Mode** - that trigger requests four images in quick succession (see "Burst Mode" below).
* **Video Mode** - that trigger starts a video recording (see "Video Mode" below).

A trigger's configured mode is entirely independent of every other trigger's - for example, Volume
Up can be set to Burst Mode while the screen's top half stays set to Single-Shot Mode, and each
behaves only according to its own setting. All six settings are persisted and restored
automatically when the application restarts, the same way the other settings are.

The underlying camera hardware pipeline's latency/resolution behavior (see "Capture Performance")
is a shared, one-at-a-time configuration rather than something chosen per capture, so using a
trigger whose configured mode differs from whichever mode the pipeline is currently configured for
incurs a one-time delay while the pipeline reconfigures itself for the new mode before that capture
proceeds. Repeated use of the same trigger, or of triggers sharing the same configured mode, does
not incur this delay. Video Mode's recording capability does not participate in this
reconfiguration - it is always available regardless of which mode the pipeline is currently
configured for, since it needs no Single-Shot/Burst-specific latency or resolution treatment.

## Burst Mode

When a trigger configured for Burst Mode (see "Capture Mode") fires, it initiates a sequence of
four image-capture requests spaced by a configurable target interval, rather than a separate
capture path. All four requests still flow through the one central capture operation described in
"Capture coordination"; Burst Mode issues that same operation four times in sequence instead of
once.

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

## Video Mode

When a trigger configured for Video Mode (see "Capture Mode") fires while no recording is in
progress, it starts recording video (with audio, when microphone permission is currently granted -
recording proceeds silently rather than blocking or prompting for permission if it is not) to the
same photograph storage location's video equivalent (see "Photograph storage").

While a recording is in progress, **any** trigger - regardless of that trigger's own configured
capture mode, and regardless of whether it's the same trigger that started the recording - stops
the active recording instead of starting a new capture of its own. For example, if Volume Up starts
a recording and the screen's top half is separately configured for Burst Mode, touching the top
half while the recording is active stops the recording; it does not also start a burst. Only once
the recording has stopped does every trigger return to behaving according to its own individually
configured mode again.

This "any trigger stops it" behavior is an extension of the same one-capture-operation-at-a-time
principle described in "Capture coordination" and "Burst Mode" (a capture command received while
another is already in progress does not start a second, overlapping one) - here, the in-progress
operation is a recording, and the arriving command's role changes from "rejected" to "stop this."

The device vibrates once when the recording starts, using the same configured vibration duration
and pulse as Burst Mode's start feedback (see "Burst Feedback"), and vibrates twice in quick
succession when the recording stops - a distinct pattern so starting and stopping a recording are
distinguishable by feel alone, without looking at the screen.

## Capture Performance

Burst Mode is intended to prioritize responsiveness over maximum image quality. During Burst Mode,
the implementation should favor the lowest practical capture latency, including capturing at a
reduced resolution relative to Single-Shot Mode's full sensor resolution, as long as the resulting
photographs remain clearly usable (not visibly degraded to the user viewing them at normal sizes -
on a phone screen, shared to messaging apps, or printed at typical small-print sizes). Single-Shot
Mode's captures are unaffected and remain full sensor resolution.

Single-Shot Mode should use the implementation's default capture behavior unless there is a demonstrated benefit to using a higher-quality capture mode.

## Burst Feedback

When a burst is successfully triggered, the device vibrates once, using the same configured
vibration duration as Single-Shot Mode's capture-success pulse (see "Settings").

This single vibration indicates only that the application accepted and started the burst request -
it does not indicate that all four images were successfully captured or saved. The application must
not vibrate separately for each image in the burst.

## Capture Progress Indicator

A standard progress control is shown while a capture is in progress, rendered above the active
cover photo (see "Cover photo visibility") - it must remain visible even while a cover photo is
covering the live preview, unlike the rest of the capture-status UI, which hides along with the
preview it's describing.

It has two visual modes, matching Capture Mode:

* **Single-Shot Mode**: an indeterminate spinner (in continuous motion, no specific completion
  fraction) appears the moment a single-shot capture begins, and disappears once that capture
  completes.
* **Burst Mode**: a determinate progress control appears the moment a burst is accepted, starting
  at 0%. It advances in four equal 25% steps as each of the burst's four images finishes, reaching
  100% once the fourth image is done, then disappears once the burst is completely finished.

**Video Mode** (see "Video Mode") reuses Single-Shot Mode's indeterminate spinner rather than a
third visual style: it appears the moment a recording starts and disappears once the recording
stops, for the whole duration of the recording regardless of how long that turns out to be.

Like the rest of the capture-status UI (see "Error Handling"), this indicator carries no
success/failure detail - it only reflects that a capture is in progress and, for Burst Mode, how
far along it is.

## Flash and Torch Restrictions

The camera flash and torch must not be used:

* While Burst Mode is active, or
* While a cover photo is visible (see "Cover photo visibility").

Whenever either condition applies, the application must ensure the flash and torch are disabled
regardless of whatever state they were previously in - actively turn them off if they were already
on, rather than merely skipping turning them on.

## Error Handling

Capture and file-saving errors must not be displayed on the main camera screen.

Instead, log errors to an application log file, in either JSON or plain-text format. Each logged
error should include, when available:

* Date and time
* Active capture mode (Single-Shot, Burst, or Video)
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
2. **Cover Photos** - lets the user build a list of up to three cover photos (see "Cover photo
   visibility"), each picked with the system Photo Picker (e.g.
   `ActivityResultContracts.PickVisualMedia`), which requires no runtime storage/media permission.
   * An **Add** control lets the user pick one more cover photo, which is appended to the end of
     the list. Once three cover photos are configured, the Add control is disabled or hidden - the
     list never exceeds three entries.
   * Each cover photo in the list has its own **Delete** control. Deleting a cover photo removes it
     immediately and shifts every cover photo after it up one position to fill the gap, so the list
     never has gaps and order is otherwise preserved.
   * If the deleted cover photo's position is at or before the current cover-photo index (see
     "Cover photo visibility"), the index is decremented by one so it keeps pointing at the same
     photo it pointed at before the deletion; if that would leave the index pointing past the new
     end of the list (for example, the last or only cover photo was deleted), the index is instead
     clamped to the new last valid position (0 if the list is now empty). The index is never left
     pointing outside the list's current bounds.
   * The list is empty by default (no cover photos configured), matching the pre-existing default
     of no overlay image.
   * The full list, and each entry's position in it, must be remembered across app restarts, not
     just for the lifetime of the current process, so the user is not required to re-pick cover
     photos every time the app is launched. Do not assume the picker's own returned `content://`
     Uri stays readable after a restart - on at least one real device/OS version tested, reopening
     the app after a restart and trying to load a Uri that had otherwise been correctly remembered
     failed with `SecurityException: ... does not have permission to access picker uri ...`,
     meaning the picker's read grant for that specific item did not survive. Copy each picked
     image's bytes into app-private storage at selection time (while the picker's grant is still
     valid) and persist a reference to that private copy instead, so the durability of the setting
     does not depend on the picker's grant lifetime at all.
3. **Capture mode, per trigger** - six independent controls (for example, six three-option segmented
   buttons, one per trigger), each choosing between Single-Shot Mode, Burst Mode, and Video Mode for
   one of the six capture triggers: screen tap top half, screen tap bottom half, shutter button,
   volume up, volume down, and voice command (see "Capture Mode").
4. **Burst interval** - a slider with discrete snap points every 250 ms from 250 ms to 2 seconds,
   defaulting to 500 ms (see "Burst Mode").
5. **Full screen** - a switch, off by default. When on, the Android system status bar and
   navigation bar are hidden (immersive mode) across the whole app - both the camera screen and
   this settings screen - rather than just the camera screen; the OS's standard edge swipe still
   temporarily reveals them without turning the setting off. Turning it off restores the bars
   immediately.

**Capture aspect ratio** and **Camera zoom** are configured directly on the camera screen instead of
here (see "UI requirements" above), as compact controls that sit at the bottom of the live preview
rather than a settings-screen row, since both are adjusted often enough while framing a shot that
requiring a trip to the settings screen would be disruptive:

* **Capture aspect ratio** - a control choosing between 4:3 (default) and 16:9 (see "Capture
  Aspect Ratio and Preview Framing"). Applies to the visible preview and to both Single-Shot and
  Burst capture; does not affect the cover photos' size or position at all (see "Overlay
  sizing"). Changing this setting while a burst is in progress must not alter the active burst -
  the new ratio takes effect only once that burst finishes. Applying a new ratio may briefly stop
  and rebind the affected camera use cases; no image may be captured during that rebind.
* **Camera zoom** - five discrete positions, 1x through 5x in integer steps, defaulting to 1x (no
  zoom). There is a single zoom level, not a per-trigger or per-mode setting the way capture mode
  is: it applies uniformly to the live preview and to Single-Shot, Burst, and Video Mode capture
  alike (see "Video Mode"). Unlike capture aspect ratio, changing this setting takes effect
  immediately on the live camera control without stopping, rebuilding, or rebinding any camera use
  case, so it incurs none of aspect ratio's rebind delay and needs no burst-in-progress deferral.

Both are still persisted exactly the same way as the settings-screen controls above (see below) -
moving their control off the settings screen changes nothing about how or where the values
themselves are stored.

Persist all of these settings (twelve distinct persisted values in total: the five settings-screen
controls above - once the six per-trigger capture modes are counted individually and the
cover-photo list is counted as one - plus capture aspect ratio and camera zoom configured on the
camera screen), and the overlay-visibility state and cover-photo index described above, across app restarts
(e.g. with Jetpack DataStore). Keep the settings screen testable the same way as the camera screen:
stateless composables driven by state and callbacks, with the actual persistence mechanism behind
an interface.

A setting, overlay-visibility write, or cover-photo-index write must be durably persisted even if
the screen that triggered it is torn down immediately afterward. If the settings screen has its own
ViewModel scoped to a navigation destination (e.g. a Navigation Compose back-stack entry), that
ViewModel - and any coroutine scope tied to its lifecycle, such as `viewModelScope` - will be
cancelled as soon as that destination is popped, which happens routinely right after picking a
value (add or delete a cover photo, see the list update, tap back). Do not perform the actual write
on a scope that can be cancelled this way; use a scope whose lifetime outlives the triggering
screen (e.g. an injected application-level coroutine scope) for the write itself, even if the
screen's own ViewModel scope is used for everything else.

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
* Settings persistence (defaults, reading back a saved value, and updates to each of the five settings)
* Aspect-ratio classification uses a tolerance rather than exact floating-point equality, and treats a dimension pair and its rotated (portrait/landscape) counterpart as the same ratio (see the worked examples in "Captured image metadata and validation")
* A capture-aspect-ratio change made while a burst is in progress does not alter that active burst; the new ratio only takes effect once the burst finishes
* Overlay-visibility persistence (defaults, reading back a saved value, and that it correctly reflects the last swipe-driven state) even though it is not presented on the settings screen
* Cover-photo-index persistence (defaults to 0, reading back a saved value, and that it correctly reflects the last swipe-cycled state) even though it is not presented on the settings screen
* A setting, overlay-visibility write, or cover-photo-index write is durably persisted even if the owning ViewModel is cleared immediately after the write is triggered (not just that the in-memory/observed state updates) - this is the specific failure mode a screen-scoped ViewModel's coroutine scope being cancelled by navigation would otherwise hide
* Adding a cover photo copies it into app-private storage (and appends a reference to that copy to the end of the list) rather than persisting the picker's own returned Uri directly - test this by asserting what gets persisted is not simply the source Uri unchanged, and that a failed copy leaves the previous list unchanged instead of persisting an unreadable reference
* The cover-photo list never grows past three entries (adding a fourth is rejected or the Add control is unavailable once three are configured)
* Deleting a cover photo shifts every later entry up one position to fill the gap, with no gaps left in the list
* Deleting a cover photo at or before the current cover-photo index decrements the index to keep pointing at the same photo, and clamps it to the new last valid position (0 if the list becomes empty) instead of leaving it out of bounds
* A left swipe from Camera Preview shows the cover photo at the current index, and has no effect when zero cover photos are configured
* A left swipe while Overlay View is already shown advances the cover-photo index to the next cover photo, wrapping from the last back to the first, only when more than one cover photo is configured; it has no effect with zero or one cover photo configured
* A right swipe while Overlay View is shown dismisses it without changing the cover-photo index
* Burst Mode issues exactly four capture requests per capture command, spaced by the configured interval, through the same central capture operation used by Single-Shot Mode
* A capture command received while a burst is already in progress does not start a second, overlapping burst
* A successfully triggered burst vibrates exactly once, regardless of how many of the four images ultimately succeed or fail, and Single-Shot Mode's own vibration behavior is unaffected
* An error on one image within a burst does not cancel the remaining requests in that burst unless continuing is technically impossible, and the burst's capture result reflects fewer than four successes without raising an on-screen error
* Logged error entries include the documented fields (date/time, active capture mode, burst image number and configured interval when applicable, output filename/destination, error type, error message, exception details) when available
* Flash/torch requests are suppressed (and any already-on flash/torch is turned off) while Burst Mode is active and while a cover photo is visible

Use fakes rather than mocks when practical.

Use coroutine test utilities and injected test dispatchers. Do not use real delays in tests.

### Compose UI tests

Write Compose tests verifying:

* Permission-denied content is displayed correctly
* Camera UI appears when permission is granted
* Touching the preview dispatches a screen-touch capture trigger
* A touch with some incidental pointer drift, below the app's own (deliberately widened) touch-slop threshold, still registers as a tap rather than being cancelled as a drag
* Pressing the visible shutter control dispatches a capture trigger
* Capture-in-progress state is represented
* A capture or file-saving error does not display any error text or detail on the main camera screen (see "Error Handling")
* A left swipe on the camera preview shows the active cover photo (Overlay View), and a right swipe on it hides it again (Camera Preview)
* With more than one cover photo configured, an additional left swipe while Overlay View is shown cycles to the next cover photo, wrapping from the last back to the first
* A mostly-vertical swipe while Overlay View is shown, whose horizontal component stays below the swipe threshold, does not cycle to the next cover photo
* The restored overlay-visibility state (from the last time the app was closed) is reflected correctly on launch
* No settings-screen control exists for enabling or disabling Overlay View directly
* The settings screen shows the configured cover photos (as thumbnails, in order), lets the user add one (up to three) and delete any of them, and the Add control is disabled or hidden once three are configured
* The settings screen reflects and updates each of its stored settings, including the capture-mode selector, the burst-interval slider, and the full-screen switch
* The camera screen's compact capture-aspect-ratio and camera-zoom controls reflect the current values, invoke their respective callbacks when changed, and are hidden while Overlay View is shown

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
* Manual test checklist for touch, voice, volume buttons, the app staying locked to portrait regardless of physical device rotation, permissions, image storage, cover-photo swipe gestures including cycling through multiple cover photos (smooth gesture-following animation is hard to unit test), Burst Mode (actual device timing between captures, flash/torch staying off, and the single burst-triggered vibration), and capture aspect ratio (letterboxing/pillarboxing at each ratio, the active cover photo staying full-screen regardless of the selected ratio, and preview framing matching the captured image)
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

# Developer Diagnostics

## Gesture Diagnostics

The application shall support a debug mode that records the complete processing path of every capture request.

The diagnostics shall make it possible to determine why a capture did or did not occur.

Detailed gesture diagnostics and visual debug overlays shall be disabled in Release builds. Normal operational and error logging may remain enabled.

### Gesture Processing

The touch-slop threshold used to distinguish a tap from a drag shall be deliberately larger than
the platform's own default, to tolerate the extra apparent pointer movement a wide or irregular
contact patch (for example, tapping with the side of a thumb rather than a fingertip) can report
during an otherwise-still press, without weakening swipe-vs-tap detection for an ordinary touch.

For every touch interaction, debug logging shall record:

- pointer down position
- pointer up position
- gesture duration
- horizontal movement
- vertical movement
- total movement distance
- touch-slop threshold
- swipe threshold
- final gesture classification

Possible classifications include:

- Tap
- Swipe Left
- Swipe Right
- Movement Below Swipe Threshold
- Cancelled

### Gesture Cancellation Diagnostics

The application shall record additional diagnostic information whenever a touch interaction does not result in a completed capture request.

For every cancelled or ignored gesture, debug logging shall include, when available:

- whether the pointer event had already been consumed by another component
- whether the gesture was cancelled before completion
- the reason for cancellation, if available
- the UI component or layer that received the event
- whether the privacy overlay was visible
- whether touch capture was enabled
- whether the camera was currently accepting capture requests

Possible cancellation reasons include:

- Pointer event consumed by another component
- Gesture cancelled
- Movement exceeded tap threshold
- Application state changed
- Camera temporarily unavailable
- Unknown

These diagnostics shall make it possible to determine why an apparent tap did not result in a capture request.

### Capture Request Processing

Every attempted capture, whether ultimately accepted or rejected, shall generate a unique Capture Attempt ID before validation begins.

The capture trigger source shall be logged as one of:
- Touch
- Voice
- Volume Button
- Shutter Button
- Other

The Capture Attempt ID shall be propagated through the complete capture pipeline.

Log Capture Events and Gesture Events.

If a capture request is rejected, the reason shall be logged.

Possible rejection reasons include:

- Camera not ready
- Camera rebinding
- Capture already running
- Burst already running
- ImageCapture unavailable
- Application inactive
- Unknown

### Gesture Events

- Gesture detected
- Gesture classified
- Gesture accepted
- Gesture cancelled

### Capture Events

- Capture requested
- Capture accepted
- Capture rejected
- CameraX request submitted
- CameraX capture started
- Image saved
- Capture completed
- CameraX error

### Camera Diagnostics

The application shall log:

- selected camera
- timestamp with millisecond resolution
- preview resolution
- capture resolution
- requested aspect ratio
- actual aspect ratio
- display rotation (the fixed, locked-portrait rotation the preview and viewport always use - see
  "Orientation changes")
- capture rotation (the `ImageCapture` use case's target rotation, which tracks the physical
  device's rotation - see "Orientation changes")
- capture mode
- burst number (if applicable)

### Diagnostic Correlation

Every log entry associated with a capture attempt shall include the same Capture Attempt ID.

This shall make it possible to reconstruct the complete path from user interaction through successful image save or failure.

### Diagnostic Persistence

Diagnostic logging shall be viewable through Logcat.

The implementation may additionally provide an option to save diagnostic logs to a file for post-analysis.

Log file generation shall be optional and disabled by default.

### Debug Overlay

Debug builds shall provide an optional developer-controlled diagnostic overlay.

- touch location
- gesture classification
- capture state
- current aspect ratio
- camera state
- Capture Attempt ID
- capture trigger source

Debug overlays shall never appear in Release builds.


