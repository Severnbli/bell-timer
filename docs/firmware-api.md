# BellTimer HTTP API (API 7, schedule version 6)

This document is the contract between the Android app (`android/`, package
`com.belltimer.app`) and the device firmware (`firmware/BellTimer/BellTimer.ino`).

Firmware `2.2.0` implements everything below and reports `"api": 7`.

## Basics

* Base URL `http://192.168.4.1`, port 80, JSON in UTF-8.
* Times are local: `YYYY-MM-DDTHH:MM:SS`, dates `YYYY-MM-DD`, times of day `HH:MM`.
* Errors are `{"ok": false, "error": "<code>"}` with a 4xx/5xx status.
* **Token.** When the device has an API token, every request needs it in the
  `X-Token` header (or `?token=` for plain GET requests from a browser).
  `POST /update` accepts the header only. After 10 wrong tokens every request
  gets `429 too_many_attempts` for 30 s.
* **JSON only.** Every POST except `/update` must have
  `Content-Type: application/json`, even without fields (send `{}`).
  Otherwise the answer is `415 json_required`.
* **No CORS.** The device sends no `Access-Control-*` headers. A web page that
  the phone opens on the internet therefore cannot read from the device. It
  also cannot send JSON or an `X-Token` header to it, because both need a CORS
  preflight that is never approved.

## Compatibility

| | Firmware 2.0.0 (API 5) | Firmware 2.1.0 (API 6) | Firmware 2.2.0 (API 7) |
| --- | --- | --- | --- |
| Schedule written | v5 | v6 (one range stays in the v5 shape) | v6 |
| Schedule accepted | v4, v5 | v4, v5, v6 | v4, v5, v6 |
| Several ranges per pack | no: `ranges` is ignored | yes | yes |
| Firmware update over Wi-Fi | no | `POST /update` | `POST /update` |
| Wiring at run time | no | `GET/POST /device` | `GET/POST /device` |
| Local-only access point | no | `local_only` in `/network` | `local_only` in `/network` |
| `min_app` in `/status` | no | yes | yes |
| Transmit power | no | no | `tx_power` in `/network` |
| `uptime`, `reset` in `/status` | no | no | yes |

Version dependencies:

* The firmware declares the oldest app it supports (`MIN_APP_VERSION` in the
  sketch). `/status` reports it as `min_app`, and the release workflow copies
  it into `firmware.json`.
* The app declares the oldest firmware it supports (`MIN_FIRMWARE_VERSION` in
  `data/Models.kt`). The release workflow copies it into `app.json`.
* The app refuses to install firmware whose `min_app` is newer than itself. It
  warns before an app update whose `min_firmware` is newer than the device
  firmware, and shows a banner when the device and the app do not fit.
* The app does not send several ranges to a device below API 6: such firmware
  would ignore `ranges` and run the pack every day.

## Endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /status` | device state, next bell, versions |
| `POST /ring {sec}` / `POST /stop` | ring for 1–60 s / stop |
| `GET/POST /mode` | `auto` or `manual` |
| `POST /time {datetime}` | set the clock; also stored as the last sync time |
| `GET/POST /settings` | `ring_default`, 1–60 s |
| `GET /schedule` | the v6 document with limits |
| `POST /schedule` | accepts v6, v5 and v4 |
| `GET /schedule/day?date=YYYY-MM-DD` | resolved bell list for one date |
| `POST /reset` | factory reset, optionally including the network |
| `GET/POST /network` | access point, token, local-only mode |
| `GET/POST /device` | wiring (pins and LED polarity) |
| `POST /update` | install a firmware image |
| `GET /` | small HTML control page |

### `GET /status`

```json
{
  "time": "2026-10-23T09:03:41",
  "rtc_ok": true,
  "mode": "auto",
  "ringing": false,
  "ring_left": 0,
  "ring_default": 5,
  "modules": 9,
  "packs": 3,
  "synced": "2026-10-20T07:55:02",
  "today_bells": 14,
  "next": {"date": "2026-10-23", "time": "09:15", "label": "1 урок",
           "type": "lesson", "role": "end"},
  "auto": {"start": "2026-09-01", "stop": "2027-05-31"},
  "errors": [],
  "fw": "2.2.0",
  "api": 7,
  "min_app": "1.5.0",
  "ssid": "BellTimer",
  "uptime": 86400,
  "reset": "power_on"
}
```

* `time` is `null` while the clock is not valid; `today_bells` is then absent.
* `synced` is the local time of the last `POST /time`, or `null`.
* `next` is the first bell after the current minute within 8 days, or `null`.
* `errors` lists active problems: `rtc_not_found`, `time_not_set`,
  `storage_error`, `wifi_ap_error`, `network_config_error` (stored network
  settings unreadable, defaults in use) and `device_config_error` (stored
  wiring unreadable, defaults in use).
* `uptime` is the number of seconds since the device started, and `reset` is
  why it started: `power_on`, `external` (reset button), `software` (restart
  after an update or a wiring change), `panic` (crash), `task_wdt` (`loop()`
  stopped for 15 s and the watchdog restarted it), `int_wdt`, `wdt`,
  `brownout` (supply voltage dropped), `deep_sleep`, `sdio` or `unknown`.
  Both fields are optional: older firmware does not send them.

### `POST /time`

`{"datetime": "2026-10-23T09:03:41"}` sets the DS3231. The minute in which the
time was set never rings. `503 rtc_not_found` without a DS3231.

### `POST /reset`

Stops a running bell and deletes `/schedule.json` and `/settings.json`: empty
schedule, `auto` mode, `ring_default` 5, no `synced`. The clock and the wiring
are kept.

With `{"network": true}` it also deletes `/network.json`. The access point and
the token return to the defaults compiled into the running firmware. For a
release build those are the `.env.example` values: SSID `BellTimer`, password
`bell12345`, no token. The access point then restarts 1.5 s after the
response, which says `"restart": true`.

### `GET /network` and `POST /network`

```json
{"ssid": "BellTimer", "channel": 6, "max_clients": 4, "open": false,
 "token_set": true, "local_only": false, "local_only_trial": false,
 "tx_power": 20}
```

The password and the token are never returned. `POST` takes any of:

* `ssid`: 1–32 bytes.
* `password`: 8–63 characters; `""` makes the network open.
* `token`: up to 64 printable ASCII characters without spaces; `""` turns
  authentication off.
* `channel`: 1–13. `max_clients`: 1–8.
* `local_only`: `true` makes the DHCP server announce no gateway. Phones then
  treat the network as local and keep using mobile data for the internet.
* `tx_power`: transmit power of the access point in dBm, one of 20, 19.5, 19,
  18.5, 17, 15, 13, 11, 8.5, 7, 5, 2. The default 20 is the chip default. A
  lower power draws less current at every packet, which helps a board whose
  supply sags (for example `brownout` restarts), at the cost of range.

A new `tx_power` applies at once, clients stay joined. Any change except the
token and `tx_power` restarts the access point 1.5 s after the
response (`"restart": true`). A change of `local_only` is a **trial**: it is
stored only when an authorised request arrives on the restarted network. If no
request comes within 3 minutes, the device returns to the previous mode by
itself. A phone that cannot join the changed network therefore only has to
wait. The response and `GET /network` report `local_only_trial` while the
trial runs.

Errors: `invalid_ssid`, `invalid_password`, `invalid_token`,
`invalid_channel`, `invalid_max_clients`, `invalid_local_only`,
`invalid_tx_power`, `500 storage_error`.

### `GET /device` and `POST /device`

```json
{"relay": 26, "led_green": 27, "led_red": 25, "sda": 21, "scl": 22,
 "led_active_low": false, "error": false}
```

`POST` takes any of the first six fields. The five pins must be distinct and
able to drive an output: 0, 2, 4, 5, 12–19, 21–23, 25–27, 32, 33. GPIO 1 and
3 (serial), 6–11 (flash) and 34–39 (input only) are refused with
`invalid_pins`. On success the device answers `{"ok": true, "restart": true}`
and restarts 1.5 s later, because pins are set up once at boot.

### `POST /update`

```
POST /update?md5=<32 hex>&size=<bytes>
X-Token: <token>
Content-Type: multipart/form-data; boundary=...

part "firmware": the application image (BellTimer.ino.bin)
```

* Needs a token on the device (`403 token_required` otherwise), sent in the
  `X-Token` header (`401 unauthorized`).
* The image is written into the idle OTA slot. The device switches to it only
  after the whole image arrived, its header is valid and its MD5 matches. A
  broken upload or a power cut keeps the running firmware.
* Bells stop while the image is written (about 20–40 s).
* LittleFS is a separate partition and is not touched, so the schedule, the
  settings, the network and the wiring survive.
* Success: `{"ok": true, "restart": true}`, then a restart after 1.5 s.

Errors: `invalid_md5`, `invalid_size`, `no_firmware`, `md5_mismatch`,
`invalid_image`, `upload_aborted` (400), `update_begin_failed`,
`update_write_failed` (500), `too_many_attempts` (429).

With curl:

```bash
bin=BellTimer.ino.bin
curl -H "X-Token: $TOKEN" \
  -F "firmware=@$bin" \
  "http://192.168.4.1/update?md5=$(md5sum "$bin" | cut -d' ' -f1)&size=$(stat -c %s "$bin")"
```

### `GET /schedule/day?date=YYYY-MM-DD`

```json
{
  "date": "2026-11-02",
  "bells": [
    {"time": "07:58", "label": "1 урок", "type": "lesson", "role": "pre",   "pack": 1},
    {"time": "08:00", "label": "1 урок", "type": "lesson", "role": "start", "pack": 1},
    {"time": "08:45", "label": "1 урок", "type": "lesson", "role": "end",   "pack": 1}
  ]
}
```

Bells are sorted by time and merged: a minute appears once.

## Schedule document, version 6

```json
{
  "version": 6,
  "packs": [
    {
      "id": 1,
      "name": "Четверти",
      "kind": "base",
      "enabled": true,
      "ranges": [
        {"from": "2026-09-01", "to": "2026-10-25"},
        {"from": "2026-11-03", "to": "2026-12-24"},
        {"from": "2027-01-12", "to": "2027-03-20"},
        {"from": "2027-04-01", "to": "2027-05-31"}
      ],
      "modules": [
        {"type": "lesson", "time": "08:00", "length": 45, "pre": 2,
         "days": [1, 2, 3, 4, 5], "label": "1 урок"},
        {"type": "bell", "time": "12:30", "days": [1, 2, 3, 4, 5], "label": "Обед"},
        {"type": "series", "time": "14:00", "until": "18:00", "every": 60}
      ]
    },
    {
      "id": 2,
      "name": "4 ноября",
      "kind": "correction",
      "mute": true,
      "yearly": true,
      "dates": ["2026-11-04"],
      "modules": []
    }
  ],
  "auto": {"start": "2026-09-01", "stop": "2027-05-31"}
}
```

### Modules

| `type` | Fields | Bells |
| --- | --- | --- |
| `bell` | `time` | one bell at `time` (role `bell`) |
| `lesson` | `time`, `length` (1–720 min), optional `pre` (0–60 min) | `time − pre` (role `pre`, only when `pre > 0`), `time` (`start`), `time + length` (`end`) |
| `series` | `time`, `until` (after `time`), `every` (1–720 min) | `time`, `time + every`, … while `≤ until` (role `step`) |

Every module also has `days` (ISO weekdays, `1` = Monday … `7` = Sunday;
missing or empty = every day) and an optional `label` (truncated to 32 UTF-8
bytes). A missing `type` means `bell`. A lesson must end by 23:59 and its
warning bell may not fall before midnight.

### Packs

* `packs`: ordered list, top to bottom. The order matters for corrections.
* `kind`: `"base"` (default) or `"correction"`.
* `enabled`: `false` removes the pack from evaluation.
* `mute`: corrections only, the pack is a holiday. It cancels the base packs
  above it on its dates.
* `name`: optional, truncated to 32 UTF-8 bytes.
* `ranges`: up to 8 inclusive date ranges, each with `from`, `to` or both. For
  a non-yearly pack `to` may not be before `from`.
* `from` / `to` on the pack itself: one range, the version 5 shape. Read when
  `ranges` is absent. The device and the app write a single range this way, so
  older firmware and apps still read it.
* `dates`: up to 24 individual dates.
* A pack applies when the date falls into one of its ranges **or** is one of
  its dates; with neither, it applies every day.
* `yearly`: compare ranges and dates by month and day only, so a range may wrap
  over New Year.
* `auto`: optional global restriction; a missing field means "no limit".

### Limits

| Limit | Value | Constant (firmware / app) |
| --- | --- | --- |
| Packs | 8 | `MAX_PACKS` |
| Modules per pack | 32 | `MAX_PACK_MODULES` |
| Modules in total | 256 | `MAX_MODULES` |
| Date ranges per pack | 8 | `MAX_PACK_RANGES` |
| Individual dates per pack | 24 | `MAX_PACK_DATES` |
| Label / pack name | 32 UTF-8 bytes | `MAX_LABEL_BYTES` / `MAX_PACK_NAME_BYTES` |
| Request body | 40 KiB | `MAX_SCHEDULE_BODY_BYTES` |

Reference build (`esp32:esp32:esp32`, firmware 2.1.0): 1 051 404 bytes of
program storage (80 % of the 1.25 MB OTA slot) and 71 312 bytes of static RAM.

## Evaluation

For a date:

1. Outside `auto.start` / `auto.stop` nothing rings.
2. Walk the packs top to bottom. Skip a pack that is disabled or whose window
   does not match the date.
3. A **base** pack adds the bells of its modules whose `days` contain the
   weekday.
4. A **correction** applies when it is muted or has a module for the weekday.
   It first drops every bell contributed by base packs so far, then adds its
   own bells unless muted.
5. Base packs below a correction are evaluated normally.
6. Bells that share a minute ring once; the first module to claim the minute
   owns it.

The device rings at the start of a minute that holds a bell, only in `auto`
mode and with a valid clock. A bell still fires within the first 5 seconds of
its minute, never twice, and never in the minute the device booted or the time
was set. This mirrors `BellSchedule.resolveDay(date)` in
`android/app/src/main/java/com/belltimer/app/data/Schedule.kt`.

## Migration

* v5 → v6: the pack's `from` / `to` become its single range.
* v4 → v6: packs keep their id, name, kind, window and `mute`. An entry with a
  `pair` becomes a `lesson` from the earlier to the later time (at most 720
  minutes apart); any other entry becomes a `bell`.

## Error codes

`{"ok": false, "error": "<code>"}` with HTTP 400 unless noted.

| Code | Meaning |
| --- | --- |
| `invalid_schedule` | Missing `packs`, invalid field, unknown module type, duplicate pack id, invalid range, `mute` on a base pack |
| `invalid_json` | Body missing or not parseable |
| `too_many_packs` / `too_many_pack_modules` / `too_many_modules` | More than 8 packs / 32 modules in a pack / 256 modules or a body over 40 KiB |
| `too_many_pack_ranges` / `too_many_pack_dates` | More than 8 ranges / 24 dates in one pack |
| `invalid_date` | `/schedule/day` without a valid `date` |
| `invalid_sec`, `invalid_mode`, `invalid_datetime`, `invalid_ring_default` | value out of range |
| `invalid_ssid`, `invalid_password`, `invalid_token`, `invalid_channel`, `invalid_max_clients`, `invalid_local_only`, `invalid_tx_power` | `POST /network` value out of range |
| `invalid_pins` | `POST /device` with an unusable or repeated pin |
| `invalid_request` | `POST /reset` with a malformed body |
| `json_required` | 415, POST without `Content-Type: application/json` |
| `unauthorized` | 401, wrong or missing token |
| `token_required` | 403, `POST /update` on a device without a token |
| `too_many_attempts` | 429, 10 wrong tokens in a row, wait 30 s |
| `storage_error` | 500, flash write failed |
| `rtc_not_found` | 503, `/time` without a DS3231 |
| `not_found` | 404, unknown path |
| `POST /update` codes | see above |

## Storage

All files live in LittleFS, which a firmware update never touches. Each
settings file carries `"v": 1`; a newer firmware must keep reading older files.

| File | Content | When missing |
| --- | --- | --- |
| `/schedule.json` | the schedule document | empty schedule |
| `/settings.json` | `ring_default`, `mode`, `synced` | defaults |
| `/network.json` | `ssid`, `password`, `token`, `channel`, `max_clients`, `local_only`, `tx_power` | created from the build defaults on boot |
| `/device.json` | pins and `led_active_low` | created from the build defaults on boot |

* Files are written atomically (temp file, then rename).
* Because missing network and wiring files are created on the first boot,
  the values a device first ran with stay with it. A firmware built with other
  defaults, such as a release build, cannot change them.
* A schedule the firmware cannot read is renamed to `/schedule.json.bad`
  instead of being lost. A damaged network or wiring file is left alone, the
  defaults are used and `/status` reports the error until the app saves new
  values.
