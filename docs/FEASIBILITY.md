# LiveLingo: feasibility, decisions and status

## 1. Can this work on modern Android? Yes, with three platform rules

| # | Platform rule | What it means for LiveLingo | How the app handles it |
|---|---|---|---|
| 1 | **Capture consent every session (Android 14+).** A `MediaProjection` token is single-use and the user must approve each capture session. | One tap on the tile cannot silently start capture. Android shows *"Start recording or casting?"* over the current app. | The tile opens an invisible `CaptureActivity` that shows the dialog over Instagram/TikTok; one more tap ("Start now") and subtitles start. **Pause/resume keeps the same session**, so it never asks again until you Stop. |
| 2 | **Foreground-service ordering (Android 14+).** A service of type `mediaProjection` may only enter the foreground after consent; a `microphone` service can't be started from the background. A `TileService` may only launch activities through `startActivityAndCollapse(PendingIntent)`. | The service can't be started directly from the tile. | Tile → `CaptureActivity` (foreground, so allowed) → consent → `startForegroundService` with types `mediaProjection|microphone`. Including `microphone` from the start lets the app fall back to the mic later while in the background. |
| 3 | **Apps decide whether their audio can be captured.** `AudioPlaybackCapture` only copies `USAGE_MEDIA`, `USAGE_GAME`, `USAGE_UNKNOWN`, and only from apps whose capture policy is `ALLOW_CAPTURE_BY_ALL` (the default for apps targeting Android 10+). Call audio is never capturable. | Most video apps should work; a DRM or opted-out app will deliver silence. | `InternalAudioCapture` detects *digital silence while media is playing*, reads the active players' capture policy, tells the user exactly why, and (by default) switches to the microphone automatically. No bypass is attempted. |

### Other architectural problems found before implementation

- **Overlay tap-through (Android 12+).** Touches only pass through another app's overlay if that
  window's opacity is ≤ `InputManager.getMaximumObscuringOpacityForTouch()` (0.8). A fully opaque,
  non-touchable subtitle window would block taps on the video where the text sits.
  → Locked subtitles are drawn with window alpha capped at that limit (default on, can be turned off).
- **Echo cancellation defeats the microphone fallback.** AEC subtracts the phone's own speaker
  output from the mic signal, which is the video audio we need. → Mic mode uses
  `VOICE_RECOGNITION` with optional noise suppression and AGC, and never enables AEC.
- **Compose has no lifecycle inside a Service window.** → `OverlayLifecycleOwner` supplies
  lifecycle, saved-state and view-model owners for the bubble and panel.
- **Latency piles up after network stalls.** → bounded 4 s queue that drops the oldest audio,
  stale audio (> 1.5 s) skipped after reconnect, stall detection on the socket's send queue.
- **The consent dialog briefly takes focus**, so some apps pause playback when it appears. Tap
  play once after "Start now". Pause/resume through the tile does not show the dialog.
- **Samsung battery management** may kill long-running services. → session flag detects an
  unexpected kill on next launch and points to the Unrestricted battery setting.

### Speech/translation engine choice

| Option | Streaming STT | Auto language ID | Translation | Phone-to-provider without an audio proxy | Decision |
|---|---|---|---|---|---|
| **Soniox real-time** | Yes (WebSocket) | Yes, per token, mid-stream switching | Built in, mid-sentence, any pair of 60+ languages | Yes (temporary keys) | **Default** |
| Google Cloud STT v2 + Translation | Yes (gRPC) | Limited in streaming | Separate API call per segment | No (service-account keys need a server; audio would be proxied) | Not chosen: more latency and a server in the audio path |
| OpenAI Realtime transcription | Yes | Yes | Separate step | Yes (ephemeral tokens) | Not chosen: the transcription model line-up is being replaced (several models shut down Feb 2027) |
| Android on-device recognizer + ML Kit | Yes (Android 13+, via `EXTRA_AUDIO_SOURCE`) | No | ML Kit offline | Fully offline | **Implemented as experimental offline engine** |

Engines sit behind `SpeechRecognitionProvider` and `TranslationProvider`, so others can be added.

## 2. Compatibility matrix

| Feature | Android 10 | 11–12 | 13 | 14–17 (S25 Ultra) |
|---|---|---|---|---|
| Internal audio capture | ✓ | ✓ | ✓ | ✓ (consent each session) |
| Microphone capture | ✓ | ✓ | ✓ | ✓ |
| Floating subtitles | ✓ | ✓ (tap-through needs ≤ 80 % opacity on 12+) | ✓ | ✓ |
| Quick Settings tile | ✓ | ✓ | ✓ + "Add tile" prompt | ✓ |
| Notification controls | ✓ | ✓ | ✓ (needs notification permission) | ✓ |
| Cloud engine (Soniox) | ✓ | ✓ | ✓ | ✓ |
| Offline speech (experimental) | ✗ | ✗ | ✓ if the phone offers on-device recognition | ✓ |
| Offline translation (ML Kit) | ✓ | ✓ | ✓ | ✓ |

APKs are built for 64-bit ARM (`arm64-v8a`), which covers the S25 Ultra and nearly all Android 10+ phones.

## 3. Phase report

| Phase | Implemented | Verified so far | Remaining |
|---|---|---|---|
| 1 Foundation | Kotlin/Compose project, Gradle, manifest, theme, navigation, DataStore settings, Keystore secrets, manual DI | Compiles in CI | — |
| 2 Audio | Playback capture, mic fallback, resampling, VAD, pre-roll, bounded buffer, blocked-capture detection, headphone detection | Unit tests (VAD, resampler, buffer, pre-roll, PCM encoding) | On-device capture tests 1, 8, 11 |
| 3 Speech | Soniox WebSocket provider, protocol parser, error mapping, reconnect/backoff, on-device provider | Unit tests (config, token parsing, errors) | Real-key session test, dialect test 12 |
| 4 Translation | Inline Soniox translation, ML Kit provider, `TranslationManager`, language selection, favourites/recents | Unit tests (caption assembly, history pairing) | Tests 2, 3, 6 on device |
| 5 Overlay | Subtitle window, drag/resize/lock, presets and full styling, two display modes, bubble and panel | Compiles; UI test compiled | Tests 4, 5 on device |
| 6 System | Foreground service, consent flow, QS tile states, notification actions, battery-kill detection | Compiles | Test 7 on device |
| 7 Testing | 34 unit tests run in CI; 2 Compose UI tests compiled | All unit tests pass | Device tests below |
| 8 Delivery | Debug APK + compact APK published per build; signed release when secrets exist | APK produced by CI | Install on S25 Ultra |

## 4. Device test plan (needs the phone)

These cannot be run from the build environment. Record results here after testing.

| # | Scenario | Expected | Result |
|---|---|---|---|
| 1 | Instagram, Arabic video, tile → Start now | English subtitles over the video within ~1–2 s | _pending_ |
| 2 | TikTok, Spanish video, auto-detect | "Hearing Spanish" pill, English subtitles | _pending_ |
| 3 | YouTube, French video, 5 min | Continuous subtitles, no drift | _pending_ |
| 4 | Switch Instagram ↔ WhatsApp while active | Overlay stays, keeps translating Instagram audio | _pending_ |
| 5 | Bubble → Move, drag and resize while running | Box follows finger, position persists | _pending_ |
| 6 | Bubble → Swahili while running | Next sentence in Swahili, capture not restarted | _pending_ |
| 7 | Pause/resume from tile and notification | No consent dialog, state shown on tile | _pending_ |
| 8 | Play a capture-blocking app (e.g. a DRM video app) | Message + automatic switch to microphone | _pending_ |
| 9 | Airplane mode for 20 s mid-video | "No internet… resumes automatically", recovers without restart | _pending_ |
| 10 | 30 min session | Diagnostics: stable latency, 0 growth in dropped chunks, battery % recorded | _pending_ |
| 11 | Bluetooth headphones | Internal audio keeps working; mic mode warns | _pending_ |
| 12 | Egyptian, Gulf, Levantine clips, fast and slow speech | Recognised with auto-detect; note accuracy | _pending_ |

## 5. Known limitations

- Android 14+ shows the capture dialog at every new session (platform rule, cannot be bypassed).
- Apps that block capture, DRM video and call audio cannot be captured internally; the microphone
  fallback works but is less accurate and does not hear audio in headphones.
- Locked subtitles are drawn at 80 % opacity so taps can pass through (Android 12+ rule); turn off
  Tap-through mode for solid subtitles that block taps on the text area.
- The cloud engine needs internet and a paid Soniox account (about US$0.18/hour of streamed speech).
- The offline engine needs a fixed source language and its downloaded model; quality depends on
  the phone's built-in recognizer.
- Raw audio is never stored. Only text history is kept, and it can be turned off or cleared.
