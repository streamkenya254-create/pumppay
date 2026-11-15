import { db, BRAND, ksh, normalizePhone, toast } from "./firebase-config.js";
import { DEFAULT_PLANS, DEFAULT_PRODUCTS } from "./plans-default.js";

// ---------- Brand binding ----------
document.querySelectorAll("[data-brand]").forEach((e) => (e.textContent = BRAND.name));
document.querySelectorAll("[data-company]").forEach((e) => (e.textContent = BRAND.company));
document.querySelectorAll("[data-phone]").forEach((e) => { e.textContent = BRAND.phone; e.href = `tel:${BRAND.phone.replace(/\s/g, "")}`; });
document.querySelectorAll("[data-email]").forEach((e) => (e.href = `mailto:${BRAND.email}`));
document.getElementById("yr").textContent = new Date().getFullYear();
document.getElementById("wa").href = `https://wa.me/${BRAND.whatsapp}?text=${encodeURIComponent(`Hi ${BRAND.name}, I'm interested in a solar pump.`)}`;
document.getElementById("burger").onclick = () => document.getElementById("links").classList.toggle("open");
document.querySelectorAll("#links a").forEach((a) => (a.onclick = () => document.getElementById("links").classList.remove("open")));

// ---------- Plans ----------
let plans = DEFAULT_PLANS;
function renderPlans() {
  const el = document.getElementById("plans");
  el.innerHTML = plans
    .sort((a, b) => (a.order || 0) - (b.order || 0))
    .map((p) => {
      const price = p.type === "payg"
        ? `${ksh(p.dailyRate)}<small> / day</small>`
        : `${ksh(p.hourlyRate)}<small> / hour</small>`;
      const sub = p.type === "payg"
        ? `${ksh(p.deposit)} deposit · own it at ${ksh(p.totalPrice)}`
        : `${ksh(p.deposit)} installation · no contract`;
      return `<div class="plan ${p.featured ? "featured" : ""}">
        <span class="tag">${p.type === "payg" ? "PAYG · Ownership" : "PAYU · Usage"}</span>
        <h3>${p.name}</h3><div class="sub">${p.tagline || ""}</div>
        <div class="price">${price}</div><div class="sub">${sub}</div>
        <ul>${(p.features || []).map((f) => `<li>${f}</li>`).join("")}</ul>
        <a href="#contact" class="btn btn-green btn-block" data-plan="${p.type}">Choose ${p.name}</a>
      </div>`;
    }).join("");
  el.querySelectorAll("[data-plan]").forEach((b) => (b.onclick = () => {
    const sel = document.querySelector('#leadForm [name="plan"]'); if (sel) sel.value = b.dataset.plan;
  }));
  calc();
}

// ---------- Products ----------
const PH_ICON = '<svg viewBox="0 0 24 24" width="56" height="56" fill="none" stroke="rgb(89,242,35)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="3"/><path d="M12 1.5v1.5M5.6 3.6l1 1M18.4 3.6l-1 1M3 8h1.5M19.5 8H21"/><path d="M4 22l2-7h12l2 7zM5 18.5h14M10 15l-1 7M14 15l1 7"/></svg>';
let products = DEFAULT_PRODUCTS;
function renderProducts() {
  document.getElementById("products").innerHTML = products.filter((p) => p.active !== false).map((p) => `
    <div class="prod">
      <div class="ph">${p.imageUrl ? `<img src="${p.imageUrl}" alt="${p.name}" loading="lazy">` : PH_ICON}</div>
      <div class="bd"><h4>${p.name}</h4><p>${p.desc || ""}</p><div class="pr">${ksh(p.price)}</div>
        <a class="btn btn-black btn-sm" target="_blank" rel="noopener"
           href="https://wa.me/${BRAND.whatsapp}?text=${encodeURIComponent(`Hi, I'd like to order: ${p.name} (${ksh(p.price)})`)}">Order on WhatsApp</a>
      </div>
    </div>`).join("");
}

// ---------- Savings calculator ----------
const $ = (id) => document.getElementById(id);
function calc() {
  const h = +$("hours").value, l = +$("lph").value, pr = +$("price").value;
  $("hV").textContent = h; $("lV").textContent = l.toFixed(1); $("pV").textContent = pr;
  const diesel = h * l * pr * 30;
  const payg = (plans.find((p) => p.type === "payg")?.dailyRate || 150) * 30;
  const save = Math.max(0, diesel - payg);
  $("dieselM").textContent = ksh(diesel);
  $("paygM").textContent = ksh(payg);
  $("saveM").textContent = ksh(save);
  $("saveY").textContent = ksh(save * 12);
}
["hours", "lph", "price"].forEach((id) => $(id).addEventListener("input", calc));

// ---------- Lead form ----------
$("leadForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  const btn = e.target.querySelector("button"); btn.disabled = true; btn.textContent = "Sending…";
  // No database behind this page: the details go to the sales WhatsApp, ready to send.
  const lines = [
    "Hi PumpPay, I'd like a free site visit.",
    "Name: " + f.name,
    "Phone: " + f.phone,
    "County: " + f.county,
    f.acres ? "Farm size: " + f.acres + " acres" : "",
    "Water source: " + f.waterSource,
    "Plan: " + f.plan,
  ].filter(Boolean);
  window.open("https://wa.me/" + BRAND.whatsapp + "?text=" + encodeURIComponent(lines.join("\n")), "_blank");
  e.target.reset();
  toast("Opening WhatsApp - tap send to reach our team.");
  } finally { btn.disabled = false; btn.textContent = "Request a call back"; }
});

// ---------- Load live data (falls back to defaults) ----------
renderPlans(); renderProducts();
(async () => {
  try {
    const ps = await getDocs(query(collection(db, "plans"), where("active", "==", true)));
    if (!ps.empty) { plans = ps.docs.map((d) => ({ id: d.id, ...d.data() })); renderPlans(); }
    const pr = await getDocs(query(collection(db, "products"), where("active", "==", true)));
    if (!pr.empty) { products = pr.docs.map((d) => ({ id: d.id, ...d.data() })); renderProducts(); }
  } catch (err) { console.warn("Using default plans/products:", err.message); }
})();
