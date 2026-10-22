# BellTimer HTTP API, schedule version 3

This document is the contract between the Android app (`android/`, package
`com.belltimer.app`) and the device firmware (`firmware/BellTimer/BellTimer.ino`).
It describes the schedule format the app writes, the rules the device must use to
decide what rings, and what changed compared with version 2.

Firmware `1.2.0` implements everything below and reports `"api": 3`.

## Compatibility

| Document | Device accepts | Device returns | App reads |
| --- | --- | --- | --- |
| v1 – flat `entries` array | yes (legacy) | – | yes |
| v2 – `groups` + `exceptions` | yes (migrated in memory) | – | yes |
| v3 – `groups` + `overrides` | yes | yes | yes |

* The device always **writes** version 3. A v1/v2 file in flash is migrated on
  the next successful `POST /schedule`.
* The app always **writes** version 3 and refuses to send overrides to a device
  whose `/status` reports `api < 3` (see "App behaviour without v3").

## Endpoints

Unchanged: `GET /status`, `POST /ring {sec}`, `POST /stop`, `GET/POST /mode`,
`POST /time {datetime}`, `GET/POST /settings`, optional header `X-Token`
(or `?token=`), CORS preflight via `OPTIONS`.

New or extended:

| Endpoint | Purpose |
| --- | --- |
| `GET /status` | adds `api` and `overrides` |
| `GET /schedule` | returns the v3 document |
| `POST /schedule` | accepts v3, and v2/v1 for compatibility |
| `GET /schedule/day?date=YYYY-MM-DD` | resolved bell list for one date (optional, implemented) |

### `GET /status`

```json
{
  "time": "2026-10-22T14:05:07",
  "rtc_ok": true,
  "mode": "auto",
  "ringing": false,
  "ring_left": 0,
  "ring_default": 5,
  "entries": 14,
  "groups": 2,
  "overrides": 3,
  "auto": { "start": "2026-09-01", "stop": "2027-05-31" },
  "errors": [],
  "fw": "1.2.0",
  "api": 3
}
```

`api` is an integer API level. A device that omits it is treated as level 2.
`next_bell` (`{"at": "...", "sec": 5}` or `null`) is **not** implemented: the app
computes the next bell from `bellsFor()` and the device clock in `/status.time`.

### `GET /schedule/day?date=YYYY-MM-DD` (optional)

```json
{
  "date": "2026-11-13",
  "bells": [
    {"time": "08:00", "duration": 5, "label": "Урок 1 — начало"},
    {"time": "12:30", "duration": 10, "label": "Доп. звонок"}
  ]
}
```

The list is already resolved (auto window, group, override) and merged: two bells
on the same `HH:MM` appear once with the longer duration. Invalid dates return
`400 {"ok": false, "error": "invalid_date"}`. The app does not call this endpoint;
it exists so a human can verify that the device and the app agree.

## Schedule document, version 3

```json
{
  "version": 3,
  "groups": [
    {
      "id": 1,
      "name": "Будни",
      "days": [1, 2, 3, 4, 5],
      "entries": [
        {"time": "08:00", "duration": 5, "label": "Урок 1 — начало"},
        {"time": "08:45", "duration": 5, "label": "Урок 1 — конец"}
      ]
    }
  ],
  "overrides": [
    {
      "date": "2026-11-07",
      "mode": "replace",
      "based_on": 1,
      "entries": [{"time": "09:00", "duration": 5, "label": "Урок 1 — начало"}]
    },
    {"date": "2026-11-04", "mode": "mute"},
    {
      "date": "2026-11-13",
      "mode": "add",
      "entries": [{"time": "12:30", "duration": 10, "label": "Доп. звонок"}]
    }
  ],
  "auto": {"start": "2026-09-01", "stop": "2027-05-31"}
}
```

Field notes:

* `groups[].days` – ISO weekdays, `1` = Monday … `7` = Sunday. Must contain at
  least one day; `[]` is rejected with `invalid_schedule`.
* `groups[].entries[].days` – optional and legacy. When present it narrows the
  bell to those weekdays *inside* the group. The app always writes the group's
  weekdays here, so it is a no-op for app-authored schedules.
* `entries[].time` – `HH:MM`, 24h, `00:00`–`23:59`.
* `entries[].duration` – integer seconds, `1`–`60`.
* `entries[].label` – optional, truncated to 32 UTF-8 bytes on the device.
* `overrides[].date` – unique `YYYY-MM-DD`; duplicate dates are rejected.
* `overrides[].mode` – `"mute"`, `"replace"` or `"add"`. Required.
* `overrides[].entries` – ignored for `mute`. For `replace` it is the complete
  bell list of that date (an empty array means "no bells"). For `add` it holds
  only the extra bells. Weekdays inside an override are ignored.
* `overrides[].based_on` – metadata only. It is stored and returned unchanged but
  **never** used to evaluate anything, and the referenced group may not exist.
* `auto` – optional; a missing field means "no limit".
* `entries` at the top level – legacy read-only view of the weekly bells, still
  returned by `GET /schedule` for older clients. Ignored on input when `groups`
  is present.

## Evaluation

Per minute tick, at second `00`:

1. If `auto.start` / `auto.stop` exist and today is outside that range → nothing
   rings.
2. Find the override for today. If its mode is `mute` → nothing rings.
3. `base` = bells of the **first** group whose `days` contains today's weekday
   (respecting the optional per-entry `days`), or empty when no group matches.
4. `replace` → only the override's bells. `add` → `base` plus the override's
   bells. No override → `base`.
5. Two bells on the same `HH:MM` ring once with the larger `duration`.
6. A bell fires only in `auto` mode, exactly once even if the tick is late, and
   never twice after an RTC time change.

This is identical to `BellSchedule.bellsFor(date)` in
`android/app/src/main/java/com/belltimer/app/data/Models.kt`; the unit tests in
`android/app/src/test/` pin that behaviour.

## Limits and storage

| Limit | Value | Constant (firmware / app) |
| --- | --- | --- |
| Bells in total (weekly + all overrides) | 256 | `MAX_ENTRIES` / `MAX_SCHEDULE_ENTRIES` |
| Weekly schedules | 12 | `MAX_GROUPS` / `MAX_SCHEDULE_GROUPS` |
| Per-date overrides | 90 | `MAX_OVERRIDES` / `MAX_DATE_OVERRIDES` |
| Label | 32 UTF-8 bytes | `MAX_LABEL_BYTES` |
| Group name | 32 UTF-8 bytes | `MAX_GROUP_NAME_BYTES` |
| Request body | 16 KiB | `MAX_SCHEDULE_BODY_BYTES` |

Measured on the reference build (`esp32:esp32:esp32`, firmware 1.2.0): 1 030 640
bytes of program storage (78 %) and 66 408 bytes of static RAM (20 %), leaving
about 255 KiB of heap. The entry table plus its parse scratch buffer is roughly
13 KiB of that; do not raise `MAX_ENTRIES` to 384 without re-measuring, because
every entry holds a `String` label on the heap.

Storage rules:

* The schedule lives in `LittleFS` at `/schedule.json` and is written with
  `writeFileAtomic()` (temp file, then rename). A failed save keeps the previous
  schedule and returns `500 storage_error`.
* `POST /schedule` validates into scratch buffers and only then persists, so a
  rejected document never touches the live schedule.
* Overrides older than 30 days are dropped on save **when the clock is valid**.
  Their entries are removed too and the remaining override slots are renumbered.
* Request bodies larger than `MAX_SCHEDULE_BODY_BYTES` are rejected with
  `too_many_entries` before ArduinoJson allocates anything; parsing also uses
  `NestingLimit(8)`.

## Error codes

`{"ok": false, "error": "<code>"}` with HTTP 400 unless noted.

| Code | Meaning |
| --- | --- |
| `invalid_schedule` | Any invalid field, duplicate override date, both `overrides` and `exceptions` present, empty group days |
| `invalid_json` | Body missing or not parseable |
| `too_many_entries` | More than 256 bells, or a body larger than 16 KiB |
| `too_many_groups` | More than 12 schedules |
| `too_many_overrides` | More than 90 overrides |
| `too_many_exceptions` | v2 `exceptions` array larger than 90 (kept for old clients) |
| `storage_error` | 500, flash write failed |
| `unauthorized` | 401, wrong or missing token |
| `invalid_date` | `/schedule/day` without a valid `date` |
| `invalid_sec`, `invalid_mode`, `invalid_datetime`, `invalid_ring_default`, `invalid_request` | unchanged |
| `not_found` | 404, unknown path |

## Migration from version 2

When a stored or uploaded v2 document is read:

* `exception { "date": D, "mute": true }` → `override { date: D, mode: "mute" }`.
* `exception { "date": D, "group_id": X }` → `override { date: D, mode:
  "replace", based_on: X, entries: <copy of group X's bells at that moment> }`.
  The copy counts towards the 256-bell limit.
* A v1 document (flat `entries`) becomes one group with `days: [1..7]` and the
  per-entry weekday masks preserved.
* The next save rewrites `/schedule.json` as version 3, so `exceptions` never
  reappears in responses.

A document that contains both `overrides` and `exceptions` is rejected.

## Examples

```bash
# current document
curl -s http://192.168.4.1/schedule | jq .

# replace the schedule with one weekly schedule and three special days
curl -s -X POST http://192.168.4.1/schedule \
  -H 'Content-Type: application/json' \
  -d '{
    "version": 3,
    "groups": [{"id": 1, "name": "Будни", "days": [1,2,3,4,5],
                "entries": [{"time": "08:00", "duration": 5, "label": "Урок 1"}]}],
    "overrides": [
      {"date": "2026-11-04", "mode": "mute"},
      {"date": "2026-11-07", "mode": "replace", "based_on": 1,
       "entries": [{"time": "10:00", "duration": 5, "label": "Суббота"}]},
      {"date": "2026-11-13", "mode": "add",
       "entries": [{"time": "12:30", "duration": 10, "label": "Доп. звонок"}]}
    ]
  }'

# what rings on a specific date
curl -s 'http://192.168.4.1/schedule/day?date=2026-11-07' | jq .
```

## App behaviour without v3

`StatusDto.api` defaults to `2` when the field is missing. With `api < 3` the app

* keeps weekly editing, import/export, manual ring, mode and time sync,
* disables "Особые дни" and shows "Нужно обновить прошивку устройства",
* refuses to send a schedule that contains overrides
  (`ScheduleIssue.FirmwareTooOld`).

## Device checklist

1. `GET /status` returns `"api": 3`, `"fw": "1.2.0"`.
2. `POST /schedule` with the v3 example above returns `200` and
   `{"ok": true, "entries": 4, "groups": 1, "overrides": 3}`.
3. `GET /schedule/day?date=<mute date>` returns `"bells": []`.
4. `GET /schedule/day?date=<replace date>` returns only the override bells.
5. `GET /schedule/day?date=<add date>` returns the weekday bells plus the extra one.
6. Two bells on the same minute ring once with the longer duration.
7. A v2 document posts successfully and `GET /schedule` returns `"version": 3`
   with `mode`/`based_on` instead of `mute`/`group_id`.
8. 91 overrides → `400 too_many_overrides`; 257 bells → `400 too_many_entries`.
9. Power-cycle: the schedule, overrides and `auto` survive.
10. `scripts/api-test.sh` passes against the device.
