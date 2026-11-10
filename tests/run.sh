#!/bin/sh
# Runs the analysis tests on a PC. Needs only a JDK (17 or newer), no Android SDK.
# They simulate a phone's accelerometer and microphone and check the rpm reading,
# amplitudes, order matching and OBD2 answer decoding.
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/app/shakefinder
OUT=$(mktemp -d)
javac -proc:none -d "$OUT" $SRC/Analysis.java $SRC/RpmTracker.java $SRC/Orders.java $SRC/SpeedFilter.java \
    $SRC/TimeSeries.java $SRC/Elm327.java $SRC/SoundRpm.java tests/app/shakefinder/*.java
java -cp "$OUT" app.shakefinder.Tests
java -cp "$OUT" app.shakefinder.Tests2
