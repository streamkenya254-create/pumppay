#pragma once
#include <Arduino.h>

/*
  Pump line voltage and current measurement.

  PumpPay watches ONE line. The hardware has a second measurement channel left
  over from the solar/mains transfer switch it was designed as; it is not
  connected and not read.

  Signal path:
    PUMP voltage -> GPIO 39 (ADC1_CH3)   read by the ESP32 directly
    PUMP current -> ADS AIN3, net A3E2   read over I2C (SDA 32, SCL 33)

  Sampling is continuous and non-blocking: tick() takes one sample of each
  signal per call and closes a measurement window every WINDOW_MS, so the
  numbers you read are a windowed result, never a single instantaneous sample.

  AC signals are reduced to true RMS (sum of squares about a tracked DC bias);
  DC signals are averaged. Which one applies is set per quantity by
  VOLTAGE_IS_AC / CURRENT_IS_AC in power_meter.cpp.

  CURRENT IS AC on this system - measured. The sense node is an ACS758-050U
  unidirectional Hall sensor behind a divider and a ~29 Hz low-pass. It was
  designed for a DC level, but the line carries mains-frequency AC, and the
  sensor swings symmetrically about its pedestal - so averaging it returns the
  pedestal whatever the current, and 6.58 A once read as 0.02 A. read() now
  takes RMS about the tracked bias and multiplies by CURRENT_AC_FILTER_COMP to
  undo the low-pass. That is a correction, not a measurement: see the comment
  block on CURRENT_AC_FILTER_COMP for what it assumes and where it breaks.

  CALIBRATION IS REQUIRED before any reading means anything - see the scale
  constants at the top of power_meter.cpp.
*/
namespace PowerMeter {

struct Reading {
  float    volts   = 0.0f;   // line voltage, V (RMS if VOLTAGE_IS_AC)
  float    amps    = 0.0f;   // pump current, A (RMS if CURRENT_IS_AC)
  float    va      = 0.0f;   // apparent power, volts * amps
  bool     voltsOk = false;  // false until the first window closes
  bool     ampsOk  = false;  // also false if the external ADC is missing
  uint32_t updated = 0;      // millis() of the last completed window
};

void begin();
void tick();

Reading read();

// Which line is measured: 1 or 2 (see board_pins.h). Switching reconfigures the
// voltage pin and the ADC channel and throws away the half-finished window, so
// the first reading after a switch is never a blend of the two lines. Readings
// are marked not-ok until a fresh window closes. Returns false for anything but
// 1 or 2.
bool setLine(uint8_t line);
uint8_t line();

// True once the external ADC answered on I2C. Voltage still works without it,
// but the pump cannot be detected without current.
bool adcPresent();

// Re-probe the I2C bus for the ADC, e.g. after a wiring fix. Returns adcPresent().
bool probeAdc();

// Captures the present current reading as the zero-current pedestal. Call it
// with the PUMP OFF - it nulls the Hall sensor offset and its supply-rail
// dependence in one step. Only meaningful when CURRENT_IS_AC is false; in AC
// mode the accumulator removes the bias itself. Returns false if the ADC is
// absent or no measurement window has closed yet.
bool zeroCurrent();

// The pedestal currently in use, in volts at the ADS pin.
float currentZero();

// Serial dump of the line, with the raw diagnostics calibration depends on:
// pin swing, sample counts, the current-channel zero. Telemetry rather than
// debug output - ENABLE_METER_REPORT in PumpPay.ino gates the caller.
void printAll();

}  // namespace PowerMeter
