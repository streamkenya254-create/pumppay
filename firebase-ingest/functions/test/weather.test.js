const test = require("node:test");
const assert = require("node:assert/strict");
const sun = require("../forecast-core");
const { forecast, mildAir } = require("./helpers/owm");

const LAT = -0.05, LON = 34.8;
const now = new Date("2026-11-15T03:00:00Z");                     // 06:00 in Kenya
const outlook = (cloudAt, rainAt, popAt, airAt = mildAir) => sun.solarOutlook(forecast(cloudAt, rainAt, popAt, now, airAt), { lat: LAT, lon: LON, now });
const air = (over) => (h, d) => ({ ...mildAir(h), ...over(h, d) });

test("the outlook carries each day's temperature, wind and humidity", () => {
  const d = outlook(() => 10).days[1];
  assert.equal(d.tMaxC, 27); assert.equal(d.tMinC, 16);
  assert.equal(d.windMaxKmh, 7);                                  // 2 m/s
  assert.equal(d.humidityPct, 60);
  assert.equal(d.rainWhen, null);
  assert.match(d.bestSun, /^1[01]:[03]0 to 1[34]:[03]0$/);
});

test("a clear, calm, dry day: no rain, good sun, a good day to spray - and nothing else", () => {
  const ins = sun.dayInsights(outlook(() => 0).days[1]);
  assert.equal(ins[0], "No rain expected.");
  assert.match(ins[1], /^Good sun for pumping: about [\d.]+ hours, strongest \d\d:\d\d to \d\d:\d\d\.$/);
  assert.match(ins[2], /good day for spraying/);
  assert.equal(ins.length, 3);
});

test("heavy rain leads, and says when it falls", () => {
  const o = outlook(() => 90, (h, d) => (d === 1 && h > 12 && h <= 18 ? 9 : 0), (h, d) => (d === 1 ? 0.9 : 0));
  const ins = sun.dayInsights(o.days[1]);
  assert.match(ins[0], /^Heavy rain likely: about 18 mm, mostly in the afternoon\. Leave the pump off/);
  assert.ok(!ins.some((x) => /spraying/.test(x)), "no spraying advice on a wet day");
});

test("light rain and bare showers are told apart", () => {
  const some = sun.dayInsights(outlook(() => 60, (h, d) => (d === 1 && h === 9 ? 4 : 0), () => 0.6).days[1]);
  assert.match(some[0], /^Some rain likely: about 4 mm, mostly in the morning/);
  const showers = sun.dayInsights(outlook(() => 60, () => 0, () => 0.7).days[1]);
  assert.match(showers[0], /^Showers possible \(70% chance\)/);
});

test("heat, cold and wind are each mentioned only when they matter", () => {
  const hot = sun.dayInsights(outlook(() => 0, undefined, undefined, air((h) => ({ temp: mildAir(h).temp + 7 }))).days[1]);
  assert.ok(hot.some((x) => /^Hot, up to 34°C/.test(x)));
  const cold = sun.dayInsights(outlook(() => 0, undefined, undefined, air((h) => ({ temp: mildAir(h).temp - 9 }))).days[1]);
  assert.ok(cold.some((x) => /^Cold night, down to 7°C/.test(x)));
  const windy = sun.dayInsights(outlook(() => 0, undefined, undefined, air(() => ({ wind: 9 }))).days[1]);
  assert.ok(windy.some((x) => /^Windy, up to 32 km\/h: do not spray/.test(x)));
  assert.ok(!windy.some((x) => /good day for spraying/.test(x)));
  const breezy = sun.dayInsights(outlook(() => 0, undefined, undefined, air(() => ({ wind: 6 }))).days[1]);
  assert.ok(breezy.some((x) => /^Breezy, up to 22 km\/h/.test(x)));
  const plain = sun.dayInsights(outlook(() => 0).days[1]);
  assert.ok(!plain.some((x) => /Hot|Cold|Windy|Breezy|Damp/.test(x)));
});

test("damp, mild weather warns of blight on tomato and potato, and of fungus on other crops", () => {
  const damp = outlook(() => 90, (h, d) => (d === 1 ? 1 : 0), () => 0.8, air((h) => ({ humidity: 92, temp: mildAir(h).temp - 3 }))).days[1];
  assert.ok(sun.dayInsights(damp, "tomato").some((x) => /blight weather for tomato/.test(x)));
  assert.ok(sun.dayInsights(damp, "potato").some((x) => /blight weather for potato/.test(x)));
  const maize = sun.dayInsights(damp, "maize");
  assert.ok(maize.some((x) => /fungal disease/.test(x)) && !maize.some((x) => /blight/.test(x)));
});

test("never more than four points a day, however much is going on", () => {
  const rough = outlook(() => 100, (h, d) => (d === 1 ? 3 : 0), () => 1, air((h) => ({ humidity: 95, wind: 10, temp: mildAir(h).temp + 8 }))).days[1];
  assert.equal(sun.dayInsights(rough, "maize").length, 4);
});

test("today in full, the later days by headline, and one line on the days ahead", () => {
  const w = sun.weatherInsights(outlook(() => 20, (h, d) => (d === 2 && h > 12 && h <= 18 ? 7 : 0), (h, d) => (d === 2 ? 0.9 : 0.1)), { crop: "maize" });
  assert.equal(w.source, "OpenWeather 5-day forecast");
  assert.equal(w.today.name, "Today");
  assert.ok(w.today.insights.length >= 2 && w.today.insights.length <= 4);
  assert.match(w.today.summary, /°C, dry, [\d.]+ h of pumping sun$/);
  assert.ok(w.later.length >= 3);
  assert.equal(w.later[0].name, "Tomorrow");
  assert.match(w.later[0].headline, /sun/i, "a dry day is headlined by its sun");
  assert.match(w.later[1].headline, /^Heavy rain likely: about 14 mm/, "a wet day is headlined by its rain");
  assert.match(w.ahead, new RegExp("^Wettest day ahead: " + w.later[1].name + ", about 14 mm\\. Best pumping day: "));
});

test("a dry spell ahead says the crop depends on the pump", () => {
  const w = sun.weatherInsights(outlook(() => 0));
  assert.match(w.ahead, /^No real rain in the next \d days, so the crop depends on the pump\./);
});

test("a forecast with no temperature or wind still gives rain and sun, and no outlook gives null", () => {
  const bare = sun.weatherInsights(sun.solarOutlook(forecast(() => 0), { lat: LAT, lon: LON, now }));
  assert.equal(bare.today.insights[0], "No rain expected.");
  assert.equal(bare.today.insights.length, 2);
  assert.match(bare.today.summary, /^dry, /);
  assert.equal(sun.weatherInsights(null), null);
});
