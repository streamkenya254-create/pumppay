#include "webui.h"
#include "power_meter.h"
#include "pump.h"
#include "report.h"
#include "ethernet.h"
#include "wifi_net.h"
#include "settings.h"
#include "display.h"
#include "buttons.h"

#include <WebServer.h>

namespace WebUI {
namespace {

// ===========================================================================
//  CONTROL PIN
// ===========================================================================
// The PIN lives in NVS - see settings.h. It can be changed from the buttons,
// over Bluetooth, or from this page, and it survives a reflash.
//
// BE CLEAR ABOUT WHAT IT DOES AND DOES NOT BUY. It stops a casual visitor on
// the network from changing the board's WiFi or PIN. It is NOT security
// against anyone determined:
//
//   - the page is plain HTTP, so the PIN crosses the network in the clear and
//     anyone able to capture traffic on the LAN can read it;
//   - it is one shared PIN, so it cannot be revoked for one person, and the
//     log can only ever record an IP address rather than a who.
//
// Treat it as a guard against accident, not against attack. If the board is
// ever on a network you do not control, set ALLOW_WEB_CONTROL to 0 and use the
// LCD or a paired phone instead.

// Set to 0 for a view-only page. The settings and panel sections disappear and
// the endpoints refuse everything, no PIN accepted.
#define ALLOW_WEB_CONTROL 1

// Rate limit. A four-digit PIN is ten thousand guesses, which is minutes of
// work over HTTP if nothing slows it down - so something must. After this many
// consecutive failures the endpoint refuses everything for LOCKOUT_MS,
// regardless of the PIN offered.
constexpr uint8_t  MAX_FAILURES = 5;
constexpr uint32_t LOCKOUT_MS   = 60000;

WebServer s_server(80);
bool      s_running = false;

uint8_t   s_failures  = 0;
uint32_t  s_lockedAt  = 0;

bool lockedOut() {
  if (s_failures < MAX_FAILURES) return false;
  if (millis() - s_lockedAt < LOCKOUT_MS) return true;

  s_failures = 0;      // lockout served
  return false;
}

// ---- The page -------------------------------------------------------------
// Everything is inline - no CDN, no external font, no framework. The board may
// well be on a network with no route to the internet, and a page that depended
// on fetching anything would show up broken exactly when it was needed most.
//
// Held in PROGMEM: a few kilobytes of constant text with no reason to occupy
// RAM.
const char PAGE[] PROGMEM = R"HTML(<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PumpPay</title>
<style>
  :root{
    --bg:#0b0b0b; --card:#141414; --line:#262626;
    --txt:#f2f2f2; --dim:#8a8a8a;
    --green:#59f223; --bad:#ff5252;
  }
  *{box-sizing:border-box}
  body{
    margin:0; padding:16px; max-width:560px; margin-inline:auto;
    background:var(--bg); color:var(--txt);
    font:16px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
    -webkit-font-smoothing:antialiased;
  }
  header{display:flex;align-items:center;justify-content:space-between;
         gap:12px;margin-bottom:16px}
  h1{font-size:18px;font-weight:700;margin:0;letter-spacing:-.01em}
  h1 b{color:var(--green)}
  #state{font-size:13px;font-weight:700;letter-spacing:.08em;
         padding:5px 12px;border-radius:999px;white-space:nowrap;
         background:#1c1c1c;color:var(--dim)}
  #state.on{background:var(--green);color:#000}
  #state.bad{background:rgba(255,82,82,.15);color:var(--bad)}

  .card{background:var(--card);border:1px solid var(--line);
        border-radius:16px;padding:18px;margin-bottom:12px}
  .card h2{margin:0 0 12px;font-size:12px;letter-spacing:.14em;
           text-transform:uppercase;font-weight:700;color:var(--dim)}

  .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
  .grid.two{grid-template-columns:repeat(2,1fr)}
  .lbl{font-size:11px;letter-spacing:.1em;color:var(--dim);text-transform:uppercase}
  .val{font-size:26px;font-weight:650;font-variant-numeric:tabular-nums;
       letter-spacing:-.01em;margin-top:2px}
  .val span{font-size:13px;color:var(--dim);margin-left:3px;font-weight:500}
  .stale{color:var(--dim)}

  #chart{display:flex;align-items:flex-end;gap:3px;height:96px}
  #chart i{flex:1;background:#2a2a2a;border-radius:3px 3px 0 0;min-height:2px}
  #chart i.on{background:var(--green)}
  .axis{display:flex;justify-content:space-between;font-size:11px;
        color:var(--dim);margin-top:6px}
  #note{font-size:13px;color:var(--dim)}

  .ctl{margin-top:12px;background:var(--card);border:1px solid var(--line);
       border-radius:16px;padding:18px}
  .ctl h2{margin:0 0 4px;font-size:12px;letter-spacing:.14em;
          text-transform:uppercase;font-weight:700;color:var(--dim)}
  .why{font-size:12px;color:var(--dim);margin-bottom:12px}
  #pin{width:100%;padding:12px;border-radius:10px;border:1px solid var(--line);
       background:#0b0b0b;color:var(--txt);font-size:16px;font-family:inherit;
       letter-spacing:.3em;text-align:center}
  .fld{width:100%;padding:12px;border-radius:10px;border:1px solid var(--line);
       background:#0b0b0b;color:var(--txt);font-size:16px;font-family:inherit;
       margin-bottom:8px}
  .pinfld{letter-spacing:.3em;text-align:center}
  .btns2{display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap}
  .btns2 button{
    flex:1 1 auto;padding:12px 10px;border-radius:10px;
    border:1px solid var(--line);background:#0b0b0b;color:var(--txt);
    font-size:13px;font-weight:650;cursor:pointer;font-family:inherit;
  }
  .btns2 button:active{transform:translateY(1px)}
  #cmsg{margin-top:4px;font-size:13px;min-height:18px}
  #lcdtext{margin:0 0 12px;padding:12px 14px;border-radius:8px;
           background:#0b1f16;border:1px solid #1d3a2c;color:#7cf5b0;
           font:15px/1.6 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
           letter-spacing:.06em;white-space:pre;overflow-x:auto}
  .keys{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-top:12px}
  .keys button{
    padding:12px 4px;border-radius:10px;border:1px solid var(--line);
    background:#0b0b0b;color:var(--txt);font-size:14px;font-weight:700;
    cursor:pointer;font-family:inherit;
  }
  .keys button:active{transform:translateY(1px);border-color:var(--green)}
  .keys small{display:block;color:var(--dim);font-weight:500;font-size:11px;
              margin-top:3px;letter-spacing:0}
  .good{color:var(--green)} .fail{color:var(--bad)}

  footer{margin-top:16px;padding-top:12px;border-top:1px solid var(--line);
         font-size:12px;color:var(--dim);
         display:flex;gap:8px 18px;flex-wrap:wrap}
  .dot{display:inline-block;width:7px;height:7px;border-radius:50%;
       margin-right:6px;vertical-align:middle}
  .up{background:var(--green)} .down{background:var(--bad)}
</style>
</head>
<body>

<header>
  <h1>Pump<b>Pay</b></h1>
  <div id="state">&mdash;</div>
</header>

<div class="card">
  <h2>Pump line</h2>
  <div class="grid">
    <div><div class="lbl">Volts</div><div class="val" id="v">&mdash;</div></div>
    <div><div class="lbl">Amps</div><div class="val" id="a">&mdash;</div></div>
    <div><div class="lbl">Apparent</div><div class="val" id="va">&mdash;</div></div>
  </div>
</div>

<div class="card">
  <h2>Run time</h2>
  <div class="grid">
    <div><div class="lbl">Today</div><div class="val" id="today">&mdash;</div></div>
    <div><div class="lbl">Total</div><div class="val" id="total">&mdash;</div></div>
    <div><div class="lbl">Starts</div><div class="val" id="starts">&mdash;</div></div>
  </div>
</div>

<div class="card">
  <h2>Last 24 hours</h2>
  <div id="chart"></div>
  <div class="axis"><span>24h ago</span><span>12h</span><span>now</span></div>
  <div id="note"></div>
</div>

<div class="ctl" id="panel" style="display:none">
  <h2>Panel</h2>
  <div class="why">The board's own screen and buttons, mirrored. Needs the PIN
    below.</div>
  <pre id="lcdtext"></pre>
  <div class="keys">
    <button data-b="1">B1<small>up</small></button>
    <button data-b="2">B2<small>down</small></button>
    <button data-b="3">B3<small>OK</small></button>
    <button data-b="4">B4<small>back</small></button>
  </div>
  <div style="margin-top:12px">
    <input id="pin" type="password" inputmode="numeric" autocomplete="off"
           placeholder="PIN">
  </div>
</div>

<div class="ctl" id="cfg" style="display:none">
  <h2>Settings</h2>
  <div class="why">Authorised with the PIN above. WiFi changes need a reboot.</div>

  <input class="fld" id="ssid" placeholder="WiFi network (SSID)" autocomplete="off">
  <input class="fld" id="wpass" type="password" placeholder="WiFi password"
         autocomplete="new-password">
  <div class="btns2">
    <button id="savewifi">Save WiFi</button>
    <button id="clearwifi">Turn WiFi off</button>
  </div>

  <input class="fld pinfld" id="newpin" type="password" inputmode="numeric"
         autocomplete="off" placeholder="New PIN (4-8 digits)">
  <div class="btns2">
    <button id="savepin">Change PIN</button>
  </div>

  <div id="cmsg"></div>
</div>

<footer>
  <div id="adc">&mdash;</div>
  <div id="eth">&mdash;</div>
  <div id="wifi">&mdash;</div>
  <div id="up">&mdash;</div>
</footer>

<script>
// A value that is not yet valid is shown as a dash in grey, never as 0.00.
// A zero and a "no reading" look identical on a meter face and mean opposite
// things, so they are never allowed to render the same way here.
function put(id, v, ok, dp, unit){
  const el = document.getElementById(id);
  if(!ok){ el.textContent = '—'; el.classList.add('stale'); return; }
  el.classList.remove('stale');
  el.innerHTML = Number(v).toFixed(dp) + '<span>' + unit + '</span>';
}
function dot(up){ return '<i class="dot ' + (up?'up':'down') + '"></i>'; }

function uptime(s){
  const h = Math.floor(s/3600), m = Math.floor(s%3600/60);
  return h ? h+'h '+m+'m' : m+'m '+Math.floor(s%60)+'s';
}
function hm(s){
  const h = Math.floor(s/3600), m = Math.floor(s%3600/60);
  return h ? h+'h '+m+'m' : m+'m';
}
function setHm(id, s, ok){
  const el = document.getElementById(id);
  if(!ok){ el.textContent = '—'; el.classList.add('stale'); return; }
  el.classList.remove('stale'); el.textContent = hm(s);
}

async function poll(){
  try{
    const r = await fetch('/api', {cache:'no-store'});
    const d = await r.json();

    const st = document.getElementById('state');
    st.textContent = d.state;
    st.className = d.state === 'PUMPING' ? 'on' : d.state === 'NO SIGNAL' ? 'bad' : '';

    put('v',  d.pump.v,  d.pump.vok, 1, 'V');
    put('a',  d.pump.a,  d.pump.aok, 2, 'A');
    put('va', d.pump.va, d.pump.vok && d.pump.aok, 0, 'VA');

    // "Today" needs the board's clock; without it the value would be a
    // confident zero for a day that has not been measured.
    setHm('today', d.run.today, d.t > 0);
    setHm('total', d.run.total, true);
    document.getElementById('starts').textContent = d.run.starts;

    if(d.lcd) document.getElementById('lcdtext').textContent = d.lcd.join(String.fromCharCode(10));
    if(d.ctl){
      document.getElementById('cfg').style.display = 'block';
      document.getElementById('panel').style.display = 'block';
    }

    document.getElementById('adc').innerHTML  = dot(d.adc) + 'Current sensor';
    document.getElementById('eth').innerHTML  = dot(!!d.eth)  + (d.eth  || 'Ethernet down');
    document.getElementById('wifi').innerHTML = dot(!!d.wifi) + (d.wifi || 'WiFi off');
    document.getElementById('up').textContent = 'up ' + uptime(d.up);
  }catch(e){
    // A failed poll means the board went away. Say so rather than leaving the
    // last good numbers on screen looking live.
    const st = document.getElementById('state');
    st.textContent = 'No signal'; st.className = 'bad';
  }
}

async function hist(){
  try{
    const r = await fetch('/api/hist', {cache:'no-store'});
    const h = (await r.json()).hist;
    const c = document.getElementById('chart');
    const note = document.getElementById('note');
    if(!h.t || !h.m.length){
      c.innerHTML = '';
      note.textContent = 'The board does not know the time yet. Connect with the PumpPay app, or join WiFi, to start the hourly chart.';
      return;
    }
    note.textContent = '';
    const last = h.m.slice(-24);
    c.innerHTML = last.map(m =>
      '<i class="' + (m>0?'on':'') + '" style="height:' + Math.max(2, m/60*100) + '%" title="' + m + ' min"></i>'
    ).join('');
  }catch(e){}
}

function cmsg(text, cls){
  const m = document.getElementById('cmsg');
  m.textContent = text;
  m.className = cls || '';
  setTimeout(() => { m.textContent = ''; }, 8000);
}

// Every settings action authorises with the SAME PIN field. One PIN, entered
// once, rather than several boxes asking for the same secret - which people
// work around by picking something short.
async function post(url, extra){
  const pin = document.getElementById('pin').value.trim();
  if(!pin){ cmsg('Enter the PIN in the Panel box first', 'fail'); return; }

  try{
    const r = await fetch(url, {
      method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:'pin=' + encodeURIComponent(pin) + '&' + extra
    });
    const t = (await r.text()).trim();
    cmsg(t, r.ok ? 'good' : 'fail');
  }catch(e){ cmsg('Could not reach the board', 'fail'); }
}

document.getElementById('savewifi').addEventListener('click', () => {
  const s = document.getElementById('ssid').value.trim();
  const p = document.getElementById('wpass').value;
  post('/api/wifi', 'ssid=' + encodeURIComponent(s) +
                    '&wpass=' + encodeURIComponent(p));
});

document.getElementById('clearwifi').addEventListener('click', () => {
  post('/api/wifi', 'ssid=&clear=1');
});

document.getElementById('savepin').addEventListener('click', () => {
  const n = document.getElementById('newpin').value.trim();
  post('/api/pin', 'newpin=' + encodeURIComponent(n));
  document.getElementById('newpin').value = '';
});

// Button presses keep the PIN in the field rather than clearing it. Navigation
// takes several presses in a row, and a PIN that had to be retyped for each one
// would push people toward something short enough to retype.
async function press(n){
  const pin = document.getElementById('pin').value.trim();
  if(!pin){ cmsg('Enter the PIN first', 'fail'); return; }

  try{
    const r = await fetch('/api/button', {
      method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:'pin=' + encodeURIComponent(pin) + '&n=' + n
    });
    if(!r.ok){ cmsg((await r.text()).trim(), 'fail'); return; }
    poll();                       // show the new screen immediately
  }catch(e){ cmsg('Could not reach the board', 'fail'); }
}

document.querySelectorAll('.keys button').forEach(b => {
  b.addEventListener('click', () => press(b.dataset.b));
});
poll(); hist();
setInterval(poll, 1000);
setInterval(hist, 30000);
</script>
</body>
</html>
)HTML";

// ---- Handlers -------------------------------------------------------------
void handleRoot() {
  s_server.send_P(200, "text/html", PAGE);
}

void handleApi() {
  // Report builds the object; "ctl" is the one field only this page needs, so
  // it is spliced in ahead of the closing brace rather than taught to Report.
  char body[700];
  size_t n = Report::statusJson(body, sizeof(body) - 16);
  if (n > 0 && body[n - 1] == '}') {
    snprintf(body + n - 1, 16, ",\"ctl\":%d}", ALLOW_WEB_CONTROL);
  }

  // no-store, because a cached reading is a wrong reading.
  s_server.sendHeader("Cache-Control", "no-store");
  s_server.send(200, "application/json", body);
}

void handleHist() {
  char body[1800];
  Report::histJson(body, sizeof(body));
  s_server.sendHeader("Cache-Control", "no-store");
  s_server.send(200, "application/json", body);
}

// Shared gate for every endpoint that changes something. Sends its own error
// response and returns false, so each handler can open with one line and the
// lockout accounting can never drift between them.
bool authOk() {
#if !ALLOW_WEB_CONTROL
  s_server.send(403, "text/plain", "Control is disabled in this build");
  return false;
#else
  if (lockedOut()) {
    const uint32_t left = (LOCKOUT_MS - (millis() - s_lockedAt)) / 1000;
    char msg[80];
    snprintf(msg, sizeof(msg), "Too many wrong PINs - locked for %lu s",
             static_cast<unsigned long>(left + 1));
    s_server.send(429, "text/plain", msg);
    return false;
  }

  if (s_server.arg("pin") != Settings::controlPin()) {
    s_failures++;
    if (s_failures >= MAX_FAILURES) s_lockedAt = millis();

    Serial.print("[web] wrong PIN (");
    Serial.print(s_failures);
    Serial.print(" of ");
    Serial.print(MAX_FAILURES);
    Serial.print(") from ");
    Serial.println(s_server.client().remoteIP());

    s_server.send(401, "text/plain", "Wrong PIN");
    return false;
  }

  s_failures = 0;
  return true;
#endif
}

void handleButton() {
  if (!authOk()) return;

  const int n = s_server.arg("n").toInt();

  Buttons::Button btn;
  switch (n) {
    case 1: btn = Buttons::Button::B1; break;
    case 2: btn = Buttons::Button::B2; break;
    case 3: btn = Buttons::Button::B3; break;
    case 4: btn = Buttons::Button::B4; break;
    default:
      s_server.send(400, "text/plain", "Button must be 1 to 4");
      return;
  }

  // Injected as a Press, the same event the physical ladder produces, so the
  // menu cannot tell the difference and there is only ever one code path
  // deciding what a button means.
  Display::onButton(Buttons::Event::Press, btn);

  // Not logged. These are navigation, not changes of state - anything that
  // actually alters the board is logged by its own handler.
  s_server.send(200, "text/plain", "");
}

void handleWifi() {
  if (!authOk()) return;

  const String ssid = s_server.arg("ssid");
  const String pass = s_server.arg("wpass");

  // An empty SSID means "turn the radio off", which is a different intention
  // from "join this network" and is spelled differently so a cleared field
  // cannot do it by accident.
  if (ssid.length() == 0) {
    if (s_server.arg("clear") == "1") {
      Settings::clearWifi();
      s_server.send(200, "text/plain", "WiFi cleared - reboot to apply");
    } else {
      s_server.send(400, "text/plain", "Enter an SSID");
    }
    return;
  }

  if (!Settings::setWifi(ssid.c_str(), pass.c_str())) {
    s_server.send(400, "text/plain", "SSID or password too long");
    return;
  }

  Serial.print("[web] wifi credentials set from ");
  Serial.println(s_server.client().remoteIP());

  s_server.send(200, "text/plain", "WiFi saved - reboot to apply");
}

void handlePin() {
  if (!authOk()) return;

  const String next = s_server.arg("newpin");

  if (!Settings::setControlPin(next.c_str())) {
    s_server.send(400, "text/plain", "PIN must be 4 to 8 digits");
    return;
  }

  Serial.print("[web] control PIN changed from ");
  Serial.println(s_server.client().remoteIP());

  s_server.send(200, "text/plain", "PIN changed - use the new one from now on");
}

}  // namespace

void begin() {
  // Started unconditionally. Binding to port 80 does not require an interface
  // to be up yet - the server simply has nobody to talk to until one is, and
  // starts serving the moment either Ethernet or WiFi gets an address. That
  // avoids having to watch for "network came up" and start late.
  s_server.on("/", HTTP_GET, handleRoot);
  s_server.on("/api", HTTP_GET, handleApi);
  s_server.on("/api/hist", HTTP_GET, handleHist);
  s_server.on("/api/wifi", HTTP_POST, handleWifi);
  s_server.on("/api/pin",  HTTP_POST, handlePin);
  s_server.on("/api/button", HTTP_POST, handleButton);
  s_server.onNotFound([]() { s_server.send(404, "text/plain", "not found"); });
  s_server.begin();
  s_running = true;

  Serial.println("[web] server on port 80 - open the board's IP in a browser");

#if ALLOW_WEB_CONTROL
  // Say it loudly. A default PIN that nobody changed is the same as no PIN,
  // and the whole argument for putting settings on this page rests on the PIN
  // being real.
  if (Settings::pinIsDefault()) {
    Serial.println("[web] *** CONTROL PIN IS STILL THE DEFAULT 0000 ***");
    Serial.println("[web]     change it from the LCD (SET PIN screen), over");
    Serial.println("[web]     Bluetooth, or on this page, before the board");
    Serial.println("[web]     goes anywhere it can be reached");
  }
#else
  Serial.println("[web] control disabled - page is view only");
#endif
}

void tick() {
  if (!s_running) return;
  s_server.handleClient();
}

bool running() { return s_running; }

}  // namespace WebUI
