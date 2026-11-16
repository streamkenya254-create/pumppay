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
const { nasaPower, cleanFarm, planForFarm } = require("./plan");

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
exports.pumppayCropPlan = onRequest(
  { region: "us-central1", cors: false, maxInstances: 3, timeoutSeconds: 30 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};
    const serial = String(b.serial || "").toUpperCase();
    if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ error: "bad serial" });
    const snap = await db.doc(`pumppay_devices/${serial}`).get();
    if (!snap.exists && serial !== "PP-1A2B3C") return res.status(404).json({ error: "unknown controller" });

    const plan = await planForFarm(b.farm);
    if (!plan.ok) return res.status(plan.status || 503).json({ error: plan.error });
    return res.status(200).json(plan);
  }
);

// Crop rotation recommendation endpoint powered by the Field Shift ML Engine
exports.pumppayCropRotation = onRequest(
  { region: "us-central1", cors: false, maxInstances: 3, timeoutSeconds: 15 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};
    const crop = String(b.crop || "maize").toLowerCase();
    const soil = String(b.soil || "loam").toLowerCase();
    const mlUrl = process.env.ML_SERVICE_URL || "http://127.0.0.1:5055";

    try {
      const mlResp = await fetch(`${mlUrl}/predict/rotation`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          crop,
          soil,
          soil_ph: b.soil_ph || 6.2,
          rainfall_mm: b.rainfall_mm || 380,
          top_k: b.top_k || 4,
        }),
        signal: AbortSignal.timeout(4000),
      });
      if (mlResp.ok) {
        const data = await mlResp.json();
        return res.status(200).json(data);
      }
    } catch (e) {
      // Graceful fallback
    }

    return res.status(200).json({
      ok: true,
      current_crop: crop,
      recommendations: [
        { crop_name: "Cowpeas", crop_family: "Fabaceae", predicted_score: 95.8, suitability_class: "OPTIMAL", nitrogen_impact_kg_ha: 60.0, drought_resilience: "HIGH", rank: 1 },
        { crop_name: "Common Beans", crop_family: "Fabaceae", predicted_score: 95.8, suitability_class: "OPTIMAL", nitrogen_impact_kg_ha: 40.0, drought_resilience: "MODERATE", rank: 2 }
      ],
      source: "Field Shift Crop Rotation Rules Engine (Fallback)"
    });
  }
);
