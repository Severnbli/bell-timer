# Bell timer – programmable bell schedule on ESP32

Bell timer is a standalone bell controller. The ESP32 keeps a bell
schedule in flash, switches a relay at the configured times and creates its own
Wi-Fi access point. An Android app joins that access point and controls the
device over HTTP. No router, internet connection or NTP server is required.

## Repository layout

```
firmware/BellTimer/BellTimer.ino     ESP32 firmware (single sketch)
android/                             Android Studio project (Kotlin + Compose)
scripts/gen-config.sh                .env -> firmware/BellTimer/app_config.h
scripts/build-firmware.sh            arduino-cli build helper
scripts/flash-firmware.sh            build, upload, then reset app_config.h
scripts/api-test.sh                  curl test for every HTTP endpoint
flash-firmware.sh                    convenience wrapper for the flash script
docs/firmware-api-v4.md              schedule/API contract (v4 packs)
CHANGELOG.md                         release notes
.env.example                         template for the firmware configuration
README.md
```

## Hardware

| Function | ESP32 pin | Notes |
| --- | --- | --- |
| DS3231 SDA | GPIO21 | I2C, address `0x68`, RTC stores **local** time |
| DS3231 SCL | GPIO22 | I2C |
| Relay IN | GPIO26 | 5 V relay module with optocoupler, **active LOW** |
| Status LED green | GPIO27 | KY-029 `G` pin, active HIGH |
| Status LED red | GPIO25 | KY-029 `R` pin, active HIGH |
| KY-029 common | GND | Tied to GND = common cathode (`LED_ACTIVE_LOW=false` in `.env`) |

The relay is forced OFF at boot: GPIO26 is driven HIGH before it becomes an
output. There are no buttons or switches. Power the board from USB 5 V.

The DS3231 must have a working backup battery (usually CR2032). Without it the
oscillator-stop flag is set after every power loss and the device reports
`time_not_set` until the time is set from the app.

## Toolchain used and verified

Firmware:

- `arduino-cli` 1.5.1
- Arduino-ESP32 core **3.3.12**
- FQBN `esp32:esp32:esp32` (ESP32 Dev Module, 4 MB flash, default partition)
- RTClib 2.1.4 (pulls Adafruit BusIO 1.17.4)
- ArduinoJson 7.4.3
- `WebServer`, `LittleFS` and `Wire` are bundled with the ESP32 core

Android:

- JDK 17
- Gradle 9.6.0 (wrapper included)
- Android Gradle plugin 9.4.1
- Kotlin 2.4.21 and the Compose compiler plugin 2.4.21
- Compose BOM 2026.09.00, Material 3
- `compileSdk` / `targetSdk` 37, `minSdk` 26
- OkHttp 4.12.0, kotlinx.serialization 1.9.0, coroutines 1.10.2

## System configuration

Install the following once on the build machine:

```bash
# ESP32 toolchain
arduino-cli core install esp32:esp32
arduino-cli lib install "RTClib"
arduino-cli lib install "ArduinoJson"

# Android SDK (command-line tools, no Android Studio required)
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
yes | sdkmanager --licenses
sdkmanager --list_installed
```

Requirements:

- JDK 17 (`JAVA_HOME` must point to it). The project is verified with OpenJDK
  17.0.20; AGP 9.4 requires JDK 17.
- `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) must point to the SDK that has platform
  37 installed. AGP 9.4 also accepts build-tools 36.0.0 and installs it
  automatically if missing.
- No `local.properties` is needed when `ANDROID_HOME` is set. If your SDK is in
  a custom location, create `android/local.properties` with
  `sdk.dir=/absolute/path/to/Sdk` (the file is git-ignored).

## Build the firmware

Wi-Fi credentials, the API token and the GPIO wiring live in `.env`. Create it
once from the template and adjust it to your device, then build:

```bash
cp .env.example .env    # edit AP_SSID, AP_PASSWORD, API_TOKEN, pins, ...
scripts/build-firmware.sh
```

The build script runs `scripts/gen-config.sh`, which renders `.env` into
`firmware/BellTimer/app_config.h`, before calling `arduino-cli`. Both files are
git-ignored, so the header does not exist in a fresh clone; run the generator
once before invoking `arduino-cli compile --fqbn esp32:esp32:esp32
firmware/BellTimer` directly:

```bash
scripts/gen-config.sh
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/BellTimer
```

The .env is optional: without it the generator falls back to `.env.example` and
warns. Flash the firmware and open the serial monitor:

```bash
arduino-cli upload -p /dev/ttyUSB0 --fqbn esp32:esp32:esp32 firmware/BellTimer
arduino-cli monitor -p /dev/ttyUSB0 -c baudrate=115200
```

Or build, upload and restore the default config in one step:

```bash
./flash-firmware.sh
```

The serial port is detected automatically (`arduino-cli board list`), so no
arguments are needed when a single board is connected. The script runs the build
script, uploads the result and then rewrites `firmware/BellTimer/app_config.h`
with the defaults from `.env.example`, so your real SSID, password and token are
not left in the generated header. The next build renders `.env` again.

Use `--port /dev/ttyUSB0` when several boards are connected and `--no-reset` to
keep the generated header with your values. `scripts/flash-firmware.sh` is the
same script; the root `flash-firmware.sh` is a thin wrapper for convenience.

On Linux, the serial port may be `/dev/ttyACM0` or `/dev/ttyUSB0`; the user must
have permission to access it. Board settings in the IDE: **ESP32 Dev Module**,
upload speed 921600 or 115200, flash size 4 MB, partition scheme "Default 4MB
with spiffs (1.2MB APP/1.5MB SPIFFS)".

## Build and install the Android app

```bash
cd android
./gradlew assembleDebug
./gradlew lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is produced at
`android/app/build/outputs/apk/debug/app-debug.apk`.

### App version

`versionName` and `versionCode` default to `1.0.0` and `1`. Override them for a
single build through Gradle properties:

```bash
cd android
./gradlew assembleDebug -PappVersionName=1.2.0 -PappVersionCode=10200
```

### Release workflow (GitHub Actions)

`.github/workflows/android-release.yml` is manual only (`workflow_dispatch`), so
it never runs on pushes. Start it from the Actions tab or with `gh`:

```bash
gh workflow run android-release.yml -f version=1.2.0
gh workflow run android-release.yml -f version=1.2.0 -f version_code=10200 -f prerelease=true
```

| Input | Required | Meaning |
| --- | --- | --- |
| `version` | yes | `1.2.3` or `v1.2.3`; used as `versionName` and tag `v1.2.3` |
| `version_code` | no | integer `versionCode`; defaults to `major*10000 + minor*100 + patch` |
| `prerelease` | no | mark the GitHub release as a pre-release |
| `draft` | no | create the GitHub release as a draft |

The workflow installs JDK 17 plus the Android SDK packages, builds
`assembleDebug` with the requested version, uploads the APK as a workflow
artifact, and publishes `bell-timer-v<version>.apk` with a `.sha256` checksum on
the GitHub release tagged `v<version>`. Re-running the workflow for an existing
tag replaces the attached assets. The APK is debug-signed (installable on any
phone); add a release keystore if you need a Play-style signed build.

## Using the device

1. Power the ESP32. With the default configuration it creates the Wi-Fi network
   **BellTimer** with password `bell12345`, channel 6, at most 4 clients,
   address `192.168.4.1`. Change these values in `.env` and rebuild to customize
   them.
2. Join that network on the phone. It has no internet access; Android may warn
   about this and may prefer mobile data. The app binds its HTTP sockets to the
   Wi-Fi network with `Network.socketFactory`, so the device is still reachable.
3. Open the app. When the RTC is missing or the device clock is not set, the
   control screen offers a "Синхронизировать время" button that sets the device
   time from the phone.
4. Review or edit the schedule. It is organised into **packs** (`пачки`) shown
   top to bottom. A **base** pack adds its entries; a **correction** cancels the
   base packs above it on the days it has entries, then adds its own. Use the
   arrows on a pack row to change the order, the switch to enable or disable it,
   and the pack editor to set a name, a date range, individual dates or a yearly
   repeat. Every entry has its own weekdays, an optional `pair` time and a label.
   Each pack holds up to 16 entries and has a quick "Очистить" action.
   "Собрать по урокам" builds entries from a per-lesson table with individual
   breaks, and the schedule tab can import and export the whole document as
   JSON. Nothing is sent to the device until the single "Отправить" bar is
   used, and the app then re-reads the schedule to verify the bell count.
   The ring duration lives on the Settings screen only.
5. Choose **Авто** to follow the schedule, or **Вручную** to disable the schedule;
   manual ringing always works.

## Firmware configuration (.env)

Deployment settings are read from the git-ignored `.env` file in the repository
root. The ESP32 cannot read host files, so `scripts/gen-config.sh` renders
`.env` into `firmware/BellTimer/app_config.h`, which the sketch includes.
`scripts/build-firmware.sh` invokes the generator automatically:

```bash
scripts/gen-config.sh                  # .env -> app_config.h
scripts/gen-config.sh other.env        # use another settings file
CONFIG_HEADER=/tmp/app_config.h scripts/gen-config.sh
```

The generator only rewrites the header when the rendered content changed, so it
can safely run before every build.

| Variable | Default | Description |
| --- | --- | --- |
| `AP_SSID` | `BellTimer` | Access point name, at most 32 bytes |
| `AP_PASSWORD` | `bell12345` | WPA2 password, 8..63 characters; empty = open network |
| `AP_CHANNEL` | `6` | Wi-Fi channel, 1..13 |
| `AP_MAX_CLIENTS` | `4` | Concurrent clients, 1..8 |
| `API_TOKEN` | empty | Expected `X-Token` value; empty disables authentication |
| `PIN_RELAY` | `26` | Relay input, active LOW |
| `PIN_LED_GREEN` | `27` | KY-029 `G` pin |
| `PIN_LED_RED` | `25` | KY-029 `R` pin |
| `PIN_I2C_SDA` | `21` | DS3231 SDA |
| `PIN_I2C_SCL` | `22` | DS3231 SCL |
| `LED_ACTIVE_LOW` | `false` | `false` = common cathode (common to GND), `true` = common anode (common to 3V3) |

Variables already set in the environment take precedence over `.env`, and
`scripts/gen-config.sh /path/to/file` (or `ENV_FILE=...`) selects a different
config file; `CONFIG_HEADER=...` changes the output path. The generator
validates all values and warns when a pin uses GPIO6..11 (SPI flash).

If `API_TOKEN` is non-empty, every API request (including `GET /`) must send the
header `X-Token: <value>`. CORS preflight `OPTIONS` requests are exempt because
browsers do not attach custom headers to them. Tokens can also be supplied as a
`?token=` query parameter; the built-in HTML page has a token input.

## HTTP API

- Base URL: `http://192.168.4.1`
- Port 80, JSON in UTF-8
- All responses include CORS headers (`Access-Control-Allow-Origin: *`), and
  every endpoint answers `OPTIONS` preflight with HTTP 204.
- Errors return `{"ok":false,"error":"..."}` with a 4xx/5xx status.
- Optional authentication header: `X-Token` (not required for OPTIONS).
- Time format: `YYYY-MM-DDTHH:MM:SS`, local time.

### Status

```bash
curl -s http://192.168.4.1/status
```

```json
{
  "time": "2026-10-22T12:36:46",
  "rtc_ok": true,
  "mode": "auto",
  "ringing": false,
  "ring_left": 0,
  "ring_default": 5,
  "entries": 12,
  "groups": 1,
  "exceptions": 0,
  "errors": [],
  "fw": "1.1.0"
}
```

`time` is `null` when the RTC is missing or the time is not valid. `entries` is
the total number of bells across all groups; `groups` and `exceptions` are the
counts of the schedule groups and date exceptions.

### Ring and stop

```bash
# JSON body
curl -s -X POST http://192.168.4.1/ring \
  -H 'Content-Type: application/json' \
  -d '{"sec":5}'

# Query parameter
curl -s -X POST 'http://192.168.4.1/ring?sec=5'

# Without sec, ring_default is used
curl -s -X POST http://192.168.4.1/ring

curl -s -X POST http://192.168.4.1/stop
```

`sec` must be an integer from 1 to 60. The relay is always turned off by the
main loop when the duration expires; `/stop` cancels immediately.

### Mode

```bash
curl -s http://192.168.4.1/mode
curl -s -X POST http://192.168.4.1/mode \
  -H 'Content-Type: application/json' -d '{"mode":"auto"}'
curl -s -X POST http://192.168.4.1/mode \
  -H 'Content-Type: application/json' -d '{"mode":"manual"}'
```

The mode is stored in flash and survives power loss. The schedule rings only in
`auto` mode and only while the RTC time is valid.

### Time

```bash
curl -s -X POST http://192.168.4.1/time \
  -H 'Content-Type: application/json' \
  -d "{\"datetime\":\"$(date +%Y-%m-%dT%H:%M:%S)\"}"
```

The value is the phone's local time. The minute in which the time was set is
never used to ring a bell. Returns 503 `rtc_not_found` if the DS3231 does not
respond.

### Schedule

A schedule is an ordered list of **packs** (`пачки`). A **base** pack adds its
entries; a **correction** cancels the entries of every base pack above it and
adds its own. Packs can be enabled/disabled, limited by a date range or
individual dates, and repeated every year. Each entry has a time, weekdays, an
optional second time (`pair`) and a label. The ring duration is a device setting,
not an entry field. The complete contract, evaluation rules and migration notes
live in [docs/firmware-api-v4.md](docs/firmware-api-v4.md).

```bash
curl -s http://192.168.4.1/schedule
```

```json
{
  "version": 4,
  "max_entries": 192,
  "max_packs": 12,
  "max_pack_entries": 16,
  "max_pack_dates": 24,
  "auto": { "start": "2026-09-01", "stop": "2027-05-31" },
  "packs": [
    {
      "id": 1,
      "name": "Будни",
      "kind": "base",
      "enabled": true,
      "from": "2026-09-01",
      "to": "2027-05-31",
      "entries": [
        { "time": "08:30", "pair": "09:15", "days": [1, 2, 3, 4, 5], "label": "1 урок" }
      ]
    },
    {
      "id": 2,
      "name": "Коррекция на 7 ноября",
      "kind": "correction",
      "yearly": true,
      "dates": ["2026-11-07"],
      "entries": [ { "time": "10:00", "days": [6], "label": "Суббота" } ]
    }
  ]
}
```

Days are ISO weekdays: `1` = Monday, `7` = Sunday. `duration` is 1–60 seconds
and defaults to the device's `ring_default`. Labels are UTF-8 and truncated to
32 bytes at a code-point boundary.

- `auto.start` / `auto.stop` are optional global dates. Scheduled bells fire
  only inside the range; an omitted date means "no limit". Manual ringing is not
  affected.
- Packs are evaluated top to bottom. A base pack adds its entries. A correction
  that has entries for today first cancels all base bells collected above it,
  then adds its own. Base packs below a correction still ring.
- `from`/`to` form an inclusive date range; `dates` lists individual dates. A
  pack applies when either rule matches. `yearly` compares month and day, so a
  range may wrap over New Year.
- `pair` adds a second time with the same weekdays and label.
- Two bells on the same `HH:MM` ring once, for the current `ring_default`.
- v1 flat `entries`, v2 `groups`/`exceptions` and v3
  `groups`/`overrides` are still accepted and migrated to version 4 on the next
  save.
- Limits: 12 packs, 16 entries per pack, 24 individual dates per pack, 192
  entries in total. The app shows these limits and refuses to save more.

Replace the whole schedule:

```bash
curl -s -X POST http://192.168.4.1/schedule \
  -H 'Content-Type: application/json' \
  -d '{"version":4,"packs":[{"id":1,"name":"Будни","kind":"base",
       "entries":[{"time":"08:30","pair":"09:15","days":[1,2,3,4,5],"label":"1 урок"}]}],
       "auto":{"start":"2026-09-01","stop":"2027-05-31"}}'
```

What rings on a specific date (resolved on the device):

```bash
curl -s 'http://192.168.4.1/schedule/day?date=2026-11-07'
```

The whole payload is validated before anything is written. Invalid data returns
400 and leaves the old schedule untouched. A flash write failure returns 500
`storage_error`. Bodies over 32 KiB are rejected before parsing. A bell fires
only at second 0; the minute in which the device booted or the time was set is
skipped, and missed bells are not replayed.

### Settings

```bash
curl -s http://192.168.4.1/settings
curl -s -X POST http://192.168.4.1/settings \
  -H 'Content-Type: application/json' -d '{"ring_default":5}'
```

`ring_default` must be from 1 to 60.

### HTML test page

```bash
curl -s http://192.168.4.1/
```

Open the same URL in a browser while connected to BellTimer. The page shows
status, has ring/stop/mode buttons and an X-Token input.

### Error codes

| Error | HTTP | Meaning |
| --- | --- | --- |
| `unauthorized` | 401 | `X-Token` missing or wrong |
| `invalid_json` | 400 | Body is not valid JSON |
| `invalid_sec` | 400 | Ring duration is not 1–60 |
| `invalid_mode` | 400 | Mode is not `auto`/`manual` |
| `invalid_datetime` | 400 | Bad date/time or year outside 2024–2099 |
| `invalid_schedule` | 400 | Any invalid schedule field, duplicate pack id, `to` before `from` on a non-yearly pack, `mute` on a base pack, or `auto.stop` before `auto.start` |
| `too_many_entries` | 400 | More than 192 entries in total, or a body larger than 32 KiB |
| `too_many_packs` | 400 | More than 12 packs |
| `too_many_pack_entries` | 400 | More than 16 entries in one pack |
| `too_many_pack_dates` | 400 | More than 24 individual dates in one pack |
| `too_many_groups`, `too_many_overrides`, `too_many_exceptions` | 400 | Kept for clients that still send v1/v2/v3 documents |
| `invalid_date` | 400 | `/schedule/day` with a missing or invalid `date` |
| `invalid_ring_default` | 400 | Default duration is not 1–60 |
| `not_found` | 404 | Unknown path |
| `storage_error` | 500 | LittleFS mount/write failed |
| `rtc_not_found` | 503 | DS3231 did not respond to `/time` |

`/status` also reports active device errors: `rtc_not_found`, `time_not_set`,
`storage_error`, `wifi_ap_error`. They disappear automatically when the cause
goes away.

## Status LED

The KY-029 is a dual-colour module: two dies (green and red) sharing one common
pin. It is wired to two GPIOs, so the colours below are produced natively.

| Colour | Meaning |
| --- | --- |
| Green, 1 Hz blink (500 ms on/off) | Normal, no errors |
| Steady green | The bell is ringing (nothing blinks while it sounds) |
| Red, 2 Hz blink (250 ms on/off) | Critical error: `rtc_not_found`, `storage_error` or `wifi_ap_error` |
| Steady red | Warning: `time_not_set` - manual ring works, but the schedule cannot fire |

Priority from highest to lowest: ringing, critical error, warning, normal.

Using red both ways keeps the severity readable at a glance without Morse code:
a slow red blink means *broken*, a steady red means *not ready yet*, and the
steady red turns itself off as soon as the time is set.

If your KY-029 is the **common-anode** variant (common pin to 3V3 instead of
GND), set `LED_ACTIVE_LOW = true` in `firmware/BellTimer/BellTimer.ino` and
reflash; nothing else changes.

## Automated API test

With the PC connected to the BellTimer Wi-Fi network:

```bash
scripts/api-test.sh
# or a custom address / token:
X_TOKEN=secret scripts/api-test.sh http://192.168.4.1
```

The script exercises every endpoint, checks status codes, CORS, UTF-8 labels,
default days, legacy migration, packs and corrections, `/schedule/day`, the
limits and 400/503 error paths, and 404 handling. It saves the current schedule,
mode and `ring_default` and restores them on exit.

## Testing checklist

1. `scripts/build-firmware.sh` succeeds and prints flash/RAM usage.
2. The serial monitor prints no crash/reboot loop after power-up.
3. The BellTimer SSID appears; a phone gets an address in `192.168.4.0/24`.
4. `curl http://192.168.4.1/status` returns JSON with `"fw":"1.3.0"` and
   `"api":4`.
5. LED blinks green 1 Hz; disconnecting the RTC shows a fast red blink and
   `rtc_not_found`.
6. Set the time from the app; `rtc_ok` becomes `true`, `time_not_set` clears.
7. Ring for 3 s and press Stop: relay clicks on/off and the LED is steady green
   while ringing.
8. Save an entry one minute in the future; at second 0 the relay clicks once.
   Enable its `pair` and check that both times ring with the same duration.
9. Place a correction below a base pack with an entry for today; check that
   `GET /schedule/day?date=<today>` drops the base bells. Add a base pack below
   the correction and check that its bells survive.
10. Switch to `manual`; the schedule no longer fires, manual ring still works.
11. Power-cycle the device: schedule, packs, mode and `ring_default` survive.
12. Boot during a scheduled minute: no bell for that minute.
13. Rotate the phone and toggle dark mode: screen state and unsaved schedule
    changes stay intact.
14. Disable Wi-Fi: the app shows an offline indicator and a Russian error.
15. Send a malformed schedule via curl: HTTP 400 and the device keeps the old
    schedule.
16. With an older firmware (`api` < 4), the app refuses to send a non-empty
    schedule and shows "Нужно обновить прошивку устройства".

## Implementation notes and assumptions

- The DS3231 stores local time; there is no timezone conversion
  and no NTP.
- The relay module is assumed to be active LOW, as specified. If a different
  module is used, invert `relayOn()`/`relayOff()`.
- The firmware uses a single `.ino` file and never blocks in `loop()`. RTC
  detection, AP startup and LittleFS mounting are retried periodically.
- The schedule is written with a temporary file and rename, so a power loss
  during save does not leave a half-written schedule.
- Packs have their own date rules, so nothing is pruned by age; a pack with a
  past range simply stops applying.
- The schedule is resolved by `collectDayBells()` on the device and by
  `BellSchedule.bellsFor(date)` in the app; both implement the same rules from
  [docs/firmware-api-v4.md](docs/firmware-api-v4.md).
- The app uses cleartext HTTP only for `192.168.4.1`
  (`res/xml/network_security_config.xml`). If the base URL is changed to a
  different cleartext host, add that host to the network security config.
- The Android app binds HTTP calls to the Wi-Fi transport. This avoids mobile
  data being used when the BellTimer network has no internet access.
- The app's default ring duration is stored locally and also sent to the device
  as `ring_default`.
- HTTP is unencrypted and the AP password is a compile-time constant. This is a
  closed, local-only device; do not expose it to an untrusted network. Set
  `API_TOKEN` for a small amount of protection.
