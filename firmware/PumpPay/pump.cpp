#include "pump.h"
#include "power_meter.h"
#include "board_pins.h"
#include "wifi_net.h"

#include <Preferences.h>
#include <sys/time.h>
#include <time.h>

namespace Pump {
namespace {

// ===========================================================================
//  POLICY - the numbers that decide when the pump counts as running
// ===========================================================================
// Sized for a small single-phase solar pump. A pump idles at ~0 A on this
// sensor (a few tenths of an amp of noise at most) and draws well over an amp
// the moment the motor turns. Change them together, and keep the gap.

constexpr float RUN_ON_AMPS  = 1.0f;   // at or above this the pump is running
constexpr float RUN_OFF_AMPS = 0.6f;   // below this it has stopped

// A start has to hold this long before the clock starts. Long enough to ride
// out a switch-on spike, short enough not to lose a real run.
constexpr uint32_t CONFIRM_ON_MS  = 3000;

// A stop has to hold this long. Longer than the start, because a pump that
// dips for a few seconds (cloud over a solar array) and recovers is one run,
// not two - and every extra "start" is one more entry in the lifetime count.
constexpr uint32_t CONFIRM_OFF_MS = 8000;

// Flash is rewritten at most this often, and only when something changed. NVS
// wear-levels, but there is no reason to spend it faster than needed.
constexpr uint32_t SAVE_EVERY_MS = 10UL * 60UL * 1000UL;

// Anything earlier than this is a clock that never got set (1970 + uptime).
constexpr uint32_t MIN_VALID_EPOCH = 1700000000UL;   // Nov 2023

constexpr char NVS_AREA[] = "pumppay-run";

struct Bucket {
  uint32_t hour;   // epoch / 3600 this bucket describes, 0 = empty
  uint16_t secs;   // run seconds in that hour
  uint16_t vah;    // apparent energy drawn in that hour, VAh (volt-amp hours)
};

Preferences s_prefs;
bool        s_prefsOk = false;

State    s_state       = State::NoSignal;
uint32_t s_changeSince = 0;     // when the pending on/off condition began, 0 = none
uint32_t s_runStart    = 0;     // millis() the current run began
uint32_t s_lastTick    = 0;
uint32_t s_msCarry     = 0;     // sub-second remainder while running
float    s_vahCarry    = 0.0f;  // fractional VAh not yet filed

uint32_t s_total   = 0;         // lifetime run seconds
uint32_t s_starts  = 0;
int      s_tz      = 180;       // minutes east of UTC; Nairobi until told otherwise
Bucket   s_hist[HIST_HOURS] = {};

bool     s_dirty     = false;
uint32_t s_lastSave  = 0;
bool     s_ntpAsked  = false;

// ---- Wall clock -----------------------------------------------------------
uint32_t now32() {
  const time_t t = time(nullptr);
  return (t >= static_cast<time_t>(MIN_VALID_EPOCH)) ? static_cast<uint32_t>(t) : 0;
}

// ---- Persistence ----------------------------------------------------------
void save() {
  if (!s_prefsOk) return;
  s_prefs.putUInt("tot", s_total);
  s_prefs.putUInt("starts", s_starts);
  s_prefs.putInt("tz", s_tz);
  s_prefs.putBytes("hist", s_hist, sizeof(s_hist));
  s_dirty    = false;
  s_lastSave = millis();
}

void load() {
  s_total  = s_prefs.getUInt("tot", 0);
  s_starts = s_prefs.getUInt("starts", 0);
  s_tz     = s_prefs.getInt("tz", 180);
  if (s_prefs.getBytesLength("hist") == sizeof(s_hist)) {
    s_prefs.getBytes("hist", s_hist, sizeof(s_hist));
  }
}

// ---- Accounting -----------------------------------------------------------
// Adds whole seconds of run time to the lifetime total and, if the clock is
// known, to the bucket for the current hour.
void addRunSeconds(uint32_t secs) {
  if (secs == 0) return;

  s_total += secs;
  s_dirty  = true;

  const uint32_t t = now32();
  if (t == 0) return;

  uint32_t hour = t / 3600;
  uint32_t left = secs;

  // A tick can straddle an hour boundary; split rather than misfile it.
  while (left > 0) {
    Bucket& b = s_hist[hour % HIST_HOURS];
    if (b.hour != hour) { b.hour = hour; b.secs = 0; b.vah = 0; }

    const uint32_t room = 3600 - b.secs;
    const uint32_t put  = (left < room) ? left : room;
    b.secs = static_cast<uint16_t>(b.secs + put);
    left  -= put;
    if (left > 0) hour++;      // overflowed this hour - carry to the next
  }
}

// Files whole VAh against the current clock hour. Without a clock there is no
// hour to file them under, so they are dropped rather than parked somewhere
// wrong - the carry is cleared so it cannot build up and land in the first hour
// the clock is set.
void addEnergy(uint32_t vah) {
  const uint32_t t = now32();
  if (t == 0 || vah == 0) return;

  const uint32_t hour = t / 3600;
  Bucket& b = s_hist[hour % HIST_HOURS];
  if (b.hour != hour) { b.hour = hour; b.secs = 0; b.vah = 0; }

  const uint32_t sum = static_cast<uint32_t>(b.vah) + vah;
  b.vah   = static_cast<uint16_t>(sum > 65535 ? 65535 : sum);
  s_dirty = true;
}

void setState(State next) {
  if (next == s_state) return;

  Serial.print("[pump] ");
  Serial.print(name(s_state));
  Serial.print(" -> ");
  Serial.println(name(next));

  if (next == State::Running) {
    s_runStart = millis();
    s_msCarry  = 0;
    s_starts++;
    s_dirty = true;
  }
  // Leaving Running: flush promptly, so a stop is on flash even if power then
  // goes. Rate-limited so a flapping pump cannot hammer it.
  if (s_state == State::Running && millis() - s_lastSave > 60000UL) save();

  s_state = next;
}

}  // namespace

void begin() {
  pinMode(PIN_LED_PUMP, OUTPUT);
  pinMode(PIN_LED_SPARE, OUTPUT);
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_LED_PUMP, LOW);
  digitalWrite(PIN_LED_SPARE, LOW);
  digitalWrite(PIN_RELAY, LOW);   // coil off, always - PumpPay switches nothing

  s_prefsOk = s_prefs.begin(NVS_AREA, false);
  if (s_prefsOk) {
    load();
  } else {
    Serial.println("[pump] NVS would not open - counters will not survive a reboot");
  }

  // Timezone for the board's own clock functions; the offset itself is applied
  // by hand below so nothing depends on a TZ database being present.
  setenv("TZ", "UTC0", 1);
  tzset();

  s_lastTick = millis();
  s_lastSave = millis();

  Serial.print("[pump] runs when current >= ");
  Serial.print(RUN_ON_AMPS, 1);
  Serial.print(" A (held ");
  Serial.print(CONFIRM_ON_MS / 1000);
  Serial.print(" s), stops below ");
  Serial.print(RUN_OFF_AMPS, 1);
  Serial.print(" A (held ");
  Serial.print(CONFIRM_OFF_MS / 1000);
  Serial.println(" s)");
  Serial.print("[pump] lifetime ");
  Serial.print(s_total / 3600.0f, 1);
  Serial.print(" h, ");
  Serial.print(s_starts);
  Serial.println(" starts");
}

void tick() {
  const uint32_t now = millis();
  const uint32_t dt  = now - s_lastTick;
  s_lastTick = now;

  // No clock yet and WiFi is up: ask the network once. Harmless if it fails -
  // the phone app can still set the time over Bluetooth.
  if (!s_ntpAsked && WifiNet::connected()) {
    s_ntpAsked = true;
    configTime(0, 0, "pool.ntp.org", "time.google.com");
  }

  const PowerMeter::Reading r = PowerMeter::read();

  // No current measurement: hold position and stop the clock. See the header.
  if (!r.ampsOk) {
    s_changeSince = 0;
    setState(State::NoSignal);
  } else {
    const bool want = (s_state == State::Running) ? (r.amps >= RUN_OFF_AMPS)
                                                  : (r.amps >= RUN_ON_AMPS);

    if (s_state == State::NoSignal) {
      // First valid reading: no run is in progress that we know of, so the
      // pump is simply idle or - if it is already drawing - starts now.
      s_state = State::Idle;
    }

    const bool running = (s_state == State::Running);
    if (want == running) {
      s_changeSince = 0;
    } else {
      if (s_changeSince == 0) s_changeSince = now;
      const uint32_t need = want ? CONFIRM_ON_MS : CONFIRM_OFF_MS;
      if (now - s_changeSince >= need) {
        s_changeSince = 0;
        setState(want ? State::Running : State::Idle);
      }
    }
  }

  // Run clock: whole seconds only, with the remainder carried so a long run
  // does not drift by the rounding of each tick.
  if (s_state == State::Running) {
    s_msCarry += dt;
    if (s_msCarry >= 1000) {
      addRunSeconds(s_msCarry / 1000);
      s_msCarry %= 1000;
    }
  }

  // Energy: apparent power times time, only while running and only with a valid
  // reading. A missing reading adds nothing rather than a guess.
  if (s_state == State::Running && r.voltsOk && r.ampsOk) {
    s_vahCarry += r.va * (static_cast<float>(dt) / 3600000.0f);
    if (s_vahCarry >= 1.0f) {
      const uint32_t whole = static_cast<uint32_t>(s_vahCarry);
      s_vahCarry -= whole;
      addEnergy(whole);
    }
  } else if (s_state != State::Running) {
    s_vahCarry = 0.0f;
  }

  digitalWrite(PIN_LED_PUMP, s_state == State::Running ? HIGH : LOW);

  if (s_dirty && now - s_lastSave >= SAVE_EVERY_MS) save();
}

State state() { return s_state; }
bool  running() { return s_state == State::Running; }

const char* name(State s) {
  switch (s) {
    case State::Running: return "PUMPING";
    case State::Idle:    return "IDLE";
    default:             return "NO SIGNAL";
  }
}

const char* shortName() { return name(s_state); }

uint32_t currentRunSecs() {
  if (s_state != State::Running) return 0;
  return (millis() - s_runStart) / 1000;
}

uint32_t totalRunSecs() { return s_total; }
uint32_t starts()       { return s_starts; }

void setTime(uint32_t epoch, int tzMin) {
  if (epoch < MIN_VALID_EPOCH) return;
  if (tzMin < -840 || tzMin > 840) tzMin = s_tz;

  struct timeval tv = { static_cast<time_t>(epoch), 0 };
  settimeofday(&tv, nullptr);

  if (tzMin != s_tz) { s_tz = tzMin; s_dirty = true; }
}

bool     timeKnown() { return now32() != 0; }
uint32_t epochNow()  { return now32(); }
int      tzMinutes() { return s_tz; }

uint32_t histHourNow() {
  const uint32_t t = now32();
  return t ? t / 3600 : 0;
}

uint16_t histSecs(uint16_t back) {
  const uint32_t nowHour = histHourNow();
  if (nowHour == 0 || back >= HIST_HOURS || back > nowHour) return 0;

  const uint32_t hour = nowHour - back;
  const Bucket&  b    = s_hist[hour % HIST_HOURS];
  return (b.hour == hour) ? b.secs : 0;
}

uint16_t histVah(uint16_t back) {
  const uint32_t nowHour = histHourNow();
  if (nowHour == 0 || back >= HIST_HOURS || back > nowHour) return 0;

  const uint32_t hour = nowHour - back;
  const Bucket&  b    = s_hist[hour % HIST_HOURS];
  return (b.hour == hour) ? b.vah : 0;
}

uint32_t todayRunSecs() {
  const uint32_t t = now32();
  if (t == 0) return 0;

  // Local midnight, worked out by hand from the stored offset. Buckets are
  // UTC hours, so a zone that is not a whole number of hours from UTC would
  // put the boundary mid-bucket; whole hours are what Kenya and most of the
  // world use, and a half-hour zone is off by at most one partial bucket.
  const int32_t  local     = static_cast<int32_t>(t) + s_tz * 60;
  const uint32_t secsToday = static_cast<uint32_t>(local % 86400 + 86400) % 86400;
  const uint32_t hoursBack = secsToday / 3600;

  uint32_t sum = 0;
  for (uint16_t back = 0; back <= hoursBack && back < HIST_HOURS; back++) sum += histSecs(back);

  return sum;
}

void resetCounters() {
  s_total  = 0;
  s_starts = 0;
  memset(s_hist, 0, sizeof(s_hist));
  s_dirty = true;
  save();
  Serial.println("[pump] counters reset");
}

}  // namespace Pump
