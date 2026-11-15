import { auth, db, storage, functions, BRAND, ksh, fmtDate, toast, normalizePhone, firebaseConfig } from "../assets/js/firebase-config.js";
import { signInWithEmailAndPassword, onAuthStateChanged, signOut } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import { collection, doc, getDoc, setDoc, addDoc, updateDoc, deleteDoc, onSnapshot, query, orderBy, limit, serverTimestamp, Timestamp }
  from "https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js";
import { ref, uploadBytes, getDownloadURL } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-storage.js";
import { httpsCallable } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-functions.js";
import { DEFAULT_PLANS, DEFAULT_PRODUCTS } from "../assets/js/plans-default.js";

const $ = (id) => document.getElementById(id);
const call = (name, data) => httpsCallable(functions, name)(data).then((r) => r.data);
const S = { customers: [], devices: [], payments: [], plans: [], products: [], orders: [], leads: [], tickets: [] };
let view = "overview", unsubs = [];
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const badge = (t, c) => `<span class="badge badge-${c}">${esc(t)}</span>`;
const custName = (id) => S.customers.find((c) => c.id === id)?.name || "—";

document.querySelectorAll("[data-brand]").forEach((e) => (e.textContent = BRAND.name));
$("apiInfo").textContent =
`GET https://europe-west1-${firebaseConfig.projectId}.cloudfunctions.net/deviceSync?serial=SERIAL&key=SECRET
     &litres=123&hours=4.5&solarW=820          (optional telemetry)

Response: {"on":true,"mode":"payg","until":1761300000,"hours":0,"owned":false}
• PAYG → run while now < until
• PAYU → controller counts run-time locally and reports "hours"; the server deducts it`;

// ---------- Auth ----------
$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = Object.fromEntries(new FormData(e.target));
  try { await signInWithEmailAndPassword(auth, f.email, f.password); }
  catch { toast("Wrong email or password", "err"); }
});
$("logout").onclick = () => signOut(auth);

onAuthStateChanged(auth, async (u) => {
  unsubs.forEach((x) => x()); unsubs = [];
  if (!u) return show(false);
  const a = await getDoc(doc(db, "admins", u.uid)).catch(() => null);
  if (!a?.exists()) {
    $("loginNote").innerHTML = `This account is not an admin. Add <code>admins/${u.uid}</code> in Firestore.`;
    await signOut(auth); return show(false);
  }
  $("who").textContent = u.email; show(true); listen();
});
function show(on) { $("login").classList.toggle("hidden", on); $("shell").classList.toggle("hidden", !on); }

function listen() {
  const sub = (name, q) => unsubs.push(onSnapshot(q, (s) => { S[name] = s.docs.map((d) => ({ id: d.id, ...d.data() })); render(); },
    (err) => console.warn(name, err.message)));
  sub("customers", query(collection(db, "customers"), orderBy("createdAt", "desc")));
  sub("devices", collection(db, "devices"));
  sub("payments", query(collection(db, "payments"), orderBy("createdAt", "desc"), limit(1000)));
  sub("plans", collection(db, "plans"));
  sub("products", collection(db, "products"));
  sub("orders", query(collection(db, "orders"), orderBy("createdAt", "desc"), limit(500)));
  sub("leads", query(collection(db, "leads"), orderBy("createdAt", "desc"), limit(500)));
  sub("tickets", query(collection(db, "tickets"), orderBy("createdAt", "desc"), limit(500)));
}

// ---------- Navigation ----------
const TITLES = { overview: "Overview", customers: "Customers", devices: "Controllers", payments: "Payments", plans: "Plans",
  products: "Products", orders: "Orders", leads: "Leads", tickets: "Support tickets", settings: "Settings" };
const ADD = { customers: addCustomer, devices: () => editDevice(), payments: () => recordPayment(), plans: () => editPlan(), products: () => editProduct() };
document.querySelectorAll("#menu a").forEach((a) => (a.onclick = () => {
  view = a.dataset.v;
  document.querySelectorAll("#menu a").forEach((x) => x.classList.toggle("on", x === a));
  document.querySelectorAll(".view").forEach((v) => v.classList.toggle("hidden", v.dataset.view !== view));
  $("title").textContent = TITLES[view]; $("search").value = "";
  $("addBtn").classList.toggle("hidden", !ADD[view]);
  $("side").classList.remove("open"); render();
}));
$("addBtn").onclick = () => ADD[view]?.();
$("burger").onclick = () => $("side").classList.toggle("open");
$("search").oninput = () => render();
const match = (row) => { const q = $("search").value.trim().toLowerCase(); return !q || JSON.stringify(row).toLowerCase().includes(q); };

// ---------- Device state ----------
function isOn(d) {
  if (d.status === "suspended") return false;
  if (d.owned) return true;
  if (d.planType === "payu") return (d.hoursBalance || 0) > 0;
  return d.activeUntil?.toMillis?.() > Date.now();
}
function creditText(d) {
  if (d.owned) return "Owned";
  if (d.planType === "payu") return `${Number(d.hoursBalance || 0).toFixed(1)} h`;
  const ms = (d.activeUntil?.toMillis?.() || 0) - Date.now();
  return ms > 0 ? `${(ms / 86400000).toFixed(1)} days` : "expired";
}

// ---------- Render ----------
function render() {
  $("cntOrders").textContent = S.orders.filter((o) => o.status === "pending").length || "";
  $("cntLeads").textContent = S.leads.filter((o) => o.status === "new").length || "";
  $("cntTickets").textContent = S.tickets.filter((o) => o.status === "open").length || "";
  ({ overview, customers, devices, payments, plans, products, orders, leads, tickets }[view] || (() => {}))();
}

function table(id, head, rows) {
  $(id).innerHTML = `<thead><tr>${head.map((h) => `<th>${h}</th>`).join("")}</tr></thead>
    <tbody>${rows.length ? rows.join("") : `<tr><td colspan="${head.length}" class="muted">Nothing here yet.</td></tr>`}</tbody>`;
}
function bind(id, attr, fn) { $(id).querySelectorAll(`[${attr}]`).forEach((b) => (b.onclick = () => fn(b.getAttribute(attr), b))); }

function overview() {
  const ok = S.payments.filter((p) => p.status === "success");
  const now = new Date(), startDay = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime(),
    startMonth = new Date(now.getFullYear(), now.getMonth(), 1).getTime();
  const t = (p) => p.createdAt?.toMillis?.() || 0;
  const sum = (arr) => arr.reduce((a, p) => a + (p.amount || 0), 0);
  const on = S.devices.filter(isOn).length;
  const payg = S.devices.filter((d) => d.planType === "payg" && !d.owned);
  const outstanding = payg.reduce((a, d) => a + Math.max(0, (d.totalPrice || 0) - (d.totalPaid || 0)), 0);
  $("kpis").innerHTML = [
    ["Revenue today", ksh(sum(ok.filter((p) => t(p) >= startDay))), true],
    ["Revenue this month", ksh(sum(ok.filter((p) => t(p) >= startMonth)))],
    ["Customers", S.customers.length],
    ["Pumps running", `${on} / ${S.devices.length}`],
    ["Pumps locked", S.devices.length - on],
    ["PAYG receivable", ksh(outstanding)],
    ["New leads", S.leads.filter((l) => l.status === "new").length],
    ["Open tickets", S.tickets.filter((x) => x.status === "open").length],
  ].map(([k, v, d]) => `<div class="kpi ${d ? "dark" : ""}"><span>${k}</span><b>${v}</b></div>`).join("");

  const days = [...Array(30)].map((_, i) => { const d = new Date(startDay - (29 - i) * 86400000); return { d, v: 0 }; });
  ok.forEach((p) => { const i = Math.floor((t(p) - days[0].d.getTime()) / 86400000); if (i >= 0 && i < 30) days[i].v += p.amount || 0; });
  const max = Math.max(1, ...days.map((x) => x.v));
  $("chart").innerHTML = days.map((x) => `<div style="height:${(100 * x.v) / max}%" data-t="${x.d.toLocaleDateString("en-KE", { day: "numeric", month: "short" })}: ${ksh(x.v)}"></div>`).join("");

  const n = S.devices.length || 1, g = S.devices.filter((d) => d.planType === "payg").length, u = S.devices.filter((d) => d.planType === "payu").length,
    o = S.devices.filter((d) => d.owned).length;
  $("mix").innerHTML = [["Pay As You Go", g, "g"], ["Pay As You Use", u, ""], ["Fully owned", o, "g"]].map(([l, c, k]) =>
    `<div class="mixrow ${k}"><div class="flex between"><span>${l}</span><b>${c}</b></div><div class="bar2"><i style="width:${(100 * c) / n}%"></i></div></div>`).join("");

  table("tLatest", ["Date", "Customer", "Controller", "Amount", "Receipt", "Status"], S.payments.slice(0, 8).map(payRow));
}

const payRow = (p) => `<tr><td>${fmtDate(p.createdAt)}</td><td>${esc(custName(p.customerId))}</td><td>${esc(p.serial || p.deviceId)}</td>
  <td><b>${ksh(p.amount)}</b></td><td>${esc(p.mpesaReceipt || p.method || "")}</td>
  <td>${badge(p.status, p.status === "success" ? "green" : p.status === "failed" ? "red" : "amber")}</td></tr>`;

function customers() {
  table("tCustomers", ["Name", "Phone", "Location", "Controllers", "Total paid", "Joined", ""],
    S.customers.filter(match).map((c) => {
      const ds = S.devices.filter((d) => d.customerId === c.id);
      return `<tr><td><b>${esc(c.name)}</b></td><td>+${esc(c.phone)}</td><td>${esc([c.village, c.county].filter(Boolean).join(", "))}</td>
        <td>${ds.map((d) => badge(d.serial, isOn(d) ? "green" : "red")).join(" ") || "—"}</td>
        <td>${ksh(ds.reduce((a, d) => a + (d.totalPaid || 0), 0))}</td><td>${fmtDate(c.createdAt)}</td>
        <td><button class="btn btn-sm btn-outline" data-pin="${c.id}">Reset PIN</button></td></tr>`;
    }));
  bind("tCustomers", "data-pin", (id) => form("Reset PIN", [{ n: "pin", l: "New 6-digit PIN", req: 1, pattern: "\\d{6}" }], {}, async (v) => {
    await call("resetPin", { uid: id, pin: v.pin }); toast("PIN reset — tell the customer");
  }));
}

function devices() {
  table("tDevices", ["Serial", "Customer", "Plan", "Credit", "Paid / Price", "Status", "Last seen", ""],
    S.devices.filter(match).map((d) => `<tr><td><b>${esc(d.serial)}</b></td><td>${esc(custName(d.customerId))}</td>
      <td>${badge(d.planType === "payu" ? "PAYU" : "PAYG", d.planType === "payu" ? "black" : "green")}</td>
      <td>${creditText(d)}</td><td>${ksh(d.totalPaid)}${d.planType === "payg" ? ` / ${ksh(d.totalPrice)}` : ""}</td>
      <td>${d.status === "suspended" ? badge("suspended", "red") : badge(isOn(d) ? "running" : "locked", isOn(d) ? "green" : "amber")}</td>
      <td>${fmtDate(d.telemetry?.lastSeen)}</td>
      <td><button class="btn btn-sm btn-green" data-credit="${d.id}">Credit</button><button class="btn btn-sm btn-outline" data-edit="${d.id}">Edit</button>
      <button class="btn btn-sm btn-outline" data-sus="${d.id}">${d.status === "suspended" ? "Resume" : "Suspend"}</button>
      <button class="btn btn-sm btn-outline" data-key="${d.id}">Key</button></td></tr>`));
  bind("tDevices", "data-credit", (id) => recordPayment(id));
  bind("tDevices", "data-edit", (id) => editDevice(S.devices.find((d) => d.id === id)));
  bind("tDevices", "data-sus", async (id) => {
    const d = S.devices.find((x) => x.id === id);
    await updateDoc(doc(db, "devices", id), { status: d.status === "suspended" ? "active" : "suspended" }); toast("Updated");
  });
  bind("tDevices", "data-key", async (id) => {
    const k = await getDoc(doc(db, "deviceKeys", id));
    prompt("Device secret key (flash this into the controller):", k.data()?.key || "none");
  });
}

function payments() {
  table("tPayments", ["Date", "Customer", "Controller", "Amount", "Credit", "Method / receipt", "Phone", "Status"],
    S.payments.filter(match).map((p) => `<tr><td>${fmtDate(p.createdAt)}</td><td>${esc(custName(p.customerId))}</td><td>${esc(p.serial || "")}</td>
      <td><b>${ksh(p.amount)}</b></td><td>${esc(p.creditText || "")}</td><td>${esc(p.mpesaReceipt || p.method || "")}</td><td>${esc(p.phone || "")}</td>
      <td>${badge(p.status, p.status === "success" ? "green" : p.status === "failed" ? "red" : "amber")}</td></tr>`));
}

function plans() {
  table("tPlans", ["Name", "Type", "Deposit", "Rate", "Full price", "Active", ""],
    S.plans.filter(match).map((p) => `<tr><td><b>${esc(p.name)}</b></td><td>${badge(p.type.toUpperCase(), p.type === "payg" ? "green" : "black")}</td>
      <td>${ksh(p.deposit)}</td><td>${p.type === "payg" ? `${ksh(p.dailyRate)}/day` : `${ksh(p.hourlyRate)}/hr`}</td>
      <td>${p.type === "payg" ? ksh(p.totalPrice) : "—"}</td><td>${p.active ? "✓" : "—"}</td>
      <td><button class="btn btn-sm btn-outline" data-e="${p.id}">Edit</button></td></tr>`));
  bind("tPlans", "data-e", (id) => editPlan(S.plans.find((p) => p.id === id)));
}

function products() {
  table("tProducts", ["", "Name", "Price", "Active", ""],
    S.products.filter(match).map((p) => `<tr><td>${p.imageUrl ? `<img class="thumb" src="${p.imageUrl}">` : ""}</td>
      <td><b>${esc(p.name)}</b><div class="muted small">${esc(p.desc)}</div></td><td>${ksh(p.price)}</td><td>${p.active ? "✓" : "—"}</td>
      <td><button class="btn btn-sm btn-outline" data-e="${p.id}">Edit</button><button class="btn btn-sm btn-outline" data-d="${p.id}">Delete</button></td></tr>`));
  bind("tProducts", "data-e", (id) => editProduct(S.products.find((p) => p.id === id)));
  bind("tProducts", "data-d", async (id) => { if (confirm("Delete this product?")) await deleteDoc(doc(db, "products", id)); });
}

const statusSelect = (col, id, cur, opts) =>
  `<select data-st="${col}|${id}" style="width:auto;padding:6px 8px">${opts.map((o) => `<option ${o === cur ? "selected" : ""}>${o}</option>`).join("")}</select>`;
function bindStatus(tid) {
  $(tid).querySelectorAll("[data-st]").forEach((s) => (s.onchange = async () => {
    const [col, id] = s.dataset.st.split("|"); await updateDoc(doc(db, col, id), { status: s.value, updatedAt: serverTimestamp() }); toast("Status updated");
  }));
}
function orders() {
  table("tOrders", ["Date", "Customer", "Phone", "Product", "Price", "Payment", "Status"],
    S.orders.filter(match).map((o) => `<tr><td>${fmtDate(o.createdAt)}</td><td>${esc(o.customerName)}</td><td>+${esc(o.phone)}</td><td><b>${esc(o.name)}</b></td>
      <td>${ksh(o.price)}</td><td>${o.payMode === "plan" ? badge("Add to PAYG", "green") : badge("Cash", "grey")}</td>
      <td>${statusSelect("orders", o.id, o.status, ["pending", "confirmed", "delivered", "cancelled"])}</td></tr>`));
  bindStatus("tOrders");
}
function leads() {
  table("tLeads", ["Date", "Name", "Phone", "County", "Acres", "Water source", "Plan", "Status"],
    S.leads.filter(match).map((l) => `<tr><td>${fmtDate(l.createdAt)}</td><td><b>${esc(l.name)}</b></td>
      <td><a href="tel:+${esc(l.phone)}">+${esc(l.phone)}</a></td><td>${esc(l.county)}</td><td>${esc(l.acres)}</td><td>${esc(l.waterSource)}</td>
      <td>${esc(l.plan)}</td><td>${statusSelect("leads", l.id, l.status, ["new", "contacted", "site visit", "won", "lost"])}</td></tr>`));
  bindStatus("tLeads");
}
function tickets() {
  table("tTickets", ["Date", "Customer", "Phone", "Issue", "Details", "Status"],
    S.tickets.filter(match).map((t) => `<tr><td>${fmtDate(t.createdAt)}</td><td>${esc(t.customerName)}</td><td>+${esc(t.phone)}</td>
      <td><b>${esc(t.kind)}</b></td><td style="white-space:normal;min-width:220px">${esc(t.message)}</td>
      <td>${statusSelect("tickets", t.id, t.status, ["open", "assigned", "resolved"])}</td></tr>`));
  bindStatus("tTickets");
}

// ---------- Generic form modal ----------
function form(title, fields, values, onSubmit) {
  $("mTitle").textContent = title;
  $("mForm").innerHTML = fields.map((f) => {
    const v = values[f.n] ?? f.def ?? "";
    if (f.t === "select") return `<div class="field"><label>${f.l}</label><select name="${f.n}" ${f.req ? "required" : ""}>${f.opts.map(([k, l]) =>
      `<option value="${esc(k)}" ${String(k) === String(v) ? "selected" : ""}>${esc(l)}</option>`).join("")}</select></div>`;
    if (f.t === "check") return `<div class="field chk"><input type="checkbox" name="${f.n}" ${v ? "checked" : ""}><label style="margin:0">${f.l}</label></div>`;
    if (f.t === "textarea") return `<div class="field"><label>${f.l}</label><textarea name="${f.n}" rows="4">${esc(v)}</textarea></div>`;
    if (f.t === "file") return `<div class="field"><label>${f.l}</label><input type="file" name="${f.n}" accept="image/*"></div>`;
    return `<div class="field"><label>${f.l}</label><input name="${f.n}" type="${f.t || "text"}" value="${esc(v)}" ${f.req ? "required" : ""}
      ${f.pattern ? `pattern="${f.pattern}"` : ""} ${f.t === "number" ? 'step="any"' : ""} ${f.ph ? `placeholder="${esc(f.ph)}"` : ""}></div>`;
  }).join("") + `<button class="btn btn-green btn-block">Save</button>`;
  $("modal").classList.add("open");
  $("mForm").onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData(e.target), v = {};
    fields.forEach((f) => { v[f.n] = f.t === "check" ? fd.get(f.n) === "on" : f.t === "number" ? Number(fd.get(f.n) || 0) : fd.get(f.n); });
    const btn = e.target.querySelector("button[class*=btn-green]"); btn.disabled = true; btn.textContent = "Saving…";
    try { await onSubmit(v); $("modal").classList.remove("open"); }
    catch (err) { console.error(err); toast(err.message || "Failed", "err"); }
    finally { btn.disabled = false; btn.textContent = "Save"; }
  };
}
$("mClose").onclick = () => $("modal").classList.remove("open");

// ---------- Actions ----------
function addCustomer() {
  form("New customer", [
    { n: "name", l: "Full name", req: 1 }, { n: "phone", l: "Phone (M-Pesa)", req: 1, ph: "07xx xxx xxx" },
    { n: "pin", l: "Starting 6-digit PIN (customer can change it)", req: 1, pattern: "\\d{6}" },
    { n: "county", l: "County" }, { n: "village", l: "Village / location" },
  ], {}, async (v) => {
    await call("createCustomer", { ...v, phone: normalizePhone(v.phone) });
    toast(`Customer created — login: phone + PIN ${v.pin}`);
  });
}

function editDevice(d = {}) {
  const planOpts = S.plans.map((p) => [p.id, `${p.name} (${p.type.toUpperCase()})`]);
  form(d.id ? `Edit ${d.serial}` : "New controller", [
    { n: "serial", l: "Serial number", req: 1, ph: "PP-0001" },
    { n: "customerId", l: "Customer", t: "select", opts: [["", "— unassigned —"], ...S.customers.map((c) => [c.id, `${c.name} (+${c.phone})`])] },
    { n: "planId", l: "Plan", t: "select", req: 1, opts: planOpts.length ? planOpts : [["", "Create a plan first (Settings → Seed)"]] },
    { n: "totalPrice", l: "System price (PAYG ownership, KES) — leave 0 to use plan price", t: "number" },
    { n: "notes", l: "Install notes", t: "textarea" },
  ], d, async (v) => {
    const plan = S.plans.find((p) => p.id === v.planId);
    if (!plan) throw new Error("Choose a plan");
    const data = {
      serial: v.serial.trim().toUpperCase(), customerId: v.customerId || null, planId: plan.id, planType: plan.type,
      dailyRate: plan.dailyRate || 0, hourlyRate: plan.hourlyRate || 0, bundles: plan.bundles || [],
      totalPrice: v.totalPrice || plan.totalPrice || 0, notes: v.notes || "", updatedAt: serverTimestamp(),
    };
    if (d.id) { await updateDoc(doc(db, "devices", d.id), data); }
    else {
      const id = data.serial;
      if ((await getDoc(doc(db, "devices", id))).exists()) throw new Error("Serial already exists");
      await setDoc(doc(db, "devices", id), { ...data, status: "active", owned: false, totalPaid: 0, hoursBalance: 0,
        activeUntil: Timestamp.fromMillis(0), telemetry: {}, createdAt: serverTimestamp() });
      const key = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
      await setDoc(doc(db, "deviceKeys", id), { key });
      prompt("Controller created. Secret key — flash into firmware:", key);
    }
    toast("Saved");
  });
}

function recordPayment(deviceId = "") {
  form("Record payment (cash / bank / manual)", [
    { n: "deviceId", l: "Controller", t: "select", req: 1, opts: S.devices.map((d) => [d.id, `${d.serial} — ${custName(d.customerId)}`]) },
    { n: "amount", l: "Amount (KES)", t: "number", req: 1 },
    { n: "method", l: "Method", t: "select", opts: [["cash", "Cash"], ["bank", "Bank"], ["mpesa-manual", "M-Pesa (manual)"], ["bonus", "Free credit / bonus"]] },
    { n: "reference", l: "Receipt / reference" },
    { n: "isDeposit", l: "This is the deposit (PAYG: gives the starter days)", t: "check" },
  ], { deviceId }, async (v) => {
    const r = await call("recordPayment", v); toast(`Credited: ${r.creditText}`);
  });
}

function editPlan(p = { type: "payg", active: true }) {
  form(p.id ? `Edit ${p.name}` : "New plan", [
    { n: "name", l: "Plan name", req: 1 }, { n: "tagline", l: "Tagline" },
    { n: "type", l: "Type", t: "select", opts: [["payg", "Pay As You Go (ownership)"], ["payu", "Pay As You Use (per hour)"]] },
    { n: "deposit", l: "Deposit / installation (KES)", t: "number" },
    { n: "dailyRate", l: "PAYG daily rate (KES/day)", t: "number" },
    { n: "totalPrice", l: "PAYG full price for ownership (KES)", t: "number" },
    { n: "depositDays", l: "PAYG days given with deposit", t: "number", def: 7 },
    { n: "hourlyRate", l: "PAYU rate (KES/hour)", t: "number" },
    { n: "bundlesTxt", l: "PAYU bundles — one per line: hours,price", t: "textarea" },
    { n: "featuresTxt", l: "Features shown on website — one per line", t: "textarea" },
    { n: "order", l: "Display order", t: "number" }, { n: "featured", l: "Highlight on website", t: "check" }, { n: "active", l: "Active", t: "check" },
  ], { ...p, bundlesTxt: (p.bundles || []).map((b) => `${b.hours},${b.price}`).join("\n"), featuresTxt: (p.features || []).join("\n") }, async (v) => {
    const bundles = (v.bundlesTxt || "").split("\n").map((l) => l.split(",").map(Number)).filter(([h, pr]) => h > 0 && pr > 0).map(([hours, price]) => ({ hours, price }));
    const features = (v.featuresTxt || "").split("\n").map((s) => s.trim()).filter(Boolean);
    const { bundlesTxt, featuresTxt, ...rest } = v;
    const data = { ...rest, bundles, features, updatedAt: serverTimestamp() };
    if (p.id) await updateDoc(doc(db, "plans", p.id), data); else await addDoc(collection(db, "plans"), data);
    toast("Plan saved — controllers keep their old rates until you Edit them");
  });
}

function editProduct(p = { active: true }) {
  form(p.id ? `Edit ${p.name}` : "New product", [
    { n: "name", l: "Name", req: 1 }, { n: "price", l: "Price (KES)", t: "number", req: 1 },
    { n: "desc", l: "Short description", t: "textarea" }, 
    { n: "image", l: "Photo (uploaded to Firebase Storage)", t: "file" }, { n: "active", l: "Show on website & app", t: "check" },
  ], p, async (v) => {
    const file = $("mForm").querySelector('[name="image"]').files[0];
    const { image, ...data } = v;
    const id = p.id || doc(collection(db, "products")).id;
    if (file) {
      const r = ref(storage, `products/${id}/${Date.now()}-${file.name}`);
      await uploadBytes(r, file, { contentType: file.type });
      data.imageUrl = await getDownloadURL(r);
    }
    await setDoc(doc(db, "products", id), { ...data, updatedAt: serverTimestamp() }, { merge: true });
    toast("Product saved");
  });
}

$("seedBtn").onclick = async () => {
  for (const { id, ...p } of DEFAULT_PLANS) if (!S.plans.some((x) => x.id === id))
    await setDoc(doc(db, "plans", id), { ...p, depositDays: p.type === "payg" ? 7 : 0, active: true, createdAt: serverTimestamp() });
  for (const { id, ...p } of DEFAULT_PRODUCTS) if (!S.products.some((x) => x.id === id))
    await setDoc(doc(db, "products", id), { ...p, createdAt: serverTimestamp() });
  toast("Default plans & products added");
};
