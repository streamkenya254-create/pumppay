/**
 * The whole server path on a desk: plan.js and soil.js with a stand-in Firestore and canned
 * answers from NASA, SoilGrids, the height service and OpenWeather. No network, no project.
 */
const { store, calls, fetchFrom } = require("./helpers/fake-firebase");      // must come first
const test = require("node:test");
const assert = require("node:assert/strict");

const farmland = require("./fixtures/soilgrids-farmland.json");
const town = require("./fixtures/soilgrids-town.json");
const climate = require("./fixtures/power-climatology.json");
const daily = require("./fixtures/power-daily.json");
const { forecast } = require("./helpers/owm");

const FAKE_KEY = "test-key-not-real";
process.env.OPENWEATHER_KEY = FAKE_KEY;
const { planForFarm, sunTiming, weatherFor } = require("../plan");
const { farmHealth, saveFarmPlace } = require("../soil");

const now = new Date("2026-11-15T03:00:00Z");                    // 06:00 in Kenya
const farm = { lat: -0.05, lon: 34.8, crop: "maize", plantedOn: "2026-09-01", areaM2: 4047, flowLpm: 40, efficiency: 0.9, soil: "clay_loam" };
const routes = (extra = []) => [
  ...extra,
  ["power.larc.nasa.gov/api/temporal/daily", daily],
  ["power.larc.nasa.gov/api/temporal/climatology", climate],
  ["rest.isric.org", farmland],
  ["opentopodata.org", { results: [{ elevation: 1216 }], status: "OK" }],
  ["openweathermap.org", forecast(() => 10)],
];
const count = (part) => calls.filter((u) => u.includes(part)).length;
const mins = (hhmm) => +hhmm.slice(0, 2) * 60 + +hhmm.slice(3);

test.beforeEach(() => { store.clear(); process.env.OPENWEATHER_KEY = FAKE_KEY; });

// ---- Weather insights ------------------------------------------------------------------------

test("the plan carries the weather insights: today in full, the later days by headline", async () => {
  const { mildAir } = require("./helpers/owm");
  fetchFrom(routes([["openweathermap.org", forecast(() => 10, (h, d) => (d === 2 ? 5 : 0), (h, d) => (d === 2 ? 0.9 : 0), now, mildAir)]]));
  const p = await planForFarm(farm, now);
  assert.equal(p.weather.today.name, "Today");
  assert.equal(p.weather.today.insights[0], "No rain expected.");
  assert.ok(p.weather.later.length >= 3);
  assert.match(p.weather.later[1].headline, /^Heavy rain likely/);
  assert.match(p.weather.ahead, /^Wettest day ahead/);
  assert.ok(JSON.stringify(p.weather).length < 4000, "small enough to hand to the assistant");
});

test("weather needs only a position, and with no key it is simply absent", async () => {
  fetchFrom(routes());
  const w = await weatherFor(-0.05, 34.8, now);
  assert.ok(w.weather && w.outlook && w.error === null);
  process.env.OPENWEATHER_KEY = "";
  store.clear();
  const none = await weatherFor(-0.05, 34.8, now);
  assert.deepEqual(none, { outlook: null, weather: null, error: null });
});

// ---- The watering plan with the sun forecast -------------------------------------------------

test("the plan carries the sun forecast and times the pumping by it", async () => {
  fetchFrom(routes());
  const p = await planForFarm(farm, now);
  assert.equal(p.ok, true);
  assert.equal(p.sun.source, "OpenWeather 5-day forecast");
  assert.ok(p.sun.days.length >= 4 && p.sun.days[0].name === "Today");
  assert.equal(p.sunError, null);
  assert.match(p.sunNote, /sun today/);
  if (p.minutes) {
    assert.equal(p.windowDay, "Today");
    assert.ok(mins(p.window.start) >= 6 * 60 && mins(p.window.start) <= 16 * 60, "starts in daylight: " + p.window.start);
  }
  assert.equal(count("openweathermap.org"), 1);
  assert.ok(calls.find((u) => u.includes("openweathermap.org")).includes("appid=" + FAKE_KEY));
});

test("the forecast is kept for an hour, so a second plan does not call OpenWeather again", async () => {
  fetchFrom(routes());
  await planForFarm(farm, now);
  await planForFarm(farm, now);
  assert.equal(count("openweathermap.org"), 1);
  assert.equal(count("temporal/daily"), 1, "NASA is cached too");
});

test("with no OpenWeather key the plan still works, on the fixed midday window", async () => {
  process.env.OPENWEATHER_KEY = "";
  fetchFrom(routes());
  const p = await planForFarm(farm, now);
  assert.equal(p.ok, true);
  assert.equal(p.sun, null);
  assert.equal(count("openweathermap.org"), 0);
  assert.equal(p.windowDay, undefined);
});

test("a refused key does not stop the plan, and the key is not echoed back", async () => {
  fetchFrom(routes([["openweathermap.org", { __status: 401 }]]));
  const p = await planForFarm(farm, now);
  assert.equal(p.ok, true);
  assert.equal(p.sun, null);
  assert.equal(p.sunError, "OpenWeather 401");
  assert.ok(!JSON.stringify(p).includes(FAKE_KEY));
});

test("OpenWeather being unreachable does not stop the plan or leak the address with the key", async () => {
  fetchFrom(routes([["openweathermap.org", new Error("getaddrinfo ENOTFOUND api.openweathermap.org?appid=" + FAKE_KEY)]]));
  const p = await planForFarm(farm, now);
  assert.equal(p.ok, true);
  assert.equal(p.sunError, "OpenWeather unreachable");
  assert.ok(!JSON.stringify(p).includes(FAKE_KEY));
});

test("a poor-sun day gives the pump fewer hours than a clear one", async () => {
  fetchFrom(routes([["openweathermap.org", forecast(() => 100)]]));
  const dull = await planForFarm(farm, now);
  store.clear();
  fetchFrom(routes([["openweathermap.org", forecast(() => 0)]]));
  const clear = await planForFarm(farm, now);
  assert.match(dull.sunNote, /Poor sun today/);
  assert.match(clear.sunNote, /Good sun today/);
  assert.ok(dull.sun.days[0].goodHours < clear.sun.days[0].goodHours);
  if (dull.needLitres > 0 && clear.needLitres > 0) assert.ok(dull.litres <= clear.litres, "less sun cannot pump more");
});

test("rain on the way is passed to the water model", async () => {
  fetchFrom(routes([["openweathermap.org", forecast(() => 90, (h, d) => (d === 0 ? 6 : 0), () => 0.9)]]));
  const p = await planForFarm(farm, now);
  assert.ok(p.sun.rainNext24Mm >= 20);
  assert.match(p.sunNote, /mm of rain is forecast/);
  if (p.action === "skip") assert.equal(p.litres, 0);
});

test("when today's sun has gone, the time given is tomorrow's", () => {
  const sun = require("../forecast-core");
  const evening = new Date("2026-11-15T15:30:00Z");                // 18:30 in Kenya
  const o = sun.solarOutlook(forecast(() => 0), { lat: -0.05, lon: 34.8, now: evening });
  const t = sunTiming(o, 120);
  assert.equal(t.windowDay, "Tomorrow");
  assert.match(t.sunNote, /tomorrow's best time/);
  assert.ok(mins(t.window.start) >= 10 * 60 && mins(t.window.start) <= 12 * 60);
  assert.equal(sunTiming(o, 0).window, undefined, "nothing to time when no pumping is needed");
});

// ---- Soil health and rotation ----------------------------------------------------------------

test("farm health: soil score and rotation from the map, the climate and the farm's height", async () => {
  fetchFrom(routes());
  const h = await farmHealth({ ...farm, seasons: [{ crop: "tomato", harvestedOn: "2026-08-20" }] }, { now });
  assert.equal(h.ok, true);
  assert.equal(h.soil.ok, true);
  assert.equal(h.soil.mapState, "ok");
  assert.ok(h.soil.score > 60 && h.soil.score <= 100);
  assert.match(h.soil.source, /SoilGrids/);
  assert.equal(h.elevM, 1216);
  assert.equal(h.rotation.growing, "maize");
  assert.equal(h.rotation.after, "maize");
  assert.ok(h.rotation.best.length === 3 && h.rotation.sequence.length === 3);
  assert.ok(h.rotation.avoid.some((a) => a.crops.includes("maize")));
  assert.ok(h.rotation.avoid.some((a) => a.crops.includes("potato")), "tomato two seasons back holds potato off");
  assert.equal(h.rotation.usedClimate, true); assert.equal(h.rotation.usedSoil, true);
  assert.match(h.rotation.climateSource, /1216 m/);
  assert.deepEqual(h.rotation.history, [{ crop: "tomato", harvestedOn: "2026-08-20" }]);
});

test("the soil map is fetched once per place, then served from the store", async () => {
  fetchFrom(routes());
  await farmHealth(farm, { now });
  await farmHealth(farm, { now });
  assert.equal(count("rest.isric.org"), 1);
  assert.equal(count("climatology"), 1);
  assert.equal(count("opentopodata.org"), 1);
});

test("a pin on a town looks at the ground around it and says so", async () => {
  fetchFrom(routes([["rest.isric.org", (url) => (url.includes("lat=-0.09000") ? town : farmland)]]));
  const h = await farmHealth({ ...farm, lat: -0.09, lon: 34.75 }, { now });
  assert.equal(h.soil.ok, true);
  assert.match(h.soil.mapNote, /3 km around/);
  assert.equal(count("rest.isric.org"), 5, "the pin, then north, east, south and west");
});

test("blank everywhere: says to move the pin or enter a test, and rotation still answers", async () => {
  fetchFrom(routes([["rest.isric.org", town]]));
  const h = await farmHealth(farm, { now });
  assert.equal(h.soil.ok, false);
  assert.equal(h.soil.mapState, "none");
  assert.match(h.soil.error, /Move the pin/);
  assert.ok(h.rotation.best.length > 0);
});

test("wait:false answers at once without the soil map, and uses it once it is stored", async () => {
  fetchFrom(routes());
  const quick = await farmHealth(farm, { now, wait: false });
  assert.equal(quick.soil.ok, false);
  assert.equal(quick.soil.mapState, "pending");
  assert.equal(count("rest.isric.org"), 0);
  assert.ok(quick.rotation.best.length > 0);
  await farmHealth(farm, { now });                                 // the page's slow call fills the store
  const after = await farmHealth(farm, { now, wait: false });
  assert.equal(after.soil.ok, true);
});

test("the soil map being down falls back to the farmer's own test", async () => {
  fetchFrom(routes([["rest.isric.org", new Error("timeout")]]));
  const none = await farmHealth(farm, { now });
  assert.equal(none.soil.ok, false); assert.equal(none.soil.mapState, "unavailable");
  const withTest = await farmHealth({ ...farm, soilTest: { ph: 5.2, omPct: 1.4, nPct: 0.08 } }, { now });
  assert.equal(withTest.soil.ok, true);
  assert.equal(withTest.soil.estimated, false);
  assert.match(withTest.soil.source, /your soil test/);
  assert.equal(withTest.soil.nitrogenLow, true);
  assert.ok(withTest.rotation.best[0].reasons.some((r) => /low in nitrogen/.test(r)));
});

test("the pump record adds a watering verdict to the soil score", async () => {
  fetchFrom(routes());
  const applied = {};
  for (const k of Object.keys(daily.properties.parameter.T2M_MAX)) applied[k] = 120000;   // about 27 mm a day: far too much
  const h = await farmHealth({ ...farm, applied }, { now });
  const w = h.soil.parts.find((p) => p.key === "watering");
  assert.ok(w, "watering part present");
  assert.equal(w.source, "pump");
  assert.equal(w.word, "Too much");
});

test("no location is a plain refusal; a farm with no crop yet still gets soil and rotation", async () => {
  fetchFrom(routes());
  const bad = await farmHealth({ crop: "maize" }, { now });
  assert.equal(bad.ok, false); assert.equal(bad.status, 400);
  const bare = await farmHealth({ lat: -0.05, lon: 34.8 }, { now });
  assert.equal(bare.ok, true); assert.equal(bare.soil.ok, true);
  assert.equal(bare.rotation.needsHistory, true);
});

test("the farm's position and exact name are stored with the account, and only with one", async () => {
  const saved = await saveFarmPlace("user-1", { lat: -0.051234567, lon: 34.80456789, place: "  Kibos,\nKisumu East, Kisumu  " }, "PP-1A2B3C");
  assert.deepEqual({ lat: saved.lat, lon: saved.lon, place: saved.place, serial: saved.serial }, { lat: -0.05123, lon: 34.80457, place: "Kibos, Kisumu East, Kisumu", serial: "PP-1A2B3C" });
  assert.equal(store.get("pumppay_farms/user-1").place, "Kibos, Kisumu East, Kisumu");
  assert.equal(await saveFarmPlace(null, { lat: 1, lon: 1 }), null);
  assert.equal(await saveFarmPlace("user-1", { lat: 0, lon: 0 }), null);
  assert.equal(await saveFarmPlace("user-1", { place: "nowhere" }), null);
});

test("every function the app calls is exported, and loading them needs no keys", () => {
  const all = { ...require("../irrigation"), ...require("../ask") };
  for (const name of ["pumppayCropPlan", "pumppayFarmHealth", "pumppayIrrigationPlan", "pumppayAdminFarm", "pumppayAsk"]) assert.equal(typeof all[name], "function", name);
});
