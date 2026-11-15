#include "report.h"
#include "power_meter.h"
#include "pump.h"
#include "ethernet.h"
#include "wifi_net.h"
#include "display.h"
#include "cloud.h"
#include "webui.h"
#include "billing.h"

namespace Report {
namespace {

// LCD rows as a JSON array of strings, escaped. A row could in principle
// contain a quote or a backslash, and one malformed string would break the
// whole body - taking every reading with it, not just this field.
void lcdJson(char* out, size_t n) {
  char* w = out;
  char* const wend = out + n - 4;
  *w++ = '[';
  for (uint8_t r = 0; r < Display::rows() && w < wend; r++) {
    if (r) *w++ = ',';
    *w++ = '"';
    for (const char* p = Display::rowText(r); *p && w < wend; p++) {
      if (*p == '"' || *p == '\\') *w++ = '\\';
      *w++ = *p;
    }
    *w++ = '"';
  }
  *w++ = ']';
  *w   = '\0';
}

}  // namespace

void ethText(char* out, size_t n) {
  out[0] = '\0';
  if (Net::hasAddress()) {
    const IPAddress a = Net::ip();
    snprintf(out, n, "%u.%u.%u.%u", a[0], a[1], a[2], a[3]);
  }
}

void wifiText(char* out, size_t n) {
  out[0] = '\0';
  if (WifiNet::connected()) {
    const IPAddress a = WifiNet::ip();
    snprintf(out, n, "%u.%u.%u.%u", a[0], a[1], a[2], a[3]);
  }
}

size_t statusJson(char* out, size_t n) {
  const PowerMeter::Reading r = PowerMeter::read();

  char eth[20], wifi[20], lcd[160];
  ethText(eth, sizeof(eth));
  wifiText(wifi, sizeof(wifi));
  lcdJson(lcd, sizeof(lcd));

  const int len = snprintf(out, n,
      "{\"state\":\"%s\",\"line\":%d,\"id\":\"%s\",\"web\":%d,"
      "\"pump\":{\"v\":%.2f,\"a\":%.3f,\"va\":%.1f,\"vok\":%d,\"aok\":%d},"
      "\"run\":{\"cur\":%lu,\"today\":%lu,\"total\":%lu,\"starts\":%lu},"
      "\"bill\":{\"on\":%d,\"rem\":%ld,\"lock\":%d,\"off\":%d},"
      "\"t\":%lu,\"tz\":%d,\"temp\":%.1f,\"adc\":%d,\"eth\":\"%s\",\"wifi\":\"%s\","
      "\"up\":%lu,\"lcd\":%s}",
      Pump::name(Pump::state()),
      PowerMeter::line(),
      Cloud::deviceId(),
      WebUI::running() ? 1 : 0,
      r.volts, r.amps, r.va, r.voltsOk ? 1 : 0, r.ampsOk ? 1 : 0,
      static_cast<unsigned long>(Pump::currentRunSecs()),
      static_cast<unsigned long>(Pump::todayRunSecs()),
      static_cast<unsigned long>(Pump::totalRunSecs()),
      static_cast<unsigned long>(Pump::starts()),
      Billing::enforcing() ? 1 : 0, static_cast<long>(Billing::remainingSecs()), Billing::locked() ? 1 : 0, Billing::manualOff() ? 1 : 0,
      static_cast<unsigned long>(Pump::epochNow()),
      Pump::tzMinutes(),
      temperatureRead(),   // ESP32 chip temperature, deg C
      PowerMeter::adcPresent() ? 1 : 0,
      eth, wifi,
      static_cast<unsigned long>(millis() / 1000),
      lcd);

  return (len < 0) ? 0 : (static_cast<size_t>(len) < n ? len : n - 1);
}

size_t histJson(char* out, size_t n) {
  const uint32_t hourNow = Pump::histHourNow();

  size_t w = snprintf(out, n, "{\"hist\":{\"t\":%lu,\"tz\":%d,\"m\":[",
                      static_cast<unsigned long>(hourNow), Pump::tzMinutes());

  if (hourNow != 0) {
    // Oldest first, so index 167 is "this hour" and a client can draw left to
    // right without reversing anything.
    for (int back = Pump::HIST_HOURS - 1; back >= 0 && w + 8 < n; back--) {
      const unsigned mins = (Pump::histSecs(static_cast<uint16_t>(back)) + 30) / 60;
      w += snprintf(out + w, n - w, back == Pump::HIST_HOURS - 1 ? "%u" : ",%u", mins);
    }
  }

  w += snprintf(out + w, n - w, "],\"e\":[");

  if (hourNow != 0) {
    for (int back = Pump::HIST_HOURS - 1; back >= 0 && w + 8 < n; back--) {
      w += snprintf(out + w, n - w, back == Pump::HIST_HOURS - 1 ? "%u" : ",%u",
                    static_cast<unsigned>(Pump::histVah(static_cast<uint16_t>(back))));
    }
  }

  if (w + 4 < n) w += snprintf(out + w, n - w, "]}}");
  return w;
}

}  // namespace Report
