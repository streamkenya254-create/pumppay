# PumpPay

A solar water-pump controller with a phone app, prepaid M-Pesa billing and an AI assistant that works out when and how long to pump.

| Folder | What it is |
|---|---|
| `firmware/PumpPay/` | ESP32 controller firmware (Arduino core 3.x, `Huge APP` partition). Measures the power going to the pump, runs a prepaid-token relay, talks to the app over Bluetooth, uploads readings over WiFi. |
| `android/` | The Android app (Kotlin, Jetpack Compose, package `ke.tafiti.pumppay`). Live readings, billing, water/tank, plant plan, assistant with voice. |
| `backend/` | Firebase Cloud Functions (Node 22): ingest, M-Pesa top-ups, irrigation plan (NASA POWER + FAO-56), assistant. |
| `website/` | The public landing page. |

## Secrets are not in this repository

Everything below is set outside git:

- **Cloud Function secrets** (`firebase functions:secrets:set <NAME>`): `PUMPPAY_INGEST_KEY`, `PUMPPAY_ADMIN_KEY`, `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, `MPESA_PASSKEY`, `PUMPPAY_CALLBACK_TOKEN`, `GROQ_KEYS`.
- **Till and environment**: copy `backend/functions/.env.example` to `.env` and fill it in.
- **Android**: `local.properties` (SDK path) is per machine and ignored.

The Firebase Web API key and Google OAuth Web client ID in `Auth.kt` identify the project and are public by design; access is governed by Firebase Authentication and the server-side checks.

## Build

**App**: `gradle assembleDebug` in `android/`. Google sign-in needs your build's SHA-1 added to the Firebase Android app.

**Firmware**: `arduino-cli compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app firmware/PumpPay`. Billing enforcement is off until switched on from the app.

**Backend**: `cd backend && firebase deploy --only functions:pumppay`.

## How the pieces fit

The controller reports to the server; the server holds each controller's paid-for time and the controller counts it down with no network, failing safe (pump powered) if anything is unknown. The app talks to the controller over Bluetooth and to the server over HTTPS. The assistant runs on the server so no model key ever ships in the app; its irrigation answers come from a crop-water model, not from the language model.
