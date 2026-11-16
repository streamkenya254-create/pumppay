/**
 * The irrigation plan for one farm: NASA weather and the crop model go in, a decision comes out
 * (water today or not, how many litres, how long to pump, when). Shared by the Plant page
 * endpoint and the assistant, so both always quote the same numbers.
 *
 * This is plain helpers, not a Cloud Function: nothing here is exported to the platform.
 */
const admin = require("firebase-admin");
const core = require("./irrigation-core");

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

function cleanFarm(f) {
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
  return farm.lat !== null && farm.lon !== null && farm.crop && farm.plantedOn && farm.areaM2 && farm.flowLpm ? farm : null;
}

/** The decision, plus the figures a screen or an answer needs to explain it. */
async function planForFarm(rawFarm, now = new Date()) {
  const farm = cleanFarm(rawFarm);
  if (!farm) return { ok: false, error: "farm needs lat, lon, crop, plantedOn, areaM2, flowLpm", status: 400 };
  let power;
  try { power = await nasaPower(farm.lat, farm.lon, now); }
  catch (e) { return { ok: false, error: "NASA POWER unavailable", status: 502 }; }
  const { days, elevM } = core.powerToDays(power);

  // Check if Field Shift ML microservice is available
  const mlUrl = process.env.ML_SERVICE_URL || "http://127.0.0.1:5055";
  try {
    const usableDays = days.filter((d) => d && d.tmax != null && d.tmin != null);
    const recentDay = usableDays[usableDays.length - 1] || {};
    const mlResp = await fetch(`${mlUrl}/predict/plan`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        farm: { ...farm, elevM },
        weather: {
          tmax: recentDay.tmax,
          tmin: recentDay.tmin,
          rh: recentDay.rh,
          u2: recentDay.u2,
          rs: recentDay.rs,
          rain: recentDay.rain,
        },
      }),
      signal: AbortSignal.timeout(3000),
    });

    if (mlResp.ok) {
      const mlData = await mlResp.json();
      if (mlData && mlData.ok) {
        const total = core.CROPS[farm.crop]?.stages.reduce((a, b) => a + b, 0) || 120;
        const day = Math.max(0, Math.floor((now - new Date(farm.plantedOn + "T00:00:00Z")) / 864e5));
        const lastUsable = usableDays[usableDays.length - 1];
        return {
          ...mlData,
          day,
          totalDays: total,
          areaM2: farm.areaM2,
          dataThrough: lastUsable ? lastUsable.date : now.toISOString().slice(0, 10).replace(/-/g, ""),
          source: "NASA Earth Observations + Field Shift ML Ensemble",
        };
      }
    }
  } catch (mlErr) {
    // Graceful fallback to pure JS irrigation core when ML service is offline
  }

  // Fallback pure JS calculation
  const plan = core.planIrrigation(days, farm, now, elevM);
  if (!plan.ok) return { ...plan, status: 503 };
  const { series, ...d } = plan;
  const total = core.CROPS[farm.crop].stages.reduce((a, b) => a + b, 0);
  const day = Math.max(0, Math.floor((now - new Date(farm.plantedOn + "T00:00:00Z")) / 864e5));
  return {
    ...d,
    day, totalDays: total, areaM2: farm.areaM2,
    soilLeftPct: Math.round(((d.tawMm - d.depletionMm) / d.tawMm) * 100),
    stressPct: Math.round(((d.tawMm - d.rawMm) / d.tawMm) * 100),
    dailyUseLitres: Math.round((d.etcToday * farm.areaM2) / 10) * 10,
  };
}

module.exports = { nasaPower, cleanFarm, planForFarm };
