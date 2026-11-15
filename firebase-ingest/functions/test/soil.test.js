const test = require("node:test");
const assert = require("node:assert/strict");
const soil = require("../soil-core");

const farmland = require("./fixtures/soilgrids-farmland.json");   // real SoilGrids answer, farmland east of Kisumu
const town = require("./fixtures/soilgrids-town.json");           // real answer for Kisumu town: every value null

test("a real SoilGrids answer is read into topsoil values", () => {
  const g = soil.parseSoilGrids(farmland);
  assert.ok(Math.abs(g.ph - 5.9) < 0.05, "pH " + g.ph);
  // 0-30 cm is weighted by layer thickness: (5 x 35.7 + 10 x 18.0 + 15 x 14.3) / 30 = 19.1
  assert.ok(Math.abs(g.socGkg - 19.1) < 0.2, "organic carbon " + g.socGkg);
  assert.ok(Math.abs(g.nitrogenGkg - 2.0) < 0.1, "nitrogen " + g.nitrogenGkg);
  assert.ok(g.cec > 15 && g.cec < 22);
  assert.ok(Math.abs(g.clay + g.sand + g.silt - 100) < 3, "texture adds up");
});

test("a town or water pin, where the map is blank, reads as no soil", () => {
  assert.equal(soil.parseSoilGrids(town), null);
  assert.equal(soil.parseSoilGrids(null), null);
  assert.equal(soil.parseSoilGrids({ properties: {} }), null);
});

test("the texture triangle names each class", () => {
  const cases = [
    [90, 5, 5, "sand"], [82, 10, 8, "loamy sand"], [65, 25, 10, "sandy loam"], [40, 40, 20, "loam"],
    [20, 65, 15, "silt loam"], [5, 90, 5, "silt"], [60, 15, 25, "sandy clay loam"], [30, 35, 35, "clay loam"],
    [10, 55, 35, "silty clay loam"], [50, 10, 40, "sandy clay"], [5, 50, 45, "silty clay"], [20, 20, 60, "clay"],
  ];
  for (const [sand, silt, clay, name] of cases) assert.equal(soil.usdaTexture(sand, silt, clay), name, `${sand}/${silt}/${clay}`);
  assert.equal(soil.usdaTexture(20.3, 10, 19.7), soil.usdaTexture(40.6, 20, 39.4), "scaled to 100 first");
  for (const six of Object.values(soil.TO_SIX)) assert.ok(require("../irrigation-core").SOIL_AW[six], six + " is a soil the water model knows");
});

test("the farmland sample scores as healthy, with its soil type named", () => {
  const r = soil.soilHealth({ grid: soil.parseSoilGrids(farmland) });
  assert.equal(r.ok, true);
  assert.ok(r.score >= 80 && r.score <= 100, "score " + r.score);
  assert.equal(r.grade, "Healthy");
  assert.equal(r.estimated, true);
  assert.deepEqual(r.parts.map((p) => p.key), ["acidity", "organic", "nitrogen", "holding", "texture"]);
  assert.equal(r.texture.soil, "clay_loam");
  assert.equal(r.nitrogenLow, false);
  for (const p of r.parts) assert.ok(p.advice.length > 20 && p.score >= 0 && p.score <= 100);
});

test("a worn-out acid soil scores low and says what to do first", () => {
  const r = soil.soilHealth({ grid: { ph: 4.8, socGkg: 6, nitrogenGkg: 0.6, cec: 4, sand: 80, silt: 10, clay: 10 } });
  assert.ok(r.score < 45, "score " + r.score);
  assert.ok(["Poor", "Needs care"].includes(r.grade));
  assert.equal(r.nitrogenLow, true);
  assert.equal(r.todo.length, 3);
  const text = r.todo.map((t) => t.advice).join(" ");
  assert.match(text, /lime/i);
  assert.match(text, /manure|compost/i);
  assert.ok(!/\d+\s*(kg|bags?)\b/i.test(r.parts.find((p) => p.key === "acidity").advice), "no lime amounts without a soil test");
});

test("the farmer's soil test wins over the map", () => {
  const grid = soil.parseSoilGrids(farmland);
  const r = soil.soilHealth({ grid, test: { ph: 6.8, omPct: 1.2 } });
  const ph = r.parts.find((p) => p.key === "acidity"), om = r.parts.find((p) => p.key === "organic"), n = r.parts.find((p) => p.key === "nitrogen");
  assert.equal(ph.value, "6.8"); assert.equal(ph.source, "test");
  assert.equal(om.value, "1.2 %"); assert.equal(om.source, "test"); assert.equal(om.word, "Low");
  assert.equal(n.source, "map");
  assert.equal(r.usedTest, true); assert.equal(r.estimated, true);
});

test("a test alone, with no map, still gives a score", () => {
  const r = soil.soilHealth({ test: { ph: 6.5 } });
  assert.equal(r.ok, true);
  assert.equal(r.score, 100);            // one part, so its score is the whole score
  assert.equal(r.estimated, false);
});

test("the advice mentions the crop when the soil does not suit it", () => {
  const r = soil.soilHealth({ grid: { ph: 5.4 }, crop: "onion" });
  assert.match(r.parts[0].advice, /onion does best between pH 6\.0 and 7\.0/);
  const fine = soil.soilHealth({ grid: { ph: 6.4 }, crop: "onion" });
  assert.ok(!/does best between/.test(fine.parts[0].advice));
});

test("nothing known gives an honest no", () => {
  assert.equal(soil.soilHealth({}).ok, false);
  assert.equal(soil.soilHealth({ grid: null, test: null }).ok, false);
});

// ---- Watering: what the pump did against what the crop needed -------------------------------
const farm = { crop: "maize", plantedOn: "2026-09-01", areaM2: 4000, efficiency: 0.9, soil: "loam" };
// Fifteen dry days in mid-season, 5 mm of crop use a day, starting 60 mm down. Maize on loam has
// about 150 mm to draw on and is stressed past 82 mm, so with no water that comes on the fifth day.
const days = Array.from({ length: 15 }, (_, i) => ({ date: "202611" + String(i + 1).padStart(2, "0"), etc: 5, peff: 0, Dr: 60 }));
const everyDay = (litres) => Object.fromEntries(days.map((d) => [d.date, litres]));

test("no pumping in a dry spell shows as short days", () => {
  const w = soil.wateringReview(days, farm, {});
  assert.equal(w.days, 14);
  assert.ok(w.shortDays >= 3, "short days " + w.shortDays);
  assert.equal(w.overDays, 0);
  assert.equal(w.word, "Too little");
  assert.ok(w.score < 80);
});

test("pumping what the crop uses is on target", () => {
  // 5 mm on 4000 m2 at 90% efficiency = 22,222 litres a day
  const w = soil.wateringReview(days, farm, everyDay(22222));
  assert.equal(w.shortDays, 0); assert.equal(w.overDays, 0);
  assert.equal(w.word, "On target"); assert.equal(w.score, 100);
  assert.ok(Math.abs(w.pumpedMm - w.cropUsedMm) < 1);
});

test("pumping far too much shows as over-watered days and drained water", () => {
  const w = soil.wateringReview(days, farm, everyDay(90000));      // about 20 mm a day
  assert.ok(w.overDays >= 10);
  assert.ok(w.drainedMm > 100);
  assert.equal(w.word, "Too much");
  assert.match(w.advice, /nitrogen/);
});

test("heavy rain alone is not counted against the farmer", () => {
  const wet = days.map((d) => ({ ...d, peff: 30 }));
  const w = soil.wateringReview(wet, farm, {});
  assert.equal(w.overDays, 0); assert.equal(w.drainedMm, 0);
});

test("no pump record means no verdict, not a bad one", () => {
  assert.equal(soil.wateringReview(days, farm, null), null);
  assert.equal(soil.wateringReview(days.slice(0, 3), farm, {}), null);
  assert.equal(soil.wateringReview(days, { ...farm, areaM2: 0 }, {}), null);
});

test("watering joins the score with its own weight", () => {
  const grid = soil.parseSoilGrids(farmland);
  const without = soil.soilHealth({ grid });
  const bad = soil.soilHealth({ grid, watering: soil.wateringReview(days, farm, everyDay(90000)) });
  assert.ok(bad.parts.some((p) => p.key === "watering" && p.source === "pump"));
  assert.ok(bad.score < without.score - 5, `${bad.score} vs ${without.score}`);
  assert.equal(bad.todo[0].key, "watering");
});
