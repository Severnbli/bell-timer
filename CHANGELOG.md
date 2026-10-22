# Changelog

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
