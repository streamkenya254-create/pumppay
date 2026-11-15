#include "ethernet.h"
#include "board_pins.h"

namespace Net {
namespace {

constexpr eth_phy_type_t   PHY_TYPE  = ETH_PHY_LAN8720;
constexpr eth_clock_mode_t CLK_MODE  = ETH_CLOCK_GPIO17_OUT;

// PHY addresses to try, in order. The LAN8720's address is set by strapping
// resistors and boards disagree: 1 is the common default, 0 is the other.
// Trying both costs one boot-time attempt and removes an entire class of
// "check PHY_ADDR" guesswork.
constexpr int32_t PHY_ADDR_CANDIDATES[] = { 1, 0 };
constexpr size_t  PHY_ADDR_COUNT = sizeof(PHY_ADDR_CANDIDATES) / sizeof(int32_t);

// How often to re-print link state when nothing has changed.
constexpr uint32_t HEARTBEAT_MS = 10000;

bool     s_started     = false;
int32_t  s_phyAddr     = -1;      // which candidate actually answered
bool     s_lastLink    = false;
uint32_t s_lastReport  = 0;

void report(const char* why) {
  Serial.print("[eth] ");
  Serial.print(why);
  if (linkUp()) {
    Serial.print(" - link UP, IP ");
    Serial.println(ETH.localIP());
  } else {
    Serial.println(" - link DOWN");
  }
  s_lastReport = millis();
}

bool ethBegin(int32_t addr) {
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  return ETH.begin(PHY_TYPE, addr,
                   PIN_ETH_MDC, PIN_ETH_MDIO, PIN_ETH_POWER, CLK_MODE);
#else
  // ESP32 Arduino core 2.x takes the same arguments in a different order.
  return ETH.begin(addr, PIN_ETH_POWER,
                   PIN_ETH_MDC, PIN_ETH_MDIO, PHY_TYPE, CLK_MODE);
#endif
}

}  // namespace

void begin() {
  Serial.print("[eth] MDC ");
  Serial.print(PIN_ETH_MDC);
  Serial.print(", MDIO ");
  Serial.print(PIN_ETH_MDIO);
  Serial.print(", power ");
  Serial.print(PIN_ETH_POWER);
  Serial.println(", trying PHY addresses 1 then 0");

  // Each attempt allocates the EMAC interrupt, and a FAILED begin() does not
  // give it back. That is what turned the old 10-second retry loop into a
  // resource leak: the first attempt failed honestly on the PHY, and every
  // attempt after it failed with "No free interrupt inputs" instead - burying
  // the real error under a symptom of the retry itself.
  //
  // ETH.end() between attempts releases it, which is what makes trying a
  // second address possible at all.
  for (size_t i = 0; i < PHY_ADDR_COUNT; i++) {
    const int32_t addr = PHY_ADDR_CANDIDATES[i];

    Serial.print("[eth]   PHY addr ");
    Serial.print(addr);
    Serial.print(" ... ");

    if (ethBegin(addr)) {
      s_started = true;
      s_phyAddr = addr;
      Serial.println("ok");
      break;
    }

    Serial.println("no answer");
    ETH.end();     // release the EMAC interrupt before the next attempt
  }

  if (!s_started) {
    // Deliberately terminal. There is no periodic retry, because a retry
    // cannot succeed: nothing about the wiring changes while the board runs,
    // and each attempt costs an interrupt slot the system never gets back.
    // Fix the hardware and reboot.
    Serial.println("[eth] FAILED on every PHY address - Ethernet is off for");
    Serial.println("[eth]   this boot. No retry: nothing changes at runtime and");
    Serial.println("[eth]   each attempt costs an interrupt slot permanently.");
    Serial.println("[eth]   Check the 50 MHz REF_CLK on GPIO 17, the PHY reset");
    Serial.println("[eth]   pin, and that the PHY has power (PIN_ETH_POWER=-1");
    Serial.println("[eth]   assumes it is hard-wired on).");
  }

  s_lastLink   = false;
  s_lastReport = millis();
}

void tick() {
  if (!s_started) return;   // said its piece in begin(); nothing to add

  const bool link = ETH.linkUp();

  if (link != s_lastLink) {
    s_lastLink = link;
    report(link ? "link came up" : "link dropped");
    return;
  }

  if (millis() - s_lastReport >= HEARTBEAT_MS) {
    report("status");
  }
}

bool linkUp() {
  return s_started && ETH.linkUp();
}

bool hasAddress() {
  // Spelled with == and ! because IPAddress does not define operator!= on
  // every core version.
  return linkUp() && !(ETH.localIP() == IPAddress(0, 0, 0, 0));
}

IPAddress ip() {
  return s_started ? ETH.localIP() : IPAddress(0, 0, 0, 0);
}

int32_t phyAddress() { return s_phyAddr; }

}  // namespace Net
