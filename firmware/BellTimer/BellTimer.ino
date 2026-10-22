// BellTimer - programmable bell timer for ESP32.
//
// Hardware:
//   GPIO21/22 - DS3231 RTC (I2C, address 0x68, stores LOCAL time)
//   GPIO26    - relay input, ACTIVE LOW (LOW = relay on)
//   GPIO27    - KY-029 "G" pin (green die)
//   GPIO25    - KY-029 "R" pin (red die)
//   KY-029 common pin - GND (common cathode) or 3V3 (common anode)
//
// The device creates its own Wi-Fi access point and serves a small JSON API.

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>
#include <Wire.h>
#include <RTClib.h>
#include <ArduinoJson.h>

// Deployment settings (Wi-Fi credentials, API token, GPIO wiring) come from
// .env. scripts/build-firmware.sh renders them into this header; it is
// generated, git-ignored and must exist before the sketch can be compiled.
#if __has_include("app_config.h")
#include "app_config.h"
#else
#error "app_config.h is missing: copy .env.example to .env and run scripts/build-firmware.sh."
#endif

namespace {

const char *const FW_VERSION = "1.2.0";

// API level reported by GET /status. Level 3 adds per-date overrides.
const uint8_t API_VERSION = 3;

// ----------------------------- Configuration ------------------------------

const char *const AP_SSID = BELLTIMER_AP_SSID;
const char *const AP_PASSWORD = BELLTIMER_AP_PASSWORD;
const char *const API_TOKEN = BELLTIMER_API_TOKEN;  // Empty disables X-Token.
const uint8_t AP_CHANNEL = BELLTIMER_AP_CHANNEL;
const uint8_t AP_MAX_CLIENTS = BELLTIMER_AP_MAX_CLIENTS;

const uint8_t PIN_RELAY = BELLTIMER_PIN_RELAY;
const uint8_t PIN_LED_GREEN = BELLTIMER_PIN_LED_GREEN;  // KY-029 "G" pin
const uint8_t PIN_LED_RED = BELLTIMER_PIN_LED_RED;      // KY-029 "R" pin
// The KY-029 exists in a common-cathode and a common-anode variant. With the
// common pin tied to GND the dies are lit by a HIGH level; with the common pin
// tied to 3V3 they are lit by a LOW level. See LED_ACTIVE_LOW in .env.
const bool LED_ACTIVE_LOW = BELLTIMER_LED_ACTIVE_LOW;
const uint8_t PIN_I2C_SDA = BELLTIMER_PIN_I2C_SDA;
const uint8_t PIN_I2C_SCL = BELLTIMER_PIN_I2C_SCL;

// A schedule is a set of named groups. Every group owns a list of bells and
// repeats on the weekdays selected for the group. A per-date override can mute
// a day, replace its bells or add extra bells on top. Auto mode can be limited
// to a date range. The limits below are mirrored by the Android app
// (data/Models.kt).
const size_t MAX_ENTRIES = 256;      // bells across all groups and overrides
const size_t MAX_GROUPS = 12;        // named schedule groups
const size_t MAX_OVERRIDES = 90;     // per-date overrides
const size_t MAX_LABEL_BYTES = 32;   // About 10 Cyrillic characters in UTF-8.
const size_t MAX_GROUP_NAME_BYTES = 32;
// Bodies larger than this are rejected before parsing, so an oversized
// document cannot exhaust the heap.
const size_t MAX_SCHEDULE_BODY_BYTES = 16384;
const uint8_t MIN_RING_SEC = 1;
const uint8_t MAX_RING_SEC = 60;
const uint8_t DEFAULT_RING_SEC = 5;
const uint8_t ALL_DAYS_MASK = 0x7F;    // bit 0 = Monday ... bit 6 = Sunday

const char *const SCHEDULE_FILE = "/schedule.json";
const char *const SETTINGS_FILE = "/settings.json";

const uint32_t RTC_POLL_MS = 250;
const uint32_t RTC_RETRY_MS = 2000;
const uint32_t AP_RETRY_MS = 5000;
const uint32_t STORAGE_RETRY_MS = 5000;
const uint32_t AP_IP_CHECK_MS = 5000;

// ------------------------------- Types ------------------------------------

struct ScheduleEntry {
  uint16_t minuteOfDay = 0;  // 0..1439
  uint8_t daysMask = ALL_DAYS_MASK;
  uint8_t duration = DEFAULT_RING_SEC;
  uint8_t groupId = 0;       // owning group, 0 for override entries
  uint8_t overrideSlot = 0;  // 1..MAX_OVERRIDES, 0 for schedule entries
  String label;
};

struct ScheduleGroup {
  uint8_t id = 0;
  uint8_t daysMask = ALL_DAYS_MASK;
  String name;
};

// How a date override changes the day: ring nothing, use its own bell list or
// add extra bells on top of the schedule.
enum OverrideMode : uint8_t {
  OVERRIDE_MUTE = 0,
  OVERRIDE_REPLACE = 1,
  OVERRIDE_ADD = 2,
};

struct DayOverride {
  uint32_t dateKey = 0;        // YYYYMMDD
  uint8_t mode = OVERRIDE_MUTE;
  uint8_t basedOnGroupId = 0;  // metadata only, never used for evaluation
};

// ------------------------------- State ------------------------------------

RTC_DS3231 rtc;
WebServer server(80);

bool storageOk = false;
bool rtcOk = false;
bool rtcLostPower = true;
bool apOk = false;

uint8_t ringDefault = DEFAULT_RING_SEC;
String currentMode = "auto";

ScheduleEntry entries[MAX_ENTRIES];
size_t entryCount = 0;

ScheduleGroup groups[MAX_GROUPS];
size_t groupCount = 0;

DayOverride overrides[MAX_OVERRIDES];
size_t overrideCount = 0;
// Auto mode date range. A zero key means "no limit".
uint32_t autoStartDate = 0;
uint32_t autoStopDate = 0;

// Scratch buffers filled by parseScheduleDocument() and committed only after
// the document has been validated and written to flash.
ScheduleEntry parsedEntries[MAX_ENTRIES];
size_t parsedEntryCount = 0;
ScheduleGroup parsedGroups[MAX_GROUPS];
size_t parsedGroupCount = 0;
DayOverride parsedOverrides[MAX_OVERRIDES];
size_t parsedOverrideCount = 0;
uint32_t parsedAutoStart = 0;
uint32_t parsedAutoStop = 0;

DateTime cachedNow(2000, 1, 1, 0, 0, 0);
bool cachedNowValid = false;
uint32_t rtcLastPollMs = 0;
uint32_t rtcLastRetryMs = 0;
uint32_t rtcLastStatusMs = 0;
uint32_t apLastRetryMs = 0;
uint32_t apLastIpCheckMs = 0;
uint32_t storageLastRetryMs = 0;
bool storageRetryPending = false;

bool ringing = false;
uint32_t ringEndMs = 0;
uint32_t ringLastTickMs = 0;
int ringLeftSec = 0;

uint32_t suppressMinuteKey = 0;
bool suppressMinuteSet = false;
uint32_t lastScheduleMinute = 0xFFFFFFFFUL;

// LED blink timings. The phase is derived from millis(), so no state is kept.
const uint32_t LED_OK_BLINK_MS = 500;    // green, 1 Hz
const uint32_t LED_FAULT_BLINK_MS = 250; // red, 2 Hz

// --------------------------- Small helpers --------------------------------

void relayOn() { digitalWrite(PIN_RELAY, LOW); }
void relayOff() { digitalWrite(PIN_RELAY, HIGH); }

// Single point that translates a logical LED state into a pin level, so the
// common-cathode and common-anode KY-029 variants differ only by a constant.
inline void ledWrite(uint8_t pin, bool on) {
  digitalWrite(pin, on == LED_ACTIVE_LOW ? LOW : HIGH);
}

// Pin level that keeps a die dark, used before pinMode() so the LED never
// flashes during boot.
inline uint8_t ledOffLevel() { return LED_ACTIVE_LOW ? HIGH : LOW; }

void setLed(bool green, bool red) {
  ledWrite(PIN_LED_GREEN, green);
  ledWrite(PIN_LED_RED, red);
}

String formatDateTime(const DateTime &dt) {
  char buf[20];
  snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02d", dt.year(), dt.month(),
           dt.day(), dt.hour(), dt.minute(), dt.second());
  return String(buf);
}

String formatTimeOfDay(uint16_t minuteOfDay) {
  char buf[6];
  snprintf(buf, sizeof(buf), "%02u:%02u", (unsigned)(minuteOfDay / 60U),
           (unsigned)(minuteOfDay % 60U));
  return String(buf);
}

uint32_t minuteKey(const DateTime &dt) { return dt.unixtime() / 60UL; }

uint8_t isoWeekday(const DateTime &dt) {
  // RTClib: 0 = Sunday ... 6 = Saturday. API: 1 = Monday ... 7 = Sunday.
  uint8_t dow = dt.dayOfTheWeek();
  return dow == 0 ? 7 : dow;
}

bool timeIsValid() {
  return rtcOk && !rtcLostPower && cachedNowValid && cachedNow.year() >= 2024 &&
         cachedNow.year() <= 2099;
}

bool parseTwoDigits(const String &s, size_t pos, int &out) {
  if (pos + 1 >= s.length()) return false;
  char a = s[pos];
  char b = s[pos + 1];
  if (a < '0' || a > '9' || b < '0' || b > '9') return false;
  out = (a - '0') * 10 + (b - '0');
  return true;
}

bool parseDateTime(const String &s, DateTime &out) {
  if (s.length() != 19) return false;
  if (s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':') {
    return false;
  }
  int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0;
  for (size_t i = 0; i < 4; ++i) {
    char c = s[i];
    if (c < '0' || c > '9') return false;
    year = year * 10 + (c - '0');
  }
  if (!parseTwoDigits(s, 5, month) || !parseTwoDigits(s, 8, day) ||
      !parseTwoDigits(s, 11, hour) || !parseTwoDigits(s, 14, minute) ||
      !parseTwoDigits(s, 17, second)) {
    return false;
  }
  if (year < 2024 || year > 2099 || month < 1 || month > 12 || day < 1 ||
      hour > 23 || minute > 59 || second > 59) {
    return false;
  }
  static const uint8_t DAYS_IN_MONTH[12] = {31, 28, 31, 30, 31, 30,
                                            31, 31, 30, 31, 30, 31};
  uint8_t maxDay = DAYS_IN_MONTH[month - 1];
  bool leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
  if (month == 2 && leap) maxDay = 29;
  if (day > maxDay) return false;
  out = DateTime(year, month, day, hour, minute, second);
  return true;
}

bool parseTimeOfDay(const String &s, uint16_t &minuteOfDay) {
  if (s.length() != 5 || s[2] != ':') return false;
  int hour = 0, minute = 0;
  if (!parseTwoDigits(s, 0, hour) || !parseTwoDigits(s, 3, minute)) return false;
  if (hour > 23 || minute > 59) return false;
  minuteOfDay = (uint16_t)(hour * 60 + minute);
  return true;
}

bool validDateParts(int year, int month, int day) {
  if (year < 2024 || year > 2099 || month < 1 || month > 12 || day < 1) {
    return false;
  }
  static const uint8_t DAYS_IN_MONTH[12] = {31, 28, 31, 30, 31, 30,
                                            31, 31, 30, 31, 30, 31};
  uint8_t maxDay = DAYS_IN_MONTH[month - 1];
  bool leap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
  if (month == 2 && leap) maxDay = 29;
  return day <= maxDay;
}

// Parses "YYYY-MM-DD" into a comparable YYYYMMDD key.
bool parseDateKey(const String &s, uint32_t &key) {
  if (s.length() != 10 || s[4] != '-' || s[7] != '-') return false;
  int year = 0, month = 0, day = 0;
  for (size_t i = 0; i < 4; ++i) {
    char c = s[i];
    if (c < '0' || c > '9') return false;
    year = year * 10 + (c - '0');
  }
  if (!parseTwoDigits(s, 5, month) || !parseTwoDigits(s, 8, day)) return false;
  if (!validDateParts(year, month, day)) return false;
  key = static_cast<uint32_t>(year) * 10000UL + month * 100UL + day;
  return true;
}

String formatDateKey(uint32_t key) {
  char buf[12];
  snprintf(buf, sizeof(buf), "%04u-%02u-%02u", (unsigned)(key / 10000UL),
           (unsigned)((key / 100UL) % 100UL), (unsigned)(key % 100UL));
  return String(buf);
}

uint32_t dateKeyOf(const DateTime &dt) {
  return static_cast<uint32_t>(dt.year()) * 10000UL + dt.month() * 100UL + dt.day();
}

// ISO weekday (1 = Monday .. 7 = Sunday) of a YYYYMMDD key.
uint8_t isoWeekdayFromDateKey(uint32_t key) {
  int y = static_cast<int>(key / 10000UL);
  int m = static_cast<int>((key / 100UL) % 100UL);
  int d = static_cast<int>(key % 100UL);
  static const int OFFSETS[12] = {0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4};
  if (m < 3) --y;
  int w = (y + y / 4 - y / 100 + y / 400 + OFFSETS[m - 1] + d) % 7;  // 0 = Sunday
  return w == 0 ? 7 : static_cast<uint8_t>(w);
}

// Shifts a YYYYMMDD key by whole days.
uint32_t addDaysToDateKey(uint32_t key, int days) {
  DateTime base(static_cast<int>(key / 10000UL),
                static_cast<int>((key / 100UL) % 100UL),
                static_cast<int>(key % 100UL), 12, 0, 0);
  DateTime moved(base.unixtime() + static_cast<int32_t>(days) * 86400L);
  return dateKeyOf(moved);
}

const char *overrideModeName(uint8_t mode) {
  switch (mode) {
    case OVERRIDE_REPLACE:
      return "replace";
    case OVERRIDE_ADD:
      return "add";
    default:
      return "mute";
  }
}

bool parseOverrideMode(JsonVariant value, uint8_t &mode) {
  if (!value.is<const char *>()) return false;
  String raw = String(value.as<const char *>());
  if (raw == "mute") {
    mode = OVERRIDE_MUTE;
  } else if (raw == "replace") {
    mode = OVERRIDE_REPLACE;
  } else if (raw == "add") {
    mode = OVERRIDE_ADD;
  } else {
    return false;
  }
  return true;
}

// Truncates a UTF-8 string to at most maxBytes bytes without splitting a
// multi-byte code point.
String truncateUtf8(const String &input, size_t maxBytes) {
  if (input.length() <= maxBytes) return input;
  size_t cut = maxBytes;
  while (cut > 0 && (static_cast<uint8_t>(input[cut]) & 0xC0) == 0x80) {
    --cut;
  }
  return input.substring(0, cut);
}

bool clearOscillatorStopFlag() {
  Wire.beginTransmission(0x68);
  Wire.write(0x0F);  // DS3231 status register
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(0x68, 1) != 1) return false;
  uint8_t status = Wire.read();
  Wire.beginTransmission(0x68);
  Wire.write(0x0F);
  Wire.write(static_cast<uint8_t>(status & 0x7F));  // clear OSF
  return Wire.endTransmission() == 0;
}

// --------------------------- Storage layer --------------------------------

bool writeFileAtomic(const char *path, const String &data) {
  String tmpPath = String(path) + ".tmp";
  File out = LittleFS.open(tmpPath, "w");
  if (!out) return false;
  size_t written = out.print(data);
  out.close();
  if (written != data.length()) {
    LittleFS.remove(tmpPath);
    return false;
  }
  if (LittleFS.exists(path)) {
    LittleFS.remove(path);
  }
  if (!LittleFS.rename(tmpPath, path)) {
    LittleFS.remove(tmpPath);
    return false;
  }
  return true;
}

String sanitizeMode(const String &value) {
  if (value == "auto" || value == "manual") return value;
  return "auto";
}

bool saveSettingsToFlash() {
  JsonDocument doc;
  doc["ring_default"] = ringDefault;
  doc["mode"] = currentMode;
  String payload;
  serializeJson(doc, payload);
  bool ok = storageOk && writeFileAtomic(SETTINGS_FILE, payload);
  if (!ok) {
    storageOk = false;
    storageRetryPending = true;
  }
  return ok;
}

void loadSettingsFromFlash() {
  ringDefault = DEFAULT_RING_SEC;
  currentMode = "auto";
  if (!storageOk) return;
  File in = LittleFS.open(SETTINGS_FILE, "r");
  if (!in) return;
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, in);
  in.close();
  if (err) return;
  int rd = doc["ring_default"] | static_cast<int>(DEFAULT_RING_SEC);
  if (rd >= MIN_RING_SEC && rd <= MAX_RING_SEC) {
    ringDefault = static_cast<uint8_t>(rd);
  }
  const char *m = doc["mode"] | "auto";
  currentMode = sanitizeMode(String(m));
}

// --------------------------- Schedule document ----------------------------
//
// Canonical document (version 3):
// {
//   "version": 3,
//   "auto": {"start": "YYYY-MM-DD", "stop": "YYYY-MM-DD"},
//   "groups": [
//     {"id": 1, "name": "Будни", "days": [1,2,3,4,5],
//      "entries": [{"time": "08:00", "days": [...], "duration": 5, "label": "..."}]}
//   ],
//   "overrides": [
//     {"date": "YYYY-MM-DD", "mode": "mute"},
//     {"date": "YYYY-MM-DD", "mode": "replace", "based_on": 1,
//      "entries": [{"time": "09:00", "duration": 5, "label": "..."}]},
//     {"date": "YYYY-MM-DD", "mode": "add",
//      "entries": [{"time": "12:30", "duration": 10, "label": "..."}]}
//   ]
// }
// Version 2 documents ("exceptions" with mute/group_id) and version 1 documents
// (a flat "entries" array) are still accepted and migrated in memory.

bool parseDaysMask(JsonVariant value, uint8_t &mask) {
  if (value.isNull()) {
    mask = ALL_DAYS_MASK;
    return true;
  }
  if (!value.is<JsonArray>()) return false;
  JsonArray days = value.as<JsonArray>();
  if (days.size() == 0) return false;
  uint8_t parsed = 0;
  for (JsonVariant dayValue : days) {
    if (!dayValue.is<int>()) return false;
    int day = dayValue.as<int>();
    if (day < 1 || day > 7) return false;
    parsed = static_cast<uint8_t>(parsed | (1U << (day - 1)));
  }
  mask = parsed;
  return true;
}

bool parseScheduleEntry(JsonObject obj, uint8_t groupId, uint8_t overrideSlot,
                        ScheduleEntry &out) {
  JsonVariant timeValue = obj["time"];
  if (!timeValue.is<const char *>()) return false;
  uint16_t minuteOfDay = 0;
  if (!parseTimeOfDay(String(timeValue.as<const char *>()), minuteOfDay)) {
    return false;
  }

  // A schedule bell may carry a weekday mask that narrows the group. Inside an
  // override the date decides, so "days" is ignored there.
  uint8_t daysMask = ALL_DAYS_MASK;
  if (overrideSlot == 0 && !parseDaysMask(obj["days"], daysMask)) return false;

  uint8_t duration = ringDefault;
  JsonVariant durationValue = obj["duration"];
  if (!durationValue.isNull()) {
    if (!durationValue.is<int>()) return false;
    int d = durationValue.as<int>();
    if (d < MIN_RING_SEC || d > MAX_RING_SEC) return false;
    duration = static_cast<uint8_t>(d);
  }

  String label;
  JsonVariant labelValue = obj["label"];
  if (!labelValue.isNull()) {
    if (!labelValue.is<const char *>()) return false;
    label = truncateUtf8(String(labelValue.as<const char *>()), MAX_LABEL_BYTES);
  }

  out.minuteOfDay = minuteOfDay;
  out.daysMask = daysMask;
  out.duration = duration;
  out.groupId = groupId;
  out.overrideSlot = overrideSlot;
  out.label = label;
  return true;
}

bool parsedGroupExists(uint8_t id) {
  for (size_t i = 0; i < parsedGroupCount; ++i) {
    if (parsedGroups[i].id == id) return true;
  }
  return false;
}

// Parses a schedule document into the scratch buffers.
// Returns nullptr on success, otherwise a stable error code string.
const char *parseScheduleDocument(JsonDocument &doc) {
  parsedEntryCount = 0;
  parsedGroupCount = 0;
  parsedOverrideCount = 0;
  parsedAutoStart = 0;
  parsedAutoStop = 0;

  JsonVariant groupsValue = doc["groups"];
  if (!groupsValue.isNull()) {
    if (!groupsValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray groupsArray = groupsValue.as<JsonArray>();
    if (groupsArray.size() > MAX_GROUPS) return "too_many_groups";

    for (JsonVariant groupValue : groupsArray) {
      if (!groupValue.is<JsonObject>()) return "invalid_schedule";
      JsonObject groupObj = groupValue.as<JsonObject>();

      uint8_t groupId = static_cast<uint8_t>(parsedGroupCount + 1);
      JsonVariant idValue = groupObj["id"];
      if (!idValue.isNull()) {
        if (!idValue.is<int>()) return "invalid_schedule";
        int id = idValue.as<int>();
        if (id < 1 || id > 255) return "invalid_schedule";
        if (parsedGroupExists(static_cast<uint8_t>(id))) return "invalid_schedule";
        groupId = static_cast<uint8_t>(id);
      }

      uint8_t groupDays = ALL_DAYS_MASK;
      if (!parseDaysMask(groupObj["days"], groupDays)) return "invalid_schedule";
      if (groupDays == 0) return "invalid_schedule";

      String name;
      JsonVariant nameValue = groupObj["name"];
      if (!nameValue.isNull()) {
        if (!nameValue.is<const char *>()) return "invalid_schedule";
        name = truncateUtf8(String(nameValue.as<const char *>()), MAX_GROUP_NAME_BYTES);
      }

      JsonVariant entriesValue = groupObj["entries"];
      if (entriesValue.isNull() || !entriesValue.is<JsonArray>()) {
        return "invalid_schedule";
      }
      for (JsonVariant item : entriesValue.as<JsonArray>()) {
        if (!item.is<JsonObject>()) return "invalid_schedule";
        if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
        if (!parseScheduleEntry(item.as<JsonObject>(), groupId, 0,
                                parsedEntries[parsedEntryCount])) {
          return "invalid_schedule";
        }
        ++parsedEntryCount;
      }

      parsedGroups[parsedGroupCount].id = groupId;
      parsedGroups[parsedGroupCount].daysMask = groupDays;
      parsedGroups[parsedGroupCount].name = name;
      ++parsedGroupCount;
    }
  } else {
    // Legacy document: a flat list. It becomes one group that is active every
    // day; the per-entry weekday masks still apply.
    JsonVariant entriesValue = doc["entries"];
    if (entriesValue.isNull() || !entriesValue.is<JsonArray>()) {
      return "invalid_schedule";
    }
    JsonArray entriesArray = entriesValue.as<JsonArray>();
    if (entriesArray.size() > MAX_ENTRIES) return "too_many_entries";

    parsedGroups[0].id = 1;
    parsedGroups[0].daysMask = ALL_DAYS_MASK;
    parsedGroups[0].name = String("Расписание");
    parsedGroupCount = 1;

    for (JsonVariant item : entriesArray) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      if (!parseScheduleEntry(item.as<JsonObject>(), 1, 0,
                              parsedEntries[parsedEntryCount])) {
        return "invalid_schedule";
      }
      ++parsedEntryCount;
    }
  }

  // Version 3 replaces "exceptions" with "overrides".
  JsonVariant overridesValue = doc["overrides"];
  JsonVariant exceptionsValue = doc["exceptions"];
  if (!overridesValue.isNull() && !exceptionsValue.isNull()) {
    return "invalid_schedule";
  }

  if (!overridesValue.isNull()) {
    if (!overridesValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray overridesArray = overridesValue.as<JsonArray>();
    if (overridesArray.size() > MAX_OVERRIDES) return "too_many_overrides";

    for (JsonVariant item : overridesArray) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      JsonObject obj = item.as<JsonObject>();

      JsonVariant dateValue = obj["date"];
      if (!dateValue.is<const char *>()) return "invalid_schedule";
      uint32_t dateKey = 0;
      if (!parseDateKey(String(dateValue.as<const char *>()), dateKey)) {
        return "invalid_schedule";
      }
      for (size_t i = 0; i < parsedOverrideCount; ++i) {
        if (parsedOverrides[i].dateKey == dateKey) return "invalid_schedule";
      }

      uint8_t mode = OVERRIDE_MUTE;
      if (!parseOverrideMode(obj["mode"], mode)) return "invalid_schedule";

      uint8_t basedOn = 0;
      JsonVariant basedValue = obj["based_on"];
      if (!basedValue.isNull()) {
        if (!basedValue.is<int>()) return "invalid_schedule";
        int id = basedValue.as<int>();
        if (id < 0 || id > 255) return "invalid_schedule";
        basedOn = static_cast<uint8_t>(id);
      }

      uint8_t slot = static_cast<uint8_t>(parsedOverrideCount + 1);
      if (mode != OVERRIDE_MUTE) {
        JsonVariant entriesValue = obj["entries"];
        if (!entriesValue.isNull()) {
          if (!entriesValue.is<JsonArray>()) return "invalid_schedule";
          for (JsonVariant entry : entriesValue.as<JsonArray>()) {
            if (!entry.is<JsonObject>()) return "invalid_schedule";
            if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
            if (!parseScheduleEntry(entry.as<JsonObject>(), 0, slot,
                                    parsedEntries[parsedEntryCount])) {
              return "invalid_schedule";
            }
            ++parsedEntryCount;
          }
        }
      }

      parsedOverrides[parsedOverrideCount].dateKey = dateKey;
      parsedOverrides[parsedOverrideCount].mode = mode;
      parsedOverrides[parsedOverrideCount].basedOnGroupId = basedOn;
      ++parsedOverrideCount;
    }
  }

  // Version 2 migration: "mute" stays mute, "group_id" becomes a copy of that
  // group's bell list. The next save rewrites storage as version 3.
  if (!exceptionsValue.isNull()) {
    if (!exceptionsValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray exceptionsArray = exceptionsValue.as<JsonArray>();
    if (exceptionsArray.size() > MAX_OVERRIDES) return "too_many_exceptions";

    for (JsonVariant item : exceptionsArray) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      JsonObject obj = item.as<JsonObject>();

      JsonVariant dateValue = obj["date"];
      if (!dateValue.is<const char *>()) return "invalid_schedule";
      uint32_t dateKey = 0;
      if (!parseDateKey(String(dateValue.as<const char *>()), dateKey)) {
        return "invalid_schedule";
      }
      for (size_t i = 0; i < parsedOverrideCount; ++i) {
        if (parsedOverrides[i].dateKey == dateKey) return "invalid_schedule";
      }

      bool mute = false;
      JsonVariant muteValue = obj["mute"];
      if (!muteValue.isNull()) {
        if (!muteValue.is<bool>()) return "invalid_schedule";
        mute = muteValue.as<bool>();
      }

      uint8_t groupId = 0;
      JsonVariant groupIdValue = obj["group_id"];
      if (!groupIdValue.isNull()) {
        if (!groupIdValue.is<int>()) return "invalid_schedule";
        int id = groupIdValue.as<int>();
        if (id < 1 || id > 255 || !parsedGroupExists(static_cast<uint8_t>(id))) {
          return "invalid_schedule";
        }
        groupId = static_cast<uint8_t>(id);
      }
      if (!mute && groupId == 0) return "invalid_schedule";

      uint8_t mode = mute ? OVERRIDE_MUTE : OVERRIDE_REPLACE;
      uint8_t slot = static_cast<uint8_t>(parsedOverrideCount + 1);
      if (mode == OVERRIDE_REPLACE) {
        size_t groupEntryCount = parsedEntryCount;
        for (size_t i = 0; i < groupEntryCount; ++i) {
          if (parsedEntries[i].overrideSlot != 0) continue;
          if (parsedEntries[i].groupId != groupId) continue;
          if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
          parsedEntries[parsedEntryCount] = parsedEntries[i];
          parsedEntries[parsedEntryCount].groupId = 0;
          parsedEntries[parsedEntryCount].overrideSlot = slot;
          ++parsedEntryCount;
        }
      }

      parsedOverrides[parsedOverrideCount].dateKey = dateKey;
      parsedOverrides[parsedOverrideCount].mode = mode;
      parsedOverrides[parsedOverrideCount].basedOnGroupId = groupId;
      ++parsedOverrideCount;
    }
  }

  JsonVariant autoValue = doc["auto"];
  if (!autoValue.isNull()) {
    if (!autoValue.is<JsonObject>()) return "invalid_schedule";
    JsonObject autoObj = autoValue.as<JsonObject>();
    JsonVariant startValue = autoObj["start"];
    if (!startValue.isNull()) {
      if (!startValue.is<const char *>()) return "invalid_schedule";
      if (!parseDateKey(String(startValue.as<const char *>()), parsedAutoStart)) {
        return "invalid_schedule";
      }
    }
    JsonVariant stopValue = autoObj["stop"];
    if (!stopValue.isNull()) {
      if (!stopValue.is<const char *>()) return "invalid_schedule";
      if (!parseDateKey(String(stopValue.as<const char *>()), parsedAutoStop)) {
        return "invalid_schedule";
      }
    }
    if (parsedAutoStart != 0 && parsedAutoStop != 0 &&
        parsedAutoStop < parsedAutoStart) {
      return "invalid_schedule";
    }
  }

  return nullptr;
}

void applyParsedSchedule() {
  for (size_t i = 0; i < parsedEntryCount; ++i) entries[i] = parsedEntries[i];
  entryCount = parsedEntryCount;
  for (size_t i = 0; i < parsedGroupCount; ++i) groups[i] = parsedGroups[i];
  groupCount = parsedGroupCount;
  for (size_t i = 0; i < parsedOverrideCount; ++i) overrides[i] = parsedOverrides[i];
  overrideCount = parsedOverrideCount;
  autoStartDate = parsedAutoStart;
  autoStopDate = parsedAutoStop;
}

// Drops parsed overrides older than 30 days so the flash file does not grow
// without bound. Entries follow their override slot.
void pruneParsedOverrides(uint32_t todayKey) {
  if (!timeIsValid()) return;
  uint32_t cutoff = addDaysToDateKey(todayKey, -30);
  uint8_t slotMap[MAX_OVERRIDES + 1];
  for (size_t i = 0; i <= MAX_OVERRIDES; ++i) slotMap[i] = 0;

  size_t kept = 0;
  for (size_t i = 0; i < parsedOverrideCount; ++i) {
    if (parsedOverrides[i].dateKey >= cutoff) {
      slotMap[i + 1] = static_cast<uint8_t>(kept + 1);
      if (kept != i) parsedOverrides[kept] = parsedOverrides[i];
      ++kept;
    }
  }
  parsedOverrideCount = kept;

  size_t written = 0;
  for (size_t i = 0; i < parsedEntryCount; ++i) {
    uint8_t slot = parsedEntries[i].overrideSlot;
    if (slot != 0) {
      uint8_t mapped = slot <= MAX_OVERRIDES ? slotMap[slot] : 0;
      if (mapped == 0) continue;
      parsedEntries[written] = parsedEntries[i];
      parsedEntries[written].overrideSlot = mapped;
      ++written;
    } else {
      parsedEntries[written] = parsedEntries[i];
      ++written;
    }
  }
  parsedEntryCount = written;
}

void loadScheduleFromFlash() {
  entryCount = 0;
  groupCount = 0;
  overrideCount = 0;
  autoStartDate = 0;
  autoStopDate = 0;
  if (!storageOk) return;
  File in = LittleFS.open(SCHEDULE_FILE, "r");
  if (!in) return;
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, in);
  in.close();
  if (err) return;
  if (parseScheduleDocument(doc) != nullptr) return;
  applyParsedSchedule();
}

void writeDays(JsonObject &obj, uint8_t mask) {
  JsonArray days = obj["days"].to<JsonArray>();
  for (uint8_t bit = 0; bit < 7; ++bit) {
    if (mask & (1U << bit)) days.add(bit + 1);
  }
}

void appendEntry(JsonArray &arr, const ScheduleEntry &entry, bool withDays) {
  JsonObject obj = arr.add<JsonObject>();
  obj["time"] = formatTimeOfDay(entry.minuteOfDay);
  if (withDays) writeDays(obj, entry.daysMask);
  obj["duration"] = entry.duration;
  obj["label"] = entry.label;
}

void buildScheduleDocument(JsonDocument &doc, bool includeLimits) {
  doc["version"] = 3;
  if (includeLimits) {
    doc["max_entries"] = static_cast<unsigned>(MAX_ENTRIES);
    doc["max_groups"] = static_cast<unsigned>(MAX_GROUPS);
    doc["max_overrides"] = static_cast<unsigned>(MAX_OVERRIDES);
  }
  if (autoStartDate != 0 || autoStopDate != 0) {
    JsonObject autoObj = doc["auto"].to<JsonObject>();
    if (autoStartDate != 0) autoObj["start"] = formatDateKey(autoStartDate);
    if (autoStopDate != 0) autoObj["stop"] = formatDateKey(autoStopDate);
  }

  JsonArray groupsArray = doc["groups"].to<JsonArray>();
  for (size_t g = 0; g < groupCount; ++g) {
    JsonObject groupObj = groupsArray.add<JsonObject>();
    groupObj["id"] = groups[g].id;
    groupObj["name"] = groups[g].name;
    writeDays(groupObj, groups[g].daysMask);
    JsonArray entriesArray = groupObj["entries"].to<JsonArray>();
    for (size_t i = 0; i < entryCount; ++i) {
      if (entries[i].overrideSlot != 0) continue;
      if (entries[i].groupId == groups[g].id) appendEntry(entriesArray, entries[i], true);
    }
  }

  JsonArray overridesArray = doc["overrides"].to<JsonArray>();
  for (size_t o = 0; o < overrideCount; ++o) {
    JsonObject obj = overridesArray.add<JsonObject>();
    obj["date"] = formatDateKey(overrides[o].dateKey);
    obj["mode"] = overrideModeName(overrides[o].mode);
    if (overrides[o].basedOnGroupId != 0) obj["based_on"] = overrides[o].basedOnGroupId;
    if (overrides[o].mode == OVERRIDE_MUTE) continue;
    JsonArray entriesArray = obj["entries"].to<JsonArray>();
    for (size_t i = 0; i < entryCount; ++i) {
      if (entries[i].overrideSlot == o + 1) {
        // Weekdays are irrelevant inside an override, so they are not written.
        appendEntry(entriesArray, entries[i], false);
      }
    }
  }

  // Legacy flat view so older clients can still read the weekly schedule.
  JsonArray flat = doc["entries"].to<JsonArray>();
  for (size_t i = 0; i < entryCount; ++i) {
    if (entries[i].overrideSlot == 0) appendEntry(flat, entries[i], true);
  }
}

bool saveScheduleToFlash() {
  JsonDocument doc;
  buildScheduleDocument(doc, false);
  String payload;
  serializeJson(doc, payload);
  bool ok = storageOk && writeFileAtomic(SCHEDULE_FILE, payload);
  if (!ok) {
    storageOk = false;
    storageRetryPending = true;
  }
  return ok;
}

void tryMountStorage() {
  storageOk = LittleFS.begin(true);
  if (storageOk) {
    loadSettingsFromFlash();
    loadScheduleFromFlash();
    storageRetryPending = false;
  } else {
    storageRetryPending = true;
  }
}

// ------------------------------ Ringing -----------------------------------

void startRing(uint16_t seconds) {
  if (seconds < MIN_RING_SEC) seconds = MIN_RING_SEC;
  if (seconds > MAX_RING_SEC) seconds = MAX_RING_SEC;
  ringing = true;
  ringEndMs = millis() + static_cast<uint32_t>(seconds) * 1000UL;
  ringLeftSec = seconds;
  relayOn();
}

void stopRing() {
  ringing = false;
  ringLeftSec = 0;
  relayOff();
}

void updateRing() {
  if (!ringing) {
    ringLeftSec = 0;
    return;
  }
  int32_t remaining = static_cast<int32_t>(ringEndMs - millis());
  if (remaining <= 0) {
    stopRing();
    return;
  }
  ringLeftSec = (remaining + 999) / 1000;
}

// -------------------------------- RTC -------------------------------------

void pollRtc() {
  if (!rtcOk) {
    cachedNowValid = false;
    return;
  }
  cachedNow = rtc.now();
  cachedNowValid = true;
  if (!suppressMinuteSet && timeIsValid()) {
    suppressMinuteKey = minuteKey(cachedNow);
    lastScheduleMinute = suppressMinuteKey;
    suppressMinuteSet = true;
  }
}

void tryDetectRtc() {
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  bool found = rtc.begin(&Wire);
  rtcOk = found;
  if (found) {
    rtcLostPower = rtc.lostPower();
    cachedNowValid = true;
    cachedNow = rtc.now();
  } else {
    rtcLostPower = true;
    cachedNowValid = false;
  }
}

// Reads the DS3231 status register. Returns false on an I2C error, in which
// case the previously known OSF state is kept.
bool readOscillatorStopFlag(bool &flag) {
  Wire.beginTransmission(0x68);
  Wire.write(0x0F);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(0x68, 1) != 1) return false;
  flag = (Wire.read() & 0x80) != 0;
  return true;
}

void refreshRtcStatus() {
  if (!rtcOk) return;
  bool flag = false;
  if (!readOscillatorStopFlag(flag)) {
    // The DS3231 stopped acknowledging I2C. Report rtc_not_found and let the
    // periodic re-detection bring it back when the connection is restored.
    rtcOk = false;
    cachedNowValid = false;
    return;
  }
  rtcLostPower = flag;
}

void handleRtcTimers() {
  uint32_t nowMs = millis();
  if (!rtcOk) {
    if (nowMs - rtcLastRetryMs >= RTC_RETRY_MS) {
      rtcLastRetryMs = nowMs;
      tryDetectRtc();
      if (rtcOk) {
        rtcLastStatusMs = nowMs;
        rtcLastPollMs = nowMs;
      }
    }
    return;
  }
  if (nowMs - rtcLastPollMs >= RTC_POLL_MS) {
    rtcLastPollMs = nowMs;
    pollRtc();
  }
  if (nowMs - rtcLastStatusMs >= RTC_RETRY_MS) {
    rtcLastStatusMs = nowMs;
    refreshRtcStatus();
  }
}

// ------------------------------ Schedule ----------------------------------

// Bells resolved for one date. The buffer is static so the recursion-free
// evaluation never touches the stack.
struct DayBell {
  uint16_t minuteOfDay = 0;
  uint8_t duration = DEFAULT_RING_SEC;
  uint16_t entryIndex = 0;
};

DayBell dayBells[MAX_ENTRIES];
size_t dayBellCount = 0;

bool autoAllowsDate(uint32_t dateKey) {
  if (autoStartDate != 0 && dateKey < autoStartDate) return false;
  if (autoStopDate != 0 && dateKey > autoStopDate) return false;
  return true;
}

int findOverrideIndex(uint32_t dateKey) {
  for (size_t i = 0; i < overrideCount; ++i) {
    if (overrides[i].dateKey == dateKey) return static_cast<int>(i);
  }
  return -1;
}

void appendDayBell(const ScheduleEntry &entry, size_t index) {
  if (dayBellCount >= MAX_ENTRIES) return;
  dayBells[dayBellCount].minuteOfDay = entry.minuteOfDay;
  dayBells[dayBellCount].duration = entry.duration;
  dayBells[dayBellCount].entryIndex = static_cast<uint16_t>(index);
  ++dayBellCount;
}

// Resolves a date exactly like BellSchedule.bellsFor() in the Android app:
// auto window, first matching group, then mute / replace / add.
void collectDayBells(uint32_t dateKey, uint8_t weekdayMask) {
  dayBellCount = 0;
  if (!autoAllowsDate(dateKey)) return;

  int overrideIndex = findOverrideIndex(dateKey);
  const DayOverride *override = overrideIndex >= 0 ? &overrides[overrideIndex] : nullptr;
  if (override != nullptr && override->mode == OVERRIDE_MUTE) return;

  if (override == nullptr || override->mode == OVERRIDE_ADD) {
    uint8_t groupId = 0;
    for (size_t g = 0; g < groupCount; ++g) {
      if (groups[g].daysMask & weekdayMask) {
        groupId = groups[g].id;
        break;
      }
    }
    if (groupId != 0) {
      for (size_t i = 0; i < entryCount; ++i) {
        const ScheduleEntry &entry = entries[i];
        if (entry.overrideSlot != 0 || entry.groupId != groupId) continue;
        if (entry.daysMask != 0 && (entry.daysMask & weekdayMask) == 0) continue;
        appendDayBell(entry, i);
      }
    }
  }

  if (override == nullptr) return;
  uint8_t slot = static_cast<uint8_t>(overrideIndex + 1);
  for (size_t i = 0; i < entryCount; ++i) {
    if (entries[i].overrideSlot == slot) appendDayBell(entries[i], i);
  }
}

void checkSchedule() {
  if (!timeIsValid() || currentMode != "auto" || ringing) return;
  if (cachedNow.second() != 0) return;

  uint32_t key = minuteKey(cachedNow);
  if (key == lastScheduleMinute) return;
  lastScheduleMinute = key;
  if (suppressMinuteSet && key == suppressMinuteKey) return;

  uint32_t today = dateKeyOf(cachedNow);
  uint8_t todayMask = static_cast<uint8_t>(1U << (isoWeekday(cachedNow) - 1));
  collectDayBells(today, todayMask);

  uint16_t minuteOfDay =
      static_cast<uint16_t>(cachedNow.hour() * 60 + cachedNow.minute());
  uint8_t bestDuration = 0;
  for (size_t i = 0; i < dayBellCount; ++i) {
    if (dayBells[i].minuteOfDay != minuteOfDay) continue;
    if (dayBells[i].duration > bestDuration) bestDuration = dayBells[i].duration;
  }

  if (bestDuration > 0) startRing(bestDuration);
}

// -------------------------------- LED -------------------------------------

// Severity of the current fault, if any.
//   CRITICAL - the bell cannot work at all (no RTC, no storage, no AP)
//   WARNING  - the bell still rings on demand, but the time is not valid, so
//              scheduled bells will not fire until the time is set
enum LedFault : uint8_t {
  LED_FAULT_NONE,
  LED_FAULT_WARNING,
  LED_FAULT_CRITICAL
};

LedFault currentLedFault() {
  if (!rtcOk || !storageOk || !apOk) return LED_FAULT_CRITICAL;
  if (!timeIsValid()) return LED_FAULT_WARNING;
  return LED_FAULT_NONE;
}

inline bool blinkPhase(uint32_t nowMs, uint32_t halfPeriodMs) {
  return (nowMs / halfPeriodMs) % 2UL == 0UL;
}

// Colour mapping on the KY-029 dual-colour module:
//   steady green  - the bell is ringing (nothing blinks while it sounds)
//   green 1 Hz    - idle and healthy
//   steady red    - warning: time not set, so the schedule will not fire
//   red 2 Hz      - critical error: RTC, storage or access point failed
void updateLed() {
  uint32_t nowMs = millis();

  if (ringing) {
    setLed(true, false);
    return;
  }

  switch (currentLedFault()) {
    case LED_FAULT_CRITICAL:
      setLed(false, blinkPhase(nowMs, LED_FAULT_BLINK_MS));
      return;
    case LED_FAULT_WARNING:
      setLed(false, true);
      return;
    case LED_FAULT_NONE:
    default:
      setLed(blinkPhase(nowMs, LED_OK_BLINK_MS), false);
      return;
  }
}

// ------------------------------ Wi-Fi / AP --------------------------------

void startAccessPoint() {
  WiFi.mode(WIFI_AP);
  IPAddress ip(192, 168, 4, 1);
  IPAddress gateway(192, 168, 4, 1);
  IPAddress subnet(255, 255, 255, 0);
  WiFi.softAPConfig(ip, gateway, subnet);
  apOk = WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL, 0, AP_MAX_CLIENTS);
  apLastRetryMs = millis();
  apLastIpCheckMs = apLastRetryMs;
}

void handleApTimers() {
  uint32_t nowMs = millis();
  if (!apOk && nowMs - apLastRetryMs >= AP_RETRY_MS) {
    startAccessPoint();
    return;
  }
  if (apOk && nowMs - apLastIpCheckMs >= AP_IP_CHECK_MS) {
    apLastIpCheckMs = nowMs;
    IPAddress expected(192, 168, 4, 1);
    if (WiFi.softAPIP() != expected) {
      apOk = false;
    }
  }
}

// -------------------------------- HTTP ------------------------------------

void sendJson(int code, const String &payload) {
  // WebServer::enableCORS() adds the Access-Control-* headers to every
  // response, including error responses and OPTIONS preflight replies.
  server.send(code, "application/json; charset=utf-8", payload);
}

void sendError(int code, const char *errorCode) {
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = errorCode;
  String payload;
  serializeJson(doc, payload);
  sendJson(code, payload);
}

bool tokenAccepted() {
  if (API_TOKEN[0] == '\0') return true;
  if (server.hasHeader("X-Token") && server.header("X-Token") == API_TOKEN) {
    return true;
  }
  if (server.hasArg("token") && server.arg("token") == API_TOKEN) return true;
  return false;
}

bool requireToken() {
  if (tokenAccepted()) return true;
  sendError(401, "unauthorized");
  return false;
}

bool readJsonBody(JsonDocument &doc, const char *&errorCode) {
  if (!server.hasArg("plain") || server.arg("plain").length() == 0) {
    errorCode = "invalid_json";
    return false;
  }
  // Reject oversized documents before ArduinoJson allocates anything, and keep
  // the nesting shallow so a hostile body cannot exhaust the heap.
  if (server.arg("plain").length() > MAX_SCHEDULE_BODY_BYTES) {
    errorCode = "too_many_entries";
    return false;
  }
  DeserializationError err = deserializeJson(
      doc, server.arg("plain"), DeserializationOption::NestingLimit(8));
  if (err) {
    errorCode = "invalid_json";
    return false;
  }
  return true;
}

void handleOptions() { server.send(204, "text/plain", ""); }

void sendStatus() {
  JsonDocument doc;
  if (timeIsValid()) {
    doc["time"] = formatDateTime(cachedNow);
  } else {
    doc["time"] = nullptr;
  }
  doc["rtc_ok"] = rtcOk;
  doc["mode"] = currentMode;
  doc["ringing"] = ringing;
  doc["ring_left"] = ringing ? ringLeftSec : 0;
  doc["ring_default"] = ringDefault;
  doc["entries"] = static_cast<unsigned>(entryCount);
  doc["groups"] = static_cast<unsigned>(groupCount);
  doc["overrides"] = static_cast<unsigned>(overrideCount);
  if (autoStartDate != 0 || autoStopDate != 0) {
    JsonObject autoObj = doc["auto"].to<JsonObject>();
    if (autoStartDate != 0) autoObj["start"] = formatDateKey(autoStartDate);
    if (autoStopDate != 0) autoObj["stop"] = formatDateKey(autoStopDate);
  }
  JsonArray errors = doc["errors"].to<JsonArray>();
  if (!rtcOk) errors.add("rtc_not_found");
  if (rtcOk && !timeIsValid()) errors.add("time_not_set");
  if (!storageOk) errors.add("storage_error");
  if (!apOk) errors.add("wifi_ap_error");
  doc["fw"] = FW_VERSION;
  doc["api"] = API_VERSION;
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

void handleGetStatus() {
  if (!requireToken()) return;
  sendStatus();
}

void handlePostRing() {
  if (!requireToken()) return;

  int seconds = ringDefault;
  bool haveSeconds = false;

  if (server.hasArg("sec")) {
    String raw = server.arg("sec");
    raw.trim();
    if (raw.length() == 0) {
      sendError(400, "invalid_sec");
      return;
    }
    for (size_t i = 0; i < raw.length(); ++i) {
      if (raw[i] < '0' || raw[i] > '9') {
        sendError(400, "invalid_sec");
        return;
      }
    }
    long value = raw.toInt();
    if (value < MIN_RING_SEC || value > MAX_RING_SEC) {
      sendError(400, "invalid_sec");
      return;
    }
    seconds = static_cast<int>(value);
    haveSeconds = true;
  }

  if (server.hasArg("plain") && server.arg("plain").length() > 0) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, server.arg("plain"));
    if (err) {
      sendError(400, "invalid_json");
      return;
    }
    JsonVariant secValue = doc["sec"];
    if (!secValue.isNull()) {
      if (!secValue.is<int>()) {
        sendError(400, "invalid_sec");
        return;
      }
      int value = secValue.as<int>();
      if (value < MIN_RING_SEC || value > MAX_RING_SEC) {
        sendError(400, "invalid_sec");
        return;
      }
      seconds = value;
      haveSeconds = true;
    }
  }

  if (!haveSeconds && seconds < MIN_RING_SEC) seconds = DEFAULT_RING_SEC;
  startRing(static_cast<uint16_t>(seconds));

  JsonDocument doc;
  doc["ok"] = true;
  doc["ringing"] = true;
  doc["ring_left"] = ringLeftSec;
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

void handlePostStop() {
  if (!requireToken()) return;
  stopRing();
  JsonDocument doc;
  doc["ok"] = true;
  doc["ringing"] = false;
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

void handleGetMode() {
  if (!requireToken()) return;
  JsonDocument doc;
  doc["mode"] = currentMode;
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

void handlePostMode() {
  if (!requireToken()) return;
  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }
  JsonVariant modeValue = doc["mode"];
  if (!modeValue.is<const char *>()) {
    sendError(400, "invalid_mode");
    return;
  }
  String newMode = String(modeValue.as<const char *>());
  if (newMode != "auto" && newMode != "manual") {
    sendError(400, "invalid_mode");
    return;
  }

  String previousMode = currentMode;
  currentMode = newMode;
  if (!saveSettingsToFlash()) {
    currentMode = previousMode;
    sendError(500, "storage_error");
    return;
  }

  JsonDocument out;
  out["ok"] = true;
  out["mode"] = currentMode;
  String payload;
  serializeJson(out, payload);
  sendJson(200, payload);
}

void handlePostTime() {
  if (!requireToken()) return;
  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }
  JsonVariant dtValue = doc["datetime"];
  if (!dtValue.is<const char *>()) {
    sendError(400, "invalid_datetime");
    return;
  }
  DateTime requested;
  if (!parseDateTime(String(dtValue.as<const char *>()), requested)) {
    sendError(400, "invalid_datetime");
    return;
  }
  if (!rtcOk) {
    sendError(503, "rtc_not_found");
    return;
  }

  rtc.adjust(requested);
  rtcLostPower = !clearOscillatorStopFlag();
  cachedNow = requested;
  cachedNowValid = true;
  suppressMinuteKey = minuteKey(requested);
  lastScheduleMinute = suppressMinuteKey;
  suppressMinuteSet = true;

  JsonDocument out;
  out["ok"] = true;
  out["time"] = formatDateTime(requested);
  String payload;
  serializeJson(out, payload);
  sendJson(200, payload);
}

void handleGetSchedule() {
  if (!requireToken()) return;
  JsonDocument doc;
  buildScheduleDocument(doc, true);
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

// Resolved bell list for one date, useful for checking that the app's preview
// and the device agree.
void handleGetScheduleDay() {
  if (!requireToken()) return;
  if (!server.hasArg("date")) {
    sendError(400, "invalid_date");
    return;
  }
  uint32_t dateKey = 0;
  if (!parseDateKey(server.arg("date"), dateKey)) {
    sendError(400, "invalid_date");
    return;
  }

  uint8_t weekdayMask =
      static_cast<uint8_t>(1U << (isoWeekdayFromDateKey(dateKey) - 1));
  collectDayBells(dateKey, weekdayMask);

  // Merge bells that share a minute (keeping the longer duration) and sort by
  // time into a small scratch buffer.
  static DayBell merged[MAX_ENTRIES];
  size_t mergedCount = 0;
  bool taken[MAX_ENTRIES];
  for (size_t i = 0; i < dayBellCount; ++i) taken[i] = false;
  for (size_t i = 0; i < dayBellCount; ++i) {
    if (taken[i]) continue;
    DayBell value = dayBells[i];
    for (size_t j = i + 1; j < dayBellCount; ++j) {
      if (dayBells[j].minuteOfDay != value.minuteOfDay) continue;
      taken[j] = true;
      if (dayBells[j].duration > value.duration) value = dayBells[j];
    }
    size_t position = mergedCount;
    while (position > 0 && merged[position - 1].minuteOfDay > value.minuteOfDay) {
      merged[position] = merged[position - 1];
      --position;
    }
    merged[position] = value;
    ++mergedCount;
  }

  JsonDocument doc;
  doc["date"] = formatDateKey(dateKey);
  JsonArray bells = doc["bells"].to<JsonArray>();
  for (size_t i = 0; i < mergedCount; ++i) {
    JsonObject obj = bells.add<JsonObject>();
    obj["time"] = formatTimeOfDay(merged[i].minuteOfDay);
    obj["duration"] = merged[i].duration;
    obj["label"] = entries[merged[i].entryIndex].label;
  }
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

void handlePostSchedule() {
  if (!requireToken()) return;

  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }

  const char *validationError = parseScheduleDocument(doc);
  if (validationError != nullptr) {
    sendError(400, validationError);
    return;
  }

  if (timeIsValid()) pruneParsedOverrides(dateKeyOf(cachedNow));

  // Commit the validated document, then persist exactly that state. The write
  // is atomic, so a failure keeps the previous file; reloading it puts the
  // device back to the schedule it had before this request.
  applyParsedSchedule();
  if (!saveScheduleToFlash()) {
    loadScheduleFromFlash();
    sendError(500, "storage_error");
    return;
  }

  JsonDocument out;
  out["ok"] = true;
  out["entries"] = static_cast<unsigned>(entryCount);
  out["groups"] = static_cast<unsigned>(groupCount);
  out["overrides"] = static_cast<unsigned>(overrideCount);
  String payload;
  serializeJson(out, payload);
  sendJson(200, payload);
}

void handleGetSettings() {
  if (!requireToken()) return;
  JsonDocument doc;
  doc["ring_default"] = ringDefault;
  String payload;
  serializeJson(doc, payload);
  sendJson(200, payload);
}

void handlePostSettings() {
  if (!requireToken()) return;
  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }
  JsonVariant value = doc["ring_default"];
  if (!value.is<int>()) {
    sendError(400, "invalid_ring_default");
    return;
  }
  int newDefault = value.as<int>();
  if (newDefault < MIN_RING_SEC || newDefault > MAX_RING_SEC) {
    sendError(400, "invalid_ring_default");
    return;
  }

  uint8_t previous = ringDefault;
  ringDefault = static_cast<uint8_t>(newDefault);
  if (!saveSettingsToFlash()) {
    ringDefault = previous;
    sendError(500, "storage_error");
    return;
  }

  JsonDocument out;
  out["ok"] = true;
  out["ring_default"] = ringDefault;
  String payload;
  serializeJson(out, payload);
  sendJson(200, payload);
}

void handleRoot() {
  if (!tokenAccepted()) {
    sendError(401, "unauthorized");
    return;
  }
  static const char PAGE[] PROGMEM = R"HTML(<!DOCTYPE html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BellTimer</title>
<style>
body{font-family:system-ui,sans-serif;margin:16px;background:#111;color:#eee}
button{font-size:1rem;padding:8px 14px;margin:4px;border-radius:8px;border:0;background:#3b82f6;color:#fff}
pre{background:#222;padding:10px;border-radius:8px;white-space:pre-wrap}
input{width:80px;padding:6px;margin:4px;border-radius:6px;border:1px solid #555;background:#222;color:#eee}
</style></head><body>
<h1>BellTimer</h1>
<p><b>Статус:</b></p><pre id="status">...</pre>
<p><input id="sec" type="number" min="1" max="60" value="5">
<button onclick="ring()">Звонок</button>
<button onclick="stop()">Стоп</button></p>
<p><button onclick="setMode('auto')">AUTO</button>
<button onclick="setMode('manual')">MANUAL</button></p>
<p><input id="token" placeholder="X-Token" size="16">
<button onclick="refresh()">Обновить</button></p>
<script>
function headers(){const t=document.getElementById('token').value;return t?{'X-Token':t}:{}}
async function call(path,opts){const r=await fetch(path,Object.assign({headers:headers()},opts||{}));document.getElementById('status').textContent=await r.text();}
function refresh(){call('/status');}
function ring(){call('/ring',{method:'POST',headers:Object.assign({'Content-Type':'application/json'},headers()),body:JSON.stringify({sec:+document.getElementById('sec').value})});}
function stop(){call('/stop',{method:'POST'});}
function setMode(m){call('/mode',{method:'POST',headers:Object.assign({'Content-Type':'application/json'},headers()),body:JSON.stringify({mode:m})});}
refresh();
</script></body></html>)HTML";
  server.send_P(200, "text/html; charset=utf-8", PAGE);
}

void handleNotFound() { sendError(404, "not_found"); }

void setupHttpRoutes() {
  server.enableCORS(true);

  const char *paths[] = {"/",     "/status", "/ring",    "/stop",
                         "/mode", "/time",   "/schedule", "/settings",
                         "/schedule/day"};
  for (const char *path : paths) {
    server.on(path, HTTP_OPTIONS, handleOptions);
  }

  server.on("/", HTTP_GET, handleRoot);
  server.on("/status", HTTP_GET, handleGetStatus);
  server.on("/ring", HTTP_POST, handlePostRing);
  server.on("/stop", HTTP_POST, handlePostStop);
  server.on("/mode", HTTP_GET, handleGetMode);
  server.on("/mode", HTTP_POST, handlePostMode);
  server.on("/time", HTTP_POST, handlePostTime);
  server.on("/schedule", HTTP_GET, handleGetSchedule);
  server.on("/schedule", HTTP_POST, handlePostSchedule);
  server.on("/schedule/day", HTTP_GET, handleGetScheduleDay);
  server.on("/settings", HTTP_GET, handleGetSettings);
  server.on("/settings", HTTP_POST, handlePostSettings);
  server.onNotFound(handleNotFound);
}

}  // namespace

void setup() {
  // Force the active-low relay off before the pin becomes an output.
  digitalWrite(PIN_RELAY, HIGH);
  pinMode(PIN_RELAY, OUTPUT);
  relayOff();

  // KY-029: drive the dark level before the pins become outputs.
  digitalWrite(PIN_LED_GREEN, ledOffLevel());
  digitalWrite(PIN_LED_RED, ledOffLevel());
  pinMode(PIN_LED_GREEN, OUTPUT);
  pinMode(PIN_LED_RED, OUTPUT);
  setLed(false, false);

  Serial.begin(115200);

  rtcLastRetryMs = millis() - RTC_RETRY_MS;
  tryDetectRtc();
  if (!rtcOk) {
    rtcLostPower = true;
  }

  tryMountStorage();

  startAccessPoint();

  setupHttpRoutes();
  server.begin();

  rtcLastPollMs = millis();
  rtcLastStatusMs = millis();
}

void loop() {
  server.handleClient();
  handleRtcTimers();
  handleApTimers();
  updateRing();
  checkSchedule();
  updateLed();

  if (storageRetryPending && millis() - storageLastRetryMs >= STORAGE_RETRY_MS) {
    storageLastRetryMs = millis();
    tryMountStorage();
  }
}
