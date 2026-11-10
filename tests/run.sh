#!/bin/sh
# Runs the logic tests on a PC. Needs only a JDK (17 or newer), no Android SDK.
# They check distances, typed coordinates, which project a position belongs to,
# file names and the stamp's lines of text.
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/app/sitestamp
OUT=$(mktemp -d)
javac -proc:none -d "$OUT" $SRC/Project.java $SRC/Geo.java $SRC/SiteMatcher.java \
    $SRC/Names.java $SRC/StampText.java tests/app/sitestamp/*.java
java -cp "$OUT" app.sitestamp.Tests
