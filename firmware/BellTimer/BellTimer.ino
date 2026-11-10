// BellTimer - programmable bell timer for ESP32.
//
// Hardware (default wiring; the pins in use are kept in /device.json):
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
#include <Update.h>
#include <esp_netif.h>
#include <dhcpserver/dhcpserver.h>
#include <esp_system.h>
#include <esp_task_wdt.h>

// Deployment settings (Wi-Fi credentials, API token, GPIO wiring) come from
// .env. scripts/build-firmware.sh renders them into this header; it is
// generated, git-ignored and must exist before the sketch can be compiled.
#if __has_include("app_config.h")
#include "app_config.h"
#else
#error "app_config.h is missing: copy .env.example to .env and run scripts/build-firmware.sh."
#endif

// The release workflow sets the version through FW_VERSION in the environment;
// local builds fall back to this one.
#ifndef BELLTIMER_FW_VERSION
#define BELLTIMER_FW_VERSION "2.2.0"
#endif

namespace {

const char *const FW_VERSION = BELLTIMER_FW_VERSION;

// Oldest Android app that can drive this firmware. GET /status reports it and
// the release workflow copies it into firmware.json, so the app refuses to
// install a firmware it could not control afterwards. Raise it whenever a
// change needs a newer app.
const char *const MIN_APP_VERSION = "1.5.0";

// API level reported by GET /status. Level 5 replaces the raw entries of a
// pack with typed modules (bell, lesson, series). Level 6 adds firmware
// updates (POST /update), the wiring settings (/device), the local-only access
// point and several date ranges per pack. Level 7 adds the transmit power of
// the access point ("tx_power" in /network).
const uint8_t API_VERSION = 7;

// ----------------------------- Configuration ------------------------------

// Build-time defaults. They are used only on a device that has never stored
// its own values: on the first boot the firmware copies them into
// /network.json and /device.json and reads those files from then on, so a
// firmware built with other defaults (for example a release build) never
// changes the settings of a device that already runs.
const char *const DEFAULT_AP_SSID = BELLTIMER_AP_SSID;
const char *const DEFAULT_AP_PASSWORD = BELLTIMER_AP_PASSWORD;
const char *const DEFAULT_API_TOKEN = BELLTIMER_API_TOKEN;  // Empty disables X-Token.
const uint8_t DEFAULT_AP_CHANNEL = BELLTIMER_AP_CHANNEL;
const uint8_t DEFAULT_AP_MAX_CLIENTS = BELLTIMER_AP_MAX_CLIENTS;

// The KY-029 exists in a common-cathode and a common-anode variant. With the
// common pin tied to GND the dies are lit by a HIGH level; with the common pin
// tied to 3V3 they are lit by a LOW level. See LED_ACTIVE_LOW in .env.
struct DeviceConfig {
  uint8_t relay = BELLTIMER_PIN_RELAY;
  uint8_t ledGreen = BELLTIMER_PIN_LED_GREEN;  // KY-029 "G" pin
  uint8_t ledRed = BELLTIMER_PIN_LED_RED;      // KY-029 "R" pin
  uint8_t sda = BELLTIMER_PIN_I2C_SDA;
  uint8_t scl = BELLTIMER_PIN_I2C_SCL;
  bool ledActiveLow = BELLTIMER_LED_ACTIVE_LOW;
};

// A schedule is an ordered list of packs ("пачки"). A pack holds modules; a
// module expands into one or more bells (a lesson rings at its start and its
// end, a series rings every N minutes). A base pack contributes its bells; a
// correction cancels the bells of every base pack above it. The limits below
// are mirrored by the Android app (data/Schedule.kt).
//
// They are sized for the worst case: every module a lesson or series with all
// days and a 32-byte Cyrillic label, every pack with 24 dates. Such a full
// schedule is a ~36 KB request and, while POST /schedule runs, is estimated to
// need about 120 KB of heap (body, parsed document, old and new labels) out of
// roughly 180 KB free with the access point up; the rest stays as headroom.
// Do not lower MAX_PACK_MODULES: a stored schedule above the new limit would
// no longer load after a firmware update.
const size_t MAX_PACKS = 8;            // ordered packs
const size_t MAX_PACK_MODULES = 32;    // modules inside one pack
const size_t MAX_PACK_DATES = 24;      // individual dates inside one pack
const size_t MAX_PACK_RANGES = 8;      // from/to date ranges inside one pack
const size_t MAX_MODULES = MAX_PACKS * MAX_PACK_MODULES;
const size_t MAX_LABEL_BYTES = 32;     // About 10 Cyrillic characters in UTF-8.
const size_t MAX_PACK_NAME_BYTES = 32;
// Bodies larger than this are rejected before parsing, so an oversized
// document cannot exhaust the heap.
const size_t MAX_SCHEDULE_BODY_BYTES = 40960;
const uint16_t MINUTES_PER_DAY = 1440;
const uint16_t MAX_LESSON_MINUTES = 720;
const uint8_t MAX_PRE_MINUTES = 60;
const uint16_t MAX_SERIES_STEP = 720;
// GET /status looks this many days ahead (today included) for the next bell.
const uint8_t NEXT_BELL_HORIZON_DAYS = 8;
// A bell may still fire this many seconds into its minute, so a loop that was
// busy at second 0 (for example while writing flash) does not lose it.
const uint8_t RING_GRACE_SEC = 5;
const uint8_t MIN_RING_SEC = 1;
const uint8_t MAX_RING_SEC = 60;
const uint8_t DEFAULT_RING_SEC = 5;
const uint8_t ALL_DAYS_MASK = 0x7F;    // bit 0 = Monday ... bit 6 = Sunday

const char *const SCHEDULE_FILE = "/schedule.json";
const char *const SETTINGS_FILE = "/settings.json";
const char *const NETWORK_FILE = "/network.json";
const char *const DEVICE_FILE = "/device.json";
// Layout version written into every settings file as "v". A newer firmware
// must keep reading files written by an older one.
const uint8_t FILE_FORMAT = 1;
const size_t MAX_SSID_BYTES = 32;
const size_t MIN_PASSWORD_BYTES = 8;
const size_t MAX_PASSWORD_BYTES = 63;
const size_t MAX_TOKEN_BYTES = 64;
// Delay between answering POST /network and restarting the access point, so
// the response reaches the phone before its Wi-Fi drops.
const uint32_t AP_RESTART_DELAY_MS = 1500;
// Same idea for a full restart after a firmware update or a wiring change.
const uint32_t REBOOT_DELAY_MS = 1500;
// A switch of the local-only mode is kept only when a client talks to the
// device within this time after the access point came back. Otherwise the
// previous mode returns, so a phone that cannot join the changed network does
// not lock everyone out.
const uint32_t LOCAL_ONLY_TRIAL_MS = 180000;
// The task watchdog restarts the device when loop() stops running, for example
// when interference from the bell freezes the chip. A single request can keep
// the web server busy for several seconds (each read waits up to 5 s for a
// slow client), so the limit is well above that. Firmware uploads feed the
// watchdog for every chunk.
const uint32_t LOOP_WDT_TIMEOUT_MS = 15000;

const uint32_t RTC_POLL_MS = 250;
const uint32_t RTC_RETRY_MS = 2000;
const uint32_t AP_RETRY_MS = 5000;
const uint32_t STORAGE_RETRY_MS = 5000;
const uint32_t AP_IP_CHECK_MS = 5000;

// ------------------------------- Types ------------------------------------

enum ModuleType : uint8_t {
  MODULE_BELL = 0,    // one bell at "time"
  MODULE_LESSON = 1,  // bells at "time" and "time" + "length", optional warning
  MODULE_SERIES = 2,  // a bell every "every" minutes from "time" to "until"
};

// What one resolved bell means inside its module.
enum BellRole : uint8_t {
  ROLE_BELL = 0,
  ROLE_START = 1,
  ROLE_END = 2,
  ROLE_PRE = 3,
  ROLE_STEP = 4,
};

struct ScheduleModule {
  uint8_t type = MODULE_BELL;
  uint8_t packId = 0;              // owning pack
  uint8_t daysMask = ALL_DAYS_MASK;
  uint8_t pre = 0;                 // lesson: warning N minutes before, 0 = none
  uint16_t start = 0;              // minute of day, 0..1439
  uint16_t length = 0;             // lesson: minutes
  uint16_t until = 0;              // series: last allowed minute of day
  uint16_t every = 0;              // series: step in minutes
  String label;
};

enum PackKind : uint8_t {
  PACK_BASE = 0,
  PACK_CORRECTION = 1,
};

// One validity range of a pack. A zero key means "no limit" on that side; at
// least one side is set.
struct DateRange {
  uint32_t fromKey = 0;  // YYYYMMDD
  uint32_t toKey = 0;
};

struct SchedulePack {
  uint8_t id = 0;
  uint8_t kind = PACK_BASE;
  bool enabled = true;
  bool yearly = false;
  bool mute = false;  // correction that only cancels the packs above (a holiday)
  DateRange ranges[MAX_PACK_RANGES];
  uint8_t rangeCount = 0;
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

// Wiring in use. It is read once at boot; POST /device stores a new one and
// restarts the device to apply it.
DeviceConfig device;
bool deviceConfigError = false;  // /device.json exists but cannot be used

String apSsid = DEFAULT_AP_SSID;
String apPassword = DEFAULT_AP_PASSWORD;
String apiToken = DEFAULT_API_TOKEN;
uint8_t apChannel = DEFAULT_AP_CHANNEL;
uint8_t apMaxClients = DEFAULT_AP_MAX_CLIENTS;
// Local-only: the DHCP server announces no gateway, so phones keep using
// mobile data for the internet while they are joined to the bell. Off by
// default; the app turns it on with a trial (see LOCAL_ONLY_TRIAL_MS).
bool apLocalOnly = false;
// Transmit power of the access point in quarters of a dBm (a wifi_power_t
// value). A lower power draws less current at every packet, which helps a
// board whose supply sags. 20 dBm is the chip default, so files written
// before this setting keep the old behaviour.
const int8_t DEFAULT_TX_POWER_QDBM = WIFI_POWER_20dBm;
int8_t apTxPower = DEFAULT_TX_POWER_QDBM;
bool networkConfigError = false;  // /network.json exists but cannot be used
bool apRestartPending = false;
uint32_t apRestartAtMs = 0;

// A local-only switch on trial (see LOCAL_ONLY_TRIAL_MS).
bool localOnlyTrial = false;
bool localOnlyPrevious = false;
uint32_t localOnlyTrialStartMs = 0;

bool rebootPending = false;
uint32_t rebootAtMs = 0;

// Firmware upload state (POST /update).
bool updateRunning = false;
bool updateAuthorized = false;
const char *updateError = nullptr;

uint8_t ringDefault = DEFAULT_RING_SEC;
String currentMode = "auto";
String lastSync;  // local time of the last POST /time, empty = never

ScheduleModule modules[MAX_MODULES];
size_t moduleCount = 0;

SchedulePack packs[MAX_PACKS];
size_t packCount = 0;

// Auto mode date range. A zero key means "no limit".
uint32_t autoStartDate = 0;
uint32_t autoStopDate = 0;

// Bumped whenever the live schedule changes; invalidates cached results.
uint32_t scheduleRevision = 0;

// Scratch buffers filled by parseScheduleDocument() and committed only after
// the document has been validated.
ScheduleModule parsedModules[MAX_MODULES];
size_t parsedModuleCount = 0;
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
int ringLeftSec = 0;

uint32_t suppressMinuteKey = 0;
bool suppressMinuteSet = false;
uint32_t lastScheduleMinute = 0xFFFFFFFFUL;

// LED blink timings. The phase is derived from millis(), so no state is kept.
const uint32_t LED_OK_BLINK_MS = 500;    // green, 1 Hz
const uint32_t LED_RING_BLINK_MS = 100;  // green, 5 Hz while ringing
const uint32_t LED_FAULT_BLINK_MS = 250; // red, 2 Hz

// --------------------------- Small helpers --------------------------------

void relayOn() { digitalWrite(device.relay, LOW); }
void relayOff() { digitalWrite(device.relay, HIGH); }

// Single point that translates a logical LED state into a pin level, so the
// common-cathode and common-anode KY-029 variants differ only by a setting.
inline void ledWrite(uint8_t pin, bool on) {
  digitalWrite(pin, on == device.ledActiveLow ? LOW : HIGH);
}

// Pin level that keeps a die dark, used before pinMode() so the LED never
// flashes during boot.
inline uint8_t ledOffLevel() { return device.ledActiveLow ? HIGH : LOW; }

void setLed(bool green, bool red) {
  ledWrite(device.ledGreen, green);
  ledWrite(device.ledRed, red);
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

bool parseYear(const String &s, int &year) {
  year = 0;
  for (size_t i = 0; i < 4; ++i) {
    char c = s[i];
    if (c < '0' || c > '9') return false;
    year = year * 10 + (c - '0');
  }
  return true;
}

bool parseDateTime(const String &s, DateTime &out) {
  if (s.length() != 19) return false;
  if (s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':') {
    return false;
  }
  int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0;
  if (!parseYear(s, year)) return false;
  if (!parseTwoDigits(s, 5, month) || !parseTwoDigits(s, 8, day) ||
      !parseTwoDigits(s, 11, hour) || !parseTwoDigits(s, 14, minute) ||
      !parseTwoDigits(s, 17, second)) {
    return false;
  }
  if (!validDateParts(year, month, day) || hour > 23 || minute > 59 || second > 59) {
    return false;
  }
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

// Parses "YYYY-MM-DD" into a comparable YYYYMMDD key.
bool parseDateKey(const String &s, uint32_t &key) {
  if (s.length() != 10 || s[4] != '-' || s[7] != '-') return false;
  int year = 0, month = 0, day = 0;
  if (!parseYear(s, year)) return false;
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

uint8_t weekdayMaskOf(uint32_t dateKey) {
  return static_cast<uint8_t>(1U << (isoWeekdayFromDateKey(dateKey) - 1));
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

const char *moduleTypeName(uint8_t type) {
  switch (type) {
    case MODULE_LESSON: return "lesson";
    case MODULE_SERIES: return "series";
    default: return "bell";
  }
}

const char *bellRoleName(uint8_t role) {
  switch (role) {
    case ROLE_START: return "start";
    case ROLE_END: return "end";
    case ROLE_PRE: return "pre";
    case ROLE_STEP: return "step";
    default: return "bell";
  }
}

bool sameMonthDay(uint32_t a, uint32_t b) {
  return ((a / 100UL) % 100UL) == ((b / 100UL) % 100UL) && (a % 100UL) == (b % 100UL);
}

uint32_t monthDayKey(uint32_t dateKey) {
  return ((dateKey / 100UL) % 100UL) * 100UL + (dateKey % 100UL);
}

bool rangeAllowsDate(const DateRange &range, bool yearly, uint32_t dateKey) {
  if (range.fromKey == 0 && range.toKey == 0) return false;
  if (yearly) {
    uint32_t value = monthDayKey(dateKey);
    uint32_t start = range.fromKey != 0 ? monthDayKey(range.fromKey) : 0;
    uint32_t end = range.toKey != 0 ? monthDayKey(range.toKey) : 0;
    if (start != 0 && end != 0) {
      if (start > end) return value >= start || value <= end;
      return value >= start && value <= end;
    }
    if (start != 0) return value >= start;
    return value <= end;
  }
  if (range.fromKey != 0 && dateKey < range.fromKey) return false;
  if (range.toKey != 0 && dateKey > range.toKey) return false;
  return true;
}

// A pack without ranges and dates applies every day; otherwise a date must
// fall into one of its ranges or be one of its single dates.
bool packAllowsDate(const SchedulePack &pack, uint32_t dateKey) {
  if (pack.rangeCount == 0 && pack.dateCount == 0) return true;
  for (uint8_t i = 0; i < pack.rangeCount; ++i) {
    if (rangeAllowsDate(pack.ranges[i], pack.yearly, dateKey)) return true;
  }
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

// Replaces "path" with the bytes produced by write(), via a temporary file.
// write() receives the open file and returns true when everything was written.
template <typename Writer>
bool writeFileAtomicWith(const char *path, Writer write) {
  String tmpPath = String(path) + ".tmp";
  File out = LittleFS.open(tmpPath, "w");
  if (!out) return false;
  bool written = write(out);
  out.close();
  if (!written) {
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

bool writeFileAtomic(const char *path, const String &data) {
  return writeFileAtomicWith(path, [&data](File &out) {
    return out.print(data) == data.length();
  });
}

// Serializes straight into the file, so no String copy of the document is
// held in RAM next to it.
bool writeJsonFileAtomic(const char *path, const JsonDocument &doc) {
  return writeFileAtomicWith(path, [&doc](File &out) {
    return serializeJson(doc, out) == measureJson(doc);
  });
}

String sanitizeMode(const String &value) {
  if (value == "auto" || value == "manual") return value;
  return "auto";
}

// Keeps a file the firmware cannot read as "<path>.bad" instead of losing it,
// for example a schedule written by a newer firmware after a downgrade.
void setAsideBadFile(const char *path) {
  String badPath = String(path) + ".bad";
  if (LittleFS.exists(badPath)) LittleFS.remove(badPath);
  LittleFS.rename(path, badPath);
}

// Only damaged content counts as bad; running out of memory while parsing
// says nothing about the file.
bool fileContentIsBad(DeserializationError err) {
  return err == DeserializationError::InvalidInput ||
         err == DeserializationError::IncompleteInput ||
         err == DeserializationError::TooDeep ||
         err == DeserializationError::EmptyInput;
}

bool saveSettingsToFlash() {
  JsonDocument doc;
  doc["v"] = FILE_FORMAT;
  doc["ring_default"] = ringDefault;
  doc["mode"] = currentMode;
  if (lastSync.length() > 0) doc["synced"] = lastSync;
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
  lastSync = "";
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
  const char *synced = doc["synced"] | "";
  DateTime parsed;
  if (parseDateTime(String(synced), parsed)) lastSync = String(synced);
}

// --------------------------- Schedule document ----------------------------
//
// Canonical document (version 6):
// {
//   "version": 6,
//   "auto": {"start": "YYYY-MM-DD", "stop": "YYYY-MM-DD"},
//   "packs": [
//     {"id": 1, "name": "Будни", "kind": "base", "enabled": true,
//      "yearly": false,
//      "ranges": [{"from": "YYYY-MM-DD", "to": "YYYY-MM-DD"}, ...],
//      "dates": ["YYYY-MM-DD"],
//      "modules": [
//        {"type": "lesson", "time": "08:00", "length": 45, "pre": 2,
//         "days": [1, 2, 3, 4, 5], "label": "1 урок"},
//        {"type": "bell", "time": "12:00", "days": [...], "label": "Обед"},
//        {"type": "series", "time": "09:00", "until": "17:00", "every": 60}
//      ]},
//     {"id": 2, "name": "Праздник", "kind": "correction", "mute": true,
//      "dates": ["YYYY-MM-DD"], "modules": []}
//   ]
// }
// A pack with exactly one range is written in the version 5 shape, with "from"
// and "to" on the pack itself, and version 5 documents are read the same way.
// Version 4 packs ("entries" with an optional "pair") are migrated in memory:
// an entry with a pair becomes a lesson, any other entry a bell.

bool parseDaysMask(JsonVariant value, uint8_t &mask) {
  // Missing or empty means "every day", matching the app's decoder.
  if (value.isNull()) {
    mask = ALL_DAYS_MASK;
    return true;
  }
  if (!value.is<JsonArray>()) return false;
  JsonArray days = value.as<JsonArray>();
  if (days.size() == 0) {
    mask = ALL_DAYS_MASK;
    return true;
  }
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

bool parseTimeField(JsonVariant value, uint16_t &minuteOfDay) {
  if (!value.is<const char *>()) return false;
  return parseTimeOfDay(String(value.as<const char *>()), minuteOfDay);
}

bool parseLabelField(JsonVariant value, String &label, size_t maxBytes) {
  label = "";
  if (value.isNull()) return true;
  if (!value.is<const char *>()) return false;
  label = truncateUtf8(String(value.as<const char *>()), maxBytes);
  return true;
}

// Reads an optional integer field within [minValue, maxValue].
bool parseIntField(JsonVariant value, int minValue, int maxValue, int fallback, int &out) {
  if (value.isNull()) {
    out = fallback;
    return true;
  }
  if (!value.is<int>()) return false;
  int parsed = value.as<int>();
  if (parsed < minValue || parsed > maxValue) return false;
  out = parsed;
  return true;
}

bool parseModuleType(JsonVariant value, uint8_t &type) {
  if (value.isNull()) {
    type = MODULE_BELL;
    return true;
  }
  if (!value.is<const char *>()) return false;
  String raw = String(value.as<const char *>());
  if (raw == "bell") {
    type = MODULE_BELL;
  } else if (raw == "lesson") {
    type = MODULE_LESSON;
  } else if (raw == "series") {
    type = MODULE_SERIES;
  } else {
    return false;
  }
  return true;
}

bool parseModule(JsonObject obj, uint8_t packId, ScheduleModule &out) {
  uint8_t type = MODULE_BELL;
  if (!parseModuleType(obj["type"], type)) return false;
  uint16_t start = 0;
  if (!parseTimeField(obj["time"], start)) return false;
  uint8_t daysMask = ALL_DAYS_MASK;
  if (!parseDaysMask(obj["days"], daysMask)) return false;
  String label;
  if (!parseLabelField(obj["label"], label, MAX_LABEL_BYTES)) return false;

  out.type = type;
  out.packId = packId;
  out.daysMask = daysMask;
  out.start = start;
  out.pre = 0;
  out.length = 0;
  out.until = 0;
  out.every = 0;
  out.label = label;

  if (type == MODULE_LESSON) {
    int length = 0;
    int pre = 0;
    if (obj["length"].isNull()) return false;
    if (!parseIntField(obj["length"], 1, MAX_LESSON_MINUTES, 0, length)) return false;
    if (!parseIntField(obj["pre"], 0, MAX_PRE_MINUTES, 0, pre)) return false;
    if (start + length >= MINUTES_PER_DAY) return false;
    if (pre > start) return false;
    out.length = static_cast<uint16_t>(length);
    out.pre = static_cast<uint8_t>(pre);
  } else if (type == MODULE_SERIES) {
    uint16_t until = 0;
    int every = 0;
    if (!parseTimeField(obj["until"], until)) return false;
    if (until <= start) return false;
    if (obj["every"].isNull()) return false;
    if (!parseIntField(obj["every"], 1, MAX_SERIES_STEP, 0, every)) return false;
    out.until = until;
    out.every = static_cast<uint16_t>(every);
  }
  return true;
}

// Version 4 entry: "time", optional "pair", "days", "label".
bool parseLegacyEntry(JsonObject obj, uint8_t packId, ScheduleModule &out) {
  uint16_t start = 0;
  if (!parseTimeField(obj["time"], start)) return false;
  uint8_t daysMask = ALL_DAYS_MASK;
  if (!parseDaysMask(obj["days"], daysMask)) return false;
  String label;
  if (!parseLabelField(obj["label"], label, MAX_LABEL_BYTES)) return false;

  out.type = MODULE_BELL;
  out.packId = packId;
  out.daysMask = daysMask;
  out.start = start;
  out.pre = 0;
  out.length = 0;
  out.until = 0;
  out.every = 0;
  out.label = label;

  JsonVariant pairValue = obj["pair"];
  if (!pairValue.isNull()) {
    uint16_t pair = 0;
    if (!parseTimeField(pairValue, pair)) return false;
    if (pair != start) {
      uint16_t first = start < pair ? start : pair;
      uint16_t second = start < pair ? pair : start;
      if (second - first <= MAX_LESSON_MINUTES) {
        out.type = MODULE_LESSON;
        out.start = first;
        out.length = second - first;
      }
    }
  }
  return true;
}

bool parsedPackExists(uint8_t id) {
  for (size_t i = 0; i < parsedPackCount; ++i) {
    if (parsedPacks[i].id == id) return true;
  }
  return false;
}

bool parsePackKind(JsonVariant value, uint8_t &kind) {
  if (value.isNull()) {
    kind = PACK_BASE;
    return true;
  }
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

bool parseBoolField(JsonVariant value, bool fallback, bool &out) {
  if (value.isNull()) {
    out = fallback;
    return true;
  }
  if (!value.is<bool>()) return false;
  out = value.as<bool>();
  return true;
}

bool parseDateField(JsonVariant value, uint32_t &key) {
  key = 0;
  if (value.isNull()) return true;
  if (!value.is<const char *>()) return false;
  return parseDateKey(String(value.as<const char *>()), key);
}

// Parses one pack object and its modules into the scratch buffers.
const char *parsePack(JsonObject packObj) {
  uint8_t packId = static_cast<uint8_t>(parsedPackCount + 1);
  JsonVariant idValue = packObj["id"];
  if (!idValue.isNull()) {
    if (!idValue.is<int>()) return "invalid_schedule";
    int id = idValue.as<int>();
    if (id < 1 || id > 255) return "invalid_schedule";
    packId = static_cast<uint8_t>(id);
  }
  if (parsedPackExists(packId)) return "invalid_schedule";

  SchedulePack pack;
  pack.id = packId;
  if (!parsePackKind(packObj["kind"], pack.kind)) return "invalid_schedule";
  if (!parseBoolField(packObj["enabled"], true, pack.enabled)) return "invalid_schedule";
  if (!parseBoolField(packObj["yearly"], false, pack.yearly)) return "invalid_schedule";
  if (!parseBoolField(packObj["mute"], false, pack.mute)) return "invalid_schedule";
  if (pack.mute && pack.kind != PACK_CORRECTION) return "invalid_schedule";
  if (!parseLabelField(packObj["name"], pack.name, MAX_PACK_NAME_BYTES)) {
    return "invalid_schedule";
  }
  // Version 6 lists the validity ranges in "ranges"; a single "from"/"to"
  // pair (version 5) is read as one range.
  JsonVariant rangesValue = packObj["ranges"];
  if (!rangesValue.isNull()) {
    if (!rangesValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray rangesArray = rangesValue.as<JsonArray>();
    if (rangesArray.size() > MAX_PACK_RANGES) return "too_many_pack_ranges";
    for (JsonVariant rangeValue : rangesArray) {
      if (!rangeValue.is<JsonObject>()) return "invalid_schedule";
      JsonObject rangeObj = rangeValue.as<JsonObject>();
      DateRange range;
      if (!parseDateField(rangeObj["from"], range.fromKey)) return "invalid_schedule";
      if (!parseDateField(rangeObj["to"], range.toKey)) return "invalid_schedule";
      if (range.fromKey == 0 && range.toKey == 0) return "invalid_schedule";
      pack.ranges[pack.rangeCount++] = range;
    }
  } else {
    DateRange range;
    if (!parseDateField(packObj["from"], range.fromKey)) return "invalid_schedule";
    if (!parseDateField(packObj["to"], range.toKey)) return "invalid_schedule";
    if (range.fromKey != 0 || range.toKey != 0) pack.ranges[pack.rangeCount++] = range;
  }
  for (uint8_t i = 0; i < pack.rangeCount; ++i) {
    const DateRange &range = pack.ranges[i];
    if (!pack.yearly && range.fromKey != 0 && range.toKey != 0 && range.toKey < range.fromKey) {
      return "invalid_schedule";
    }
  }

  JsonVariant datesValue = packObj["dates"];
  if (!datesValue.isNull()) {
    if (!datesValue.is<JsonArray>()) return "invalid_schedule";
    JsonArray datesArray = datesValue.as<JsonArray>();
    if (datesArray.size() > MAX_PACK_DATES) return "too_many_pack_dates";
    for (JsonVariant dateValue : datesArray) {
      uint32_t key = 0;
      if (!dateValue.is<const char *>() || !parseDateField(dateValue, key)) {
        return "invalid_schedule";
      }
      bool duplicate = false;
      for (uint8_t i = 0; i < pack.dateCount; ++i) {
        if (pack.dates[i] == key) duplicate = true;
      }
      if (!duplicate) pack.dates[pack.dateCount++] = key;
    }
  }

  // Version 5 uses "modules"; version 4 used "entries".
  JsonVariant modulesValue = packObj["modules"];
  bool legacy = false;
  if (modulesValue.isNull() && !packObj["entries"].isNull()) {
    modulesValue = packObj["entries"];
    legacy = true;
  }
  if (!modulesValue.isNull() && !modulesValue.is<JsonArray>()) return "invalid_schedule";
  size_t size = modulesValue.isNull() ? 0 : modulesValue.as<JsonArray>().size();
  if (size > MAX_PACK_MODULES) return "too_many_pack_modules";
  if (parsedModuleCount + size > MAX_MODULES) return "too_many_modules";

  parsedPacks[parsedPackCount++] = pack;

  if (modulesValue.isNull()) return nullptr;
  for (JsonVariant item : modulesValue.as<JsonArray>()) {
    if (!item.is<JsonObject>()) return "invalid_schedule";
    ScheduleModule &target = parsedModules[parsedModuleCount];
    bool ok = legacy ? parseLegacyEntry(item.as<JsonObject>(), packId, target)
                     : parseModule(item.as<JsonObject>(), packId, target);
    if (!ok) return "invalid_schedule";
    ++parsedModuleCount;
  }
  return nullptr;
}

// Parses a schedule document into the scratch buffers.
// Returns nullptr on success, otherwise a stable error code string.
const char *parseScheduleDocument(JsonDocument &doc) {
  parsedModuleCount = 0;
  parsedPackCount = 0;
  parsedAutoStart = 0;
  parsedAutoStop = 0;

  JsonVariant packsValue = doc["packs"];
  if (!packsValue.is<JsonArray>()) return "invalid_schedule";
  JsonArray packsArray = packsValue.as<JsonArray>();
  if (packsArray.size() > MAX_PACKS) return "too_many_packs";
  for (JsonVariant packValue : packsArray) {
    if (!packValue.is<JsonObject>()) return "invalid_schedule";
    const char *error = parsePack(packValue.as<JsonObject>());
    if (error != nullptr) return error;
  }

  JsonVariant autoValue = doc["auto"];
  if (!autoValue.isNull()) {
    if (!autoValue.is<JsonObject>()) return "invalid_schedule";
    JsonObject autoObj = autoValue.as<JsonObject>();
    if (!parseDateField(autoObj["start"], parsedAutoStart)) return "invalid_schedule";
    if (!parseDateField(autoObj["stop"], parsedAutoStop)) return "invalid_schedule";
    if (parsedAutoStart != 0 && parsedAutoStop != 0 &&
        parsedAutoStop < parsedAutoStart) {
      return "invalid_schedule";
    }
  }

  return nullptr;
}

void applyParsedSchedule() {
  // Moving the labels leaves the scratch buffers empty, so their heap copies
  // are not kept alive next to the live ones.
  for (size_t i = 0; i < parsedModuleCount; ++i) modules[i] = std::move(parsedModules[i]);
  moduleCount = parsedModuleCount;
  for (size_t i = 0; i < parsedPackCount; ++i) packs[i] = std::move(parsedPacks[i]);
  packCount = parsedPackCount;
  autoStartDate = parsedAutoStart;
  autoStopDate = parsedAutoStop;
  ++scheduleRevision;
}

void loadScheduleFromFlash() {
  moduleCount = 0;
  packCount = 0;
  autoStartDate = 0;
  autoStopDate = 0;
  ++scheduleRevision;
  if (!storageOk) return;
  File in = LittleFS.open(SCHEDULE_FILE, "r");
  if (!in) return;
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, in);
  in.close();
  if (err) {
    if (fileContentIsBad(err)) setAsideBadFile(SCHEDULE_FILE);
    return;
  }
  if (parseScheduleDocument(doc) != nullptr) {
    setAsideBadFile(SCHEDULE_FILE);
    return;
  }
  applyParsedSchedule();
}

void writeDays(JsonObject &obj, uint8_t mask) {
  JsonArray days = obj["days"].to<JsonArray>();
  for (uint8_t bit = 0; bit < 7; ++bit) {
    if (mask & (1U << bit)) days.add(bit + 1);
  }
}

void appendModule(JsonArray &arr, const ScheduleModule &module) {
  JsonObject obj = arr.add<JsonObject>();
  obj["type"] = moduleTypeName(module.type);
  obj["time"] = formatTimeOfDay(module.start);
  if (module.type == MODULE_LESSON) {
    obj["length"] = module.length;
    if (module.pre > 0) obj["pre"] = module.pre;
  } else if (module.type == MODULE_SERIES) {
    obj["until"] = formatTimeOfDay(module.until);
    obj["every"] = module.every;
  }
  writeDays(obj, module.daysMask);
  obj["label"] = module.label;
}

void buildScheduleDocument(JsonDocument &doc, bool includeLimits) {
  doc["version"] = 6;
  if (includeLimits) {
    doc["max_modules"] = static_cast<unsigned>(MAX_MODULES);
    doc["max_packs"] = static_cast<unsigned>(MAX_PACKS);
    doc["max_pack_modules"] = static_cast<unsigned>(MAX_PACK_MODULES);
    doc["max_pack_dates"] = static_cast<unsigned>(MAX_PACK_DATES);
    doc["max_pack_ranges"] = static_cast<unsigned>(MAX_PACK_RANGES);
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
    // One range keeps the version 5 shape, so an older app still reads it.
    if (pack.rangeCount == 1) {
      const DateRange &range = pack.ranges[0];
      if (range.fromKey != 0) packObj["from"] = formatDateKey(range.fromKey);
      if (range.toKey != 0) packObj["to"] = formatDateKey(range.toKey);
    } else if (pack.rangeCount > 1) {
      JsonArray rangesArray = packObj["ranges"].to<JsonArray>();
      for (uint8_t r = 0; r < pack.rangeCount; ++r) {
        JsonObject rangeObj = rangesArray.add<JsonObject>();
        if (pack.ranges[r].fromKey != 0) rangeObj["from"] = formatDateKey(pack.ranges[r].fromKey);
        if (pack.ranges[r].toKey != 0) rangeObj["to"] = formatDateKey(pack.ranges[r].toKey);
      }
    }
    if (pack.dateCount > 0) {
      JsonArray datesArray = packObj["dates"].to<JsonArray>();
      for (uint8_t d = 0; d < pack.dateCount; ++d) {
        datesArray.add(formatDateKey(pack.dates[d]));
      }
    }
    JsonArray modulesArray = packObj["modules"].to<JsonArray>();
    for (size_t i = 0; i < moduleCount; ++i) {
      if (modules[i].packId == pack.id) appendModule(modulesArray, modules[i]);
    }
  }
}

bool saveScheduleToFlash() {
  JsonDocument doc;
  buildScheduleDocument(doc, false);
  bool ok = storageOk && writeJsonFileAtomic(SCHEDULE_FILE, doc);
  if (!ok) {
    storageOk = false;
    storageRetryPending = true;
  }
  return ok;
}

// ------------------------------ Network ----------------------------------

bool validSsid(const String &value) {
  return value.length() >= 1 && value.length() <= MAX_SSID_BYTES;
}

// Empty means an open network; WPA2 needs 8..63 characters.
bool validPassword(const String &value) {
  return value.length() == 0 ||
         (value.length() >= MIN_PASSWORD_BYTES && value.length() <= MAX_PASSWORD_BYTES);
}

// Printable ASCII without spaces, so it survives an HTTP header unchanged.
bool validToken(const String &value) {
  if (value.length() > MAX_TOKEN_BYTES) return false;
  for (size_t i = 0; i < value.length(); ++i) {
    if (value[i] < 0x21 || value[i] > 0x7E) return false;
  }
  return true;
}

bool validChannel(int value) { return value >= 1 && value <= 13; }
bool validMaxClients(int value) { return value >= 1 && value <= 8; }

// Levels the ESP32 radio accepts, in quarters of a dBm (wifi_power_t).
// Mirrored by NetworkDto.TX_POWER_LEVELS in the app.
const int8_t TX_POWER_LEVELS[] = {
    WIFI_POWER_20dBm, WIFI_POWER_19_5dBm, WIFI_POWER_19dBm, WIFI_POWER_18_5dBm,
    WIFI_POWER_17dBm, WIFI_POWER_15dBm,   WIFI_POWER_13dBm, WIFI_POWER_11dBm,
    WIFI_POWER_8_5dBm, WIFI_POWER_7dBm,   WIFI_POWER_5dBm,  WIFI_POWER_2dBm};

bool validTxPower(int quarterDbm) {
  for (int8_t level : TX_POWER_LEVELS) {
    if (level == quarterDbm) return true;
  }
  return false;
}

// The wire format and the file carry dBm ("tx_power": 8.5). Every level is a
// multiple of 0.25, so the conversion is exact.
float txPowerDbm(int8_t quarterDbm) { return quarterDbm / 4.0f; }

// Reads a "tx_power" value in dBm. A missing value gives the fallback.
bool parseTxPowerField(JsonVariant value, int8_t fallback, int8_t &out) {
  if (value.isNull()) {
    out = fallback;
    return true;
  }
  if (!value.is<float>() && !value.is<int>()) return false;
  float dbm = value.as<float>();
  if (!(dbm >= 0.0f && dbm <= 21.0f)) return false;
  float quarters = dbm * 4.0f;
  int rounded = static_cast<int>(lroundf(quarters));
  if (fabsf(quarters - rounded) > 0.01f || !validTxPower(rounded)) return false;
  out = static_cast<int8_t>(rounded);
  return true;
}

bool saveNetworkToFlash() {
  JsonDocument doc;
  doc["v"] = FILE_FORMAT;
  doc["ssid"] = apSsid;
  doc["password"] = apPassword;
  doc["token"] = apiToken;
  doc["channel"] = apChannel;
  doc["max_clients"] = apMaxClients;
  // A local-only switch on trial is not stored until a client confirmed it.
  doc["local_only"] = localOnlyTrial ? localOnlyPrevious : apLocalOnly;
  doc["tx_power"] = txPowerDbm(apTxPower);
  String payload;
  serializeJson(doc, payload);
  return storageOk && writeFileAtomic(NETWORK_FILE, payload);
}

// Reads /network.json. A missing file is created from the build defaults, so
// the values a device first ran with stay with it across firmware updates.
// A damaged file never locks the user out: the defaults are used and the
// file is left alone until POST /network replaces it.
void loadNetworkFromFlash() {
  apSsid = DEFAULT_AP_SSID;
  apPassword = DEFAULT_AP_PASSWORD;
  apiToken = DEFAULT_API_TOKEN;
  apChannel = DEFAULT_AP_CHANNEL;
  apMaxClients = DEFAULT_AP_MAX_CLIENTS;
  apLocalOnly = false;
  apTxPower = DEFAULT_TX_POWER_QDBM;
  localOnlyTrial = false;
  networkConfigError = false;
  if (!storageOk) return;
  if (!LittleFS.exists(NETWORK_FILE)) {
    saveNetworkToFlash();
    return;
  }
  File in = LittleFS.open(NETWORK_FILE, "r");
  if (!in) return;
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, in);
  in.close();
  if (err) {
    networkConfigError = fileContentIsBad(err);
    return;
  }
  String ssid = doc["ssid"] | DEFAULT_AP_SSID;
  String password = doc["password"] | DEFAULT_AP_PASSWORD;
  String token = doc["token"] | DEFAULT_API_TOKEN;
  int channel = doc["channel"] | static_cast<int>(DEFAULT_AP_CHANNEL);
  int maxClients = doc["max_clients"] | static_cast<int>(DEFAULT_AP_MAX_CLIENTS);
  bool localOnly = doc["local_only"] | false;
  int8_t txPower = DEFAULT_TX_POWER_QDBM;
  bool txPowerOk = parseTxPowerField(doc["tx_power"], DEFAULT_TX_POWER_QDBM, txPower);
  if (!validSsid(ssid) || !validPassword(password) || !validToken(token) ||
      !validChannel(channel) || !validMaxClients(maxClients) || !txPowerOk) {
    networkConfigError = true;
    return;
  }
  apSsid = ssid;
  apPassword = password;
  apiToken = token;
  apChannel = static_cast<uint8_t>(channel);
  apMaxClients = static_cast<uint8_t>(maxClients);
  apLocalOnly = localOnly;
  apTxPower = txPower;
  // Files without the new fields get them now, so a later firmware built with
  // other defaults cannot change them.
  if (doc["v"].isNull() || doc["channel"].isNull() || doc["max_clients"].isNull() ||
      doc["local_only"].isNull() || doc["tx_power"].isNull()) {
    saveNetworkToFlash();
  }
}

// GPIOs that can drive the relay, the LED and the I2C bus: outputs that are
// not wired to the SPI flash (6..11), not the serial console (1, 3) and not
// input-only (34..39).
bool validOutputPin(int pin) {
  static const uint8_t PINS[] = {0,  2,  4,  5,  12, 13, 14, 15, 16, 17,
                                 18, 19, 21, 22, 23, 25, 26, 27, 32, 33};
  for (uint8_t candidate : PINS) {
    if (candidate == pin) return true;
  }
  return false;
}

bool validDeviceConfig(const DeviceConfig &config) {
  const uint8_t pins[] = {config.relay, config.ledGreen, config.ledRed, config.sda, config.scl};
  for (size_t i = 0; i < 5; ++i) {
    if (!validOutputPin(pins[i])) return false;
    for (size_t j = i + 1; j < 5; ++j) {
      if (pins[i] == pins[j]) return false;
    }
  }
  return true;
}

void writeDeviceConfig(JsonDocument &doc, const DeviceConfig &config) {
  doc["relay"] = config.relay;
  doc["led_green"] = config.ledGreen;
  doc["led_red"] = config.ledRed;
  doc["sda"] = config.sda;
  doc["scl"] = config.scl;
  doc["led_active_low"] = config.ledActiveLow;
}

bool saveDeviceConfig(const DeviceConfig &config) {
  JsonDocument doc;
  doc["v"] = FILE_FORMAT;
  writeDeviceConfig(doc, config);
  String payload;
  serializeJson(doc, payload);
  return storageOk && writeFileAtomic(DEVICE_FILE, payload);
}

// Reads a wiring field; false when it is present but not a usable integer.
bool readPinField(JsonVariant value, uint8_t fallback, uint8_t &out) {
  int pin = 0;
  if (!parseIntField(value, 0, 39, fallback, pin)) return false;
  out = static_cast<uint8_t>(pin);
  return true;
}

bool parseDeviceConfig(JsonDocument &doc, const DeviceConfig &base, DeviceConfig &out) {
  out = base;
  if (!readPinField(doc["relay"], base.relay, out.relay)) return false;
  if (!readPinField(doc["led_green"], base.ledGreen, out.ledGreen)) return false;
  if (!readPinField(doc["led_red"], base.ledRed, out.ledRed)) return false;
  if (!readPinField(doc["sda"], base.sda, out.sda)) return false;
  if (!readPinField(doc["scl"], base.scl, out.scl)) return false;
  if (!parseBoolField(doc["led_active_low"], base.ledActiveLow, out.ledActiveLow)) return false;
  return validDeviceConfig(out);
}

// Reads /device.json once at boot, before any pin is touched. Like the
// network file, a missing one is created from the build defaults.
void loadDeviceConfig() {
  device = DeviceConfig();
  deviceConfigError = false;
  if (!storageOk) return;
  if (!LittleFS.exists(DEVICE_FILE)) {
    saveDeviceConfig(device);
    return;
  }
  File in = LittleFS.open(DEVICE_FILE, "r");
  if (!in) return;
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, in);
  in.close();
  DeviceConfig parsed;
  if (err || !parseDeviceConfig(doc, DeviceConfig(), parsed)) {
    deviceConfigError = true;
    return;
  }
  device = parsed;
}

void tryMountStorage() {
  storageOk = LittleFS.begin(true);
  if (storageOk) {
    loadNetworkFromFlash();
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

// ------------------------------ Watchdog ----------------------------------

// Why the chip started this time. Read once at boot and reported in /status,
// so a brownout or a watchdog reset is visible from the app.
esp_reset_reason_t bootResetReason = ESP_RST_UNKNOWN;

const char *resetReasonName(esp_reset_reason_t reason) {
  switch (reason) {
    case ESP_RST_POWERON: return "power_on";
    case ESP_RST_EXT: return "external";
    case ESP_RST_SW: return "software";
    case ESP_RST_PANIC: return "panic";
    case ESP_RST_INT_WDT: return "int_wdt";
    case ESP_RST_TASK_WDT: return "task_wdt";
    case ESP_RST_WDT: return "wdt";
    case ESP_RST_DEEPSLEEP: return "deep_sleep";
    case ESP_RST_BROWNOUT: return "brownout";
    case ESP_RST_SDIO: return "sdio";
    default: return "unknown";
  }
}

// Watches the loop task: the core feeds the watchdog before every loop() pass.
// The idle task of core 0 stays watched, as in the core's default settings.
void startLoopWatchdog() {
  esp_task_wdt_config_t config = {};
  config.timeout_ms = LOOP_WDT_TIMEOUT_MS;
  config.idle_core_mask = 1U << 0;
  config.trigger_panic = true;
  if (esp_task_wdt_reconfigure(&config) != ESP_OK) {
    esp_task_wdt_init(&config);
  }
  enableLoopWDT();
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
  Wire.begin(device.sda, device.scl);
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

// One resolved day as a minute map. Every minute holds the index of the
// module that owns its bell (or NO_SOURCE) and the bell's role. Bells that
// share a minute ring once, so the first module to claim a minute owns it.
const uint16_t NO_SOURCE = 0xFFFF;
const uint8_t ROLE_FROM_CORRECTION = 0x80;

uint16_t minuteSource[MINUTES_PER_DAY];
uint8_t minuteRole[MINUTES_PER_DAY];
uint16_t dayBellCount = 0;

bool autoAllowsDate(uint32_t dateKey) {
  if (autoStartDate != 0 && dateKey < autoStartDate) return false;
  if (autoStopDate != 0 && dateKey > autoStopDate) return false;
  return true;
}

void markBell(int32_t minute, size_t moduleIndex, uint8_t role, bool correction) {
  if (minute < 0 || minute >= MINUTES_PER_DAY) return;
  if (minuteSource[minute] != NO_SOURCE) return;
  minuteSource[minute] = static_cast<uint16_t>(moduleIndex);
  minuteRole[minute] = static_cast<uint8_t>(role | (correction ? ROLE_FROM_CORRECTION : 0));
  ++dayBellCount;
}

void expandModule(size_t index, bool correction) {
  const ScheduleModule &module = modules[index];
  switch (module.type) {
    case MODULE_LESSON:
      if (module.pre > 0) {
        markBell(static_cast<int32_t>(module.start) - module.pre, index, ROLE_PRE, correction);
      }
      markBell(module.start, index, ROLE_START, correction);
      markBell(static_cast<int32_t>(module.start) + module.length, index, ROLE_END,
               correction);
      break;
    case MODULE_SERIES:
      for (uint32_t minute = module.start; minute <= module.until; minute += module.every) {
        markBell(static_cast<int32_t>(minute), index, ROLE_STEP, correction);
      }
      break;
    default:
      markBell(module.start, index, ROLE_BELL, correction);
      break;
  }
}

bool packHasModuleForDay(uint8_t packId, uint8_t weekdayMask) {
  for (size_t i = 0; i < moduleCount; ++i) {
    if (modules[i].packId == packId && (modules[i].daysMask & weekdayMask)) return true;
  }
  return false;
}

// Resolves a date exactly like BellSchedule.resolveDay() in the Android app.
// Packs are walked top to bottom. A base pack adds bells; a correction that
// applies (mute, or a module for this weekday) first drops every base bell
// collected above it, then adds its own.
void collectDayBells(uint32_t dateKey) {
  for (uint16_t m = 0; m < MINUTES_PER_DAY; ++m) minuteSource[m] = NO_SOURCE;
  dayBellCount = 0;
  if (!autoAllowsDate(dateKey)) return;
  uint8_t weekdayMask = weekdayMaskOf(dateKey);

  for (size_t p = 0; p < packCount; ++p) {
    const SchedulePack &pack = packs[p];
    if (!pack.enabled || !packAllowsDate(pack, dateKey)) continue;
    bool correction = pack.kind == PACK_CORRECTION;
    if (correction) {
      if (!pack.mute && !packHasModuleForDay(pack.id, weekdayMask)) continue;
      for (uint16_t m = 0; m < MINUTES_PER_DAY; ++m) {
        if (minuteSource[m] == NO_SOURCE) continue;
        if (minuteRole[m] & ROLE_FROM_CORRECTION) continue;
        minuteSource[m] = NO_SOURCE;
        --dayBellCount;
      }
      if (pack.mute) continue;
    }
    for (size_t i = 0; i < moduleCount; ++i) {
      if (modules[i].packId != pack.id) continue;
      if ((modules[i].daysMask & weekdayMask) == 0) continue;
      expandModule(i, correction);
    }
  }
}

// The next bell after the current minute, cached per minute and schedule
// revision because GET /status is polled every couple of seconds.
struct NextBell {
  bool valid = false;
  uint32_t dateKey = 0;
  uint16_t minute = 0;
  uint16_t moduleIndex = 0;
  uint8_t role = ROLE_BELL;
};

NextBell nextBell;
uint16_t todayBellCount = 0;
uint32_t nextBellMinuteKey = 0xFFFFFFFFUL;
uint32_t nextBellRevision = 0xFFFFFFFFUL;

void refreshNextBell() {
  if (!timeIsValid()) {
    nextBell.valid = false;
    todayBellCount = 0;
    nextBellMinuteKey = 0xFFFFFFFFUL;
    return;
  }
  uint32_t key = minuteKey(cachedNow);
  if (key == nextBellMinuteKey && scheduleRevision == nextBellRevision) return;
  nextBellMinuteKey = key;
  nextBellRevision = scheduleRevision;
  nextBell.valid = false;

  uint32_t dateKey = dateKeyOf(cachedNow);
  uint16_t nowMinute = static_cast<uint16_t>(cachedNow.hour() * 60 + cachedNow.minute());
  for (uint8_t day = 0; day < NEXT_BELL_HORIZON_DAYS; ++day) {
    collectDayBells(dateKey);
    if (day == 0) todayBellCount = dayBellCount;
    uint16_t from = day == 0 ? nowMinute + 1 : 0;
    for (uint16_t m = from; m < MINUTES_PER_DAY; ++m) {
      if (minuteSource[m] == NO_SOURCE) continue;
      nextBell.valid = true;
      nextBell.dateKey = dateKey;
      nextBell.minute = m;
      nextBell.moduleIndex = minuteSource[m];
      nextBell.role = minuteRole[m] & ~ROLE_FROM_CORRECTION;
      return;
    }
    dateKey = addDaysToDateKey(dateKey, 1);
  }
}

void checkSchedule() {
  if (!timeIsValid() || currentMode != "auto") return;
  if (cachedNow.second() >= RING_GRACE_SEC) return;

  uint32_t key = minuteKey(cachedNow);
  if (key == lastScheduleMinute) return;
  lastScheduleMinute = key;
  if (suppressMinuteSet && key == suppressMinuteKey) return;

  collectDayBells(dateKeyOf(cachedNow));
  uint16_t minuteOfDay =
      static_cast<uint16_t>(cachedNow.hour() * 60 + cachedNow.minute());
  if (minuteSource[minuteOfDay] == NO_SOURCE) return;

  // The ring duration is a device setting. A manual ring that is already
  // sounding is extended rather than cut short.
  if (ringing && ringLeftSec >= ringDefault) return;
  startRing(ringDefault);
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
//   green 5 Hz    - the bell is ringing
//   green 1 Hz    - idle and healthy
//   steady red    - warning: time not set, so the schedule will not fire
//   red 2 Hz      - critical error: RTC, storage or access point failed
void updateLed() {
  uint32_t nowMs = millis();

  if (ringing) {
    setLed(blinkPhase(nowMs, LED_RING_BLINK_MS), false);
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

// In local-only mode the DHCP server leaves out the router option. Without a
// default route Android and iOS treat the network as local, keep mobile data
// for the internet and still reach 192.168.4.1 over Wi-Fi.
void applyDhcpRouterOption() {
  esp_netif_t *netif = WiFi.AP.netif();
  if (netif == nullptr) return;
  dhcps_offer_t offer = apLocalOnly ? 0 : OFFER_ROUTER;
  esp_netif_dhcps_stop(netif);
  esp_netif_dhcps_option(netif, ESP_NETIF_OP_SET, ESP_NETIF_ROUTER_SOLICITATION_ADDRESS,
                         &offer, sizeof(offer));
  esp_netif_dhcps_start(netif);
}

// Can be called at any time while the access point runs; clients stay joined.
void applyTxPower() {
  if (!WiFi.setTxPower(static_cast<wifi_power_t>(apTxPower))) {
    Serial.printf("Wi-Fi: cannot set tx power %d/4 dBm\n", apTxPower);
  }
}

void startAccessPoint() {
  WiFi.mode(WIFI_AP);
  IPAddress ip(192, 168, 4, 1);
  IPAddress gateway = apLocalOnly ? IPAddress(0, 0, 0, 0) : ip;
  IPAddress subnet(255, 255, 255, 0);
  WiFi.softAPConfig(ip, gateway, subnet);
  const char *password = apPassword.length() > 0 ? apPassword.c_str() : nullptr;
  apOk = WiFi.softAP(apSsid.c_str(), password, apChannel, 0, apMaxClients);
  if (apOk) {
    applyDhcpRouterOption();
    applyTxPower();
  }
  apLastRetryMs = millis();
  apLastIpCheckMs = apLastRetryMs;
}

void scheduleApRestart() {
  apRestartPending = true;
  apRestartAtMs = millis() + AP_RESTART_DELAY_MS;
}

// Any authorised request after the access point came back proves that a
// client can still join it, so a local-only switch on trial is kept.
void confirmLocalOnlyTrial() {
  if (!localOnlyTrial || apRestartPending) return;
  localOnlyTrial = false;
  if (!saveNetworkToFlash()) {
    // Not stored: the next boot starts in the previous mode, which is safe.
    storageRetryPending = true;
  }
}

void handleApTimers() {
  uint32_t nowMs = millis();
  if (apRestartPending && static_cast<int32_t>(nowMs - apRestartAtMs) >= 0) {
    apRestartPending = false;
    WiFi.softAPdisconnect(true);
    startAccessPoint();
    if (localOnlyTrial) localOnlyTrialStartMs = millis();
    return;
  }
  if (localOnlyTrial && !apRestartPending &&
      nowMs - localOnlyTrialStartMs >= LOCAL_ONLY_TRIAL_MS) {
    // Nobody came back: return to the mode that worked.
    localOnlyTrial = false;
    apLocalOnly = localOnlyPrevious;
    scheduleApRestart();
    return;
  }
  if (rebootPending && static_cast<int32_t>(nowMs - rebootAtMs) >= 0) {
    relayOff();
    ESP.restart();
  }
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

void sendDocument(int code, JsonDocument &doc) {
  String payload;
  serializeJson(doc, payload);
  sendJson(code, payload);
}

void sendError(int code, const char *errorCode) {
  JsonDocument doc;
  doc["ok"] = false;
  doc["error"] = errorCode;
  sendDocument(code, doc);
}

// Compares in time that depends only on the lengths, so the response time does
// not reveal how many leading characters of a guess were right.
bool tokensEqual(const String &given, const String &expected) {
  if (given.length() != expected.length()) return false;
  uint8_t diff = 0;
  for (size_t i = 0; i < expected.length(); ++i) {
    diff |= static_cast<uint8_t>(given[i] ^ expected[i]);
  }
  return diff == 0;
}

// Brute-force brake: after AUTH_MAX_FAILURES wrong tokens every request is
// refused for AUTH_LOCKOUT_MS, the right token included.
const uint8_t AUTH_MAX_FAILURES = 10;
const uint32_t AUTH_LOCKOUT_MS = 30000;
uint8_t authFailures = 0;
bool authLocked = false;
uint32_t authLockedAtMs = 0;

bool authLockedOut() {
  if (authLocked && millis() - authLockedAtMs >= AUTH_LOCKOUT_MS) {
    authLocked = false;
    authFailures = 0;
  }
  return authLocked;
}

void noteAuthFailure() {
  if (++authFailures >= AUTH_MAX_FAILURES) {
    authLocked = true;
    authLockedAtMs = millis();
  }
}

// headerOnly: the token must come in the X-Token header. A web page in the
// phone's browser cannot add a custom header to a cross-origin request
// without a CORS preflight, which this server never approves, so such a
// request can never pass.
bool tokenAccepted(bool headerOnly = false) {
  if (apiToken.length() == 0) return true;
  if (authLockedOut()) return false;
  bool ok = (server.hasHeader("X-Token") && tokensEqual(server.header("X-Token"), apiToken)) ||
            (!headerOnly && server.hasArg("token") && tokensEqual(server.arg("token"), apiToken));
  if (ok) {
    authFailures = 0;
  } else {
    noteAuthFailure();
  }
  return ok;
}

bool requireToken(bool headerOnly = false) {
  if (tokenAccepted(headerOnly)) {
    confirmLocalOnlyTrial();
    return true;
  }
  sendError(authLocked ? 429 : 401, authLocked ? "too_many_attempts" : "unauthorized");
  return false;
}

// Changes made by POST requests need a JSON body. A cross-origin page can
// send text/plain or form bodies without a preflight, but not JSON, so this
// keeps a web page from driving the device when no token is set.
bool requireJsonRequest() {
  String type = server.header("Content-Type");
  type.toLowerCase();
  if (type.startsWith("application/json")) return true;
  sendError(415, "json_required");
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
    errorCode = "too_many_modules";
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

// Describes one resolved bell: its time, label and what it means.
void writeBell(JsonObject obj, uint16_t minute, uint16_t moduleIndex, uint8_t role) {
  const ScheduleModule &module = modules[moduleIndex];
  obj["time"] = formatTimeOfDay(minute);
  obj["label"] = module.label;
  obj["type"] = moduleTypeName(module.type);
  obj["role"] = bellRoleName(role);
}

void handleGetStatus() {
  if (!requireToken()) return;
  refreshNextBell();

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
  doc["modules"] = static_cast<unsigned>(moduleCount);
  doc["packs"] = static_cast<unsigned>(packCount);
  if (lastSync.length() > 0) {
    doc["synced"] = lastSync;
  } else {
    doc["synced"] = nullptr;
  }
  if (timeIsValid()) {
    doc["today_bells"] = todayBellCount;
  }
  if (nextBell.valid) {
    JsonObject next = doc["next"].to<JsonObject>();
    next["date"] = formatDateKey(nextBell.dateKey);
    writeBell(next, nextBell.minute, nextBell.moduleIndex, nextBell.role);
  } else {
    doc["next"] = nullptr;
  }
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
  if (networkConfigError) errors.add("network_config_error");
  if (deviceConfigError) errors.add("device_config_error");
  doc["fw"] = FW_VERSION;
  doc["api"] = API_VERSION;
  doc["min_app"] = MIN_APP_VERSION;
  doc["ssid"] = apSsid;
  doc["uptime"] = static_cast<uint32_t>(esp_timer_get_time() / 1000000ULL);
  doc["reset"] = resetReasonName(bootResetReason);
  sendDocument(200, doc);
}

bool parseRingSeconds(int &seconds) {
  if (server.hasArg("sec")) {
    String raw = server.arg("sec");
    raw.trim();
    if (raw.length() == 0 || raw.length() > 3) return false;
    for (size_t i = 0; i < raw.length(); ++i) {
      if (raw[i] < '0' || raw[i] > '9') return false;
    }
    seconds = static_cast<int>(raw.toInt());
  }
  if (server.hasArg("plain") && server.arg("plain").length() > 0) {
    JsonDocument doc;
    if (deserializeJson(doc, server.arg("plain"))) return false;
    if (!parseIntField(doc["sec"], MIN_RING_SEC, MAX_RING_SEC, seconds, seconds)) {
      return false;
    }
  }
  return seconds >= MIN_RING_SEC && seconds <= MAX_RING_SEC;
}

void handlePostRing() {
  if (!requireToken() || !requireJsonRequest()) return;
  int seconds = ringDefault;
  if (!parseRingSeconds(seconds)) {
    sendError(400, "invalid_sec");
    return;
  }
  startRing(static_cast<uint16_t>(seconds));

  JsonDocument doc;
  doc["ok"] = true;
  doc["ringing"] = true;
  doc["ring_left"] = ringLeftSec;
  sendDocument(200, doc);
}

void handlePostStop() {
  if (!requireToken() || !requireJsonRequest()) return;
  stopRing();
  JsonDocument doc;
  doc["ok"] = true;
  doc["ringing"] = false;
  sendDocument(200, doc);
}

void handleGetMode() {
  if (!requireToken()) return;
  JsonDocument doc;
  doc["mode"] = currentMode;
  sendDocument(200, doc);
}

void handlePostMode() {
  if (!requireToken() || !requireJsonRequest()) return;
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
  sendDocument(200, out);
}

void handlePostTime() {
  if (!requireToken() || !requireJsonRequest()) return;
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
  nextBellMinuteKey = 0xFFFFFFFFUL;

  // Remembering the sync time is a convenience; the clock is already set even
  // when the flash write fails.
  lastSync = formatDateTime(requested);
  saveSettingsToFlash();

  JsonDocument out;
  out["ok"] = true;
  out["time"] = lastSync;
  sendDocument(200, out);
}

void handleGetSchedule() {
  if (!requireToken()) return;
  JsonDocument doc;
  buildScheduleDocument(doc, true);
  sendDocument(200, doc);
}

// Resolved bell list for one date, useful for checking that the app's preview
// and the device agree.
void handleGetScheduleDay() {
  if (!requireToken()) return;
  uint32_t dateKey = 0;
  if (!server.hasArg("date") || !parseDateKey(server.arg("date"), dateKey)) {
    sendError(400, "invalid_date");
    return;
  }
  collectDayBells(dateKey);

  JsonDocument doc;
  doc["date"] = formatDateKey(dateKey);
  JsonArray bells = doc["bells"].to<JsonArray>();
  for (uint16_t m = 0; m < MINUTES_PER_DAY; ++m) {
    if (minuteSource[m] == NO_SOURCE) continue;
    JsonObject obj = bells.add<JsonObject>();
    writeBell(obj, m, minuteSource[m], minuteRole[m] & ~ROLE_FROM_CORRECTION);
    obj["pack"] = modules[minuteSource[m]].packId;
  }
  sendDocument(200, doc);
}

void handlePostSchedule() {
  if (!requireToken() || !requireJsonRequest()) return;

  // The parsed request is freed before the schedule is saved, so the request
  // document and the one written to flash are never in RAM together.
  {
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
  out["modules"] = static_cast<unsigned>(moduleCount);
  out["packs"] = static_cast<unsigned>(packCount);
  sendDocument(200, out);
}

// Factory reset: forgets the schedule, the mode, the ring duration and the
// last sync time. With {"network": true} the access point and the token go
// back to the build defaults too. The clock keeps running; it lives in the
// DS3231.
void handlePostReset() {
  if (!requireToken() || !requireJsonRequest()) return;
  bool network = false;
  if (server.hasArg("plain") && server.arg("plain").length() > 0) {
    JsonDocument doc;
    if (deserializeJson(doc, server.arg("plain")) ||
        !parseBoolField(doc["network"], false, network)) {
      sendError(400, "invalid_request");
      return;
    }
  }
  stopRing();
  bool ok = storageOk;
  if (ok) {
    if (LittleFS.exists(SCHEDULE_FILE) && !LittleFS.remove(SCHEDULE_FILE)) ok = false;
    if (LittleFS.exists(SETTINGS_FILE) && !LittleFS.remove(SETTINGS_FILE)) ok = false;
    if (network && LittleFS.exists(NETWORK_FILE) && !LittleFS.remove(NETWORK_FILE)) ok = false;
  }
  // The wiring in /device.json describes the hardware, not a preference, so
  // a reset keeps it.
  bool restart = false;
  if (network) {
    String oldSsid = apSsid;
    String oldPassword = apPassword;
    uint8_t oldChannel = apChannel;
    uint8_t oldMaxClients = apMaxClients;
    bool oldLocalOnly = apLocalOnly;
    loadNetworkFromFlash();
    restart = apSsid != oldSsid || apPassword != oldPassword || apChannel != oldChannel ||
              apMaxClients != oldMaxClients || apLocalOnly != oldLocalOnly;
    if (!restart && apOk) applyTxPower();
  }
  if (restart) scheduleApRestart();
  loadSettingsFromFlash();
  moduleCount = 0;
  packCount = 0;
  autoStartDate = 0;
  autoStopDate = 0;
  ++scheduleRevision;
  if (!ok) {
    sendError(500, "storage_error");
    return;
  }
  JsonDocument out;
  out["ok"] = true;
  out["restart"] = restart;
  sendDocument(200, out);
}

void handleGetNetwork() {
  if (!requireToken()) return;
  JsonDocument doc;
  doc["ssid"] = apSsid;
  doc["channel"] = apChannel;
  doc["max_clients"] = apMaxClients;
  doc["open"] = apPassword.length() == 0;
  doc["token_set"] = apiToken.length() > 0;
  doc["local_only"] = apLocalOnly;
  doc["local_only_trial"] = localOnlyTrial;
  doc["tx_power"] = txPowerDbm(apTxPower);
  sendDocument(200, doc);
}

// Changes the access point name, its password, channel, client limit,
// local-only mode, transmit power and the API token. A new transmit power
// applies at once, without a restart of the access point. Every field is optional; "password": ""
// makes the network open and "token": "" turns authentication off. The access
// point restarts shortly after the response when anything but the token
// changed. A change of "local_only" is a trial: it is stored only once a
// client talks to the device on the restarted network.
void handlePostNetwork() {
  if (!requireToken() || !requireJsonRequest()) return;
  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }
  String ssid = apSsid;
  String password = apPassword;
  String token = apiToken;
  JsonVariant ssidValue = doc["ssid"];
  if (!ssidValue.isNull()) {
    if (!ssidValue.is<const char *>()) {
      sendError(400, "invalid_ssid");
      return;
    }
    ssid = String(ssidValue.as<const char *>());
  }
  JsonVariant passwordValue = doc["password"];
  if (!passwordValue.isNull()) {
    if (!passwordValue.is<const char *>()) {
      sendError(400, "invalid_password");
      return;
    }
    password = String(passwordValue.as<const char *>());
  }
  JsonVariant tokenValue = doc["token"];
  if (!tokenValue.isNull()) {
    if (!tokenValue.is<const char *>()) {
      sendError(400, "invalid_token");
      return;
    }
    token = String(tokenValue.as<const char *>());
  }
  if (!validSsid(ssid)) {
    sendError(400, "invalid_ssid");
    return;
  }
  if (!validPassword(password)) {
    sendError(400, "invalid_password");
    return;
  }
  if (!validToken(token)) {
    sendError(400, "invalid_token");
    return;
  }
  int channel = apChannel;
  if (!parseIntField(doc["channel"], 1, 13, apChannel, channel)) {
    sendError(400, "invalid_channel");
    return;
  }
  int maxClients = apMaxClients;
  if (!parseIntField(doc["max_clients"], 1, 8, apMaxClients, maxClients)) {
    sendError(400, "invalid_max_clients");
    return;
  }
  bool localOnly = apLocalOnly;
  if (!parseBoolField(doc["local_only"], apLocalOnly, localOnly)) {
    sendError(400, "invalid_local_only");
    return;
  }
  int8_t txPower = apTxPower;
  if (!parseTxPowerField(doc["tx_power"], apTxPower, txPower)) {
    sendError(400, "invalid_tx_power");
    return;
  }

  bool localOnlyChanged = localOnly != apLocalOnly;
  bool restart = ssid != apSsid || password != apPassword || channel != apChannel ||
                 maxClients != apMaxClients || localOnlyChanged;
  bool txPowerChanged = txPower != apTxPower;
  bool changed = restart || token != apiToken || txPowerChanged;
  if (changed) {
    String oldSsid = apSsid;
    String oldPassword = apPassword;
    String oldToken = apiToken;
    uint8_t oldChannel = apChannel;
    uint8_t oldMaxClients = apMaxClients;
    bool oldLocalOnly = apLocalOnly;
    int8_t oldTxPower = apTxPower;
    bool oldTrial = localOnlyTrial;
    bool oldPrevious = localOnlyPrevious;
    if (localOnlyChanged) {
      // Revert target: the mode that is stored, i.e. known to work.
      if (!localOnlyTrial) localOnlyPrevious = apLocalOnly;
      localOnlyTrial = localOnly != localOnlyPrevious;
    }
    apSsid = ssid;
    apPassword = password;
    apiToken = token;
    apChannel = static_cast<uint8_t>(channel);
    apMaxClients = static_cast<uint8_t>(maxClients);
    apLocalOnly = localOnly;
    apTxPower = txPower;
    if (!saveNetworkToFlash()) {
      apSsid = oldSsid;
      apPassword = oldPassword;
      apiToken = oldToken;
      apChannel = oldChannel;
      apMaxClients = oldMaxClients;
      apLocalOnly = oldLocalOnly;
      apTxPower = oldTxPower;
      localOnlyTrial = oldTrial;
      localOnlyPrevious = oldPrevious;
      sendError(500, "storage_error");
      return;
    }
  }
  if (restart) {
    scheduleApRestart();
  } else if (txPowerChanged && apOk) {
    applyTxPower();
  }

  JsonDocument out;
  out["ok"] = true;
  out["ssid"] = apSsid;
  out["restart"] = restart;
  out["local_only"] = apLocalOnly;
  out["local_only_trial"] = localOnlyTrial;
  out["tx_power"] = txPowerDbm(apTxPower);
  sendDocument(200, out);
}

void handleGetSettings() {
  if (!requireToken()) return;
  JsonDocument doc;
  doc["ring_default"] = ringDefault;
  sendDocument(200, doc);
}

void handlePostSettings() {
  if (!requireToken() || !requireJsonRequest()) return;
  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }
  int newDefault = 0;
  if (doc["ring_default"].isNull() ||
      !parseIntField(doc["ring_default"], MIN_RING_SEC, MAX_RING_SEC, 0, newDefault)) {
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
  sendDocument(200, out);
}

// ------------------------------- Wiring -----------------------------------

void handleGetDevice() {
  if (!requireToken()) return;
  JsonDocument doc;
  writeDeviceConfig(doc, device);
  doc["error"] = deviceConfigError;
  sendDocument(200, doc);
}

// Stores new wiring and restarts the device, because pins are set up once at
// boot. Every field is optional; the five pins must be distinct GPIOs that
// can drive an output.
void handlePostDevice() {
  if (!requireToken() || !requireJsonRequest()) return;
  JsonDocument doc;
  const char *errorCode = nullptr;
  if (!readJsonBody(doc, errorCode)) {
    sendError(400, errorCode);
    return;
  }
  DeviceConfig updated;
  if (!parseDeviceConfig(doc, device, updated)) {
    sendError(400, "invalid_pins");
    return;
  }
  if (!saveDeviceConfig(updated)) {
    sendError(500, "storage_error");
    return;
  }
  stopRing();
  rebootPending = true;
  rebootAtMs = millis() + REBOOT_DELAY_MS;
  JsonDocument out;
  out["ok"] = true;
  out["restart"] = true;
  sendDocument(200, out);
}

// --------------------------- Firmware update ------------------------------
//
// POST /update?md5=<32 hex>&size=<bytes> with the image as the "firmware"
// part of a multipart/form-data body. The image goes into the idle OTA slot;
// the device boots it only after the whole image was written and its MD5
// matched, so a broken upload or a power cut keeps the running firmware.
// LittleFS is a separate partition and is not touched: the schedule, the
// settings, the network and the wiring all survive the update.
//
// Flashing needs an API token to be set and sent in the X-Token header, so
// neither a client without the token nor a web page can replace the firmware.

bool validMd5(const String &value) {
  if (value.length() != 32) return false;
  for (size_t i = 0; i < value.length(); ++i) {
    char c = value[i];
    bool hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    if (!hex) return false;
  }
  return true;
}

void failUpdate(const char *error) {
  if (Update.isRunning()) Update.abort();
  if (updateError == nullptr) updateError = error;
  updateRunning = false;
}

// Called by the web server for every chunk of the uploaded file.
void handleUpdateUpload() {
  HTTPUpload &upload = server.upload();
  switch (upload.status) {
    case UPLOAD_FILE_START: {
      updateError = nullptr;
      updateRunning = false;
      updateAuthorized = apiToken.length() > 0 && tokenAccepted(true);
      if (apiToken.length() == 0) {
        updateError = "token_required";
        return;
      }
      if (!updateAuthorized) {
        updateError = authLocked ? "too_many_attempts" : "unauthorized";
        return;
      }
      String md5 = server.arg("md5");
      if (!validMd5(md5)) {
        updateError = "invalid_md5";
        return;
      }
      size_t size = UPDATE_SIZE_UNKNOWN;
      if (server.hasArg("size")) {
        long parsed = server.arg("size").toInt();
        if (parsed <= 0) {
          updateError = "invalid_size";
          return;
        }
        size = static_cast<size_t>(parsed);
      }
      // The web server does not run the loop while it reads the body, so a
      // ring could not be stopped in time and a bell would be missed anyway.
      stopRing();
      if (!Update.begin(size, U_FLASH)) {
        failUpdate("update_begin_failed");
        return;
      }
      Update.setMD5(md5.c_str());
      updateRunning = true;
      break;
    }
    case UPLOAD_FILE_WRITE:
      // The whole upload is read inside one loop() pass.
      esp_task_wdt_reset();
      if (!updateRunning) return;
      if (Update.write(upload.buf, upload.currentSize) != upload.currentSize) {
        failUpdate("update_write_failed");
      }
      break;
    case UPLOAD_FILE_END:
      if (!updateRunning) return;
      updateRunning = false;
      if (!Update.end(true)) {
        // end() checks the MD5 and the image header before switching slots.
        updateError = Update.getError() == UPDATE_ERROR_MD5 ? "md5_mismatch" : "invalid_image";
      }
      break;
    case UPLOAD_FILE_ABORTED:
    default:
      failUpdate("upload_aborted");
      break;
  }
}

// Called once the request is complete.
void handlePostUpdate() {
  if (updateError == nullptr && !updateAuthorized) {
    // No file part was sent at all.
    updateError = "no_firmware";
  }
  if (updateError != nullptr) {
    const char *error = updateError;
    updateError = nullptr;
    updateAuthorized = false;
    int code = 400;
    if (strcmp(error, "unauthorized") == 0) code = 401;
    else if (strcmp(error, "token_required") == 0) code = 403;
    else if (strcmp(error, "too_many_attempts") == 0) code = 429;
    else if (strncmp(error, "update_", 7) == 0) code = 500;
    sendError(code, error);
    return;
  }
  updateAuthorized = false;
  rebootPending = true;
  rebootAtMs = millis() + REBOOT_DELAY_MS;
  JsonDocument out;
  out["ok"] = true;
  out["restart"] = true;
  sendDocument(200, out);
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
:root{--bg:#14120f;--card:#1f1c17;--ink:#f3ede2;--mute:#a59d8f;--acc:#e8a33d;--bad:#e5645a}
*{box-sizing:border-box}body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:var(--bg);color:var(--ink);max-width:480px;margin:auto}
h1{font-size:1.1rem;color:var(--mute);font-weight:500;margin:4px 0 12px}
.card{background:var(--card);border-radius:16px;padding:16px;margin-bottom:12px}
.clock{font-size:3rem;font-weight:700;font-variant-numeric:tabular-nums;letter-spacing:-1px}
.mute{color:var(--mute);font-size:.9rem}.bad{color:var(--bad)}
button{font:inherit;padding:12px 16px;margin:4px 4px 0 0;border-radius:12px;border:0;background:#2c2820;color:var(--ink)}
button.acc{background:var(--acc);color:#1b1408;font-weight:600}
input{font:inherit;width:72px;padding:10px;border-radius:10px;border:1px solid #3a352c;background:#16140f;color:var(--ink)}
</style></head><body>
<h1>BellTimer</h1>
<div class="card"><div class="clock" id="clock">--:--:--</div>
<div class="mute" id="info">…</div><div class="bad" id="err"></div>
<button class="acc" onclick="sync()">Синхронизировать с этим устройством</button></div>
<div class="card"><div class="mute">Следующий звонок</div><div id="next" style="font-size:1.4rem;margin-top:4px">—</div></div>
<div class="card"><input id="sec" type="number" min="1" max="60" value="5"> с
<button class="acc" onclick="ring()">Звонок</button><button onclick="post('/stop')">Стоп</button><br>
<button onclick="post('/mode',{mode:'auto'})">Авто</button><button onclick="post('/mode',{mode:'manual'})">Вручную</button></div>
<div class="card"><input id="token" placeholder="X-Token" style="width:100%"></div>
<script>
const $=id=>document.getElementById(id);
function hdr(){const t=$('token').value;const h={'Content-Type':'application/json'};if(t)h['X-Token']=t;return h}
async function post(p,b){await fetch(p,{method:'POST',headers:hdr(),body:b?JSON.stringify(b):''});refresh()}
function ring(){post('/ring',{sec:+$('sec').value})}
function pad(n){return String(n).padStart(2,'0')}
function sync(){const d=new Date();post('/time',{datetime:d.getFullYear()+'-'+pad(d.getMonth()+1)+'-'+pad(d.getDate())+'T'+pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds())})}
async function refresh(){try{const r=await fetch('/status',{headers:hdr()});const s=await r.json();
$('clock').textContent=s.time?s.time.slice(11):'--:--:--';
$('info').textContent=(s.mode==='auto'?'Авто':'Вручную')+' · модулей '+s.modules+(s.ringing?' · звонит '+s.ring_left+' с':'')+' · fw '+s.fw;
$('err').textContent=(s.errors||[]).join(', ');
$('next').textContent=s.next?(s.next.date.slice(5)+' '+s.next.time+' '+(s.next.label||'')):'—';
}catch(e){$('err').textContent='нет связи'}}
refresh();setInterval(refresh,2000);
</script></body></html>)HTML";
  server.send_P(200, "text/html; charset=utf-8", PAGE);
}

void handleNotFound() { sendError(404, "not_found"); }

void setupHttpRoutes() {
  // No CORS: the app is not a web page and the built-in page is same-origin,
  // so allowing cross-origin requests would only let any web site the phone
  // opens talk to the bell.
  // WebServer keeps only the request headers it is told to collect; without
  // this the X-Token header is dropped and only ?token= would work.
  static const char *HEADER_KEYS[] = {"X-Token", "Content-Type"};
  server.collectHeaders(HEADER_KEYS, 2);

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
  server.on("/reset", HTTP_POST, handlePostReset);
  server.on("/network", HTTP_GET, handleGetNetwork);
  server.on("/network", HTTP_POST, handlePostNetwork);
  server.on("/device", HTTP_GET, handleGetDevice);
  server.on("/device", HTTP_POST, handlePostDevice);
  server.on("/update", HTTP_POST, handlePostUpdate, handleUpdateUpload);
  server.onNotFound(handleNotFound);
}

}  // namespace

void setup() {
  // The wiring lives in /device.json, so storage comes first. Until then
  // every GPIO is still a floating input, as during the boot ROM.
  storageOk = LittleFS.begin(true);
  loadDeviceConfig();

  // Force the active-low relay off before the pin becomes an output.
  digitalWrite(device.relay, HIGH);
  pinMode(device.relay, OUTPUT);
  relayOff();

  // KY-029: drive the dark level before the pins become outputs.
  digitalWrite(device.ledGreen, ledOffLevel());
  digitalWrite(device.ledRed, ledOffLevel());
  pinMode(device.ledGreen, OUTPUT);
  pinMode(device.ledRed, OUTPUT);
  setLed(false, false);

  Serial.begin(115200);
  bootResetReason = esp_reset_reason();
  Serial.printf("BellTimer %s, reset reason: %s\n", FW_VERSION, resetReasonName(bootResetReason));

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

  // Last, so the first-boot LittleFS format above cannot trip it.
  startLoopWatchdog();
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
