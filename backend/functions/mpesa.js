/**
 * PumpPay M-Pesa - prepaid token top-ups by STK push.
 *
 * The flow is the one the Tafiti website uses (Daraja STK push to a Till, one
 * callback that settles it), with the parts a PREPAID balance needs and a
 * donation does not:
 *
 *   - the PRICE is decided here, from PACKAGES. The app only says which package;
 *     it can never choose an amount, so it cannot buy a day for one shilling.
 *   - the callback must carry a secret in its address. Without it anyone who
 *     guessed the URL could post a "payment succeeded" body and be credited.
 *   - a payment credits ONCE. Daraja retries callbacks, and a retry must not
 *     add the token twice - the credit happens inside a transaction that first
 *     checks the payment is still pending, and the M-Pesa receipt number is
 *     itself a unique key.
 *   - the amount M-Pesa reports paid must equal the amount asked for.
 *   - how often one phone number can be prompted is limited, because an STK
 *     push is a message on a stranger's phone.
 *
 * Collections (all server-only; the project's rules deny every client):
 *   pumppay_payments/{id}               one per attempt
 *   pumppay_receipts/{mpesaReceipt}     exists once that receipt has been credited
 *   pumppay_rate/{phone}                prompt-rate bookkeeping
 *   pumppay_devices/{serial}            balanceSecs lives here, beside the telemetry
 *   pumppay_devices/{serial}/credits/*  one line per top-up, for the customer's history
 */
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret, defineString } = require("firebase-functions/params");
const admin = require("firebase-admin");
const crypto = require("crypto");
const https = require("https");

const db = admin.firestore();
const FieldValue = admin.firestore.FieldValue;

// Credentials come from Firebase secrets, never from source:
//   firebase functions:secrets:set MPESA_CONSUMER_KEY   (and the other three)
const CONSUMER_KEY = defineSecret("MPESA_CONSUMER_KEY");
const CONSUMER_SECRET = defineSecret("MPESA_CONSUMER_SECRET");
const PASSKEY = defineSecret("MPESA_PASSKEY");
const CALLBACK_TOKEN = defineSecret("PUMPPAY_CALLBACK_TOKEN");

// Not secret, but they decide where the money goes, so they have no default:
// set them in functions/.env (see .env.example). A missing value fails loudly.
const SHORTCODE = defineString("MPESA_SHORTCODE");   // BusinessShortCode
const TILL = defineString("MPESA_TILL");             // PartyB - where the money lands
const ENVIRONMENT = defineString("MPESA_ENV", { default: "production" });
const REGION = "us-central1";

// KES price -> seconds of pumping. Keep in step with RATE_KES_PER_HOUR in the app.
const RATE_KES_PER_HOUR = 20;
const PACKAGES = { 20: 3600, 50: 9000, 100: 18000 };
const secsFor = (kes) => Math.round((kes * 3600) / RATE_KES_PER_HOUR);

// What a controller has paid for and not yet used. "credited" only ever goes up
// (top-ups, and any complimentary credit); "spent" is what the controller reports
// it has run, and also only goes up. The old balanceSecs field, written before
// spending was tracked, still counts as credit so nothing already paid is lost.
function remaining(d) {
  const credited = (Number(d.creditedSecs) || 0) + (Number(d.balanceSecs) || 0);
  return Math.max(0, credited - (Number(d.spentSecs) || 0));
}

const SECRETS = [CONSUMER_KEY, CONSUMER_SECRET, PASSKEY, CALLBACK_TOKEN];

function tokenOk(given) {
  const a = Buffer.from(String(given || ""));
  const b = Buffer.from(String(CALLBACK_TOKEN.value()));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function baseHost() {
  return ENVIRONMENT.value() === "production" ? "api.safaricom.co.ke" : "sandbox.safaricom.co.ke";
}

function request(options, body) {
  return new Promise((resolve, reject) => {
    const data = body ? JSON.stringify(body) : null;
    const req = https.request(
      { ...options, headers: { ...(options.headers || {}), ...(data ? { "Content-Length": Buffer.byteLength(data) } : {}) } },
      (res) => {
        let chunks = "";
        res.on("data", (c) => (chunks += c));
        res.on("end", () => {
          try { resolve(JSON.parse(chunks)); } catch (e) { reject(new Error("Daraja replied with something that is not JSON")); }
        });
      }
    );
    req.setTimeout(15000, () => req.destroy(new Error("Daraja timed out")));
    req.on("error", reject);
    if (data) req.write(data);
    req.end();
  });
}

// 2547XXXXXXXX or 2541XXXXXXXX, nothing else. Returns null for anything that is
// not a Safaricom number, so a typo never becomes someone else's prompt.
function safaricomPhone(raw) {
  let d = String(raw || "").replace(/\D/g, "");
  if (d.startsWith("0")) d = "254" + d.slice(1);
  else if (/^[71]\d{8}$/.test(d)) d = "254" + d;
  return /^254[71]\d{8}$/.test(d) ? d : null;
}

function stkTimestamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
}

// At most 5 prompts per phone per 10 minutes.
async function allowPrompt(phone) {
  const ref = db.doc(`pumppay_rate/${phone}`);
  return db.runTransaction(async (tx) => {
    const now = Date.now();
    const s = await tx.get(ref);
    let { windowStart = 0, count = 0 } = s.exists ? s.data() : {};
    if (now - windowStart > 10 * 60 * 1000) { windowStart = now; count = 0; }
    if (count >= 5) return false;
    tx.set(ref, { windowStart, count: count + 1 });
    return true;
  });
}

// ---------------------------------------------------------------------------
//  POST { serial, phone, kes }  ->  { ok, paymentId }
// ---------------------------------------------------------------------------
exports.pumppayTopUp = onRequest(
  { secrets: SECRETS, region: REGION, cors: true, maxInstances: 5, timeoutSeconds: 30 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ ok: false, error: "POST only" });
    const b = typeof req.body === "object" && req.body ? req.body : {};

    const serial = String(b.serial || "").toUpperCase();
    const phone = safaricomPhone(b.phone);
    const kes = Number(b.kes);

    if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ ok: false, error: "Unknown controller" });
    if (!phone) return res.status(400).json({ ok: false, error: "Enter a Safaricom number, e.g. 0712 345 678" });
    if (!Object.prototype.hasOwnProperty.call(PACKAGES, kes)) return res.status(400).json({ ok: false, error: "Choose one of the listed top-ups" });

    // Only controllers that have reported in can be topped up. This stops
    // prompts being fired at strangers "for" a serial that does not exist.
    const dev = await db.doc(`pumppay_devices/${serial}`).get();
    if (!dev.exists) return res.status(404).json({ ok: false, error: "That controller has not connected yet" });

    if (!(await allowPrompt(phone))) return res.status(429).json({ ok: false, error: "Too many attempts. Wait a few minutes." });

    const payRef = db.collection("pumppay_payments").doc();
    await payRef.set({
      serial, phone, amount: kes, secs: secsFor(kes),
      status: "pending", createdAt: FieldValue.serverTimestamp(),
    });

    try {
      const auth = Buffer.from(`${CONSUMER_KEY.value()}:${CONSUMER_SECRET.value()}`).toString("base64");
      const tok = await request({
        hostname: baseHost(), path: "/oauth/v1/generate?grant_type=client_credentials",
        method: "GET", headers: { Authorization: `Basic ${auth}` },
      });
      if (!tok.access_token) throw new Error("Could not get an M-Pesa access token");

      const ts = stkTimestamp();
      const callback = `https://${REGION}-${process.env.GCLOUD_PROJECT}.cloudfunctions.net/pumppayMpesaCallback?t=${encodeURIComponent(CALLBACK_TOKEN.value())}`;

      const stk = await request(
        {
          hostname: baseHost(), path: "/mpesa/stkpush/v1/processrequest", method: "POST",
          headers: { Authorization: `Bearer ${tok.access_token}`, "Content-Type": "application/json" },
        },
        {
          BusinessShortCode: SHORTCODE.value(),
          Password: Buffer.from(`${SHORTCODE.value()}${PASSKEY.value()}${ts}`).toString("base64"),
          Timestamp: ts,
          TransactionType: "CustomerBuyGoodsOnline",
          Amount: kes,
          PartyA: phone,
          PartyB: TILL.value(),
          PhoneNumber: phone,
          CallBackURL: callback,
          AccountReference: serial,
          TransactionDesc: "PumpPay token",
        }
      );

      if (stk.ResponseCode !== "0") {
        await payRef.update({ status: "failed", failureReason: stk.errorMessage || stk.ResponseDescription || "STK push rejected" });
        return res.status(502).json({ ok: false, error: "M-Pesa did not accept the request" });
      }

      await payRef.update({ status: "awaiting_pin", checkoutRequestId: stk.CheckoutRequestID });
      return res.status(200).json({ ok: true, paymentId: payRef.id });
    } catch (e) {
      console.error("pumppayTopUp", e.message);
      await payRef.update({ status: "failed", failureReason: String(e.message).slice(0, 200) });
      return res.status(502).json({ ok: false, error: "Could not reach M-Pesa. Try again." });
    }
  }
);

// ---------------------------------------------------------------------------
//  Daraja calls this once per payment (and again if it thinks we did not answer)
// ---------------------------------------------------------------------------
exports.pumppayMpesaCallback = onRequest(
  { secrets: SECRETS, region: REGION, cors: false, maxInstances: 5, timeoutSeconds: 30 },
  async (req, res) => {
    // A request without the secret is not from us. It gets the same empty OK as
    // an unknown payment, so nothing is revealed about which one it was.
    if (!tokenOk(req.query.t)) return res.status(200).json({ ResultCode: 0, ResultDesc: "Accepted" });

    try {
      const cb = req.body && req.body.Body && req.body.Body.stkCallback;
      if (!cb) return res.status(400).send("Invalid callback");

      const q = await db.collection("pumppay_payments").where("checkoutRequestId", "==", cb.CheckoutRequestID).limit(1).get();
      if (q.empty) return res.status(200).json({ ResultCode: 0, ResultDesc: "Accepted" });
      const payRef = q.docs[0].ref;

      if (cb.ResultCode !== 0) {
        await payRef.update({ status: "failed", failureReason: String(cb.ResultDesc || "Cancelled").slice(0, 200) });
        return res.status(200).json({ ResultCode: 0, ResultDesc: "Accepted" });
      }

      const meta = {};
      ((cb.CallbackMetadata && cb.CallbackMetadata.Item) || []).forEach((i) => (meta[i.Name] = i.Value));
      const receipt = String(meta.MpesaReceiptNumber || "");

      await db.runTransaction(async (tx) => {
        const pay = await tx.get(payRef);
        const p = pay.data();
        // Already settled: a retry of the same callback. Credit nothing.
        if (p.status === "completed" || p.status === "mismatch") return;

        // M-Pesa must report the amount we asked for, and a receipt number.
        if (!receipt || Number(meta.Amount) !== p.amount) {
          tx.update(payRef, { status: "mismatch", failureReason: "Amount or receipt did not match", mpesaReceiptNumber: receipt });
          return;
        }

        // One receipt, one credit - even if two different payment records
        // somehow pointed at it.
        const rcRef = db.doc(`pumppay_receipts/${receipt}`);
        if ((await tx.get(rcRef)).exists) { tx.update(payRef, { status: "duplicate" }); return; }

        const devRef = db.doc(`pumppay_devices/${p.serial}`);
        tx.set(rcRef, { paymentId: payRef.id, serial: p.serial, at: FieldValue.serverTimestamp() });
        tx.set(devRef, { creditedSecs: FieldValue.increment(p.secs), balanceUpdatedAt: FieldValue.serverTimestamp() }, { merge: true });
        tx.set(devRef.collection("credits").doc(), { kes: p.amount, secs: p.secs, receipt, at: FieldValue.serverTimestamp() });
        tx.update(payRef, { status: "completed", mpesaReceiptNumber: receipt, completedAt: FieldValue.serverTimestamp() });
      });

      return res.status(200).json({ ResultCode: 0, ResultDesc: "Accepted" });
    } catch (e) {
      console.error("pumppayMpesaCallback", e.message);
      // 500 makes Daraja retry, which is safe: the credit above is idempotent.
      return res.status(500).send("Error");
    }
  }
);

// ---------------------------------------------------------------------------
//  GET ?id=<paymentId>  or  ?serial=PP-XXXXXX   ->  status / balance
// ---------------------------------------------------------------------------
exports.pumppayStatus = onRequest(
  { region: REGION, cors: true, maxInstances: 5, timeoutSeconds: 15 },
  async (req, res) => {
    const out = {};
    const id = String(req.query.id || "");
    if (id) {
      if (!/^[A-Za-z0-9]{20}$/.test(id)) return res.status(400).json({ ok: false, error: "bad id" });
      const p = await db.doc(`pumppay_payments/${id}`).get();
      if (!p.exists) return res.status(404).json({ ok: false, error: "not found" });
      const d = p.data();
      out.status = d.status;                       // pending | awaiting_pin | completed | failed | mismatch | duplicate
      out.reason = d.status === "failed" ? d.failureReason || "" : "";
      out.receipt = d.status === "completed" ? d.mpesaReceiptNumber : "";
      out.serial = d.serial;
    }
    const serial = String(req.query.serial || out.serial || "").toUpperCase();
    if (serial) {
      if (!/^PP-[0-9A-F]{6}$/.test(serial)) return res.status(400).json({ ok: false, error: "bad serial" });
      const dev = await db.doc(`pumppay_devices/${serial}`).get();
      out.balanceSecs = dev.exists ? remaining(dev.data()) : 0;
    }
    delete out.serial;
    return res.status(200).json({ ok: true, ...out });
  }
);
