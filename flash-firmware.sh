#!/usr/bin/env bash
# Convenience wrapper: build the firmware, flash the connected ESP32 and reset
# the generated app_config.h. Equivalent to scripts/flash-firmware.sh.
#
# Usage: ./flash-firmware.sh [--port PORT] [--no-reset] [compile-args...]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/scripts/flash-firmware.sh" "$@"
