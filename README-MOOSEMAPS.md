# Moose Maps

An unofficial, cycling-focused fork of [Organic Maps](https://organicmaps.app/) for Android.

**Not affiliated with, endorsed by, or supported by the Organic Maps project.** Please do not report
problems with this build to them. Organic Maps is licensed under Apache-2.0, which covers their
code — this fork uses its own name, application id and launcher icon so the two apps are distinct
and can be installed side by side.

Everything Organic Maps does — offline OpenStreetMap data, no ads, no tracking, no account — plus
three additions for riding.

## Download

Grab the latest `.apk` from the [Releases page](../../releases) and open it on your phone.

The APK is **debug-signed**, so Android will warn that it comes from an unknown developer and ask
you to allow installs from your browser or file manager. That warning is expected for an app
distributed outside a store. If that is not a trade-off you want, build it yourself — see below.

Requires Android 5.0 (API 21) or newer, on a 64-bit ARM device (any phone from roughly 2017 on).

## What this fork adds

### Bluetooth sensors

Connects to heart-rate, speed, cadence and power sensors over Bluetooth LE.

Sensors are recognised by their standard GATT profile rather than by brand, so a CYCPLUS H2 or C3
works, and so does a Wahoo, Garmin, Magene, Polar or anything else that follows the spec. Readings
appear on the map and on the lock-screen notification.

Pair them under **Settings → Cycling**. If you use a wheel speed sensor, set your wheel
circumference there too, or the speed will be wrong.

### Music controls

Play, pause and skip from three buttons in the map's control column, just above zoom. They work with\nany player - YouTube, YouTube Music, TIDAL, Qobuz, Spotify, Poweramp - because control goes through\nAndroid's media session rather than any one app's SDK. No account, no API key, no network access.

This works with **any** Android music player — Spotify, TIDAL, and the rest — because it goes
through the platform's media session rather than any one app's SDK. That also means no account
login, no API keys and no network access were added to the app.

Transport controls need no permission at all. Seeing track names and artwork needs notification
access, which you grant once under **Settings → Cycling**; skipping tracks works fine without it.

## Building it yourself

You need the Android SDK (platform 36, build-tools 36.x), NDK 29+, CMake 3.22.1+ and JDK 17+.

```bash
git clone --recurse-submodules https://github.com/MelatoninFein/Moose-Maps.git
cd Moose-Maps && bash ./configure.sh
cd android && ./gradlew assembleGoogleDebug -Parm64
```

On Windows, clone with `-c core.symlinks=true` and Developer Mode enabled. The repository uses
symlinks for shared assets and localisations; without symlink support they check out as text files
containing a path, and the resource merger fails with `Content is not allowed in prolog`.

Tests for the cycling logic:

```bash
cd android && ./gradlew :app:testGoogleDebugUnitTest --tests 'app.organicmaps.cycling.*'
```

## How it is put together

All three features live in `android/app/src/main/java/app/organicmaps/cycling/` and touch only the
Android layer — no changes to the C++ core, iOS, the Qt desktop app, or the map data. That keeps
rebasing onto upstream Organic Maps cheap.

[`docs/CYCLING.md`](docs/CYCLING.md) covers the design in detail: which GATT profiles are used and
why revolution counters are harder to differentiate than they look, how picture-in-picture is
wired, and why the music integration deliberately avoids the Spotify and TIDAL SDKs.

## Known gaps

- The cycling UI is **English only**. Its strings sit outside the upstream translation pipeline;
  see the note in `docs/CYCLING.md`.
- The splash screen and notification icon still use Organic Maps' logo. The launcher icon has been
  replaced; these have not.
- On Android 5–7 (API 21–25) the launcher falls back to the old icon, because the replacement is an
  adaptive icon and those versions predate the format.
- Sensor connection behaviour, picture-in-picture and media-session selection have been verified by
  build and unit tests, but not yet on a physical device with real sensors.

## Licence

Apache-2.0, inherited from Organic Maps. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Map data is
© OpenStreetMap contributors, ODbL.
