# LiveLingo

Real-time translated subtitles over any Android app. Play an Arabic video in Instagram, tap the
LiveLingo tile in the notification shade, and English subtitles appear over the video while the
person is speaking.

Native Android: Kotlin, Jetpack Compose, Material 3, coroutines and Flow. Built for the Samsung
Galaxy S25 Ultra (One UI, Android 14 to 17), runs on Android 10+.

---

## 1. Install

1. Open the latest **LiveLingo build** under *Releases* on this repository (or the run's
   artifacts in the *Actions* tab) and download `LiveLingo-debug.apk` on your phone.
2. Open the file and allow installing from your browser/file manager when Android asks.
3. Each new build installs over the previous one (all builds share one signing key).

## 2. Set up the speech engine (2 minutes)

LiveLingo uses **Soniox** real-time speech AI: one streaming connection does recognition,
automatic language identification and translation, which is what keeps delay low.

1. Create an account at <https://console.soniox.com> and add credit (real-time translation is
   about US$0.18 per hour of audio; silence is not sent, so actual use is lower).
2. Create an API key.
3. In LiveLingo: **Home → Settings → Speech & translation engine**.
   - *Simple (personal use):* choose **API key stored on this phone**, paste the key, **Save key**.
     The key is encrypted with the Android Keystore. It is never in the source code or APK.
   - *Recommended if anyone else uses the app:* deploy the token server below and choose
     **Token server**.
4. Tap **Test connection**. You should see "Connected".

### Token server (optional, keeps the real key off phones)

`backend/cloudflare-worker/` is a 50-line Cloudflare Worker (free tier is enough):

```bash
npm install -g wrangler
cd backend/cloudflare-worker
wrangler login
wrangler secret put SONIOX_API_KEY      # your Soniox key
wrangler secret put CLIENT_SECRET       # any long random string
wrangler deploy                          # prints https://livelingo-token.<you>.workers.dev
```

In LiveLingo choose **Token server**, enter the worker URL and the same client secret.
Each session the phone receives a single-use temporary key that expires in 60 seconds.

## 3. First run

On the Home screen, complete the *Finish setting up* checklist:

| Permission | Why |
|---|---|
| Display over other apps | Draws subtitles over Instagram, TikTok, YouTube… |
| Audio (microphone) | Android requires it for internal audio capture too |
| Notifications | Status with Pause / Resume / Stop / Settings |
| Unrestricted battery | Stops One UI from closing LiveLingo during long videos |

Then add the tile: swipe down twice → pencil (Edit) → drag **LiveLingo** into active tiles
(or tap **Add tile now** on Android 13+).

## 4. Everyday use

1. Open Instagram / TikTok / YouTube and start a video.
2. Pull down the shade and tap **LiveLingo**.
3. Android shows *"Start recording or casting with LiveLingo?"* → **Start now**.
   (Android 14+ requires this every session; see Limitations.)
4. Subtitles appear. Tap the tile again to **pause / resume**. **Stop** is in the notification.

The floating bubble (optional) opens a panel with pause, stop, target language, show/hide,
move/lock and audio source.

To move subtitles: bubble → **Move**, drag the box, pull the teal dot to resize, then **Lock**.

---

## Architecture

```
CaptureActivity ─(consent)─▶ TranslationForegroundService  (types: mediaProjection | microphone)
                                   │
   InternalAudioCapture ──┐        ├── SubtitleOverlayManager (TYPE_APPLICATION_OVERLAY)
   (AudioPlaybackCapture) │        │      SubtitleView · ControlBubble · ControlPanel
   MicrophoneCapture ─────┤        │
                          ▼        ▼
            AudioBufferManager → VAD gate → SpeechRecognitionProvider ──▶ CaptionAssembler ─▶ SessionStore
            (bounded, drops oldest)          ├ SonioxSpeechProvider (WebSocket, STT+LID+translation)      │
                                             └ OnDeviceSpeechProvider (Android 13+, offline)              ├▶ overlay
                                                       └▶ TranslationManager + MlKitTranslationProvider   ├▶ notification
                                                                                                          └▶ QS tile, UI
```

| Package | Contents |
|---|---|
| `audio` | `AudioCaptureProvider`, `InternalAudioCapture`, `MicrophoneCapture`, `AudioBufferManager`, `VoiceActivityDetector`, `Resampler`, `PreRollBuffer` |
| `speech` | `SpeechRecognitionProvider`, `SonioxSpeechProvider`, `SonioxProtocol`, `OnDeviceSpeechProvider` |
| `translation` | `TranslationProvider`, `MlKitTranslationProvider`, `TranslationManager` |
| `pipeline` | `TranslationPipeline` (orchestration, reconnects, latency), `CaptionAssembler` |
| `overlay` | `SubtitleOverlayManager`, `SubtitleView`, `ControlBubble`/`ControlPanel`, `OverlayLifecycleOwner` |
| `service` | `TranslationForegroundService`, `TranslationNotificationManager`, `TranslationQuickSettingsTile`, `CaptureActivity`, `ServiceController` |
| `settings` | Preference groups (language, subtitle, audio, engine), `SettingsRepository` (DataStore), `SecureStore` (Keystore) |
| `history` | Room database, `TranslationHistoryRepository`, transcript export |
| `network` | `ApiClient` (OkHttp), `NetworkMonitor`, `CredentialProvider` |
| `ui` | Compose screens: Home, Languages, Subtitles, History, Audio, Engine, Diagnostics |

Dependency injection is constructor-based through `AppContainer` (no code-generation framework).
New speech engines implement `SpeechRecognitionProvider` and are registered in
`AppContainer.createSpeechProvider`; new languages are one line in `core/Languages.kt`.

### Latency design

- 100 ms PCM chunks over one persistent WebSocket (never one request per fragment).
- Voice activity gate with 400 ms pre-roll; after a pause the engine is told to finalise
  immediately instead of waiting for its own timeout.
- Soniox translates mid-sentence; provisional words are shown dimmer and replaced, finished
  sentences are never rewritten.
- Bounded 4-second queue that drops the oldest audio; after a reconnect, audio older than
  1.5 s is skipped so subtitles catch up instead of lagging.
- **Diagnostics** shows real measured latency (capture timestamp → text arrival), translation lag,
  buffer, drops and reconnects.

## Build from source

```bash
./gradlew testDebugUnitTest assembleDebug
```

JDK 17, Android SDK 34. CI (`.github/workflows/build-apk.yml`) runs unit tests, builds the debug
APK, compiles the UI tests and publishes the APK as a release download. A signed release APK is
produced when these repository secrets exist: `LIVELINGO_RELEASE_KEYSTORE_B64`,
`LIVELINGO_RELEASE_STORE_PASSWORD`, `LIVELINGO_RELEASE_KEY_ALIAS`, `LIVELINGO_RELEASE_KEY_PASSWORD`.

UI tests run on a connected phone: `./gradlew connectedDebugAndroidTest`.

See `docs/FEASIBILITY.md` for the Android platform analysis, compatibility matrix, device test
plan and known limitations.
