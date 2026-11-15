#pragma once
#include <Arduino.h>

/*
  Built-in web page - the phone view, with PIN-gated control.

  There is no app to write and no app to install. The board serves a page on
  port 80; a phone on the same network opens its IP in a browser and sees the
  readings. That works identically on iPhone and Android, which is the whole
  reason to do it this way rather than over Bluetooth.

  Reachable over EITHER transport. Ethernet and WiFi both land on the same
  network stack, so the server listens on whichever has an address - and on
  both at once if both are up. Nothing here has to know or care which.

  Endpoints:
      GET  /            the page - static HTML, served once
      GET  /api         the readings as JSON, polled by the page each second
      GET  /api/hist    last 7 days of pump run time, one value per hour
      POST /api/wifi, /api/pin, /api/button   settings, behind the PIN

  Splitting the page from the data is what keeps it cheap. The HTML never
  changes, so it is sent once and cached; only the small JSON is re-fetched. A
  page that reloaded itself whole every second would cost far more of the
  board's time for the same result.

  CONTROL, AND WHAT THE PIN IS ACTUALLY WORTH. The settings change how the
  board joins a network, so they are behind the control PIN and a rate limit: five
  consecutive wrong PINs lock the endpoint for a minute, because four digits is
  ten thousand guesses and that is minutes of work if nothing slows it down.

  Be clear about the limits, because they are real:

    - the page is plain HTTP, so the PIN crosses the network in the clear;
    - it is one shared PIN, so it cannot be revoked for one person and the log
      can only ever record an IP address, not a who.

  It is a guard against accident and against a casual visitor, not against
  anyone determined. On a network you do not control, set ALLOW_WEB_CONTROL to
  0 and use the LCD or a paired phone instead.

  The PIN is sent by POST, never as a query string - a PIN in a URL ends up in
  browser history, in the back button, and in any proxy log on the way.

  BLOCKING: handleClient() sends over TCP, which is not instant. Nothing here
  runs long enough to matter against a 2 s transfer confirm or a 200 ms
  measurement window, but it is the one part of loop() that talks to the
  outside world and cannot be bounded from inside this module.
*/
namespace WebUI {

void begin();
void tick();

// True once the server has been started. It is started whenever either
// transport is up, so this also answers "is the board reachable at all".
bool running();

}  // namespace WebUI
