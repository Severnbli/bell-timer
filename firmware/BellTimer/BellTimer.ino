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

const char *const FW_VERSION = "1.3.0";

// API level reported by GET /status. Level 4 replaces groups/overrides with
// ordered packs (base packs plus corrections).
const uint8_t API_VERSION = 4;

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

// A schedule is an ordered list of packs ("пачки"). A base pack contributes
// its entries; a correction cancels the entries of every base pack above it.
// Packs can have a date range, individual dates and a yearly repeat. Auto mode
// can still be limited to a global date range. The limits below are mirrored
// by the Android app (data/Models.kt).
const size_t MAX_PACKS = 12;          // ordered packs
const size_t MAX_PACK_ENTRIES = 16;   // entries inside one pack
const size_t MAX_PACK_DATES = 24;     // individual dates inside one pack
const size_t MAX_ENTRIES = MAX_PACKS * MAX_PACK_ENTRIES;
const size_t MAX_LABEL_BYTES = 32;    // About 10 Cyrillic characters in UTF-8.
const size_t MAX_PACK_NAME_BYTES = 32;
// Bodies larger than this are rejected before parsing, so an oversized
// document cannot exhaust the heap.
const size_t MAX_SCHEDULE_BODY_BYTES = 32768;
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
  uint16_t minuteOfDay = 0;       // 0..1439
  uint16_t pairMinuteOfDay = 0xFFFF;  // 0xFFFF = no pair
  uint8_t daysMask = ALL_DAYS_MASK;
  uint8_t packId = 0;             // owning pack
  String label;
};

enum PackKind : uint8_t {
  PACK_BASE = 0,
  PACK_CORRECTION = 1,
};

struct SchedulePack {
  uint8_t id = 0;
  uint8_t kind = PACK_BASE;
  bool enabled = true;
  bool yearly = false;
  bool mute = false;  // legacy correction that only cancels the packs above
  uint32_t fromKey = 0;  // YYYYMMDD, 0 = no limit
  uint32_t toKey = 0;
  uint32_t dates[MAX_PACK_DATES];  // 0 = unused
  uint8_t dateCount = 0;
  String name;
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

SchedulePack packs[MAX_PACKS];
size_t packCount = 0;

// Auto mode date range. A zero key means "no limit".
uint32_t autoStartDate = 0;
uint32_t autoStopDate = 0;

// Scratch buffers filled by parseScheduleDocument() and committed only after
// the document has been validated and written to flash.
ScheduleEntry parsedEntries[MAX_ENTRIES];
size_t parsedEntryCount = 0;
SchedulePack parsedPacks[MAX_PACKS];
size_t parsedPackCount = 0;
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

const char *packKindName(uint8_t kind) {
  return kind == PACK_CORRECTION ? "correction" : "base";
}

bool parsePackKind(JsonVariant value, uint8_t &kind) {
  if (!value.is<const char *>()) return false;
  String raw = String(value.as<const char *>());
  if (raw == "base") {
    kind = PACK_BASE;
  } else if (raw == "correction") {
    kind = PACK_CORRECTION;
  } else {
    return false;
  }
  return true;
}

bool sameMonthDay(uint32_t a, uint32_t b) {
  return ((a / 100UL) % 100UL) == ((b / 100UL) % 100UL) && (a % 100UL) == (b % 100UL);
}

uint32_t monthDayKey(uint32_t dateKey) {
  return ((dateKey / 100UL) % 100UL) * 100UL + (dateKey % 100UL);
}

bool rangeAllowsDate(const SchedulePack &pack, uint32_t dateKey) {
  if (pack.fromKey == 0 && pack.toKey == 0) return false;
  if (pack.yearly) {
    uint32_t value = monthDayKey(dateKey);
    uint32_t start = pack.fromKey != 0 ? monthDayKey(pack.fromKey) : 0;
    uint32_t end = pack.toKey != 0 ? monthDayKey(pack.toKey) : 0;
    if (start != 0 && end != 0) {
      if (start > end) return value >= start || value <= end;
      return value >= start && value <= end;
    }
    if (start != 0) return value >= start;
    return value <= end;
  }
  if (pack.fromKey != 0 && dateKey < pack.fromKey) return false;
  if (pack.toKey != 0 && dateKey > pack.toKey) return false;
  return true;
}

bool packAllowsDate(const SchedulePack &pack, uint32_t dateKey) {
  bool hasRange = pack.fromKey != 0 || pack.toKey != 0;
  bool hasDates = pack.dateCount > 0;
  if (!hasRange && !hasDates) return true;
  if (hasRange && rangeAllowsDate(pack, dateKey)) return true;
  for (uint8_t i = 0; i < pack.dateCount; ++i) {
    if (pack.yearly ? sameMonthDay(pack.dates[i], dateKey) : pack.dates[i] == dateKey) {
      return true;
    }
  }
  return false;
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
// Canonical document (version 4):
// {
//   "version": 4,
//   "auto": {"start": "YYYY-MM-DD", "stop": "YYYY-MM-DD"},
//   "packs": [
//     {"id": 1, "name": "Будни", "kind": "base", "enabled": true,
//      "yearly": false, "from": "YYYY-MM-DD", "to": "YYYY-MM-DD",
//      "dates": ["YYYY-MM-DD"],
//      "entries": [{"time": "08:00", "pair": "08:45", "days": [...], "label": "..."}]},
//     {"id": 2, "name": "Коррекция", "kind": "correction", "dates": ["YYYY-MM-DD"],
//      "entries": [{"time": "10:00", "days": [...]}]}
//   ]
// }
// Version 1 (flat "entries"), version 2 ("exceptions") and version 3
// ("groups"/"overrides") documents are migrated in memory.

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

bool parseScheduleEntry(JsonObject obj, uint8_t packId, ScheduleEntry &out) {
  JsonVariant timeValue = obj["time"];
  if (!timeValue.is<const char *>()) return false;
  uint16_t minuteOfDay = 0;
  if (!parseTimeOfDay(String(timeValue.as<const char *>()), minuteOfDay)) {
    return false;
  }

  uint16_t pairMinuteOfDay = 0xFFFF;
  JsonVariant pairValue = obj["pair"];
  if (!pairValue.isNull()) {
    if (!pairValue.is<const char *>()) return false;
    uint16_t parsedPair = 0;
    if (!parseTimeOfDay(String(pairValue.as<const char *>()), parsedPair)) return false;
    pairMinuteOfDay = parsedPair;
  }

  uint8_t daysMask = ALL_DAYS_MASK;
  JsonVariant daysValue = obj["days"];
  if (!daysValue.isNull()) {
    if (!daysValue.is<JsonArray>()) return false;
    // An empty list means \"every day\", matching the app's decoder.
    if (daysValue.as<JsonArray>().size() > 0 && !parseDaysMask(daysValue, daysMask)) {
      return false;
    }
  }

  String label;
  JsonVariant labelValue = obj["label"];
  if (!labelValue.isNull()) {
    if (!labelValue.is<const char *>()) return false;
    label = truncateUtf8(String(labelValue.as<const char *>()), MAX_LABEL_BYTES);
  }

  out.minuteOfDay = minuteOfDay;
  out.pairMinuteOfDay = pairMinuteOfDay;
  out.daysMask = daysMask;
  out.packId = packId;
  out.label = label;
  return true;
}

bool parsedPackExists(uint8_t id) {
  for (size_t i = 0; i < parsedPackCount; ++i) {
    if (parsedPacks[i].id == id) return true;
  }
  return false;
}

// Appends a new parsed pack and returns its id (0 when the limit is reached).
uint8_t beginParsedPack(uint8_t kind, const String &name, bool mute) {
  if (parsedPackCount >= MAX_PACKS) return 0;
  SchedulePack &pack = parsedPacks[parsedPackCount];
  pack.id = static_cast<uint8_t>(parsedPackCount + 1);
  pack.kind = kind;
  pack.enabled = true;
  pack.yearly = false;
  pack.mute = mute;
  pack.fromKey = 0;
  pack.toKey = 0;
  pack.dateCount = 0;
  pack.name = name;
  ++parsedPackCount;
  return pack.id;
}

// Finds the pack that a version 2 "group_id" exception refers to.
uint8_t legacyPackIdForGroup(uint8_t originalId, const uint8_t *groupIds,
                             const uint8_t *packIds, size_t count) {
  for (size_t i = 0; i < count; ++i) {
    if (groupIds[i] == originalId) return packIds[i];
  }
  return 0;
}

// Migrates version 1/2/3 documents into packs.
const char *parseLegacySchedule(JsonDocument &doc) {
  JsonVariant groupsValue = doc["groups"];
  JsonVariant overridesValue = doc["overrides"];
  JsonVariant exceptionsValue = doc["exceptions"];
  if (!overridesValue.isNull() && !exceptionsValue.isNull()) {
    return "invalid_schedule";
  }

  uint8_t legacyGroupIds[MAX_PACKS];
  uint8_t legacyPackIds[MAX_PACKS];
  size_t legacyGroupCount = 0;

  if (!groupsValue.isNull()) {
    if (!groupsValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray groupsArray = groupsValue.as<JsonArray>();
    if (groupsArray.size() > MAX_PACKS) return "too_many_packs";

    for (JsonVariant groupValue : groupsArray) {
      if (!groupValue.is<JsonObject>()) return "invalid_schedule";
      JsonObject groupObj = groupValue.as<JsonObject>();

      uint8_t originalId = static_cast<uint8_t>(legacyGroupCount + 1);
      JsonVariant idValue = groupObj["id"];
      if (!idValue.isNull()) {
        if (!idValue.is<int>()) return "invalid_schedule";
        int id = idValue.as<int>();
        if (id < 1 || id > 255) return "invalid_schedule";
        originalId = static_cast<uint8_t>(id);
      }

      uint8_t groupDays = ALL_DAYS_MASK;
      if (!parseDaysMask(groupObj["days"], groupDays)) return "invalid_schedule";
      if (groupDays == 0) return "invalid_schedule";

      String name;
      JsonVariant nameValue = groupObj["name"];
      if (!nameValue.isNull()) {
        if (!nameValue.is<const char *>()) return "invalid_schedule";
        name = truncateUtf8(String(nameValue.as<const char *>()), MAX_PACK_NAME_BYTES);
      }

      JsonVariant entriesValue = groupObj["entries"];
      if (entriesValue.isNull() || !entriesValue.is<JsonArray>()) {
        return "invalid_schedule";
      }
      JsonArray entriesArray = entriesValue.as<JsonArray>();
      if (entriesArray.size() > MAX_PACK_ENTRIES) return "too_many_pack_entries";

      uint8_t packId = beginParsedPack(PACK_BASE, name, false);
      if (packId == 0) return "too_many_packs";
      legacyGroupIds[legacyGroupCount] = originalId;
      legacyPackIds[legacyGroupCount] = packId;
      ++legacyGroupCount;

      for (JsonVariant item : entriesArray) {
        if (!item.is<JsonObject>()) return "invalid_schedule";
        if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
        if (!parseScheduleEntry(item.as<JsonObject>(), packId,
                                parsedEntries[parsedEntryCount])) {
          return "invalid_schedule";
        }
        parsedEntries[parsedEntryCount].daysMask &= groupDays;
        ++parsedEntryCount;
      }
    }
  } else {
    // Version 1: a flat list becomes one all-week base pack.
    JsonVariant entriesValue = doc["entries"];
    if (entriesValue.isNull() || !entriesValue.is<JsonArray>()) {
      return "invalid_schedule";
    }
    JsonArray entriesArray = entriesValue.as<JsonArray>();
    if (entriesArray.size() > MAX_PACK_ENTRIES) return "too_many_pack_entries";
    uint8_t packId = beginParsedPack(PACK_BASE, String("Расписание"), false);
    if (packId == 0) return "too_many_packs";
    for (JsonVariant item : entriesArray) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
      if (!parseScheduleEntry(item.as<JsonObject>(), packId,
                              parsedEntries[parsedEntryCount])) {
        return "invalid_schedule";
      }
      ++parsedEntryCount;
    }
  }

  // Version 3 "add" overrides become date-limited base packs.
  if (!overridesValue.isNull()) {
    if (!overridesValue.is<JsonArray>()) return "invalid_schedule";
    for (JsonVariant item : overridesValue.as<JsonArray>()) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      JsonObject obj = item.as<JsonObject>();
      JsonVariant dateValue = obj["date"];
      if (!dateValue.is<const char *>()) return "invalid_schedule";
      uint32_t dateKey = 0;
      if (!parseDateKey(String(dateValue.as<const char *>()), dateKey)) {
        return "invalid_schedule";
      }
      JsonVariant modeValue = obj["mode"];
      if (!modeValue.is<const char *>()) return "invalid_schedule";
      String mode = String(modeValue.as<const char *>());
      if (mode != "add") continue;
      if (parsedPackCount >= MAX_PACKS) return "too_many_packs";
      uint8_t packId = beginParsedPack(PACK_BASE, String(""), false);
      if (packId == 0) return "too_many_packs";
      parsedPacks[parsedPackCount - 1].dateCount = 1;
      parsedPacks[parsedPackCount - 1].dates[0] = dateKey;
      JsonVariant entriesValue = obj["entries"];
      if (!entriesValue.isNull()) {
        if (!entriesValue.is<JsonArray>()) return "invalid_schedule";
        JsonArray entriesArray = entriesValue.as<JsonArray>();
        if (entriesArray.size() > MAX_PACK_ENTRIES) return "too_many_pack_entries";
        for (JsonVariant entry : entriesArray) {
          if (!entry.is<JsonObject>()) return "invalid_schedule";
          if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
          if (!parseScheduleEntry(entry.as<JsonObject>(), packId,
                                  parsedEntries[parsedEntryCount])) {
            return "invalid_schedule";
          }
          parsedEntries[parsedEntryCount].daysMask = ALL_DAYS_MASK;
          ++parsedEntryCount;
        }
      }
    }
  }

  // Version 3 "mute"/"replace" overrides become corrections.
  if (!overridesValue.isNull()) {
    for (JsonVariant item : overridesValue.as<JsonArray>()) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      JsonObject obj = item.as<JsonObject>();
      JsonVariant dateValue = obj["date"];
      if (!dateValue.is<const char *>()) return "invalid_schedule";
      uint32_t dateKey = 0;
      if (!parseDateKey(String(dateValue.as<const char *>()), dateKey)) {
        return "invalid_schedule";
      }
      JsonVariant modeValue = obj["mode"];
      if (!modeValue.is<const char *>()) return "invalid_schedule";
      String mode = String(modeValue.as<const char *>());
      if (mode == "add") continue;
      bool mute = mode == "mute";
      if (!mute && mode != "replace") return "invalid_schedule";
      if (parsedPackCount >= MAX_PACKS) return "too_many_packs";
      uint8_t packId = beginParsedPack(PACK_CORRECTION, String(""), mute);
      if (packId == 0) return "too_many_packs";
      parsedPacks[parsedPackCount - 1].dateCount = 1;
      parsedPacks[parsedPackCount - 1].dates[0] = dateKey;
      if (mute) continue;
      size_t beforeEntries = parsedEntryCount;
      JsonVariant entriesValue = obj["entries"];
      if (!entriesValue.isNull()) {
        if (!entriesValue.is<JsonArray>()) return "invalid_schedule";
        JsonArray entriesArray = entriesValue.as<JsonArray>();
        if (entriesArray.size() > MAX_PACK_ENTRIES) return "too_many_pack_entries";
        for (JsonVariant entry : entriesArray) {
          if (!entry.is<JsonObject>()) return "invalid_schedule";
          if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
          if (!parseScheduleEntry(entry.as<JsonObject>(), packId,
                                  parsedEntries[parsedEntryCount])) {
            return "invalid_schedule";
          }
          parsedEntries[parsedEntryCount].daysMask = ALL_DAYS_MASK;
          ++parsedEntryCount;
        }
      }
      // A v3 replace with an empty list means "no bells", i.e. mute.
      if (parsedEntryCount == beforeEntries) {
        parsedPacks[parsedPackCount - 1].mute = true;
      }
    }
  }

  // Version 2 exceptions: mute or a copy of the referenced group.
  if (!exceptionsValue.isNull()) {
    if (!exceptionsValue.is<JsonArray>()) return "invalid_schedule";
    for (JsonVariant item : exceptionsValue.as<JsonArray>()) {
      if (!item.is<JsonObject>()) return "invalid_schedule";
      JsonObject obj = item.as<JsonObject>();
      JsonVariant dateValue = obj["date"];
      if (!dateValue.is<const char *>()) return "invalid_schedule";
      uint32_t dateKey = 0;
      if (!parseDateKey(String(dateValue.as<const char *>()), dateKey)) {
        return "invalid_schedule";
      }

      bool mute = false;
      JsonVariant muteValue = obj["mute"];
      if (!muteValue.isNull()) {
        if (!muteValue.is<bool>()) return "invalid_schedule";
        mute = muteValue.as<bool>();
      }

      uint8_t sourcePackId = 0;
      JsonVariant groupIdValue = obj["group_id"];
      if (!groupIdValue.isNull()) {
        if (!groupIdValue.is<int>()) return "invalid_schedule";
        int id = groupIdValue.as<int>();
        if (id < 1 || id > 255) return "invalid_schedule";
        sourcePackId = legacyPackIdForGroup(static_cast<uint8_t>(id), legacyGroupIds,
                                            legacyPackIds, legacyGroupCount);
        if (sourcePackId == 0) return "invalid_schedule";
      }
      if (!mute && sourcePackId == 0) return "invalid_schedule";

      if (parsedPackCount >= MAX_PACKS) return "too_many_packs";
      uint8_t packId = beginParsedPack(PACK_CORRECTION, String(""), mute);
      if (packId == 0) return "too_many_packs";
      parsedPacks[parsedPackCount - 1].dateCount = 1;
      parsedPacks[parsedPackCount - 1].dates[0] = dateKey;

      if (!mute) {
        size_t sourceEntryCount = parsedEntryCount;
        for (size_t i = 0; i < sourceEntryCount; ++i) {
          if (parsedEntries[i].packId != sourcePackId) continue;
          if (parsedEntryCount >= MAX_ENTRIES) return "too_many_entries";
          parsedEntries[parsedEntryCount] = parsedEntries[i];
          parsedEntries[parsedEntryCount].packId = packId;
          // A date exception ignores weekdays in v2.
          parsedEntries[parsedEntryCount].daysMask = ALL_DAYS_MASK;
          ++parsedEntryCount;
        }
        // Copying an empty group means "no bells", i.e. mute.
        if (parsedEntryCount == sourceEntryCount) {
          parsedPacks[parsedPackCount - 1].mute = true;
        }
      }
    }
  }

  return nullptr;
}

// Parses a schedule document into the scratch buffers.
// Returns nullptr on success, otherwise a stable error code string.
const char *parseScheduleDocument(JsonDocument &doc) {
  parsedEntryCount = 0;
  parsedPackCount = 0;
  parsedAutoStart = 0;
  parsedAutoStop = 0;

  JsonVariant packsValue = doc["packs"];
  if (!packsValue.isNull()) {
    if (!packsValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray packsArray = packsValue.as<JsonArray>();
    if (packsArray.size() > MAX_PACKS) return "too_many_packs";

    for (JsonVariant packValue : packsArray) {
      if (!packValue.is<JsonObject>()) return "invalid_schedule";
      JsonObject packObj = packValue.as<JsonObject>();

      uint8_t packId = static_cast<uint8_t>(parsedPackCount + 1);
      JsonVariant idValue = packObj["id"];
      if (!idValue.isNull()) {
        if (!idValue.is<int>()) return "invalid_schedule";
        int id = idValue.as<int>();
        if (id < 1 || id > 255) return "invalid_schedule";
        if (parsedPackExists(static_cast<uint8_t>(id))) return "invalid_schedule";
        packId = static_cast<uint8_t>(id);
      }

      uint8_t kind = PACK_BASE;
      JsonVariant kindValue = packObj["kind"];
      if (!kindValue.isNull()) {
        if (!parsePackKind(kindValue, kind)) return "invalid_schedule";
      }

      bool enabled = true;
      JsonVariant enabledValue = packObj["enabled"];
      if (!enabledValue.isNull()) {
        if (!enabledValue.is<bool>()) return "invalid_schedule";
        enabled = enabledValue.as<bool>();
      }

      bool yearly = false;
      JsonVariant yearlyValue = packObj["yearly"];
      if (!yearlyValue.isNull()) {
        if (!yearlyValue.is<bool>()) return "invalid_schedule";
        yearly = yearlyValue.as<bool>();
      }

      bool mute = false;
      JsonVariant muteValue = packObj["mute"];
      if (!muteValue.isNull()) {
        if (!muteValue.is<bool>()) return "invalid_schedule";
        mute = muteValue.as<bool>();
      }
      if (mute && kind != PACK_CORRECTION) return "invalid_schedule";

      String name;
      JsonVariant nameValue = packObj["name"];
      if (!nameValue.isNull()) {
        if (!nameValue.is<const char *>()) return "invalid_schedule";
        name = truncateUtf8(String(nameValue.as<const char *>()), MAX_PACK_NAME_BYTES);
      }

      uint32_t fromKey = 0;
      uint32_t toKey = 0;
      JsonVariant fromValue = packObj["from"];
      if (!fromValue.isNull()) {
        if (!fromValue.is<const char *>()) return "invalid_schedule";
        if (!parseDateKey(String(fromValue.as<const char *>()), fromKey)) {
          return "invalid_schedule";
        }
      }
      JsonVariant toValue = packObj["to"];
      if (!toValue.isNull()) {
        if (!toValue.is<const char *>()) return "invalid_schedule";
        if (!parseDateKey(String(toValue.as<const char *>()), toKey)) {
          return "invalid_schedule";
        }
      }
      if (!yearly && fromKey != 0 && toKey != 0 && toKey < fromKey) {
        return "invalid_schedule";
      }

      uint32_t dates[MAX_PACK_DATES];
      uint8_t dateCount = 0;
      JsonVariant datesValue = packObj["dates"];
      if (!datesValue.isNull()) {
        if (!datesValue.is<JsonArray>()) return "invalid_schedule";
        JsonArray datesArray = datesValue.as<JsonArray>();
        if (datesArray.size() > MAX_PACK_DATES) return "too_many_pack_dates";
        for (JsonVariant dateValue : datesArray) {
          if (!dateValue.is<const char *>()) return "invalid_schedule";
          uint32_t key = 0;
          if (!parseDateKey(String(dateValue.as<const char *>()), key)) {
            return "invalid_schedule";
          }
          bool duplicate = false;
          for (uint8_t i = 0; i < dateCount; ++i) {
            if (dates[i] == key) duplicate = true;
          }
          if (!duplicate && dateCount < MAX_PACK_DATES) dates[dateCount++] = key;
        }
      }

      JsonVariant entriesValue = packObj["entries"];
      if (!entriesValue.isNull() && !entriesValue.is<JsonArray>()) {
        return "invalid_schedule";
      }
      size_t entriesSize =
          entriesValue.isNull() ? 0 : entriesValue.as<JsonArray>().size();
      if (entriesSize > MAX_PACK_ENTRIES) return "too_many_pack_entries";
      if (parsedEntryCount + entriesSize > MAX_ENTRIES) {
        return "too_many_entries";
      }

      SchedulePack &pack = parsedPacks[parsedPackCount];
      pack.id = packId;
      pack.kind = kind;
      pack.enabled = enabled;
      pack.yearly = yearly;
      pack.mute = mute;
      pack.fromKey = fromKey;
      pack.toKey = toKey;
      pack.dateCount = dateCount;
      for (uint8_t i = 0; i < dateCount; ++i) pack.dates[i] = dates[i];
      pack.name = name;
      ++parsedPackCount;

      if (!entriesValue.isNull()) {
        for (JsonVariant item : entriesValue.as<JsonArray>()) {
          if (!item.is<JsonObject>()) return "invalid_schedule";
          if (!parseScheduleEntry(item.as<JsonObject>(), packId,
                                  parsedEntries[parsedEntryCount])) {
            return "invalid_schedule";
          }
          ++parsedEntryCount;
        }
      }
    }
  } else {
    const char *legacyError = parseLegacySchedule(doc);
    if (legacyError != nullptr) return legacyError;
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
  for (size_t i = 0; i < parsedPackCount; ++i) packs[i] = parsedPacks[i];
  packCount = parsedPackCount;
  autoStartDate = parsedAutoStart;
  autoStopDate = parsedAutoStop;
}

void loadScheduleFromFlash() {
  entryCount = 0;
  packCount = 0;
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

void appendEntry(JsonArray &arr, const ScheduleEntry &entry) {
  JsonObject obj = arr.add<JsonObject>();
  obj["time"] = formatTimeOfDay(entry.minuteOfDay);
  if (entry.pairMinuteOfDay != 0xFFFF) {
    obj["pair"] = formatTimeOfDay(entry.pairMinuteOfDay);
  }
  writeDays(obj, entry.daysMask);
  obj["label"] = entry.label;
}

void buildScheduleDocument(JsonDocument &doc, bool includeLimits) {
  doc["version"] = 4;
  if (includeLimits) {
    doc["max_entries"] = static_cast<unsigned>(MAX_ENTRIES);
    doc["max_packs"] = static_cast<unsigned>(MAX_PACKS);
    doc["max_pack_entries"] = static_cast<unsigned>(MAX_PACK_ENTRIES);
    doc["max_pack_dates"] = static_cast<unsigned>(MAX_PACK_DATES);
  }
  if (autoStartDate != 0 || autoStopDate != 0) {
    JsonObject autoObj = doc["auto"].to<JsonObject>();
    if (autoStartDate != 0) autoObj["start"] = formatDateKey(autoStartDate);
    if (autoStopDate != 0) autoObj["stop"] = formatDateKey(autoStopDate);
  }

  JsonArray packsArray = doc["packs"].to<JsonArray>();
  for (size_t p = 0; p < packCount; ++p) {
    const SchedulePack &pack = packs[p];
    JsonObject packObj = packsArray.add<JsonObject>();
    packObj["id"] = pack.id;
    packObj["name"] = pack.name;
    packObj["kind"] = packKindName(pack.kind);
    packObj["enabled"] = pack.enabled;
    if (pack.yearly) packObj["yearly"] = true;
    if (pack.mute) packObj["mute"] = true;
    if (pack.fromKey != 0) packObj["from"] = formatDateKey(pack.fromKey);
    if (pack.toKey != 0) packObj["to"] = formatDateKey(pack.toKey);
    if (pack.dateCount > 0) {
      JsonArray datesArray = packObj["dates"].to<JsonArray>();
      for (uint8_t d = 0; d < pack.dateCount; ++d) {
        datesArray.add(formatDateKey(pack.dates[d]));
      }
    }
    JsonArray entriesArray = packObj["entries"].to<JsonArray>();
    for (size_t i = 0; i < entryCount; ++i) {
      if (entries[i].packId == pack.id) appendEntry(entriesArray, entries[i]);
    }
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

// Bells resolved for one date. The buffers are static so the recursion-free
// evaluation never touches the stack.
struct DayBell {
  uint16_t minuteOfDay = 0;
  uint16_t entryIndex = 0;
};

DayBell dayBells[MAX_ENTRIES];
size_t dayBellCount = 0;
DayBell correctionBells[MAX_ENTRIES];
size_t correctionBellCount = 0;

bool autoAllowsDate(uint32_t dateKey) {
  if (autoStartDate != 0 && dateKey < autoStartDate) return false;
  if (autoStopDate != 0 && dateKey > autoStopDate) return false;
  return true;
}

void appendDayBell(DayBell *buffer, size_t &count, const ScheduleEntry &entry,
                   size_t index) {
  if (count >= MAX_ENTRIES) return;
  buffer[count].minuteOfDay = entry.minuteOfDay;
  buffer[count].entryIndex = static_cast<uint16_t>(index);
  ++count;
}

bool packHasEntryForDay(uint8_t packId, uint8_t weekdayMask) {
  for (size_t i = 0; i < entryCount; ++i) {
    if (entries[i].packId != packId) continue;
    if (entries[i].daysMask & weekdayMask) return true;
  }
  return false;
}

// Resolves a date exactly like BellSchedule.bellsFor() in the Android app.
// Base packs add bells; a correction with bells for this weekday first clears
// every base bell collected above it, then adds its own.
void collectDayBells(uint32_t dateKey, uint8_t weekdayMask) {
  dayBellCount = 0;
  correctionBellCount = 0;
  if (!autoAllowsDate(dateKey)) return;

  for (size_t p = 0; p < packCount; ++p) {
    const SchedulePack &pack = packs[p];
    if (!pack.enabled || !packAllowsDate(pack, dateKey)) continue;

    if (pack.kind == PACK_BASE) {
      for (size_t i = 0; i < entryCount; ++i) {
        const ScheduleEntry &entry = entries[i];
        if (entry.packId != pack.id) continue;
        if ((entry.daysMask & weekdayMask) == 0) continue;
        appendDayBell(dayBells, dayBellCount, entry, i);
      }
      continue;
    }

    bool applies = pack.mute || packHasEntryForDay(pack.id, weekdayMask);
    if (!applies) continue;
    dayBellCount = 0;
    if (pack.mute) continue;
    for (size_t i = 0; i < entryCount; ++i) {
      const ScheduleEntry &entry = entries[i];
      if (entry.packId != pack.id) continue;
      if ((entry.daysMask & weekdayMask) == 0) continue;
      appendDayBell(correctionBells, correctionBellCount, entry, i);
    }
  }

  for (size_t i = 0; i < correctionBellCount && dayBellCount < MAX_ENTRIES; ++i) {
    dayBells[dayBellCount++] = correctionBells[i];
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
  bool shouldRing = false;
  for (size_t i = 0; i < dayBellCount; ++i) {
    if (dayBells[i].minuteOfDay == minuteOfDay) {
      shouldRing = true;
      break;
    }
  }

  // Ring duration comes from the settings, not from individual entries.
  if (shouldRing) startRing(ringDefault);
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
  doc["packs"] = static_cast<unsigned>(packCount);
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

  // Merge bells that share a minute and sort by time into a small scratch
  // buffer. Duration comes from the settings, so the first bell wins.
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
    obj["duration"] = ringDefault;
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
  out["packs"] = static_cast<unsigned>(packCount);
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
