/**
 * PumpPay ingest - receives readings from a PumpPay controller and files them.
 *
 *   POST https://us-central1-<project>.cloudfunctions.net/pumppayIngest
 *   body: {"serial":"PP-1A2B3C","key":"...","state":"PUMPING","line":1,
 *          "v":12.1,"a":1.42,"va":17.2,"vok":1,"aok":1,"temp":30.2,
 *          "today":18600,"total":2923200,"starts":143,"t":1767225600,"up":3600,"rssi":-61}
 *
 * WHAT IT WRITES, and only this:
 *   pumppay_devices/{serial}                 the latest reading (overwritten)
 *   pumppay_devices/{serial}/readings/{auto} one document per upload, for history
 *
 * WHY A FUNCTION. The project this lives in keeps its database closed to every
 * browser and device (firestore.rules denies all client access). This function
 * uses the Admin SDK, so that stays true: nothing about the rules changes, and
 * a controller can write pump data and nothing else.
 *
 * WHO MAY CALL IT. Anyone who knows the shared key, set once with
 *   firebase functions:secrets:set PUMPPAY_INGEST_KEY
 * and typed into the controller. It is one key for all controllers, so it cannot
 * be revoked for a single board - rotate it for everyone if it leaks.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();
const FieldValue = admin.firestore.FieldValue;

const INGEST_KEY = defineSecret("PUMPPAY_INGEST_KEY");

const STATES = new Set(["PUMPING", "IDLE", "NO SIGNAL"]);

// Compared in constant time, so response timing cannot be used to guess the key
// a character at a time.
function keyMatches(given, expected) {
  const a = Buffer.from(String(given || ""));
  const b = Buffer.from(String(expected || ""));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

// A finite number within [lo, hi], else null. Anything the board sends is
// untrusted input until it has passed through here.
function num(v, lo, hi) {
  const n = Number(v);
  return Number.isFinite(n) && n >= lo && n <= hi ? n : null;
}

exports.pumppayIngest = onRequest(
  { secrets: [INGEST_KEY], region: "us-central1", cors: false, maxInstances: 5, timeoutSeconds: 20 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });

    const b = typeof req.body === "object" && req.body ? req.body : {};

    if (!keyMatches(b.key, INGEST_KEY.value())) {
      // Same answer for a wrong key and a missing one; nothing is revealed.
      return res.status(401).json({ error: "unauthorized" });
    }

    const serial = String(b.serial || "").toUpperCase();
    if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ error: "bad serial" });
    if (!STATES.has(b.state)) return res.status(400).json({ error: "bad state" });

    const t = num(b.t, 1.7e9, 4.1e9);          // device clock, UTC seconds
    if (t === null) return res.status(400).json({ error: "bad time" });

    // Only these fields are copied, each range-checked. A value out of range is
    // stored as null - "not measured" - not clamped into something plausible.
    const reading = {
      state: b.state,
      line: num(b.line, 1, 2),
      volts: b.vok ? num(b.v, 0, 1000) : null,
      amps: b.aok ? num(b.a, 0, 500) : null,
      va: b.vok && b.aok ? num(b.va, 0, 500000) : null,
      tempC: num(b.temp, -40, 150),
      runTodaySecs: num(b.today, 0, 86400),
      runTotalSecs: num(b.total, 0, 4e9),
      starts: num(b.starts, 0, 4e9),
      uptimeSecs: num(b.up, 0, 4e9),
      rssi: num(b.rssi, -130, 0),
      deviceTime: admin.firestore.Timestamp.fromMillis(t * 1000),
      receivedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    const ref = db.doc(`pumppay_devices/${serial}`);

    // What the board says it has used. It only ever goes up: the server keeps the
    // larger of what it already had and what the board reports, so a board that
    // lost its memory (reflashed, power-cut mid-save) cannot hand itself back
    // credit it already spent, and a hostile post cannot lower it.
    const spentReported = num(b.spent, 0, 4e9);

    const out = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const d = snap.exists ? snap.data() : {};
      const spent = Math.max(Number(d.spentSecs) || 0, spentReported || 0);

      tx.set(ref, { serial, latest: reading, updatedAt: reading.receivedAt, spentSecs: spent }, { merge: true });
      tx.set(ref.collection("readings").doc(), reading);

      // Everything paid for, in seconds of pumping. The board works out what is
      // left itself (credited - spent) so it can keep counting between uploads.
      const credited = (Number(d.creditedSecs) || 0) + (Number(d.balanceSecs) || 0);
      return { creditedSecs: credited, spentSecs: spent };
    });

    return res.status(200).json({ ok: true, ...out });
  }
);

// ---------------------------------------------------------------------------
//  Support credit: POST { key, serial, secs } adds complimentary pumping time.
//  Uses its OWN key (PUMPPAY_ADMIN_KEY), not the ingest key. Every controller
//  carries the ingest key in its flash, where it can be read out; if that same
//  key could grant credit, anyone with one board could give themselves free water.
// ---------------------------------------------------------------------------
const ADMIN_KEY = defineSecret("PUMPPAY_ADMIN_KEY");

exports.pumppayAdminCredit = onRequest(
  { secrets: [ADMIN_KEY], region: "us-central1", cors: false, maxInstances: 2, timeoutSeconds: 15 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};
    if (!keyMatches(b.key, ADMIN_KEY.value())) return res.status(401).json({ error: "unauthorized" });

    const serial = String(b.serial || "").toUpperCase();
    const secs = num(b.secs, -86400 * 30, 86400 * 30);
    if (!/^PP-[0-9A-F]{6}$/.test(serial) || secs === null) return res.status(400).json({ error: "bad serial or secs" });

    const ref = db.doc(`pumppay_devices/${serial}`);
    if (!(await ref.get()).exists) return res.status(404).json({ error: "unknown controller" });

    await ref.set({ creditedSecs: FieldValue.increment(Math.round(secs)) }, { merge: true });
    await ref.collection("credits").add({ kes: 0, secs: Math.round(secs), receipt: "ADMIN", at: FieldValue.serverTimestamp() });
    return res.status(200).json({ ok: true });
  }
);

// M-Pesa top-ups: pumppayTopUp, pumppayMpesaCallback, pumppayStatus.
// Required after initializeApp() above, because that module opens Firestore.
Object.assign(exports, require("./mpesa"));

// Irrigation plan from NASA POWER data: pumppayIrrigationPlan, pumppayAdminFarm.
Object.assign(exports, require("./irrigation"));

// The app assistant: pumppayAsk.
Object.assign(exports, require("./ask"));
