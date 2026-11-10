# SiteStamp

An Android camera for site photos and videos. Every picture gets a stamp with the date, time, GPS position and address. Save your project sites once, and the app writes the right project name on the stamp by itself whenever the phone is on that site.

## Get it

Every push to the `site-camera` branch builds an APK and publishes it under **Releases** as `SiteStamp.apk`. Download it on the phone and open it. All builds are signed with the same key, so a new one installs over the old one. Needs Android 10 or newer.

To build it yourself: open the folder in Android Studio and press **Run**, or run `./gradlew assembleDebug` with `ANDROID_HOME` pointing at an Android SDK.

## Use it

**Save your sites (once).** Tap the pin icon. At a site, press **Add the site I am standing on**, type the project name, and save. Or press **Add a site by its coordinates** and paste a position from a map. Each site is a centre point and a circle (150 m unless you change it). A site can also carry a line above the name (company or contractor) and a line below it (town or area).

**Shoot.** The pill at the top of the picture shows what GPS found: the project you are on, or the nearest one and how far it is. On a site, the project name is on the stamp. Away from every site, the stamp has the date, time, coordinates and address only.

Tap the pill to choose the project by hand for now, for example indoors where GPS is weak. That choice lasts until the app is closed, then it goes back to automatic.

**Photo and video.** Switch with PHOTO / VIDEO under the picture. In video the stamp is part of every frame and its clock runs. Volume keys work as the shutter. Pinch to zoom, tap to focus. The top icons are flash, photo timer and grid.

**Where pictures go.** `DCIM/SiteStamp/<project name>/`, one folder per project, with file names like `ZONAL OFFICE_20261110_150322.jpg`. Pictures taken away from every site go in `Other`. Each photo also carries its position, time and project name in its hidden details (EXIF).

**Settings** (sliders icon), with a sample picture at the top that changes as you choose:

- Position: nine places (each corner, the middle of each side, and the centre), plus how far the stamp sits from the edge.
- Text size: a slider from 40% to 300% in steps of 5%. If the text would not fit the picture, it is shrunk to fit.
- Text colour, and the box behind the text: none, dark or light.
- Which lines the stamp shows, date and coordinate style.
- Photo size and video quality.
- Theme: dark, light, or the same as the phone.

## How it finds the project

`SiteMatcher` measures the distance from the phone to each saved centre point. Inside a circle means on that site. If two circles overlap, the closer centre wins. Once on a site, the name is kept until the phone is clearly outside (the radius plus 20%, at least 25 m), so it does not flicker at the edge. This uses the coordinates only and works with no internet. Only the street address needs internet.

## The code

- `Geo`, `SiteMatcher`, `StampText`, `Names`, `Project` – plain Java: distances, typed coordinates, which site, the stamp's lines, file names. `tests/run.sh` checks them on a PC with only a JDK.
- `StampRenderer` – draws the stamp. The same code draws it on the preview, into a photo and into each video frame.
- `CameraRig` – the camera (CameraX). Photos are stamped after capture at full size. Video uses an overlay drawn into the camera stream before the recorder.
- `PhotoSaver` – turns the photo upright, stamps it, saves it to the gallery with EXIF.
- `LocationTracker` – GPS and network position, and the address lookup.
- `MainActivity`, `ProjectsActivity`, `SettingsActivity` and the small view classes – the screens.
