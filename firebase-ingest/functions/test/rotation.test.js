const test = require("node:test");
const assert = require("node:assert/strict");
const core = require("../irrigation-core");
const rot = require("../rotation-core");

const today = new Date("2026-11-15T00:00:00Z");
const clim = rot.climateMonths(require("./fixtures/power-climatology.json"), 1216);
const farm = { lat: -0.05, areaM2: 4047, flowLpm: 40, efficiency: 0.9, solarHours: 6 };
const names = (list) => list.map((c) => c.crop);
const left = (r) => r.avoid.flatMap((a) => a.crops);            // every crop in the leave-out list
const why = (r, crop) => (r.avoid.find((a) => a.crops.includes(crop)) || {}).reason || "";

test("every crop the irrigation model knows has rotation traits, and the other way round", () => {
  assert.deepEqual(Object.keys(rot.TRAITS).sort(), Object.keys(core.CROPS).sort());
  for (const [crop, t] of Object.entries(rot.TRAITS)) {
    assert.ok(rot.FAMILY_NAME[t.family], crop + " has a named family");
    assert.ok(t.ph[0] < t.ph[1] && t.temp[0] < t.temp[1], crop + " has sane ranges");
  }
});

test("the four crops added for rotation work in the water model", () => {
  for (const c of ["cowpea", "green_gram", "groundnut", "sweet_potato"]) {
    const mid = core.cropState(c, 60);
    assert.equal(core.CROPS[c].stages.length, 4);
    assert.ok(mid.kc > 0.9 && mid.kc < 1.3, c + " mid-season Kc");
    assert.ok(mid.rootM > 0.3 && mid.rootM <= 1.0, c + " roots");
  }
});

test("after maize the pick is a legume, and maize itself is left out", () => {
  const r = rot.recommend({ history: ["maize"], today });
  assert.equal(rot.TRAITS[r.best[0].crop].family, "legume");
  assert.ok(r.best[0].reasons.some((x) => /nitrogen/i.test(x)));
  assert.ok(!names(r.best).includes("maize"));
  assert.ok(left(r).includes("maize"));
  assert.equal(r.after, "maize");
  assert.equal(r.needsHistory, false);
});

test("after a legume the pick is a hungry crop that uses the nitrogen", () => {
  const r = rot.recommend({ history: ["maize", "beans"], today });
  assert.equal(rot.TRAITS[r.best[0].crop].feeder, "heavy");
  assert.ok(r.best[0].reasons.some((x) => /nitrogen your beans left/i.test(x)));
  for (const legume of ["beans", "cowpea", "green_gram", "groundnut"]) assert.ok(!names(r.best).includes(legume), legume + " not offered after beans");
});

test("the same family is never offered straight after itself", () => {
  const r = rot.recommend({ history: ["tomato"], today });
  assert.ok(!names(r.best).includes("potato") && !names(r.best).includes("tomato"));
  assert.match(why(r, "potato"), /tomato and potato family was just on this field/);
  assert.equal(why(r, "potato"), why(r, "tomato"), "relatives share one line");
});

test("a family grown two seasons ago is held back one more season", () => {
  const r = rot.recommend({ history: ["tomato", "maize"], today });
  assert.ok(!names(r.best).includes("potato"));
  assert.match(why(r, "potato"), /two seasons ago/);
});

test("the three picks come from three different families when there are that many", () => {
  const r = rot.recommend({ history: ["maize"], today });
  assert.equal(new Set(r.best.map((c) => rot.TRAITS[c.crop].family)).size, 3);
  assert.deepEqual(r.best.map((c) => c.score), r.best.map((c) => c.score).slice().sort((a, b) => b - a), "best first");
});

test("acid soil pushes out the crops that cannot take it", () => {
  const r = rot.recommend({ history: ["maize"], soil: { ph: 5.0, nitrogenLow: false }, today });
  assert.ok(!names(r.best).includes("onion"));
  assert.match(why(r, "onion"), /too acidic/);
  assert.match(why(r, "onion"), /Lime first/);
  assert.equal(r.usedSoil, true);
});

test("low nitrogen favours legumes and says why", () => {
  const r = rot.recommend({ history: ["sweet_potato"], soil: { ph: 6.2, nitrogenLow: true }, today });
  assert.equal(rot.TRAITS[r.best[0].crop].family, "legume");
  assert.ok(r.best[0].reasons.some((x) => /low in nitrogen/.test(x)));
});

test("a crop still in the field moves the advice to after its harvest", () => {
  const r = rot.recommend({ history: [{ crop: "maize", plantedOn: "2026-09-01" }], today });
  assert.equal(r.growing, "maize");
  assert.equal(r.startsOn, "2027-01-19");          // 1 Sep + 140 days
  const done = rot.recommend({ history: [{ crop: "maize", plantedOn: "2026-03-01", harvestedOn: "2026-07-20" }], today });
  assert.equal(done.growing, null);
  assert.equal(done.startsOn, "2026-11-15");
});

test("with no history it still answers, and says the history is missing", () => {
  const r = rot.recommend({ history: [], climate: clim, farm, today });
  assert.equal(r.needsHistory, true);
  assert.ok(r.best.length >= 1);
  assert.equal(r.after, null);
});

test("unknown crops in the history are ignored, not fatal", () => {
  const r = rot.recommend({ history: ["bananas", { crop: "maize" }, null, 7], today });
  assert.equal(r.after, "maize");
});

test("the three-season plan never repeats a family back to back", () => {
  for (const start of [["maize"], ["beans"], ["tomato", "kale"], []]) {
    const r = rot.recommend({ history: start, climate: clim, farm, today });
    assert.equal(r.sequence.length, 3);
    const chain = [...start, ...r.sequence.map((s) => s.crop)];
    for (let i = 1; i < chain.length; i++) {
      if (i >= start.length) assert.notEqual(rot.TRAITS[chain[i]].family, rot.TRAITS[chain[i - 1]].family, chain.join(" > "));
    }
  }
});

test("season water is in a believable range for maize near Kisumu", () => {
  const w = rot.seasonWater("maize", today, clim, -0.05);
  assert.equal(w.days, 140);
  assert.ok(w.needMm > 400 && w.needMm < 800, "need " + w.needMm);
  assert.ok(w.irrigationMm > 0 && w.irrigationMm < w.needMm);
  assert.ok(w.peakMmDay > 1 && w.peakMmDay < 8);
  assert.equal(w.label, "Nov to Apr");
});

test("the farm's height moves the temperatures: lower farm, warmer", () => {
  const raw = require("./fixtures/power-climatology.json");
  const cell = rot.climateMonths(raw), low = rot.climateMonths(raw, 1216), high = rot.climateMonths(raw, 2300);
  assert.equal(cell.tempShiftC, 0);
  assert.ok(low.tempShiftC > 2 && low.tempShiftC < 3.2, "about 2.6 C warmer 400 m lower: " + low.tempShiftC);
  assert.ok(high.tempShiftC < -4);
  assert.ok(low.months[0].t > cell.months[0].t && high.months[0].t < cell.months[0].t);
});

test("potato is left out in warm lowland and offered in cool highland", () => {
  const raw = require("./fixtures/power-climatology.json");
  const warm = rot.recommend({ history: ["beans"], climate: rot.climateMonths(raw, 1216), farm, today });
  assert.match(why(warm, "potato"), /too hot/i);
  const cool = rot.recommend({ history: ["beans"], climate: rot.climateMonths(raw, 2300), farm, today });
  assert.ok(!left(cool).includes("potato"));
});

test("a pump too small for the field is said plainly, with the area it can carry", () => {
  const r = rot.recommend({ history: ["beans"], climate: clim, farm: { ...farm, flowLpm: 5 }, today });
  assert.ok(r.best[0].cautions.some((c) => /more water than your pump can give/.test(c) && /acre/.test(c)));
  assert.equal(rot.pumpMmPerDay({ flowLpm: 0, areaM2: 4047 }), null);
  assert.ok(Math.abs(rot.pumpMmPerDay(farm) - 3.2) < 0.05);
});

test("scores stay inside 0 to 100 for every history of one or two crops", () => {
  const all = Object.keys(rot.TRAITS);
  for (const a of all) for (const b of all) {
    for (const r of rot.rank([a, b], today, { soil: { ph: 4.6, nitrogenLow: true }, climate: clim, farm })) {
      assert.ok(r.score >= 0 && r.score <= 100, `${a},${b} -> ${r.crop} ${r.score}`);
    }
  }
});
