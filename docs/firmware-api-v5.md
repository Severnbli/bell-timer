# BellTimer HTTP API, schedule version 5 (modules)

This document is the contract between the Android app (`android/`, package
`com.belltimer.app`) and the device firmware (`firmware/BellTimer/BellTimer.ino`).
It describes the module-based schedule the app writes, how the device resolves
a date, and how version 4 documents are migrated.

Firmware `2.0.0` implements everything below and reports `"api": 5`.

## Compatibility

| Document | Device accepts | Device returns | App reads |
| --- | --- | --- | --- |
| v4 – packs with `entries` | yes (migrated) | – | yes (import) |
| v5 – packs with `modules` | yes | yes | yes |

* The device always **writes** version 5. A v4 file in flash is migrated in
  memory when it is loaded and rewritten as v5 on the next `POST /schedule`.
* v1–v3 documents (flat `entries`, `groups`, `overrides`, `exceptions`) are no
  longer accepted. Firmware 1.3.0 already migrated them to v4.
* The app always **writes** version 5 and refuses to send a schedule to a
  device whose `/status` reports `api < 5`.

## Endpoints

Unchanged: `POST /ring {sec}`, `POST /stop`, `GET/POST /mode`,
`GET/POST /settings`, optional header `X-Token` (or `?token=`), CORS preflight
via `OPTIONS`.

| Endpoint | Purpose |
| --- | --- |
| `GET /status` | device state, next bell, last time sync |
| `POST /time {datetime}` | set the clock; also stored as the last sync time |
| `GET /schedule` | returns the v5 document with limits |
| `POST /schedule` | accepts v5, and v4 for compatibility |
| `GET /schedule/day?date=YYYY-MM-DD` | resolved bell list for one date |
| `POST /reset` | factory reset, optionally including the network |
| `GET /network` | access point name and whether a password / token is set |
| `POST /network` | change the access point name, password and API token |

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
  "fw": "2.0.0",
  "api": 5,
  "ssid": "BellTimer"
}
```

* `time` is `null` while the clock is not valid; `today_bells` is then absent.
* `synced` is the local time of the last `POST /time`, or `null`.
* `next` is the first bell after the current minute within 8 days (today
  included), resolved like `/schedule/day`, or `null`. It is computed once per
  minute and after every schedule change.

### `POST /time`

```json
{"datetime": "2026-10-23T09:03:41"}
```

Sets the DS3231 to the phone's local time. The app waits for the phone clock
to cross a whole second before sending, so the device ends up within network
latency of the phone. The minute in which the time was set never rings.

### `POST /reset`

Factory reset. Stops a running bell, deletes `/schedule.json` and
`/settings.json` and returns to the defaults: empty schedule, `auto` mode,
`ring_default` 5, no `synced`. The clock is kept (it lives in the DS3231).

With the body `{"network": true}` it also deletes `/network.json`, so the
access point name, password and token return to the build defaults from
`.env`; the access point then restarts 1.5 s after the response.

Returns `{"ok": true, "restart": false}`, or `500 storage_error` when a file
could not be deleted.

### `GET /network` and `POST /network`

```json
{"ssid": "BellTimer", "channel": 6, "open": false, "token_set": true}
```

The password and the token are never returned.

```json
{"ssid": "School-Bell", "password": "new-secret", "token": "abc123"}
```

Every field is optional and only the fields present change:

* `ssid` – 1–32 bytes.
* `password` – 8–63 characters; `""` makes the network open.
* `token` – up to 64 printable ASCII characters without spaces; `""` turns
  authentication off. The new token is required from the next request on.

The values are stored in `/network.json` and survive a reboot; a missing or
damaged file falls back to the build defaults. When the name or the password
changes, the device answers `{"ok": true, "ssid": "...", "restart": true}` and
restarts the access point 1.5 s later, so every client has to join the new
network. Errors: `invalid_ssid`, `invalid_password`, `invalid_token`,
`500 storage_error`.

### `GET /schedule/day?date=YYYY-MM-DD`

```json
{
  "date": "2026-11-02",
  "bells": [
    {"time": "07:58", "label": "1 урок", "type": "lesson", "role": "pre",   "pack": 1},
    {"time": "08:00", "label": "1 урок", "type": "lesson", "role": "start", "pack": 1},
    {"time": "08:45", "label": "1 урок", "type": "lesson", "role": "end",   "pack": 1},
    {"time": "12:30", "label": "Обед",   "type": "bell",   "role": "bell",  "pack": 1}
  ]
}
```

Bells are sorted by time and already merged: a minute appears once.

## Schedule document, version 5

```json
{
  "version": 5,
  "packs": [
    {
      "id": 1,
      "name": "Будни",
      "kind": "base",
      "enabled": true,
      "from": "2026-09-01",
      "to": "2027-05-31",
      "modules": [
        {"type": "lesson", "time": "08:00", "length": 45, "pre": 2,
         "days": [1, 2, 3, 4, 5], "label": "1 урок"},
        {"type": "bell", "time": "12:30", "days": [1, 2, 3, 4, 5], "label": "Обед"},
        {"type": "series", "time": "14:00", "until": "18:00", "every": 60,
         "days": [1, 2, 3, 4, 5, 6, 7], "label": "Смена"}
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
| `series` | `time`, `until` (`HH:MM`, after `time`), `every` (1–720 min) | `time`, `time + every`, … while `≤ until` (role `step`) |

Every module also has `days` (ISO weekdays, `1` = Monday … `7` = Sunday;
missing or empty = every day) and an optional `label` (truncated to 32 UTF-8
bytes). A missing `type` means `bell`. A lesson must end by 23:59 and its
warning bell may not fall before midnight. Unused fields of a type are ignored.

### Packs

* `packs` – ordered list, top to bottom. The order matters for corrections.
* `kind` – `"base"` or `"correction"`, default `"base"`.
* `enabled` – `false` removes the pack from evaluation. Default `true`.
* `mute` – corrections only: the pack is a holiday. It cancels the base packs
  above it on its dates and has no modules of its own. `mute` on a base pack is
  rejected.
* `name` – optional, truncated to 32 UTF-8 bytes.
* `from` / `to` – optional inclusive date range. For a non-yearly pack `to` may
  not be before `from`.
* `dates` – optional individual dates. A pack applies when the date matches
  the range **or** one of the dates; with neither, it applies every day.
* `yearly` – compare `from`, `to` and `dates` by month and day only, so a range
  may wrap over New Year.
* `auto` – optional global restriction; a missing field means "no limit".

The ring duration is not part of the schedule: every bell rings for the device
setting `ring_default`. A manual ring that is already longer is not shortened.

### Limits

| Limit | Value | Constant (firmware / app) |
| --- | --- | --- |
| Packs | 6 | `MAX_PACKS` |
| Modules per pack | 32 | `MAX_PACK_MODULES` |
| Modules in total | 192 | `MAX_MODULES` |
| Individual dates per pack | 24 | `MAX_PACK_DATES` |
| Label / pack name | 32 UTF-8 bytes | `MAX_LABEL_BYTES` / `MAX_PACK_NAME_BYTES` |
| Request body | 32 KiB | `MAX_SCHEDULE_BODY_BYTES` |

Reference build (`esp32:esp32:esp32`, firmware 2.0.0): 1 029 416 bytes of
program storage (78 %) and 65 808 bytes of static RAM (20 %).

## Evaluation

For a date:

1. Outside `auto.start` / `auto.stop` nothing rings.
2. Walk the packs top to bottom. Skip a pack that is disabled or whose window
   does not match the date.
3. A **base** pack adds the bells of its modules whose `days` contain the
   weekday.
4. A **correction** applies when it is muted or has a module for the weekday.
   It first drops every bell contributed by base packs so far (bells from
   earlier corrections stay), then adds its own bells unless muted.
5. Base packs below a correction are evaluated normally.
6. Bells that share a minute ring once; the module that claimed the minute
   first owns it (its label and role are reported).

The device evaluates today's map at the start of each minute and rings when
the current minute holds a bell, only in `auto` mode and with a valid clock.
A bell still fires within the first 5 seconds of its minute if the loop was
busy at second 0, never twice, and never in the minute the device booted or
the time was set.

This mirrors `BellSchedule.resolveDay(date)` in
`android/app/src/main/java/com/belltimer/app/data/Schedule.kt`.

## Migration from v4

* Packs keep their id, name, kind, window and `mute`.
* An entry with a `pair` becomes a `lesson` from the earlier to the later time
  (when they are at most 720 minutes apart); any other entry becomes a `bell`.

## Error codes

`{"ok": false, "error": "<code>"}` with HTTP 400 unless noted.

| Code | Meaning |
| --- | --- |
| `invalid_schedule` | Missing `packs`, invalid field, unknown module type, duplicate pack id, invalid range, `mute` on a base pack |
| `invalid_json` | Body missing or not parseable |
| `too_many_packs` | More than 6 packs |
| `too_many_pack_modules` | More than 32 modules in one pack |
| `too_many_modules` | More than 192 modules in total, or a body larger than 32 KiB |
| `too_many_pack_dates` | More than 24 individual dates in one pack |
| `invalid_date` | `/schedule/day` without a valid `date` |
| `invalid_sec`, `invalid_mode`, `invalid_datetime`, `invalid_ring_default` | unchanged |
| `invalid_ssid`, `invalid_password`, `invalid_token` | `POST /network` values out of range |
| `invalid_request` | `POST /reset` with a malformed body |
| `storage_error` | 500, flash write failed |
| `rtc_not_found` | 503, `/time` without a DS3231 |
| `unauthorized` | 401, wrong or missing token |
| `not_found` | 404, unknown path |

## Storage

* `/schedule.json` holds the v5 document, written with `writeFileAtomic()`
  (temp file, then rename). A failed save keeps the previous schedule.
* `/settings.json` holds `ring_default`, `mode` and `synced`.
* `/network.json` holds `ssid`, `password` and `token` once they were changed
  from the app; without it the build defaults from `.env` apply.
* `POST /schedule` validates into scratch buffers and only then commits, so a
  rejected document never touches the live schedule.
