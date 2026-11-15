#include "billing.h"
#include "pump.h"
#include "board_pins.h"

#include <Preferences.h>

namespace Billing {
namespace {

constexpr char NVS_AREA[] = "pumppay-bill";

// Shortest time the relay stays where it is. A balance sitting right at zero
// must not make it chatter, and a relay coil is rated for far fewer operations
// than a loop() runs.
constexpr uint32_t MIN_DWELL_MS = 5000;

// How often the running total is written to flash while it is changing. Losing
// a few seconds of count in a power cut favours the customer, never the board.
constexpr uint32_t SAVE_EVERY_MS = 30000;

Preferences s_prefs;
bool        s_prefsOk = false;

bool     s_enforce  = false;
bool     s_known    = false;
uint32_t s_credited = 0;       // from the server, last time we heard
uint32_t s_spent    = 0;       // ours, only counts while enforcing
uint32_t s_lastTotal = 0;      // Pump::totalRunSecs() at the last tick

bool     s_locked    = false;  // relay energized = line 2
bool     s_manualOff = false;  // switched off from the app; never saved
uint32_t s_changedAt = 0;
uint32_t s_lastSave  = 0;
bool     s_dirty     = false;

// Written by the cloud task, applied by tick() on the loop thread, so the
// spent/credited pair is only ever changed from one place.
volatile bool     s_pending       = false;
volatile uint32_t s_pendCredited  = 0;
volatile uint32_t s_pendSpent     = 0;

void save() {
  if (!s_prefsOk) return;
  s_prefs.putBool("enf", s_enforce);
  s_prefs.putBool("known", s_known);
  s_prefs.putUInt("cred", s_credited);
  s_prefs.putUInt("spent", s_spent);
  s_dirty    = false;
  s_lastSave = millis();
}

// The one place the relay pin is written. Energized (HIGH) is line 2.
void drive(bool line2) {
  digitalWrite(PIN_RELAY, line2 ? HIGH : LOW);
  digitalWrite(PIN_LED_SPARE, line2 ? HIGH : LOW);   // lit while the pump is locked out
  s_locked    = line2;
  s_changedAt = millis();

  Serial.print("[bill] relay -> line ");
  Serial.print(line2 ? 2 : 1);
  Serial.print(line2 ? "  (token used up, pump locked)" : "  (pump fed)");
  Serial.print("  remaining ");
  Serial.println(remainingSecs());
}

}  // namespace

void begin() {
  s_prefsOk = s_prefs.begin(NVS_AREA, false);
  if (s_prefsOk) {
    s_enforce  = s_prefs.getBool("enf", false);
    s_known    = s_prefs.getBool("known", false);
    s_credited = s_prefs.getUInt("cred", 0);
    s_spent    = s_prefs.getUInt("spent", 0);
  }

  s_lastTotal = Pump::totalRunSecs();
  s_lastSave  = millis();

  // pin was already set low by Pump::begin(); this makes the ownership explicit.
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, LOW);

  Serial.print("[bill] enforcement ");
  Serial.print(s_enforce ? "ON" : "off");
  Serial.print(", balance ");
  if (s_known) {
    Serial.print(static_cast<long>(s_credited) - static_cast<long>(s_spent));
    Serial.println(" s");
  } else {
    Serial.println("not known yet");
  }
}

bool enforcing() { return s_enforce; }
bool known()     { return s_known; }
bool locked()    { return s_locked; }
uint32_t spentSecs() { return s_spent; }

int32_t remainingSecs() {
  if (!s_enforce || !s_known) return -1;
  const int64_t left = static_cast<int64_t>(s_credited) - static_cast<int64_t>(s_spent);
  return left > 0 ? static_cast<int32_t>(left > 2000000000 ? 2000000000 : left) : 0;
}

void setManualOff(bool off) {
  s_manualOff = off;
  Serial.println(off ? "[bill] manual OFF (pump cut off)" : "[bill] manual off released");
}

bool manualOff() { return s_manualOff; }

void setEnforcing(bool on) {
  if (on == s_enforce) return;
  s_enforce = on;
  s_lastTotal = Pump::totalRunSecs();     // do not charge for time already run
  s_dirty = true;
  save();

  Serial.print("[bill] enforcement ");
  Serial.println(on ? "ON" : "off");

  // Turning it off must never leave the pump locked out.
  if (!on && s_locked) drive(false);
}

void onServer(uint32_t creditedSecs, uint32_t serverSpentSecs) {
  s_pendCredited = creditedSecs;
  s_pendSpent    = serverSpentSecs;
  s_pending      = true;
}

void tick() {
  const uint32_t now = millis();

  // ---- take in what the server said -------------------------------------
  if (s_pending) {
    s_pending = false;
    s_credited = s_pendCredited;
    // The server's spent is a floor: if the board's own count was lost it is
    // restored here, and if the board is ahead (not yet uploaded) it is kept.
    if (s_pendSpent > s_spent) s_spent = s_pendSpent;
    s_known = true;
    s_dirty = true;
  }

  // ---- spend time while the pump runs and billing is on -----------------
  const uint32_t total = Pump::totalRunSecs();
  if (total > s_lastTotal) {
    if (s_enforce) { s_spent += total - s_lastTotal; s_dirty = true; }
    s_lastTotal = total;
  } else if (total < s_lastTotal) {
    s_lastTotal = total;                  // counters were reset; never go negative
  }

  // ---- decide the relay --------------------------------------------------
  // Lock only with billing on AND a balance actually known AND nothing left.
  const bool wantLock = s_manualOff ||
                        (s_enforce && s_known &&
                         (static_cast<int64_t>(s_credited) - static_cast<int64_t>(s_spent) <= 0));

  if (wantLock != s_locked && now - s_changedAt >= MIN_DWELL_MS) {
    drive(wantLock);
    s_dirty = true;
  }

  if (s_dirty && now - s_lastSave >= SAVE_EVERY_MS) save();
}

}  // namespace Billing
