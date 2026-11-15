// PumpPay client service worker — caches the app shell so it opens offline.
const CACHE = "pumppay-v2";
const SHELL = ["./", "index.html", "app.css", "app.js", "manifest.json",
  "../assets/css/theme.css", "../assets/js/firebase-config.js", "../assets/js/plans-default.js",
  "../assets/img/icon-192.png", "../assets/img/icon.svg"];

self.addEventListener("install", (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL))); self.skipWaiting(); });
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return; // Firebase calls go to network
  e.respondWith(fetch(e.request).then((r) => {
    const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r;
  }).catch(() => caches.match(e.request)));
});
