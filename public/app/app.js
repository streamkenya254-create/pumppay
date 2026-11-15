import { auth, db, functions, BRAND, ksh, fmtDate, toast, phoneToEmail, normalizePhone } from "../assets/js/firebase-config.js";
import { signInWithEmailAndPassword, onAuthStateChanged, signOut, updatePassword } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { doc, getDoc, collection, query, where, orderBy, limit, onSnapshot, addDoc, getDocs, serverTimestamp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { httpsCallable } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-functions.js";
import { DEFAULT_PRODUCTS } from "../assets/js/plans-default.js";

const $ = (id) => document.getElementById(id);
const DAY = 86400000;
let me = null, profile = null, devices = [], unsub = [];
const PAYBILL = "000000"; // ← your M-Pesa Paybill / Till number (display only)

document.querySelectorAll("[data-brand]").forEach((e) => (e.textContent = BRAND.name));
$("callBtn").href = `tel:${BRAND.phone.replace(/\s/g, "")}`;
$("helpLink").href = `https://wa.me/${BRAND.whatsapp}?text=${encodeURIComponent("Hi, I need help with my PumpPay account / PIN")}`;
$("pbNo").textContent = PAYBILL;

// ---------- Auth ----------
$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  const btn = e.target.querySelector("button"); btn.disabled = true;
  try { await signInWithEmailAndPassword(auth, phoneToEmail(f.phone), f.pin); }
  catch (err) { toast("Wrong phone number or PIN", "err"); }
  finally { btn.disabled = false; }
});
$("logoutBtn").onclick = () => signOut(auth);

onAuthStateChanged(auth, async (user) => {
  unsub.forEach((u) => u()); unsub = [];
  $("splash").classList.add("gone");
  if (!user) { $("login").classList.remove("hidden"); $("app").classList.add("hidden"); return; }
  me = user;
  const snap = await getDoc(doc(db, "customers", user.uid));
  profile = snap.exists() ? snap.data() : { name: "Customer", phone: user.email.split("@")[0] };
  $("hello").textContent = (profile.name || "").split(" ")[0] || "Mkulima";
  $("accName").textContent = profile.name || "—";
  $("accPhone").textContent = "+" + (profile.phone || "");
  $("accLoc").textContent = [profile.village, profile.county].filter(Boolean).join(", ");
  $("payPhone").value = "0" + String(profile.phone || "").slice(3);
  $("login").classList.add("hidden"); $("app").classList.remove("hidden");
  listen();
});

// ---------- Live data ----------
function listen() {
  unsub.push(onSnapshot(query(collection(db, "devices"), where("customerId", "==", me.uid)), (s) => {
    devices = s.docs.map((d) => ({ id: d.id, ...d.data() }));
    renderDevices(); renderPaySelect(); renderQuick(); renderUsage();
  }));
  unsub.push(onSnapshot(query(collection(db, "payments"), where("customerId", "==", me.uid), orderBy("createdAt", "desc"), limit(100)), (s) => {
    const rows = s.docs.map((d) => ({ id: d.id, ...d.data() }));
    const total = rows.filter((r) => r.status === "success").reduce((a, r) => a + (r.amount || 0), 0);
    $("paidTotal").textContent = ksh(total) + " paid";
    $("payList").innerHTML = rows.length ? rows.map((r) => `
      <div class="row"><div><b>${ksh(r.amount)}</b><div class="meta">${fmtDate(r.createdAt)} · ${r.mpesaReceipt || r.method || "M-Pesa"}</div></div>
      <div style="text-align:right">${statusBadge(r.status)}<div class="meta">${r.creditText || ""}</div></div></div>`).join("")
      : `<p class="muted">No payments yet.</p>`;
  }));
  unsub.push(onSnapshot(query(collection(db, "orders"), where("customerId", "==", me.uid), orderBy("createdAt", "desc")), (s) => {
    $("orderList").innerHTML = s.empty ? `<p class="muted">No orders yet.</p>` : s.docs.map((d) => {
      const o = d.data();
      return `<div class="row"><div><b>${o.name}</b><div class="meta">${fmtDate(o.createdAt)} · ${o.payMode === "plan" ? "Added to plan" : "Cash"}</div></div>${statusBadge(o.status)}</div>`;
    }).join("");
  }));
  listenUsage();
  loadShop();
}

const statusBadge = (s) => {
  const m = { success: "green", paid: "green", delivered: "green", pending: "amber", processing: "amber", confirmed: "black", failed: "red", cancelled: "grey" };
  return `<span class="badge badge-${m[s] || "grey"}">${s || "—"}</span>`;
};

// ---------- Device cards ----------
function deviceState(d) {
  if (d.owned) return { on: true, big: "Owned", unit: "", label: "This system is fully yours 🎉" };
  if (d.planType === "payu") {
    const h = Number(d.hoursBalance || 0);
    return { on: h > 0 && d.status !== "suspended", big: h.toFixed(1), unit: "hrs", label: "pumping hours left" };
  }
  const until = d.activeUntil?.toDate ? d.activeUntil.toDate().getTime() : 0;
  const left = Math.max(0, (until - Date.now()) / DAY);
  return { on: left > 0 && d.status !== "suspended", big: left >= 1 ? Math.floor(left) : (left * 24).toFixed(0), unit: left >= 1 ? "days" : "hrs", label: until ? `credit until ${fmtDate(d.activeUntil)}` : "no credit yet" };
}

function renderDevices() {
  $("noDevice").classList.toggle("hidden", devices.length > 0);
  $("deviceCards").innerHTML = devices.map((d) => {
    const st = deviceState(d);
    const t = d.telemetry || {};
    const pct = d.planType === "payg" && d.totalPrice ? Math.min(100, (100 * (d.totalPaid || 0)) / d.totalPrice) : null;
    return `<div class="dev">
      <div class="head"><span><span class="status-dot ${st.on ? "" : "off"}"></span>${st.on ? "Pump active" : "Pump locked"}</span>
        <span class="badge ${d.planType === "payu" ? "badge-black" : "badge-green"}">${d.planType === "payu" ? "Pay As You Use" : "Pay As You Go"}</span></div>
      <div class="big">${st.big} <small>${st.unit}</small></div><div class="lbl">${st.label}</div>
      ${pct !== null ? `<div class="bar"><i style="width:${pct}%"></i></div>
        <div class="lbl">${ksh(d.totalPaid)} of ${ksh(d.totalPrice)} paid — ${pct.toFixed(0)}% to ownership</div>` : ""}
      <div class="tele">
        <div><b>${fmtNum(t.litresToday)}</b><span>litres today</span></div>
        <div><b>${fmtNum(t.hoursRun)}</b><span>hours run</span></div>
        <div><b>${t.lastSeen ? ago(t.lastSeen) : "—"}</b><span>last online</span></div>
      </div>
      <button class="link" data-go-usage>See water usage →</button>
      ${d.owned ? "" : `<button class="btn btn-green btn-block" data-pay="${d.id}">Top up ${d.serial || ""}</button>`}
    </div>`;
  }).join("");
  document.querySelectorAll("[data-go-usage]").forEach((b) => (b.onclick = () => go("usage")));
  document.querySelectorAll("[data-pay]").forEach((b) => (b.onclick = () => { $("payDevice").value = b.dataset.pay; go("pay"); onDeviceChange(); }));
}
const fmtNum = (n) => (n == null ? "—" : Number(n).toLocaleString("en-KE", { maximumFractionDigits: 1 }));
function ago(ts) {
  const ms = Date.now() - (ts.toDate ? ts.toDate() : new Date(ts)).getTime();
  const m = Math.round(ms / 60000);
  return m < 60 ? `${m}m` : m < 1440 ? `${Math.round(m / 60)}h` : `${Math.round(m / 1440)}d`;
}

// ---------- Usage ----------
let usage = [], range = 1;
const HR = 3600000;
const hourLabel = (h) => `${h % 12 || 12}${h < 12 ? "am" : "pm"}`;
const sumBy = (rows, k) => rows.reduce((a, r) => a + (r[k] || 0), 0);
const rangeBar = (vals, labels, hi) => vals.map((v, i) => {
  const max = Math.max(...vals, 1);
  return `<i class="${i === hi ? "hi" : ""}" style="height:${Math.max(v ? 4 : 1, (v / max) * 100)}%" title="${labels[i]}: ${Math.round(v)} L"></i>`;
}).join("");

function listenUsage() {
  const since = new Date(Date.now() - 61 * DAY);
  unsub.push(onSnapshot(query(collection(db, "usage"), where("customerId", "==", me.uid), where("ts", ">=", since), orderBy("ts")), (s) => {
    usage = s.docs.map((d) => { const u = d.data(); return { t: u.ts.toDate(), litres: u.litres || 0, mins: u.mins || 0 }; });
    renderUsage();
  }, (e) => console.warn("usage", e)));
}

function renderUsage() {
  const midnight = new Date(); midnight.setHours(0, 0, 0, 0);
  const days = range, start = midnight.getTime() - (days - 1) * DAY;
  const cur = usage.filter((u) => u.t.getTime() >= start);
  const prev = usage.filter((u) => u.t.getTime() >= start - days * DAY && u.t.getTime() < start);
  const litres = sumBy(cur, "litres"), pl = sumBy(prev, "litres");

  $("uLitres").textContent = fmtNum(Math.round(litres));
  $("uHours").textContent = (sumBy(cur, "mins") / 60).toFixed(1);
  $("uAvgL").textContent = days === 1 ? "per pump hour" : "avg / day";
  $("uAvg").textContent = days === 1 ? (sumBy(cur, "mins") ? fmtNum(Math.round((litres / sumBy(cur, "mins")) * 60)) : "—") : fmtNum(Math.round(litres / days));
  const vs = days === 1 ? "yesterday" : `previous ${days} days`;
  const dl = $("uDelta");
  if (pl > 0) { const p = Math.round(((litres - pl) / pl) * 100); dl.textContent = `${p >= 0 ? "▲" : "▼"} ${Math.abs(p)}% vs ${vs}`; dl.className = "u-delta small " + (p > 0 ? "up" : "down"); }
  else { dl.textContent = ""; }

  // hour-of-day pattern (at least the last 7 days so a single day isn't noisy)
  const pStart = midnight.getTime() - (Math.max(days, 7) - 1) * DAY;
  const byHour = Array(24).fill(0);
  usage.filter((u) => u.t.getTime() >= pStart).forEach((u) => (byHour[u.t.getHours()] += u.litres));
  const peak = byHour.indexOf(Math.max(...byHour));
  const hasPattern = Math.max(...byHour) > 0;
  $("uPeak").textContent = hasPattern ? `${hourLabel(peak)}–${hourLabel((peak + 1) % 24)}` : "—";
  $("uPattern").innerHTML = rangeBar(byHour, byHour.map((_, h) => hourLabel(h)), hasPattern ? peak : -1);
  $("uPatternCard").classList.toggle("hidden", !hasPattern);

  // main chart: hourly for today, daily for 7/30
  let vals, labels, axis;
  if (days === 1) {
    vals = Array(24).fill(0); cur.forEach((u) => (vals[u.t.getHours()] += u.litres));
    labels = vals.map((_, h) => hourLabel(h)); axis = ["12am", "6am", "12pm", "6pm", "12am"];
    $("uChartT").textContent = "Today, by hour";
  } else {
    vals = Array(days).fill(0); labels = [];
    for (let i = 0; i < days; i++) labels.push(new Date(start + i * DAY).toLocaleDateString("en-KE", { weekday: "short", day: "numeric", month: "short" }));
    cur.forEach((u) => { const i = Math.floor((u.t.getTime() - start) / DAY); if (vals[i] !== undefined) vals[i] += u.litres; });
    axis = days === 7 ? labels.map((l) => l[0]) : ["30d ago", "20d", "10d", "Today"];
    $("uChartT").textContent = `Last ${days} days`;
  }
  $("uChart").innerHTML = rangeBar(vals, labels, Math.max(...vals) > 0 ? vals.indexOf(Math.max(...vals)) : -1);
  $("uAxis").innerHTML = axis.map((a) => `<span>${a}</span>`).join("");
  $("uChart").classList.toggle("thin", days === 30);

  // plain-language insights
  const tot = byHour.reduce((a, b) => a + b, 0), L = [];
  if (tot > 0) {
    const share = (a, b) => Math.round((100 * byHour.slice(a, b).reduce((x, y) => x + y, 0)) / tot);
    const parts = [["morning (5am–12pm)", share(5, 12)], ["afternoon (12pm–6pm)", share(12, 18)], ["evening & night", 100 - share(5, 12) - share(12, 18)]];
    const top = parts.sort((a, b) => b[1] - a[1])[0];
    L.push(`Most of your water (${top[1]}%) is pumped in the ${top[0]}.`);
    if (share(10, 15) < 25 && days >= 7) L.push("Tip: midday has the strongest sun — pumping 10am–3pm gives the most water per hour.");
    if (days > 1) {
      const bi = vals.indexOf(Math.max(...vals));
      L.push(`Busiest day: ${labels[bi]} — ${fmtNum(Math.round(vals[bi]))} litres.`);
    }
  }
  const t = devices[0]?.telemetry;
  if (t?.tankPct != null) L.push(`Tank is about ${Math.round(t.tankPct)}% full.`);
  $("uInsights").classList.toggle("hidden", !L.length);
  $("uInsights").innerHTML = `<h4>Insights</h4><ul>${L.map((l) => `<li>${l}</li>`).join("")}</ul>`;
  if (!usage.length) {
    $("uChart").innerHTML = `<p class="muted small empty-chart">No usage recorded yet. Your pump controller reports usage while it runs — check back after it has pumped.</p>`;
    $("uAxis").innerHTML = "";
  }
}
document.querySelectorAll("#range button").forEach((b) => (b.onclick = () => {
  range = Number(b.dataset.r);
  document.querySelectorAll("#range button").forEach((x) => x.classList.toggle("on", x === b));
  renderUsage();
}));

// ---------- Pay ----------
const payable = () => devices.filter((d) => !d.owned);
function renderPaySelect() {
  const cur = $("payDevice").value;
  $("payDevice").innerHTML = payable().map((d) => `<option value="${d.id}">${d.serial || d.id} — ${d.planType === "payu" ? "PAYU" : "PAYG"}</option>`).join("");
  if (cur) $("payDevice").value = cur;
  onDeviceChange();
}
const curDev = () => devices.find((d) => d.id === $("payDevice").value);

function onDeviceChange() {
  const d = curDev(); if (!d) return;
  $("pbAcc").textContent = d.serial || d.id;
  const isPayu = d.planType === "payu";
  $("bundleBox").classList.toggle("hidden", !isPayu || !(d.bundles || []).length);
  $("bundles").innerHTML = (d.bundles || []).map((b, i) => `<button type="button" data-b="${i}">${b.hours}h<small>${ksh(b.price)}</small></button>`).join("");
  $("bundles").querySelectorAll("button").forEach((btn) => (btn.onclick = () => {
    $("bundles").querySelectorAll("button").forEach((x) => x.classList.remove("on")); btn.classList.add("on");
    $("payAmount").value = d.bundles[btn.dataset.b].price; preview();
  }));
  if (!$("payAmount").value) $("payAmount").value = isPayu ? (d.bundles?.[0]?.price || d.hourlyRate * 10) : d.dailyRate * 7;
  preview();
}
function preview() {
  const d = curDev(); const a = Number($("payAmount").value || 0);
  if (!d || !a) return ($("payPreview").textContent = "—");
  if (d.planType === "payu") {
    const b = (d.bundles || []).find((x) => x.price === a);
    const hrs = b ? b.hours : a / d.hourlyRate;
    $("payPreview").textContent = `≈ ${hrs.toFixed(1)} pumping hours`;
  } else {
    const days = a / d.dailyRate;
    const left = Math.max(0, (d.totalPrice || 0) - (d.totalPaid || 0));
    $("payPreview").textContent = `≈ ${days.toFixed(1)} days of water · ${ksh(Math.max(0, left - a))} left to ownership`;
  }
}
$("payDevice").onchange = () => { $("payAmount").value = ""; onDeviceChange(); };
$("payAmount").oninput = () => { $("bundles").querySelectorAll("button").forEach((x) => x.classList.remove("on")); preview(); };

function renderQuick() {
  const d = payable()[0];
  if (!d) return ($("quick").innerHTML = "");
  const opts = d.planType === "payu"
    ? (d.bundles || []).slice(0, 3).map((b) => ({ a: b.price, l: `${b.hours} hours` }))
    : [1, 7, 30].map((n) => ({ a: n * d.dailyRate, l: n === 1 ? "1 day" : `${n} days` }));
  $("quick").innerHTML = opts.map((o) => `<button data-a="${o.a}">${ksh(o.a)}<small>${o.l}</small></button>`).join("");
  $("quick").querySelectorAll("button").forEach((b) => (b.onclick = () => {
    $("payDevice").value = d.id; onDeviceChange(); $("payAmount").value = b.dataset.a; preview(); go("pay");
  }));
}

$("payBtn").onclick = async () => {
  const d = curDev(); const amount = Math.round(Number($("payAmount").value));
  const phone = normalizePhone($("payPhone").value);
  if (!d) return toast("No pump selected", "err");
  if (!amount || amount < 10) return toast("Enter at least KES 10", "err");
  if (!/^254(7|1)\d{8}$/.test(phone)) return toast("Enter a valid Safaricom number", "err");
  $("payBtn").disabled = true; $("stkStatus").classList.remove("hidden");
  try {
    const res = await httpsCallable(functions, "stkPush")({ deviceId: d.id, amount, phone });
    const stop = onSnapshot(doc(db, "payments", res.data.paymentId), (s) => {
      const p = s.data(); if (!p) return;
      if (p.status === "success") { stop(); done(); toast(`Payment received ✓ ${p.creditText || ""}`); go("home"); }
      if (p.status === "failed") { stop(); done(); toast(p.resultDesc || "Payment cancelled", "err"); }
    });
    setTimeout(() => { stop(); done(); }, 120000);
  } catch (err) { console.error(err); done(); toast(err.message || "Could not start payment", "err"); }
  function done() { $("payBtn").disabled = false; $("stkStatus").classList.add("hidden"); }
};

// ---------- Shop ----------
const PH_ICON = '<svg viewBox="0 0 24 24" width="56" height="56" fill="none" stroke="rgb(89,242,35)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="3"/><path d="M12 1.5v1.5M5.6 3.6l1 1M18.4 3.6l-1 1M3 8h1.5M19.5 8H21"/><path d="M4 22l2-7h12l2 7zM5 18.5h14M10 15l-1 7M14 15l1 7"/></svg>';
let shop = DEFAULT_PRODUCTS;
async function loadShop() {
  try {
    const s = await getDocs(query(collection(db, "products"), where("active", "==", true)));
    if (!s.empty) shop = s.docs.map((d) => ({ id: d.id, ...d.data() }));
  } catch {}
  $("shopList").innerHTML = shop.map((p) => `<div class="sp">
    <div class="ph">${p.imageUrl ? `<img src="${p.imageUrl}" alt="">` : PH_ICON}</div>
    <div class="bd"><h4>${p.name}</h4><div class="pr">${ksh(p.price)}</div>
      <button class="btn btn-black btn-sm" data-buy="${p.id}">Order</button></div></div>`).join("");
  $("shopList").querySelectorAll("[data-buy]").forEach((b) => (b.onclick = () => order(shop.find((p) => p.id === b.dataset.buy))));
}
async function order(p) {
  const onPlan = payable().some((d) => d.planType === "payg");
  const mode = onPlan && confirm(`Add "${p.name}" (${ksh(p.price)}) to your PAYG plan?\n\nOK = add to plan · Cancel = pay cash on delivery`) ? "plan" : "cash";
  await addDoc(collection(db, "orders"), {
    customerId: me.uid, customerName: profile.name || "", phone: profile.phone || "",
    productId: p.id, name: p.name, price: p.price, qty: 1, payMode: mode, status: "pending", createdAt: serverTimestamp(),
  });
  toast("Order sent — we'll call you to confirm delivery");
}

// ---------- Account ----------
$("ticketForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  await addDoc(collection(db, "tickets"), { ...f, customerId: me.uid, customerName: profile.name || "", phone: profile.phone || "",
    deviceId: devices[0]?.id || null, status: "open", createdAt: serverTimestamp() });
  e.target.reset(); toast("Sent — a technician will contact you");
});
$("pinForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const pin = new FormData(e.target).get("pin");
  if (!/^\d{6}$/.test(pin)) return toast("PIN must be 6 digits", "err");
  try { await updatePassword(me, pin); e.target.reset(); toast("PIN updated"); }
  catch { toast("Please log out and in again, then retry", "err"); }
});

// ---------- Navigation ----------
function go(v) {
  document.querySelectorAll(".view").forEach((s) => s.classList.toggle("hidden", s.dataset.view !== v));
  document.querySelectorAll(".tabbar button").forEach((b) => b.classList.toggle("on", b.dataset.go === v));
  window.scrollTo(0, 0);
}
document.querySelectorAll(".tabbar button").forEach((b) => (b.onclick = () => go(b.dataset.go)));

// ---------- PWA ----------
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
let deferred;
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); deferred = e; $("installBtn").hidden = false; });
$("installBtn").onclick = async () => { if (!deferred) return; deferred.prompt(); await deferred.userChoice; deferred = null; $("installBtn").hidden = true; };
