#include "bt.h"
#include "power_meter.h"
#include "pump.h"
#include "report.h"
#include "ethernet.h"
#include "wifi_net.h"
#include "settings.h"
#include "display.h"
#include "buttons.h"
#include "cloud.h"
#include "billing.h"

#include <BluetoothSerial.h>

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Bluetooth is not enabled in this build of the ESP32 core."
#endif

namespace Bluetooth {
namespace {

// Set to 0 for a listen-only build - status still streams, every command that
// changes anything is refused. Worth doing if the board sits somewhere a
// stranger could pair with it, since pairing is the only thing standing
// between a phone and the board's settings.
#define BT_ALLOW_CONTROL 1

constexpr char     BT_NAME[]  = "PUMPPAY";
constexpr uint32_t STATUS_MS  = 1000;

// Commands arrive as lines, not single keystrokes, because an SSID or a
// passphrase can contain spaces and neither fits in one character.
//
// Named CMD_LINE_MAX and not LINE_MAX: the latter is a POSIX macro from
// limits.h, so the plain name silently expands to a number and the constant
// becomes a syntax error a long way from anything that looks wrong.
constexpr size_t CMD_LINE_MAX = 96;

BluetoothSerial s_bt;

bool     s_enabled    = false;
bool     s_lastConn   = false;
uint32_t s_lastStatus = 0;

// Machine-readable stream. Off by default so a plain terminal shows human
// text; a client sends "json on" once at connect and gets a stable contract.
bool     s_json      = false;

char   s_line[CMD_LINE_MAX + 1] = { 0 };
size_t s_lineLen = 0;

#if BT_ALLOW_CONTROL
// WiFi is staged then committed, rather than set by one long command. It keeps
// each line short enough to type on a phone, and it means a half-finished
// change never reaches NVS.
char s_stagedSsid[Settings::SSID_MAX + 1] = { 0 };
char s_stagedPass[Settings::PASS_MAX + 1] = { 0 };
bool s_haveSsid = false;
bool s_havePass = false;
#endif

void sendHelp() {
  s_bt.println();
  s_bt.println(F("PUMPPAY"));
  s_bt.println(F("  s          status now"));
  s_bt.println(F("  n          network addresses"));
  s_bt.println(F("  hist       last 7 days of pump run time"));
  s_bt.println(F("  time E Z   set clock: E = unix seconds, Z = zone minutes"));
#if BT_ALLOW_CONTROL
  s_bt.println(F("  ssid NAME  stage a WiFi network"));
  s_bt.println(F("  pass WORD  stage its password"));
  s_bt.println(F("  wifisave   save both, then reboot to join"));
  s_bt.println(F("  wifioff    clear credentials, radio off"));
  s_bt.println(F("  pin NNNN   change the web control PIN"));
  s_bt.println(F("  line 1|2   which measurement line to read"));
  s_bt.println(F("  sync       upload now and fetch the paid balance"));
  s_bt.println(F("  pump on|off      switch the pump off/on from the app (not saved)"));
  s_bt.println(F("  billing    prepaid billing status"));
  s_bt.println(F("  billing on|off   enforce prepaid time (relay)"));
  s_bt.println(F("  cloud      cloud upload status"));
  s_bt.println(F("  cloud URL  set the https address to upload to"));
  s_bt.println(F("  cloudkey K set the upload key (12+ chars)"));
  s_bt.println(F("  cloud off  clear the key, stop uploading"));
  s_bt.println(F("  1 2 3 4    panel keys: back / next / act / home"));
  s_bt.println(F("  json on    machine-readable stream (for apps)"));
  s_bt.println(F("  json off   back to human text"));
#else
  s_bt.println(F("  (control disabled in this build)"));
#endif
  s_bt.println(F("  ?          this help"));
  s_bt.println();
}

// Machine-readable status, one object per line, built by Report so the web
// /api endpoint and this stream cannot drift apart.
//
// This exists because an app should never parse the human text below. That
// text is laid out for someone reading it, so it is free to change whenever a
// label reads better - and every such change would silently break a parser.
// A client sends "json on" once at connect and gets a stable contract.
void sendStatusJson() {
  char body[640];
  Report::statusJson(body, sizeof(body));
  s_bt.println(body);
}

void sendHist() {
  char body[1800];
  Report::histJson(body, sizeof(body));
  s_bt.println(body);
}

void sendStatus() {
  if (s_json) { sendStatusJson(); return; }

  const PowerMeter::Reading r = PowerMeter::read();

  char v[10], a[10];

  // A dash rather than a number when a channel has no valid reading. A zero
  // and a missing reading mean opposite things and must never look alike.
  if (r.voltsOk) dtostrf(r.volts, 0, 1, v); else strcpy(v, "---");
  if (r.ampsOk)  dtostrf(r.amps,  0, 2, a); else strcpy(a, "---");

  char line[120];
  snprintf(line, sizeof(line), "%s | %sV %sA | today %lum | total %.1fh",
           Pump::shortName(), v, a,
           static_cast<unsigned long>(Pump::todayRunSecs() / 60),
           Pump::totalRunSecs() / 3600.0f);
  s_bt.println(line);
}

void sendNetwork() {
  s_bt.print(F("eth  "));
  if (Net::hasAddress()) s_bt.println(Net::ip());
  else                   s_bt.println(F("down"));

  s_bt.print(F("wifi "));
  if (WifiNet::connected())    s_bt.println(WifiNet::ip());
  else if (WifiNet::enabled()) s_bt.println(F("not associated"));
  else                         s_bt.println(F("off - no SSID stored"));

  if (Settings::wifiSsid()[0]) {
    s_bt.print(F("ssid "));
    s_bt.println(Settings::wifiSsid());
  }
}

// Returns the argument after a command word, or nullptr if there is none.
// Everything after the first space is taken verbatim, so an SSID or a
// passphrase containing spaces survives intact.
const char* argOf(const char* line, const char* word) {
  const size_t n = strlen(word);
  if (strncasecmp(line, word, n) != 0) return nullptr;
  if (line[n] != ' ') return nullptr;

  const char* p = line + n + 1;
  while (*p == ' ') p++;
  return (*p == '\0') ? nullptr : p;
}

void handleLine(const char* line) {
  if (line[0] == '\0') return;

  // Read-only, so it works even in a listen-only build. An app needs the
  // stable feed whether or not it is allowed to change anything.
  const char* jarg = argOf(line, "json");
  if (jarg != nullptr) {
    if (strcasecmp(jarg, "on") == 0) {
      s_json = true;
      // Send one frame immediately. A client that waits a whole second to
      // learn whether the command worked will usually be written to retry.
      sendStatusJson();
    } else if (strcasecmp(jarg, "off") == 0) {
      s_json = false;
      s_bt.println(F("human text"));
    } else {
      s_bt.println(F("json on | json off"));
    }
    return;
  }

  // Read-only commands, available in a listen-only build too.
  if (strcasecmp(line, "hist") == 0) { sendHist(); return; }

  // "time EPOCH ZONE" - the phone tells the board what time it is, since the
  // board has no clock of its own. Sent by the app on every connect. Harmless
  // enough to allow without control: the worst a stranger can do is skew the
  // hour-of-day chart, never the run totals.
  if (strncasecmp(line, "time ", 5) == 0) {
    unsigned long epoch = 0;
    int tz = Pump::tzMinutes();   // keeps the stored zone if none is sent
    if (sscanf(line + 5, "%lu %d", &epoch, &tz) >= 1 && epoch > 1700000000UL) {
      Pump::setTime(epoch, tz);
      s_bt.println(F("clock set"));
    } else {
      s_bt.println(F("usage: time UNIXSECONDS ZONEMINUTES"));
    }
    return;
  }

#if BT_ALLOW_CONTROL
  const char* arg;

  if ((arg = argOf(line, "ssid")) != nullptr) {
    if (strlen(arg) > Settings::SSID_MAX) {
      s_bt.println(F("SSID too long (32 max)"));
      return;
    }
    strncpy(s_stagedSsid, arg, sizeof(s_stagedSsid) - 1);
    s_stagedSsid[sizeof(s_stagedSsid) - 1] = '\0';
    s_haveSsid = true;
    s_bt.print(F("staged ssid: "));
    s_bt.println(s_stagedSsid);
    s_bt.println(F("now send: pass YOURPASSWORD"));
    return;
  }

  if ((arg = argOf(line, "pass")) != nullptr) {
    if (strlen(arg) > Settings::PASS_MAX) {
      s_bt.println(F("password too long (63 max)"));
      return;
    }
    strncpy(s_stagedPass, arg, sizeof(s_stagedPass) - 1);
    s_stagedPass[sizeof(s_stagedPass) - 1] = '\0';
    s_havePass = true;
    // Length only, never the value - a terminal app keeps scrollback, and
    // there is no reason for the passphrase to sit in it.
    s_bt.print(F("staged password ("));
    s_bt.print(strlen(s_stagedPass));
    s_bt.println(F(" chars)"));
    s_bt.println(F("now send: wifisave"));
    return;
  }

  if (strcasecmp(line, "wifisave") == 0) {
    if (!s_haveSsid) { s_bt.println(F("no ssid staged - send: ssid NAME")); return; }
    if (!s_havePass) { s_bt.println(F("no password staged - send: pass WORD")); return; }

    if (Settings::setWifi(s_stagedSsid, s_havePass ? s_stagedPass : "")) {
      s_bt.println(F("saved - reboot the board to join"));
      // Wipe the staging copies. There is no reason for the passphrase to sit
      // in RAM after it has been committed.
      memset(s_stagedPass, 0, sizeof(s_stagedPass));
      s_haveSsid = s_havePass = false;
    } else {
      s_bt.println(F("rejected - check lengths"));
    }
    return;
  }

  if (strcasecmp(line, "wifioff") == 0) {
    Settings::clearWifi();
    s_bt.println(F("wifi cleared - radio off after reboot"));
    return;
  }

  if (strcasecmp(line, "sync") == 0) {
    Cloud::syncNow();
    s_bt.println(F("syncing - Bluetooth drops for a few seconds"));
    return;
  }

  if (strcasecmp(line, "pump off") == 0) {
    Billing::setManualOff(true);
    s_bt.println(F("pump OFF - relay on line 2 until 'pump on' or a reboot"));
    return;
  }

  if (strcasecmp(line, "pump on") == 0) {
    Billing::setManualOff(false);
    s_bt.println(F("pump on - control returned to billing"));
    return;
  }

  if (strcasecmp(line, "billing") == 0) {
    s_bt.print(F("billing "));
    s_bt.print(Billing::enforcing() ? F("ON") : F("off"));
    s_bt.print(F("  remaining "));
    s_bt.print(Billing::remainingSecs());
    s_bt.print(F("s  "));
    s_bt.println(Billing::locked() ? F("LOCKED line 2") : F("line 1"));
    return;
  }

  if (strcasecmp(line, "billing on") == 0) {
    Billing::setEnforcing(true);
    s_bt.println(F("billing ON"));
    return;
  }

  if (strcasecmp(line, "billing off") == 0) {
    Billing::setEnforcing(false);
    s_bt.println(F("billing off - pump on line 1"));
    return;
  }

  if (strcasecmp(line, "cloud") == 0) {
    s_bt.print(F("cloud "));
    s_bt.print(Cloud::configured() ? F("on") : F("off - no key"));
    s_bt.print(F("  id "));
    s_bt.print(Cloud::deviceId());
    s_bt.print(F("  last "));
    s_bt.println(Cloud::lastStatus());
    return;
  }

  if (strcasecmp(line, "cloud off") == 0) {
    Settings::clearCloudKey();
    s_bt.println(F("cloud upload off"));
    return;
  }

  if ((arg = argOf(line, "cloudkey")) != nullptr) {
    // Length only is echoed. The key is a password, and a terminal keeps
    // scrollback.
    s_bt.println(Settings::setCloudKey(arg) ? F("cloud key saved") : F("rejected - 12 to 64 characters"));
    return;
  }

  if ((arg = argOf(line, "cloud")) != nullptr) {
    s_bt.println(Settings::setCloudUrl(arg) ? F("cloud address saved") : F("rejected - must start with https://"));
    return;
  }

  if ((arg = argOf(line, "line")) != nullptr) {
    const int n = atoi(arg);
    if (n == 1 || n == 2) {
      Settings::setMeasuredLine(static_cast<uint8_t>(n));
      PowerMeter::setLine(static_cast<uint8_t>(n));
      s_bt.print(F("measuring line "));
      s_bt.println(n);
    } else {
      s_bt.println(F("line 1 | line 2"));
    }
    return;
  }

  if ((arg = argOf(line, "pin")) != nullptr) {
    if (Settings::setControlPin(arg)) {
      s_bt.println(F("PIN changed - use the new one on the web page"));
    } else {
      s_bt.println(F("rejected - 4 to 8 digits, digits only"));
    }
    return;
  }
#endif

  // Single-letter commands, for everything that needs no argument.
  if (line[1] != '\0') {
    s_bt.println(F("unknown - send ? for help"));
    return;
  }

  switch (line[0]) {
    case 's': case 'S': sendStatus();  break;
    case 'n': case 'N': sendNetwork(); break;
    case '?': case 'h': case 'H': sendHelp(); break;
#if BT_ALLOW_CONTROL
    // Virtual panel keys. Injected as the same Press event the physical ladder
    // produces, so the menu cannot tell the difference and there is only ever
    // one place deciding what a button means.
    case '1': Display::onButton(Buttons::Event::Press, Buttons::Button::B1); break;
    case '2': Display::onButton(Buttons::Event::Press, Buttons::Button::B2); break;
    case '3': Display::onButton(Buttons::Event::Press, Buttons::Button::B3); break;
    case '4': Display::onButton(Buttons::Event::Press, Buttons::Button::B4); break;
#endif
    default:
      s_bt.println(F("unknown - send ? for help"));
      break;
  }
}

void readIncoming() {
  while (s_bt.available()) {
    const char c = static_cast<char>(s_bt.read());

    if (c == '\r') continue;

    if (c == '\n') {
      s_line[s_lineLen] = '\0';
      handleLine(s_line);
      s_lineLen = 0;
      continue;
    }

    // An over-long line is truncated rather than wrapped into a second
    // command, so a runaway paste cannot execute half of itself.
    if (s_lineLen < CMD_LINE_MAX) s_line[s_lineLen++] = c;
  }
}

}  // namespace

void begin() {
  s_enabled = s_bt.begin(BT_NAME);

  if (!s_enabled) {
    Serial.println("[bt] failed to start");
    return;
  }

  Serial.print("[bt] discoverable as ");
  Serial.print(BT_NAME);
#if BT_ALLOW_CONTROL
  Serial.println(" - status, control and setup");
#else
  Serial.println(" - status only, control disabled");
#endif
}

void tick() {
  if (!s_enabled) return;

  const bool conn = s_bt.hasClient();

  if (conn != s_lastConn) {
    s_lastConn = conn;
    Serial.println(conn ? "[bt] phone connected" : "[bt] phone disconnected");
    if (conn) {
      s_lineLen = 0;
      // Every connection starts in human text. Otherwise someone opening a
      // terminal after an app had been connected would be met with JSON and
      // no obvious way back.
      s_json = false;
      sendHelp();
      sendStatus();
      s_lastStatus = millis();
    }
    return;
  }

  if (!conn) return;

  readIncoming();


  if (millis() - s_lastStatus >= STATUS_MS) {
    s_lastStatus = millis();
    sendStatus();
  }
}

void pause() {
  if (!s_enabled) return;
  Serial.println("[bt] paused for cloud upload");
  s_bt.end();
  s_enabled  = false;
  s_lastConn = false;
  s_lineLen  = 0;
}

void resume() {
  if (s_enabled) return;
  s_enabled = s_bt.begin(BT_NAME);
  Serial.println(s_enabled ? "[bt] resumed" : "[bt] FAILED to resume");
}

bool connected() { return s_enabled && s_bt.hasClient(); }
bool enabled()   { return s_enabled; }

}  // namespace Bluetooth
