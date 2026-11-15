#pragma once
#include <Arduino.h>

/*
  The one place that turns the board's state into JSON.

  Bluetooth and the web page used to build the same object by hand, each with
  its own copy of the field names. They drifted, and an app parsing one could
  not be trusted to parse the other. Both now call these, so there is exactly
  one shape.

  statusJson - one object, sent every second:
    {"state":"PUMPING",
     "pump":{"v":230.1,"a":4.20,"va":966.4,"vok":1,"aok":1},
     "run":{"cur":1260,"today":5400,"total":812000,"starts":143},
     "t":1767225600,"tz":180,"adc":1,"eth":"","wifi":"","up":3600,"lcd":["..",".."]}

  histJson - the last 168 hours, oldest first - minutes run, and apparent energy
  in VAh, per hour:
    {"hist":{"t":491452,"tz":180,"m":[0,0,12,60,...],"e":[0,0,210,950,...]}}
  "t" is the UTC hour number (epoch / 3600) of the LAST entry, so a client can
  work out which clock hour every value belongs to. t is 0 while the board has
  no idea what time it is, and "m" is then empty.

  Both write into the caller's buffer, always NUL-terminated, and return the
  length. Output that would not fit is cut short rather than overrun.
*/
namespace Report {

size_t statusJson(char* out, size_t n);
size_t histJson(char* out, size_t n);

// Dotted-quad of whichever transports have an address, "" when down.
void ethText(char* out, size_t n);
void wifiText(char* out, size_t n);

}  // namespace Report
