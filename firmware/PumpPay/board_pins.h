#pragma once
#include <Arduino.h>

/*
  PumpPay - board pin map.

  Single source of truth. Every module includes this header; no module defines
  its own pin numbers.

  ESP32 rules that constrain this map:
    * GPIO 34/35/36/39 are INPUT ONLY - they cannot be pinMode(OUTPUT) and
      have no internal pull-ups.
    * ADC2 is unusable while the radio is active - all analog inputs are on ADC1.
    * RMII Ethernet (LAN8720) hard-reserves the pins listed below. Never reuse
      them for I/O.

  Reserved by the RMII interface (fixed by silicon, not configurable):
      GPIO 19  TXD0        GPIO 25  RXD0
      GPIO 21  TX_EN       GPIO 26  RXD1
      GPIO 22  TXD1        GPIO 27  CRS_DV
*/

// ---- Ethernet PHY (configurable half) -------------------------------------
constexpr int PIN_ETH_MDC   = 23;
constexpr int PIN_ETH_MDIO  = 18;
constexpr int PIN_ETH_POWER = -1;   // -1 = PHY power is hard-wired on
// The 50 MHz REF_CLK is driven OUT on GPIO 17 - see ETH_CLK_MODE in ethernet.cpp

// ---- Digital outputs ------------------------------------------------------
// PumpPay measures ONE line - the pump feed - and switches nothing. The board
// was laid out as a two-source transfer switch; the second source is simply
// not connected, so its channels are not read and its relay is never driven.
//
// GPIO 4 (the DPDT coil driver) is configured as an output and held LOW, so
// the coil stays de-energized whatever is wired to it. The ESP32 also gives
// it a weak pull-down at reset, so it is low from power-on.
constexpr int PIN_RELAY     = 4;   // held LOW - unused by PumpPay
constexpr int PIN_LED_PUMP  = 16;  // LED1 / Q1 gate - lit while the pump runs
constexpr int PIN_LED_SPARE = 15;  // LED2 / Q2 gate - unused, held off

// There is no firmware heartbeat LED and there cannot be one. GPIO 2 was the
// last spare pin and it went to the display D/C line. Nothing is left:
//
//   GPIO 0        strapping, LOW at reset enters download mode
//   GPIO 1, 3     UART0 - taking these costs the serial monitor
//   GPIO 6..11    internal SPI flash
//   GPIO 12       VDD_SDIO strapping - HIGH at reset and the chip will not
//                 boot at all, because it configures flash for 1.8 V
//   GPIO 34..39   input only, no output driver in the silicon
//
// So a hung loop() is not visible at a glance on this board. The two LEDs
// flashing alternately during the power-on self-test are the only proof of
// life.

// ---- J1 header - retired ---------------------------------------------------
// J1 was laid out as SPI (+5V2 / SCLK 14 / MOSI 13 / CS 5 / GND) for the
// ST7735 panel. The panel is now an I2C part and shares the ADC's bus on
// GPIO 32/33 (see below), so none of J1's signal pins are used any more.
//
// GPIO 5, 13 and 14 are therefore free, and so is GPIO 2, which used to carry
// the display D/C line by flying lead. Nothing claims any of the four yet.
//
// One I2C bus, not two, is a deliberate choice and not just convenience: the
// display and the ADS1015 sit at different addresses (0x3C or 0x3D for a
// typical OLED, 0x48 for the ADS), so they coexist without a second peripheral
// and without spending two more pins. The cost is bus time: I2C_CLOCK_HZ in
// power_meter.cpp sets the speed, and the ADS is polled hard enough that a
// slow full-screen refresh will steal samples from it. Keep display writes
// small, or the current RMS pays for them.

// ---- Pump voltage sense (ESP32 ADC1, input-only pin) ----------------------
// Voltage IS read by the ESP32 directly, on the dedicated sense pin of U14
// (ESP32-WROOM-32D): pin 5 SENSOR_VN = GPIO39. GPIO 34/35/36/39 are input-only
// and have no internal pull-ups. This pin is on ADC1, which stays usable while
// the radio is active (ADC2 does not).
//
// The other sense pin, GPIO36 (SENSOR_VP), is LINE 2: the second measurement
// channel the board was built with. PumpPay measures ONE line at a time and
// reads this one only when LINE 2 has been selected (Settings::measuredLine).
constexpr int PIN_PUMP_VOLTAGE_L2 = 36;  // ADC1_CH0, U14 pin 4 (SENSOR_VP) - LINE 2
constexpr int PIN_PUMP_VOLTAGE = 39;  // ADC1_CH3, U14 pin 5 (SENSOR_VN) - LINE 1

constexpr int PIN_BUTTONS      = 35;  // ADC1_CH7 - 4-button resistor ladder

// ---- Pump current sense (external I2C ADC) --------------------------------
// Current does NOT reach the ESP32 directly. It goes into U16, an ADS1015IDGS,
// which the ESP32 reads over I2C:
//
//     PUMP current -> AIN3 (pin 7), board net A3E2
//
//     LINE 2 current -> AIN2 (pin 6), board net A1E2, read when LINE 2 is selected
//
// AIN0 (pin 4) and AIN1 (pin 5) are no-connects.
//
// U16 runs from +3.3V2 with ADDR (pin 1) tied to GND, and ALERT/RDY (pin 2)
// is unconnected - there is no conversion-ready interrupt available.
//
// The ESP32 default I2C pins (21/22) are RMII TX_EN and TXD1, so I2C is
// remapped to IO32/IO33 (U14 pins 8 and 9):
constexpr int PIN_I2C_SDA = 32;
constexpr int PIN_I2C_SCL = 33;

constexpr uint8_t ADS_I2C_ADDRESS     = 0x48;  // ADDR tied to GND
constexpr uint8_t ADS_CH_PUMP_CURRENT = 3;     // AIN3, net A3E2 - LINE 1
constexpr uint8_t ADS_CH_PUMP_CURRENT_L2 = 2;  // AIN2, net A1E2 - LINE 2
