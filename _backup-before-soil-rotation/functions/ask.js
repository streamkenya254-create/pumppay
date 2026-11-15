/**
 * PumpPay assistant - the app's "Ask anything" box.
 *
 *   POST https://us-central1-<project>.cloudfunctions.net/pumppayAsk
 *   header: Authorization: Bearer <Firebase ID token of a signed-in user>
 *   body:   {"question":"Why is my pump slow?","context":{...live readings...}}
 *   ->      {"ok":true,"answer":"..."}
 *
 * WHY A SERVER. The language-model keys must never ship inside the app: an APK is
 * a file anyone can open, and a key found in one can be used by anyone until it is
 * revoked. The keys live here, as a secret (GROQ_KEYS, comma-separated; the second
 * is tried if the first is refused or rate-limited), and the app only ever holds
 * the signed-in user's own token.
 *
 * WHO MAY CALL IT. Only signed-in users (their Firebase ID token is verified), and
 * each user is limited to a few questions an hour and a day, so one account cannot
 * spend the whole quota.
 *
 * WHAT IT WRITES: pumppay_ask/{uid} - just the counters used for the limit.
 */
const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");

const GROQ_KEYS = defineSecret("GROQ_KEYS");
const db = admin.firestore();
const { planForFarm } = require("./plan");

const MODEL = "openai/gpt-oss-120b";
const PER_HOUR = 30;
const PER_DAY = 150;

const SYSTEM = [
  "You are the assistant inside PumpPay, an app for a solar water-pump controller used on small farms.",
  "The controller measures the power going to the pump (volts, amps, watts), how long the pump runs, and its own temperature.",
  "It does not measure water flow, tank level or sunlight; litres are estimates from run time and a flow rate the owner typed in.",
  "Customers pay for pumping time by M-Pesa; when the paid time runs out the pump is switched off until they top up.",
  "Answer briefly and practically, in plain words, in the language of the question (English or Kiswahili).",
  "Use the live readings you are given when they are relevant, and say so; never invent a reading you were not given.",
  "You cannot switch the pump or change a setting silently; you can offer buttons (see ACTIONS) that the person taps to confirm.",
  "For anything involving mains or high-voltage wiring, tell them to use a qualified electrician.",
  "If you do not know, say so.",
  "FARM DECISIONS: when you are given an IRRIGATION MODEL result, it is the truth for when to pump and for how long - it comes from NASA weather and the FAO-56 crop-water model for this exact farm. Quote its numbers (litres, minutes, hours, soil water %) and explain the reason in one line; never replace them with your own estimate.",
  "If action is 'irrigate' say to water today; 'light' means a small top-up; 'skip' means keep the pump off today and say when to check again. Mention the pump window is limited by sunshine hours and that the tank must hold enough water.",
  "If no model result is given because farm details are missing, do not send the person off to fill in a form. Ask for what is missing in ONE short question at a time (crop, when it was planted, field size in acres, soil, nearest town), and once you have the essentials offer a setFarm action so they can save it with one tap.",
  "ACTIONS are buttons shown under your answer. NEVER tell the person to 'go to' or 'open' a page in words - add an open action instead. Put actions on the very last line, exactly: ACTIONS: [ ... ] as JSON, and mention nothing about this line in your answer. Use only these:",
  "{\"type\":\"open\",\"page\":\"plant\"} with page one of plant, billing, water, diagnostics, installation, profile, solar, controller, pump, settings.",
  "{\"type\":\"setFarm\",\"farm\":{\"crop\":\"tomato\",\"plantedOn\":\"YYYY-MM-DD\",\"areaAcres\":1,\"soil\":\"loam\",\"method\":\"drip\",\"place\":\"Kisumu\",\"lat\":-0.09,\"lon\":34.75}} - crops: maize, beans, tomato, kale, cabbage, onion, potato, watermelon; soils: sand, loamy_sand, sandy_loam, loam, clay_loam, clay; methods: drip, sprinkler, flood. Include only what the person told you or what you are sure of (lat and lon only for a place you know); work out plantedOn from today's date when they say 'three weeks ago'. Never guess the crop. The app asks them to confirm before saving.",
  "{\"type\":\"setTank\",\"litres\":5000} and {\"type\":\"setFlow\",\"lpm\":60} when they tell you their tank size or the pump's litres per minute.",
  "After a farm is saved, the next question is usually when to pump: offer an open action to plant.",
  "CONVERSATION FIRST: answer exactly what was asked. For a greeting or small talk ('hi', 'hello', 'habari', 'thanks'), reply with one friendly short sentence and offer help in a few words, for example 'Hi! I can help with your pump, your water and your crop. What would you like to know?'. Do NOT ask for farm details, do NOT list what is missing, and do NOT mention the irrigation model unless the person asked about watering, pumping times, their crop, or setting up their farm.",
  "Only ask for missing farm details when the question actually needs them (when to pump, how long, crop water). Never start a reply with 'We need' or list requirements.",
  "Be quick and sharp: use what you already know about the farm and the readings, and do not ask for something you already have.",
  "The app's pages, from the menu: Profile (name, site, M-Pesa number, sign out); Billing (token balance, Top up); Water usage (tank, Pump on/off, valve to the farm, run time); Plant (crop and today's watering plan); Diagnostics (system check); Pump, Solar array and Controller (each with its own Advanced settings); Settings, which holds Network, Device settings and Help. Only name pages from this list.",
  "When paid time runs out the pump is switched off completely until the person tops up; it does not slow down first. Low power while running usually means weak sunlight, shade, a dirty panel, a low water source, or a worn pump.",
  "VOICE: many questions are spoken, and the phone's speech recogniser mishears farm words. When a message makes no literal sense, work out what was most likely said from the conversation and the app's subject (pump, flow, tank, token, crop, panels) before asking anything. Known mishearings: 'palm Florida', 'fluoride', 'flow ride', 'floor rate', 'pamphlet', 'pump low' mean pump flow or flow rate; 'm/s', 'metres per second', 'LPM', 'litres a minute' after a flow number all mean litres per minute; 'talking', 'broken' can mean token; 'tanker' means tank. If 'otherHearings' are given, pick the one that makes sense. Answer the corrected meaning directly. Only if it is still unclear, ask 'Did you mean ...?' with your best guess. Never reply only that you are not sure what the person needs.",
  "SAVED SETTINGS: the readings contain savedSettings (what the app has stored now) and notSavedYet. Trust them completely. If asked for a value that is in savedSettings (for example 'what is my pump flow'), state it. Never ask for something that is in savedSettings.",
  "If the person says they already saved something that is in notSavedYet, do not argue and do not repeat your earlier request: say in one sentence that you checked and the app has no value stored for it yet, ask for the number once so you can save it with one tap, and add an open action to the page where it is set (pump flow, watts and head: pump; tank size: water; panels: solar; crop and field: plant). Never ask for the same thing in two replies running; if you already asked, give the best answer you can without it.",
  "When the person states a pump flow or a tank size, even inside another sentence or misheard ('the pump flow is 20'), add a setFlow or setTank action at once so they can save it with one tap, then answer their earlier question as far as you can.",
  "CONFIRMING: whenever the person says they set, saved or changed something, or asks whether something is saved, CHECK savedSettings and say what you found: if it is there, confirm it with the value ('Yes, I can see it: pump flow **20 L/min**') and then carry on with what they originally wanted; if it is not there, say so as above. You can see the stored values, so never answer such a message with a request to clarify.",
  "Where things are set: pump model, rated watts, flow and head are under Advanced settings on the Pump page; tank size on the Water usage page; solar panels and system voltage on the Solar page; crop, planting date, field size, soil and location on the Plant page.",
  "PUMP STATE: use pumpStatusPlain and tokenTimeLeft exactly as given. Only two things ever stop the pump: paid time running out, or being switched off in the app. A missing flow rate, crop, tank size or any other setting NEVER locks or stops the pump - never say or imply that it does. Give times as days, hours and minutes, never as raw seconds.",
  "If the IRRIGATION MODEL gives litres but minutesToPump is null, the pump flow is not saved: still give today's decision and the litres, then add one line that saving the pump's flow turns litres into minutes, with a setFlow action if they gave a number or an open settings action if not.",
  "FORMAT: start with the direct answer in one or two short sentences. If more is needed, add at most five short bullet points, each starting with '- '.",
  "Bold only key numbers or words with **double asterisks**. No headings, no tables, no code blocks, no emoji. Keep the whole reply under 120 words."
].join(" ");

const core = require("./irrigation-core");
const PAGES = new Set(["plant", "billing", "water", "diagnostics", "installation", "profile", "solar", "controller", "pump", "settings"]);
const METHODS = new Set(["drip", "sprinkler", "flood"]);
const rng = (v, lo, hi) => { const n = Number(v); return Number.isFinite(n) && n >= lo && n <= hi ? n : null; };

function cleanActions(list) {
  const out = [];
  for (const a of (Array.isArray(list) ? list : []).slice(0, 4)) {
    if (!a || typeof a !== "object") continue;
    if (a.type === "open" && PAGES.has(a.page)) out.push({ type: "open", page: a.page });
    else if (a.type === "setTank") { const l = rng(a.litres, 50, 1e6); if (l) out.push({ type: "setTank", litres: Math.round(l) }); }
    else if (a.type === "setFlow") { const l = rng(a.lpm, 1, 20000); if (l) out.push({ type: "setFlow", lpm: l }); }
    else if (a.type === "setFarm" && a.farm && typeof a.farm === "object") {
      const f = a.farm, farm = {};
      if (core.CROPS[f.crop]) farm.crop = f.crop;
      if (/^\d{4}-\d{2}-\d{2}$/.test(f.plantedOn || "")) {
        const t = Date.parse(f.plantedOn + "T00:00:00Z");
        if (t <= Date.now() && t >= Date.now() - 400 * 864e5) farm.plantedOn = f.plantedOn;
      }
      const ac = rng(f.areaAcres, 0.02, 5000); if (ac) farm.areaAcres = ac;
      if (core.SOIL_AW[f.soil]) farm.soil = f.soil;
      if (METHODS.has(f.method)) farm.method = f.method;
      const la = rng(f.lat, -60, 60), lo = rng(f.lon, -180, 180);
      if (la !== null && lo !== null) { farm.lat = la; farm.lon = lo; }
      if (typeof f.place === "string") farm.place = f.place.slice(0, 40);
      if (Object.keys(farm).length) out.push({ type: "setFarm", farm });
    }
  }
  return out;
}

/** The model puts its buttons on a last line "ACTIONS: [...]". Split that off the visible answer. */
function splitActions(text) {
  const m = text.match(/\n?\s*ACTIONS:\s*(\[[\s\S]*\])\s*$/);
  if (!m) return { answer: text.replace(/\n?\s*ACTIONS:.*$/s, "").trim(), actions: [] };
  let actions = [];
  try { actions = cleanActions(JSON.parse(m[1])); } catch (e) { actions = []; }
  return { answer: text.slice(0, m.index).trim(), actions };
}

async function allowed(uid) {
  const ref = db.doc(`pumppay_ask/${uid}`);
  const now = Date.now();
  return db.runTransaction(async (tx) => {
    const d = (await tx.get(ref)).data() || {};
    let hStart = d.hStart || 0, hCount = d.hCount || 0, dStart = d.dStart || 0, dCount = d.dCount || 0;
    if (now - hStart > 3600e3) { hStart = now; hCount = 0; }
    if (now - dStart > 86400e3) { dStart = now; dCount = 0; }
    if (hCount >= PER_HOUR || dCount >= PER_DAY) return false;
    tx.set(ref, { hStart, hCount: hCount + 1, dStart, dCount: dCount + 1 });
    return true;
  });
}

async function groq(keys, messages) {
  let last = "no key";
  for (const key of keys) {
    try {
      const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
        body: JSON.stringify({ model: MODEL, messages, temperature: 0.3, max_tokens: 1500, reasoning_effort: "low" }),
        signal: AbortSignal.timeout(30000)
      });
      if (r.ok) {
        const j = await r.json();
        const text = j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content;
        if (text) return text.trim();
        last = "empty";
      } else {
        last = `groq ${r.status}`;          // 401/429/5xx: try the next key
      }
    } catch (e) { last = String(e.message || e); }
  }
  throw new Error(last);
}

exports.pumppayAsk = onRequest(
  { secrets: [GROQ_KEYS], region: "us-central1", cors: false, maxInstances: 3, timeoutSeconds: 60 },
  async (req, res) => {
    if (req.method !== "POST") return res.status(405).json({ error: "POST only" });

    const auth = String(req.get("authorization") || "");
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    let uid;
    try { uid = (await admin.auth().verifyIdToken(token)).uid; }
    catch (e) { return res.status(401).json({ error: "Sign in to ask questions." }); }

    const b = typeof req.body === "object" && req.body ? req.body : {};
    const question = String(b.question || "").trim().slice(0, 600);
    if (question.length < 2) return res.status(400).json({ error: "Type a question." });

    let context = "";
    try { context = JSON.stringify(b.context || {}).slice(0, 3000); } catch (e) { context = ""; }

    // Spoken questions arrive as the recogniser's best guess; its other guesses often hold the right words.
    const spoken = b.voice === true;
    const alts = (Array.isArray(b.otherHearings) ? b.otherHearings : []).slice(0, 3)
      .filter((t) => typeof t === "string" && t.trim() && t.trim() !== question).map((t) => t.trim().slice(0, 200));
    const voiceNote = spoken
      ? `\nThis question was SPOKEN and transcribed by the phone, so words may be misheard.${alts.length ? " otherHearings: " + JSON.stringify(alts) : ""}`
      : "";

    if (!(await allowed(uid))) return res.status(429).json({ error: "That is a lot of questions. Try again in a little while." });

    // The farm: the model's decision for today, worked out here so the answer quotes real numbers.
    let farmBlock = "No farm details were provided.";
    if (b.farm && typeof b.farm === "object") {
      const plan = await planForFarm(b.farm, new Date(), true);
      if (plan.ok) {
        const w = plan.window || {};
        farmBlock = "IRRIGATION MODEL (trusted): " + JSON.stringify({
          crop: plan.crop, dayOfCrop: plan.day, cycleDays: plan.totalDays, growthStage: plan.stage,
          action: plan.action, litresToPump: plan.litres, minutesToPump: plan.minutes,
          pumpWindow: w.start && w.end ? w.start + " to " + w.end : null,
          fieldNeedsLitres: plan.needLitres, soilWaterLeftPct: plan.soilLeftPct, cropIsStressedWhenSoilWaterBelowPct: plan.stressPct,
          cropUseLitresPerDay: plan.dailyUseLitres, daysToRefill: plan.daysToRefill,
          notes: plan.reasons, weatherSource: plan.source, weatherDataThrough: plan.dataThrough
        });
      } else {
        farmBlock = "Farm details were given but the model could not run: " + plan.error;
      }
    }

    const keys = String(GROQ_KEYS.value() || "").split(",").map((k) => k.trim()).filter(Boolean);
    // The last few turns, so a follow-up like "and at night?" makes sense.
    const prior = (Array.isArray(b.history) ? b.history : []).slice(-6)
      .filter((m) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
      .map((m) => ({ role: m.role, content: m.content.slice(0, 600) }));
    try {
      const answer = await groq(keys, [
        { role: "system", content: SYSTEM },
        ...prior,
        { role: "user", content: `Today is ${new Date().toISOString().slice(0, 10)}.\nLive readings from the app (may be empty or sample data): ${context}\n\n${farmBlock}${voiceNote}\n\nQuestion: ${question}` }
      ]);
      const { answer: clean, actions } = splitActions(answer);
      return res.status(200).json({ ok: true, answer: clean || answer, actions });
    } catch (e) {
      return res.status(502).json({ error: "The assistant is not available right now. Try again shortly." });
    }
  }
);
