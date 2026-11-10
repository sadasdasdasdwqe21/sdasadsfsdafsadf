# Shake Finder

An Android app that measures a car's vibration with the phone's accelerometer and names the part that turns at that frequency: engine ½, 1st, 2nd or 4th order, propshaft, or tyres.

Version 2 adds GPS road speed, automatic engine-speed tracking, an engine sound analyser, a full OBD2 tab, recording, saved readings and an animated, touchable interface.

## Get it

Every push to the `shake-finder` branch builds an APK and publishes it under **Releases**. Download `ShakeFinder.apk` on the phone and open it. All builds are signed with the same key, so a new one installs over the old one.

To build it yourself: open the folder in Android Studio and press **Run**, or run `./gradlew assembleDebug` with `ANDROID_HOME` pointing at an Android SDK.

## Use it

**Engine tab**

1. Lay the phone flat on the seat rail or centre console, not on a cushion.
2. Press **Start** and hold the engine steady, under load if that's when it shakes.
3. Read the verdict. Tap any peak in the list or on the chart to see what that one is. Drag across the chart to read any frequency.
4. Press **Freeze** to keep a reading.

The two dials show engine speed and road speed, and say where each number comes from.

- **Engine speed** is measured from the vibration itself. If two readings are possible (for example 786 and 1572 rpm) the app shows "Tach shows ≈ …?" buttons. Tap the one that matches the tachometer and the app locks to it.
- **Road speed** comes from GPS. Tap the dial to switch between GPS and a typed speed.

**Engine sound** (Setup › Use engine sound too). The microphone backs up the rpm reading and adds a sound chart next to the vibration chart. Tones are matched to engine speed and road speed the same way. Keep the fan and radio off. Nothing is recorded or sent anywhere.

**Recording.** Press **● Rec** on the Engine tab to record a test run. Four times a second it logs engine speed, road speed, the strongest peak, the strength of each engine order, the sound level and every OBD value to a CSV file. If the microphone is on, the engine sound is saved as a WAV file (up to five minutes). While it records, the **Shake against rpm** chart fills in: rev slowly through the range and the rpm where the shake is worst is marked. Press Rec again, or Stop, to finish.

**Save this reading**, under any result, keeps that reading with a note.

**Saved tab.** Every saved reading and recorded run. Open one to see its chart again, compare two readings (for example before and after a repair), play back the engine sound, share the files, or delete them. Files live in the app's own folder and go when the app is uninstalled.

**Tap test tab.** Engine off, exhaust cold. Tape the phone to a part, press Start, tap the part 3–4 times, press Freeze. The app shows the frequency the part rings at and the rpm where the engine would set it ringing.

**OBD2 tab.** Needs an ELM327 adapter with classic Bluetooth, paired in Android's Bluetooth settings first.

- Exact engine speed and road speed, fed into the shake analysis
- Live data tiles with small graphs for every value the car supports; tap a tile for its lowest and highest
- Boost pressure and live fuel economy, worked out from the raw values
- Stored, pending and permanent fault codes with descriptions, and a clear-codes button
- Self-test readiness and the freeze frame
- Adapter version, protocol and battery voltage

## How it works

- `Analysis.java` – Welch-averaged spectrum, exact Hann-window peak estimates, noise floor, up-down share, dropped-sample repair
- `RpmTracker.java` – finds the engine's harmonic series, scores which octave it is, follows it, and accepts a lock from the driver or backup from a second source
- `SoundRpm.java` – microphone stream to a 0–700 Hz sound spectrum and its tones
- `Orders.java` – expected frequency of each part and the match rules
- `Elm327.java` – decodes OBD2 answers: live values, fault codes, readiness, freeze frame
- `SpeedFilter.java`, `TimeSeries.java` – recent GPS and OBD readings
- `Recorder.java`, `SweepMap.java`, `WavWriter.java` – the run log, the shake-against-rpm map and the sound file
- `ObdLink.java`, `SoundCapture.java` – Bluetooth and microphone threads
- `Store.java`, `ShareProvider.java` – saved files and sharing them
- `MainActivity.java`, `ObdPage.java`, `SavedPage.java` and the `*View.java` files – the screens

The analysis, decoding and recording classes are plain Java. `tests/run.sh` checks them on a PC against a simulated phone (needs only a JDK).

Defaults are set for a Forester on 225/55R18 tyres. Tyre speed uses the rolling circumference (3% under the unloaded size). The final drive ratio defaults to 3.700 as a placeholder; enter the real figure for your car.
