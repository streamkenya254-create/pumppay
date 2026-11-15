#include "cloud.h"
#include "settings.h"
#include "power_meter.h"
#include "pump.h"
#include "wifi_net.h"
#include "ethernet.h"
#include "bt.h"
#include "billing.h"

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>

namespace Cloud {
namespace {

constexpr uint32_t FIRST_AFTER_MS = 15000UL;     // soon after the network is up
// Each upload pauses Bluetooth for a few seconds (see bt.h), so they are spaced
// out: often enough for billing, rarely enough that the app is not kept dropping.
constexpr uint32_t RUNNING_MS     = 120000UL;    // pump running
constexpr uint32_t IDLE_MS        = 300000UL;    // pump stopped
constexpr uint32_t RETRY_MS       = 30000UL;     // after a failure
constexpr uint32_t LOCKED_MS      = 45000UL;     // pump locked: watch for the top-up

// Root certificates, embedded in the ESP32 core. Declared by hand because the
// core does not publish a header for them.
extern "C" {
extern const uint8_t rootca_crt_bundle_start[] asm("_binary_x509_crt_bundle_start");
extern const uint8_t rootca_crt_bundle_end[]   asm("_binary_x509_crt_bundle_end");
}

char     s_id[12]  = "";
char     s_body[560];
volatile bool s_ready = false;       // a body is waiting for the network task
volatile bool s_syncNow = false;      // asked to upload at once
bool     s_btPaused = false;         // Bluetooth is off while the upload runs
volatile int  s_status = 0;
volatile uint32_t s_okAt = 0;
volatile bool s_everOk = false;

uint32_t s_nextAt = 0;
bool     s_wasUp  = false;
TaskHandle_t s_task = nullptr;

bool networkUp() { return WifiNet::connected() || Net::hasAddress(); }

void post() {
  // Copy under the assumption nothing rewrites it: tick() will not build a new
  // body while s_ready is set.
  WiFiClientSecure client;
  client.setCACertBundle(rootca_crt_bundle_start, rootca_crt_bundle_end - rootca_crt_bundle_start);
  client.setTimeout(10);

  HTTPClient http;
  http.setConnectTimeout(8000);
  http.setTimeout(10000);

  int code = -1;

  // DNS first, on its own, so a name that will not resolve is reported as that.
  {
    String host = Settings::cloudUrl();
    host.remove(0, 8);                 // "https://"
    const int slash = host.indexOf('/');
    if (slash > 0) host.remove(slash);
    IPAddress ip;
    const bool ok = WiFi.hostByName(host.c_str(), ip);
    Serial.print("[cloud] dns ");
    Serial.print(host);
    Serial.print(" -> ");
    if (ok) Serial.println(ip); else Serial.println("FAILED");
  }

  if (http.begin(client, Settings::cloudUrl())) {
    http.addHeader("Content-Type", "application/json");
    code = http.POST(reinterpret_cast<uint8_t*>(s_body), strlen(s_body));
    if (code == 200) {
      // The answer is tiny and fixed in shape, so it is read with strstr rather
      // than a JSON library: {"ok":true,"creditedSecs":3600,"spentSecs":120}
      const String body = http.getString();
      const char* c = strstr(body.c_str(), "\"creditedSecs\":");
      const char* s = strstr(body.c_str(), "\"spentSecs\":");
      if (c && s) {
        Billing::onServer(static_cast<uint32_t>(atol(c + 15)), static_cast<uint32_t>(atol(s + 12)));
      }
    }
    http.end();
  }

  s_status = code;
  if (code == 200) { s_okAt = millis(); s_everOk = true; }

  Serial.print("[cloud] upload -> ");
  Serial.print(code);

  // On failure, say WHY. -1 alone covers DNS, TCP, TLS and out-of-memory, and
  // each has a different fix. The TLS library keeps its own error text.
  if (code < 0) {
    char tls[100] = "";
    client.lastError(tls, sizeof(tls));
    Serial.print("  tls=\"");
    Serial.print(tls);
    Serial.print("\"  heap=");
    Serial.print(ESP.getFreeHeap());
    Serial.print(" maxblock=");
    Serial.print(ESP.getMaxAllocHeap());
    Serial.print("  rssi=");
    Serial.print(WiFi.RSSI());
  }
  Serial.println();
}

// Runs on the other core, so the TLS handshake never stalls loop().
void uploadTask(void*) {
  for (;;) {
    if (s_ready) {
      post();
      s_ready = false;
    }
    vTaskDelay(pdMS_TO_TICKS(250));
  }
}

}  // namespace

const char* deviceId() {
  if (s_id[0] == '\0') {
    snprintf(s_id, sizeof(s_id), "PP-%06X",
             static_cast<unsigned>((ESP.getEfuseMac() >> 24) & 0xFFFFFF));
  }
  return s_id;
}

void syncNow() { s_syncNow = true; }

bool configured() {
  return Settings::cloudUrl()[0] != '\0' && Settings::cloudKey()[0] != '\0';
}

int  lastStatus() { return s_status; }

uint32_t secondsSinceOk() {
  return s_everOk ? (millis() - s_okAt) / 1000 : 0xFFFFFFFFUL;
}

void begin() {
  deviceId();
  xTaskCreatePinnedToCore(uploadTask, "cloud", 12288, nullptr, 1, &s_task, 0);

  Serial.print("[cloud] device ");
  Serial.print(s_id);
  Serial.println(configured() ? " - upload on when the network is up"
                              : " - no key stored, upload off");
}

void tick() {
  const bool up = networkUp();
  const uint32_t now = millis();

  // An upload is under way with Bluetooth off. When the network task has
  // finished, bring Bluetooth back - from here, on the loop thread.
  if (s_btPaused) {
    if (!s_ready) {
      s_btPaused = false;
      Bluetooth::resume();
    }
    return;
  }

  if (up && !s_wasUp) s_nextAt = now + FIRST_AFTER_MS;
  s_wasUp = up;

  if (s_syncNow) { s_syncNow = false; s_nextAt = now; }

  if (!up || !configured() || s_ready) return;
  if (static_cast<int32_t>(now - s_nextAt) < 0) return;

  // Readings without a clock would be filed under 1970 on the server.
  if (!Pump::timeKnown()) { s_nextAt = now + 5000; return; }

  const PowerMeter::Reading r = PowerMeter::read();

  snprintf(s_body, sizeof(s_body),
           "{\"serial\":\"%s\",\"key\":\"%s\",\"state\":\"%s\",\"line\":%u,"
           "\"v\":%.2f,\"a\":%.3f,\"va\":%.1f,\"vok\":%d,\"aok\":%d,"
           "\"temp\":%.1f,\"today\":%lu,\"total\":%lu,\"starts\":%lu,"
           "\"t\":%lu,\"up\":%lu,\"rssi\":%d,\"spent\":%lu}",
           s_id, Settings::cloudKey(), Pump::name(Pump::state()),
           static_cast<unsigned>(PowerMeter::line()),
           r.volts, r.amps, r.va, r.voltsOk ? 1 : 0, r.ampsOk ? 1 : 0,
           temperatureRead(),
           static_cast<unsigned long>(Pump::todayRunSecs()),
           static_cast<unsigned long>(Pump::totalRunSecs()),
           static_cast<unsigned long>(Pump::starts()),
           static_cast<unsigned long>(Pump::epochNow()),
           static_cast<unsigned long>(millis() / 1000),
           WifiNet::connected() ? WiFi.RSSI() : 0,
           static_cast<unsigned long>(Billing::spentSecs()));

  // Free the RAM the handshake needs, then hand the body to the network task.
  Bluetooth::pause();
  s_btPaused = true;
  s_ready = true;
  // A locked pump is waiting for a top-up, so ask more often. Otherwise a customer
  // who has just paid would sit locked out for up to five minutes.
  const uint32_t every = Billing::locked() ? LOCKED_MS : (Pump::running() ? RUNNING_MS : IDLE_MS);
  // A failed attempt is retried sooner than a normal interval.
  s_nextAt = now + ((s_status != 0 && s_status != 200) ? RETRY_MS : every);
}

}  // namespace Cloud
