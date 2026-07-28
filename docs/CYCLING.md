# Cycling features (Android)

This fork adds three cycling-oriented features to the Organic Maps Android app. All three live in
the Android layer only — **no C++ core, iOS, Qt or map-data changes** — so rebasing on upstream
touches a small, well-isolated set of files.

Everything is under `android/app/src/main/java/app/organicmaps/cycling/`.

| Package | What it does |
| --- | --- |
| `cycling/sensors/` | Bluetooth LE sensors: heart rate, speed, cadence, power |
| `cycling/media/` | Now-playing metadata and transport controls for any music app |
| `cycling/ui/`, `CyclingController` | The on-map readout and the glue into `MwmActivity` |
| `cycling/settings/` | Settings → Cycling |

---

## 1. Bluetooth sensors

### What is supported

Sensors are matched by **standard GATT service UUID**, not by brand, so any compliant sensor works:

| Service | UUID | Characteristic | Gives |
| --- | --- | --- | --- |
| Heart Rate | `0x180D` | `0x2A37` | bpm, contact status, RR intervals |
| Cycling Speed and Cadence | `0x1816` | `0x2A5B` | wheel + crank revolution counters |
| Cycling Power | `0x1818` | `0x2A63` | watts, optional wheel/crank counters |
| Battery | `0x180F` | `0x2A19` | charge percentage |

The two sensors named in the original request are covered by this:

- **CYCPLUS H2** (heart-rate armband) → Heart Rate service.
- **CYCPLUS C3** (speed/cadence) → CSC service; in dual mode it sets both flags in one packet, which
  is explicitly tested (`csc with both wheel and crank data`).

This is the same approach XOSS and every other bike-computer app takes. It also means a Wahoo,
Garmin, Magene or Polar sensor works with no code change.

### How a rate is derived

Sensors never report speed or cadence. They report *cumulative revolutions* plus *the time of the
last revolution*, and the app differentiates them. Three things make this less trivial than it
sounds, and each has a test in `RevolutionCounterTest`:

- **The counters wrap.** Event time is 16-bit; crank revolutions are 16-bit; wheel revolutions are
  32-bit. Differencing has to be modular or the readout spikes once per wrap.
- **The time resolution differs between profiles.** CSC uses 1/1024 s. The Cycling Power profile
  uses 1/1024 s for crank data but **1/2048 s for wheel data**. Using one constant for both makes a
  power meter report double speed.
- **A stopped wheel keeps transmitting.** The same counters are re-sent, so the last rate is held
  for 3 s and then decays to zero — otherwise the display freezes at the speed you had when you
  stopped at the lights.

Speed additionally needs the wheel circumference, set in Settings → Cycling (default 2105 mm,
700×25c).

### Connection handling

- `SensorLink` — one GATT connection. Android allows **one outstanding GATT operation at a time**,
  so subscriptions are pushed through a queue. Firing the descriptor writes back-to-back silently
  drops all but the first; that is the usual reason a dual-mode sensor only ever reports one metric.
- `SensorHub` — process-wide singleton that owns all links and merges their readings into one
  `SensorSnapshot`. When two sensors report the same metric (a power meter and a dedicated cadence
  sensor both send crank data) the dedicated sensor wins.
- `SensorService` — foreground service, type `connectedDevice`. Without it Android freezes the
  process a few minutes after the screen locks and tears the GATT connections down, which presents
  as a strap that "randomly disconnects".

### Permissions

| Android | Needed | Notes |
| --- | --- | --- |
| ≤ 11 (API 30) | `BLUETOOTH`, `BLUETOOTH_ADMIN` install-time + `ACCESS_FINE_LOCATION` runtime | The app already holds fine location for the map, so scanning usually prompts for nothing extra |
| 12+ (API 31) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` runtime | Declared `neverForLocation`, so scanning stays out of the location permission group |

New permissions were also added to the allowlist in `android/app/build.gradle`, which is enforced
against the built APK by `permission-checker.gradle`.

---


## 2. Music controls (any player)

`MediaControlHub`, surfaced by `map_buttons_music.xml` and wired by `ui/MusicButtons.kt`.

The UI is three buttons in the map's own control column, directly above zoom and styled identically.
Always visible: transport works through media key events even with no permission granted and no
recognised player, so there is no state in which they are useless.

Session selection treats every app equally — it takes whichever session is actually playing, and
otherwise the most recently active, deferring to the order `getActiveSessions` returns. An earlier
version ranked Spotify and TIDAL above everything else, which let a stale Spotify session beat the
Qobuz or YouTube Music track actually playing.

This deliberately does **not** integrate against Spotify's or TIDAL's own SDKs. Those need per-app
API keys, an account login inside this app, and network access — all at odds with a privacy-focused
offline map — and neither would help the person using a third player. The platform media session is
the common denominator: every compliant Android player publishes one.

Two capability levels, and the UI degrades between them:

| | Transport controls | Track / artist / artwork |
| --- | --- | --- |
| **Without notification access** | ✅ via `AudioManager.dispatchMediaKeyEvent` | ❌ |
| **With notification access** | ✅ targeted at a specific session | ✅ |

The rider is never forced to grant notification access just to skip a track.

`MediaNotificationListener` is an **empty** `NotificationListenerService`. It overrides nothing, so
every notification is ignored; declaring it is simply the only way to hold the access that
`MediaSessionManager.getActiveSessions` requires. The user enables it by hand from Settings →
Cycling.

No player is named anywhere in the code. `MusicApps` discovers installed players by querying
`CATEGORY_APP_MUSIC` through the manifest's `<queries>` block, so YouTube, YouTube Music, TIDAL,
Qobuz, Spotify, Poweramp and anything else are all equal citizens.

---

## Files changed outside `cycling/`

| File | Change |
| --- | --- |
| `MwmActivity.java` | Creates `CyclingController` and forwards `onStart` |
| `MwmApplication.java` | Creates the sensor notification channel |
| `SettingsPrefsFragment.java` | Opens the Cycling settings screen |
| `AndroidManifest.xml` | Bluetooth permissions, two services, `<queries>` for music apps |
| `app/build.gradle` | Permission allowlist |
| `res/layout/activity_map.xml` | Includes the sensor readout overlay |
| `res/xml/prefs_main.xml` | Cycling entry |

New resources are in files of their own (`values/cycling.xml`, `values/strings_cycling.xml`,
`values/attrs_cycling.xml`) so the fork's additions don't collide with upstream on rebase.

### A note on strings

English strings live in `res/values/strings_cycling.xml`, **not** in `res/values/strings.xml`. The
latter is generated from `data/strings/strings.txt` by the translation tooling, so hand-edits there
are lost on the next regeneration. Upstreaming any of this would mean moving the entries into
`strings.txt` and regenerating. As a consequence the cycling UI is English-only for now.

---

## Building and testing

To build:

```bash
cd android && ./gradlew assembleGoogleDebug -Parm64
```

Unit tests cover the parsing and rate-derivation logic, which is where the subtle bugs are and is
pure JVM code with no Android dependencies:

```bash
cd android && ./gradlew :app:testGoogleDebugUnitTest --tests 'app.organicmaps.cycling.*'
```

Kotlin style and static analysis:

```bash
cd android && ./gradlew :app:ktlintCheck :app:detektCheck
```

### What still needs a device

The parsers and counters are unit tested. These are not, and can only be checked on hardware:

- Actual GATT connect / subscribe / reconnect against a real sensor.
- Foreground-service survival across screen lock and a long ride.
- Media session selection with Spotify and TIDAL installed together.
