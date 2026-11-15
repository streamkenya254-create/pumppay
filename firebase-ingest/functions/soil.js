/**
 * Soil health and crop rotation for one farm: the soil map, the place's climate and the farm's
 * height go in; a soil score and "what to plant next" come out. Shared by the Farm page endpoint
 * and the assistant, so both always quote the same numbers.
 *
 * Plain helpers, not a Cloud Function: nothing here is exported to the platform (see plan.js).
 *
 * Outside data, each kept in Firestore after the first fetch because none of it changes:
 *   pumppay_soil/{lat_lon}      ISRIC SoilGrids topsoil values (a 250 m map). Slow: 10-30 s a call.
 *   pumppay_climate/{lat_lon}   NASA POWER monthly climatology (25-year averages)
 *   pumppay_elev/{lat_lon}      height above sea level, NASA SRTM, served by opentopodata.org
 * None needs a key.
 */
const admin = require("firebase-admin");
const core = require("./irrigation-core");
const soilCore = require("./soil-core");
const rotation = require("./rotation-core");
const { nasaPower, cleanFarm } = require("./plan");

const db = admin.firestore();
const num = (v, lo, hi) => { const n = Number(v); return v !== null && v !== "" && Number.isFinite(n) && n >= lo && n <= hi ? n : null; };
const isDate = (s) => typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && Number.isFinite(Date.parse(s + "T00:00:00Z"));

const SOILGRIDS = "https://rest.isric.org/soilgrids/v2.0/properties/query";
function soilGridsUrl(lat, lon) {
  const props = ["phh2o", "soc", "clay", "sand", "silt", "nitrogen", "cec"].map((p) => "property=" + p).join("&");
  const depths = ["0-5cm", "5-15cm", "15-30cm"].map((d) => "depth=" + d).join("&");
  return `${SOILGRIDS}?lon=${lon.toFixed(5)}&lat=${lat.toFixed(5)}&${props}&${depths}&value=mean`;
}

async function fetchGrid(lat, lon) {
  const r = await fetch(soilGridsUrl(lat, lon), { signal: AbortSignal.timeout(50000), headers: { accept: "application/json" } });
  if (!r.ok) throw new Error(`SoilGrids ${r.status}`);
  return soilCore.parseSoilGrids(await r.json());
}

/**
 * The soil map at the farm's pin.
 *   { grid, movedKm }  found (movedKm > 0 when it had to look beside the pin)
 *   null               the map has nothing here or nearby
 *   undefined          not looked up yet, and cacheOnly said not to wait for it
 * Throws when the map service cannot be reached.
 *
 * The map is blank over towns and water, and people often drop the pin on the nearest town. So a
 * blank pin is retried about 3 km north, east, south and west, and the ground found there is
 * averaged. Five calls at most, inside SoilGrids' fair-use limit of five a minute.
 */
async function soilAt(lat, lon, { cacheOnly = false } = {}) {
  const ref = db.doc(`pumppay_soil/${lat.toFixed(3)}_${lon.toFixed(3)}`);
  const snap = await ref.get();
  if (snap.exists) {
    const d = snap.data();
    if (d.grid) return { grid: d.grid, movedKm: d.movedKm || 0 };
    if (Date.now() - (d.at || 0) < 7 * 864e5) return null;      // blank here: ask again after a week
  }
  if (cacheOnly) return undefined;

  let grid = await fetchGrid(lat, lon), movedKm = 0;
  if (!grid) {
    const step = 0.03;
    const around = await Promise.allSettled([[step, 0], [0, step], [-step, 0], [0, -step]].map(([a, b]) => fetchGrid(lat + a, lon + b)));
    const hits = around.filter((t) => t.status === "fulfilled" && t.value).map((t) => t.value);
    if (!hits.length && around.every((t) => t.status === "rejected")) throw new Error("SoilGrids unavailable");
    if (hits.length) {
      grid = {};
      for (const k of Object.keys(hits[0])) {
        const vals = hits.map((h) => h[k]).filter((v) => typeof v === "number");
        grid[k] = vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
      }
      movedKm = 3;
    }
  }
  await ref.set({ grid: grid || null, movedKm, at: Date.now() });
  return grid ? { grid, movedKm } : null;
}

/** NASA POWER's monthly averages for the place: rain, temperature, humidity, wind and sunshine. */
async function climateAt(lat, lon) {
  const ref = db.doc(`pumppay_climate/${lat.toFixed(1)}_${lon.toFixed(1)}`);
  const snap = await ref.get();
  if (snap.exists && snap.data().json) return snap.data().json;
  const url = "https://power.larc.nasa.gov/api/temporal/climatology/point?parameters=PRECTOTCORR,T2M,T2M_RANGE,RH2M,WS2M,ALLSKY_SFC_SW_DWN" +
    `&community=AG&latitude=${lat}&longitude=${lon}&format=JSON`;
  const r = await fetch(url, { signal: AbortSignal.timeout(20000) });
  if (!r.ok) throw new Error(`NASA POWER climatology ${r.status}`);
  const json = await r.json();
  await ref.set({ json, at: Date.now() });
  return json;
}

/** The farm's height above sea level in metres (NASA SRTM), or null. Never throws: it only sharpens the temperatures. */
async function elevationAt(lat, lon) {
  try {
    const ref = db.doc(`pumppay_elev/${lat.toFixed(3)}_${lon.toFixed(3)}`);
    const snap = await ref.get();
    if (snap.exists && typeof snap.data().m === "number") return snap.data().m;
    const r = await fetch(`https://api.opentopodata.org/v1/srtm90m?locations=${lat.toFixed(5)},${lon.toFixed(5)}`, { signal: AbortSignal.timeout(10000) });
    if (!r.ok) return null;
    const m = ((await r.json()).results || [])[0];
    const elev = m && typeof m.elevation === "number" ? m.elevation : null;
    if (elev !== null) await ref.set({ m: elev, at: Date.now() });
    return elev;
  } catch (e) { return null; }
}

/** Finished seasons, oldest first: [{ crop, plantedOn?, harvestedOn? }]. Anything unrecognised is dropped. */
function cleanSeasons(list) {
  const out = [];
  for (const s of (Array.isArray(list) ? list : []).slice(-12)) {
    const crop = typeof s === "string" ? s : s && s.crop;
    if (!core.CROPS[crop]) continue;
    const row = { crop };
    if (s && isDate(s.plantedOn)) row.plantedOn = s.plantedOn;
    if (s && isDate(s.harvestedOn)) row.harvestedOn = s.harvestedOn;
    out.push(row);
  }
  return out;
}

/** The farmer's own lab report: pH, organic matter %, total nitrogen %. Null when none of it is given. */
function cleanTest(t) {
  if (!t || typeof t !== "object") return null;
  const test = { ph: num(t.ph, 3, 10.5), omPct: num(t.omPct, 0.05, 40), nPct: num(t.nPct, 0.005, 3) };
  return test.ph === null && test.omPct === null && test.nPct === null ? null : test;
}

/** Litres pumped per day, { "YYYYMMDD": litres }. Null when the app sent no pump record at all. */
function cleanApplied(a) {
  if (!a || typeof a !== "object" || Array.isArray(a)) return null;
  const out = {};
  for (const [k, v] of Object.entries(a).slice(0, 60)) {
    const n = num(v, 0, 5e6);
    if (/^\d{8}$/.test(k) && n !== null) out[k] = n;
  }
  return out;
}

const SOIL_WAIT = {
  pending: "Reading the soil map for your farm. This can take a minute the first time.",
  none: "The soil map is blank at this spot, as it is over towns and water. Move the pin onto your field, or enter a soil test.",
  unavailable: "The soil map service is not answering right now. Try again later, or enter a soil test.",
};

/**
 * Soil health and rotation for one farm.
 * raw = what the app holds: { lat, lon, crop?, plantedOn?, areaM2?, flowLpm?, efficiency?, soil?,
 *        seasons?: [...], soilTest?: {ph, omPct, nPct}, applied?: {"YYYYMMDD": litres} }
 * wait = false returns at once with whatever soil is already stored (the assistant uses this; the
 *        first read of the soil map is too slow to hold a chat answer for).
 */
async function farmHealth(raw, { now = new Date(), wait = true } = {}) {
  const f = raw && typeof raw === "object" ? raw : {};
  const lat = num(f.lat, -60, 60), lon = num(f.lon, -180, 180);
  if (lat === null || lon === null || (lat === 0 && lon === 0)) return { ok: false, status: 400, error: "Set where the farm is first." };

  const farm = cleanFarm(f, true);                 // null until a crop, planting date and field size are saved
  const seasons = cleanSeasons(f.seasons);
  const test = cleanTest(f.soilTest);
  const applied = cleanApplied(f.applied);
  const crop = core.CROPS[f.crop] ? f.crop : null;

  const [soilR, climR, elev, seriesR] = await Promise.all([
    soilAt(lat, lon, { cacheOnly: !wait }).then((v) => ({ v }), (e) => ({ err: e })),
    climateAt(lat, lon).then((v) => ({ v }), (e) => ({ err: e })),
    elevationAt(lat, lon),
    (async () => {
      if (!farm || !applied) return null;
      try {
        const { days, elevM } = core.powerToDays(await nasaPower(farm.lat, farm.lon, now));
        const plan = core.planIrrigation(days, farm, now, elevM);
        return plan.ok ? plan.series : null;
      } catch (e) { return null; }
    })(),
  ]);

  // ---- Soil ----
  const mapState = soilR.err ? "unavailable" : soilR.v === undefined ? "pending" : soilR.v === null ? "none" : "ok";
  const grid = mapState === "ok" ? soilR.v.grid : null;
  const watering = seriesR ? soilCore.wateringReview(seriesR, farm, applied) : null;
  let soil = soilCore.soilHealth({ grid, test, crop, watering });
  if (!soil.ok) soil = { ok: false, error: SOIL_WAIT[mapState] || soil.error };
  soil.mapState = mapState;
  if (mapState !== "ok" && soil.ok) soil.mapNote = SOIL_WAIT[mapState];
  if (mapState === "ok" && soilR.v.movedKm) soil.mapNote = "The soil map is blank at your pin (it is over towns and water), so this is the ground about 3 km around it. Put the pin on your field for a closer reading.";
  soil.source = soil.ok
    ? (soil.estimated ? "ISRIC SoilGrids soil map (250 m estimate)" : "") + (soil.estimated && soil.usedTest ? " and " : "") + (soil.usedTest ? "your soil test" : "")
    : "";

  // ---- Rotation ----
  const history = seasons.slice();
  if (crop && isDate(f.plantedOn)) history.push({ crop, plantedOn: f.plantedOn });
  const climate = climR.v ? rotation.climateMonths(climR.v, elev) : null;
  const pump = {
    lat, areaM2: num(f.areaM2, 10, 5e6), flowLpm: num(f.flowLpm, 1, 20000),
    efficiency: num(f.efficiency, 0.4, 0.95) ?? 0.75, solarHours: farm ? farm.solarHours : 6,
  };
  const rot = rotation.recommend({
    history, climate, farm: pump, today: now,
    soil: soil.ok ? { ph: soil.ph, nitrogenLow: soil.nitrogenLow } : null,
  });
  rot.history = seasons;
  rot.climateSource = climate ? "NASA POWER 25-year monthly averages" + (climate.tempShiftC ? `, temperatures moved to the farm's height (${Math.round(elev)} m)` : "") : null;

  return { ok: true, soil, rotation: rot, elevM: elev, at: now.toISOString() };
}

/**
 * Where the farm is, kept with the signed-in account: latitude, longitude and the exact name of
 * the place as the phone worked it out ("Kibos, Kisumu East, Kisumu"). One document per account,
 *   pumppay_farms/{uid} = { lat, lon, place, serial?, updatedAt }
 * so the location survives a new phone. Only ever written for a verified sign-in: a controller's
 * serial is printed on its case, so a serial alone must not be able to move someone's farm.
 * Returns what was stored, or null when there is no usable position.
 */
async function saveFarmPlace(uid, raw, serial = null) {
  const f = raw && typeof raw === "object" ? raw : {};
  const lat = num(f.lat, -60, 60), lon = num(f.lon, -180, 180);
  if (!uid || lat === null || lon === null || (lat === 0 && lon === 0)) return null;
  const place = typeof f.place === "string" ? f.place.replace(/[\u0000-\u001f]+/g, " ").trim().slice(0, 120) : "";
  const doc = { lat: +lat.toFixed(5), lon: +lon.toFixed(5), place, updatedAt: Date.now() };
  if (serial) doc.serial = serial;
  await db.doc(`pumppay_farms/${uid}`).set(doc, { merge: true });
  return doc;
}

module.exports = { soilAt, climateAt, elevationAt, farmHealth, saveFarmPlace, cleanSeasons, cleanTest, cleanApplied, soilGridsUrl };
