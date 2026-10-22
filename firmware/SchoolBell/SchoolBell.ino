// SchoolBell - programmable school bell timer for ESP32.
//
// Hardware:
//   GPIO21/22 - DS3231 RTC (I2C, address 0x68, stores LOCAL time)
//   GPIO26    - relay input, ACTIVE LOW (LOW = relay on)
//   GPIO27    - status LED, active HIGH
//
// The device creates its own Wi-Fi access point and serves a small JSON API.

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <LittleFS.h>
#include <Wire.h>
#include <RTClib.h>
#include <ArduinoJson.h>

namespace {

const char *const FW_VERSION = "1.0.0";

// ----------------------------- Configuration ------------------------------

const char *const AP_SSID = "SchoolBell";
const char *const AP_PASSWORD = "bell12345";
const char *const API_TOKEN = "";  // Leave empty to disable the X-Token check.
const uint8_t AP_CHANNEL = 6;
const uint8_t AP_MAX_CLIENTS = 4;

const uint8_t PIN_RELAY = 26;
const uint8_t PIN_LED = 27;
const uint8_t PIN_I2C_SDA = 21;
const uint8_t PIN_I2C_SCL = 22;

const size_t MAX_ENTRIES = 64;
const size_t MAX_LABEL_BYTES = 45;  // About 15 Cyrillic characters in UTF-8.
const uint8_t MIN_RING_SEC = 1;
const uint8_t MAX_RING_SEC = 60;
const uint8_t DEFAULT_RING_SEC = 5;

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
  uint8_t daysMask = 0;      // bit 0 = Monday ... bit 6 = Sunday
  uint8_t duration = DEFAULT_RING_SEC;
  String label;
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

// LED state.
uint32_t ledLastChangeMs = 0;
size_t ledStepIndex = 0;
bool ledSosActive = false;

struct LedStep {
  bool on;
  uint16_t durationMs;
};

const LedStep SOS_PATTERN[] = {
    {true, 150},   // . 
    {false, 150},  //
    {true, 150},   // .
    {false, 150},  //
    {true, 150},   // .
    {false, 450},  // letter gap
    {true, 450},   // -
    {false, 150},  //
    {true, 450},   // -
    {false, 150},  //
    {true, 450},   // -
    {false, 450},  // letter gap
    {true, 150},   // .
    {false, 150},  //
    {true, 150},   // .
    {false, 150},  //
    {true, 150},   // .
    {false, 1500}  // pause before repeating
};
const size_t SOS_PATTERN_LEN = sizeof(SOS_PATTERN) / sizeof(SOS_PATTERN[0]);

// --------------------------- Small helpers --------------------------------

void relayOn() { digitalWrite(PIN_RELAY, LOW); }
void relayOff() { digitalWrite(PIN_RELAY, HIGH); }

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

bool entryLess(const ScheduleEntry &a, const ScheduleEntry &b) {
  if (a.minuteOfDay != b.minuteOfDay) return a.minuteOfDay < b.minuteOfDay;
  return a.label.compareTo(b.label) < 0;
}

// Insertion sort is used instead of qsort because ScheduleEntry owns a String.
void sortEntries(ScheduleEntry *list, size_t count) {
  for (size_t i = 1; i < count; ++i) {
    ScheduleEntry value = list[i];
    size_t j = i;
    while (j > 0 && entryLess(value, list[j - 1])) {
      list[j] = list[j - 1];
      --j;
    }
    list[j] = value;
  }
}

// Validates the document in place, fills "out" and "count".
// Returns nullptr on success, otherwise a stable error code string.
const char *validateScheduleDocument(JsonDocument &doc, ScheduleEntry *out,
                                     size_t &count) {
  count = 0;
  JsonVariant root = doc["entries"];
  if (root.isNull() || !root.is<JsonArray>()) return "invalid_schedule";
  JsonArray arr = root.as<JsonArray>();
  if (arr.size() > MAX_ENTRIES) return "too_many_entries";

  for (JsonVariant item : arr) {
    if (!item.is<JsonObject>()) return "invalid_schedule";
    JsonObject obj = item.as<JsonObject>();

    JsonVariant timeValue = obj["time"];
    if (!timeValue.is<const char *>()) return "invalid_schedule";
    uint16_t minuteOfDay = 0;
    if (!parseTimeOfDay(String(timeValue.as<const char *>()), minuteOfDay)) {
      return "invalid_schedule";
    }

    uint8_t daysMask = 0;
    JsonVariant daysValue = obj["days"];
    if (daysValue.isNull()) {
      daysMask = 0x7F;  // default: all days
    } else {
      if (!daysValue.is<JsonArray>()) return "invalid_schedule";
      JsonArray days = daysValue.as<JsonArray>();
      if (days.size() == 0) return "invalid_schedule";
      for (JsonVariant dayValue : days) {
        if (!dayValue.is<int>()) return "invalid_schedule";
        int day = dayValue.as<int>();
        if (day < 1 || day > 7) return "invalid_schedule";
        daysMask = static_cast<uint8_t>(daysMask | (1U << (day - 1)));
      }
    }

    uint8_t duration = ringDefault;
    JsonVariant durationValue = obj["duration"];
    if (!durationValue.isNull()) {
      if (!durationValue.is<int>()) return "invalid_schedule";
      int d = durationValue.as<int>();
      if (d < MIN_RING_SEC || d > MAX_RING_SEC) return "invalid_schedule";
      duration = static_cast<uint8_t>(d);
    }

    String label;
    JsonVariant labelValue = obj["label"];
    if (!labelValue.isNull()) {
      if (!labelValue.is<const char *>()) return "invalid_schedule";
      label = truncateUtf8(String(labelValue.as<const char *>()), MAX_LABEL_BYTES);
    }

    out[count].minuteOfDay = minuteOfDay;
    out[count].daysMask = daysMask;
    out[count].duration = duration;
    out[count].label = label;
    ++count;
  }

  if (count > 1) {
    sortEntries(out, count);
  }
  return nullptr;
}

void loadScheduleFromFlash() {
  entryCount = 0;
  if (!storageOk) return;
  File in = LittleFS.open(SCHEDULE_FILE, "r");
  if (!in) return;
  JsonDocument doc;
  DeserializationError err = deserializeJson(doc, in);
  in.close();
  if (err) return;

  ScheduleEntry parsed[MAX_ENTRIES];
  size_t parsedCount = 0;
  if (validateScheduleDocument(doc, parsed, parsedCount) != nullptr) return;
  for (size_t i = 0; i < parsedCount; ++i) {
    entries[i] = parsed[i];
  }
  entryCount = parsedCount;
}

bool saveScheduleToFlash(const ScheduleEntry *list, size_t count) {
  JsonDocument doc;
  JsonArray arr = doc["entries"].to<JsonArray>();
  for (size_t i = 0; i < count; ++i) {
    JsonObject obj = arr.add<JsonObject>();
    obj["time"] = formatTimeOfDay(list[i].minuteOfDay);
    JsonArray days = obj["days"].to<JsonArray>();
    for (uint8_t bit = 0; bit < 7; ++bit) {
      if (list[i].daysMask & (1U << bit)) days.add(bit + 1);
    }
    obj["duration"] = list[i].duration;
    obj["label"] = list[i].label;
  }
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

void checkSchedule() {
  if (!timeIsValid() || currentMode != "auto" || ringing) return;
  if (cachedNow.second() != 0) return;

  uint32_t key = minuteKey(cachedNow);
  if (key == lastScheduleMinute) return;
  lastScheduleMinute = key;
  if (suppressMinuteSet && key == suppressMinuteKey) return;

  uint16_t minuteOfDay =
      static_cast<uint16_t>(cachedNow.hour() * 60 + cachedNow.minute());
  uint8_t mask = static_cast<uint8_t>(1U << (isoWeekday(cachedNow) - 1));
  uint8_t bestDuration = 0;
  for (size_t i = 0; i < entryCount; ++i) {
    if (entries[i].minuteOfDay == minuteOfDay && (entries[i].daysMask & mask)) {
      if (entries[i].duration > bestDuration) bestDuration = entries[i].duration;
    }
  }
  if (bestDuration > 0) startRing(bestDuration);
}

// -------------------------------- LED -------------------------------------

bool hasActiveErrors() { return !rtcOk || !timeIsValid() || !storageOk || !apOk; }

void resetSos() {
  ledSosActive = false;
  ledStepIndex = 0;
  ledLastChangeMs = millis();
}

void updateLed() {
  if (ringing) {
    digitalWrite(PIN_LED, HIGH);
    resetSos();
    return;
  }

  if (hasActiveErrors()) {
    uint32_t nowMs = millis();
    if (!ledSosActive) {
      ledSosActive = true;
      ledStepIndex = 0;
      ledLastChangeMs = nowMs;
      digitalWrite(PIN_LED, SOS_PATTERN[0].on ? HIGH : LOW);
      return;
    }
    if (nowMs - ledLastChangeMs >= SOS_PATTERN[ledStepIndex].durationMs) {
      ledLastChangeMs = nowMs;
      ledStepIndex = (ledStepIndex + 1) % SOS_PATTERN_LEN;
      digitalWrite(PIN_LED, SOS_PATTERN[ledStepIndex].on ? HIGH : LOW);
    }
    return;
  }

  if (ledSosActive) resetSos();
  bool on = (millis() / 500UL) % 2UL == 0UL;
  digitalWrite(PIN_LED, on ? HIGH : LOW);
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
  DeserializationError err = deserializeJson(doc, server.arg("plain"));
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
  JsonArray errors = doc["errors"].to<JsonArray>();
  if (!rtcOk) errors.add("rtc_not_found");
  if (rtcOk && !timeIsValid()) errors.add("time_not_set");
  if (!storageOk) errors.add("storage_error");
  if (!apOk) errors.add("wifi_ap_error");
  doc["fw"] = FW_VERSION;
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
  JsonArray arr = doc["entries"].to<JsonArray>();
  for (size_t i = 0; i < entryCount; ++i) {
    JsonObject obj = arr.add<JsonObject>();
    obj["time"] = formatTimeOfDay(entries[i].minuteOfDay);
    JsonArray days = obj["days"].to<JsonArray>();
    for (uint8_t bit = 0; bit < 7; ++bit) {
      if (entries[i].daysMask & (1U << bit)) days.add(bit + 1);
    }
    obj["duration"] = entries[i].duration;
    obj["label"] = entries[i].label;
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

  ScheduleEntry parsed[MAX_ENTRIES];
  size_t parsedCount = 0;
  const char *validationError = validateScheduleDocument(doc, parsed, parsedCount);
  if (validationError != nullptr) {
    sendError(400, validationError);
    return;
  }

  if (!saveScheduleToFlash(parsed, parsedCount)) {
    sendError(500, "storage_error");
    return;
  }

  for (size_t i = 0; i < parsedCount; ++i) {
    entries[i] = parsed[i];
  }
  entryCount = parsedCount;

  JsonDocument out;
  out["ok"] = true;
  out["entries"] = static_cast<unsigned>(entryCount);
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
<title>SchoolBell</title>
<style>
body{font-family:system-ui,sans-serif;margin:16px;background:#111;color:#eee}
button{font-size:1rem;padding:8px 14px;margin:4px;border-radius:8px;border:0;background:#3b82f6;color:#fff}
pre{background:#222;padding:10px;border-radius:8px;white-space:pre-wrap}
input{width:80px;padding:6px;margin:4px;border-radius:6px;border:1px solid #555;background:#222;color:#eee}
</style></head><body>
<h1>SchoolBell</h1>
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
                         "/mode", "/time",   "/schedule", "/settings"};
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

  pinMode(PIN_LED, OUTPUT);
  digitalWrite(PIN_LED, LOW);

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
  ledLastChangeMs = millis();
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
