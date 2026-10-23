#!/usr/bin/env bash
# Exercises every BellTimer HTTP endpoint against a running device.
#
# Usage:
#   scripts/api-test.sh [base-url]
#   X_TOKEN=secret scripts/api-test.sh http://192.168.4.1
#
# The script saves the current schedule, mode and ring_default and restores
# them when it exits.
set -euo pipefail

BASE_URL="${1:-http://192.168.4.1}"
BASE_URL="${BASE_URL%/}"
TOKEN="${X_TOKEN:-}"

WORK_DIR="$(mktemp -d)"
PASS_COUNT=0

AUTH=()
if [[ -n "${TOKEN}" ]]; then
    AUTH=(-H "X-Token: ${TOKEN}")
fi

request() {
    local method="$1"
    local path="$2"
    local body="${3:-}"
    local output="$4"
    local args=(
        -sS
        --max-time 8
        -X "${method}"
        -o "${output}"
        -w "%{http_code}"
        -H "Accept: application/json"
        "${AUTH[@]}"
    )
    if [[ -n "${body}" ]]; then
        args+=(--data-binary "${body}" -H "Content-Type: application/json; charset=utf-8")
    fi
    curl "${args[@]}" "${BASE_URL}${path}"
}

expect_code() {
    local expected="$1"
    local actual="$2"
    local label="$3"
    local file="$4"
    if [[ "${actual}" != "${expected}" ]]; then
        echo "FAIL: ${label} (expected HTTP ${expected}, got ${actual})" >&2
        cat "${file}" >&2
        exit 1
    fi
    PASS_COUNT=$((PASS_COUNT + 1))
    echo "PASS: ${label} (HTTP ${actual})"
}

expect_contains() {
    local file="$1"
    local needle="$2"
    local label="$3"
    if ! grep -qF -- "${needle}" "${file}"; then
        echo "FAIL: ${label} (missing ${needle})" >&2
        cat "${file}" >&2
        exit 1
    fi
    PASS_COUNT=$((PASS_COUNT + 1))
    echo "PASS: ${label}"
}

expect_one_of() {
    local label="$1"
    local actual="$2"
    shift 2
    local allowed
    for allowed in "$@"; do
        if [[ "${actual}" == "${allowed}" ]]; then
            PASS_COUNT=$((PASS_COUNT + 1))
            echo "PASS: ${label} (HTTP ${actual})"
            return 0
        fi
    done
    echo "FAIL: ${label} (unexpected HTTP ${actual})" >&2
    exit 1
}

cleanup() {
    if [[ -f "${WORK_DIR}/original_schedule.json" ]]; then
        request POST /schedule "$(cat "${WORK_DIR}/original_schedule.json")" \
            "${WORK_DIR}/restore_schedule.json" >/dev/null 2>&1 || true
    fi
    if [[ -f "${WORK_DIR}/original_mode.json" ]]; then
        local mode
        mode="$(sed -n 's/.*"mode"[[:space:]]*:[[:space:]]*"\([a-z]*\)".*/\1/p' \
            "${WORK_DIR}/original_mode.json" | head -n1)"
        if [[ -n "${mode}" ]]; then
            request POST /mode "{\"mode\":\"${mode}\"}" \
                "${WORK_DIR}/restore_mode.json" >/dev/null 2>&1 || true
        fi
    fi
    if [[ -f "${WORK_DIR}/original_settings.json" ]]; then
        local ring_default
        ring_default="$(sed -n 's/.*"ring_default"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
            "${WORK_DIR}/original_settings.json" | head -n1)"
        if [[ -n "${ring_default}" ]]; then
            request POST /settings "{\"ring_default\":${ring_default}}" \
                "${WORK_DIR}/restore_settings.json" >/dev/null 2>&1 || true
        fi
    fi
    rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

echo "Testing BellTimer at ${BASE_URL}"
if [[ -n "${TOKEN}" ]]; then
    echo "Using X-Token from the environment."
fi

# --- Capture the current state for restoration ---------------------------
code="$(request GET /status "" "${WORK_DIR}/status.json")"
expect_code 200 "${code}" "GET /status" "${WORK_DIR}/status.json"
expect_contains "${WORK_DIR}/status.json" '"rtc_ok"' "status contains rtc_ok"
expect_contains "${WORK_DIR}/status.json" '"errors"' "status contains errors"
expect_contains "${WORK_DIR}/status.json" '"fw":"2.0.0"' "status contains firmware version"
expect_contains "${WORK_DIR}/status.json" '"api":5' "status reports API level 5"
expect_contains "${WORK_DIR}/status.json" '"next"' "status contains the next bell"
expect_contains "${WORK_DIR}/status.json" '"synced"' "status contains the last sync time"

code="$(request GET /mode "" "${WORK_DIR}/original_mode.json")"
expect_code 200 "${code}" "GET /mode" "${WORK_DIR}/original_mode.json"

code="$(request GET /schedule "" "${WORK_DIR}/original_schedule.json")"
expect_code 200 "${code}" "GET /schedule" "${WORK_DIR}/original_schedule.json"
expect_contains "${WORK_DIR}/original_schedule.json" '"packs"' "schedule contains packs"

code="$(request GET /settings "" "${WORK_DIR}/original_settings.json")"
expect_code 200 "${code}" "GET /settings" "${WORK_DIR}/original_settings.json"
expect_contains "${WORK_DIR}/original_settings.json" '"ring_default"' "settings contains ring_default"

# --- CORS preflight -------------------------------------------------------
preflight="$(curl -sS --max-time 8 -D - -o /dev/null -X OPTIONS "${BASE_URL}/status")"
if ! grep -qi '^Access-Control-Allow-Origin: \*' <<<"${preflight}"; then
    echo "FAIL: OPTIONS /status did not return Access-Control-Allow-Origin" >&2
    exit 1
fi
PASS_COUNT=$((PASS_COUNT + 1))
echo "PASS: OPTIONS /status returns CORS headers"

if [[ -n "${TOKEN}" ]]; then
    unauth_code="$(curl -sS --max-time 8 -o "${WORK_DIR}/unauth.json" -w "%{http_code}" \
        "${BASE_URL}/status")"
    expect_code 401 "${unauth_code}" "GET /status without X-Token is rejected" \
        "${WORK_DIR}/unauth.json"
    expect_contains "${WORK_DIR}/unauth.json" '"error":"unauthorized"' \
        "missing token returns unauthorized"
fi

# --- Mode -----------------------------------------------------------------
code="$(request POST /mode '{"mode":"manual"}' "${WORK_DIR}/mode_manual.json")"
expect_code 200 "${code}" "POST /mode manual" "${WORK_DIR}/mode_manual.json"
expect_contains "${WORK_DIR}/mode_manual.json" '"mode":"manual"' "manual mode applied"

code="$(request GET /mode "" "${WORK_DIR}/mode_get_manual.json")"
expect_code 200 "${code}" "GET /mode manual" "${WORK_DIR}/mode_get_manual.json"
expect_contains "${WORK_DIR}/mode_get_manual.json" '"mode":"manual"' "GET /mode reports manual"

code="$(request POST /mode '{"mode":"auto"}' "${WORK_DIR}/mode_auto.json")"
expect_code 200 "${code}" "POST /mode auto" "${WORK_DIR}/mode_auto.json"
expect_contains "${WORK_DIR}/mode_auto.json" '"mode":"auto"' "auto mode applied"

code="$(request POST /mode '{"mode":"bogus"}' "${WORK_DIR}/mode_invalid.json")"
expect_code 400 "${code}" "POST /mode rejects invalid mode" "${WORK_DIR}/mode_invalid.json"
expect_contains "${WORK_DIR}/mode_invalid.json" '"error":"invalid_mode"' \
    "invalid mode error code"

# --- Ring / stop ----------------------------------------------------------
code="$(request POST /ring '{"sec":3}' "${WORK_DIR}/ring.json")"
expect_code 200 "${code}" "POST /ring (body)" "${WORK_DIR}/ring.json"
expect_contains "${WORK_DIR}/ring.json" '"ringing":true' "ring response reports ringing"

code="$(request GET /status "" "${WORK_DIR}/status_ringing.json")"
expect_code 200 "${code}" "GET /status while ringing" "${WORK_DIR}/status_ringing.json"
expect_contains "${WORK_DIR}/status_ringing.json" '"ringing":true' "status reports ringing"

code="$(request POST /stop "" "${WORK_DIR}/stop.json")"
expect_code 200 "${code}" "POST /stop" "${WORK_DIR}/stop.json"

code="$(request GET /status "" "${WORK_DIR}/status_stopped.json")"
expect_code 200 "${code}" "GET /status after stop" "${WORK_DIR}/status_stopped.json"
expect_contains "${WORK_DIR}/status_stopped.json" '"ringing":false' "status reports stopped"

code="$(request POST "/ring?sec=1" "" "${WORK_DIR}/ring_query.json")"
expect_code 200 "${code}" "POST /ring?sec=1 (query)" "${WORK_DIR}/ring_query.json"
request POST /stop "" "${WORK_DIR}/stop_query.json" >/dev/null

code="$(request POST /ring '{"sec":0}' "${WORK_DIR}/ring_invalid.json")"
expect_code 400 "${code}" "POST /ring rejects sec=0" "${WORK_DIR}/ring_invalid.json"
expect_contains "${WORK_DIR}/ring_invalid.json" '"error":"invalid_sec"' \
    "invalid ring duration error code"

# --- Time -----------------------------------------------------------------
NOW="$(date +%Y-%m-%dT%H:%M:%S)"
code="$(request POST /time "{\"datetime\":\"${NOW}\"}" "${WORK_DIR}/time.json")"
expect_one_of "POST /time with phone local time" "${code}" 200 503
if [[ "${code}" == "503" ]]; then
    expect_contains "${WORK_DIR}/time.json" '"error":"rtc_not_found"' \
        "time sync reports missing RTC"
    echo "NOTE: DS3231 did not respond; /time returned 503 as expected without the RTC."
fi

invalid_time_code="$(request POST /time '{"datetime":"2026-13-40T25:61:61"}' \
    "${WORK_DIR}/time_invalid.json")"
expect_code 400 "${invalid_time_code}" "POST /time rejects invalid datetime" \
    "${WORK_DIR}/time_invalid.json"
expect_contains "${WORK_DIR}/time_invalid.json" '"error":"invalid_datetime"' \
    "invalid datetime error code"

# --- Schedule -------------------------------------------------------------
V5_SCHEDULE='{"version":5,"auto":{"start":"2026-01-10","stop":"2027-06-30"},"packs":[{"id":1,"name":"Будни","kind":"base","modules":[{"type":"lesson","time":"08:00","length":45,"pre":2,"days":[1,2,3,4,5],"label":"1 урок"},{"type":"bell","time":"12:30","days":[1,2,3,4,5],"label":"Обед"},{"type":"series","time":"14:00","until":"16:00","every":60,"days":[1,2,3,4,5]}]},{"id":2,"name":"Сокращённый","kind":"correction","dates":["2027-03-09"],"modules":[{"type":"lesson","time":"09:00","length":30}]},{"id":3,"name":"Праздник","kind":"correction","mute":true,"dates":["2027-03-08"]},{"id":4,"name":"Ниже","kind":"base","dates":["2027-03-09"],"modules":[{"type":"bell","time":"15:30"}]}]}'
code="$(request POST /schedule "${V5_SCHEDULE}" "${WORK_DIR}/schedule_post.json")"
expect_code 200 "${code}" "POST /schedule v5" "${WORK_DIR}/schedule_post.json"
expect_contains "${WORK_DIR}/schedule_post.json" '"modules":6' "module count is reported"
expect_contains "${WORK_DIR}/schedule_post.json" '"packs":4' "pack count is reported"

code="$(request GET /schedule "" "${WORK_DIR}/schedule_after.json")"
expect_code 200 "${code}" "GET /schedule after save" "${WORK_DIR}/schedule_after.json"
expect_contains "${WORK_DIR}/schedule_after.json" '"version":5' "schedule is returned as version 5"
expect_contains "${WORK_DIR}/schedule_after.json" '"label":"1 урок"' "saved UTF-8 label is returned"
expect_contains "${WORK_DIR}/schedule_after.json" '"type":"lesson","time":"08:00","length":45,"pre":2' "lesson fields are returned"
expect_contains "${WORK_DIR}/schedule_after.json" '"until":"16:00","every":60' "series fields are returned"
expect_contains "${WORK_DIR}/schedule_after.json" '"mute":true' "holiday correction is returned"

# Monday 2027-03-01: pre-bell, lesson start/end, lunch, series.
code="$(request GET "/schedule/day?date=2027-03-01" "" "${WORK_DIR}/day_normal.json")"
expect_code 200 "${code}" "GET /schedule/day normal day" "${WORK_DIR}/day_normal.json"
expect_contains "${WORK_DIR}/day_normal.json" '"time":"07:58","label":"1 урок","type":"lesson","role":"pre"' "warning bell"
expect_contains "${WORK_DIR}/day_normal.json" '"time":"08:45","label":"1 урок","type":"lesson","role":"end"' "lesson end bell"
expect_contains "${WORK_DIR}/day_normal.json" '"time":"16:00","label":"","type":"series","role":"step"' "last series bell"

code="$(request GET "/schedule/day?date=2027-03-08" "" "${WORK_DIR}/day_mute.json")"
expect_code 200 "${code}" "GET /schedule/day holiday" "${WORK_DIR}/day_mute.json"
expect_contains "${WORK_DIR}/day_mute.json" '"bells":[]' "holiday has no bells"

code="$(request GET "/schedule/day?date=2027-03-09" "" "${WORK_DIR}/day_correction.json")"
expect_code 200 "${code}" "GET /schedule/day correction" "${WORK_DIR}/day_correction.json"
expect_contains "${WORK_DIR}/day_correction.json" '"time":"09:30"' "correction lesson end"
expect_contains "${WORK_DIR}/day_correction.json" '"time":"15:30"' "base pack below the correction still rings"
if grep -qF '"time":"08:00"' "${WORK_DIR}/day_correction.json"; then
    echo "FAIL: correction did not cancel the base pack above it" >&2
    exit 1
fi
PASS_COUNT=$((PASS_COUNT + 1))
echo "PASS: correction cancels the base pack above it"

code="$(request GET "/schedule/day?date=2026-13-01" "" "${WORK_DIR}/day_invalid.json")"
expect_code 400 "${code}" "GET /schedule/day rejects an invalid date" "${WORK_DIR}/day_invalid.json"
expect_contains "${WORK_DIR}/day_invalid.json" '"error":"invalid_date"' "invalid date error code"

V4_SCHEDULE='{"version":4,"packs":[{"id":1,"name":"Старое","entries":[{"time":"08:30","pair":"09:15","days":[1,2,3,4,5],"label":"1 урок"},{"time":"12:00","label":"Обед"}]}]}'
code="$(request POST /schedule "${V4_SCHEDULE}" "${WORK_DIR}/schedule_v4.json")"
expect_code 200 "${code}" "POST /schedule v4 is migrated" "${WORK_DIR}/schedule_v4.json"
code="$(request GET /schedule "" "${WORK_DIR}/schedule_v4_get.json")"
expect_contains "${WORK_DIR}/schedule_v4_get.json" '"type":"lesson","time":"08:30","length":45' "v4 pair becomes a lesson"
expect_contains "${WORK_DIR}/schedule_v4_get.json" '"days":[1,2,3,4,5,6,7]' "omitted days default to Monday-Sunday"

for bad in \
    '{"packs":[{"modules":[{"type":"lesson","time":"23:30","length":45}]}]}' \
    '{"packs":[{"modules":[{"type":"series","time":"10:00","until":"09:00","every":30}]}]}' \
    '{"packs":[{"modules":[{"type":"siren","time":"10:00"}]}]}' \
    '{"packs":[{"kind":"base","mute":true}]}' \
    '{"packs":[{"modules":[{"time":"25:99"}]}]}' \
    '{"entries":[]}'; do
    code="$(request POST /schedule "${bad}" "${WORK_DIR}/schedule_invalid.json")"
    expect_code 400 "${code}" "POST /schedule rejects ${bad:0:60}" "${WORK_DIR}/schedule_invalid.json"
    expect_contains "${WORK_DIR}/schedule_invalid.json" '"error":"invalid_schedule"' "invalid schedule error code"
done

MANY_MODULES='{"packs":[{"modules":['
for i in $(seq 1 33); do
    if [[ "${i}" -gt 1 ]]; then
        MANY_MODULES+=','
    fi
    MANY_MODULES+='{"type":"bell","time":"08:00"}'
done
MANY_MODULES+=']}]}'
code="$(request POST /schedule "${MANY_MODULES}" "${WORK_DIR}/schedule_too_many.json")"
expect_code 400 "${code}" "POST /schedule rejects more than 32 modules per pack" \
    "${WORK_DIR}/schedule_too_many.json"
expect_contains "${WORK_DIR}/schedule_too_many.json" '"error":"too_many_pack_modules"' \
    "too many pack modules error code"

MANY_PACKS='{"packs":[{},{},{},{},{},{},{}]}'
code="$(request POST /schedule "${MANY_PACKS}" "${WORK_DIR}/schedule_too_many_packs.json")"
expect_code 400 "${code}" "POST /schedule rejects more than 6 packs" "${WORK_DIR}/schedule_too_many_packs.json"
expect_contains "${WORK_DIR}/schedule_too_many_packs.json" '"error":"too_many_packs"' "too many packs error code"

# --- Settings -------------------------------------------------------------
code="$(request POST /settings '{"ring_default":7}' "${WORK_DIR}/settings_post.json")"
expect_code 200 "${code}" "POST /settings" "${WORK_DIR}/settings_post.json"

code="$(request GET /settings "" "${WORK_DIR}/settings_after.json")"
expect_code 200 "${code}" "GET /settings after save" "${WORK_DIR}/settings_after.json"
expect_contains "${WORK_DIR}/settings_after.json" '"ring_default":7' \
    "ring_default was stored"

code="$(request POST /settings '{"ring_default":0}' "${WORK_DIR}/settings_invalid.json")"
expect_code 400 "${code}" "POST /settings rejects 0" "${WORK_DIR}/settings_invalid.json"
expect_contains "${WORK_DIR}/settings_invalid.json" '"error":"invalid_ring_default"' \
    "invalid ring_default error code"

# --- Factory reset --------------------------------------------------------
code="$(request POST /reset "" "${WORK_DIR}/reset.json")"
expect_code 200 "${code}" "POST /reset" "${WORK_DIR}/reset.json"
code="$(request GET /schedule "" "${WORK_DIR}/schedule_reset.json")"
expect_contains "${WORK_DIR}/schedule_reset.json" '"packs":[]' "reset clears the schedule"
code="$(request GET /settings "" "${WORK_DIR}/settings_reset.json")"
expect_contains "${WORK_DIR}/settings_reset.json" '"ring_default":5' "reset restores ring_default"
code="$(request GET /status "" "${WORK_DIR}/status_reset.json")"
expect_contains "${WORK_DIR}/status_reset.json" '"mode":"auto"' "reset restores auto mode"
expect_contains "${WORK_DIR}/status_reset.json" '"synced":null' "reset forgets the last sync"

# --- Network --------------------------------------------------------------
code="$(request GET /network "" "${WORK_DIR}/network.json")"
expect_code 200 "${code}" "GET /network" "${WORK_DIR}/network.json"
expect_contains "${WORK_DIR}/network.json" '"ssid"' "network reports the access point name"
if grep -qF '"password"' "${WORK_DIR}/network.json"; then
    echo "FAIL: GET /network must not return the password" >&2
    exit 1
fi
PASS_COUNT=$((PASS_COUNT + 1))
echo "PASS: GET /network hides the password"

# An empty body changes nothing, so the access point does not restart.
code="$(request POST /network '{}' "${WORK_DIR}/network_noop.json")"
expect_code 200 "${code}" "POST /network without changes" "${WORK_DIR}/network_noop.json"
expect_contains "${WORK_DIR}/network_noop.json" '"restart":false' "unchanged network does not restart"

for bad in '{"ssid":""}:invalid_ssid' '{"password":"short"}:invalid_password' '{"token":"has space"}:invalid_token'; do
    body="${bad%:*}"
    error="${bad##*:}"
    code="$(request POST /network "${body}" "${WORK_DIR}/network_bad.json")"
    expect_code 400 "${code}" "POST /network rejects ${body}" "${WORK_DIR}/network_bad.json"
    expect_contains "${WORK_DIR}/network_bad.json" "\"error\":\"${error}\"" "${error} error code"
done

# --- HTML page and 404 ----------------------------------------------------
code="$(request GET / "" "${WORK_DIR}/index.html")"
expect_code 200 "${code}" "GET /" "${WORK_DIR}/index.html"
expect_contains "${WORK_DIR}/index.html" "BellTimer" "test page contains app name"

code="$(request GET /does-not-exist "" "${WORK_DIR}/not_found.json")"
expect_code 404 "${code}" "GET /does-not-exist" "${WORK_DIR}/not_found.json"
expect_contains "${WORK_DIR}/not_found.json" '"error":"not_found"' "not found error code"

echo
echo "All checks passed (${PASS_COUNT} assertions)."
echo "Schedule, mode and ring_default will be restored from the saved state."
