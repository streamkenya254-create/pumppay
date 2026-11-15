#pragma once
#include <Arduino.h>
#include <ETH.h>

/*
  LAN8720 Ethernet over RMII.

  The PHY address is auto-detected at boot: address 1 is tried, then 0. Boards
  disagree because the address is set by strapping resistors, and trying both
  removes the guesswork.

  IT DOES NOT RETRY AFTER BOOT, and that is deliberate. A failed ETH.begin()
  allocates the EMAC interrupt and does not return it, so a periodic retry
  leaks an interrupt slot every time round. The original version retried every
  10 seconds and eventually reported "No free interrupt inputs" - a symptom of
  its own retry loop, which buried the real PHY error underneath it. Nothing
  about the wiring changes while the board runs, so a retry could never have
  succeeded anyway. ETH.end() between the two address attempts is what makes
  even those two possible.

  If Ethernet is down, WifiNet is the other route onto the network and WebUI
  serves on whichever comes up.
*/
namespace Net {

void begin();
void tick();

bool linkUp();
bool hasAddress();
IPAddress ip();

// Which PHY address answered, or -1 if none did. Worth recording in a board
// log: it is a property of the hardware, not of the firmware.
int32_t phyAddress();

}  // namespace Net
