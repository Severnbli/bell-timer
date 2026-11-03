# Bell timer

A standalone school bell controller. An ESP32 keeps the bell schedule in its
flash memory and switches a relay at the right times. It creates its own Wi-Fi
network, and an Android app joins it to edit the schedule, ring by hand, set
the clock and install firmware updates. No router, internet or NTP server is
needed.

- **Schedule**: packs of lessons, single bells and repeating series, with
  corrections, holidays, several date ranges per pack and a yearly repeat.
- **Clock**: a DS3231 real-time clock with a backup battery keeps the time
  through power cuts. The app sets it from the phone.
- **Updates**: the app checks GitHub for new app and firmware versions and
  installs firmware over Wi-Fi. The schedule and all settings stay.

## Contents

- [Parts](#parts)
- [Wiring](#wiring)
- [First setup](#first-setup)
- [Using the app](#using-the-app)
- [Updates](#updates)
- [Security](#security)
- [Status LED](#status-led)
- [Building from source](#building-from-source)
- [Releases](#releases)
- [HTTP API](#http-api)
- [Repository layout](#repository-layout)

## Parts

| Part | Example | Notes |
| --- | --- | --- |
| ESP32 board | ESP32-DevKitC / "ESP32 Dev Module" (ESP32-WROOM-32, 4 MB flash) | Any classic ESP32 with 4 MB flash |
| Real-time clock | DS3231 module (ZS-042 or similar) | I2C address `0x68` |
| Clock battery | CR2032 (or LIR2032 on modules with a charging circuit) | Keeps the time without power |
| Relay | 1-channel 5 V relay module with optocoupler, **active LOW** input | Contacts rated for your bell |
| Status LED | KY-029 two-colour (red/green) LED module | Common cathode or common anode |
| LED resistors | 2 × 220 Ω | Only if your KY-029 has no resistors on board |
| Power supply | 5 V, at least 1 A, USB or into the `5V`/`VIN` pin | Powers the ESP32 and the relay |
| Bell | Any bell or sounder the relay contacts can switch | Mains bells need a proper enclosure |
| Wires | Dupont jumper wires, terminal blocks | |

## Wiring

```
                         ESP32 DevKit
                     ┌──────────────────┐
   DS3231 VCC ───────┤ 3V3              │
   DS3231 GND ───────┤ GND          5V  ├─────── Relay VCC
   DS3231 SDA ───────┤ GPIO21       GND ├─────── Relay GND
   DS3231 SCL ───────┤ GPIO22    GPIO26 ├─────── Relay IN   (LOW = on)
                     │           GPIO27 ├──[220Ω]── KY-029 G (green)
                     │           GPIO25 ├──[220Ω]── KY-029 R (red)
                     │              GND ├─────── KY-029 common ("-")
                     └──────────────────┘

   Relay contacts:   supply ──── COM    NO ──── bell ──── supply return
```

| From | To | Notes |
| --- | --- | --- |
| DS3231 `VCC` | ESP32 `3V3` | |
| DS3231 `GND` | ESP32 `GND` | |
| DS3231 `SDA` | `GPIO21` | |
| DS3231 `SCL` | `GPIO22` | |
| Relay `VCC` | ESP32 `5V` (`VIN`) | |
| Relay `GND` | ESP32 `GND` | |
| Relay `IN` | `GPIO26` | Active LOW. The firmware drives it HIGH (off) before anything else at boot |
| KY-029 `G` | `GPIO27` | Through 220 Ω if the module has no resistor |
| KY-029 `R` | `GPIO25` | Through 220 Ω if the module has no resistor |
| KY-029 common | `GND` | Common cathode. A common-anode module goes to `3V3`; then turn on "Common anode" in the app (Settings → Wiring) |
| Relay `COM` / `NO` | Bell circuit | The bell sounds while the relay is on |

- These are the default pins. They can be changed later in the app (Settings
  → Wiring) without rebuilding the firmware.
- Most optocoupler relay modules switch reliably from a 3.3 V input with a 5 V
  supply. If yours does not, use a module made for 3.3 V logic.
- **Mains voltage:** if the bell runs on 230 V, have the relay side wired by
  a qualified person, in a closed enclosure, with the contacts rated for the
  bell's current.

## First setup

1. **Flash the firmware over USB, once.** Download
   `belltimer-firmware-v<version>-full.bin` from the newest `firmware-v*`
   release and write it to a new board:

   ```bash
   esptool.py --chip esp32 write_flash 0x0 belltimer-firmware-v2.1.0-full.bin
   ```

   Or build and flash from source with `./flash-firmware.sh` (see
   [Building from source](#building-from-source)). The full image erases the
   board, so use it only on a new device. Later updates go over Wi-Fi.
2. **Install the app.** Download `bell-timer-v<version>.apk` from the newest
   `v*` release and open it on the phone.
3. **Join the bell's Wi-Fi.** By default the network is **BellTimer**,
   password `bell12345`. The device is at `192.168.4.1`.
4. **Open the app.** It finds the device by itself and offers to set the clock
   from the phone.
5. **Secure it.** In Settings → Access point and token, set your own Wi-Fi
   password and an API token. Firmware updates need a token.
6. **Optional:** turn on "Keep mobile internet" in the same card. The phone
   then keeps using mobile data while it is joined to the bell (see
   [Updates](#updates)).

## Using the app

The app has three tabs.

- **Now**: the device clock and its drift from the phone, the running lesson,
  the next bell, a big ring button, the Auto/Manual switch and today's bells.
  Banners warn about device errors, incompatible versions and available
  firmware updates.
- **Schedule**: a month calendar coloured by day kind. Tap a day to see its
  bells or add it to a pack. Below the calendar is the list of **packs**,
  which you can reorder by dragging. A pack holds **modules**:
  - **Lesson**: a bell at the start and at the end, and optionally a warning
    bell a few minutes before;
  - **Bell**: a single bell;
  - **Series**: a bell every N minutes between two times.

  A **base** pack adds its bells. A **correction** replaces the base packs
  above it on its dates, and a **day off** (muted correction) silences them.
  Each pack can be limited to up to **8 date ranges** (for example the four
  school terms), up to 24 single dates, and a yearly repeat. "Lesson grid"
  fills a pack from a template with a length for every lesson and every
  break. Nothing reaches the device until you press **Send**.
- **Settings**: ring duration, automatic time sync, connection, access point
  and token, wiring, updates, language (Русский, Беларуская, English) and
  factory reset.

## Updates

**Checking.** The app checks the GitHub releases when it starts, and when you
tap **Check** in Settings → Updates. It shows the newest app and firmware
versions next to the installed ones.

**Firmware.** Firmware updates take two steps, so you can download at home and
install at the bell:

1. **Download**: the app downloads the image and verifies its SHA-256.
2. **Install**: while joined to the bell, the app sends the image. The device
   writes it to the spare firmware slot, checks its MD5 and restarts. If
   anything fails, it keeps running the old firmware.

The schedule, the settings, the Wi-Fi network, the token and the wiring are
stored separately from the firmware and are not touched by an update. Bells
pause for about a minute during the install.

**App.** When a newer app exists, Settings → Updates opens its download page.
Releases are signed with one permanent key, so a new version installs over
the old one.

> Upgrading from 1.4.x or older: those builds were signed with a temporary
> key. Uninstall the old app once before installing 1.5.0. The schedule lives
> on the device and is kept; only your own lesson templates and the app's
> settings (address, token) are lost. Later updates install over the top.

**Internet while joined to the bell.** The bell's network has no internet, so
the app sends its GitHub requests over mobile data, even while the phone is
joined to the bell. To make the whole phone keep its mobile internet, turn on
**Keep mobile internet**. The device then stops announcing itself as a
gateway. Phones vary, so this switch is a trial. Join the bell again within 3
minutes and the setting stays; otherwise the device switches back by itself.

**Version dependencies.** Each firmware release states the oldest app it
works with, and each app release states the oldest firmware it works with.
The app refuses to install firmware that needs a newer app. It warns before
an app update that would not work with the device firmware, and shows a
banner when the two do not fit.

**First update over Wi-Fi.** Firmware 2.0.0 and older cannot update over
Wi-Fi. Flash 2.1.0 or newer over USB once. Use `./flash-firmware.sh` with your
`.env`, which keeps the stored schedule; the `-full.bin` image would erase it.
On its first boot, 2.1.0 saves the network settings and wiring it was built
with, so later release builds keep them.

## Security

- **Token.** With a token set, every request needs it. After 10 wrong tokens
  the device refuses all requests for 30 seconds. Firmware updates are refused
  unless a token is set.
- **Web pages.** The device sends no CORS headers and accepts changes only as
  JSON, so a web site opened on the phone cannot ring the bell or change it.
- **Firmware integrity.** Every image is checked twice: by its SHA-256 from the
  release manifest, downloaded over HTTPS from GitHub, and by its MD5 on the
  device before it boots.
- **Plain HTTP.** Traffic inside the bell's own network is unencrypted. Use a
  WPA2 password so that only people who know it can join and see it.

## Status LED

| Colour | Meaning |
| --- | --- |
| Green, slow blink (1 Hz) | Working normally |
| Green, fast blink (5 Hz) | The bell is ringing |
| Red, steady | The clock is not set: manual ringing works, the schedule waits |
| Red, blinking (2 Hz) | Fault: clock not found, storage error or Wi-Fi failed |

## Building from source

### Firmware

Requirements: `arduino-cli`, the ESP32 core **3.3.12**, RTClib 2.1.4 and
ArduinoJson 7.4.3.

```bash
arduino-cli core install esp32:esp32@3.3.12
arduino-cli lib install "RTClib@2.1.4" "ArduinoJson@7.4.3"

cp .env.example .env          # Wi-Fi name, password, token, pins
scripts/build-firmware.sh     # build
./flash-firmware.sh           # build and flash over USB (port detected automatically)
```

`.env` holds only the **defaults** for a new device. On its first boot the
firmware stores them in `/network.json` and `/device.json`. From then on the
app changes them, and new firmware never overrides them.
`scripts/gen-config.sh` turns `.env` into `firmware/BellTimer/app_config.h`;
both files are git-ignored. `flash-firmware.sh` resets the header to the
`.env.example` values after flashing, so your password is not left on disk.

| Variable | Default | Meaning |
| --- | --- | --- |
| `AP_SSID` | `BellTimer` | Network name, up to 32 bytes |
| `AP_PASSWORD` | `bell12345` | WPA2 password, 8–63 characters; empty = open |
| `AP_CHANNEL` | `6` | Wi-Fi channel, 1–13 |
| `AP_MAX_CLIENTS` | `4` | Clients at once, 1–8 |
| `API_TOKEN` | empty | Token required in `X-Token`; empty = none |
| `PIN_RELAY`, `PIN_LED_GREEN`, `PIN_LED_RED`, `PIN_I2C_SDA`, `PIN_I2C_SCL` | 26, 27, 25, 21, 22 | Default wiring |
| `LED_ACTIVE_LOW` | `false` | `true` for a common-anode KY-029 |
| `FW_VERSION` | from the sketch | Version to report; set by the release workflow |

Board settings for the Arduino IDE: **ESP32 Dev Module**, 4 MB flash,
partition scheme **"Default 4MB with spiffs (1.2MB APP/1.5MB SPIFFS)"**. This
scheme has the two firmware slots that Wi-Fi updates need. Changing it erases
the stored settings.

### Android app

Requirements: JDK 17 and the Android SDK with platform 37 (`ANDROID_HOME` set).

```bash
cd android
./gradlew assembleDebug testDebugUnitTest lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 9.6 (wrapper), AGP 9.4.1, Kotlin 2.4.21, Compose BOM
2026.09.00, `minSdk` 26, `targetSdk` 37, OkHttp 4.12, kotlinx.serialization 1.9.

### Device test

With the computer joined to the bell's network:

```bash
scripts/api-test.sh                          # default address, no token
X_TOKEN=secret scripts/api-test.sh http://192.168.4.1
```

It tests every endpoint, then restores the schedule, mode and ring duration.

## Releases

Both workflows run only by hand (Actions tab or `gh workflow run`).

| Workflow | Tag | Assets |
| --- | --- | --- |
| `firmware-release.yml` | `firmware-v2.1.0` | `belltimer-firmware-v2.1.0.bin` (Wi-Fi update), `…-full.bin` (USB, new boards), `firmware.json`, `SHA256SUMS` |
| `android-release.yml` | `v1.5.0` | `bell-timer-v1.5.0.apk`, `.sha256`, `app.json` |

```bash
gh workflow run firmware-release.yml -f version=2.1.0
gh workflow run android-release.yml -f version=1.5.0
```

- Firmware releases never become the repository's "Latest" release, and the
  app ignores pre-releases and drafts.
- Before releasing, raise `MIN_APP_VERSION` in `BellTimer.ino` or
  `MIN_FIRMWARE_VERSION` in `android/.../data/Models.kt` when a change needs
  it.

**App signing key (one-time setup).** Android installs an update only if it
is signed with the same key as the installed app. Create a key once, store it
in the repository secrets, and never replace it:

```bash
keytool -genkeypair -v -keystore belltimer-release.jks -alias belltimer \
  -keyalg RSA -keysize 4096 -validity 36500
gh secret set ANDROID_KEYSTORE_BASE64 < <(base64 -w0 belltimer-release.jks)
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body belltimer
gh secret set ANDROID_KEY_PASSWORD
```

Keep a backup of `belltimer-release.jks` and its passwords outside the
repository. If the key is lost, every phone must uninstall the app to update.

## HTTP API

The device serves JSON at `http://192.168.4.1`. The full contract, covering
endpoints, the schedule format, limits, evaluation rules, storage and error
codes, is in [docs/firmware-api.md](docs/firmware-api.md). Quick examples:

```bash
H='Content-Type: application/json'
curl -s http://192.168.4.1/status
curl -s -X POST -H "$H" -d '{"sec":3}' http://192.168.4.1/ring
curl -s -X POST -H "$H" -d "{\"datetime\":\"$(date +%FT%T)\"}" http://192.168.4.1/time
curl -s 'http://192.168.4.1/schedule/day?date=2026-11-02'
```

Add `-H "X-Token: <token>"` when a token is set.

## Repository layout

```
firmware/BellTimer/BellTimer.ino   ESP32 firmware (one sketch)
android/                           Android app (Kotlin, Jetpack Compose)
docs/firmware-api.md               HTTP API and schedule format
scripts/gen-config.sh              .env -> firmware/BellTimer/app_config.h
scripts/build-firmware.sh          build with arduino-cli
scripts/flash-firmware.sh          build, flash, reset the generated header
scripts/api-test.sh                test every endpoint on a live device
flash-firmware.sh                  shortcut to scripts/flash-firmware.sh
.github/workflows/                 manual release workflows
.env.example                       template for the firmware defaults
CHANGELOG.md                       release notes
```
