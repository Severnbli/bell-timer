# Bell timer

The ESP32 sketch in `firmware/BellTimer/` controls the bell. The Kotlin and
Compose app in `android/` talks to it over HTTP on the device's access point.

- Read [Building from source](README.md#building-from-source) for toolchain
  setup, local configuration and device testing. Read
  [Releases](README.md#releases) when changing packaging or version metadata.
- Read the [HTTP contract](docs/firmware-api.md) before changing endpoints,
  schedule behavior, storage or app–firmware compatibility.
- Follow the [firmware guide](firmware/AGENTS.md) for sketch, firmware script
  and firmware release changes. Follow the [Android guide](android/AGENTS.md)
  for app and Android release changes.

## Shared contract

- Keep the pack, module, date, range and label limits and the valid output
  pins in sync across `BellTimer.ino`, the app's `data/Schedule.kt` and
  `data/Models.kt`, and `docs/firmware-api.md`. Keep existing limits or raise
  them so stored schedules continue to load.
- Keep `collectDayBells()` and `BellSchedule.resolveDay()` in agreement.
- Gate new app features on `status.api` and bump `API_VERSION` when the HTTP
  contract changes. Raise `MIN_APP_VERSION` when firmware requires a newer app
  and `MIN_FIRMWARE_VERSION` when the app drops support for older firmware.
- Keep new schedule fields compatible with older readers, or block sending to
  those readers. A single range keeps the v5 `from`/`to` shape.

## Git and releases

- Work on `dev`. Run the relevant checks and commit each completed step
  separately. Target `main` when a pull request is requested.
- Run release workflows only when requested. `firmware-release.yml` creates
  `firmware-v*` tags and `android-release.yml` creates `v*` tags.
- Use en dashes in place of em dashes in code, docs, strings and commits.
