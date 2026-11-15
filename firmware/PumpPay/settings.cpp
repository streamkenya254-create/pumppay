#include "settings.h"
#include <Preferences.h>

namespace Settings {
namespace {

// ===========================================================================
//  DEFAULTS - used only when nothing has ever been stored
// ===========================================================================
// Leave DEFAULT_SSID empty. An empty SSID means the radio never starts, so a
// board that has never been given credentials behaves exactly as it did before
// WiFi existed rather than failing to join a network nobody named.
constexpr char DEFAULT_SSID[] = "";
constexpr char DEFAULT_PASS[] = "";

// Deliberately obvious. The web UI checks against this exact value and
// complains at every boot until it changes.
constexpr char DEFAULT_PIN[]  = "0000";

// Where readings go until told otherwise. This is the address a Cloud Function
// named pumppayIngest has in the paperless-receipt-ke project once deployed.
// Nothing is sent until a key is also stored.
constexpr char DEFAULT_CLOUD_URL[] =
    "https://us-central1-paperless-receipt-ke.cloudfunctions.net/pumppayIngest";

// NVS namespace. Anything stored under it survives a reflash; only
// factoryReset() clears it.
constexpr char NVS_AREA[] = "pumppay";

Preferences s_prefs;

char s_ssid[SSID_MAX + 1] = { 0 };
char s_pass[PASS_MAX + 1] = { 0 };
char s_pin [PIN_MAX  + 1] = { 0 };
uint8_t s_line = 1;
char s_cloudUrl[CLOUD_URL_MAX + 1] = { 0 };
char s_cloudKey[CLOUD_KEY_MAX + 1] = { 0 };

void load() {
  s_prefs.getString("ssid", s_ssid, sizeof(s_ssid));
  s_prefs.getString("pass", s_pass, sizeof(s_pass));
  s_prefs.getString("pin",  s_pin,  sizeof(s_pin));
  s_prefs.getString("curl", s_cloudUrl, sizeof(s_cloudUrl));
  s_prefs.getString("ckey", s_cloudKey, sizeof(s_cloudKey));
  if (s_cloudUrl[0] == '\0') strncpy(s_cloudUrl, DEFAULT_CLOUD_URL, sizeof(s_cloudUrl) - 1);
  s_line = s_prefs.getUChar("line", 1);
  if (s_line != 1 && s_line != 2) s_line = 1;

  // getString leaves the buffer empty when the key is absent, which is
  // indistinguishable from a stored empty string - fine here, because an
  // empty stored value and an absent one should both fall back.
  if (s_ssid[0] == '\0') strncpy(s_ssid, DEFAULT_SSID, sizeof(s_ssid) - 1);
  if (s_pass[0] == '\0') strncpy(s_pass, DEFAULT_PASS, sizeof(s_pass) - 1);
  if (s_pin[0]  == '\0') strncpy(s_pin,  DEFAULT_PIN,  sizeof(s_pin)  - 1);
}

bool allDigits(const char* s) {
  for (const char* p = s; *p; p++) {
    if (*p < '0' || *p > '9') return false;
  }
  return true;
}

}  // namespace

void begin() {
  // false = read/write. The namespace is created on first use.
  if (!s_prefs.begin(NVS_AREA, false)) {
    Serial.println("[set] NVS would not open - running on defaults only,");
    Serial.println("[set]   nothing saved this session will survive a reboot");
    load();     // still populates the compile-time defaults
    return;
  }

  load();

  Serial.print("[set] wifi ssid ");
  if (s_ssid[0]) Serial.println(s_ssid);
  else           Serial.println("(none - radio stays off)");

  Serial.print("[set] control pin ");
  Serial.println(pinIsDefault() ? "DEFAULT - change it" : "set");
}

const char* wifiSsid()     { return s_ssid; }
const char* wifiPassword() { return s_pass; }
const char* controlPin()   { return s_pin;  }

bool setWifi(const char* ssid, const char* password) {
  if (ssid == nullptr || password == nullptr) return false;
  if (ssid[0] == '\0') return false;                  // use clearWifi()
  if (strlen(ssid)     > SSID_MAX) return false;
  if (strlen(password) > PASS_MAX) return false;

  strncpy(s_ssid, ssid, sizeof(s_ssid) - 1);
  s_ssid[sizeof(s_ssid) - 1] = '\0';
  strncpy(s_pass, password, sizeof(s_pass) - 1);
  s_pass[sizeof(s_pass) - 1] = '\0';

  s_prefs.putString("ssid", s_ssid);
  s_prefs.putString("pass", s_pass);

  // The SSID is logged, the password is not. A serial monitor is often on a
  // screen someone else can see, and the log is the one place credentials
  // would otherwise leak for no reason.
  Serial.print("[set] wifi saved: ");
  Serial.println(s_ssid);
  Serial.println("[set]   reboot for it to take effect");
  return true;
}

void clearWifi() {
  s_ssid[0] = '\0';
  s_pass[0] = '\0';
  s_prefs.remove("ssid");
  s_prefs.remove("pass");
  Serial.println("[set] wifi credentials cleared - radio off after reboot");
}

bool setControlPin(const char* pin) {
  if (pin == nullptr) return false;

  const size_t n = strlen(pin);
  if (n < PIN_MIN || n > PIN_MAX) return false;
  if (!allDigits(pin))            return false;

  strncpy(s_pin, pin, sizeof(s_pin) - 1);
  s_pin[sizeof(s_pin) - 1] = '\0';
  s_prefs.putString("pin", s_pin);

  // Length, never the value. Same reasoning as the password above.
  Serial.print("[set] control pin saved (");
  Serial.print(n);
  Serial.println(" digits)");
  return true;
}

const char* cloudUrl() { return s_cloudUrl; }
const char* cloudKey() { return s_cloudKey; }

bool setCloudUrl(const char* url) {
  if (url == nullptr || strncmp(url, "https://", 8) != 0) return false;   // never plain http
  if (strlen(url) > CLOUD_URL_MAX) return false;
  strncpy(s_cloudUrl, url, sizeof(s_cloudUrl) - 1);
  s_cloudUrl[sizeof(s_cloudUrl) - 1] = '\0';
  s_prefs.putString("curl", s_cloudUrl);
  Serial.println("[set] cloud address saved");
  return true;
}

bool setCloudKey(const char* key) {
  if (key == nullptr) return false;
  const size_t n = strlen(key);
  if (n < CLOUD_KEY_MIN || n > CLOUD_KEY_MAX) return false;
  strncpy(s_cloudKey, key, sizeof(s_cloudKey) - 1);
  s_cloudKey[sizeof(s_cloudKey) - 1] = '\0';
  s_prefs.putString("ckey", s_cloudKey);
  // Length, never the value.
  Serial.print("[set] cloud key saved (");
  Serial.print(n);
  Serial.println(" chars)");
  return true;
}

void clearCloudKey() {
  s_cloudKey[0] = '\0';
  s_prefs.remove("ckey");
  Serial.println("[set] cloud key cleared - upload off");
}

uint8_t measuredLine() { return s_line; }

bool setMeasuredLine(uint8_t line) {
  if (line != 1 && line != 2) return false;
  s_line = line;
  s_prefs.putUChar("line", line);
  Serial.print("[set] measured line ");
  Serial.println(line);
  return true;
}

bool pinIsDefault() { return strcmp(s_pin, DEFAULT_PIN) == 0; }

void factoryReset() {
  s_prefs.clear();
  load();
  Serial.println("[set] factory reset - all stored settings cleared");
}

}  // namespace Settings
