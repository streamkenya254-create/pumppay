#pragma once
#include <Arduino.h>

/*
  Prepaid billing - spends the paid-for pumping time and works the relay.

  THE RULE. A customer buys pumping time. While the pump runs, that time is
  spent. When none is left the dual relay changes over from LINE 1, which feeds
  the pump, to LINE 2, which does not. A top-up changes it back.

  WHERE THE BALANCE LIVES. On the server. It holds two numbers per controller:
  how much has ever been CREDITED (top-ups) and how much the controller has said
  it has SPENT. The board keeps the credited figure from its last upload and its
  own running SPENT total, and works out what is left itself as credited minus
  spent. That lets it keep counting - and keep the pump honest - with no network.
  Spent is reported on every upload, and the server only ever raises it, so
  reflashing the board or wiping its memory cannot win back time already used.

  SAFETY, because a relay that wrongly cuts someone's water is the failure that
  matters here. Every rule below errs toward leaving the pump powered:

    * OFF BY DEFAULT. Nothing is enforced until it is switched on deliberately.
    * NEVER LOCKS ON NO INFORMATION. Until the board has heard a balance from the
      server at least once it does not know what was paid for, and does not lock.
    * RESTING POSITION IS LINE 1. The coil is de-energized on line 1, so a crash,
      a brownout or a lost 3.3 V rail leaves the pump fed - the relay returns to
      the customer-friendly side by itself.
    * A MINIMUM DWELL between changes, so a balance hovering at zero cannot make
      the relay chatter.
    * TURNING IT OFF always returns to line 1.

  Spending counts only while enforcement is on: time pumped before it was
  switched on is not charged for.
*/
namespace Billing {

void begin();
void tick();

bool enforcing();
void setEnforcing(bool on);

// True once a balance has been received from the server (and kept in flash).
bool known();

// Seconds of pumping left. -1 when billing is off or no balance is known yet.
int32_t remainingSecs();

// The relay is on line 2: the token is used up and the pump is cut off.
bool locked();

// Manual switch-off from the app: holds the relay on line 2 (pump cut off)
// regardless of the token. NOT saved - a reboot always returns the pump to
// line 1, so a crash or power cut can never leave someone's water switched off.
void setManualOff(bool off);
bool manualOff();

// Lifetime seconds spent while enforcing. Reported to the server on every upload.
uint32_t spentSecs();

// Called by the cloud task with the server's answer: everything credited so far,
// and the server's own record of what was spent. Safe to call from another task.
void onServer(uint32_t creditedSecs, uint32_t serverSpentSecs);

}  // namespace Billing
