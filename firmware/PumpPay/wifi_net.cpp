#include "wifi_net.h"
#include "settings.h"

namespace WifiNet {
namespace {

// Credentials live in NVS, not here - see settings.h. They can be set over
// Bluetooth, from the web page, or left empty. An empty SSID means the radio
// never starts, so a board that has never been given credentials behaves
// exactly as it did before this module existed.

// How long to let an association attempt run before starting it again. WiFi
// association is neither instant nor reliable; this is a patience setting, not
// a timeout in the error sense.
constexpr uint32_t RETRY_MS = 20000;

bool     s_enabled = false;
bool     s_lastUp  = false;
uint32_t s_lastTry = 0;

}  // namespace

void begin() {
  s_enabled = (Settings::wifiSsid()[0] != '\0');

  if (!s_enabled) {
    Serial.println("[wifi] no SSID stored - radio off");
    Serial.println("[wifi]   set one over Bluetooth or from the web page");
    return;
  }

  // STA only. The ESP32 will happily run an access point at the same time, but
  // an unsecured AP on a board that controls a pump is not something to enable
  // by accident.
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(Settings::wifiSsid(), Settings::wifiPassword());

  s_lastTry = millis();

  Serial.print("[wifi] joining ");
  Serial.println(Settings::wifiSsid());
}

void tick() {
  if (!s_enabled) return;

  const bool up = connected();

  if (up != s_lastUp) {
    s_lastUp = up;
    if (up) {
      Serial.print("[wifi] connected, IP ");
      Serial.println(WiFi.localIP());
    } else {
      Serial.println("[wifi] disconnected");
    }
    s_lastTry = millis();
    return;
  }

  // Nudge a stalled association. setAutoReconnect() covers a link that drops
  // after being up; this covers the case where it never came up at all - a
  // mistyped password, or an AP that was not powered when the board booted.
  if (!up && (millis() - s_lastTry >= RETRY_MS)) {
    s_lastTry = millis();
    Serial.println("[wifi] still not associated - retrying");
    WiFi.disconnect();
    WiFi.begin(Settings::wifiSsid(), Settings::wifiPassword());
  }
}

bool connected() {
  // Both halves matter. WL_CONNECTED can be true for a moment before DHCP has
  // finished, and an address of 0.0.0.0 is no use to anything trying to reach
  // the board.
  return s_enabled && WiFi.status() == WL_CONNECTED &&
         !(WiFi.localIP() == IPAddress(0, 0, 0, 0));
}

IPAddress ip() {
  return s_enabled ? WiFi.localIP() : IPAddress(0, 0, 0, 0);
}

bool enabled() { return s_enabled; }

}  // namespace WifiNet
