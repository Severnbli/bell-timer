# Firmware

- Preserve LittleFS across updates. Keep `PartitionScheme=default` and keep
  reading older layouts of `/schedule.json`, `/settings.json`, `/network.json`
  and `/device.json`. Preserve the files on upgrade and default new fields to
  the old behavior. Bump a file's `"v"` only with a migration.
- Use `.env` build defaults to seed configuration on first boot. Once the
  files exist, use the stored values. `scripts/gen-config.sh` generates
  `BellTimer/app_config.h` during the build. Keep the header and `.env` out of Git.
- Keep CORS disabled. Use `requireToken()` and `requireJsonRequest()` for JSON
  POST handlers. `/update` takes a binary body and requires `X-Token` in the
  header. Compare tokens with `tokensEqual()`.
- Drive the relay off before making its pin an output and before long blocking
  work such as flash writes, updates and restarts.

After each firmware change, run from the repo root with the Arduino CLI core
and libraries installed as described in the README.

```bash
ENV_FILE=.env.example scripts/build-firmware.sh --warnings all
```

For live testing, use `./flash-firmware.sh` to build and upload over USB while
preserving LittleFS. Run `scripts/api-test.sh` against a test device on the
bell's network, with `X_TOKEN` set if required. The script rings the bell and
exercises the reset endpoint. Report when the hardware test was skipped.
