# Changelog

## Unreleased — schedule version 5 (modules), firmware 2.0.0

### Schema: v4 → v5 (firmware and app)

* Packs hold **modules** instead of raw entries: `bell` (one bell), `lesson`
  (start and end bell, optional warning bell `pre` minutes before) and `series`
  (a bell every `every` minutes from `time` to `until`).
* A correction with `"mute": true` is a first-class **holiday**: it cancels the
  base packs above it on its dates.
* Limits: 6 packs, 32 modules per pack, 192 modules, 24 dates per pack. New
  error codes `too_many_pack_modules` and `too_many_modules`.
* v4 documents are migrated (an entry with a `pair` becomes a lesson); v1–v3
  are no longer accepted.

### Firmware 2.0.0 (`api: 5`)

* A day is resolved into a per-minute map, so overlapping bells merge for free
  and `/schedule/day` reports the module type and role of each bell.
* `/status` reports the `next` bell, `today_bells` and `synced` (the last
  `POST /time`, stored in flash).
* A bell still fires within 5 s of its minute if the loop was busy at second 0;
  a scheduled bell extends a shorter manual ring instead of being skipped.
* The built-in HTML page was redesigned and can sync the time from the browser.

### App

* New look: warm "brass and ink" palette (light and dark), bottom navigation
  with **Сейчас / Расписание / Настройки**.
* **Сейчас**: device clock with live seconds, drift against the phone, a
  "Синхронизировать с телефоном" button (aligned to the phone's second), the
  running lesson with a progress bar, the next bell from the device, a large
  ring button, mode switch and today's bells.
* **Расписание**: week strip with a month calendar, colour-coded day kinds,
  the resolved agenda of the selected day and pack cards with module counts.
* **Pack editor**: kind, holiday switch, validity window, module cards, a
  module sheet per type with a live preview of the bells, "Сетка уроков" with
  a long break, shift-all and duplicate.
* Automatic time sync can be turned off in the settings.


## Unreleased — schedule version 4 (packs)

### Schema: v3 → v4 (firmware and app)

* **Packs** (`пачки`) replace `groups` + `overrides`. A pack is a named,
  ordered bundle of entries with `kind: "base" | "correction"`, an `enabled`
  switch, an optional `from`/`to` range, individual `dates` and a `yearly`
  repeat flag.
* A base pack adds its entries. A correction cancels every base pack above it
  when it has entries for the current weekday, then adds its own. Base packs
  below a correction still ring.
* **Entries** now carry `time`, optional `pair`, `days` and `label`. The
  per-entry `duration` is gone: the ring duration is the device setting edited
  on the Settings screen only.
* Limits: 6 packs, 32 entries per pack, 24 individual dates per pack and 192
  entries in total.
* New firmware (`1.3.0`, `api: 4`) migrates v1/v2/v3 documents in memory and
  rewrites storage as v4. New error codes `too_many_packs`,
  `too_many_pack_entries` and `too_many_pack_dates`.
* `GET /status` returns `packs` instead of `groups`/`overrides`.

### App

* **Top bar** with the device time in the middle, the connection status on the
  left and the settings button on the right. The bottom navigation bar is
  removed.
* **Main menu** shows the mode switch, the next bell and the ring button. Time
  is synchronised automatically; the manual sync button is removed.
* **Schedule** opens with a swipe up as a bottom sheet, shows a month calendar
  (base days in green, correction days in red, past days unlit) and a
  drag-and-drop pack list.
* **Pack editor**: editable name, type switch, enable switch, validity range,
  individual dates, yearly repeat, entry list with swipe-to-delete, a quick
  "Очистить" action, builder and move-by-N-minutes.
* **Entry editor**: time, optional pair time, weekday chips and label. No
  duration control.
* The duration picker was removed from the control screen and both editors; it
  remains only on the Settings screen.
* Import/export and "Проверить дату" moved to the schedule overflow menu.
* Unit tests rewritten for pack resolution, codec migration and the builder.

### Docs

* `docs/firmware-api-v4.md` — contract, evaluation rules, limits, migration and
  error codes. `docs/firmware-api-v3.md` is superseded.

## Unreleased — schedule version 3

### Schema: v2 → v3 (firmware and app)

* `exceptions` (`mute` / `group_id`) is replaced by `overrides`. Each override is
  a bell list for one calendar date: `mode: "mute" | "replace" | "add"`, optional
  `entries`, optional `based_on` metadata.
* `ScheduleDto`/`ScheduleCodec` encode v3 and still decode v1 (flat `entries`) and
  v2 (`exceptions` → mute, or replace with a copy of the referenced schedule).
* New firmware (`1.2.0`, `api: 3`) accepts v2/v1 uploads and rewrites storage as
  v3; limits are now 256 bells, 12 schedules, **90** overrides and 32-byte labels.
* New error code `too_many_overrides`; `too_many_exceptions` is kept for older
  clients. New optional endpoint `GET /schedule/day?date=YYYY-MM-DD`.
* Weekly schedules no longer overlap in the app: each weekday belongs to one
  schedule.
* The schedule is resolved by one pure function, `BellSchedule.bellsFor(date)`,
  mirrored by the firmware; bells on the same minute ring once with the longer
  duration.

### App

* **Duration picker**: 1..10 s slider plus a "Своё" field for 1..60 s. The ten
  number chips and the 60-step slider are gone; one implementation is used on the
  control screen, in settings and in every bell editor.
* **Schedule tab** rebuilt: Неделя / Особые дни segmented switch, 7-cell week
  strip (tap a weekday to choose its schedule), plain schedule rows, day timeline,
  chronological list of special days grouped by month with past days collapsed.
* **Schedule editor** is a full screen with an editable name, weekday chips,
  swipe-to-delete bells with undo, entry sheet and move-by-N-minutes.
* **Builder** ("Собрать по урокам") replaced the generator: per-lesson table with
  individual breaks, presets (Школа 45, Школа 40, Колледж 45/5, Вуз 90), bell
  pattern, live preview and validation.
* **Day editor**: mute / own list (pre-filled from the schedule of that day) /
  extra bells, with "Повторить на другие даты".
* One "Отправить" action in a bottom bar, shown only when there are unsent
  changes; the device's bell count is verified after sending. Import/export and
  the auto-mode period moved into the overflow menu.
* GUI de-carded: no elevation, one accent colour, plain rows and dividers, tabular
  digits for times, no decorative icons or hint paragraphs.
* Unit tests for resolution, codec and builder (`./gradlew testDebugUnitTest`).

### Docs

* `docs/firmware-api-v3.md` — contract, evaluation rules, limits, migration,
  error codes and device checklist.
