#pragma once
#include <Arduino.h>
#include "buttons.h"

/*
  HD44780 character LCD on a PCF8574 I2C backpack, address 0x27, with a
  button-driven menu.

  It shares the ADC's bus - no second I2C peripheral and no pin of its own:

      SDA -> GPIO 32      SCL -> GPIO 33      (Wire, alongside the ADS1015)

  NAVIGATION - up, down, OK, back:

      B1  UP      move the selection
      B2  DOWN    move the selection
      B3  OK      enter / commit
      B4  BACK    leave / abandon

  Each button means one thing everywhere, which is what lets the panel be
  worked without reading a label - the point, on a board in a plant room.

      HOME  -- OK/UP/DOWN -->  MENU  -- OK -->  screen
            <---- BACK -------       <- BACK --

  HOME is the status root: is the pump running, its volts and amps, and how
  long it has run today.

  MENU lists Pump, Run time, Network, System, Set PIN. On a two-row panel it
  shows the selection and the item after it, so there is never a question
  about whether anything lies below.

  From a detail screen, UP and DOWN step to the neighbouring menu entry rather
  than doing nothing, so the readings can be walked without going back to the
  list each time.

  ANYTHING THAT CHANGES STATE IS BEHIND OK, AND BACK ABANDONS IT. The PIN
  editor only saves on OK after the last digit - scrolling past it on the way
  somewhere else changes nothing.

  The panel returns to HOME after 30 s untouched. Someone who walked away
  mid-menu left it showing a submenu that says nothing about whether the pump
  is running; home always does.

  GEOMETRY. LCD_COLS and LCD_ROWS at the top of display.cpp must match the
  panel. 16x2, 20x2 and 20x4 all work - a 4-row panel gets extra detail on
  most screens; rows past the fitted panel are never written. Set them wrong
  on a 4-row and the bottom half prints 20 characters adrift, because rows 2
  and 3 are not contiguous with 0 and 1 on these controllers.

  NO LIBRARY. The PCF8574 and HD44780 are driven inline against Wire, the same
  way the ADS1015 is in power_meter.cpp. That is not stubbornness: on a shared
  bus this module has to control exactly how many bytes it sends and when,
  which a library that redraws on its own schedule takes away.

  BUS COST. power_meter polls the ADS continuously and manages only ~92 current
  samples per 200 ms window. Each 20-character row is roughly 120 single-byte
  I2C writes, so one row is redrawn per tick and only when its text changed.
  Watch in= in the meter dump: if it falls, the current RMS is paying for the
  screen and ROW_MS should go up.

  The old ST7735 SPI implementation is preserved in
  display_st7735_spi.cpp.disabled - the extension stops the Arduino IDE
  compiling it, since the IDE builds every .cpp beside the .ino.
*/
namespace Display {

void begin();
void tick();

// Fed from the sketch's button drain. Acts on Press only.
void onButton(Buttons::Event evt, Buttons::Button btn);

// What the panel is currently showing, row by row. Exposed so the web page can
// mirror the LCD and drive it with virtual buttons - which makes the menu
// usable before the physical buttons are wired, and lets someone holding a
// phone see exactly what someone standing at the board sees.
//
// Returns the composed text for the row, space-padded to cols(). Rows at or
// beyond rows() return an empty string rather than reading past the array.
const char* rowText(uint8_t row);
uint8_t     rows();
uint8_t     cols();

// A PCF8574 backpack cannot be read back in this wiring, so an addressed but
// dead panel is indistinguishable from a working one. The boot scan in
// EnergyHub.ino answering at 0x27 is the real presence check.
bool present();

}  // namespace Display
