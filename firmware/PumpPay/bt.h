#pragma once
#include <Arduino.h>

/*
  Bluetooth Classic serial (SPP) - readings and control from a phone with no
  network at all.

  Pair with "PUMPPAY", open any Bluetooth terminal app, and the board
  streams a status line once a second. Single-letter commands come back the
  other way.

  ANDROID ONLY, and this is a platform limit, not a firmware one. Apple does
  not let third-party apps use Bluetooth Classic SPP, so an iPhone cannot open
  this at all. iPhone users have the web page over WiFi or Ethernet, which is
  the better route anyway - richer, no pairing, no app to install. Bluetooth
  earns its place only where there is no network to join.

  COSTS. BluetoothSerial is roughly 600 KB of flash on top of WiFi, which is
  why this build needs the "Huge APP (3 MB No OTA)" partition scheme rather
  than the default. Set it in the IDE under Tools > Partition Scheme, or the
  upload will not fit. It also shares one radio with WiFi: both work together,
  but throughput on each suffers while the other is busy.

  CONTROL IS GATED. Pairing is a weak gate, but it is a gate - unlike an open
  web page - so settings can be changed here and are gated behind a PIN on the web UI. Set BT_ALLOW_CONTROL to
  0 in bt.cpp for a listen-only build.
*/
namespace Bluetooth {

void begin();
void tick();

// True while a phone is paired and connected.
bool connected();

// False when the module was compiled out or failed to start.
bool enabled();

// Switch the whole Bluetooth stack off, and back on. The cloud upload needs a
// large block of RAM for its TLS handshake and the Bluetooth stack is the single
// biggest thing holding it; with both resident the board has ~16 KB free and the
// handshake fails. So Cloud pauses Bluetooth for the few seconds an upload takes.
// A connected phone is dropped for that time and its auto-connect rejoins.
// Both must be called from loop(), never from another task.
void pause();
void resume();

}  // namespace Bluetooth
