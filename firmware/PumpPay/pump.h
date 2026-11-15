#pragma once
#include <Arduino.h>

/*
  Pump run tracking - is the pump running, for how long, and at what hours.

  Everything here is derived from the one thing the board measures: the current
  on the pump line. Above RUN_ON_AMPS the pump is running; below RUN_OFF_AMPS
  it has stopped. That is all PumpPay needs to know to bill by hours and to
  show a farmer when the pump was on.

  TWO THRESHOLDS, NOT ONE. A motor starting draws a surge, and a pump sitting
  right on a single threshold would flicker between running and stopped.
  RUN_OFF_AMPS is lower than RUN_ON_AMPS, and each change has to hold for a
  confirm time before it counts, so a surge, a dip or one noisy window cannot
  start or stop the clock.

  NO CURRENT READING IS NOT "STOPPED". If the current ADC is not answering the
  state is NoSignal and the run clock holds still. Counting a dead sensor as
  an idle pump would silently under-bill and hide the fault.

  TIME OF DAY. The board has no real-time clock. It learns the time from the
  phone app (the "time" Bluetooth command, sent on every connect) or from NTP
  once WiFi is up, and until then it can count how long the pump has run but
  not WHEN. Hourly history is only recorded while the time is known.

  HISTORY. The last 168 hours (7 days) of run time and apparent energy (VAh), one bucket per clock hour,
  kept in RAM and saved to flash at most every SAVE_EVERY_MS. A power cut loses
  at most that much. The lifetime run-time and start counters are saved with it.
*/
namespace Pump {

enum class State : uint8_t { NoSignal = 0, Idle, Running };

constexpr uint16_t HIST_HOURS = 168;   // 7 days of hourly buckets

void begin();
void tick();

State       state();
bool        running();
const char* name(State s);       // "NO SIGNAL" / "IDLE" / "PUMPING"
const char* shortName();         // for a 20-column LCD row

uint32_t currentRunSecs();       // length of the run in progress, 0 if stopped
uint32_t todayRunSecs();         // run time since local midnight, 0 if time unknown
uint32_t totalRunSecs();         // lifetime run time, survives reboot
uint32_t starts();               // lifetime pump starts, survives reboot

// Wall-clock time. epoch is UTC seconds; tzMinutes is the local offset from
// UTC (Nairobi = 180). Ignored if epoch is implausibly old.
void     setTime(uint32_t epoch, int tzMinutes);
bool     timeKnown();
uint32_t epochNow();             // 0 if unknown
int      tzMinutes();

// Run time in the clock hour `back` hours before the current one (0 = this
// hour). Seconds, 0..3600. Returns 0 outside the 168 h window or if time is
// unknown. Index by UTC hour; the app converts to local.
uint16_t histSecs(uint16_t back);
uint16_t histVah(uint16_t back);   // apparent energy in that hour, VAh
uint32_t histHourNow();          // current UTC hour number (epoch/3600), 0 if unknown

// Wipes the lifetime counters and history. Used when a board is moved to a new
// customer. Not exposed on the web page.
void resetCounters();

}  // namespace Pump
