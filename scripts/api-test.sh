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
expect_contains "${WORK_DIR}/status.json" '"fw":"1.2.0"' "status contains firmware version"
expect_contains "${WORK_DIR}/status.json" '"api":3' "status reports API level 3"

code="$(request GET /mode "" "${WORK_DIR}/original_mode.json")"
expect_code 200 "${code}" "GET /mode" "${WORK_DIR}/original_mode.json"

code="$(request GET /schedule "" "${WORK_DIR}/original_schedule.json")"
expect_code 200 "${code}" "GET /schedule" "${WORK_DIR}/original_schedule.json"
expect_contains "${WORK_DIR}/original_schedule.json" '"entries"' "schedule contains entries"

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
SAMPLE_SCHEDULE='{"entries":[{"time":"08:30","days":[1,2,3,4,5],"duration":3,"label":"1 урок"},{"time":"09:15","days":[1,2,3,4,5],"duration":3,"label":"2 урок"}]}'
code="$(request POST /schedule "${SAMPLE_SCHEDULE}" "${WORK_DIR}/schedule_post.json")"
expect_code 200 "${code}" "POST /schedule" "${WORK_DIR}/schedule_post.json"

code="$(request GET /schedule "" "${WORK_DIR}/schedule_after.json")"
expect_code 200 "${code}" "GET /schedule after save" "${WORK_DIR}/schedule_after.json"
expect_contains "${WORK_DIR}/schedule_after.json" '"label":"1 урок"' \
    "saved UTF-8 label is returned"

DEFAULT_DAYS_SCHEDULE='{"entries":[{"time":"07:00","duration":1,"label":"без дней"}]}'
code="$(request POST /schedule "${DEFAULT_DAYS_SCHEDULE}" "${WORK_DIR}/schedule_default_days.json")"
expect_code 200 "${code}" "POST /schedule without days" \
    "${WORK_DIR}/schedule_default_days.json"
code="$(request GET /schedule "" "${WORK_DIR}/schedule_default_days_get.json")"
expect_code 200 "${code}" "GET /schedule default days" \
    "${WORK_DIR}/schedule_default_days_get.json"
expect_contains "${WORK_DIR}/schedule_default_days_get.json" '"days":[1,2,3,4,5,6,7]' \
    "omitted days default to Monday-Sunday"

GROUP_SCHEDULE='{"version":2,"auto":{"start":"2026-01-10","stop":"2027-06-30"},"groups":[{"id":1,"name":"Будни","days":[1,2,3,4,5],"entries":[{"time":"08:00","duration":4,"label":"1 урок"}]},{"id":2,"name":"Суббота","days":[6],"entries":[]}],"exceptions":[{"date":"2027-03-08","mute":true},{"date":"2027-03-09","group_id":2}]}'
code="$(request POST /schedule "${GROUP_SCHEDULE}" "${WORK_DIR}/schedule_groups_post.json")"
expect_code 200 "${code}" "POST /schedule with groups" \
    "${WORK_DIR}/schedule_groups_post.json"
expect_contains "${WORK_DIR}/schedule_groups_post.json" '"groups":2' \
    "group count is reported"

code="$(request GET /schedule "" "${WORK_DIR}/schedule_groups_get.json")"
expect_code 200 "${code}" "GET /schedule with groups" \
    "${WORK_DIR}/schedule_groups_get.json"
expect_contains "${WORK_DIR}/schedule_groups_get.json" '"name":"Будни"' \
    "group name is returned"
expect_contains "${WORK_DIR}/schedule_groups_get.json" '"mode":"mute"' \
    "version 2 exception is migrated to a mute override"
expect_contains "${WORK_DIR}/schedule_groups_get.json" '"mode":"replace"' \
    "version 2 group exception is migrated to a replace override"
expect_contains "${WORK_DIR}/schedule_groups_get.json" '"based_on":2' \
    "migrated override keeps its source group"
expect_contains "${WORK_DIR}/schedule_groups_get.json" '"start":"2026-01-10"' \
    "auto start date is returned"

V3_SCHEDULE='{"version":3,"groups":[{"id":1,"name":"Будни","days":[1,2,3,4,5],"entries":[{"time":"08:00","duration":4,"label":"1 урок"}]}],"overrides":[{"date":"2027-03-07","mode":"mute"},{"date":"2027-03-06","mode":"replace","based_on":1,"entries":[{"time":"10:00","duration":6,"label":"суббота"}]},{"date":"2027-03-13","mode":"add","entries":[{"time":"12:30","duration":10,"label":"доп"}]}]}'
code="$(request POST /schedule "${V3_SCHEDULE}" "${WORK_DIR}/schedule_v3_post.json")"
expect_code 200 "${code}" "POST /schedule with overrides" \
    "${WORK_DIR}/schedule_v3_post.json"
expect_contains "${WORK_DIR}/schedule_v3_post.json" '"overrides":3' \
    "override count is reported"

code="$(request GET /schedule "" "${WORK_DIR}/schedule_v3_get.json")"
expect_code 200 "${code}" "GET /schedule after v3 save" \
    "${WORK_DIR}/schedule_v3_get.json"
expect_contains "${WORK_DIR}/schedule_v3_get.json" '"version":3' \
    "stored schedule is returned as version 3"
expect_contains "${WORK_DIR}/schedule_v3_get.json" '"mode":"add"' \
    "add override is returned"

code="$(request GET "/schedule/day?date=2027-03-06" "" "${WORK_DIR}/day_replace.json")"
expect_code 200 "${code}" "GET /schedule/day replace" "${WORK_DIR}/day_replace.json"
expect_contains "${WORK_DIR}/day_replace.json" '"time":"10:00"' \
    "replaced day uses the override bell list"

code="$(request GET "/schedule/day?date=2027-03-07" "" "${WORK_DIR}/day_mute.json")"
expect_code 200 "${code}" "GET /schedule/day mute" "${WORK_DIR}/day_mute.json"
expect_contains "${WORK_DIR}/day_mute.json" '"bells":[]' "muted day has no bells"

code="$(request GET "/schedule/day?date=2027-03-08" "" "${WORK_DIR}/day_weekday.json")"
expect_code 200 "${code}" "GET /schedule/day weekday" "${WORK_DIR}/day_weekday.json"
expect_contains "${WORK_DIR}/day_weekday.json" '"time":"08:00"' \
    "weekday uses the weekly schedule"

code="$(request GET "/schedule/day?date=2027-03-13" "" "${WORK_DIR}/day_add.json")"
expect_code 200 "${code}" "GET /schedule/day add" "${WORK_DIR}/day_add.json"
expect_contains "${WORK_DIR}/day_add.json" '"time":"12:30"' \
    "added bell is part of the resolved day"

code="$(request GET "/schedule/day?date=2026-13-01" "" "${WORK_DIR}/day_invalid.json")"
expect_code 400 "${code}" "GET /schedule/day rejects an invalid date" \
    "${WORK_DIR}/day_invalid.json"
expect_contains "${WORK_DIR}/day_invalid.json" '"error":"invalid_date"' \
    "invalid date error code"

BAD_GROUP_SCHEDULE='{"groups":[{"id":1,"name":"A","days":[1],"entries":[]}],"exceptions":[{"date":"2027-03-09","group_id":9}]}'
code="$(request POST /schedule "${BAD_GROUP_SCHEDULE}" "${WORK_DIR}/schedule_bad_group.json")"
expect_code 400 "${code}" "POST /schedule rejects unknown exception group" \
    "${WORK_DIR}/schedule_bad_group.json"
expect_contains "${WORK_DIR}/schedule_bad_group.json" '"error":"invalid_schedule"' \
    "invalid exception group error code"

INVALID_SCHEDULE='{"entries":[{"time":"25:99","days":[1],"duration":5,"label":"bad"}]}'
code="$(request POST /schedule "${INVALID_SCHEDULE}" "${WORK_DIR}/schedule_invalid.json")"
expect_code 400 "${code}" "POST /schedule rejects invalid time" \
    "${WORK_DIR}/schedule_invalid.json"
expect_contains "${WORK_DIR}/schedule_invalid.json" '"error":"invalid_schedule"' \
    "invalid schedule error code"

MANY_ENTRIES='{"entries":['
for i in $(seq 1 257); do
    if [[ "${i}" -gt 1 ]]; then
        MANY_ENTRIES+=','
    fi
    MANY_ENTRIES+='{"time":"08:00","days":[1],"duration":1,"label":"x"}'
done
MANY_ENTRIES+=']}'
code="$(request POST /schedule "${MANY_ENTRIES}" "${WORK_DIR}/schedule_too_many.json")"
expect_code 400 "${code}" "POST /schedule rejects more than 256 entries" \
    "${WORK_DIR}/schedule_too_many.json"
expect_contains "${WORK_DIR}/schedule_too_many.json" '"error":"too_many_entries"' \
    "too many entries error code"

MANY_OVERRIDES='{"groups":[{"id":1,"name":"A","days":[1],"entries":[]}],"overrides":['
override_count=0
for month in 01 02 03 04; do
    for day in $(seq -w 1 28); do
        override_count=$((override_count + 1))
        if [[ "${override_count}" -gt 91 ]]; then
            break 2
        fi
        if [[ "${override_count}" -gt 1 ]]; then
            MANY_OVERRIDES+=','
        fi
        MANY_OVERRIDES+="{\"date\":\"2027-${month}-${day}\",\"mode\":\"mute\"}"
    done
done
MANY_OVERRIDES+=']}'
code="$(request POST /schedule "${MANY_OVERRIDES}" "${WORK_DIR}/schedule_too_many_overrides.json")"
expect_code 400 "${code}" "POST /schedule rejects more than 90 overrides" \
    "${WORK_DIR}/schedule_too_many_overrides.json"
expect_contains "${WORK_DIR}/schedule_too_many_overrides.json" \
    '"error":"too_many_overrides"' "too many overrides error code"

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
