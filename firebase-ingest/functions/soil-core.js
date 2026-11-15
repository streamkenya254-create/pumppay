/**
 * PumpPay soil health - a score out of 100 and one thing to do about each part. No Firebase, no network.
 *
 * What goes in:
 *   grid     the ISRIC SoilGrids map values for the farm's pin (topsoil, 0-30 cm). The map is a
 *            250 m estimate made from satellite data and soil samples: a starting point, not a test.
 *   test     the farmer's own soil test, where they have one. It always wins over the map.
 *   watering what the pump actually put on the field against what the crop used (see wateringReview).
 *            This is the part only a pump controller can know.
 *
 * What comes out: six parts (acidity, organic matter, nitrogen, nutrient holding, soil type,
 * watering), each rated 0-100 with a plain sentence of advice, and one overall score.
 *
 * The rating bands are the general ones used for tropical cropland (pH for most crops 6.0-7.0;
 * organic carbon below 1% low, above 2% good; total nitrogen below 0.12% low). They are guides.
 * Lime and fertiliser amounts are deliberately not given: those need a real soil test.
 */
const core = require("./irrigation-core");
const { TRAITS, nice } = require("./rotation-core");

const ok = (v) => typeof v === "number" && Number.isFinite(v);
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/** Straight lines between the given [x, y] points; flat beyond the ends. */
function lin(x, pts) {
  if (x <= pts[0][0]) return pts[0][1];
  for (let i = 1; i < pts.length; i++) {
    if (x <= pts[i][0]) {
      const [x0, y0] = pts[i - 1], [x1, y1] = pts[i];
      return y0 + ((y1 - y0) * (x - x0)) / (x1 - x0);
    }
  }
  return pts[pts.length - 1][1];
}

/**
 * SoilGrids v2 point answer -> topsoil (0-30 cm) values in everyday units:
 *   ph, socGkg (organic carbon g/kg), nitrogenGkg (g/kg), cec (cmol(c)/kg), clay/sand/silt (%).
 * Returns null where the map has no soil at all, which is what it gives for towns and water.
 */
function parseSoilGrids(json) {
  const layers = json && json.properties && json.properties.layers;
  if (!Array.isArray(layers)) return null;
  const out = {};
  for (const l of layers) {
    const f = (l.unit_measure && l.unit_measure.d_factor) || 1;
    let sum = 0, w = 0;
    for (const d of l.depths || []) {
      const v = d.values && d.values.mean;
      const top = d.range ? d.range.top_depth : null, bottom = d.range ? d.range.bottom_depth : null;
      if (ok(v) && ok(top) && ok(bottom) && bottom <= 30 && bottom > top) { sum += (v / f) * (bottom - top); w += bottom - top; }
    }
    out[l.name] = w > 0 ? sum / w : null;
  }
  const g = {
    ph: out.phh2o ?? null, socGkg: out.soc ?? null, nitrogenGkg: out.nitrogen ?? null, cec: out.cec ?? null,
    clay: out.clay ?? null, sand: out.sand ?? null, silt: out.silt ?? null,
  };
  return Object.values(g).some(ok) ? g : null;
}

/** USDA texture triangle. Percentages; they are scaled to add up to 100 first. */
function usdaTexture(sand, silt, clay) {
  const t = sand + silt + clay;
  if (!ok(t) || t <= 0) return null;
  sand = (sand * 100) / t; silt = (silt * 100) / t; clay = (clay * 100) / t;
  if (silt + 1.5 * clay < 15) return "sand";
  if (silt + 2 * clay < 30) return "loamy sand";
  if ((clay >= 7 && clay < 20 && sand > 52) || (clay < 7 && silt < 50)) return "sandy loam";
  if (clay >= 7 && clay < 27 && silt >= 28 && silt < 50 && sand <= 52) return "loam";
  if ((silt >= 50 && clay >= 12 && clay < 27) || (silt >= 50 && silt < 80 && clay < 12)) return "silt loam";
  if (silt >= 80 && clay < 12) return "silt";
  if (clay >= 20 && clay < 35 && silt < 28 && sand > 45) return "sandy clay loam";
  if (clay >= 27 && clay < 40 && sand > 20 && sand <= 45) return "clay loam";
  if (clay >= 27 && clay < 40 && sand <= 20) return "silty clay loam";
  if (clay >= 35 && sand > 45) return "sandy clay";
  if (clay >= 40 && silt >= 40) return "silty clay";
  if (clay >= 40) return "clay";
  return "loam";
}

// The twelve USDA classes folded onto the six the irrigation model knows (SOIL_AW in irrigation-core).
const TO_SIX = {
  "sand": "sand", "loamy sand": "loamy_sand", "sandy loam": "sandy_loam",
  "loam": "loam", "silt loam": "loam", "silt": "loam", "sandy clay loam": "loam",
  "clay loam": "clay_loam", "silty clay loam": "clay_loam", "sandy clay": "clay_loam",
  "silty clay": "clay", "clay": "clay",
};

/**
 * What the pump did against what the crop needed, over the days NASA has weather for.
 *
 * The irrigation plan's own water balance (planIrrigation) leaves pumping out: it answers "what does
 * the field need today". This runs the same balance again with the litres actually pumped put in:
 *   Dr(today) = Dr(yesterday) + crop use - useful rain - water pumped
 * A day that ends past the crop's stress point is a short day. Water pumped onto soil that was
 * already full drains below the roots, and takes nitrogen with it: that is an over-watered day.
 *
 * series  = planIrrigation(...).series (date, etc, peff, Dr)
 * farm    = the cleaned farm (crop, plantedOn, areaM2, efficiency, soil)
 * applied = { "YYYYMMDD": litres pumped that day }, from the pump's run record
 */
function wateringReview(series, farm, applied) {
  if (!Array.isArray(series) || series.length < 5 || !farm || !(farm.areaM2 > 0) || !core.CROPS[farm.crop]) return null;
  if (!applied || typeof applied !== "object") return null;
  const aw = core.SOIL_AW[farm.soil] || core.SOIL_AW.loam;
  const eff = clamp(farm.efficiency || 0.75, 0.4, 0.95);
  const planted = Date.parse(farm.plantedOn + "T00:00:00Z");
  if (!ok(planted)) return null;

  let Dr = series[0].Dr;
  let days = 0, shortDays = 0, overDays = 0, drainedMm = 0, pumpedMm = 0, usedMm = 0, wateredDays = 0;
  for (const r of series.slice(1)) {
    const date = Date.UTC(+r.date.slice(0, 4), +r.date.slice(4, 6) - 1, +r.date.slice(6, 8));
    const cs = core.cropState(farm.crop, (date - planted) / 864e5);
    const taw = aw * cs.rootM, raw = cs.p * taw;
    const litres = Math.max(0, Number(applied[r.date]) || 0);
    const irr = (litres * eff) / farm.areaM2;                 // mm that reached the roots
    let after = Math.min(taw, Dr + r.etc - (r.peff || 0)) - irr;
    if (irr > 0) wateredDays++;
    if (after < 0) {
      const spill = Math.min(irr, -after);                    // only the pumped part counts; rain is nobody's fault
      if (spill >= 2) { overDays++; drainedMm += spill; }
      after = 0;
    }
    Dr = after;
    if (Dr > raw) shortDays++;
    pumpedMm += irr; usedMm += r.etc; days++;
  }
  if (!days) return null;

  const score = Math.round(clamp(100 - 80 * (shortDays / days) - 60 * (overDays / days), 0, 100));
  let word, advice;
  if (overDays >= 3 && overDays >= shortDays) {
    word = "Too much";
    advice = `Over-watered on ${overDays} of the last ${days} days: about ${Math.round(drainedMm)} mm drained below the roots, and it carries nitrogen away with it. Pump the minutes the Water tab gives, not more.`;
  } else if (shortDays >= 3) {
    word = "Too little";
    advice = `The crop was short of water on ${shortDays} of the last ${days} days. Dry soil also stops roots taking up nutrients, so fertiliser is wasted. Follow the Water tab each morning.`;
  } else {
    word = "On target";
    advice = `Watering matched what the crop needed on ${days - shortDays - overDays} of the last ${days} days. Keep following the Water tab.`;
  }
  return {
    days, wateredDays, shortDays, overDays,
    drainedMm: +drainedMm.toFixed(1), pumpedMm: +pumpedMm.toFixed(1), cropUsedMm: +usedMm.toFixed(1),
    score, word, advice,
  };
}

const level = (score) => (score >= 80 ? "good" : score >= 60 ? "fair" : "poor");

/**
 * The score.
 * grid = parseSoilGrids(...) or null;  test = { ph?, omPct?, nPct? } or null (the farmer's lab report:
 * pH, organic matter %, total nitrogen %);  crop = what is growing now, or null;
 * watering = wateringReview(...) or null.
 */
function soilHealth({ grid = null, test = null, crop = null, watering = null } = {}) {
  const g = grid || {}, t = test || {};
  const parts = [];
  const add = (key, label, value, score, word, advice, source) =>
    parts.push({ key, label, value, score: Math.round(clamp(score, 0, 100)), level: level(score), word, advice, source });

  // --- Acidity ---------------------------------------------------------------
  const ph = ok(t.ph) ? t.ph : ok(g.ph) ? g.ph : null;
  if (ph !== null) {
    const s = lin(ph, [[4.0, 10], [4.5, 25], [5.0, 45], [5.5, 70], [6.0, 95], [6.5, 100], [7.0, 100], [7.5, 85], [8.0, 60], [8.5, 35], [9.0, 15]]);
    let word, advice;
    if (ph < 5.0) { word = "Very acidic"; advice = "Very acidic. Most crops struggle: the soil locks up phosphorus, so plants stay small even with fertiliser. Spread agricultural lime before planting and work it in; a soil test tells you how much."; }
    else if (ph < 5.5) { word = "Acidic"; advice = "Acidic. Spread agricultural lime before the next planting (a soil test tells you how much), add manure, and go easy on DAP, which makes soil more acid."; }
    else if (ph < 6.0) { word = "Slightly acidic"; advice = "Slightly acidic: fine for most crops. Keep it from dropping by mixing manure or compost in each season and not using DAP every time."; }
    else if (ph <= 7.3) { word = "Good"; advice = "Right for almost every crop. Manure or compost each season keeps it there."; }
    else if (ph <= 8.0) { word = "Slightly alkaline"; advice = "Slightly alkaline. Add compost or manure, and do not add lime or wood ash."; }
    else { word = "Alkaline"; advice = "Alkaline. Iron and zinc get locked up, and leaves yellow between the veins. Add plenty of compost or manure, never lime or ash, and have the soil and the irrigation water tested for salt."; }
    const tr = crop && TRAITS[crop];
    if (tr && (ph < tr.ph[0] || ph > tr.ph[1])) {
      advice += ` Your ${nice(crop)} does best between pH ${tr.ph[0].toFixed(1)} and ${tr.ph[1].toFixed(1)}.`;
    }
    add("acidity", "Acidity (pH)", ph.toFixed(1), s, word, advice, ok(t.ph) ? "test" : "map");
  }

  // --- Organic matter --------------------------------------------------------
  // Labs report organic matter %; the map gives organic carbon. Organic matter = carbon x 1.724.
  const socPct = ok(t.omPct) ? t.omPct / 1.724 : ok(g.socGkg) ? g.socGkg / 10 : null;
  if (socPct !== null) {
    const s = lin(socPct, [[0.3, 10], [0.5, 25], [1.0, 50], [1.5, 75], [2.0, 90], [3.0, 100]]);
    let word, advice;
    if (socPct < 1.0) { word = "Low"; advice = "Low. Soil like this dries fast, crusts, and holds little fertiliser. Add 2 to 4 tonnes of well-rotted manure or compost per acre, leave stalks and leaves on the field as mulch, and never burn them."; }
    else if (socPct < 2.0) { word = "Fair"; advice = "Fair. Keep building it: manure or compost every season, crop residue left as mulch, and a legume in the rotation."; }
    else { word = "Good"; advice = "Good. This is what holds water and nutrients in the soil. Keep returning residues and manure so it stays that way."; }
    add("organic", "Organic matter", (socPct * 1.724).toFixed(1) + " %", s, word, advice, ok(t.omPct) ? "test" : "map");
  }

  // --- Nitrogen --------------------------------------------------------------
  const nGkg = ok(t.nPct) ? t.nPct * 10 : ok(g.nitrogenGkg) ? g.nitrogenGkg : null;
  let nitrogenLow = false;
  if (nGkg !== null) {
    const s = lin(nGkg, [[0.3, 15], [0.5, 30], [1.2, 65], [2.5, 100]]);
    nitrogenLow = nGkg < 1.2;
    let word, advice;
    if (nGkg < 1.2) { word = "Low"; advice = "Low. Plant a legume next (beans, cowpea, green gram or groundnut): it makes its own nitrogen and leaves some behind. Hungry crops like maize, kale and tomato will need manure and a top-dress."; }
    else if (nGkg < 2.5) { word = "Fair"; advice = "Fair. Enough for light feeders; maize, kale and tomato still need manure or a top-dress. A legume every second or third season keeps it up."; }
    else { word = "Good"; advice = "Good. Keep a legume in the rotation and it stays that way without buying more fertiliser."; }
    add("nitrogen", "Nitrogen", (nGkg / 10).toFixed(2) + " %", s, word, advice, ok(t.nPct) ? "test" : "map");
  }

  // --- Nutrient holding (CEC) ------------------------------------------------
  if (ok(g.cec)) {
    const s = lin(g.cec, [[2, 15], [5, 35], [12, 70], [25, 100]]);
    let word, advice;
    if (g.cec < 5) { word = "Low"; advice = "Low. Fertiliser washes through this soil quickly: feed little and often rather than all at once, and build organic matter, which is what holds nutrients."; }
    else if (g.cec < 12) { word = "Fair"; advice = "Fair. Split fertiliser into two or three smaller doses, and keep adding manure or compost to hold more."; }
    else { word = "Good"; advice = "Good. This soil holds on to the nutrients you give it."; }
    add("holding", "Nutrient holding", g.cec.toFixed(0), s, word, advice, "map");
  }

  // --- Soil type -------------------------------------------------------------
  let texture = null;
  if (ok(g.sand) && ok(g.silt) && ok(g.clay)) {
    const usda = usdaTexture(g.sand, g.silt, g.clay);
    const six = TO_SIX[usda] || "loam";
    texture = { usda, soil: six, sand: Math.round(g.sand), silt: Math.round(g.silt), clay: Math.round(g.clay) };
    const s = { loam: 100, sandy_loam: 85, clay_loam: 85, clay: 65, loamy_sand: 60, sand: 40 }[six];
    const advice = {
      sand: "Sandy: water and nutrients drain straight through. Water little and often, mulch, and add manure every season.",
      loamy_sand: "Light and sandy: drains fast. Water more often with less each time, and mulch to slow drying.",
      sandy_loam: "Light soil that drains well and is easy to work. Mulch helps it hold water between waterings.",
      loam: "A good mix: holds water without waterlogging, and roots move through it easily.",
      clay_loam: "Fairly heavy: holds water and nutrients well. Do not dig or drive on it when it is wet, or it packs hard.",
      clay: "Heavy clay: holds water but can waterlog and crack. Water less often, never work it wet, and use raised beds for vegetables.",
    }[six];
    add("texture", "Soil type", usda.charAt(0).toUpperCase() + usda.slice(1), s, s >= 80 ? "Good" : s >= 60 ? "Fair" : "Hard to manage", advice, "map");
  }

  // --- Watering --------------------------------------------------------------
  if (watering && ok(watering.score)) {
    add("watering", `Watering, last ${watering.days} days`, watering.word, watering.score, watering.word, watering.advice, "pump");
  }

  if (!parts.length) return { ok: false, error: "No soil data for this spot." };

  // Organic matter and acidity carry the most weight: they decide whether anything else works.
  // A part with no data is left out and the others share its weight.
  const W = { organic: 25, acidity: 20, watering: 20, nitrogen: 15, holding: 10, texture: 10 };
  const wSum = parts.reduce((a, p) => a + W[p.key], 0);
  const score = Math.round(parts.reduce((a, p) => a + p.score * W[p.key], 0) / wSum);
  const fromMap = parts.some((p) => p.source === "map"), fromTest = parts.some((p) => p.source === "test");

  return {
    ok: true,
    score,
    grade: score >= 80 ? "Healthy" : score >= 60 ? "Fair" : score >= 40 ? "Needs care" : "Poor",
    estimated: fromMap,                       // true when any part comes from the map rather than a test
    usedTest: fromTest,
    parts,
    // What to do first: the weak parts, the ones that matter most first (how far short x how much it weighs).
    todo: parts.filter((p) => p.score < 75).sort((a, b) => (100 - b.score) * W[b.key] - (100 - a.score) * W[a.key]).slice(0, 3)
      .map((p) => ({ key: p.key, label: p.label, advice: p.advice })),
    ph, nitrogenLow, texture,
  };
}

module.exports = { parseSoilGrids, usdaTexture, TO_SIX, wateringReview, soilHealth, lin };
