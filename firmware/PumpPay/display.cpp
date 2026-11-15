#include "display.h"
#include "board_pins.h"
#include "power_meter.h"
#include "pump.h"
#include "ethernet.h"
#include "wifi_net.h"
#include "bt.h"
#include "settings.h"

#include <Wire.h>

namespace Display {
namespace {

// This module reads the other modules directly, which power_meter deliberately
// does not do. That is not an inconsistency: a view depends on what it shows by
// nature, whereas power_meter is a peer that would have been coupled to a
// sibling for no reason. Dependencies point inward, to the display, never out.

// ---- Panel geometry -------------------------------------------------------
// Set these to match the module. 16x2, 20x2 and 20x4 all work; rows beyond the
// fitted panel are simply never written.
constexpr uint8_t LCD_COLS = 20;
constexpr uint8_t LCD_ROWS = 2;

constexpr uint8_t LCD_ADDR = 0x27;   // found by the boot scan in EnergyHub.ino

// HD44780 row start addresses. Rows 2 and 3 are NOT contiguous with 0 and 1:
// a 20x4 panel is really two 40-character lines folded in half, so row 2
// continues row 0 and row 3 continues row 1. Getting this wrong prints the
// bottom half 20 characters adrift, which reads as corruption rather than as
// a layout bug.
constexpr uint8_t ROW_ADDR[4] = { 0x00, 0x40, 0x14, 0x54 };

// ---- PCF8574 bit map ------------------------------------------------------
// The near-universal backpack wiring. If the panel clearly responds but prints
// garbage, this mapping is the first suspect - a few clone boards swap RS and
// EN, and nothing else about the module would look different.
constexpr uint8_t PIN_RS = 0x01;
constexpr uint8_t PIN_EN = 0x04;
constexpr uint8_t PIN_BL = 0x08;   // backlight, held on

uint8_t s_backlight = PIN_BL;

// ---- Low level: everything reaches the panel through these ----------------
void expanderWrite(uint8_t data) {
  Wire.beginTransmission(LCD_ADDR);
  Wire.write(data | s_backlight);
  Wire.endTransmission();
}

// The HD44780 latches on the falling edge of EN. These waits are the
// controller setup and hold times in MICROseconds - they are not a delay in
// the loop() sense and do not break the no-blocking rule.
void pulseEnable(uint8_t data) {
  expanderWrite(data | PIN_EN);
  delayMicroseconds(1);
  expanderWrite(data & ~PIN_EN);
  delayMicroseconds(50);
}

void write4bits(uint8_t value) {
  expanderWrite(value);
  pulseEnable(value);
}

// mode is 0 for a command, PIN_RS for character data.
void send(uint8_t value, uint8_t mode) {
  write4bits((value & 0xF0) | mode);
  write4bits(((value << 4) & 0xF0) | mode);
}

void command(uint8_t value) { send(value, 0); }
void writeChar(char c)      { send(static_cast<uint8_t>(c), PIN_RS); }

void setCursor(uint8_t col, uint8_t row) {
  if (row >= LCD_ROWS) row = 0;
  command(0x80 | (ROW_ADDR[row] + col));
}

// ---- Row cache ------------------------------------------------------------
// One row is redrawn per tick, and only when its text actually changed. Both
// halves matter on a shared bus: a 20-character row is about 120 single-byte
// I2C writes, and every one of them is time the ADS1015 is not being read.
// Pushing an unchanged row would cost current samples for nothing.
char s_shown[4][LCD_COLS + 1] = { { 0 } };
char s_want[4][LCD_COLS + 1]  = { { 0 } };

uint8_t  s_row      = 0;
uint32_t s_lastDraw = 0;

constexpr uint32_t ROW_MS = 120;   // one row per this interval

void setRow(uint8_t row, const char* text) {
  if (row >= LCD_ROWS) return;
  snprintf(s_want[row], LCD_COLS + 1, "%-*s", static_cast<int>(LCD_COLS), text);
}

void flushRow(uint8_t row) {
  if (strcmp(s_shown[row], s_want[row]) == 0) return;
  strncpy(s_shown[row], s_want[row], LCD_COLS);
  s_shown[row][LCD_COLS] = 0;

  setCursor(0, row);
  for (uint8_t i = 0; i < LCD_COLS; i++) writeChar(s_shown[row][i]);
}

// ===========================================================================
//  NAVIGATION - up, down, OK, back
// ===========================================================================
// B1 UP, B2 DOWN, B3 OK, B4 BACK.
//
// The old scheme cycled through every screen with two of the buttons, which
// meant there was no such thing as "going into" anything: no hierarchy, no way
// to know where you were, and the act button did something different depending
// on which screen the cycle had landed on.
//
// This is the arrangement every appliance panel uses, and for the same reason:
// UP and DOWN move a selection, OK commits to it, BACK undoes the last step.
// Each button means ONE thing everywhere, so the panel can be operated without
// reading a label - which matters on a board that lives in a plant room.
//
//     HOME  -- OK/DOWN -->  MENU  -- OK -->  a screen
//           <-- BACK ------       <-- BACK --
//
// Screens that can change something (mode, PIN) put the change behind OK and
// let BACK abandon it, so nothing is committed by merely scrolling past.
enum Screen : uint8_t {
  SCR_HOME = 0,
  SCR_MENU,
  SCR_PUMP,
  SCR_RUN,
  SCR_NET,
  SCR_SYS,
  SCR_SETPIN
};

struct MenuItem {
  const char* label;
  uint8_t     screen;
};

// The menu IS the navigation. Adding a screen means adding a line here, and
// nothing else has to change.
const MenuItem MENU[] = {
  { "Pump",     SCR_PUMP   },
  { "Run time", SCR_RUN    },
  { "Network",  SCR_NET    },
  { "System",   SCR_SYS    },
  { "Set PIN",  SCR_SETPIN },
};
constexpr uint8_t MENU_COUNT = sizeof(MENU) / sizeof(MENU[0]);

uint8_t  s_screen   = SCR_HOME;
uint8_t  s_menuSel  = 0;
uint32_t s_lastBtn  = 0;

// Return to home after this long untouched. Someone who walked away mid-menu
// left the panel showing a submenu that says nothing about whether the pump is
// running. Home always does.
constexpr uint32_t HOME_TIMEOUT_MS = 30000;

// ---- PIN entry ------------------------------------------------------------
// UP/DOWN change the digit, OK moves on and saves after the last, BACK
// abandons. Twelve presses or so, and no laptop.
//
// Changing it needs no old PIN, and that is not an oversight: anyone standing
// at the buttons can already reach every setting by hand. Physical access IS
// the gate here. The PIN exists to guard the network path, not this one.
//
// Only 4 digits from the buttons, though Settings accepts up to 8 - a longer
// one can be set over Bluetooth or from the web page.
constexpr uint8_t PIN_DIGITS = 4;

char     s_pinBuf[PIN_DIGITS + 1] = "0000";
uint8_t  s_pinPos     = 0;
uint32_t s_pinSavedAt = 0;
bool     s_pinOk      = false;

// ---- Formatting -----------------------------------------------------------
// Dashes rather than a number when a channel has no valid reading, so a stale
// value can never be mistaken for a live one.
void fmt(char* out, size_t n, float v, bool ok, uint8_t decimals) {
  if (!ok) { strncpy(out, "---", n - 1); out[n - 1] = 0; return; }
  dtostrf(v, 0, decimals, out);
}

void ipText(char* out, size_t n) {
  if (Net::hasAddress()) {
    const IPAddress a = Net::ip();
    snprintf(out, n, "%u.%u.%u.%u", a[0], a[1], a[2], a[3]);
  } else if (WifiNet::connected()) {
    const IPAddress a = WifiNet::ip();
    snprintf(out, n, "W %u.%u.%u.%u", a[0], a[1], a[2], a[3]);
  } else {
    snprintf(out, n, "no network");
  }
}

void uptimeText(char* out, size_t n) {
  const uint32_t s = millis() / 1000;
  if (s >= 3600) {
    snprintf(out, n, "%luh %lum", (unsigned long)(s / 3600),
             (unsigned long)((s % 3600) / 60));
  } else {
    snprintf(out, n, "%lum %lus", (unsigned long)(s / 60),
             (unsigned long)(s % 60));
  }
}

// Run time as "1h23m" or "45m". Seconds are not shown: nobody reading a pump
// log wants them, and a figure that ticks every second is distracting.
void durText(char* out, size_t n, uint32_t secs) {
  const uint32_t h = secs / 3600;
  const uint32_t m = (secs % 3600) / 60;
  if (h > 0) snprintf(out, n, "%luh%02lum", (unsigned long)h, (unsigned long)m);
  else       snprintf(out, n, "%lum", (unsigned long)m);
}

// ---- Screen bodies --------------------------------------------------------
void drawMenu() {
  char line[40];

  // Two rows: the selection, then what comes next. Seeing the following item
  // is what makes a two-line list navigable rather than a guessing game about
  // whether there is anything below.
  for (uint8_t r = 0; r < LCD_ROWS; r++) {
    const uint8_t i = static_cast<uint8_t>((s_menuSel + r) % MENU_COUNT);
    snprintf(line, sizeof(line), "%c%s", r == 0 ? '>' : ' ', MENU[i].label);
    setRow(r, line);
  }
}

void buildScreen() {
  const PowerMeter::Reading p = PowerMeter::read();

  char v[12], a[12], b[24], line[40];

  switch (s_screen) {
    case SCR_MENU:
      drawMenu();
      break;

    case SCR_PUMP:
      fmt(v, sizeof(v), p.volts, p.voltsOk, 1);
      fmt(a, sizeof(a), p.amps,  p.ampsOk,  2);
      snprintf(line, sizeof(line), "L%u PUMP %s", (unsigned)PowerMeter::line(), Pump::shortName());
      setRow(0, line);
      snprintf(line, sizeof(line), "%sV  %sA", v, a);
      setRow(1, line);
      if (LCD_ROWS >= 4) {
        fmt(v, sizeof(v), p.va, p.voltsOk && p.ampsOk, 0);
        snprintf(line, sizeof(line), "%s VA", v);
        setRow(2, line);
        setRow(3, "BACK to menu");
      }
      break;

    case SCR_RUN:
      durText(b, sizeof(b), Pump::timeKnown() ? Pump::todayRunSecs() : 0);
      snprintf(line, sizeof(line), "TODAY %s", Pump::timeKnown() ? b : "-- (no clock)");
      setRow(0, line);
      durText(b, sizeof(b), Pump::totalRunSecs());
      snprintf(line, sizeof(line), "TOTAL %s", b);
      setRow(1, line);
      if (LCD_ROWS >= 4) {
        snprintf(line, sizeof(line), "STARTS %lu", (unsigned long)Pump::starts());
        setRow(2, line);
        durText(b, sizeof(b), Pump::currentRunSecs());
        snprintf(line, sizeof(line), "THIS RUN %s", Pump::running() ? b : "--");
        setRow(3, line);
      }
      break;

    case SCR_NET:
      ipText(b, sizeof(b));
      setRow(0, Net::hasAddress() ? "ETHERNET" : "NETWORK");
      setRow(1, b);
      if (LCD_ROWS >= 4) {
        snprintf(line, sizeof(line), "bt %s",
                 Bluetooth::connected() ? "connected" : "waiting");
        setRow(2, line);
        setRow(3, "");
      }
      break;

    case SCR_SYS:
      uptimeText(b, sizeof(b));
      snprintf(line, sizeof(line), "UP %s", b);
      setRow(0, line);
      snprintf(line, sizeof(line), "ADC %s  PIN %s",
               PowerMeter::adcPresent() ? "ok" : "--",
               Settings::pinIsDefault() ? "DEF" : "set");
      setRow(1, line);
      if (LCD_ROWS >= 4) {
        snprintf(line, sizeof(line), "PHY addr %ld", (long)Net::phyAddress());
        setRow(2, line);
        setRow(3, Pump::timeKnown() ? "clock set" : "clock not set");
      }
      break;

    case SCR_SETPIN: {
      if (s_pinSavedAt != 0) {
        setRow(0, s_pinOk ? "PIN SAVED" : "PIN REJECTED");
        setRow(1, s_pinOk ? "" : "4 digits, 0-9 only");
        if (LCD_ROWS >= 4) { setRow(2, ""); setRow(3, ""); }
        break;
      }

      // Brackets mark the digit being edited: "SET PIN  [0]000".
      char shown[16];
      uint8_t k = 0;
      for (uint8_t i = 0; i < PIN_DIGITS && k < sizeof(shown) - 4; i++) {
        if (i == s_pinPos) {
          shown[k++] = '[';
          shown[k++] = s_pinBuf[i];
          shown[k++] = ']';
        } else {
          shown[k++] = s_pinBuf[i];
        }
      }
      shown[k] = '\0';

      snprintf(line, sizeof(line), "SET PIN  %s", shown);
      setRow(0, line);
      setRow(1, s_pinPos == PIN_DIGITS - 1 ? "OK saves  BACK exit"
                                           : "UP/DN digit  OK next");
      if (LCD_ROWS >= 4) { setRow(2, ""); setRow(3, ""); }
      break;
    }

    case SCR_HOME:
    default: {
      // Home answers the one question a farmer has: is it pumping, and for how
      // long today. The state fills the top row on its own so it can be read
      // from across the yard.
      snprintf(line, sizeof(line), "PUMP %s", Pump::shortName());
      setRow(0, line);

      fmt(v, sizeof(v), p.volts, p.voltsOk, 0);
      fmt(a, sizeof(a), p.amps,  p.ampsOk,  1);
      if (Pump::timeKnown()) {
        durText(b, sizeof(b), Pump::todayRunSecs());
        snprintf(line, sizeof(line), "%sV %sA  %s", v, a, b);
      } else {
        snprintf(line, sizeof(line), "%sV %sA  --", v, a);
      }
      setRow(1, line);

      if (LCD_ROWS >= 4) {
        durText(b, sizeof(b), Pump::totalRunSecs());
        snprintf(line, sizeof(line), "TOTAL %s", b);
        setRow(2, line);
        snprintf(line, sizeof(line), "STARTS %lu", (unsigned long)Pump::starts());
        setRow(3, line);
      }
      break;
    }
  }
}

// Every row is rewritten on the next tick. Used whenever the screen CHANGES,
// as opposed to a value on it changing.
void invalidate() {
  for (uint8_t r = 0; r < LCD_ROWS; r++) s_shown[r][0] = 0;
  s_row = 0;
}

}  // namespace

void begin() {
  // Wire is already up - PowerMeter::begin() started it on GPIO 32/33. A second
  // Wire.begin() here would be ignored by the core anyway, but leaning on that
  // would hide the ordering dependency rather than state it.

  // HD44780 cold start. The panel powers up in 8-bit mode and has to be talked
  // down into 4-bit through a fixed sequence with real waits. These are the
  // datasheet power-on times in MILLIseconds and they cannot be shortened.
  // begin() runs from setup(), where blocking is allowed; nothing in this
  // function is reachable from loop().
  delay(50);
  expanderWrite(s_backlight);
  delay(200);

  write4bits(0x30); delay(5);
  write4bits(0x30); delay(5);
  write4bits(0x30); delayMicroseconds(150);
  write4bits(0x20);              // 4-bit mode from here on

  command(0x28);                 // function set: 4-bit, 2 lines, 5x8 font
  command(0x08);                 // display off
  command(0x01); delay(2);       // clear - needs the full 1.52 ms
  command(0x06);                 // entry mode: left to right, no shift
  command(0x0C);                 // display on, cursor off, no blink

  Serial.print("[lcd] HD44780 ");
  Serial.print(LCD_COLS);
  Serial.print("x");
  Serial.print(LCD_ROWS);
  Serial.print(" on PCF8574 at 0x");
  Serial.println(LCD_ADDR, HEX);
  Serial.println("[lcd] buttons: B1 up, B2 down, B3 OK, B4 back");

  s_lastDraw = millis();
  s_lastBtn  = millis();
}

void tick() {
  const uint32_t now = millis();

  // The save confirmation clears itself. Leaving it up would sit there
  // inviting a second, accidental edit of a PIN that was just set.
  if (s_screen == SCR_SETPIN && s_pinSavedAt != 0 &&
      now - s_pinSavedAt >= 2500) {
    s_pinSavedAt = 0;
    s_screen     = SCR_MENU;
    invalidate();
  }

  if (s_screen != SCR_HOME && now - s_lastBtn >= HOME_TIMEOUT_MS) {
    s_screen = SCR_HOME;
    invalidate();
  }

  if (now - s_lastDraw < ROW_MS) return;
  s_lastDraw = now;

  buildScreen();
  flushRow(s_row);
  s_row = static_cast<uint8_t>((s_row + 1) % LCD_ROWS);
}

void onButton(Buttons::Event evt, Buttons::Button btn) {
  // Press only. Acting on release as well would double every action, and
  // LongPress is left unclaimed rather than overloaded onto navigation.
  if (evt != Buttons::Event::Press) return;

  s_lastBtn = millis();

  const bool up   = (btn == Buttons::Button::B1);
  const bool down = (btn == Buttons::Button::B2);
  const bool ok   = (btn == Buttons::Button::B3);
  const bool back = (btn == Buttons::Button::B4);

  switch (s_screen) {

    // ---- Root: any of OK or DOWN opens the menu ---------------------------
    case SCR_HOME:
      if (ok || down || up) {
        s_screen  = SCR_MENU;
        s_menuSel = 0;
      }
      break;

    // ---- The list ---------------------------------------------------------
    case SCR_MENU:
      if (up) {
        s_menuSel = (s_menuSel == 0) ? (MENU_COUNT - 1)
                                     : static_cast<uint8_t>(s_menuSel - 1);
      } else if (down) {
        s_menuSel = static_cast<uint8_t>((s_menuSel + 1) % MENU_COUNT);
      } else if (ok) {
        s_screen = MENU[s_menuSel].screen;

        // Entering the PIN editor starts it from a clean "0000".
        if (s_screen == SCR_SETPIN) {
          strcpy(s_pinBuf, "0000");
          s_pinPos     = 0;
          s_pinSavedAt = 0;
          s_pinOk      = false;
        }
      } else if (back) {
        s_screen = SCR_HOME;
      }
      break;

    // ---- PIN editor -------------------------------------------------------
    case SCR_SETPIN:
      if (s_pinSavedAt != 0) {
        s_pinSavedAt = 0;         // confirmation showing - any key dismisses
        s_screen     = SCR_MENU;
        break;
      }
      if (up) {
        s_pinBuf[s_pinPos] =
            (s_pinBuf[s_pinPos] == '9') ? '0'
                                        : static_cast<char>(s_pinBuf[s_pinPos] + 1);
      } else if (down) {
        s_pinBuf[s_pinPos] =
            (s_pinBuf[s_pinPos] == '0') ? '9'
                                        : static_cast<char>(s_pinBuf[s_pinPos] - 1);
      } else if (ok) {
        if (s_pinPos + 1 < PIN_DIGITS) {
          s_pinPos++;
        } else {
          s_pinOk      = Settings::setControlPin(s_pinBuf);
          s_pinSavedAt = millis();
        }
      } else if (back) {
        // Step back a digit first; only leave from the first one. Otherwise a
        // mistyped third digit costs the whole entry.
        if (s_pinPos > 0) s_pinPos--;
        else              s_screen = SCR_MENU;
      }
      break;

    // ---- Read-only detail screens -----------------------------------------
    default:
      if (back || ok) s_screen = SCR_MENU;
      else if (up) {
        s_menuSel = (s_menuSel == 0) ? (MENU_COUNT - 1)
                                     : static_cast<uint8_t>(s_menuSel - 1);
        s_screen = MENU[s_menuSel].screen;
      } else if (down) {
        // UP and DOWN on a detail screen step to the neighbouring MENU entry,
        // so you can walk the readings without returning to the list each
        // time. The list order is the walk order, which is why it exists.
        s_menuSel = static_cast<uint8_t>((s_menuSel + 1) % MENU_COUNT);
        s_screen = MENU[s_menuSel].screen;
      }
      break;
  }

  invalidate();
}

const char* rowText(uint8_t row) {
  if (row >= LCD_ROWS) return "";
  return s_want[row];
}

uint8_t rows() { return LCD_ROWS; }
uint8_t cols() { return LCD_COLS; }

// Nothing can be read back through a PCF8574 backpack in this wiring, so a
// panel that is addressed but dead looks identical to a working one. The boot
// scan answering at 0x27 is the real presence check.
bool present() { return true; }

}  // namespace Display
