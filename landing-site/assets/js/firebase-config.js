// PumpPay landing page settings.
// The page is static: nothing here talks to a database. Enquiries go to WhatsApp.
export const BRAND = {
  name: "PumpPay",
  company: "Tafiti R&I Hub",
  phone: "+254 701 326554",
  whatsapp: "254701326554",
  email: "tafitiinnovationhub@gmail.com",
  currency: "KES",
};

export const db = null;

export const ksh = (n) =>
  `${BRAND.currency} ${Number(n || 0).toLocaleString("en-KE", { maximumFractionDigits: 0 })}`;

export function normalizePhone(p) {
  let s = String(p || "").replace(/\D/g, "");
  if (s.startsWith("0")) s = "254" + s.slice(1);
  if (s.startsWith("7") || s.startsWith("1")) s = "254" + s;
  return s;
}

export function toast(msg, type = "ok") {
  let el = document.getElementById("toast");
  if (!el) { el = document.createElement("div"); el = Object.assign(el, { id: "toast" }); document.body.appendChild(el); }
  el.textContent = msg;
  el.className = `toast show ${type}`;
  clearTimeout(el._t);
  el._t = setTimeout(() => (el.className = "toast"), 3500);
}
