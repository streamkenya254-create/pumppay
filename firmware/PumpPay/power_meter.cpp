#include "power_meter.h"
#include "board_pins.h"
#include "settings.h"
#include <Wire.h>
#include <math.h>

namespace PowerMeter {
namespace {

// ===========================================================================
//  CALIBRATION - nothing below reads correctly until these match the board
// ===========================================================================

// Is the sensed quantity AC (true RMS wanted) or DC (plain average)?
//
// CURRENT is AC on this system - measured, not assumed. The line runs through
// a ZMPT101B voltage transformer, so it carries mains-frequency AC, and a
// meter reading 6.58 A RMS showed up as 0.02 A on the board.
//
// The sense node (TP11 / net A3E2) is fed by U17, an ACS758xCB-050U-PFF on a
// single +5 V supply. It rests at 0.12 x VCC = 0.60 V and moves either way
// from there, so on AC it swings symmetrically about its own pedestal - and a
// plain average of that IS the pedestal, whatever the current. That is why
// the board read zero. R100/R101 divide the node into the 3.3 V ADS range and
// C63 low-passes it at 29 Hz, which halves what is left at 50 Hz but does not
// erase it: the waveform does reach the ADS pin, so RMS can recover it.
//
// Two hardware limits survive this firmware fix, and both cap the result:
//   - the -050U is UNIDIRECTIONAL. It has only 0.60 V of room below the
//     pedestal, so it stops responding somewhere near -10 A and the negative
//     half of the waveform clips there. Past about 7 A RMS (9.9 A peak) the
//     reading goes progressively low. A bidirectional -050B, which rests at
//     VCC/2, has no such limit.
//   - C63 attenuates what does get through. See CURRENT_AC_FILTER_COMP below.
//
// VOLTAGE is AC - confirmed from the schematic. The line goes through a
// ZMPT101B voltage transformer, then a U19 (LM358N) gain/bias stage on +5V2,
// then a divider to the ESP32 pin, with TP1/TP4 brought out on the way.
constexpr bool VOLTAGE_IS_AC = true;
constexpr bool CURRENT_IS_AC = true;

// Volts of real line for every volt of RMS seen at the ESP32 pin.
//
// NO OFFSET CONSTANT IS NEEDED here. The ZMPT101B signal rides on a bias set
// by the LM358 stage, and the AC branch of Accumulator::addSample() tracks that
// bias out on its own before squaring. Whatever the bias happens to be, it
// cancels - so this single number is the only voltage calibration there is.
//
// It absorbs the whole chain at once: transformer turns ratio, LM358 gain,
// and the output divider. That means it CANNOT be derived from the schematic
// piecemeal - it has to be measured:
//
//   1. apply a known AC voltage to the line
//   2. read the V= figure the board reports
//   3. set the constant to  known_voltage / reported_voltage
//   4. re-flash and confirm the reading now matches
//
// Measured 2026-09-10. RV2 was trimmed until the pin read 0.32 V RMS with
// 245 V AC on the line:
//
//     245 / 0.32 = 765.6
//
// 0.32 V RMS is the design target for this chain, not an arbitrary landing
// point. The pin idles at 1.67 V, fixed by R124/R125, and the LM358 on a 5 V
// rail can only reach about 3.5 V - which after the divider leaves roughly
// 0.67 V of room ABOVE the bias and 1.5 V below. The op-amp, not the ADC, is
// the ceiling. A 0.32 V RMS trim puts the peaks at 1.22 - 2.12 V, clear of
// both ends, and clips only past about +47% line - around 360 V.
//
// Trimming hotter would NOT buy accuracy. The constant below is a pure scalar,
// so any trim in roughly 0.25 - 0.45 V RMS calibrates equally well; what the
// trim actually sets is headroom and noise margin. A clipped waveform still
// yields a plausible-looking RMS, just a low one - which is the failure this
// setting exists to avoid.
constexpr float PUMP_VOLTS_PER_PIN_VOLT = 765.6f;

// Current sense chain, from the U17 schematic (net A3E2 / TP11):
//
//   ACS758xCB-050U-PFF on +5 V:  60 mV/A, zero-current output 0.12 x VCC
//   R100 3.3k / R101 10k divider: gain 10 / 13.3 = 0.75188
//
// R100 and C63 are Allegro's recommended RF/CF output filter, with R100 doing
// double duty as the top of the divider that brings the 5 V sensor into the
// 3.3 V ADS range. CF is left to the designer; this board chose 2.2 uF, which
// puts the corner at 29 Hz - heavy averaging, and the clearest signal that
// this node was designed to carry a DC level rather than a waveform.
//
//     sensitivity at TP11  = 60 mV/A * 0.75188 = 45.11 mV/A  -> 22.17 A/V
//     zero-current at TP11 = 0.600 V * 0.75188 = 0.4511 V
//     50 A at TP11         = 2.707 V           (inside the 3.3 V rail)
constexpr float PUMP_AMPS_PER_ADC_VOLT = 22.17f;

// Measured calibration trim, applied on top of the schematic-derived scale
// above. Bench measurements land HERE, so the constants above can stay as the
// schematic describes them and the two never get confused.
//
// Measured 2026-09-09. A meter reading 6.8 A RMS came back as 6.276 A:
//     6.8 / 6.276 = 1.083
// The sensor was NOT clipping when this was taken, which is what makes a
// single flat factor the right correction: iraw ran 0.252..0.668 about a
// 0.454 pedestal (symmetric within 5%), and ipp/adc was 2.92 - a clipped
// waveform drags that ratio DOWN toward 2.0, not up. So the shortfall is gain
// across the whole range, not a range limit at the top of it.
//
// What this is absorbing, most likely: C63 is not exactly 2.2 uF. A real value
// near 2.44 uF puts the corner at 26.3 Hz and CURRENT_AC_FILTER_COMP at 2.15
// instead of 1.985, which closes the gap on its own. Resistor tolerance and
// the sensor's actual mV/A land here too. They all multiply and cannot be
// separated in circuit, so one number carries them all. If the cause is the
// modelled capacitor then C63 -> ~100 nF removes the need for it entirely.
constexpr float PUMP_AMPS_CAL = 1.083f;   // measured

// Sensor output with zero current flowing, at the ADS pin. Used only when
// CURRENT_IS_AC is false: a unidirectional Hall sensor reads ~0.45 V at 0 A,
// not 0 V, and that pedestal must be subtracted before scaling.
//
// DO NOT TRUST THIS NUMBER - MEASURE IT. Sources disagree on the quiescent
// spec (0.12 x VCC vs 0.1 x VCC), it is ratiometric so it tracks the actual
// 5 V rail, and it varies part to part. Call zeroCurrent() with the pump off
// and the measurement replaces it.
constexpr float PUMP_ADC_ZERO_VOLTS = 0.4511f;

// ---- C63 filter compensation - AC current only ----------------------------
//
// R100 || R101 = 3.3k || 10k = 2.481k feeding C63 = 2.2 uF. That is a
// low-pass with its corner at 29.2 Hz - BELOW the line frequency, because the
// node was designed to carry a DC level, not a waveform. A 50 Hz signal
// therefore arrives at the ADS pin already halved.
//
// A single-pole low-pass has gain 1 / sqrt(1 + (f/fc)^2) at f, so the
// correction is the reciprocal, sqrt(1 + (f/fc)^2):
//
//     50 Hz -> 1.985          60 Hz -> 2.288
//
// THIS IS AN APPROXIMATION, and the limits matter more than the number:
//   - it assumes a clean sine at LINE_FREQUENCY_HZ. Every harmonic sits at a
//     different point on the filter curve and is corrected by the wrong
//     factor, so a distorted current reads wrong no matter how good the maths.
//     A pump driven by a variable-speed drive is NOT a clean sine.
//   - it assumes nominal parts. C63 at 20% tolerance moves the corner 20% and
//     this factor by about 8%, and nothing here can detect that.
//   - the filter also shifts phase, which this does not touch. It does not
//     matter for RMS, but it rules out real power (VA stays apparent power).
constexpr float LINE_FREQUENCY_HZ   = 50.0f;   // set to 60.0f on a 60 Hz line
constexpr float SENSE_FILTER_R_OHMS = 2481.0f; // R100 || R101
constexpr float SENSE_FILTER_C_F    = 2.2e-6f; // C63

const float SENSE_FILTER_CORNER_HZ =
    1.0f / (2.0f * PI * SENSE_FILTER_R_OHMS * SENSE_FILTER_C_F);

const float CURRENT_AC_FILTER_COMP =
    sqrtf(1.0f + (LINE_FREQUENCY_HZ / SENSE_FILTER_CORNER_HZ) *
                 (LINE_FREQUENCY_HZ / SENSE_FILTER_CORNER_HZ));

// ===========================================================================
//  External ADC - schematic U16, ADS1015IDGS
// ===========================================================================

// Confirmed from the schematic: U16 is an ADS1015IDGS, the 12-bit part.
// Set this to 1 only if it is ever swapped for a pin-compatible ADS1115
// (16-bit); it changes the data rate and the count scale, nothing else.
#define ADS_IS_ADS1115 0

constexpr uint8_t REG_CONVERSION = 0x00;
constexpr uint8_t REG_CONFIG     = 0x01;

constexpr uint16_t CFG_OS_SINGLE   = 0x8000;  // start a single conversion
constexpr uint16_t CFG_MODE_SINGLE = 0x0100;  // single-shot, power down between
constexpr uint16_t CFG_COMP_OFF    = 0x0003;  // comparator disabled

// PGA. U16 is powered from +3.3V2, so whatever the PGA is set to, the AIN
// pins must stay inside -0.3 V .. VDD + 0.3 V (3.6 V) - the FSR setting does
// not extend the absolute maximum rating.
//
// +/-4.096 V is required by the current node: TP11 runs 0.45 V at zero current
// to 2.71 V at 50 A. +/-2.048 V would double the resolution but clip above
// about 35 A, so take it only if full scale is never approached:
//     CFG_PGA = 0x0400 and ADS_FULL_SCALE_VOLTS = 2.048f
constexpr uint16_t CFG_PGA              = 0x0200;   // +/-4.096 V
constexpr float    ADS_FULL_SCALE_VOLTS = 4.096f;

#if ADS_IS_ADS1115
constexpr uint16_t CFG_DATA_RATE   = 0x00E0;  // 860 SPS
constexpr uint32_t CONVERSION_US   = 1400;    // 1/860 s plus margin
constexpr float    ADS_FULL_COUNTS = 32768.0f;
#else
constexpr uint16_t CFG_DATA_RATE   = 0x00C0;  // 3300 SPS
constexpr uint32_t CONVERSION_US   = 450;     // 1/3300 s plus margin
constexpr float    ADS_FULL_COUNTS = 2048.0f; // 12-bit, right-aligned by us
#endif

// U16 pin 2 (ALERT/RDY) is left unconnected on this board, so there is no
// conversion-ready interrupt to wait on. Elapsed-time polling below is forced
// by the hardware, not a shortcut.

// I2C bus speed. The ADS1015 handles 400 kHz, but that needs real pull-up
// resistors on SDA/SCL - the ESP32 internal pull-ups are far too weak. If the
// board has none fitted, drop this to 100000 and expect marginal timing.
constexpr uint32_t I2C_CLOCK_HZ = 400000;

// Single-ended mux settings: AIN0..AIN3 vs GND are 0x4000..0x7000.
inline uint16_t muxForChannel(uint8_t ch) {
  return static_cast<uint16_t>(0x4000 + (static_cast<uint16_t>(ch & 0x03) << 12));
}

bool writeRegister(uint8_t reg, uint16_t value) {
  Wire.beginTransmission(ADS_I2C_ADDRESS);
  Wire.write(reg);
  Wire.write(static_cast<uint8_t>(value >> 8));
  Wire.write(static_cast<uint8_t>(value & 0xFF));
  return Wire.endTransmission() == 0;
}

bool readRegister(uint8_t reg, uint16_t& out) {
  Wire.beginTransmission(ADS_I2C_ADDRESS);
  Wire.write(reg);
  if (Wire.endTransmission() != 0) return false;
  // Use the (int, int) overload explicitly. Passing two uint8_t is ambiguous
  // between requestFrom(uint16_t, uint8_t) and requestFrom(int, int) on the
  // ESP32 Wire class - neither is a better match, so it fails to compile.
  if (Wire.requestFrom(static_cast<int>(ADS_I2C_ADDRESS), 2) != 2) return false;

  const uint8_t hi = Wire.read();
  const uint8_t lo = Wire.read();
  out = (static_cast<uint16_t>(hi) << 8) | lo;
  return true;
}

bool startConversion(uint8_t channel) {
  const uint16_t cfg = CFG_OS_SINGLE | muxForChannel(channel) | CFG_PGA |
                       CFG_MODE_SINGLE | CFG_DATA_RATE | CFG_COMP_OFF;
  return writeRegister(REG_CONFIG, cfg);
}

// Returns the finished conversion in volts at the ADS input pin.
bool readConversionVolts(float& volts) {
  uint16_t raw = 0;
  if (!readRegister(REG_CONVERSION, raw)) return false;

  int16_t counts = static_cast<int16_t>(raw);
#if !ADS_IS_ADS1115
  counts >>= 4;   // ADS101x returns 12 bits left-aligned in the 16-bit word
#endif

  volts = (static_cast<float>(counts) / ADS_FULL_COUNTS) * ADS_FULL_SCALE_VOLTS;
  return true;
}

// ===========================================================================
//  Windowed accumulator
// ===========================================================================

// Measurement window. 200 ms is 10 whole cycles at 50 Hz and 12 at 60 Hz, so
// the RMS result does not beat against the line frequency.
constexpr uint32_t WINDOW_MS = 200;

// High-pass constant that tracks the DC bias out of an AC signal. Slow enough
// not to eat the fundamental, fast enough to settle in about a second.
constexpr float OFFSET_ALPHA = 1.0f / 1024.0f;

struct Accumulator {
  bool     ac     = true;
  float    offset = 0.0f;
  bool     primed = false;
  double   acc    = 0.0;    // sum of squares (AC) or sum of samples (DC)
  uint32_t n      = 0;
  float    result = 0.0f;   // last closed window, in volts at the sense point
  bool     valid  = false;
  uint32_t lastCount = 0;   // samples in that window - RMS needs ~20+ per cycle

  // Raw min and max within the window, before any bias removal. This is the
  // diagnostic that answers "is the pin actually moving?" - a flat signal and
  // a healthy one look identical on a dc meter, but not here.
  float vmin = 0.0f;
  float vmax = 0.0f;
  float lastMin = 0.0f;
  float lastMax = 0.0f;

  void addSample(float v) {
    if (n == 0) { vmin = v; vmax = v; }
    else { if (v < vmin) vmin = v; if (v > vmax) vmax = v; }

    if (ac) {
      if (!primed) { offset = v; primed = true; }
      offset += (v - offset) * OFFSET_ALPHA;
      const float centred = v - offset;
      acc += static_cast<double>(centred) * centred;
    } else {
      acc += v;
    }
    n++;
  }

  void closeWindow() {
    if (n == 0) return;
    const double mean = acc / static_cast<double>(n);
    result    = ac ? sqrtf(static_cast<float>(mean)) : static_cast<float>(mean);
    valid     = true;
    lastCount = n;        // exposed so RMS sample density can be checked
    lastMin   = vmin;
    lastMax   = vmax;
    acc       = 0.0;
    n         = 0;
  }

  void invalidate() { valid = false; acc = 0.0; n = 0; }

  // Forget everything, including the tracked bias: a different line has a
  // different one, and carrying the old value over would skew the first window.
  void reset() { invalidate(); primed = false; offset = 0.0f; }
};

Accumulator s_volts;
Accumulator s_amps;

// The line being read. Both lines use the same calibration constants - the two
// chains were trimmed to the same target - so selecting a line changes only
// which voltage pin and which ADS channel are sampled.
uint8_t s_line     = 1;
int     s_voltPin  = PIN_PUMP_VOLTAGE;
uint8_t s_adsChan  = ADS_CH_PUMP_CURRENT;

uint32_t s_windowStarted = 0;
uint32_t s_lastClosed    = 0;

bool     s_adsPresent  = false;
bool     s_converting  = false;
uint32_t s_convStarted = 0;
uint32_t s_adsRetryAt  = 0;

// How often to re-probe the ADS after it has dropped off the bus.
constexpr uint32_t ADS_RETRY_MS = 5000;

// Live zero-current pedestal, seeded from the constant and replaced by
// zeroCurrent(). Volts at the ADS pin. Unused while CURRENT_IS_AC.
float s_ampsZero = PUMP_ADC_ZERO_VOLTS;

constexpr float AMPS_SCALE = PUMP_AMPS_PER_ADC_VOLT * PUMP_AMPS_CAL;

void sampleVoltage() {
  // analogReadMilliVolts applies the per-chip eFuse calibration, which a raw
  // analogRead() count does not - the ESP32 ADC is markedly non-linear.
  const float pinVolts = analogReadMilliVolts(s_voltPin) / 1000.0f;
  s_volts.addSample(pinVolts);
}

void dropAdc() {
  s_adsPresent = false;
  s_converting = false;
  s_amps.invalidate();
}

void sampleCurrent() {
  // A single I2C hiccup used to kill current readings until the next reboot:
  // dropAdc() cleared the present flag and nothing ever set it back. Retry on
  // a timer instead, so a loose wire recovers on its own once refitted.
  if (!s_adsPresent) {
    if (millis() - s_adsRetryAt < ADS_RETRY_MS) return;
    s_adsRetryAt = millis();
    probeAdc();
    return;
  }

  if (!s_converting) {
    if (!startConversion(s_adsChan)) { dropAdc(); return; }
    s_converting  = true;
    s_convStarted = micros();
    return;
  }

  if (micros() - s_convStarted < CONVERSION_US) return;

  float volts = 0.0f;
  if (!readConversionVolts(volts)) { dropAdc(); return; }

  s_amps.addSample(volts);
  s_converting = false;
}

}  // namespace

void begin() {
  s_volts.ac = VOLTAGE_IS_AC;
  s_amps.ac  = CURRENT_IS_AC;

  // Both candidate pins are set up so a later switch is instant.
  for (int pin : { PIN_PUMP_VOLTAGE, PIN_PUMP_VOLTAGE_L2 }) {
    pinMode(pin, INPUT);
    // Full 0 - 3.3 V input span. Without this the pin saturates near 1.1 V.
    analogSetPinAttenuation(pin, ADC_11db);
  }
  setLine(Settings::measuredLine());

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, I2C_CLOCK_HZ);
  // Say out loud which way the current channel is configured and what is
  // being undone, so a reading can never be misread as a raw measurement.
  if (CURRENT_IS_AC) {
    Serial.print("[pwr] current = AC RMS; C63 corner ");
    Serial.print(SENSE_FILTER_CORNER_HZ, 1);
    Serial.print(" Hz, so x");
    Serial.print(CURRENT_AC_FILTER_COMP, 3);
    Serial.print(" compensation applied at ");
    Serial.print(LINE_FREQUENCY_HZ, 0);
    Serial.println(" Hz");
  } else {
    Serial.println("[pwr] current = DC average, pedestal subtracted");
  }

  probeAdc();

  s_windowStarted = millis();
  s_lastClosed    = s_windowStarted;
}

bool setLine(uint8_t line) {
  if (line != 1 && line != 2) return false;

  s_line    = line;
  s_voltPin = (line == 1) ? PIN_PUMP_VOLTAGE : PIN_PUMP_VOLTAGE_L2;
  s_adsChan = (line == 1) ? ADS_CH_PUMP_CURRENT : ADS_CH_PUMP_CURRENT_L2;

  // Start clean: the in-flight ADC conversion was for the old channel.
  s_volts.reset();
  s_amps.reset();
  s_converting = false;

  Serial.print("[pwr] measuring line ");
  Serial.print(line);
  Serial.print("  (voltage GPIO ");
  Serial.print(s_voltPin);
  Serial.print(", current AIN");
  Serial.print(s_adsChan);
  Serial.println(")");
  return true;
}

uint8_t line() { return s_line; }

bool probeAdc() {
  Wire.beginTransmission(ADS_I2C_ADDRESS);
  const bool found = (Wire.endTransmission() == 0);

  Serial.print("[pwr] external ADC at 0x");
  Serial.print(ADS_I2C_ADDRESS, HEX);
  Serial.println(found ? " found" : " NOT FOUND - current unavailable");

  if (found) {
    s_adsPresent = true;
    s_converting = false;
  } else {
    dropAdc();
  }
  return s_adsPresent;
}

void tick() {
  sampleVoltage();
  sampleCurrent();

  const uint32_t now = millis();
  if (now - s_windowStarted < WINDOW_MS) return;

  s_volts.closeWindow();
  s_amps.closeWindow();

  s_windowStarted = now;
  s_lastClosed    = now;
}

Reading read() {
  Reading r;
  r.voltsOk = s_volts.valid;
  r.ampsOk  = s_adsPresent && s_amps.valid;
  r.volts   = s_volts.result * PUMP_VOLTS_PER_PIN_VOLT;
  // AC: the accumulator has already tracked the bias out - the same trick the
  // voltage channel relies on - so the Hall pedestal cancels itself and there
  // is nothing to subtract. Then undo what C63 took off at the line frequency.
  //
  // DC: subtract the pedestal before scaling. A small negative result is left
  // visible rather than clamped to zero - it means the stored zero is off, and
  // hiding it would hide the calibration error.
  r.amps    = CURRENT_IS_AC
                ? s_amps.result * CURRENT_AC_FILTER_COMP * AMPS_SCALE
                : (s_amps.result - s_ampsZero) * AMPS_SCALE;
  r.va      = (r.voltsOk && r.ampsOk) ? r.volts * r.amps : 0.0f;
  r.updated = s_lastClosed;
  return r;
}

bool adcPresent() { return s_adsPresent; }

float currentZero() { return s_ampsZero; }

bool zeroCurrent() {
  if (!s_adsPresent || !s_amps.valid) return false;   // no window yet

  s_ampsZero = s_amps.result;
  Serial.print("[pwr] zero = ");
  Serial.print(s_ampsZero, 4);
  Serial.println(" V at the ADS pin");
  return true;
}

void printAll() {
  const Reading r = read();

  Serial.print("[pwr] LINE ");
  Serial.print(s_line);
  Serial.print("  V=");
  if (r.voltsOk) Serial.print(r.volts, 2); else Serial.print("--.--");
  Serial.print("  I=");
  if (r.ampsOk) Serial.print(r.amps, 3); else Serial.print("--.---");
  Serial.print("  VA=");
  if (r.voltsOk && r.ampsOk) Serial.print(r.va, 1); else Serial.print("---.-");
  Serial.print("   zero=");
  Serial.print(s_ampsZero, 4);
  Serial.print("  n=");
  Serial.print(s_volts.lastCount);    // voltage samples in the last window

  // Raw pin swing over the window. If min and max are the same, the pin is
  // not moving at all and there is no ac to measure - which a dc meter
  // cannot tell you, because the bias is there either way.
  Serial.print("  pin=");
  Serial.print(s_volts.lastMin, 3);
  Serial.print("..");
  Serial.print(s_volts.lastMax, 3);
  Serial.print(" V  pp=");
  Serial.print(s_volts.lastMax - s_volts.lastMin, 3);

  // Raw volts at the ADS input, before the pedestal is subtracted. Compare
  // this against a meter on TP11: if they agree, the whole path from sensor
  // to I2C is sound and only the calibration is in question.
  Serial.print("  adc=");
  if (s_adsPresent) Serial.print(s_amps.result, 4); else Serial.print("no ADC");

  // Raw swing at the ADS current input over the window, before averaging.
  if (s_adsPresent) {
    Serial.print("  iraw=");
    Serial.print(s_amps.lastMin, 3);
    Serial.print("..");
    Serial.print(s_amps.lastMax, 3);
    Serial.print(" V  ipp=");
    Serial.print(s_amps.lastMax - s_amps.lastMin, 3);

    // Current samples in that window. RMS needs roughly 20 per cycle to be
    // trustworthy. With one current channel the ADS no longer alternates, so
    // this is roughly double what the two-source build managed.
    Serial.print(" in=");
    Serial.print(s_amps.lastCount);
  }
  Serial.println();
}

}  // namespace PowerMeter
