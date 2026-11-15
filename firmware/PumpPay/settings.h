#pragma once
#include <Arduino.h>

/*
  Persistent settings, stored in NVS - the ESP32's key/value area in flash.

  These used to be compile-time constants, which meant changing a WiFi password
  or a control PIN required a laptop, the toolchain and a reflash. They now
  survive a reboot and can be changed from the buttons, from Bluetooth or from
  the web page, so a commissioned board never has to be opened again to be
  re-pointed at a different network.

  DEFAULTS COME FROM THE CONSTANTS AT THE TOP OF settings.cpp, and are used
  only when nothing has ever been stored. A board flashed for the first time
  behaves exactly as a compile-time build would; the moment anything is saved,
  the stored value wins and keeps winning across reflashes. That last part is
  worth knowing: reflashing does NOT reset these. Call factoryReset() for that.

  NVS IS NOT ENCRYPTED unless flash encryption is enabled, which it is not
  here. Anyone who can read the flash can read the WiFi password and the PIN -
  the same exposure as hard-coding them. The gain is convenience and not
  secrecy: the credentials are no longer baked into a .bin that might be
  shared, but they are not protected on the board itself.

  Every setter validates and returns false rather than storing nonsense, so a
  mistyped Bluetooth command cannot leave the board with a PIN it will not
  accept or an SSID it cannot join.
*/
namespace Settings {

// Sizes are the protocol maxima, not arbitrary: 802.11 allows a 32-character
// SSID and WPA2 a 63-character passphrase.
constexpr size_t SSID_MAX = 32;
constexpr size_t PASS_MAX = 63;
constexpr size_t PIN_MAX  = 8;
constexpr size_t PIN_MIN  = 4;
constexpr size_t CLOUD_URL_MAX = 160;
constexpr size_t CLOUD_KEY_MAX = 64;
constexpr size_t CLOUD_KEY_MIN = 12;

void begin();

const char* wifiSsid();
const char* wifiPassword();
const char* controlPin();

// Returns false and changes nothing if the SSID is empty or either string is
// too long. An empty SSID cannot be set this way - use clearWifi(), so that
// "turn WiFi off" is always a deliberate act rather than a typo.
bool setWifi(const char* ssid, const char* password);
void clearWifi();

// Four to eight digits, digits only. Returns false otherwise.
bool setControlPin(const char* pin);

// True while the PIN is still the factory default - the web UI shouts about
// this at boot, because a default PIN nobody changed is the same as no PIN.
bool pinIsDefault();

// Cloud upload: the HTTPS address readings are posted to, and the shared key
// that proves the post came from a PumpPay board. Upload is on only when BOTH
// are set and WiFi is up; clearing the key turns it off. The key is never
// printed or sent back - not over Bluetooth, not on the serial monitor.
const char* cloudUrl();
const char* cloudKey();
bool setCloudUrl(const char* url);   // must be https:// and fit CLOUD_URL_MAX
bool setCloudKey(const char* key);   // CLOUD_KEY_MIN to CLOUD_KEY_MAX characters
void clearCloudKey();

// Which of the board's two measurement lines PumpPay reads: 1 or 2. Line 1 is
// the original pump channel, line 2 the other one the board was built with.
// Only one is read at a time; switching takes effect at once and survives a
// reboot. Anything else is rejected.
uint8_t measuredLine();
bool    setMeasuredLine(uint8_t line);

// Wipes everything back to the compile-time defaults. The only way to undo a
// stored setting, since reflashing will not.
void factoryReset();

}  // namespace Settings
