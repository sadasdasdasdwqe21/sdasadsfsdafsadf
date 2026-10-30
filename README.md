# Shake Finder

An Android app that measures a car's vibration with the phone's accelerometer and names the part that turns at that frequency: engine ½, 1st, 2nd or 4th order, propshaft, or tyres.

## Build it

1. Install Android Studio (any 2024 or newer version).
2. Unzip this folder and choose **File › Open**, then pick the `ShakeFinder` folder.
3. Let Gradle sync. It downloads Gradle 8.7, the Android plugin and SDK 34 on its own the first time.
4. Plug in your phone with USB debugging on and press **Run**, or use **Build › Build APK(s)** and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.

Command line instead: `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`), with `ANDROID_HOME` pointing at an Android SDK.

## Use it

1. Lay the phone flat on the seat rail or centre console, not on a cushion.
2. Type the engine speed you will hold, plus road speed (0 when parked in D or R).
3. Press **Start**, hold the engine steady under load for about 5 seconds, then press **Freeze**.
4. Read the verdict and the three strongest peaks. Repeat at a few rpm values: a true engine order moves in step with rpm.

The status line shows how many samples per second the phone delivers. Most phones give 400–500 per second, which covers everything up to about 180 Hz. If it shows a warning, results above the stated frequency are not reliable.

## Files

- `Analysis.java` – resampling, Welch-averaged FFT and peak finding (plain Java, no Android code)
- `Orders.java` – expected frequencies for each part, and match rules (±6%)
- `SpectrumView.java` – draws the spectrum with order lines
- `MainActivity.java` – sensor capture, inputs and results screen

Defaults are set for a Forester on 225/55R18 tyres. The final drive ratio defaults to 3.700 as a placeholder; enter the real figure for your car.
