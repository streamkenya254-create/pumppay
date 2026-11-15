#pragma once
#include <Arduino.h>

/*
  Four pushbuttons on one pin - GPIO 35, ADC1_CH7.

  The buttons form a resistor ladder: a 10k pull-up from +3.3 V to the pin,
  and each button connects its own resistor to ground, so every button lands
  the pin on a different voltage.

      button   R to GND    V at pin    counts
      B1       1 k         0.300 V       372
      B2       4.7 k       1.055 V      1309
      B3       15 k        1.980 V      2457
      B4       47 k        2.721 V      3377
      none     open        3.300 V      4095

  The external pull-up is not optional. GPIO 35 is one of the four input-only
  pins and has NO internal pull-up, so INPUT_PULLUP does nothing here - without
  the 10k the pin floats when no button is down and reads noise.

  Three limits the topology imposes, none of which firmware can work around:

    * No chords. Two buttons at once puts their resistors in parallel and the
      lowest one wins, so B1+B4 reads as B1 - not as a combination.
    * No interrupts. An ADC pin cannot wake the chip on a press, so this has
      to be polled. Fine from tick(), but it rules out waking from deep sleep.
    * Release is inferred from the pin returning to the no-press level, so a
      broken pull-up looks like a button held forever.
*/
namespace Buttons {

enum class Button : uint8_t { None = 0, B1, B2, B3, B4 };
enum class Event  : uint8_t { None = 0, Press, Release, LongPress, Repeat };

void begin();
void tick();

// Takes the oldest pending event, if any. Call in a while loop - a single
// tick can produce two events when one button is released as another goes
// down. Returns false once the queue is empty.
bool poll(Event& evt, Button& btn);

Button   held();     // button currently down, None if none
uint32_t heldMs();   // how long it has been down, 0 if none

// False when begin() found the resting level implausible and stood the module
// down. Nothing is reported in that state - a floating pin would otherwise
// produce a stream of phantom presses.
bool enabled();

// Raw millivolts at the pin, from the last sample. For checking the ladder
// against real resistor tolerances - watch this while pressing each button.
uint16_t millivolts();

const char* name(Button btn);
const char* name(Event evt);

}  // namespace Buttons
