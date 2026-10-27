# Agent guide for CircleOpenSearch

## Scope and working style

This file applies to the whole repository. Explicit user instructions take precedence.

- Keep source code, comments, UI strings, accessibility labels, and project documentation in English. Reply to the user in their preferred language.
- Read the current source before making changes. This guide records intended behavior and important implementation constraints, not a replacement for the implementation.
- Make focused changes and preserve user edits. Do not rewrite the UI, change the signing identity, introduce dependencies, or replace integrations as incidental cleanup.
- Use `rg` and `rg --files` for discovery. Build and test only as appropriate to the change. Documentation-only changes do not require an APK rebuild or device interaction.
- Explain what changed, how it was checked, and any remaining limitations. Distinguish a successful build, an on-device interaction check, and a successful online recognition result.
- Do not commit, push, publish a release, or change the connected phone's system settings unless the user's request authorizes it.

## Project and toolchain

CircleOpenSearch is a native Kotlin Android assistant. It uses Android framework Views and Canvas drawing, not Jetpack Compose or a web UI.

- Application ID and Kotlin namespace: `org.circleopensearch`.
- Minimum SDK: 29 (Android 10); compile and target SDK: 35.
- Java/JVM target: 17; Kotlin plugin: 2.0.0; Android Gradle Plugin: 8.10.1.
- The only application module is `:app`.
- Check `app/build.gradle.kts` for the current version. The offline builder also hardcodes `versionCode` and `versionName`; update both files together when changing the version.
- A Google app/Lens installation is needed for the intended native image search flow. External Google and Shazam behavior can change independently of this app.

## Product behavior to preserve

### Activation and screen capture

The app must be selected as the default digital assistant. It uses `VoiceInteractionService`, `VoiceInteractionSessionService`, and `VoiceInteractionSession` to receive one screenshot per activation.

Confirmed working entry points are the system assistant gesture/button and the Screen search Quick Settings tile. The tile launches the same assistant session and does not provide a standalone capture mode.

A home screen widget is not implemented. The user reports that adding a launch shortcut through Key Mapper or the launcher does not work. `MainActivity.addShortcut()`, the static shortcut XML, and `LaunchActivity` exist, but their presence is not evidence of a working user-facing shortcut integration. Do not advertise these routes as supported or mark them complete without implementing and verifying the actual add-and-launch flow. The component `org.circleopensearch/.LaunchActivity` and action `org.circleopensearch.SEARCH` are implementation details, not a confirmed Key Mapper integration.

Do not add recurring screenshot capture, AccessibilityService capture, or a MediaProjection prompt for ordinary search or the color picker. Do not collect or display AssistStructure screen text. Some devices still require both screen-context and screenshot settings enabled before delivering a screenshot.

Handle an early screenshot callback, duplicate delivery, unavailable/protected screenshots, timeout, and session close. Releasing a session must discard its bitmap reference and cancel pending work. Generation checks prevent old asynchronous results from reopening UI after close.

### Search and drawing

- The dock has Music on the left, Lens in the center, and Color Picker on the right. Controls use icons with English accessibility labels.
- A circle, scribble, underline, or tap computes a rectangular crop internally. Releasing the gesture starts preparing and sending that crop to Lens immediately. Do not reintroduce the old 180 ms debounce or a confirmation rectangle/corner frame.
- Tap Lens with no selection to send the entire snapshot. Long-press Lens opens the optional in-app web-results panel.
- Drawing uses a pure white stroke with no stroke glow or particles. The finger and screen edges have softly overlapping Gaussian color washes, smoothly fading to transparency.
- The glow palette uses Google blue, red, yellow, and green. The trail is time-based, currently 500 ms, not distance-based. Current tuning uses a 45 dp finger bloom radius and 160 dp edge depth. Preserve the soft falloff when adjusting these values.
- Screenshot pixels and the decorations are separate. Never bake drawing, glow, controls, or dimming into the uploaded image.
- Preserve coordinate mapping during zoom, pan, resize, and multi-touch. Historical touch samples contribute to both the path and crop bounds. Multi-touch must not accidentally launch a search.
- Native Lens may select a smaller region inside the supplied image. Do not promise control over its auto-selection. Full screenshot plus selection coordinates previously proved unreliable; keep the reliable rectangular-crop handoff unless a new supported integration is verified.

### Color picker

The picker samples the existing screenshot. It must show the whole image, including any system bars already captured in its pixels. Hide the live system status/navigation bars through immersive mode; do not crop those areas out of the bitmap and stretch the remainder.

Use original pixel coordinates for HEX values and the magnifier. The magnifier/HEX label copies `#RRGGBB`; coordinates can be copied separately. Close returns to search. No additional capture prompt or overlay permission is needed.

The optional `ScreenColorPicker/` directory was a reference checkout. It is ignored, is not built, and may be absent. The adapted views already live in `app/src/main/java/org/circleopensearch/colorpicker/`.

### Music recognition

The two audio sources are intentional and must remain distinct:

- Short press: internal playback via `AudioPlaybackCaptureConfiguration` and MediaProjection consent. Never silently fall back to the microphone when capture is blocked.
- Long press: `MediaRecorder.AudioSource.MIC`. With `RECORD_AUDIO` already granted, start directly without MediaProjection or a projection consent dialog. Otherwise request microphone permission only.

Both use the same recognizer and assistant sheet. Microphone text is "Listening to music around you"; device mode says "Listening to the music playing on your device". Foreground notification text and service type must reflect the selected source. Maintain the microphone/media-projection permissions and foreground service declarations in the manifest.

PCM is 16 kHz, mono, signed 16-bit. The service submits a six-second fingerprint while recording continues, then a twelve-second fingerprint if needed. Audio remains in memory; the recognizer uploads fingerprints, not PCM. Do not add API keys, subscriptions, account requirements, or an installed Shazam dependency. The endpoint is unofficial, so do not promise availability or recognition accuracy.

Recording must stop after an early match or the bounded capture interval. Timeouts and cancellation must remain effective. Idle service/projection state may remain while the panel is open, but no AudioRecord should keep recording while idle. Closing the panel, Back, the notification Stop action, or system termination must release recording and projection resources.

The music UI stays inside the assistant window over the live source app. Discard the screenshot when entering music mode. Do not open an opaque recognition activity or request audio focus, which can interrupt YouTube or other playback.

The music sheet is grey, flush with the side and bottom edges, with rounded top corners and a small handle. Dragging down dismisses it. There is no retry button or close X. Title and artist remain centered independently of their separate copy icons. Keep Open song and Search YouTube actions; build the YouTube URL with an encoded artist/title query.

## Source map

Paths below are under `app/src/main/java/org/circleopensearch/` unless otherwise stated.

| File | Responsibility |
| --- | --- |
| `EntryPoints.kt` | Assistant service, session service, launch activity, tile, and speech-service stub. Several manifest components live in this one file. |
| `MainActivity.kt` | Setup UI, assistant role, screen settings, working tile setup, and a shortcut-pinning attempt that the user reports does not work. |
| `AssistAccess.kt` | Screen-access settings and unavailable-capture messages. |
| `SearchSession.kt` | Session lifecycle, screenshot delivery, dock, crop dispatch, music/picker transitions, insets and immersive mode. |
| `SingleCapture.kt` | Platform-independent screenshot delivery state machine. |
| `SelectionView.kt` | Snapshot rendering, touch gestures, bitmap-space bounds and crop creation. |
| `SelectionFeedback.kt` | Soft edge/finger glow and timestamped touch trail. |
| `Ui.kt` | Shared colors, vector glyphs and icon controls. |
| `LensLauncher.kt` | Native Lens ACTION_SEND integration and handler selection. |
| `LensPanel.kt`, `LensPolicy.kt` | Optional WebView results and HTTPS host restrictions. |
| `CropProvider.kt` | Read-only content URIs for private cached PNG crops. |
| `ColorPickerOverlay.kt`, `PixelMapping.kt` | Full-screenshot picker layout, sampling and clipboard. |
| `colorpicker/TargetView.kt`, `colorpicker/MagnifierView.kt` | Adapted third-party drawing views. |
| `MusicPermissionActivity.kt` | Short-lived transparent permission relay; no standalone music screen. |
| `MusicCaptureService.kt` | Foreground capture, source selection, bounded recognition attempts and cleanup. |
| `MusicState.kt` | Shared in-process music state/listeners. |
| `MusicOverlay.kt` | Bottom sheet, waveform, swipe dismissal, copy and result actions. |
| `MusicFingerprint.kt`, `SongRecognizer.kt` | Spectral fingerprint generation and unofficial recognition request. |
| `app/src/main/AndroidManifest.xml` | Component visibility, processes, permissions and service types. |
| `app/src/main/assets/THIRD_PARTY_NOTICES.txt` | Copyright and license notices included in the APK. |

## Integration pitfalls

1. **Process boundaries:** the session service, music capture service, and permission activity run in `:session`. `MusicState` and active-service references are process-local. Moving one component into another process breaks this sharing unless actual IPC is added.
2. **Consent layering:** the assistant window is above the normal permission activity. During CONSENT, make the music content invisible and set its touchable region empty so the system dialog remains visible and tappable. Keep the assistant window attached. Calling `setUiEnabled(false/true)` caused an extra system window entrance animation before the sheet slide.
3. **Sheet entrance:** start the slide only after a measured, visible pre-draw frame following consent. Do not post an entrance animation from the constructor before layout is ready. Avoid mixing platform window animations with the sheet translation.
4. **System navigation:** transparent navigation needs both a transparent color and disabled contrast enforcement. Clear the translucent-navigation flag, which can override the requested background. Keep system insets distinct from captured screenshot pixels.
5. **URI sharing:** use a temporary read-only grant and ClipData for `ACTION_SEND image/png`. Keep `CropProvider` non-exported, validate filenames, and reject write access.
6. **Web uploads:** preserve `LensPolicy` host/scheme checks. Do not allow arbitrary result pages to receive the screenshot.
7. **Capture restrictions:** protected screenshots and blocked audio are normal platform limitations. Do not bypass them or substitute an unrequested capture source.
8. **Threading:** keep network/fingerprinting off the main thread. Publish UI state on the main thread, cancel network work on close, and reject results belonging to old attempts/sessions.

## Build and signing

Standard build with a configured Android SDK and dependency access:

```sh
./gradlew assembleDebug lintDebug
```

Some restricted environments cannot use Gradle daemon sockets or download dependencies. The supported local fallback is:

```sh
python3 scripts/build-local.py
```

It requires JDK 17+, Android SDK platform 35, build-tools 35.0.0, and the exact Kotlin compiler/dependency JARs expected by the script already in the Gradle cache. `ANDROID_HOME` overrides the SDK location; `CIRCLE_GRADLE_CACHE` overrides the Maven artifact cache root. Read the script for required versions. It compiles Kotlin/resources, packages and aligns the APK, signs it, and verifies its signature. It does not run Android lint.

Offline output: `app/build/local/circle-open-search-debug.apk`.

**Preserve the signing key.** The user accepts distributing the debug APK and keeping the existing signer. Do not switch to release signing, generate a replacement key, or rename packages without a request. The local builder creates `app/build/local/debug.keystore` only if absent; deleting build output can therefore change the signer and break updates. Avoid clean builds that delete this key unless it has been securely backed up. Do not overwrite the user's backup or place private keys/passwords in version control. Gradle's debug build may use a different keystore; do not assume the two build routes are interchangeable for installing updates.

The filename's `debug` suffix is cosmetic; `debuggable=true` and the signing certificate are separate APK properties. The Gradle project currently has no private release-signing configuration.

## Validation

Run the existing standalone checks when changing their covered logic:

```sh
python3 scripts/check-policy.py
```

These compile platform-independent Kotlin checks for:

- HTTPS Lens upload/browsing policy, including hostile URL cases.
- Screenshot state transitions and early/duplicate/timeout/closed delivery.
- Pixel mapping, clamping and HEX formatting.
- Fingerprint silence, deterministic landmarks, packet header and CRC.

They do not verify Android permissions, native Lens behavior, animations, or real recognition accuracy. Use a successful build to check Android compilation; use authorized device testing for platform behavior. Do not add tests that just repeat cosmetic constants or require live Google/Shazam servers for routine checks.

For UI work, inspect the relevant state on a real device when available and authorized: drawing while the finger is down, post-release Lens launch, picker fullscreen/coordinates, consent layering, sheet entrance/dismissal, and microphone versus device audio. A screenshot alone cannot validate animation timing. Verify cancellation and that recording actually stops, not just that its panel disappears.

## ADB workflow

Respect the user's current testing instructions. If they prohibit ADB or say a change needs no phone testing, do not interact with the device. Do not use ADB for documentation-only edits.

For authorized device work:

```sh
adb devices
adb -s SERIAL install -r app/build/local/circle-open-search-debug.apk
adb -s SERIAL shell am start -n org.circleopensearch/.MainActivity
adb -s SERIAL shell am start -n org.circleopensearch/.LaunchActivity
adb -s SERIAL shell uiautomator dump /sdcard/circle-ui.xml
adb -s SERIAL pull /sdcard/circle-ui.xml /tmp/circle-ui.xml
adb -s SERIAL shell screencap -p /sdcard/circle-ui.png
adb -s SERIAL pull /sdcard/circle-ui.png /tmp/circle-ui.png
```

Use the actual serial from `adb devices`; do not hardcode the previous phone. Wait for the expected UI before tapping coordinates. Immediately after an install, assistant service binding can lag; verify activation rather than letting a tap hit the underlying app. If the phone is locked, ask the user to unlock it rather than attempting to bypass authentication.

In a sandbox, execute ADB as a standalone shell command. Chaining host commands or redirecting ADB output can cause a different execution path where the daemon cannot access its socket. A quoted remote `adb shell '...'` command can still contain remote pipelines. Wait for a pending `uiautomator` dump before pulling its file. If the dump cannot reach an idle state, use screenshots and targeted dumpsys checks. `screenrecord` is not available on every firmware.

Keep diagnostic output focused; do not dump unrelated clipboard contents, device logs, account data, or media history. Prefer the app's setup page as a screenshot fixture. Restore any deliberately changed volume/playback settings and remove temporary test apps/files. Do not grant permanent projection app-ops or change global security/animation settings as a convenience workaround.

## Publication and attribution

- Keep `LICENSE` and third-party copyright/license notices. Adapted ScreenColorPicker and shazamio-core code require their notices to remain, regardless of whether the original reference checkout exists.
- Preserve the user's README wording, screenshot links, and presentation choices unless correcting them or changing behavior requires an update. Keep the README user-facing and put agent internals here.
- `.gitignore` excludes build outputs, APKs, keys, environment files, local agent/account directories, and `ScreenColorPicker/`. Keep Gradle Wrapper files, including the wrapper JAR, in source control. Root `AGENTS.md` is intended to be tracked.
- Never assume `.gitignore` removed already tracked files. Check staged changes and repository status before a user-authorized commit or push. In some environments `.git` is a restricted placeholder; report that limitation rather than claiming Git checks passed.
- Upload distributable APKs as release assets when publishing is requested; do not commit generated binaries or private signing material to the source tree.
- Do not present the project as an official Google or Shazam app. Do not promise the external services are open source or permanently available.
