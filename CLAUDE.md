# CLAUDE.md

ESP32 bell controller (`firmware/BellTimer/BellTimer.ino`, one Arduino sketch)
plus an Android app (`android/`, Kotlin + Compose) that talks to it over HTTP
on the device's own access point. The HTTP contract is `docs/firmware-api.md`.

## Commands

```bash
scripts/build-firmware.sh --warnings all      # firmware build (needs core 3.3.12)
./flash-firmware.sh                           # build + USB flash, keeps LittleFS
scripts/api-test.sh                           # live test against 192.168.4.1
cd android && ./gradlew assembleDebug testDebugUnitTest lintDebug
```

Run the firmware build and the Gradle command after every change to the
respective side. `api-test.sh` needs real hardware, so say when it was not run.

## Rules that are easy to break

- **Settings must survive firmware updates.** Everything a user configures
  lives in LittleFS (`/schedule.json`, `/settings.json`, `/network.json`,
  `/device.json`). Never change the partition scheme (`PartitionScheme=default`),
  never delete or reformat these files on upgrade, and keep reading every older
  file layout. New fields need defaults that keep the old behaviour. Bump the
  `"v"` field only with a migration.
- **Build defaults are only for new devices.** `.env` values are seeded into
  the files on the first boot. Do not add code that reapplies compiled
  defaults to a device that already has its files.
- **Firmware and app limits are mirrored.** `MAX_PACKS`, `MAX_PACK_MODULES`,
  `MAX_PACK_DATES`, `MAX_PACK_RANGES`, the label limits and the valid output
  pins exist in both `BellTimer.ino` and `android/.../data/Schedule.kt` /
  `Models.kt`. Change both, plus `docs/firmware-api.md`. Never lower a limit:
  stored schedules above it would stop loading.
- **Schedule resolution exists twice.** `collectDayBells()` in the firmware and
  `BellSchedule.resolveDay()` in the app must give the same bells.
- **Version dependencies.** Raise `MIN_APP_VERSION` (firmware) when firmware
  needs a newer app, and `MIN_FIRMWARE_VERSION` (`data/Models.kt`) when the app
  drops support for older firmware. Gate new app features on `status.api` and
  bump `API_VERSION` when the HTTP contract changes.
- **Backwards-compatible wire format.** Write new schedule fields so older
  readers either understand them or are refused before sending (see how a
  single range keeps the v5 `from`/`to` shape).
- **Security stays on.** No CORS headers. POST handlers call
  `requireToken()` and `requireJsonRequest()`. `/update` requires a token in
  the header. Compare tokens with `tokensEqual()`.
- **Relay safety.** The relay must be off before its pin becomes an output,
  and before any long blocking operation (flash writes, updates, restarts).
- **Strings in three languages.** Every user-visible string goes into
  `values/` (Russian, default), `values-en/` and `values-be/`.
- **Signing key.** Release APKs are signed with the key in the repository
  secrets. Never change the key or the `applicationId`, or installed apps
  stop updating.

## Workflow

- Work on `dev`, commit in logical steps, open PRs to `main`.
- Releases are manual: `firmware-release.yml` (tags `firmware-v*`) and
  `android-release.yml` (tags `v*`). Do not trigger them unless asked.
- Use the en dash (–), never the em dash, in code, docs, strings and commits.
