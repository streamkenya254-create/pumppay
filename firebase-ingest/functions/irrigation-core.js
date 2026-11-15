/**
 * PumpPay irrigation advisor - the maths, with no Firebase and no network.
 *
 * Turns NASA POWER daily weather + a farm profile into "pump N minutes today,
 * here is why". Kept pure so it can be unit-tested and reused (app, admin, pitch).
 *
 * Method: FAO-56 (Allen et al. 1998).
 *   ET0  = reference evapotranspiration, Penman-Monteith (Hargreaves if RH/wind missing)
 *   ETc  = Kc x ET0, Kc from crop + days since planting
 *   Dr   = root-zone depletion, stepped day by day: Dr += ETc - Peff
 *   TAW  = soil available water x root depth;  RAW = p x TAW
 *   Irrigate when Dr >= RAW. Net = Dr (refill to field capacity), gross = net / efficiency.
 *   1 mm over 1 m2 = 1 litre.
 *
 * NASA POWER parameters used (community=AG, daily):
 *   T2M_MAX, T2M_MIN (C)   RH2M (%)   WS2M (m/s)   ALLSKY_SFC_SW_DWN (MJ/m2/day)
 *   PRECTOTCORR (mm/day)   GWETROOT (0-1 root-zone soil wetness, used to seed Dr)
 */

const FILL = -999;
const ok = (v) => typeof v === "number" && Number.isFinite(v) && v > FILL + 1;

// ---- Crops: FAO-56 Table 11/12 stage lengths (days) and Kc, East-Africa-ish defaults
const CROPS = {
  maize:    { stages: [25, 40, 45, 30], kc: [0.3, 1.2, 0.35], rootM: [0.3, 1.0], p: 0.55 },
  beans:    { stages: [20, 30, 40, 20], kc: [0.4, 1.15, 0.35], rootM: [0.3, 0.6], p: 0.45 },
  tomato:   { stages: [30, 40, 45, 30], kc: [0.6, 1.15, 0.8], rootM: [0.3, 0.8], p: 0.40 },
  kale:     { stages: [20, 30, 50, 15], kc: [0.7, 1.05, 0.95], rootM: [0.2, 0.5], p: 0.45 }, // sukuma wiki (cabbage family)
  cabbage:  { stages: [40, 60, 50, 15], kc: [0.7, 1.05, 0.95], rootM: [0.3, 0.6], p: 0.45 },
  onion:    { stages: [15, 25, 70, 40], kc: [0.7, 1.05, 0.75], rootM: [0.2, 0.4], p: 0.30 },
  potato:   { stages: [25, 30, 45, 30], kc: [0.5, 1.15, 0.75], rootM: [0.3, 0.5], p: 0.35 },
  watermelon:{ stages: [20, 30, 30, 30], kc: [0.4, 1.0, 0.75], rootM: [0.3, 1.0], p: 0.40 },
  // Added for crop rotation: three more legumes and a light-feeding root crop (FAO-56 Tables 11, 12, 22).
  cowpea:   { stages: [20, 30, 30, 20], kc: [0.4, 1.05, 0.35], rootM: [0.3, 0.8], p: 0.45 },
  green_gram:{ stages: [20, 30, 30, 20], kc: [0.4, 1.05, 0.35], rootM: [0.3, 0.8], p: 0.45 }, // ndengu
  groundnut:{ stages: [25, 35, 45, 25], kc: [0.4, 1.15, 0.6], rootM: [0.3, 0.8], p: 0.50 },
  sweet_potato:{ stages: [15, 30, 50, 30], kc: [0.5, 1.15, 0.65], rootM: [0.3, 1.0], p: 0.65 },
};

// Available water (mm per m of soil) by texture - FAO-56 Table 19 mid values.
// Swap for ISRIC SoilGrids / SSURGO values when you have them for the farm.
const SOIL_AW = { sand: 70, loamy_sand: 90, sandy_loam: 120, loam: 150, clay_loam: 170, clay: 180 };

function cropState(cropName, daysSincePlanting) {
  const c = CROPS[cropName] || CROPS.maize;
  const [ini, dev, mid, late] = c.stages;
  const d = Math.max(0, daysSincePlanting);
  let kc, stage;
  if (d < ini) { kc = c.kc[0]; stage = "initial"; }
  else if (d < ini + dev) { kc = c.kc[0] + (c.kc[1] - c.kc[0]) * (d - ini) / dev; stage = "development"; }
  else if (d < ini + dev + mid) { kc = c.kc[1]; stage = "mid-season"; }
  else if (d < ini + dev + mid + late) { kc = c.kc[1] + (c.kc[2] - c.kc[1]) * (d - ini - dev - mid) / late; stage = "late"; }
  else { kc = c.kc[2]; stage = "harvest"; }
  const total = ini + dev + mid + late;
  const rootM = c.rootM[0] + (c.rootM[1] - c.rootM[0]) * Math.min(1, d / (ini + dev));
  return { kc, stage, rootM, p: c.p, seasonDays: total };
}

// ---- FAO-56 reference ET -------------------------------------------------
const es = (T) => 0.6108 * Math.exp((17.27 * T) / (T + 237.3));

function extraterrestrialRad(latDeg, doy) {
  const phi = (latDeg * Math.PI) / 180;
  const dr = 1 + 0.033 * Math.cos((2 * Math.PI * doy) / 365);
  const delta = 0.409 * Math.sin((2 * Math.PI * doy) / 365 - 1.39);
  const ws = Math.acos(Math.max(-1, Math.min(1, -Math.tan(phi) * Math.tan(delta))));
  return ((24 * 60) / Math.PI) * 0.082 * dr *
    (ws * Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.sin(ws));
}

/** Penman-Monteith, daily. rh = mean RH %, rs = MJ/m2/day. Returns mm/day. */
function et0PenmanMonteith({ tmax, tmin, rh, u2, rs, latDeg, elevM, doy }) {
  const tmean = (tmax + tmin) / 2;
  const P = 101.3 * Math.pow((293 - 0.0065 * elevM) / 293, 5.26);
  const gamma = 0.000665 * P;
  const delta = (4098 * es(tmean)) / Math.pow(tmean + 237.3, 2);
  const esat = (es(tmax) + es(tmin)) / 2;
  const ea = (rh / 100) * esat;
  const ra = extraterrestrialRad(latDeg, doy);
  const rso = (0.75 + 2e-5 * elevM) * ra;
  const rns = 0.77 * rs;
  const sigma = 4.903e-9;
  const rnl = sigma * ((Math.pow(tmax + 273.16, 4) + Math.pow(tmin + 273.16, 4)) / 2) *
    (0.34 - 0.14 * Math.sqrt(Math.max(0, ea))) * (1.35 * Math.min(1, rs / rso) - 0.35);
  const rn = rns - rnl;
  const num = 0.408 * delta * rn + gamma * (900 / (tmean + 273)) * u2 * (esat - ea);
  const den = delta + gamma * (1 + 0.34 * u2);
  return Math.max(0, num / den);
}

/** Hargreaves fallback when humidity or wind is missing. mm/day. */
function et0Hargreaves({ tmax, tmin, latDeg, doy }) {
  const ra = extraterrestrialRad(latDeg, doy);
  return Math.max(0, 0.0023 * ((tmax + tmin) / 2 + 17.8) * Math.sqrt(Math.max(0, tmax - tmin)) * ra * 0.408);
}

// Effective rainfall: small showers evaporate off the surface, heavy ones run off.
function effectiveRain(p) {
  if (!ok(p) || p < 5) return 0;
  return Math.min(p * 0.75, 40);
}

const doyOf = (d) => Math.floor((Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - Date.UTC(d.getUTCFullYear(), 0, 0)) / 864e5);
const ymdToDate = (s) => new Date(Date.UTC(+s.slice(0, 4), +s.slice(4, 6) - 1, +s.slice(6, 8)));

/**
 * POWER JSON (properties.parameter) -> array of daily rows, oldest first.
 */
function powerToDays(powerJson) {
  const p = powerJson.properties.parameter;
  const coords = (powerJson.geometry && powerJson.geometry.coordinates) || [];
  const dates = Object.keys(p.T2M_MAX || {}).sort();
  return {
    elevM: ok(coords[2]) ? coords[2] : null,
    days: dates.map((k) => ({
      date: k,
      tmax: p.T2M_MAX?.[k], tmin: p.T2M_MIN?.[k], rh: p.RH2M?.[k], u2: p.WS2M?.[k],
      rs: p.ALLSKY_SFC_SW_DWN?.[k], rain: p.PRECTOTCORR?.[k], gwetRoot: p.GWETROOT?.[k],
    })),
  };
}

/**
 * The decision.
 * farm = { lat, lon, elevM?, crop, plantedOn:"YYYY-MM-DD", areaM2, flowLpm,
 *          efficiency (drip 0.9 / sprinkler 0.75 / furrow 0.6), soil:"loam",
 *          fcSat?: 0.6, wpSat?: 0.3   // field capacity / wilting point as fraction of saturation, to read GWETROOT
 *          tankLitres?, rainForecastMm? }
 * today = Date
 */
function planIrrigation(days, farm, today = new Date(), elevFromPower = null) {
  const lat = farm.lat;
  const elevM = ok(farm.elevM) ? farm.elevM : (ok(elevFromPower) ? elevFromPower : 1150);
  const eff = Math.min(0.95, Math.max(0.4, farm.efficiency || 0.75));
  const aw = SOIL_AW[farm.soil] || SOIL_AW.loam;
  const planted = new Date(farm.plantedOn + "T00:00:00Z");
  const reasons = [];

  // Usable days only (POWER marks missing as -999, and the newest 1-3 days are often missing).
  const usable = days.filter((d) => ok(d.tmax) && ok(d.tmin));
  if (!usable.length) return { ok: false, error: "no NASA POWER data for this location yet" };

  // Seed root-zone depletion from NASA's root-zone soil wetness (GWETROOT) on the
  // first usable day: map wetness between wilting point and field capacity onto 0..TAW.
  const fcSat = farm.fcSat ?? 0.62, wpSat = farm.wpSat ?? 0.30;
  const first = usable[0];
  const cs0 = cropState(farm.crop, (ymdToDate(first.date) - planted) / 864e5);
  let taw = aw * cs0.rootM;
  let Dr;
  if (ok(first.gwetRoot)) {
    const rel = Math.max(0, Math.min(1, (first.gwetRoot - wpSat) / (fcSat - wpSat)));
    Dr = (1 - rel) * taw;
  } else {
    Dr = 0.5 * taw;
    reasons.push("No soil-wetness reading from NASA, so the model assumed the soil started half dry.");
  }

  const series = [];
  for (const d of usable) {
    const date = ymdToDate(d.date);
    const cs = cropState(farm.crop, (date - planted) / 864e5);
    taw = aw * cs.rootM;
    const doy = doyOf(date);
    const pm = ok(d.rh) && ok(d.u2) && ok(d.rs);
    const et0 = pm
      ? et0PenmanMonteith({ tmax: d.tmax, tmin: d.tmin, rh: d.rh, u2: d.u2, rs: d.rs, latDeg: lat, elevM, doy })
      : et0Hargreaves({ tmax: d.tmax, tmin: d.tmin, latDeg: lat, doy });
    const etc = cs.kc * et0;
    const peff = effectiveRain(d.rain);
    Dr = Math.max(0, Math.min(taw, Dr + etc - peff));
    series.push({ date: d.date, et0: +et0.toFixed(2), kc: +cs.kc.toFixed(2), etc: +etc.toFixed(2),
      rain: ok(d.rain) ? +d.rain.toFixed(1) : null, peff: +peff.toFixed(1), Dr: +Dr.toFixed(1),
      gwetRoot: ok(d.gwetRoot) ? d.gwetRoot : null, method: pm ? "PM" : "HG" });
  }

  // Carry the balance forward over the days NASA hasn't published yet (latency),
  // using the average of the last 3 usable days' crop demand.
  const last = series[series.length - 1];
  const todayYmd = today.toISOString().slice(0, 10).replace(/-/g, "");
  const gapDays = Math.max(0, Math.round((ymdToDate(todayYmd) - ymdToDate(last.date)) / 864e5));
  const recent = series.slice(-3);
  const etcAvg = recent.reduce((s, r) => s + r.etc, 0) / recent.length;
  const csToday = cropState(farm.crop, (ymdToDate(todayYmd) - planted) / 864e5);
  taw = aw * csToday.rootM;
  const raw = csToday.p * taw;
  Dr = Math.min(taw, Dr + etcAvg * gapDays);
  if (gapDays > 0) reasons.push(`NASA POWER is ${gapDays} day(s) behind; the model assumed ${etcAvg.toFixed(1)} mm/day crop use for those days.`);

  const rain2d = series.slice(-2).reduce((s, r) => s + (r.rain || 0), 0);
  const rainFc = ok(farm.rainForecastMm) ? farm.rainForecastMm : 0;

  let action = "skip", netMm = 0;
  if (Dr >= raw) {
    action = "irrigate"; netMm = Dr;
    reasons.push(`Root zone has used ${Dr.toFixed(0)} of ${taw.toFixed(0)} mm available; the stress point for ${farm.crop} is ${raw.toFixed(0)} mm.`);
  } else if (Dr >= 0.7 * raw && etcAvg >= 4) {
    action = "light"; netMm = Dr * 0.5;
    reasons.push(`Soil is getting dry (${Dr.toFixed(0)} of ${raw.toFixed(0)} mm allowed) and demand is high (${etcAvg.toFixed(1)} mm/day), so a top-up is due.`);
  } else {
    reasons.push(`Soil still holds enough water: ${Dr.toFixed(0)} mm used out of the ${raw.toFixed(0)} mm allowed.`);
  }
  if (action !== "skip" && rainFc >= 10) {
    reasons.push(`${rainFc} mm of rain is forecast, so irrigation is reduced to cover only what rain won't.`);
    netMm = Math.max(0, netMm - effectiveRain(rainFc));
    if (netMm < 2) action = "skip";
  }
  if (action !== "skip" && rain2d >= 15) {
    reasons.push(`${rain2d.toFixed(0)} mm of rain fell in the last 2 days of NASA data; check the field before pumping.`);
  }

  const grossMm = netMm / eff;
  const needLitres = Math.round(grossMm * farm.areaM2);

  // What the pump can actually deliver today: flow x usable solar hours
  // (default 6 h, centred on solar noon). Anything beyond that rolls over.
  const solarHours = ok(farm.solarHours) ? farm.solarHours : 6;
  const capLitres = farm.flowLpm > 0 ? Math.round(farm.flowLpm * 60 * solarHours) : needLitres;
  const litres = Math.min(needLitres, capLitres);
  const minutes = farm.flowLpm > 0 ? Math.round(litres / farm.flowLpm) : null;
  const daysToRefill = capLitres > 0 ? Math.ceil(needLitres / capLitres) : null;
  if (needLitres > capLitres) {
    reasons.push(`The field needs ${needLitres.toLocaleString()} L but the pump moves ${capLitres.toLocaleString()} L in ${solarHours} solar hours, so pump the full window today. Catching up will take about ${daysToRefill} days.`);
  }
  if (ok(farm.tankLitres) && litres > farm.tankLitres) reasons.push(`Today's ${litres.toLocaleString()} L is more than the ${farm.tankLitres.toLocaleString()} L tank, so pump straight to the field or refill the tank during the solar peak.`);

  // Solar pumping window: start so the run is centred on 12:30 local time.
  let window = null;
  if (minutes) {
    const startMin = Math.max(7 * 60, Math.round(12.5 * 60 - minutes / 2));
    const st = `${String(Math.floor(startMin / 60)).padStart(2, "0")}:${String(startMin % 60).padStart(2, "0")}`;
    window = { start: st, end: addMins(st, minutes) };
  }

  return {
    ok: true,
    action,                       // "irrigate" | "light" | "skip"
    crop: farm.crop, stage: csToday.stage, kc: +csToday.kc.toFixed(2),
    et0Today: +(recent.reduce((s, r) => s + r.et0, 0) / recent.length).toFixed(2),
    etcToday: +etcAvg.toFixed(2),
    depletionMm: +Dr.toFixed(1), tawMm: +taw.toFixed(1), rawMm: +raw.toFixed(1),
    netMm: +netMm.toFixed(1), grossMm: +grossMm.toFixed(1),
    needLitres, litres, minutes, daysToRefill, window, reasons,
    dataThrough: last.date, source: "NASA POWER (AG community)", series,
  };
}

function addMins(hhmm, m) {
  const [h, mm] = hhmm.split(":").map(Number);
  const t = h * 60 + mm + m;
  return `${String(Math.floor(t / 60) % 24).padStart(2, "0")}:${String(t % 60).padStart(2, "0")}`;
}

function powerUrl(lat, lon, startYmd, endYmd) {
  const params = "T2M_MAX,T2M_MIN,RH2M,WS2M,ALLSKY_SFC_SW_DWN,PRECTOTCORR,GWETROOT";
  return `https://power.larc.nasa.gov/api/temporal/daily/point?parameters=${params}` +
    `&community=AG&latitude=${lat}&longitude=${lon}&start=${startYmd}&end=${endYmd}&format=JSON`;
}

module.exports = { CROPS, SOIL_AW, cropState, et0PenmanMonteith, et0Hargreaves, effectiveRain, powerToDays, planIrrigation, powerUrl };
