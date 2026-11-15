// ============================================================
//  PumpPay — shared Firebase setup
//  1. Firebase console → Project settings → Your apps → Web app
//  2. Paste YOUR config object below (replace the placeholders)
// ============================================================
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { getAuth } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { getFirestore } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { getStorage } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-storage.js";
import { getFunctions } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-functions.js";

export const firebaseConfig = {
  apiKey: "PASTE_API_KEY",
  authDomain: "PASTE_PROJECT_ID.firebaseapp.com",
  projectId: "PASTE_PROJECT_ID",
  storageBucket: "PASTE_PROJECT_ID.appspot.com",
  messagingSenderId: "PASTE_SENDER_ID",
  appId: "PASTE_APP_ID",
};

// Brand settings — change once, used everywhere
export const BRAND = {
  name: "PumpPay",
  company: "Tafiti R&I Hub",
  phone: "+254 701 326 554",
  whatsapp: "254701326554",
  email: "tafitiinnovationhub@gmail.com",
  currency: "KES",
  // Customers log in with phone + PIN. Internally mapped to this e-mail domain.
  loginDomain: "pumppay.app",
};

export const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const db = getFirestore(app);
export const storage = getStorage(app);
export const functions = getFunctions(app, "europe-west1");

// Helpers
export const ksh = (n) =>
  `${BRAND.currency} ${Number(n || 0).toLocaleString("en-KE", { maximumFractionDigits: 0 })}`;

export function normalizePhone(p) {
  let s = String(p || "").replace(/\D/g, "");
  if (s.startsWith("0")) s = "254" + s.slice(1);
  if (s.startsWith("7") || s.startsWith("1")) s = "254" + s;
  return s;
}
export const phoneToEmail = (p) => `${normalizePhone(p)}@${BRAND.loginDomain}`;

export function fmtDate(ts) {
  if (!ts) return "—";
  const d = ts.toDate ? ts.toDate() : new Date(ts);
  return d.toLocaleString("en-KE", { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function toast(msg, type = "ok") {
  let el = document.getElementById("toast");
  if (!el) { el = document.createElement("div"); el.id = "toast"; document.body.appendChild(el); }
  el.textContent = msg;
  el.className = `toast show ${type}`;
  clearTimeout(el._t);
  el._t = setTimeout(() => (el.className = "toast"), 3500);
}
