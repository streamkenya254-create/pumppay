/**
 * PumpPay sun forecast - when the pump will have power today, tomorrow and the days after.
 * No Firebase, no network: the OpenWeather answer goes in, a pumping outlook comes out.
 *
 * A solar pump has no battery: it runs when the sun is on the panels. OpenWeather's free
 * 5-day forecast gives cloud cover and rain every 3 hours, not sunshine, so sunshine is worked out:
 *
 *   clear-sky sunshine  from the sun's height in the sky for this place, date and time
 *                       (Haurwitz 1945: GHI = 1098 * cos z * exp(-0.057 / cos z), W/m2)
 *   cloud dimming       Kasten & Czeplak 1980: GHI = clear * (1 - 0.75 * cloud^3.4)
 *
 * Half-hour steps through the day, clouds read off between the two nearest forecast points.
 * A half hour is "good for pumping" when the panels can give the pump at least 60% of its rated
 * power (when the pump and panel sizes are known), or else when sunshine is 350 W/m2 or more.
 *
 * This is an estimate from a cloud forecast, and it says so.
 */

const DEG = Math.PI / 180;
const STEP_MIN = 30;
const pad = (n) => String(n).padStart(2, "0");
const hhmm = (mins) => `${pad(Math.floor(mins / 60) % 24)}:${pad(mins % 60)}`;

/** Cosine of the sun's angle from straight overhead (NOAA's short solar position formulas). */
function cosZenith(latDeg, lonDeg, utcMs) {
  const d = new Date(utcMs);
  const doy = Math.floor((Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - Date.UTC(d.getUTCFullYear(), 0, 0)) / 864e5);
  const mins = d.getUTCHours() * 60 + d.getUTCMinutes();
  const g = ((2 * Math.PI) / 365) * (doy - 1 + (mins / 60 - 12) / 24);
  const eqtime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
  const decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
  const solarMins = mins + eqtime + 4 * lonDeg;
  const ha = (solarMins / 4 - 180) * DEG;
  const phi = latDeg * DEG;
  return Math.sin(phi) * Math.sin(decl) + Math.cos(phi) * Math.cos(decl) * Math.cos(ha);
}

/** Sunshine on flat ground under a clear sky, W/m2. */
function clearSkyGhi(latDeg, lonDeg, utcMs) {
  const cz = cosZenith(latDeg, lonDeg, utcMs);
  return cz > 0.01 ? 1098 * cz * Math.exp(-0.057 / cz) : 0;
}

/** How much of the clear-sky sunshine gets through this much cloud (0..1 of sky covered). */
const cloudFactor = (cloud) => 1 - 0.75 * Math.pow(Math.max(0, Math.min(1, cloud)), 3.4);

/**
 * OpenWeather /data/2.5/forecast JSON -> the outlook.
 * opts: lat, lon, now (Date), pumpWatts?, panelWp?
 * Returns null when the answer has no usable forecast points.
 */
function solarOutlook(owm, { lat, lon, now = new Date(), pumpWatts = null, panelWp = null } = {}) {
  const list = owm && Array.isArray(owm.list) ? owm.list.filter((e) => e && typeof e.dt === "number") : [];
  if (!list.length || typeof lat !== "number" || typeof lon !== "number") return null;
  const tz = owm.city && typeof owm.city.timezone === "number" ? owm.city.timezone : Math.round(lon / 15) * 3600;
  const numOr = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);
  const pts = list.map((e) => ({
    t: e.dt * 1000,
    cloud: e.clouds && typeof e.clouds.all === "number" ? e.clouds.all / 100 : 0.5,
    pop: typeof e.pop === "number" ? e.pop : 0,
    rain: e.rain && typeof e.rain["3h"] === "number" ? e.rain["3h"] : 0,
    // For the weather insights: temperature (C), humidity (%), wind (m/s).
    tMax: e.main ? numOr(e.main.temp_max) ?? numOr(e.main.temp) : null,
    tMin: e.main ? numOr(e.main.temp_min) ?? numOr(e.main.temp) : null,
    humidity: e.main ? numOr(e.main.humidity) : null,
    wind: e.wind ? Math.max(numOr(e.wind.speed) ?? 0, 0) : null,
  })).sort((a, b) => a.t - b.t);

  const cloudAt = (ms) => {
    if (ms <= pts[0].t) return pts[0].cloud;
    for (let i = 1; i < pts.length; i++) {
      if (ms <= pts[i].t) { const a = pts[i - 1], b = pts[i]; return a.cloud + ((b.cloud - a.cloud) * (ms - a.t)) / (b.t - a.t); }
    }
    return pts[pts.length - 1].cloud;
  };

  const sized = pumpWatts > 0 && panelWp > 0;
  // 0.8: panels in the field give about 80% of their label after heat, dust and wiring.
  const pumpShare = (ghi) => (sized ? Math.min(1, ((ghi / 1000) * panelWp * 0.8) / pumpWatts) : null);
  const isGood = (ghi) => (sized ? pumpShare(ghi) >= 0.6 : ghi >= 350);

  const localMidnight = (ms) => { const l = new Date(ms + tz * 1000); return Date.UTC(l.getUTCFullYear(), l.getUTCMonth(), l.getUTCDate()) - tz * 1000; };
  const today0 = localMidnight(now.getTime());
  const lastT = pts[pts.length - 1].t;
  const NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

  const days = [];
  for (let n = 0; n < 5; n++) {
    const day0 = today0 + n * 864e5;
    if (day0 + 12 * 3600e3 > lastT + 3 * 3600e3) break;       // the forecast runs out before this day's noon
    const steps = [];
    for (let m = 5 * 60; m <= 19 * 60 + 30; m += STEP_MIN) {
      const ms = day0 + m * 60000;
      const clear = clearSkyGhi(lat, lon, ms + (STEP_MIN / 2) * 60000);
      const cloud = cloudAt(ms);
      steps.push({ m, clear, cloud, ghi: clear * cloudFactor(cloud) });
    }
    const lit = steps.filter((s) => s.clear > 50);
    const inDay = pts.filter((p) => p.t > day0 && p.t <= day0 + 864e5);
    const goodHours = (steps.filter((s) => isGood(s.ghi)).length * STEP_MIN) / 60;
    const clearHours = (steps.filter((s) => isGood(s.clear)).length * STEP_MIN) / 60;
    const local = new Date(day0 + tz * 1000);
    // The day's temperature, wind and humidity, and when its rain falls.
    const have = (k) => inDay.map((p) => p[k]).filter((v) => v !== null);
    const hi = have("tMax"), lo = have("tMin"), winds = have("wind"), hums = have("humidity");
    const part = { morning: 0, afternoon: 0, evening: 0, night: 0 };
    for (const p of inDay) {
      const h = new Date(p.t + tz * 1000).getUTCHours();        // rain is what fell in the 3 hours up to this point
      part[h > 6 && h <= 12 ? "morning" : h > 12 && h <= 18 ? "afternoon" : h > 18 ? "evening" : "night"] += p.rain;
    }
    const wettest = Object.entries(part).sort((a, b) => b[1] - a[1])[0];
    const best3 = bestWindow({ steps }, 180, 0);
    days.push({
      date: `${local.getUTCFullYear()}-${pad(local.getUTCMonth() + 1)}-${pad(local.getUTCDate())}`,
      name: n === 0 ? "Today" : n === 1 ? "Tomorrow" : NAMES[local.getUTCDay()],
      tMaxC: hi.length ? Math.round(Math.max(...hi)) : null,
      tMinC: lo.length ? Math.round(Math.min(...lo)) : null,
      windMaxKmh: winds.length ? Math.round(Math.max(...winds) * 3.6) : null,
      humidityPct: hums.length ? Math.round(hums.reduce((a, b) => a + b, 0) / hums.length) : null,
      rainWhen: wettest[1] > 0 ? wettest[0] : null,
      bestSun: best3 ? best3.start + " to " + best3.end : null,     // the sunniest three hours
      goodHours: +goodHours.toFixed(1),                        // hours the pump can run well
      clearSkyHours: +clearHours.toFixed(1),                   // the same on a cloudless day, for comparison
      sunKwhM2: +(steps.reduce((a, s) => a + s.ghi, 0) * (STEP_MIN / 60) / 1000).toFixed(1),
      cloudPct: lit.length ? Math.round((lit.reduce((a, s) => a + s.cloud, 0) / lit.length) * 100) : null,
      rainMm: +inDay.reduce((a, p) => a + p.rain, 0).toFixed(1),
      rainChancePct: inDay.length ? Math.round(Math.max(...inDay.map((p) => p.pop)) * 100) : null,
      rating: goodHours >= 5 ? "Good sun" : goodHours >= 3 ? "Some cloud" : "Poor sun",
      // One bar an hour, 06:00 to 18:00: sunshine as a share of full sun (1000 W/m2).
      bars: Array.from({ length: 13 }, (_, i) => {
        const hour = steps.filter((s) => s.m >= (6 + i) * 60 && s.m < (7 + i) * 60);
        return Math.round((hour.reduce((a, s) => a + s.ghi, 0) / Math.max(1, hour.length)) / 10);
      }),
      steps,
    });
  }
  if (!days.length) return null;

  const in24 = pts.filter((p) => p.t > now.getTime() && p.t <= now.getTime() + 24 * 3600e3);
  return {
    source: "OpenWeather 5-day forecast",
    tzOffsetMin: tz / 60,
    nowLocalMin: Math.floor(((now.getTime() + tz * 1000) % 864e5) / 60000),
    rainNext24Mm: +in24.reduce((a, p) => a + p.rain, 0).toFixed(1),
    rainChanceNext24Pct: in24.length ? Math.round(Math.max(...in24.map((p) => p.pop)) * 100) : null,
    sized,
    days,
  };
}

/**
 * The best stretch of `minutes` to run the pump on one day of the outlook: the stretch with the
 * most sunshine in it. fromMin = do not start before this minute of the day (the time now, for today).
 * Returns { start, end, sunShare } - sunShare is this stretch's sunshine against the day's best
 * stretch of the same length, so a late start can be seen for what it is - or null.
 */
function bestWindow(day, minutes, fromMin = 0) {
  if (!day || !Array.isArray(day.steps) || !(minutes > 0)) return null;
  const n = Math.max(1, Math.ceil(minutes / STEP_MIN));
  const s = day.steps;
  const sumFrom = (i) => { let t = 0; for (let k = i; k < i + n && k < s.length; k++) t += s[k].ghi; return t; };
  let best = -1, bestI = -1, bestAny = -1;
  for (let i = 0; i + n <= s.length; i++) {
    const e = sumFrom(i);
    if (e > bestAny) bestAny = e;
    if (s[i].m >= fromMin && e > best) { best = e; bestI = i; }
  }
  if (bestI < 0 || best <= 0) return null;
  const start = s[bestI].m;
  return { start: hhmm(start), end: hhmm(start + minutes), sunShare: bestAny > 0 ? +(best / bestAny).toFixed(2) : 1 };
}

/** The outlook without the half-hour working, for sending to the app or the assistant. */
function publicOutlook(o) {
  if (!o) return null;
  return { ...o, days: o.days.map(({ steps, ...d }) => d) };
}

// Crops that late blight takes in cool, wet weather.
const BLIGHT_CROPS = new Set(["tomato", "potato"]);

/**
 * What one day's weather means for the farm, most important first. Only what a farmer acts on:
 * rain (pump or not), sun (when to pump), heat and cold (the crop), wind (spraying, sprinklers),
 * and damp (disease). Nothing is said about a thing that is unremarkable, except rain and sun,
 * which are always answered because they are always the question.
 */
function dayInsights(d, crop = null) {
  const out = [];
  const when = d.rainWhen ? `, mostly in the ${d.rainWhen}` : "";

  // Rain
  if (d.rainMm >= 10) out.push(`Heavy rain likely: about ${Math.round(d.rainMm)} mm${when}. Leave the pump off and let the rain do the watering.`);
  else if (d.rainMm >= 2) out.push(`Some rain likely: about ${Math.round(d.rainMm)} mm${when}. It will do part of the watering.`);
  else if ((d.rainChancePct || 0) >= 50) out.push(`Showers possible (${d.rainChancePct}% chance) but with little water in them. Water as planned.`);
  else out.push("No rain expected.");

  // Sun for the pump
  if (d.rating === "Good sun") out.push(`Good sun for pumping: about ${d.goodHours} hours${d.bestSun ? `, strongest ${d.bestSun}` : ""}.`);
  else if (d.rating === "Some cloud") out.push(`Some cloud: about ${d.goodHours} hours of good pumping${d.bestSun ? `, best ${d.bestSun}` : ""}.`);
  else out.push(`Poor sun: only about ${d.goodHours} hours of good pumping. Pump whenever the sky clears${d.bestSun ? `; the best chance is ${d.bestSun}` : ""}.`);

  // Heat and cold
  if (d.tMaxC !== null && d.tMaxC >= 32) out.push(`Hot, up to ${d.tMaxC}°C: crops drink more. Water early in the day, and mulch to keep the soil cool.`);
  if (d.tMinC !== null && d.tMinC <= 8) out.push(`Cold night, down to ${d.tMinC}°C: seedlings and tender crops may suffer.`);

  // Wind
  if (d.windMaxKmh !== null && d.windMaxKmh >= 29) out.push(`Windy, up to ${d.windMaxKmh} km/h: do not spray, and sprinklers lose water to drift.`);
  else if (d.windMaxKmh !== null && d.windMaxKmh >= 20) out.push(`Breezy, up to ${d.windMaxKmh} km/h: spray only in the calm of early morning.`);

  // Damp: disease weather
  const wet = d.rainMm >= 1 || (d.rainChancePct || 0) >= 60;
  if (d.humidityPct !== null && d.humidityPct >= 85 && wet) {
    if (BLIGHT_CROPS.has(crop) && d.tMaxC !== null && d.tMaxC <= 26) out.push(`Damp and mild: blight weather for ${crop}. Check the leaves, and keep water off them.`);
    else out.push("Damp air and wet leaves spread fungal disease. Water at the roots, not over the leaves, and not late in the day.");
  }

  // A good spraying day is worth saying only when nothing argues against it.
  if (d.rainMm < 1 && (d.rainChancePct || 0) < 30 && d.windMaxKmh !== null && d.windMaxKmh < 15 && out.length <= 2) out.push("Calm and dry: a good day for spraying or top-dressing.");

  return out.slice(0, 4);
}

/**
 * The outlook turned into what to tell the farmer: today in full, the later days by their headline,
 * and one line on the days ahead.
 *   { source, today: { name, date, summary, insights[] }, later: [{ name, date, headline, insights[] }], ahead }
 */
function weatherInsights(outlook, { crop = null } = {}) {
  if (!outlook || !outlook.days || !outlook.days.length) return null;
  const facts = (d) => {
    const bits = [];
    if (d.tMinC !== null && d.tMaxC !== null) bits.push(`${d.tMinC} to ${d.tMaxC}°C`);
    bits.push(d.rainMm >= 1 ? `${Math.round(d.rainMm)} mm of rain` : "dry");
    bits.push(`${d.goodHours} h of pumping sun`);
    return bits.join(", ");
  };
  const [first, ...rest] = outlook.days;
  const later = rest.map((d) => {
    const ins = dayInsights(d, crop);
    // The headline is the thing to plan around: rain if any is coming, otherwise the sun.
    const headline = d.rainMm >= 2 ? ins[0] : ins[1] || ins[0];
    return { name: d.name, date: d.date, summary: facts(d), headline, insights: ins };
  });

  let ahead = "";
  if (rest.length) {
    const wettest = rest.reduce((a, b) => (b.rainMm > a.rainMm ? b : a));
    const sunniest = rest.reduce((a, b) => (b.goodHours > a.goodHours ? b : a));
    ahead = wettest.rainMm >= 2
      ? `Wettest day ahead: ${wettest.name}, about ${Math.round(wettest.rainMm)} mm. Best pumping day: ${sunniest.name}, about ${sunniest.goodHours} hours.`
      : `No real rain in the next ${rest.length} days, so the crop depends on the pump. Best pumping day: ${sunniest.name}, about ${sunniest.goodHours} hours.`;
  }
  return {
    source: outlook.source,
    today: { name: first.name, date: first.date, summary: facts(first), insights: dayInsights(first, crop) },
    later, ahead,
  };
}

module.exports = { cosZenith, clearSkyGhi, cloudFactor, solarOutlook, bestWindow, publicOutlook, dayInsights, weatherInsights };
