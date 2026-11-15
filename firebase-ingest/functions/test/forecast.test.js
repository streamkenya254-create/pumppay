const test = require("node:test");
const assert = require("node:assert/strict");
const sun = require("../forecast-core");

const { forecast } = require("./helpers/owm");

const LAT = -0.05, LON = 34.8;                                    // near Kisumu
const now = new Date("2026-11-15T03:00:00Z");                     // 06:00 local

const mins = (hhmm) => +hhmm.slice(0, 2) * 60 + +hhmm.slice(3);

test("the sun is overhead at noon on the equator at the equinox, and gone at midnight", () => {
  const noon = Date.UTC(2026, 2, 20, 12, 7);                      // solar noon at longitude 0 is about 12:07 UTC on 20 March
  assert.ok(sun.cosZenith(0, 0, noon) > 0.995);
  assert.ok(Math.abs(sun.clearSkyGhi(0, 0, noon) - 1037) < 15);
  assert.equal(sun.clearSkyGhi(0, 0, Date.UTC(2026, 2, 20, 0, 0)), 0);
});

test("cloud dims the sun the way Kasten and Czeplak measured", () => {
  assert.equal(sun.cloudFactor(0), 1);
  assert.equal(sun.cloudFactor(1), 0.25);
  assert.ok(sun.cloudFactor(0.5) > 0.9, "half cloud barely dims");
  assert.equal(sun.cloudFactor(7), 0.25);                          // out-of-range input is held to full cover
});

test("a clear day is good sun with a full day of pumping", () => {
  const o = sun.solarOutlook(forecast(() => 0), { lat: LAT, lon: LON, now });
  assert.equal(o.days[0].name, "Today"); assert.equal(o.days[1].name, "Tomorrow");
  assert.equal(o.days[0].date, "2026-11-15");
  assert.equal(o.days[0].rating, "Good sun");
  assert.ok(o.days[0].goodHours >= 7 && o.days[0].goodHours <= 10, "good hours " + o.days[0].goodHours);
  assert.equal(o.days[0].goodHours, o.days[0].clearSkyHours);
  assert.ok(o.days[0].sunKwhM2 > 5.5 && o.days[0].sunKwhM2 < 8.5, "kWh/m2 " + o.days[0].sunKwhM2);
  assert.equal(o.days[0].bars.length, 13);
  assert.ok(o.days[0].bars[6] > 90 && o.days[0].bars[0] < 30, "bars peak at midday");
  assert.equal(o.tzOffsetMin, 180); assert.equal(o.nowLocalMin, 360);
  assert.ok(o.days.length >= 4);
});

test("a fully overcast day is poor sun", () => {
  const o = sun.solarOutlook(forecast(() => 100), { lat: LAT, lon: LON, now });
  assert.equal(o.days[0].rating, "Poor sun");
  assert.ok(o.days[0].goodHours < 3);
  assert.equal(o.days[0].cloudPct, 100);
  assert.ok(o.days[0].clearSkyHours >= 7);
});

test("rain is added up for the day and for the next 24 hours", () => {
  const o = sun.solarOutlook(forecast(() => 80, (h, d) => (d === 0 && h >= 15 ? 4 : 0), (h, d) => (d === 0 ? 0.7 : 0.1)), { lat: LAT, lon: LON, now });
  assert.ok(o.days[0].rainMm >= 8, "today " + o.days[0].rainMm);
  assert.equal(o.days[0].rainChancePct, 70);
  assert.ok(o.rainNext24Mm >= 8);
  assert.equal(o.days[1].rainMm, 0);
});

test("a small panel array gives the pump fewer good hours than a big one", () => {
  const fc = forecast(() => 0);
  const big = sun.solarOutlook(fc, { lat: LAT, lon: LON, now, pumpWatts: 750, panelWp: 1500 });
  const small = sun.solarOutlook(fc, { lat: LAT, lon: LON, now, pumpWatts: 750, panelWp: 750 });
  assert.equal(big.sized, true);
  assert.ok(small.days[0].goodHours < big.days[0].goodHours - 1, `${small.days[0].goodHours} vs ${big.days[0].goodHours}`);
});

test("on a clear day the best two hours sit around solar noon", () => {
  const o = sun.solarOutlook(forecast(() => 0), { lat: LAT, lon: LON, now });
  const w = sun.bestWindow(o.days[0], 120, 0);
  assert.ok(mins(w.start) >= 10 * 60 + 30 && mins(w.start) <= 12 * 60, "start " + w.start);
  assert.equal(mins(w.end) - mins(w.start), 120);
  assert.equal(w.sunShare, 1);
});

test("a cloudy morning moves the pumping to the clear afternoon", () => {
  const o = sun.solarOutlook(forecast((h) => (h < 13 ? 100 : 0)), { lat: LAT, lon: LON, now });
  const w = sun.bestWindow(o.days[0], 120, 0);
  assert.ok(mins(w.start) >= 12 * 60 + 30, "start " + w.start);
});

test("late in the day the window starts from now and admits it is second best", () => {
  const o = sun.solarOutlook(forecast(() => 0), { lat: LAT, lon: LON, now });
  const w = sun.bestWindow(o.days[0], 120, 15 * 60);
  assert.ok(mins(w.start) >= 15 * 60);
  assert.ok(w.sunShare < 0.75, "share " + w.sunShare);
  assert.equal(sun.bestWindow(o.days[0], 120, 19 * 60), null, "no two hours left after 19:00");
});

test("bad or empty forecasts give null, not a crash", () => {
  assert.equal(sun.solarOutlook(null, { lat: LAT, lon: LON, now }), null);
  assert.equal(sun.solarOutlook({ list: [] }, { lat: LAT, lon: LON, now }), null);
  assert.equal(sun.solarOutlook(forecast(() => 0), { now }), null);
  assert.equal(sun.bestWindow(null, 60), null);
  assert.equal(sun.publicOutlook(null), null);
});

test("the copy sent to the app leaves the half-hour working out", () => {
  const p = sun.publicOutlook(sun.solarOutlook(forecast(() => 20), { lat: LAT, lon: LON, now }));
  assert.ok(p.days.every((d) => !("steps" in d) && Array.isArray(d.bars)));
  assert.ok(JSON.stringify(p).length < 3000);
});
