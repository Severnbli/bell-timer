#!/usr/bin/env bash
# Compiles the SchoolBell ESP32 firmware with the ESP32 Arduino core 3.x.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SKETCH_DIR="${REPO_ROOT}/firmware/SchoolBell"
FQBN="${FQBN:-esp32:esp32:esp32}"

# Loads .env and renders firmware/SchoolBell/app_config.h.
CONFIG_SCRIPT="${SCRIPT_DIR}/gen-config.sh"

if [[ ! -x "${CONFIG_SCRIPT}" ]]; then
    echo "ERROR: ${CONFIG_SCRIPT} is missing or not executable." >&2
    exit 1
fi

if ! command -v arduino-cli >/dev/null 2>&1; then
    echo "ERROR: arduino-cli is not installed or not on PATH." >&2
    echo "Install it from https://arduino.github.io/arduino-cli/ and rerun this script." >&2
    exit 1
fi

if ! arduino-cli core list | awk '{print $1}' | grep -qx "esp32:esp32"; then
    echo "ERROR: the esp32:esp32 core is not installed." >&2
    echo "Run: arduino-cli core install esp32:esp32" >&2
    exit 1
fi

if ! arduino-cli lib list | awk '{print $1}' | grep -qx "RTClib"; then
    echo "ERROR: the RTClib library is not installed." >&2
    echo "Run: arduino-cli lib install RTClib" >&2
    exit 1
fi

if ! arduino-cli lib list | awk '{print $1}' | grep -qx "ArduinoJson"; then
    echo "ERROR: the ArduinoJson library is not installed." >&2
    echo "Run: arduino-cli lib install ArduinoJson" >&2
    exit 1
fi

# Load .env and write firmware/SchoolBell/app_config.h before compiling.
"${CONFIG_SCRIPT}"

echo "Compiling ${SKETCH_DIR} for ${FQBN}..."
arduino-cli compile --fqbn "${FQBN}" "${SKETCH_DIR}" "$@"
echo "Firmware build finished successfully."
