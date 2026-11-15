#pragma once
#include <Arduino.h>
#include <WiFi.h>

/*
  WiFi station - the second way onto the network, alongside Ethernet.

  Both can run at once. WebUI serves on whichever has an address, so the phone
  reaches the board either way and nothing has to choose between them.

  DISABLED UNTIL CREDENTIALS ARE SET. WIFI_SSID at the top of wifi_net.cpp is
  empty by default, and an empty SSID means the radio is never started - so a
  board with no credentials behaves exactly as it did before WiFi existed,
  rather than spending every boot failing to join a network that was never
  named.

  The file is wifi_net.h and not wifi.h on purpose: Windows filenames are
  case-insensitive, so a wifi.h beside the sketch would shadow the core's
  WiFi.h and break the include in ways that are miserable to diagnose.

  ADC NOTE. The radio makes ADC2 unusable. That costs this board nothing -
  every analog input here is already on ADC1 (GPIO 34..39), which is why
  board_pins.h insists on it - but it is the reason that rule exists, and it
  becomes real the moment this module is enabled.
*/
namespace WifiNet {

void begin();
void tick();

// True once associated AND holding an IP - not merely "radio started".
bool connected();
IPAddress ip();

// False when no SSID is configured, so callers can distinguish "switched off"
// from "trying and failing".
bool enabled();

}  // namespace WifiNet
