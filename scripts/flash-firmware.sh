#!/usr/bin/env bash
# Builds the firmware, uploads it to the ESP32 and then restores the generated
# firmware/SchoolBell/app_config.h to the default settings from .env.example.
#
# Usage:
#   scripts/flash-firmware.sh [--port PORT] [--no-reset] [compile-args...]
#
# Environment overrides:
#   PORT           serial port, e.g. /dev/ttyUSB0 (auto-detected when omitted)
#   FQBN           board, default esp32:esp32:esp32
#   CONFIG_HEADER  generated header to write and reset
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SKETCH_DIR="${REPO_ROOT}/firmware/SchoolBell"
GEN_CONFIG="${SCRIPT_DIR}/gen-config.sh"
ENV_EXAMPLE="${REPO_ROOT}/.env.example"
FQBN="${FQBN:-esp32:esp32:esp32}"
CONFIG_HEADER="${CONFIG_HEADER:-${SKETCH_DIR}/app_config.h}"
PORT="${PORT:-}"
RESET_AFTER=1
COMPILE_ARGS=()

usage() {
    cat <<'EOF'
Builds the firmware, uploads it to the ESP32 and then restores
firmware/SchoolBell/app_config.h to the defaults from .env.example.

Usage:
  scripts/flash-firmware.sh [--port PORT] [--no-reset] [compile-args...]

Options:
  -p, --port PORT  serial port (default: PORT environment variable or
                   arduino-cli auto-detection)
      --no-reset   keep the generated app_config.h with your .env values
  -h, --help       show this help

Extra arguments are forwarded to arduino-cli compile.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h | --help)
            usage
            exit 0
            ;;
        -p | --port)
            [[ $# -ge 2 ]] || fail "--port requires a value"
            PORT="$2"
            shift 2
            ;;
        --no-reset)
            RESET_AFTER=0
            shift
            ;;
        *)
            COMPILE_ARGS+=("$1")
            shift
            ;;
    esac
done

reset_config() {
    local status=$?
    if [[ "${RESET_AFTER}" -eq 1 ]]; then
        ENV_FILE="${ENV_EXAMPLE}" CONFIG_HEADER="${CONFIG_HEADER}" \
            "${GEN_CONFIG}" >/dev/null
        echo "Reset ${CONFIG_HEADER#"${REPO_ROOT}"/} to defaults from .env.example."
    fi
    return "${status}"
}

# Picks the serial port when --port/PORT is omitted. A single ESP32 wins over a
# single other board; anything ambiguous asks for an explicit --port.
detect_port() {
    local line port
    local -a ports=() esp_ports=()

    while IFS= read -r line; do
        read -r port _ <<< "${line}"
        [[ "${port}" == /dev/* ]] || continue
        case " ${ports[*]-} " in
            *" ${port} "*) continue ;;
        esac
        ports+=("${port}")
        if [[ "${line,,}" == *esp32* ]]; then
            esp_ports+=("${port}")
        fi
    done < <(arduino-cli board list 2>/dev/null | tail -n +2)

    if (( ${#esp_ports[@]} == 1 )); then
        printf '%s\n' "${esp_ports[0]}"
        return 0
    fi
    if (( ${#ports[@]} == 1 )); then
        printf '%s\n' "${ports[0]}"
        return 0
    fi
    if (( ${#ports[@]} == 0 )); then
        echo "ERROR: no serial port found. Connect the ESP32 and retry." >&2
    else
        echo "ERROR: several boards found (${ports[*]}); pass --port." >&2
    fi
    return 1
}

if [[ -z "${PORT}" ]]; then
    PORT="$(detect_port)" || exit 1
fi
trap reset_config EXIT

"${SCRIPT_DIR}/build-firmware.sh" "${COMPILE_ARGS[@]}"

UPLOAD_ARGS=(--fqbn "${FQBN}" -p "${PORT}")

echo "Uploading firmware to ${PORT}..."
arduino-cli upload "${UPLOAD_ARGS[@]}" "${SKETCH_DIR}"
echo "Firmware flashed successfully."
