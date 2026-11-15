/**
 * PumpPay — Firebase Cloud Functions (2nd gen, Node 20)
 *
 *  createCustomer   (callable, admin)      create login (phone + PIN) and customer profile
 *  resetPin         (callable, admin)
 *  recordPayment    (callable, admin)      cash / bank / manual credit
 *  stkPush          (callable, customer)   M-Pesa Express prompt on the customer's phone
 *  mpesaCallback    (HTTP, Safaricom)      STK result → credit the controller
 *  c2bValidation / c2bConfirmation (HTTP)  Paybill payments, account no. = controller serial
 *  deviceSync       (HTTP, controller)     controller polls state + uploads telemetry
 */
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { setGlobalOptions } = require("firebase-functions/v2");
const { defineSecret, defineString } = require("firebase-functions/params");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();
const { FieldValue, Timestamp } = admin.firestore;
setGlobalOptions({ region: "europe-west1", maxInstances: 10 });

// ---- Config: set with `firebase functions:secrets:set NAME` and .env (see README) ----
const MPESA_KEY = defineSecret("MPESA_CONSUMER_KEY");
const MPESA_SECRET = defineSecret("MPESA_CONSUMER_SECRET");
const MPESA_PASSKEY = defineSecret("MPESA_PASSKEY");
const CALLBACK_TOKEN = defineSecret("MPESA_CALLBACK_TOKEN");
const MPESA_ENV = defineString("MPESA_ENV", { default: "sandbox" });          // sandbox | production
const MPESA_SHORTCODE = defineString("MPESA_SHORTCODE", { default: "174379" }); // Paybill / store number
const MPESA_TYPE = defineString("MPESA_TYPE", { default: "CustomerPayBillOnline" }); // or CustomerBuyGoodsOnline
const MPESA_TILL = defineString("MPESA_TILL", { default: "" });                  // Buy Goods only: till number (PartyB)
const LOGIN_DOMAIN = defineString("LOGIN_DOMAIN", { default: "pumppay.app" });
const PUBLIC_BASE = defineString("FUNCTIONS_BASE_URL", { default: "" });          // optional override

const DAY = 86400000;
const normPhone = (p) => {
  let s = String(p || "").replace(/\D/g, "");
  if (s.startsWith("0")) s = "254" + s.slice(1);
  if (/^[71]/.test(s)) s = "254" + s;
  return s;
};

// ---------- Auth helpers ----------
async function requireAdmin(req) {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first");
  const a = await db.doc(`admins/${req.auth.uid}`).get();
  if (!a.exists) throw new HttpsError("permission-denied", "Admins only");
  return req.auth.uid;
}

// ---------- Core: apply a payment to a controller (inside a transaction) ----------
async function creditDevice(tx, devRef, amount, { isDeposit = false } = {}) {
  const snap = await tx.get(devRef);
  if (!snap.exists) throw new Error("Controller not found");
  const d = snap.data();
  const upd = { totalPaid: FieldValue.increment(amount), lastPaymentAt: FieldValue.serverTimestamp() };
  let creditText;

  if (d.planType === "payu") {
    const bundle = (d.bundles || []).find((b) => Number(b.price) === Number(amount));
    const hours = isDeposit ? 0 : bundle ? bundle.hours : amount / (d.hourlyRate || 1);
    upd.hoursBalance = FieldValue.increment(hours);
    creditText = isDeposit ? "Installation fee" : `+${hours.toFixed(1)} hours`;
  } else {
    let days = amount / (d.dailyRate || 1);
    if (isDeposit) {
      const plan = d.planId ? (await tx.get(db.doc(`plans/${d.planId}`))).data() : null;
      days = Number(plan?.depositDays ?? 7);
    }
    const base = Math.max(Date.now(), d.activeUntil?.toMillis?.() || 0);
    upd.activeUntil = Timestamp.fromMillis(base + days * DAY);
    const paid = (d.totalPaid || 0) + amount;
    if (d.totalPrice && paid >= d.totalPrice) { upd.owned = true; upd.ownedAt = FieldValue.serverTimestamp(); }
    creditText = upd.owned ? "Fully paid — system unlocked 🎉" : `+${days.toFixed(1)} days`;
  }
  tx.update(devRef, upd);
  return { creditText, device: d };
}

// ---------- Admin: customers ----------
exports.createCustomer = onCall(async (req) => {
  await requireAdmin(req);
  const { name, phone, pin, county = "", village = "" } = req.data || {};
  const p = normPhone(phone);
  if (!name || !/^254(7|1)\d{8}$/.test(p)) throw new HttpsError("invalid-argument", "Name and valid phone required");
  if (!/^\d{6}$/.test(String(pin))) throw new HttpsError("invalid-argument", "PIN must be 6 digits");
  let user;
  try {
    user = await admin.auth().createUser({ email: `${p}@${LOGIN_DOMAIN.value()}`, password: String(pin), displayName: name });
  } catch (e) {
    if (e.code === "auth/email-already-exists") throw new HttpsError("already-exists", "This phone already has an account");
    throw new HttpsError("internal", e.message);
  }
  await db.doc(`customers/${user.uid}`).set({ name, phone: p, county, village, createdAt: FieldValue.serverTimestamp(), createdBy: req.auth.uid });
  return { uid: user.uid };
});

exports.resetPin = onCall(async (req) => {
  await requireAdmin(req);
  const { uid, pin } = req.data || {};
  if (!/^\d{6}$/.test(String(pin))) throw new HttpsError("invalid-argument", "PIN must be 6 digits");
  await admin.auth().updateUser(uid, { password: String(pin) });
  return { ok: true };
});

// ---------- Admin: manual payment ----------
exports.recordPayment = onCall(async (req) => {
  const by = await requireAdmin(req);
  const { deviceId, amount, method = "cash", reference = "", isDeposit = false } = req.data || {};
  const amt = Math.round(Number(amount));
  if (!deviceId || !(amt > 0)) throw new HttpsError("invalid-argument", "Controller and amount required");
  const devRef = db.doc(`devices/${deviceId}`);
  const payRef = db.collection("payments").doc();
  const result = await db.runTransaction(async (tx) => {
    const r = await creditDevice(tx, devRef, method === "bonus" ? 0 : amt, { isDeposit });
    // bonus: give time without counting toward ownership
    if (method === "bonus" && r.device.planType !== "payu") {
      const base = Math.max(Date.now(), r.device.activeUntil?.toMillis?.() || 0);
      const days = amt / (r.device.dailyRate || 1);
      tx.update(devRef, { activeUntil: Timestamp.fromMillis(base + days * DAY) });
      r.creditText = `+${days.toFixed(1)} bonus days`;
    } else if (method === "bonus") {
      const hrs = amt / (r.device.hourlyRate || 1);
      tx.update(devRef, { hoursBalance: FieldValue.increment(hrs) });
      r.creditText = `+${hrs.toFixed(1)} bonus hours`;
    }
    tx.set(payRef, {
      deviceId, serial: r.device.serial, customerId: r.device.customerId || null, amount: method === "bonus" ? 0 : amt,
      method, mpesaReceipt: reference, isDeposit, status: "success", creditText: r.creditText,
      recordedBy: by, createdAt: FieldValue.serverTimestamp(),
    });
    return r;
  });
  return { creditText: result.creditText };
});

// ---------- M-Pesa (Daraja) ----------
const darajaBase = () => (MPESA_ENV.value() === "production" ? "https://api.safaricom.co.ke" : "https://sandbox.safaricom.co.ke");
async function darajaToken() {
  const basic = Buffer.from(`${MPESA_KEY.value()}:${MPESA_SECRET.value()}`).toString("base64");
  const r = await fetch(`${darajaBase()}/oauth/v1/generate?grant_type=client_credentials`, { headers: { Authorization: `Basic ${basic}` } });
  if (!r.ok) throw new Error(`Daraja auth failed ${r.status}`);
  return (await r.json()).access_token;
}
const fnUrl = (name) => `${PUBLIC_BASE.value() || `https://europe-west1-${process.env.GCLOUD_PROJECT}.cloudfunctions.net`}/${name}`;

exports.stkPush = onCall({ secrets: [MPESA_KEY, MPESA_SECRET, MPESA_PASSKEY, CALLBACK_TOKEN] }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Log in first");
  const { deviceId, amount, phone } = req.data || {};
  const amt = Math.round(Number(amount)), p = normPhone(phone);
  if (!(amt >= 1 && amt <= 250000)) throw new HttpsError("invalid-argument", "Invalid amount");
  if (!/^254(7|1)\d{8}$/.test(p)) throw new HttpsError("invalid-argument", "Invalid phone");
  const dev = await db.doc(`devices/${deviceId}`).get();
  if (!dev.exists) throw new HttpsError("not-found", "Controller not found");
  const isAdmin = (await db.doc(`admins/${req.auth.uid}`).get()).exists;
  if (dev.data().customerId !== req.auth.uid && !isAdmin) throw new HttpsError("permission-denied", "Not your controller");

  const ts = new Date().toLocaleString("sv-SE", { timeZone: "Africa/Nairobi" }).replace(/\D/g, "").slice(0, 14);
  const sc = MPESA_SHORTCODE.value();
  const password = Buffer.from(sc + MPESA_PASSKEY.value() + ts).toString("base64");
  const payRef = db.collection("payments").doc();
  const body = {
    BusinessShortCode: sc, Password: password, Timestamp: ts, TransactionType: MPESA_TYPE.value(),
    Amount: amt, PartyA: p, PartyB: MPESA_TILL.value() || sc, PhoneNumber: p,
    CallBackURL: `${fnUrl("mpesaCallback")}?t=${CALLBACK_TOKEN.value()}`,
    AccountReference: String(dev.data().serial || deviceId).slice(0, 12), TransactionDesc: "Solar pump",
  };
  const r = await fetch(`${darajaBase()}/mpesa/stkpush/v1/processrequest`, {
    method: "POST", headers: { Authorization: `Bearer ${await darajaToken()}`, "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  const j = await r.json();
  if (j.ResponseCode !== "0") throw new HttpsError("unavailable", j.errorMessage || j.ResponseDescription || "M-Pesa request failed");
  await payRef.set({
    deviceId, serial: dev.data().serial, customerId: dev.data().customerId || null, amount: amt, phone: p, method: "mpesa-stk",
    status: "pending", checkoutRequestId: j.CheckoutRequestID, merchantRequestId: j.MerchantRequestID, createdAt: FieldValue.serverTimestamp(),
  });
  return { paymentId: payRef.id };
});

exports.mpesaCallback = onRequest({ secrets: [CALLBACK_TOKEN] }, async (req, res) => {
  res.json({ ResultCode: 0, ResultDesc: "Accepted" }); // always ack Safaricom fast
  if (req.query.t !== CALLBACK_TOKEN.value()) return console.warn("Bad callback token");
  const cb = req.body?.Body?.stkCallback;
  if (!cb) return;
  const q = await db.collection("payments").where("checkoutRequestId", "==", cb.CheckoutRequestID).limit(1).get();
  if (q.empty) return console.warn("Unknown CheckoutRequestID", cb.CheckoutRequestID);
  const payRef = q.docs[0].ref;
  try {
    await db.runTransaction(async (tx) => {
      const pay = (await tx.get(payRef)).data();
      if (pay.status !== "pending") return; // idempotent
      if (cb.ResultCode !== 0) { tx.update(payRef, { status: "failed", resultDesc: cb.ResultDesc }); return; }
      const meta = Object.fromEntries((cb.CallbackMetadata?.Item || []).map((i) => [i.Name, i.Value]));
      const amount = Number(meta.Amount || pay.amount);
      const r = await creditDevice(tx, db.doc(`devices/${pay.deviceId}`), amount);
      tx.update(payRef, { status: "success", amount, mpesaReceipt: meta.MpesaReceiptNumber || "", creditText: r.creditText,
        paidAt: FieldValue.serverTimestamp() });
    });
  } catch (e) { console.error("Callback credit failed", e); }
});

// Paybill (C2B). Register these URLs once with Daraja's C2B register API — see README.
exports.c2bValidation = onRequest({ secrets: [CALLBACK_TOKEN] }, async (req, res) => {
  if (req.query.t !== CALLBACK_TOKEN.value()) return res.json({ ResultCode: "C2B00016", ResultDesc: "Rejected" });
  const ref = String(req.body?.BillRefNumber || "").trim().toUpperCase();
  const ok = ref && (await db.doc(`devices/${ref}`).get()).exists;
  res.json(ok ? { ResultCode: "0", ResultDesc: "Accepted" } : { ResultCode: "C2B00012", ResultDesc: "Invalid Account Number" });
});

exports.c2bConfirmation = onRequest({ secrets: [CALLBACK_TOKEN] }, async (req, res) => {
  res.json({ ResultCode: 0, ResultDesc: "Accepted" });
  if (req.query.t !== CALLBACK_TOKEN.value()) return;
  const b = req.body || {};
  const serial = String(b.BillRefNumber || "").trim().toUpperCase();
  const payRef = db.doc(`payments/c2b_${b.TransID}`); // TransID as id → no double credit
  try {
    await db.runTransaction(async (tx) => {
      if ((await tx.get(payRef)).exists) return;
      const devRef = db.doc(`devices/${serial}`);
      const amount = Math.round(Number(b.TransAmount));
      const r = await creditDevice(tx, devRef, amount);
      tx.set(payRef, { deviceId: serial, serial, customerId: r.device.customerId || null, amount, phone: String(b.MSISDN || ""),
        payerName: [b.FirstName, b.LastName].filter(Boolean).join(" "), method: "mpesa-paybill", mpesaReceipt: b.TransID,
        status: "success", creditText: r.creditText, createdAt: FieldValue.serverTimestamp() });
    });
  } catch (e) {
    console.error("C2B credit failed", serial, e.message);
    await payRef.set({ deviceId: serial, amount: Number(b.TransAmount), mpesaReceipt: b.TransID, method: "mpesa-paybill",
      status: "unmatched", raw: b, createdAt: FieldValue.serverTimestamp() }, { merge: true });
  }
});

// ---------- Controller sync ----------
// GET/POST /deviceSync?serial=PP-0001&key=SECRET&hours=123.4&litres=5600&litresToday=800&solarW=900
//  hours  = controller's lifetime run-hours counter (used to deduct PAYU balance)
exports.deviceSync = onRequest(async (req, res) => {
  const q = { ...req.query, ...(typeof req.body === "object" ? req.body : {}) };
  const serial = String(q.serial || "").toUpperCase();
  const keyDoc = serial && (await db.doc(`deviceKeys/${serial}`).get());
  if (!keyDoc?.exists || keyDoc.data().key !== q.key) return res.status(401).json({ error: "unauthorized" });

  const devRef = db.doc(`devices/${serial}`);
  const out = await db.runTransaction(async (tx) => {
    const d = (await tx.get(devRef)).data();
    const upd = { "telemetry.lastSeen": FieldValue.serverTimestamp() };
    for (const k of ["litres", "litresToday", "solarW", "tankPct", "rssi", "fw"]) if (q[k] !== undefined) upd[`telemetry.${k}`] = isNaN(q[k]) ? q[k] : Number(q[k]);
    let hoursBalance = d.hoursBalance || 0;
    if (q.hours !== undefined) {
      const counter = Number(q.hours);
      const last = d.telemetry?.hoursCounter;
      upd["telemetry.hoursCounter"] = counter;
      upd["telemetry.hoursRun"] = counter;
      if (d.planType === "payu" && !d.owned && last !== undefined && counter > last) {
        const used = Math.min(counter - last, 24); // guard against counter glitches
        hoursBalance = Math.max(0, hoursBalance - used);
        upd.hoursBalance = hoursBalance;
      }
    }
    // Usage log: one doc per device per hour (litres pumped + minutes run) → powers the app's Usage tab
    const prevLitres = d.telemetry?.litres;
    const dLitres = q.litres !== undefined && prevLitres !== undefined ? Number(q.litres) - prevLitres : 0;
    const prevHrs = d.telemetry?.hoursCounter;
    const dMins = q.hours !== undefined && prevHrs !== undefined ? (Number(q.hours) - prevHrs) * 60 : 0;
    if ((dLitres > 0 && dLitres < 50000) || (dMins > 0 && dMins < 1440)) {
      const hour = new Date(Math.floor(Date.now() / 3600000) * 3600000);
      tx.set(db.doc(`usage/${serial}_${hour.getTime() / 3600000}`), {
        deviceId: serial, customerId: d.customerId || null, ts: hour,
        litres: FieldValue.increment(dLitres > 0 && dLitres < 50000 ? dLitres : 0),
        mins: FieldValue.increment(dMins > 0 && dMins < 1440 ? dMins : 0),
      }, { merge: true });
    }
    tx.update(devRef, upd);
    const until = d.activeUntil?.toMillis?.() || 0;
    const on = d.status !== "suspended" && (d.owned || (d.planType === "payu" ? hoursBalance > 0 : until > Date.now()));
    return { on, mode: d.planType, owned: !!d.owned, until: Math.floor(until / 1000), hours: Number(hoursBalance.toFixed(2)),
      now: Math.floor(Date.now() / 1000), suspended: d.status === "suspended" };
  });
  res.json(out);
});
