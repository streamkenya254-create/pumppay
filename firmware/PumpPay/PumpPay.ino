/*
  PumpPay - solar pump controller and run-time meter
  ===================================================

  Watches ONE line, the pump feed: its voltage and current. From the current it
  works out whether the pump is running, for how long, and at what hours of the
  day, and shows that on an LCD, a web page, and a phone app over Bluetooth.

  The board was designed as a solar/mains transfer switch with two measured
  lines. PumpPay uses one of them and ignores the other; the transfer relay is
  never driven.

  Modules (each is a .h/.cpp pair in this folder, compiled automatically):
    board_pins.h    - the pin map, single source of truth
    settings        - WiFi credentials and control PIN, stored in NVS
    power_meter     - pump voltage (GPIO 39) and current (ADS AIN3)
    pump            - is it running, run-time counters, hourly history
    report          - turns the above into the JSON the web page and app read
    buttons         - four pushbuttons, resistor ladder on GPIO 35
    display         - HD44780 LCD on I2C, button-driven menu
    ethernet        - LAN8720, PHY address auto-detected
    wifi_net        - WiFi station, off until credentials are stored
    webui           - web page on port 80, over either network transport
    bt              - Bluetooth serial, status and control from a phone
    cloud           - posts readings to a Firebase Cloud Function over WiFi
    billing         - prepaid time: spends it while the pump runs, works the relay

  Every module exposes begin() and a non-blocking tick(). Nothing in loop()
  may call delay(): a blocking module would stall the measurement window and
  the link check.

  RESPONSIBILITY SPLIT. power_meter answers what the line is doing and depends
  on nothing. pump decides what that means. display, webui and bt read those
  and are read by none of them. Dependencies point one way.

  Board: ESP32 + LAN8720 PHY. Serial monitor at 115200 baud.

  PARTITION SCHEME: "Huge APP (3 MB No OTA)". WiFi and Bluetooth together do
  not fit the default 1.3 MB app partition. Tools > Partition Scheme in the
  IDE - the upload fails with a size error otherwise.
*/

#include <Wire.h>
#include "esp_bt.h"

#include "board_pins.h"
#include "settings.h"
#include "power_meter.h"
#include "pump.h"
#include "buttons.h"
#include "display.h"
#include "ethernet.h"
#include "wifi_net.h"
#include "webui.h"
#include "bt.h"
#include "cloud.h"
#include "billing.h"

#define BUILD_TAG "PUMPPAY"

// WHAT IS SWITCHED ON. The ESP32 has about 300 KB of RAM and every network
// feature takes a share. The cloud upload needs a TLS handshake, which wants tens
// of KB free at once - and with Ethernet, the web page, WiFi and Bluetooth all
// running only ~13 KB was left, so every upload failed with "SSL - Memory
// allocation failed". PumpPay is operated from the phone app over Bluetooth and
// reports to the cloud over WiFi, so the two features it does not need are off.
//
// ETHERNET: the LAN8720 driver reserves its packet buffers as soon as the chip is
// found, cable or not. Set to 1 to use a wired network (then expect to lose the
// cloud upload unless something else is freed).
#define ENABLE_ETHERNET 0

// WEB PAGE on port 80: a second way to see the readings from any browser on the
// same network. The phone app shows the same things.
#define ENABLE_WEBUI 0

// Periodic measurement dump on the serial monitor. Telemetry, not debug
// output: it carries the diagnostics that calibration depends on - raw pin
// swing, sample counts, the current-channel zero. Set to 0 for a quiet build.
#define ENABLE_METER_REPORT 1
constexpr uint32_t METER_REPORT_MS = 5000;

static void printBanner() {
  Serial.println();
  Serial.println("=========================");
  Serial.println(BUILD_TAG);
  Serial.print("compiled ");
  Serial.print(__DATE__);
  Serial.print(" ");
  Serial.println(__TIME__);
  Serial.println("=========================");
}

// Walks the 7-bit address space and reports every device that acknowledges.
// Addresses below 0x08 and above 0x77 are reserved by the I2C spec and are
// never probed - some parts misbehave if addressed there.
//
// One bus, shared: the ADS1015 answers at 0x48 and the LCD backpack at 0x27.
// Run this after wiring anything new and it names the address to use rather
// than leaving it to a datasheet guess.
static void scanI2C() {
  Serial.println("[i2c] scanning GPIO 32/33 ...");

  uint8_t found = 0;
  for (uint8_t addr = 0x08; addr <= 0x77; addr++) {
    Wire.beginTransmission(addr);
    if (Wire.endTransmission() == 0) {
      Serial.print("[i2c]   0x");
      if (addr < 0x10) Serial.print('0');
      Serial.print(addr, HEX);
      Serial.println(" answered");
      found++;
    }
  }

  if (found == 0) {
    Serial.println("[i2c]   nothing answered - a silent bus is usually missing");
    Serial.println("[i2c]   PULL-UPS, not a missing device: 4.7k to 3.3 V on both");
  }
}

// Drain the button queue - a while loop, because releasing one button while
// pressing another produces two events in the same tick. Events go to the
// display, which owns what is on screen and what each button means there.
static void buttonTick() {
  Buttons::Event  evt;
  Buttons::Button btn;

  while (Buttons::poll(evt, btn)) {
    Display::onButton(evt, btn);

    if (evt == Buttons::Event::Press) {
      Serial.print("[btn] ");
      Serial.print(Buttons::name(btn));
      Serial.print("  (");
      Serial.print(Buttons::millivolts());
      Serial.println(" mV)");
    }
  }
}

#if ENABLE_METER_REPORT
static void meterReportTick() {
  static uint32_t lastReport = 0;

  if (millis() - lastReport < METER_REPORT_MS) return;
  lastReport = millis();
  PowerMeter::printAll();
}
#endif

// Power-on proof of life: the two LEDs flash alternately, then go dark. With
// no heartbeat pin to spare on this board, this is the only way to see at a
// glance that firmware started. Runs once, from setup(), where blocking is
// allowed - about 1.7 s.
static void selfTest() {
  for (uint8_t i = 0; i < 10; i++) {
    digitalWrite(PIN_LED_PUMP,  (i % 2) == 0 ? HIGH : LOW);
    digitalWrite(PIN_LED_SPARE, (i % 2) == 0 ? LOW : HIGH);
    delay(120);
  }
  digitalWrite(PIN_LED_PUMP, LOW);
  digitalWrite(PIN_LED_SPARE, LOW);
  delay(500);
}

void setup() {
  Serial.begin(115200);
  delay(200);            // let the USB port settle before the first print
  printBanner();

  // PumpPay uses Classic Bluetooth serial only, never BLE. The BLE half of the
  // controller still holds RAM unless it is handed back before Bluetooth starts,
  // and this board needs that RAM: with BLE kept, only about 13 KB stays free once
  // WiFi, Bluetooth and the web page are up, which is too little for the TLS
  // handshake the cloud upload needs ("SSL - Memory allocation failed").
  // Must run before Bluetooth::begin(), and cannot be undone until a reboot.
  const esp_err_t bleRelease = esp_bt_controller_mem_release(ESP_BT_MODE_BLE);
  Serial.print("[mem] BLE release: ");
  Serial.print(bleRelease == ESP_OK ? "ok" : "refused");
  Serial.print("  free heap ");
  Serial.println(ESP.getFreeHeap());

  Settings::begin();     // before anything that reads a stored value
  Pump::begin();         // loads run counters; relay held low (line 1)
  Billing::begin();      // after Pump: reads its run total; owns the relay from here
  PowerMeter::begin();
  scanI2C();             // report every device on the shared bus
  Buttons::begin();
  Display::begin();

  Serial.print("[mem] before network: ");
  Serial.println(ESP.getFreeHeap());
#if ENABLE_ETHERNET
  Net::begin();
#endif
  WifiNet::begin();
#if ENABLE_WEBUI
  WebUI::begin();
#endif
  Bluetooth::begin();
  Serial.print("[mem] after bluetooth: ");
  Serial.println(ESP.getFreeHeap());
  Cloud::begin();

  Serial.print("[mem] free heap ");
  Serial.print(ESP.getFreeHeap());
  Serial.print(" bytes, largest block ");
  Serial.println(ESP.getMaxAllocHeap());

  selfTest();
}

void loop() {
  // Network first: the two transports and the server that rides on them.
#if ENABLE_ETHERNET
  Net::tick();
#endif
  WifiNet::tick();
#if ENABLE_WEBUI
  WebUI::tick();
#endif
  Bluetooth::tick();
  Cloud::tick();

  // Measurement before policy, so the run decision each pass is made on this
  // pass's data rather than the previous one's.
  PowerMeter::tick();
  Pump::tick();
  Billing::tick();       // after Pump: spends what it just counted, then sets the relay

  Buttons::tick();
  buttonTick();
  Display::tick();

#if ENABLE_METER_REPORT
  meterReportTick();
#endif
}
