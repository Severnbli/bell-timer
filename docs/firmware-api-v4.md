# BellTimer HTTP API, schedule version 4 (packs)

This document is the contract between the Android app (`android/`, package
`com.belltimer.app`) and the device firmware (`firmware/BellTimer/BellTimer.ino`).
It describes the pack-based schedule the app writes, how the device resolves a
date, and how older documents are migrated.

Firmware `1.3.0` implements everything below and reports `"api": 4`.

## Compatibility

| Document | Device accepts | Device returns | App reads |
| --- | --- | --- | --- |
| v1 – flat `entries` array | yes (migrated) | – | yes |
| v2 – `groups` + `exceptions` | yes (migrated) | – | yes |
| v3 – `groups` + `overrides` | yes (migrated) | – | yes |
| v4 – `packs` | yes | yes | yes |

* The device always **writes** version 4. A v1/v2/v3 file in flash is migrated
  in memory when it is loaded and is rewritten as v4 on the next successful
  `POST /schedule`.
* The app always **writes** version 4 and refuses to send a non-empty schedule
  to a device whose `/status` reports `api < 4`.

## Endpoints

Unchanged: `GET /status`, `POST /ring {sec}`, `POST /stop`, `GET/POST /mode`,
`POST /time {datetime}`, `GET/POST /settings`, optional header `X-Token`
(or `?token=`), CORS preflight via `OPTIONS`.

| Endpoint | Purpose |
| --- | --- |
| `GET /status` | adds `api` and `packs` |
| `GET /schedule` | returns the v4 document |
| `POST /schedule` | accepts v4, and v1/v2/v3 for compatibility |
| `GET /schedule/day?date=YYYY-MM-DD` | resolved bell list for one date |

### `GET /status`

```json
{
  "time": "2026-10-23T14:05:07",
  "rtc_ok": true,
  "mode": "auto",
  "ringing": false,
  "ring_left": 0,
  "ring_default": 5,
  "entries": 14,
  "packs": 3,
  "auto": { "start": "2026-09-01", "stop": "2027-05-31" },
  "errors": [],
  "fw": "1.3.0",
  "api": 4
}
```

`next_bell` is not implemented: the app computes the next bell from
`bellsFor()` and the device clock in `/status.time`.

### `GET /schedule/day?date=YYYY-MM-DD`

```json
{
  "date": "2026-11-13",
  "bells": [
    {"time": "08:00", "duration": 5, "label": "Урок 1 — начало"},
    {"time": "12:30", "duration": 5, "label": "Доп. звонок"}
  ]
}
```

The list is already resolved (auto window, pack order, corrections) and merged:
bells sharing an `HH:MM` appear once. `duration` is always the current
`ring_default`, because individual entries no longer carry a duration. Invalid
dates return `400 {"ok": false, "error": "invalid_date"}`.

## Schedule document, version 4

```json
{
  "version": 4,
  "packs": [
    {
      "id": 1,
      "name": "Будни",
      "kind": "base",
      "enabled": true,
      "yearly": false,
      "from": "2026-09-01",
      "to": "2027-05-31",
      "dates": [],
      "entries": [
        {"time": "08:00", "pair": "08:45", "days": [1, 2, 3, 4, 5], "label": "Урок 1"}
      ]
    },
    {
      "id": 2,
      "name": "Коррекция на 7 ноября",
      "kind": "correction",
      "enabled": true,
      "yearly": true,
      "dates": ["2026-11-07"],
      "entries": [
        {"time": "10:00", "days": [6]}
      ]
    }
  ],
  "auto": {"start": "2026-09-01", "stop": "2027-05-31"}
}
```

Field notes:

* `packs` – ordered list, top to bottom. The order matters for corrections.
* `packs[].kind` – `"base"` or `"correction"`. Defaults to `"base"`.
* `packs[].enabled` – `false` removes the pack from evaluation. Default `true`.
* `packs[].name` – optional, truncated to 32 UTF-8 bytes.
* `packs[].from` / `packs[].to` – optional inclusive date range (`YYYY-MM-DD`).
  For a non-yearly pack `to` may not be before `from`.
* `packs[].dates` – optional list of individual dates. A pack applies when the
  date matches the range **or** one of the individual dates.
* `packs[].yearly` – when `true`, `from`, `to` and every entry in `dates` are
  compared by month and day, so a range can wrap over New Year.
* `packs[].mute` – legacy migration only. A correction with `"mute": true`
  cancels the base packs above it without adding bells. The app does not write
  this field for normal packs.
* `entries[].time` – `HH:MM`, 24h, `00:00`–`23:59`.
* `entries[].pair` – optional second `HH:MM` that obeys the same weekdays and
  label.
* `entries[].days` – ISO weekdays, `1` = Monday … `7` = Sunday. Required in
  practice; an omitted or empty list means "every day" when decoding.
* `entries[].label` – optional, truncated to 32 UTF-8 bytes.
* Entries have **no duration**. The ring duration is the device setting
  `ring_default`, which the app edits on its Settings screen.
* `auto` – optional global restriction; a missing field means "no limit".

### Limits

| Limit | Value | Constant (firmware / app) |
| --- | --- | --- |
| Packs | 12 | `MAX_PACKS` / `MAX_PACKS` |
| Entries per pack | 16 | `MAX_PACK_ENTRIES` / `MAX_PACK_ENTRIES` |
| Individual dates per pack | 24 | `MAX_PACK_DATES` / `MAX_PACK_DATES` |
| Entries in total | 192 | `MAX_ENTRIES` / `MAX_SCHEDULE_ENTRIES` |
| Label | 32 UTF-8 bytes | `MAX_LABEL_BYTES` |
| Pack name | 32 UTF-8 bytes | `MAX_PACK_NAME_BYTES` |
| Request body | 32 KiB | `MAX_SCHEDULE_BODY_BYTES` |

Measured on the reference build (`esp32:esp32:esp32`, firmware 1.3.0):
1 031 516 bytes of program storage (78 %) and 63 808 bytes of static RAM
(19 %).

## Evaluation

Per minute tick, at second `00`:

1. If `auto.start` / `auto.stop` exist and today is outside that range → nothing
   rings.
2. Walk the packs top to bottom. Skip a pack when `enabled` is `false` or the
   date does not match its `from`/`to`/`dates` rules.
3. A **base** pack adds every entry whose `days` contains today's weekday.
4. A **correction** applies when `mute` is `true` or it has at least one entry
   for today's weekday. When it applies, it first discards all base bells
   collected from packs above it, then adds its own entries (unless muted).
   Corrections do not discard bells contributed by earlier corrections.
5. Base packs below a correction are evaluated normally, so they can add bells
   again.
6. Two bells on the same `HH:MM` ring once, for the current `ring_default`.
7. A bell fires only in `auto` mode, exactly once even if the tick is late, and
   never twice after an RTC time change.

This mirrors `BellSchedule.bellsFor(date)` in
`android/app/src/main/java/com/belltimer/app/data/Models.kt`.

## Migration from v1/v2/v3

* v1 flat `entries` → one all-week base pack named "Расписание".
* v2/v3 `groups` → base packs. Effective entry weekdays are
  `group.days ∩ entry.days` when the entry lists its own days.
* v3 `add` override → a base pack limited to that date.
* v3 `replace` override → a correction limited to that date.
* v3 `mute` override → a muted correction limited to that date.
* v2 `mute` exception → a muted correction.
* v2 `group_id` exception → a correction with a copy of that group's entries.
* Migrated base packs are placed before all corrections so every correction
  cancels every migrated base pack on its date.

Because a v4 schedule can hold only 12 packs, a legacy document with more than
12 packs cannot be migrated completely. The device rejects such an upload with
`too_many_packs`; stored data that cannot be migrated is ignored on boot.

## Error codes

`{"ok": false, "error": "<code>"}` with HTTP 400 unless noted.

| Code | Meaning |
| --- | --- |
| `invalid_schedule` | Any invalid field, duplicate pack id, invalid date range, `mute` on a base pack |
| `invalid_json` | Body missing or not parseable |
| `too_many_entries` | More than 192 entries in total, or a body larger than 32 KiB |
| `too_many_packs` | More than 12 packs |
| `too_many_pack_entries` | More than 16 entries in one pack |
| `too_many_pack_dates` | More than 24 individual dates in one pack |
| `too_many_groups`, `too_many_overrides`, `too_many_exceptions` | Kept for clients that still send v1/v2/v3 documents |
| `storage_error` | 500, flash write failed |
| `unauthorized` | 401, wrong or missing token |
| `invalid_date` | `/schedule/day` without a valid `date` |
| `invalid_sec`, `invalid_mode`, `invalid_datetime`, `invalid_ring_default`, `invalid_request` | unchanged |
| `not_found` | 404, unknown path |

## Storage

* The schedule lives in `LittleFS` at `/schedule.json` and is written with
  `writeFileAtomic()` (temp file, then rename). A failed save keeps the previous
  schedule and returns `500 storage_error`.
* `POST /schedule` validates into scratch buffers and only then persists, so a
  rejected document never touches the live schedule.
* Request bodies larger than `MAX_SCHEDULE_BODY_BYTES` are rejected before
  ArduinoJson allocates anything; parsing also uses `NestingLimit(8)`.
