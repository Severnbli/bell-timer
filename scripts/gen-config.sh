#!/usr/bin/env bash
# Loads the firmware settings from .env and renders them into
# firmware/BellTimer/app_config.h for the sketch to include.
#
# Usage:
#   scripts/gen-config.sh [env-file]
#
# Environment overrides:
#   ENV_FILE       settings file to read (default: <repo>/.env)
#   CONFIG_HEADER  header to write
#                  (default: <repo>/firmware/BellTimer/app_config.h)
#
# The ESP32 cannot read files from the build machine, so the values are baked
# into a generated header at build time.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_EXAMPLE="${REPO_ROOT}/.env.example"
ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"
CONFIG_HEADER="${CONFIG_HEADER:-${REPO_ROOT}/firmware/BellTimer/app_config.h}"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

usage() {
    sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

case "${1-}" in
    -h | --help)
        usage
        exit 0
        ;;
    "") ;;
    *)
        ENV_FILE="$1"
        shift
        ;;
esac
[[ $# -eq 0 ]] || fail "unexpected argument '$1' (see --help)"

# Escape a value for use inside a C string literal.
c_escape() {
    local value="$1"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s' "${value}"
}

byte_len() {
    printf '%s' "$1" | wc -c | tr -d '[:space:]'
}

# Reads KEY=VALUE lines without executing the file. Values already present in
# the environment take precedence, which allows CI or one-off overrides.
load_env_file() {
    local file="$1" line key value
    while IFS= read -r line || [[ -n "${line}" ]]; do
        line="${line%$'\r'}"
        line="${line#"${line%%[![:space:]]*}"}"
        [[ -z "${line}" || "${line}" == \#* ]] && continue
        [[ "${line}" == export[[:space:]]* ]] && line="${line#export}"
        line="${line#"${line%%[![:space:]]*}"}"
        [[ "${line}" == *=* ]] || fail "${file}: expected KEY=VALUE, got '${line}'"
        key="${line%%=*}"
        key="${key%"${key##*[![:space:]]}"}"
        value="${line#*=}"
        value="${value#"${value%%[![:space:]]*}"}"
        if [[ ${#value} -ge 2 ]] &&
            { [[ "${value}" == \"*\" && "${value}" == *\" ]] ||
                [[ "${value}" == \'*\' && "${value}" == *\' ]]; }; then
            value="${value:1:${#value}-2}"
        else
            value="${value%"${value##*[![:space:]]}"}"
        fi
        [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "${file}: invalid key '${key}'"
        if [[ -z "${!key+x}" ]]; then
            printf -v "${key}" '%s' "${value}"
        fi
    done < "${file}"
}

bool_env() {
    local key="$1" value
    value="${2,,}"
    case "${value}" in
        true | 1 | yes | on) printf 'true' ;;
        false | 0 | no | off) printf 'false' ;;
        *) fail "${key} must be true or false, got '${2}'" ;;
    esac
}

# Validates an integer and prints its canonical decimal form, so a value such
# as 08 never reaches the header as an invalid C++ octal literal.
int_env() {
    local key="$1" value="$2" min="$3" max="$4"
    [[ "${value}" =~ ^[0-9]+$ ]] || fail "${key} must be an integer, got '${value}'"
    value=$((10#${value}))
    (( value >= min && value <= max )) ||
        fail "${key} must be between ${min} and ${max}, got '${value}'"
    printf '%s' "${value}"
}

check_flash_pin() {
    local key="$1" pin="$2"
    if (( pin >= 6 && pin <= 11 )); then
        echo "WARNING: ${key}=${pin} uses GPIO${pin}, which is wired to the SPI flash on most modules." >&2
    fi
}

write_config() {
    local ap_ssid="${AP_SSID-BellTimer}"
    local ap_password="${AP_PASSWORD-bell12345}"
    local api_token="${API_TOKEN-}"
    local ap_channel="${AP_CHANNEL-6}"
    local ap_clients="${AP_MAX_CLIENTS-4}"
    local pin_relay="${PIN_RELAY-26}"
    local pin_led_green="${PIN_LED_GREEN-27}"
    local pin_led_red="${PIN_LED_RED-25}"
    local pin_i2c_sda="${PIN_I2C_SDA-21}"
    local pin_i2c_scl="${PIN_I2C_SCL-22}"
    local led_active_low="${LED_ACTIVE_LOW-false}"
    local password_len

    [[ -n "${ap_ssid}" ]] || fail "AP_SSID must not be empty"
    (( $(byte_len "${ap_ssid}") <= 32 )) || fail "AP_SSID must be at most 32 bytes"
    if [[ -n "${ap_password}" ]]; then
        password_len="$(byte_len "${ap_password}")"
        (( password_len >= 8 && password_len <= 63 )) ||
            fail "AP_PASSWORD must be empty or 8..63 bytes, got ${password_len}"
    fi
    ap_channel="$(int_env AP_CHANNEL "${ap_channel}" 1 13)"
    ap_clients="$(int_env AP_MAX_CLIENTS "${ap_clients}" 1 8)"

    pin_relay="$(int_env PIN_RELAY "${pin_relay}" 0 39)"
    pin_led_green="$(int_env PIN_LED_GREEN "${pin_led_green}" 0 39)"
    pin_led_red="$(int_env PIN_LED_RED "${pin_led_red}" 0 39)"
    pin_i2c_sda="$(int_env PIN_I2C_SDA "${pin_i2c_sda}" 0 39)"
    pin_i2c_scl="$(int_env PIN_I2C_SCL "${pin_i2c_scl}" 0 39)"

    check_flash_pin PIN_RELAY "${pin_relay}"
    check_flash_pin PIN_LED_GREEN "${pin_led_green}"
    check_flash_pin PIN_LED_RED "${pin_led_red}"
    check_flash_pin PIN_I2C_SDA "${pin_i2c_sda}"
    check_flash_pin PIN_I2C_SCL "${pin_i2c_scl}"

    led_active_low="$(bool_env LED_ACTIVE_LOW "${led_active_low}")"

    local tmp
    tmp="$(mktemp "${CONFIG_HEADER}.XXXXXX")"
    {
        echo '// Generated by scripts/gen-config.sh from .env - do not edit.'
        echo '// Regenerate with: scripts/gen-config.sh'
        echo '#pragma once'
        echo
        echo "#define BELLTIMER_AP_SSID \"$(c_escape "${ap_ssid}")\""
        echo "#define BELLTIMER_AP_PASSWORD \"$(c_escape "${ap_password}")\""
        echo "#define BELLTIMER_API_TOKEN \"$(c_escape "${api_token}")\""
        echo "#define BELLTIMER_AP_CHANNEL ${ap_channel}"
        echo "#define BELLTIMER_AP_MAX_CLIENTS ${ap_clients}"
        echo
        echo "#define BELLTIMER_PIN_RELAY ${pin_relay}"
        echo "#define BELLTIMER_PIN_LED_GREEN ${pin_led_green}"
        echo "#define BELLTIMER_PIN_LED_RED ${pin_led_red}"
        echo "#define BELLTIMER_PIN_I2C_SDA ${pin_i2c_sda}"
        echo "#define BELLTIMER_PIN_I2C_SCL ${pin_i2c_scl}"
        echo "#define BELLTIMER_LED_ACTIVE_LOW ${led_active_low}"
    } > "${tmp}"

    if [[ -f "${CONFIG_HEADER}" ]] && cmp -s "${tmp}" "${CONFIG_HEADER}"; then
        rm -f "${tmp}"
    else
        mv "${tmp}" "${CONFIG_HEADER}"
        echo "Rendered configuration into ${CONFIG_HEADER#"${REPO_ROOT}"/}."
    fi
}

if [[ -f "${ENV_FILE}" ]]; then
    load_env_file "${ENV_FILE}"
elif [[ -f "${ENV_EXAMPLE}" ]]; then
    echo "WARNING: ${ENV_FILE#"${REPO_ROOT}"/} not found, using ${ENV_EXAMPLE#"${REPO_ROOT}"/}." >&2
    echo "         Create your own config with: cp .env.example .env" >&2
    load_env_file "${ENV_EXAMPLE}"
else
    echo "WARNING: neither .env nor .env.example found, using built-in defaults." >&2
fi

write_config
