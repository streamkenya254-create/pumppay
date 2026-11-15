/**
 * PumpPay irrigation plan - NASA data in, "pump N minutes today" out.
 *
 *   POST https://us-central1-<project>.cloudfunctions.net/pumppayIrrigationPlan
 *   body: {"serial":"PP-1A2B3C","key":"<PUMPPAY_INGEST_KEY>"}
 *   -> {"ok":true,"action":"irrigate","litres":21600,"minutes":360,
 *       "window":{"start":"09:30","end":"15:30"},"reasons":[...], ...}
 *
 *   POST .../pumppayAdminFarm      (PUMPPAY_ADMIN_KEY)
 *   body: {"key":"...","serial":"PP-1A2B3C","farm":{
 *          "lat":-0.09,"lon":34.75,"crop":"tomato","plantedOn":"2026-09-20",
 *          "areaM2":4000,"flowLpm":60,"efficiency":0.9,"soil":"clay_loam",
 *          "tankLitres":10000,"solarHours":6}}
 *
 * WHAT IT WRITES, and only this:
 *   pumppay_devices/{serial}.farm            the farm profile (admin only)
 *   pumppay_devices/{serial}.plan            today's plan (overwritten)
 *   pumppay_weather/{lat_lon_ymd}            NASA POWER response, cached for the day
 *
 * The controller can call the plan endpoint once a day after its first upload,
 * show it on the LCD and, later, auto-run the pump in the window. The maths
 * lives in irrigation-core.js (FAO-56, tested against FAO's worked example).
 */
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");
const core = require("./irrigation-core");

const db = admin.firestore();
const INGEST_KEY = defineSecret("PUMPPAY_INGEST_KEY");
const ADMIN_KEY = defineSecret("PUMPPAY_ADMIN_KEY");

function keyMatches(given, expected) {
  const a = Buffer.from(String(given || ""));
  const b = Buffer.from(String(expected || ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
const { nasaPower, cleanFarm, planForFarm, OPENWEATHER_KEY } = require("./plan");
const { farmHealth, saveFarmPlace } = require("./soil");

exports.pumppayIrrigationPlan = onRequest(
  { secrets: [INGEST_KEY], region: "us-central1", cors: false, maxInstances: 5, timeoutSeconds: 30 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};
    if (!keyMatches(b.key, INGEST_KEY.value())) return res.status(401).json({ error: "unauthorized" });

    const serial = String(b.serial || "").toUpperCase();
    if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ error: "bad serial" });

    const ref = db.doc(`pumppay_devices/${serial}`);
    const snap = await ref.get();
    const farm = snap.exists ? cleanFarm(snap.data().farm) : null;
    if (!farm) return res.status(404).json({ error: "no farm profile - set one with pumppayAdminFarm" });

    const today = new Date();
    let power;
    try { power = await nasaPower(farm.lat, farm.lon, today); }
    catch (e) { return res.status(502).json({ error: "NASA POWER unavailable", detail: String(e.message || e) }); }

    const { days, elevM } = core.powerToDays(power);
    const plan = core.planIrrigation(days, farm, today, elevM);
    if (!plan.ok) return res.status(503).json(plan);

    // Keep the series out of the device doc (it is in the cache); store the decision.
    const { series, ...decision } = plan;
    await ref.set({ plan: { ...decision, at: admin.firestore.FieldValue.serverTimestamp() } }, { merge: true });
    return res.status(200).json(b.series ? plan : decision);
  }
);

exports.pumppayAdminFarm = onRequest(
  { secrets: [ADMIN_KEY], region: "us-central1", cors: false, maxInstances: 2, timeoutSeconds: 15 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};
    if (!keyMatches(b.key, ADMIN_KEY.value())) return res.status(401).json({ error: "unauthorized" });
    const serial = String(b.serial || "").toUpperCase();
    if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ error: "bad serial" });
    const farm = cleanFarm(b.farm);
    if (!farm) return res.status(400).json({ error: "farm needs lat, lon, crop, plantedOn (YYYY-MM-DD), areaM2, flowLpm", crops: Object.keys(core.CROPS), soils: Object.keys(core.SOIL_AW) });
    await db.doc(`pumppay_devices/${serial}`).set({ farm }, { merge: true });
    return res.status(200).json({ ok: true, farm });
  }
);

// The app's Plant page: the farmer's own crop details in, today's plan out.
// No key - the app holds none - so it is gated on the serial belonging to a real,
// registered controller, writes nothing, and only reads public NASA data.
// The OpenWeather key adds the sun and rain forecast that times the pumping (see plan.js).
exports.pumppayCropPlan = onRequest(
  { secrets: [OPENWEATHER_KEY], region: "us-central1", cors: false, maxInstances: 3, timeoutSeconds: 30 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};
    const serial = String(b.serial || "").toUpperCase();
    if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ error: "bad serial" });
    if (!(await db.doc(`pumppay_devices/${serial}`).get()).exists) return res.status(404).json({ error: "unknown controller" });

    const plan = await planForFarm(b.farm);
    if (!plan.ok) return res.status(plan.status || 503).json({ error: plan.error });
    return res.status(200).json(plan);
  }
);

// The app's Farm page, Soil and Next crop tabs: where the farm is and what has been grown go in;
// a soil health score and "what to plant next" come out (soil.js).
//
//   POST .../pumppayFarmHealth
//   body: {"serial":"PP-1A2B3C","wait":true,"farm":{"lat":-0.05,"lon":34.8,"crop":"maize",
//          "plantedOn":"2026-09-01","areaM2":4047,"flowLpm":40,"efficiency":0.9,"soil":"loam",
//          "seasons":[{"crop":"tomato","harvestedOn":"2026-08-20"}],
//          "soilTest":{"ph":5.8,"omPct":2.1},"applied":{"20261101":5200}}}
//   -> {"ok":true,"soil":{"score":78,"grade":"Fair","parts":[...],"todo":[...]},"rotation":{"best":[...],"avoid":[...],"sequence":[...]}}
//
// WHO MAY CALL IT. A phone that has met a registered controller (its serial) or a signed-in user
// (Authorization: Bearer <Firebase ID token>). Soil advice does not need a controller, so someone
// trying the app before buying one can still use it once signed in.
//
// "wait": false answers at once with whatever soil is already stored; true (the default) waits for
// the soil map, which can take most of a minute the first time a place is asked about.
//
// WHAT IT WRITES:
//   pumppay_soil, pumppay_climate, pumppay_elev   caches of public data
//   pumppay_farms/{uid}                           where the farm is (lat, lon, the place's exact name),
//                                                 for a signed-in caller only - see saveFarmPlace
exports.pumppayFarmHealth = onRequest(
  { region: "us-central1", cors: false, maxInstances: 3, timeoutSeconds: 120 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};

    let uid = null;
    const auth = String(req.get("authorization") || "");
    if (auth.startsWith("Bearer ")) { try { uid = (await admin.auth().verifyIdToken(auth.slice(7))).uid; } catch (e) { uid = null; } }
    const serial = String(b.serial || "").toUpperCase();
    const knownSerial = /^PP-[0-9A-F]{6}$/.test(serial) && (await db.doc(`pumppay_devices/${serial}`).get()).exists;
    if (!uid && !knownSerial) return res.status(401).json({ error: "Sign in, or connect to your controller once, to see this." });

    try {
      const out = await farmHealth(b.farm, { wait: b.wait !== false });
      if (!out.ok) return res.status(out.status || 503).json({ error: out.error });
      // Keep the farm's position and name with the account. Losing this must not lose the answer.
      if (uid) { try { out.place = await saveFarmPlace(uid, b.farm, knownSerial ? serial : null); } catch (e) { out.place = null; } }
      return res.status(200).json(out);
    } catch (e) {
      return res.status(503).json({ error: "Could not work this out right now. Try again shortly." });
    }
  }
);
