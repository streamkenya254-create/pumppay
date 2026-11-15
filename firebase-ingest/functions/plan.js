/**
 * The irrigation plan for one farm: NASA weather and the crop model go in, a decision comes out
 * (water today or not, how many litres, how long to pump, when). Shared by the Plant page
 * endpoint and the assistant, so both always quote the same numbers.
 *
 * This is plain helpers, not a Cloud Function: nothing here is exported to the platform.
 */
const admin = require("firebase-admin");
const { defineSecret } = require("firebase-functions/params");
const core = require("./irrigation-core");
const sun = require("./forecast-core");

// The OpenWeather key, for the sun and rain forecast. A secret, set once with
//   firebase functions:secrets:set OPENWEATHER_KEY
// and listed in `secrets` by every function that makes a plan. Without it the plan still works:
// it falls back to a fixed midday window and knows nothing about tomorrow.
const OPENWEATHER_KEY = defineSecret("OPENWEATHER_KEY");
function owmKey() { try { return String(OPENWEATHER_KEY.value() || "").trim(); } catch (e) { return ""; } }

const db = admin.firestore();
const num = (v, lo, hi) => { const n = Number(v); return Number.isFinite(n) && n >= lo && n <= hi ? n : null; };
const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, "");

// Fetch 21 days of NASA POWER for the farm, cached per location per day so many callers do
// not hammer NASA (and it survives NASA being slow).
async function nasaPower(lat, lon, today) {
  const end = ymd(today);
  const start = ymd(new Date(today.getTime() - 21 * 864e5));
  const id = `${lat.toFixed(2)}_${lon.toFixed(2)}_${end}`;
  const ref = db.doc(`pumppay_weather/${id}`);
  const snap = await ref.get();
  if (snap.exists) return snap.data().json;

  const r = await fetch(core.powerUrl(lat, lon, start, end), { signal: AbortSignal.timeout(15000) });
  if (!r.ok) throw new Error(`NASA POWER ${r.status}`);
  const json = await r.json();
  await ref.set({ json, fetchedAt: admin.firestore.FieldValue.serverTimestamp() });
  return json;
}

// OpenWeather's 5-day, 3-hourly forecast for the farm, kept for an hour per location. Only the
// fields the outlook reads are stored. Returns null when there is no key; throws when the call fails
// (the error never carries the key).
async function forecastAt(lat, lon, key) {
  if (!key) return null;
  const ref = db.doc(`pumppay_forecast/${lat.toFixed(2)}_${lon.toFixed(2)}`);
  const snap = await ref.get();
  if (snap.exists && Date.now() - (snap.data().at || 0) < 3600e3) return JSON.parse(snap.data().json);

  let r;
  try {
    r = await fetch(`https://api.openweathermap.org/data/2.5/forecast?lat=${lat}&lon=${lon}&units=metric&appid=${encodeURIComponent(key)}`, { signal: AbortSignal.timeout(12000) });
  } catch (e) { throw new Error("OpenWeather unreachable"); }
  if (!r.ok) throw new Error(`OpenWeather ${r.status}`);
  const j = await r.json();
  const slim = {
    city: { timezone: j.city && j.city.timezone },
    list: (j.list || []).map((e) => ({
      dt: e.dt, clouds: e.clouds, pop: e.pop, rain: e.rain,
      main: e.main && { temp: e.main.temp, temp_min: e.main.temp_min, temp_max: e.main.temp_max, humidity: e.main.humidity },
      wind: e.wind && { speed: e.wind.speed },
    })),
  };
  await ref.set({ json: JSON.stringify(slim), at: Date.now() });
  return slim;
}

/**
 * The weather for a place, reduced to what matters on a farm: today's insights, the later days'
 * headlines, and the outlook they were drawn from. Needs only a position, so it works before a
 * crop is entered. Returns { outlook, weather } - both null when there is no key or no forecast -
 * and `error` when OpenWeather could not be reached. Never throws.
 */
async function weatherFor(lat, lon, now = new Date(), { crop = null, pumpWatts = null, panelWp = null } = {}) {
  try {
    const fc = await forecastAt(lat, lon, owmKey());
    const outlook = fc ? sun.solarOutlook(fc, { lat, lon, now, pumpWatts, panelWp }) : null;
    return { outlook, weather: sun.weatherInsights(outlook, { crop }), error: null };
  } catch (e) {
    return { outlook: null, weather: null, error: String(e.message || e) };
  }
}

function cleanFarm(f, flowOptional = false) {
  if (!f || typeof f !== "object") return null;
  const farm = {
    lat: num(f.lat, -60, 60), lon: num(f.lon, -180, 180),
    crop: core.CROPS[f.crop] ? f.crop : null,
    plantedOn: /^\d{4}-\d{2}-\d{2}$/.test(f.plantedOn || "") ? f.plantedOn : null,
    areaM2: num(f.areaM2, 10, 5e6), flowLpm: num(f.flowLpm, 1, 20000),
    efficiency: num(f.efficiency, 0.4, 0.95) ?? 0.75,
    soil: core.SOIL_AW[f.soil] ? f.soil : "loam",
    tankLitres: num(f.tankLitres, 0, 1e7), solarHours: num(f.solarHours, 1, 10) ?? 6,
    elevM: num(f.elevM, -100, 6000),
    pumpWatts: num(f.pumpWatts, 10, 50000), panelWp: num(f.panelWp, 10, 500000),
  };
  // How long the panels can carry this pump: plenty of spare panel runs it through the sunny middle of the day,
  // less runs it later and for fewer hours. Only when the owner has said what the pump and array are.
  if (farm.pumpWatts && farm.panelWp && f.solarHours === undefined) {
    const ratio = farm.panelWp / farm.pumpWatts;
    farm.solarHours = ratio >= 1.5 ? 6 : ratio >= 1 ? 3 + ((ratio - 1) / 0.5) * 3 : 2;
  }
  return farm.lat !== null && farm.lon !== null && farm.crop && farm.plantedOn && farm.areaM2 && (farm.flowLpm || flowOptional) ? farm : null;
}

/** The decision, plus the figures a screen or an answer needs to explain it. */
// flowOptional: the assistant can still say "water today, N litres" before the pump flow is saved;
// minutes and the pump window are then null.
async function planForFarm(rawFarm, now = new Date(), flowOptional = false) {
  const farm = cleanFarm(rawFarm, flowOptional);
  if (!farm) return { ok: false, error: "farm needs lat, lon, crop, plantedOn, areaM2, flowLpm", status: 400 };
  let power;
  try { power = await nasaPower(farm.lat, farm.lon, now); }
  catch (e) { return { ok: false, error: "NASA POWER unavailable", status: 502 }; }
  const { days, elevM } = core.powerToDays(power);

  // The forecast, when there is one: rain on the way holds the pump back, and the sun decides
  // how many hours the pump has today. A failed forecast never stops the plan.
  const { outlook, weather, error: sunError } = await weatherFor(farm.lat, farm.lon, now, { crop: farm.crop, pumpWatts: farm.pumpWatts, panelWp: farm.panelWp });
  if (outlook) {
    farm.rainForecastMm = outlook.rainNext24Mm;
    if (rawFarm.solarHours === undefined) farm.solarHours = Math.max(0.5, Math.min(8, outlook.days[0].goodHours));
  }

  const plan = core.planIrrigation(days, farm, now, elevM);
  if (!plan.ok) return { ...plan, status: 503 };
  const { series, ...d } = plan;
  if (outlook) Object.assign(d, sunTiming(outlook, d.minutes));
  const total = core.CROPS[farm.crop].stages.reduce((a, b) => a + b, 0);
  const day = Math.max(0, Math.floor((now - new Date(farm.plantedOn + "T00:00:00Z")) / 864e5));
  return {
    ...d,
    day, totalDays: total, areaM2: farm.areaM2,
    soilLeftPct: Math.round(((d.tawMm - d.depletionMm) / d.tawMm) * 100),
    stressPct: Math.round(((d.tawMm - d.rawMm) / d.tawMm) * 100),
    dailyUseLitres: Math.round((d.etcToday * farm.areaM2) / 10) * 10,
    sun: sun.publicOutlook(outlook), sunError,
    weather,                                  // today's insights and the later days' headlines
  };
}

/**
 * When to run the pump, from the outlook: the sunniest stretch still left today, or tomorrow's
 * when today's sun has gone. Returns the fields to lay over the plan: window, windowDay, sunNote.
 */
function sunTiming(outlook, minutes) {
  const today = outlook.days[0], tomorrow = outlook.days[1] || null;
  const hrs = (h) => `${h} good pumping hour${h === 1 ? "" : "s"}`;
  let sunNote =
    today.rating === "Good sun" ? `Good sun today: about ${hrs(today.goodHours)}.`
    : today.rating === "Some cloud" ? `Some cloud today: about ${hrs(today.goodHours)}.`
    : `Poor sun today: only about ${hrs(today.goodHours)}, so the pump moves less water.`;
  if (tomorrow && tomorrow.goodHours >= today.goodHours + 1.5) sunNote += ` Tomorrow looks better (${tomorrow.goodHours} h).`;
  else if (tomorrow && tomorrow.goodHours <= today.goodHours - 1.5) sunNote += ` Tomorrow looks cloudier (${tomorrow.goodHours} h), so do not leave it for then.`;
  if (outlook.rainNext24Mm >= 5) sunNote += ` About ${Math.round(outlook.rainNext24Mm)} mm of rain is forecast in the next 24 hours.`;

  const out = { sunNote };
  if (!(minutes > 0)) return out;
  const from = Math.ceil(outlook.nowLocalMin / 30) * 30;
  const now = sun.bestWindow(today, minutes, from);
  if (now) {
    out.window = { start: now.start, end: now.end }; out.windowDay = "Today";
    if (now.sunShare < 0.6) {
      const t = tomorrow && sun.bestWindow(tomorrow, minutes, 0);
      out.sunNote += " Most of today's sun has passed, so the pump will be slow now" + (t ? `; tomorrow's best time is ${t.start} to ${t.end}.` : ".");
    }
  } else {
    const t = tomorrow && sun.bestWindow(tomorrow, minutes, 0);
    if (t) { out.window = { start: t.start, end: t.end }; out.windowDay = "Tomorrow"; out.sunNote += " Today's sun has gone, so this is tomorrow's best time."; }
  }
  return out;
}

module.exports = { nasaPower, cleanFarm, planForFarm, forecastAt, weatherFor, sunTiming, OPENWEATHER_KEY };
