/**
 * PumpPay crop rotation - what to plant next on this field, and why. No Firebase, no network.
 *
 * The rules are the ones extension officers teach, turned into points so crops can be ranked:
 *   1. Do not follow a crop with one of its own family: its pests and diseases are waiting in the soil.
 *   2. Follow a heavy feeder with a legume, and a legume with a heavy feeder.
 *   3. Change the rooting depth from one season to the next.
 *   4. Pick a crop that suits this soil (pH, nitrogen) and the coming season (temperature, rain,
 *      and what the pump can add on top of the rain).
 *
 * Season water uses the same FAO-56 maths as the irrigation plan (irrigation-core.js), run on
 * NASA POWER's monthly climatology instead of the last three weeks of weather.
 *
 * The pH and temperature ranges are the usual textbook ranges for each crop: guides, not limits.
 * A crop outside its range loses points and says why; it is never hidden.
 */
const core = require("./irrigation-core");

const ok = (v) => typeof v === "number" && Number.isFinite(v);

// feeder: heavy = takes a lot of nitrogen, giver = legume that fixes its own and leaves some behind.
const TRAITS = {
  maize:        { family: "grass",         feeder: "heavy",  ph: [5.5, 7.0], temp: [15, 30] },
  beans:        { family: "legume",        feeder: "giver",  ph: [5.8, 7.0], temp: [15, 27] },
  tomato:       { family: "nightshade",    feeder: "heavy",  ph: [5.8, 7.0], temp: [18, 29] },
  kale:         { family: "brassica",      feeder: "heavy",  ph: [6.0, 7.5], temp: [10, 26] },
  cabbage:      { family: "brassica",      feeder: "heavy",  ph: [6.0, 7.5], temp: [10, 24] },
  onion:        { family: "allium",        feeder: "light",  ph: [6.0, 7.0], temp: [13, 28] },
  potato:       { family: "nightshade",    feeder: "heavy",  ph: [5.0, 6.5], temp: [10, 21] },
  watermelon:   { family: "cucurbit",      feeder: "medium", ph: [6.0, 7.0], temp: [21, 33] },
  cowpea:       { family: "legume",        feeder: "giver",  ph: [5.5, 7.0], temp: [20, 35] },
  green_gram:   { family: "legume",        feeder: "giver",  ph: [6.0, 7.5], temp: [20, 35] },
  groundnut:    { family: "legume",        feeder: "giver",  ph: [5.5, 7.0], temp: [20, 32] },
  sweet_potato: { family: "morning_glory", feeder: "light",  ph: [5.0, 6.5], temp: [18, 30] },
};

const FAMILY_NAME = {
  grass: "cereal family", legume: "legume family", nightshade: "tomato and potato family",
  brassica: "cabbage family", allium: "onion family", cucurbit: "melon and pumpkin family",
  morning_glory: "sweet potato family",
};
// What builds up in the soil when the family is repeated.
const FAMILY_RISK = {
  grass: "stalk borers and leaf diseases", legume: "root rots and bean fly",
  nightshade: "blight, bacterial wilt and nematodes", brassica: "clubroot, black rot and diamondback moth",
  allium: "thrips and onion rots", cucurbit: "wilts and fruit fly", morning_glory: "weevils",
};

const MONTH_KEYS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];
const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

const nice = (crop) => String(crop).replace(/_/g, " ");
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const seasonDays = (crop) => core.CROPS[crop].stages.reduce((a, b) => a + b, 0);
const rootClass = (crop) => { const m = core.CROPS[crop].rootM[1]; return m <= 0.5 ? "shallow" : m <= 0.8 ? "medium" : "deep"; };
const doyOf = (d) => Math.floor((Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - Date.UTC(d.getUTCFullYear(), 0, 0)) / 864e5);

/**
 * NASA POWER climatology JSON -> twelve months of the numbers the season maths needs.
 *
 * POWER's weather is the average over a grid square about 50 km across, at that square's average
 * height. In hilly country a farm can sit hundreds of metres above or below it, so when the farm's
 * own height is known (farmElevM) the temperatures are moved to it at 6.5 C per 1000 m.
 */
function climateMonths(powerClim, farmElevM = null) {
  const p = powerClim && powerClim.properties && powerClim.properties.parameter;
  if (!p || !p.T2M) return null;
  const g = (k, m) => { const v = p[k] && p[k][m]; return ok(v) && v > -900 ? v : null; };
  const coords = (powerClim.geometry && powerClim.geometry.coordinates) || [];
  const cellElev = ok(coords[2]) && coords[2] > -900 ? coords[2] : null;
  const shift = ok(farmElevM) && cellElev !== null ? Math.max(-8, Math.min(8, 0.0065 * (cellElev - farmElevM))) : 0;
  return {
    elevM: ok(farmElevM) ? farmElevM : cellElev,
    tempShiftC: +shift.toFixed(1),
    months: MONTH_KEYS.map((m) => {
      const t = g("T2M", m);
      return {
        rain: g("PRECTOTCORR", m), t: t === null ? null : t + shift, range: g("T2M_RANGE", m),
        rh: g("RH2M", m), u2: g("WS2M", m), rs: g("ALLSKY_SFC_SW_DWN", m),
      };
    }),
  };
}

/**
 * Water for one crop planted on `start`, in an average year at this place.
 * needMm = what the crop uses, rainMm = what falls, irrigationMm = what the pump must add,
 * peakMmDay = the pump's hardest month (mm a day beyond the rain).
 */
function seasonWater(crop, start, climate, latDeg) {
  if (!climate || !core.CROPS[crop] || !ok(latDeg)) return null;
  const total = seasonDays(crop);
  const elevM = ok(climate.elevM) ? climate.elevM : 1150;
  let need = 0, rain = 0, irrigation = 0, tSum = 0;
  const byMonth = new Map();
  for (let d = 0; d < total; d++) {
    const date = new Date(start.getTime() + d * 864e5);
    const m = climate.months[date.getUTCMonth()];
    if (!m || !ok(m.t)) return null;
    const range = ok(m.range) ? m.range : 10;
    const args = { tmax: m.t + range / 2, tmin: m.t - range / 2, latDeg, elevM, doy: doyOf(date) };
    const et0 = ok(m.rh) && ok(m.u2) && ok(m.rs)
      ? core.et0PenmanMonteith({ ...args, rh: m.rh, u2: m.u2, rs: m.rs })
      : core.et0Hargreaves(args);
    const etc = core.cropState(crop, d).kc * et0;
    const r = ok(m.rain) ? m.rain : 0;
    const short = Math.max(0, etc - 0.7 * r);     // about 70% of a month's rain reaches the roots
    need += etc; rain += r; irrigation += short; tSum += m.t;
    const key = date.getUTCFullYear() * 12 + date.getUTCMonth();
    const b = byMonth.get(key) || { short: 0, n: 0 };
    b.short += short; b.n++; byMonth.set(key, b);
  }
  let peak = 0;
  for (const b of byMonth.values()) if (b.n >= 7) peak = Math.max(peak, b.short / b.n);
  const end = new Date(start.getTime() + (total - 1) * 864e5);
  return {
    days: total, needMm: need, rainMm: rain, irrigationMm: irrigation, peakMmDay: peak, meanTempC: tSum / total,
    label: MONTH_NAMES[start.getUTCMonth()] + " to " + MONTH_NAMES[end.getUTCMonth()],
  };
}

/** What the pump can put on this field in a day, in mm, or null when the pump or field size is not known. */
function pumpMmPerDay(farm) {
  if (!farm || !(farm.flowLpm > 0) || !(farm.areaM2 > 0)) return null;
  const hours = ok(farm.solarHours) ? farm.solarHours : 6;
  const eff = ok(farm.efficiency) ? farm.efficiency : 0.75;
  return (farm.flowLpm * 60 * hours * eff) / farm.areaM2;
}

function scoreCrop(cand, { last, prev, prev2, soil, water, pumpMm, areaM2 }) {
  const t = TRAITS[cand];
  const lt = last ? TRAITS[last] : null;
  // block = breaks the family rule, never offered. avoid = a strong reason to leave it this season.
  let score = 50, block = false, avoid = false;
  const fors = [], againsts = [];
  // Each reason carries the points it was worth, so the strongest are the ones shown.
  const good = (pts, text) => { score += pts; if (text) fors.push({ pts, text }); };
  const bad = (pts, text, strong = false) => { score -= pts; if (strong) avoid = true; if (text) againsts.push({ pts, text }); };

  // 1. Family: the strongest rule, because no amount of fertiliser fixes a soil full of disease.
  if (lt && t.family === lt.family) {
    block = true;
    // Worded to read the same for the crop itself and for its relatives, so they can share one line.
    bad(cand === last ? 50 : 40, `The ${FAMILY_NAME[t.family]} was just on this field (${nice(last)}), so ${FAMILY_RISK[t.family]} are waiting in the soil.`);
  } else if (prev && TRAITS[prev].family === t.family) {
    bad(18, `The ${FAMILY_NAME[t.family]} was on this field two seasons ago (${nice(prev)}); one more season of rest is safer.`, true);
  } else if (prev2 && TRAITS[prev2].family === t.family) {
    bad(6, null);
  } else if (last) {
    good(10, `A different family from your ${nice(last)}, so its pests and diseases die back.`);
  }

  // 2. Nutrients: take, then give back.
  if (lt) {
    if (lt.feeder === "heavy") {
      if (t.feeder === "giver") good(25, `Puts nitrogen back into the soil after ${nice(last)}, which takes a lot out.`);
      else if (t.feeder === "light") good(10, `A light feeder, so the soil rests after ${nice(last)}.`);
      else if (t.feeder === "heavy") bad(15, `Another hungry crop after ${nice(last)} tires the soil: it would need plenty of manure or fertiliser.`, true);
      if (t.feeder === "giver" && prev && TRAITS[prev].feeder === "heavy") good(8, null);   // two hungry crops running: a legume is overdue
    } else if (lt.feeder === "giver") {
      if (t.feeder === "heavy") good(18, `Uses the nitrogen your ${nice(last)} left in the soil.`);
      else if (t.feeder !== "giver") good(8, `Makes use of the nitrogen your ${nice(last)} left behind.`);
    } else {
      if (t.feeder === "giver") good(12, "A legume: it makes its own nitrogen and leaves some for the crop after it.");
      else if (t.feeder === "heavy") good(4, null);
    }
  }

  // 3. Roots: a different depth feeds from a different layer.
  if (last && !block && rootClass(cand) !== rootClass(last)) {
    good(6, `${cap(rootClass(cand))} roots where ${nice(last)} had ${rootClass(last)} ones, so it feeds from a different layer of soil.`);
  }

  // 4. This soil.
  if (soil && ok(soil.ph)) {
    const [lo, hi] = t.ph;
    const off = soil.ph < lo ? lo - soil.ph : soil.ph > hi ? soil.ph - hi : 0;
    const side = soil.ph < lo ? "acidic" : "alkaline";
    const likes = `${lo.toFixed(1)} to ${hi.toFixed(1)}`;
    if (off === 0) good(6, `Suits your soil pH of ${soil.ph.toFixed(1)}.`);
    else if (off < 0.5) bad(8, `Your soil is a little too ${side} for it (pH ${soil.ph.toFixed(1)}; it likes ${likes}).`);
    else bad(20, `Your soil is too ${side} for it (pH ${soil.ph.toFixed(1)}; it likes ${likes}).${side === "acidic" ? " Lime first." : ""}`, true);
  }
  if (soil && soil.nitrogenLow) {
    if (t.feeder === "giver") good(10, "Your soil is low in nitrogen, and legumes make their own.");
    else if (t.feeder === "heavy") bad(8, "Your soil is low in nitrogen and this is a hungry crop: it needs manure and a top-dress.");
  }

  // 5. The coming season: temperature, then water.
  if (water) {
    const [lo, hi] = t.temp, T = water.meanTempC;
    const off = T < lo ? lo - T : T > hi ? T - hi : 0;
    if (off > 2) bad(18, `Too ${T < lo ? "cool" : "hot"} here in that season (about ${T.toFixed(0)} C; it likes ${lo} to ${hi} C).`, true);
    else if (off > 0) bad(6, `A little ${T < lo ? "cool" : "warm"} for it in that season (about ${T.toFixed(0)} C).`);
    else good(4, null);

    const irr = Math.round(water.irrigationMm), need = Math.round(water.needMm);
    const litres = ok(areaM2) && areaM2 > 0 ? ` (about ${(Math.round((irr * areaM2) / 1000) * 1000).toLocaleString("en-US")} litres on your field)` : "";
    if (irr <= 0.2 * need) {
      good(8, `Rain in ${water.label} covers almost all the water it needs.`);
    } else if (ok(pumpMm)) {
      // How much of the field the pump can keep watered in the crop's thirstiest month.
      const share = water.peakMmDay > 0 ? pumpMm / water.peakMmDay : 1;
      if (share < 0.95) {
        const acres = (a) => (a / 4046.86).toFixed(a / 4046.86 < 1 ? 2 : 1).replace(/\.?0+$/, "");
        bad(share < 0.5 ? 15 : 8, ok(areaM2) && areaM2 > 0
          ? `At its thirstiest it needs more water than your pump can give the whole field: enough for about ${acres(areaM2 * share)} of your ${acres(areaM2)} acre.`
          : "At its thirstiest it needs more water than your pump can give the whole field.");
      } else {
        good(4, `Needs about ${irr} mm from the pump over the season${litres}, which your pump can cover.`);
      }
    } else if (irr >= 0.6 * need) {
      bad(6, `Mostly irrigated in ${water.label}: about ${irr} mm must come from the pump${litres}.`);
    }
  }

  // The points run from about -10 (same crop again on unsuitable soil) to about 125 (everything
  // right). Squeezed around 50 so the result reads as a score out of 100 without piling up at the top.
  const out = Math.round(50 + (score - 50) * 0.62);
  const strongest = (list) => list.slice().sort((a, b) => b.pts - a.pts).map((x) => x.text);
  return { crop: cand, score: Math.max(0, Math.min(100, out)), block, avoid, reasons: strongest(fors), cautions: strongest(againsts) };
}

/** Every crop, best first, for a field with this history (crop names, oldest first) planted on `start`. */
function rank(names, start, { soil = null, climate = null, farm = null } = {}) {
  const last = names[names.length - 1] || null, prev = names[names.length - 2] || null, prev2 = names[names.length - 3] || null;
  const pumpMm = pumpMmPerDay(farm);
  const lat = farm && ok(farm.lat) ? farm.lat : null;
  return Object.keys(TRAITS).map((c) => {
    const water = climate && lat !== null ? seasonWater(c, start, climate, lat) : null;
    return { ...scoreCrop(c, { last, prev, prev2, soil, water, pumpMm, areaM2: farm && farm.areaM2 }), water };
  }).sort((a, b) => b.score - a.score || a.crop.localeCompare(b.crop));
}

const present = (r) => ({
  crop: r.crop, name: cap(nice(r.crop)), score: r.score,
  fit: r.score >= 65 ? "good" : r.score >= 50 ? "ok" : "poor",
  family: FAMILY_NAME[TRAITS[r.crop].family],
  reasons: r.reasons.slice(0, 4), cautions: r.cautions.slice(0, 2),
  seasonDays: seasonDays(r.crop),
  waterNeedMm: r.water ? Math.round(r.water.needMm) : null,
  rainMm: r.water ? Math.round(r.water.rainMm) : null,
  irrigationMm: r.water ? Math.round(r.water.irrigationMm) : null,
});

/**
 * The advice.
 * history = finished seasons, oldest first, then the crop in the field now if there is one:
 *           "maize" or { crop, plantedOn:"YYYY-MM-DD", harvestedOn? }. A last entry with a planting
 *           date and no harvest date is taken as still growing, and the advice is for after it.
 * soil    = { ph, nitrogenLow } from soil-core, or null
 * climate = climateMonths(...) or null
 * farm    = { lat, areaM2, flowLpm, efficiency, solarHours } - whatever is known
 */
function recommend({ history = [], soil = null, climate = null, farm = null, today = new Date() } = {}) {
  const entries = (Array.isArray(history) ? history : [])
    .map((h) => (typeof h === "string" ? { crop: h } : h))
    .filter((h) => h && TRAITS[h.crop]);
  const names = entries.map((h) => h.crop);
  const last = names[names.length - 1] || null;

  // The next crop goes in when the one in the field comes out.
  let start = today, growing = null;
  const cur = entries[entries.length - 1];
  if (cur && /^\d{4}-\d{2}-\d{2}$/.test(cur.plantedOn || "") && !cur.harvestedOn) {
    const end = new Date(Date.parse(cur.plantedOn + "T00:00:00Z") + seasonDays(cur.crop) * 864e5);
    growing = cur.crop;
    if (end > today) start = end;
  }

  const shared = { soil, climate, farm };
  const ranked = rank(names, start, shared);

  // The pick, then the best of each other family, so the choices are real alternatives.
  // Nothing with a strong reason against it is offered, unless that would leave no choice at all.
  let pool = ranked.filter((r) => !r.block && !r.avoid);
  if (!pool.length) pool = ranked.filter((r) => !r.block);
  const best = [];
  for (const r of pool) {
    if (best.length >= 3) break;
    if (!best.some((b) => TRAITS[b.crop].family === TRAITS[r.crop].family)) best.push(r);
  }
  for (const r of pool) { if (best.length >= 3) break; if (!best.includes(r)) best.push(r); }
  best.sort((x, y) => y.score - x.score);

  // What to leave out this season. Crops left out for the same reason share a line (all the
  // legumes after beans, all the hungry crops after maize), so five lines can cover the whole list.
  const avoid = [];
  for (const r of ranked.filter((x) => (x.block || x.avoid) && !best.includes(x)).sort((x, y) => (y.block - x.block) || (x.score - y.score))) {
    const reason = r.cautions[0];
    const row = avoid.find((v) => v.reason === reason);
    if (row) row.crops.push(r.crop);
    else avoid.push({ crops: [r.crop], reason });
  }
  avoid.splice(5);
  for (const v of avoid) v.names = cap(v.crops.map(nice).join(", "));

  // Three seasons ahead: take the pick, pretend it was grown, ask again.
  const sequence = [];
  let h = names.slice(), when = start;
  for (let i = 0; i < 3; i++) {
    const order = rank(h, when, shared);
    const next = order.find((r) => !r.block && !r.avoid) || order.find((r) => !r.block);
    if (!next) break;
    sequence.push({ crop: next.crop, name: cap(nice(next.crop)), from: MONTH_NAMES[when.getUTCMonth()] + " " + when.getUTCFullYear() });
    h.push(next.crop);
    when = new Date(when.getTime() + (seasonDays(next.crop) + 14) * 864e5);
  }

  const top = best[0] || null;
  return {
    ok: true,
    after: last, afterName: last ? cap(nice(last)) : null,
    growing,                                  // set while that crop is still in the field
    startsOn: start.toISOString().slice(0, 10),
    startLabel: MONTH_NAMES[start.getUTCMonth()] + " " + start.getUTCFullYear(),
    season: top && top.water ? { label: top.water.label, rainMm: Math.round(top.water.rainMm), meanTempC: +top.water.meanTempC.toFixed(1) } : null,
    needsHistory: names.length === 0,         // with no history the ranking is soil and season only
    best: best.map(present),
    avoid,
    sequence,
    usedSoil: !!(soil && (ok(soil.ph) || soil.nitrogenLow)),
    usedClimate: !!(ranked[0] && ranked[0].water),
  };
}

module.exports = { TRAITS, FAMILY_NAME, climateMonths, seasonWater, pumpMmPerDay, rank, recommend, nice, seasonDays };
