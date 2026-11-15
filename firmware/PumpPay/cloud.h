#pragma once
#include <Arduino.h>

/*
  Cloud upload - posts the board's readings to a Firebase Cloud Function.

  WHY A FUNCTION AND NOT FIRESTORE DIRECTLY. The Firebase project this reports
  to keeps its database closed to every device and browser - all access goes
  through server code with admin rights. Loosening that for a pump would open
  the rest of the database to the same callers. A small function that checks a
  shared key and writes only pump data keeps the project exactly as locked as it
  was.

  WHEN IT SENDS. Only with WiFi (or Ethernet) up, a key stored and the clock
  known. Every 60 s while the pump runs, every 5 minutes while it is idle, and
  once shortly after the network comes up.

  NEVER BLOCKS loop(). A TLS handshake takes seconds, which would stall the
  measurement window. tick() only builds the small JSON body; a separate task on
  the other core does the network call.

  THE KEY travels in the request body over TLS and is never printed, logged or
  sent anywhere else. It is checked against the bundled root certificates, so a
  server that merely claims to be the function is refused.
*/
namespace Cloud {

void begin();
void tick();

// True when an address and a key are stored (upload will be attempted).
bool configured();

// HTTP status of the last attempt: 200 ok, 401 key refused, 0 never tried,
// negative for a network or TLS failure.
int  lastStatus();

// Seconds since the last successful upload, or 0xFFFFFFFF if none yet.
uint32_t secondsSinceOk();

// Upload now instead of waiting for the next scheduled one. Used right after a
// top-up, so the board hears about the new balance in seconds, not minutes. Safe
// to call from any task; Bluetooth drops for a few seconds while it uploads.
void syncNow();

// Stable per-board name, "PP-" and six hex digits from the chip's own ID.
const char* deviceId();

}  // namespace Cloud
