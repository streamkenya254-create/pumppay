#include "buttons.h"
#include "board_pins.h"

namespace Buttons {
namespace {

// ---- Ladder thresholds ----------------------------------------------------
// Millivolts at the pin, each set midway between two adjacent ladder levels.
// Derived from the 10k pull-up and the 1k / 4.7k / 15k / 47k legs; the
// tightest gap is 580 mV, so resistor tolerance has plenty of room.
//
// Measured in millivolts rather than raw counts because analogReadMilliVolts
// applies the per-chip eFuse calibration - the ESP32 ADC is non-linear enough
// that raw counts drift from the resistor maths.
constexpr uint16_t TH_B1 =  678;   // below this = B1  (level 300 mV)
constexpr uint16_t TH_B2 = 1518;   //             B2  (level 1055 mV)
constexpr uint16_t TH_B3 = 2351;   //             B3  (level 1980 mV)
constexpr uint16_t TH_B4 = 3011;   //             B4  (level 2721 mV)
                                   // above      = nothing pressed (3300 mV)

// ---- Timing ---------------------------------------------------------------
constexpr uint32_t SAMPLE_MS     = 5;    // ADC read cadence; loop() is faster
constexpr uint32_t DEBOUNCE_MS   = 25;   // level must hold this long to count
constexpr uint32_t LONG_PRESS_MS = 700;
constexpr uint32_t REPEAT_MS     = 200;  // auto-repeat once long press fires

// ---- Event queue ----------------------------------------------------------
// A ring rather than a single slot: releasing one button while pressing
// another produces a Release and a Press in the same tick, and a single slot
// would silently drop one of them.
struct Slot { Event evt; Button btn; };
constexpr uint8_t QUEUE_LEN = 6;

Slot    s_queue[QUEUE_LEN];
uint8_t s_head = 0;
uint8_t s_tail = 0;

void emit(Event evt, Button btn) {
  const uint8_t next = static_cast<uint8_t>((s_head + 1) % QUEUE_LEN);
  if (next == s_tail) return;          // full - drop rather than overwrite
  s_queue[s_head] = { evt, btn };
  s_head = next;
}

// ---- State ----------------------------------------------------------------
Button   s_stable         = Button::None;   // debounced, what the rest sees
Button   s_candidate      = Button::None;   // what the last sample said
uint32_t s_candidateSince = 0;
uint32_t s_pressedAt      = 0;
uint32_t s_lastSample     = 0;
uint32_t s_lastRepeat     = 0;
bool     s_longFired      = false;
uint16_t s_lastMv         = 0;
bool     s_enabled        = true;

Button decode(uint16_t mv) {
  if (mv < TH_B1) return Button::B1;
  if (mv < TH_B2) return Button::B2;
  if (mv < TH_B3) return Button::B3;
  if (mv < TH_B4) return Button::B4;
  return Button::None;
}

}  // namespace

void begin() {
  pinMode(PIN_BUTTONS, INPUT);
  // Full 0 - 3.3 V span. Without this the pin saturates near 1.1 V and every
  // button above B1 decodes as "nothing pressed".
  analogSetPinAttenuation(PIN_BUTTONS, ADC_11db);

  s_lastMv    = analogReadMilliVolts(PIN_BUTTONS);
  s_stable    = decode(s_lastMv);
  s_candidate = s_stable;

  Serial.print("[btn] idle level ");
  Serial.print(s_lastMv);
  Serial.print(" mV -> ");
  Serial.println(name(s_stable));

  // With nothing pressed the pull-up should hold the pin near 3300 mV. Any
  // other resting level means the ladder is not wired as this module expects,
  // and every reading from here would be fiction - so stand down rather than
  // flood the log with phantom presses.
  if (s_stable != Button::None) {
    s_enabled = false;
    Serial.println("[btn] DISABLED - expected about 3300 mV with nothing pressed.");
    Serial.println("[btn]   GPIO 35 is input-only and has NO internal pull-up, so the");
    Serial.println("[btn]   external 10k to 3.3 V is mandatory. A drifting reading means");
    Serial.println("[btn]   the pin is floating; a steady one means a button is stuck or");
    Serial.println("[btn]   the ladder resistors differ from the thresholds in buttons.cpp.");
  }
}

void tick() {
  if (!s_enabled) return;

  const uint32_t now = millis();

  if (now - s_lastSample >= SAMPLE_MS) {
    s_lastSample = now;
    s_lastMv     = static_cast<uint16_t>(analogReadMilliVolts(PIN_BUTTONS));

    const Button sampled = decode(s_lastMv);

    if (sampled != s_candidate) {
      s_candidate      = sampled;
      s_candidateSince = now;
    } else if (s_candidate != s_stable && now - s_candidateSince >= DEBOUNCE_MS) {
      // A new level has held steady long enough to be real.
      if (s_stable != Button::None) emit(Event::Release, s_stable);

      s_stable = s_candidate;

      if (s_stable != Button::None) {
        s_pressedAt  = now;
        s_lastRepeat = now;
        s_longFired  = false;
        emit(Event::Press, s_stable);
      }
    }
  }

  // Hold timing runs every tick, not just on sample ticks, so long-press and
  // repeat land on time rather than being quantised to SAMPLE_MS.
  if (s_stable == Button::None) return;

  const uint32_t heldFor = now - s_pressedAt;

  if (!s_longFired && heldFor >= LONG_PRESS_MS) {
    s_longFired  = true;
    s_lastRepeat = now;
    emit(Event::LongPress, s_stable);
  } else if (s_longFired && now - s_lastRepeat >= REPEAT_MS) {
    s_lastRepeat = now;
    emit(Event::Repeat, s_stable);
  }
}

bool poll(Event& evt, Button& btn) {
  if (s_tail == s_head) return false;
  evt    = s_queue[s_tail].evt;
  btn    = s_queue[s_tail].btn;
  s_tail = static_cast<uint8_t>((s_tail + 1) % QUEUE_LEN);
  return true;
}

Button held() { return s_stable; }

uint32_t heldMs() {
  return (s_stable == Button::None) ? 0 : (millis() - s_pressedAt);
}

uint16_t millivolts() { return s_lastMv; }

bool enabled() { return s_enabled; }

const char* name(Button btn) {
  switch (btn) {
    case Button::B1: return "B1";
    case Button::B2: return "B2";
    case Button::B3: return "B3";
    case Button::B4: return "B4";
    default:         return "none";
  }
}

const char* name(Event evt) {
  switch (evt) {
    case Event::Press:     return "press";
    case Event::Release:   return "release";
    case Event::LongPress: return "long press";
    case Event::Repeat:    return "repeat";
    default:               return "none";
  }
}

}  // namespace Buttons
