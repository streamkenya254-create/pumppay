# PumpPay - Android app

A native Bluetooth app for the PumpPay controller. It shows the pump line live,
whether the pump is running and for how long, and turns the run history into
usage insights: how long the pump ran, at what hours, and an estimate of the
water it moved.

Package `ke.tafiti.pumppay`. Kotlin + Jetpack Compose, dark, minimal.

## What it shows

- **Dashboard** - one line: the pump supply (volts, amps, VA), an animated feeder
  that only moves while the pump runs, and the pump itself - `PUMPING`, `IDLE` or
  `NO SIGNAL`, how long this run has lasted, and run time today / total / starts.
- **Water usage** - Today / 7 days / 30 days: run time, change versus the previous
  period, pump days, average per day, busiest time of day, a bar chart, a
  "when you usually pump" chart by hour of day, and plain-language insights
  (e.g. most pumping happens in the morning; solar is strongest 10am-3pm).
- **Panel** - the controller's LCD mirrored, with its four buttons.
- **Network**, **Device settings** (WiFi, web PIN), **Installation** (site name,
  pump flow rate), **Shop** / **About us** (the Tafiti website, in-app), **Help**.

A missing reading shows as a dash, never `0.00`. A zero and a "not measured"
mean opposite things on a meter.

## Litres are an estimate

The controller measures how long the pump runs - it has no flow meter. Enter the
pump's flow rate (litres per minute) under **Installation** and the app multiplies
run time by it. Without that number the app shows run time only and does not
invent litres. 1 m3/h is about 16.7 L/min.

## How the history works

The controller keeps the last 7 days of run time, one value per clock hour. Each
time the app connects it receives those 168 values and merges them into a store
on the phone (keyed by UTC hour, 90 days kept), so the 30-day view fills in just
by using the app. **If the app is not opened for more than a week, the days in
between are lost** - the controller has already overwritten them.

The controller has no clock. The app sends it the time and the phone's time zone
on every connect (`time <unixSeconds> <zoneMinutes>`); until it has been
connected once, "today" and the hourly charts are blank rather than guessed.

## Building it

1. Install **Android Studio**. It brings the SDK, Gradle and everything else.
2. **File > Open**, choose this `android` folder. Let it sync.
3. On the phone: **Settings > About phone**, tap *Build number* seven times, then
   **Developer options > USB debugging** on.
4. Plug the phone in, pick it in the device dropdown, press **Run**.

`local.properties` holds the SDK path for *your* machine - Android Studio rewrites
it on open, so a stale path in it is harmless.

The build output is directed outside the project (see `build.gradle.kts`,
`PumpPayBuild`) because this folder is synced by OneDrive, which holds files open
while Gradle rewrites thousands of intermediates.

## Pairing

Pair the controller once in **Android Settings > Bluetooth**, not in the app. It
appears as `PUMPPAY`; the pairing code, if asked for, is usually `1234` or `0000`.
That code is Android's and is **not** the controller's web PIN.

The app deliberately does not scan or pair - it uses bonded devices only, so it
never asks for the Bluetooth scan permission.

## Why the board sends JSON

The app sends `json on` the moment it connects, and the board switches from
human-readable status lines to one JSON object per second, with the same shape the
web page's `/api` uses. See `../firmware/README.md` for the exact fields. A second
request, `hist`, returns the 7-day hourly run time; the app repeats it once a
minute while connected so the current hour keeps growing.

## What it will not do

**iPhone.** Apple does not allow third-party apps to use Bluetooth Classic SPP.
iPhone users have the controller's web page.

**Work without pairing.** Bonded devices only, by design.

**Switch the pump.** PumpPay measures and reports; it does not control the pump.

## Is this better than the web page?

Only when there is no network. The web page needs nothing installed and works on
iPhone. This app's advantages are that Bluetooth needs no network at all, and that
it keeps a longer usage history (90 days) on your phone than the controller's week.
