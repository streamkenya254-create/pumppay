# PumpPay — Solar Pump Controller (PAYG / PAYU)

Landing page + client app (Android) + admin dashboard + Firebase backend with M-Pesa.
Colours: green `rgb(89,242,35)` / `#59F223`, black, white.

```
public/            → everything that gets hosted
  index.html       → landing page (plans, savings calculator, shop, lead form)
  app/             → CLIENT portal — installable PWA, also loaded by the Android app
  admin/           → ADMIN dashboard
  assets/js/firebase-config.js  → ⚠ paste your Firebase config + brand details here
functions/         → Cloud Functions: M-Pesa STK + Paybill, credit logic, controller API
android/           → Android Studio project (Kotlin WebView app for clients)
docs/              → PAYG / PAYU business model (Word)
firestore.rules, storage.rules, firestore.indexes.json, firebase.json
```

## 1. Connect your Firebase project
1. Firebase console → Project settings → Your apps → **Web app** → copy the config.
2. Paste it into `public/assets/js/firebase-config.js` (also edit `BRAND`: name, phone, WhatsApp, email).
3. Put your project id in `.firebaserc`.
4. In the console enable: **Authentication → Email/Password**, **Firestore**, **Storage**.
5. Upgrade to the **Blaze** plan (needed for Cloud Functions to call Safaricom).

## 2. Deploy
```bash
npm i -g firebase-tools
firebase login
cd functions && npm install && cp .env.example .env && cd ..
firebase functions:secrets:set MPESA_CONSUMER_KEY
firebase functions:secrets:set MPESA_CONSUMER_SECRET
firebase functions:secrets:set MPESA_PASSKEY
firebase functions:secrets:set MPESA_CALLBACK_TOKEN     # any long random string
firebase deploy                                          # hosting + functions + rules + indexes
```
Sandbox values work first (shortcode 174379 + Daraja sandbox passkey). For live money set
`MPESA_ENV=production` and your Paybill in `functions/.env`, then redeploy.
Till (Buy Goods): `MPESA_TYPE=CustomerBuyGoodsOnline`, `MPESA_SHORTCODE`=store number, `MPESA_TILL`=till number.

**Already hosting on HostPinnacle?** You can upload `public/` there instead of Firebase Hosting —
the pages talk to Firebase directly. Functions/rules still deploy with `firebase deploy --only functions,firestore,storage`.

## 3. First admin
1. Authentication → Add user (your email + password).
2. Firestore → collection `admins` → document id = that user's **UID** → field `role: "admin"`.
3. Open `/admin/` → Settings → **Seed default plans & products**.

## 4. Daily flow
| Step | Where |
|---|---|
| Farmer fills the website form | Admin → Leads |
| Create customer (phone + 6-digit PIN) | Admin → Customers → + Add |
| Register controller, assign customer & plan | Admin → Controllers → + Add (copy the secret key into firmware) |
| Record deposit | Admin → Controllers → Credit → tick "deposit" |
| Customer pays | App → Pay (STK prompt) **or** Paybill, account no. = controller serial |
| Pump unlocks | Controller polls `deviceSync` |

## 5. Paybill (C2B) — optional
Register these with Daraja's C2B *Register URL* API once (production):
- Validation: `https://europe-west1-<project>.cloudfunctions.net/c2bValidation?t=<CALLBACK_TOKEN>`
- Confirmation: `https://europe-west1-<project>.cloudfunctions.net/c2bConfirmation?t=<CALLBACK_TOKEN>`

## 6. Controller firmware (ESP32/STM32 + GSM)
Poll every 1–5 min:
```
GET https://europe-west1-<project>.cloudfunctions.net/deviceSync?serial=PP-0001&key=<secret>&hours=<lifetime run-hours>&litresToday=<n>&solarW=<n>
→ {"on":true,"mode":"payg","until":1761300000,"hours":0,"owned":false,"now":1761000000}
```
- PAYG: run while `now < until` — store `until` in flash so the pump keeps working if GSM drops.
- PAYU: send your lifetime run-hour counter; server deducts usage and returns the remaining `hours`. Stop at 0 locally too.
- `owned:true` → unlock permanently.
- **Usage tab:** send `litres=<lifetime litres counter>` and `hours=<lifetime run-hours>` on every poll. The server logs the
  difference into hourly `usage/` docs, which the app's **Usage** tab charts (litres, pump hours, busiest time of day).
  Deploy rules + indexes: `firebase deploy --only firestore,functions`.

## 7. Android app
**Option A — no build needed:** open `https://<your-site>/app/` in Chrome on the phone → "Install app".
**Option B — Play Store APK:** open `android/` in Android Studio, set `portal_url` in
`app/src/main/res/values/strings.xml`, then *Build → Generate Signed Bundle / APK*.
The app wraps the portal, so website updates reach the app instantly.

## Data model (Firestore)
`customers/{uid}` · `devices/{serial}` (planType, dailyRate/hourlyRate, activeUntil, hoursBalance, totalPaid, totalPrice, owned, telemetry) ·
`deviceKeys/{serial}` · `payments` · `plans` · `products` (photos in Storage `products/`) · `orders` · `leads` · `tickets` · `admins/{uid}`
