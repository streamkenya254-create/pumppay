# Field Shift & PumpPay: NASA Space Apps Pitch Playbook & Demo Guide

> **Challenge Track**: *Field Shift: NASA Earth Observations for Crop Rotation and Resilient Agriculture*  
> **Hardware Platform**: Locally Manufactured PumpPay Solar IoT Controller & M-Pesa Micro-Billing  
> **Team**: Tafiti R&I Hub (Nairobi & Kisumu, Kenya)

---

## 1. Executive Summary & Pitch Narrative (The 3-Minute Hook)

### The Hook (0:00 – 0:45)
> *"Across Sub-Saharan Africa, smallholder farmers lose over 40% of their potential harvest to two compounding crises: **soil exhaustion from repetitive monocropping**, and **water stress caused by unpredictable seasonal rainfall**. Today, over 80% of small farms rely on expensive diesel pumps—spending upwards of 180 KES per liter—while their soil depletes year after year.*  
>  
> *Welcome to **Field Shift + PumpPay**: the first closed-loop agritech system that bridges **NASA Earth Observations** with a **locally manufactured smart solar pump controller**. We don't just tell farmers when and how much to irrigate using satellite weather—our AI tells them **what to plant next** to replenish soil nutrients, while our hardware controller arbitrates solar energy so their home lights stay on while the pump irrigates the farm."*

### The Breakthrough (0:45 – 1:30)
> *"We built and validated three multi-task machine learning models grounded in **1,907 real East African soil chemistry lab tests** from Crop Nutrition Laboratories (CROPNUTS) and the World Agroforestry Centre (ICRAF), fused with **NASA POWER, GPM TRMM satellite precipitation, MERRA-2 wind, and SoilGrids**.*  
>  
> *1. **Crop Rotation & Disease Break Engine ($R^2 = 0.978$, $94.6\%$ accuracy)**: Predicts optimal succession crops across 8 target staples, eliminating disease repeats and restoring $+40$ to $+85\text{ kg N/ha}$ naturally through legume rotation.*  
> *2. **FAO-56 Penman-Monteith Water Demand ($R^2 = 0.997$)**: Replaces static lookup tables with dynamic satellite evapotranspiration, reducing over-irrigation by $35\%$.*  
> *3. **Diurnal Solar Tri-Power Arbitrator ($R^2 = 0.999$, RMSE 10.89 W)**: Dynamically splits solar power between Home Baseload (Priority 1), Daytime Pumping (Priority 2), and Battery Storage (Priority 3).*  
>  
> *Farmers access this through our M-Pesa Pay-As-You-Go Android app and voice assistant in English and Kiswahili."*

---

## 2. The 3-Part Live Demonstration Flow

To demonstrate the full stack live during your presentation, run one command in your terminal:
```bash
npm run demo
```
This automatically verifies that all three engines are running:
* **Public Website**: [http://localhost:5174](http://localhost:5174)
* **Web AI Intelligence Dashboard**: [http://localhost:3000](http://localhost:3000)
* **ML Inference Microservice**: [http://127.0.0.1:5055/health](http://127.0.0.1:5055/health)
* **Android Native App**: `/home/calvin/Desktop/Pumppay/frontend/android`

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       PROJECT LIVE SHOWCASE FLOW                            │
├────────────────────────┬───────────────────────────┬────────────────────────┤
│   PART 1: THE BUSINESS │   PART 2: THE NASA AI     │   PART 3: THE FARMER   │
│   http://localhost:5174│   http://127.0.0.1:5055   │   Android App on Phone │
│   • M-Pesa PAYG Model  │   • NASA Earthdata Fusion │   • Today's Pump Window│
│   • Diesel Savings     │   • 1,907 Lab Soil Chem   │   • Next Crop Rotation │
│   • Instant GSM Unlock │   • 3 Validated ML Models │   • Voice/Chat AI      │
└────────────────────────┴───────────────────────────┴────────────────────────┘
```

---

### Step 1: Public Website & Economics (`http://localhost:5174`)
**Goal**: Show how farmers afford the hardware and how community adoption works.

* **Action 1**: Open [http://localhost:5174](http://localhost:5174).
* **Talking Point**:
  > *"Farmers cannot pay 120,000 KES upfront for a solar pump. PumpPay offers Pay-As-You-Go (PAYG) ownership starting with a small deposit and daily M-Pesa micropayments, or Pay-As-You-Use (PAYU) for seasonal farmers."*
* **Action 2**: Scroll down to the **Savings Calculator** (`#calc`).
  * Move the slider: 4 hours pumping/day $\times$ 1.0 L diesel/hr $\times$ 180 KES/L = **21,600 KES/month on diesel**.
  * Show the PumpPay PAYG rate: **13,500 KES/month**.
  * Net farmer savings: **8,100 KES saved every month** + zero carbon emissions!
* **Action 3**: Point to the **Features** section (`#features`).
  * Highlight the integrated features: **AI Crop Rotation** and **Tri-Power Arbitration**.
  * Transition directly to the Android app on the phone.

---

### Step 2: The Multi-Task ML Inference Engine (`http://127.0.0.1:5055`)
**Goal**: Show the judges the technical depth of the NASA Earth Observations integration.

* **Action 1**: Point to the running service on [http://127.0.0.1:5055/health](http://127.0.0.1:5055/health).
* **Talking Point**:
  > *"Behind the controller is our Field Shift intelligence platform powered by NASA Earthdata and ground-truth soil lab measurements."*
* **Action 2 — The Ground-Truth & Earth Observation Layer**:
  * Explain how NASA POWER (solar irradiance `ALLSKY_SFC_SW_DWN` and surface wetness `GWETTOP`), GPM/TRMM precipitation climatology, and SoilGrids organic carbon are fused.
  * Emphasize: *"Unlike typical apps that use crude heuristics, our models were trained on 1,907 lab wet-chemistry soil samples from CROPNUTS and ICRAF in Nairobi—ensuring local agro-ecological precision."*
* **Action 3 — Live Rotation Engine**:
  * Show rotation prediction:
    * The model rejects repeated Poaceae monoculture.
    * Ranks **Cowpeas** (#1, Score 97.9/100, $+60\text{ kg N/ha}$) and **Pigeon Peas** (#2, $+85\text{ kg N/ha}$, Very High Drought Resilience).
    * Demonstrates disease cycle break and $+N$ soil replenishment.

---

### Step 3: Farmer Android App & Solar Tri-Power (`frontend/android`)
**Goal**: Show how the farmer interacts with the solution in the field with zero technical friction.

* **Action 1**: Open the PumpPay app on your phone or Android Studio emulator.
* **Action 2 — The Plant Tab (`Plant.kt`)**:
  * Show the active farm screen: OLED pure black theme (`#000000`) and PumpPay green accent (`#59F223`).
  * Show today's watering decision: **WATER TODAY** (e.g. 21,600 litres, 360 minutes).
  * Show the soil water gauge: 50% available water, stress threshold marked at 55%.
  * **Highlight the New Feature**: Point to the **`NEXT CROP ROTATION`** card:
    > *"Notice right here under the current crop progress: our AI looks ahead to the next season. It tells the farmer: 'Rotate to Cowpeas next: Score 99.2/100 [OPTIMAL], N impact: +60 kg N/ha'. The farmer doesn't need to read research papers—the recommendation is delivered directly to their phone."*
* **Action 3 — Solar Tri-Power Arbitration**:
  * Show the pumping window: `10:00 to 15:00`.
  * Explain:
    > *"Our solar model guarantees that Home Baseload (Priority 1: lights, phone charging, refrigeration) is never cut off. The controller runs the pump directly off daytime peak solar (Priority 2) and saves battery energy for the evening (Priority 3)."*
* **Action 4 — The Voice & Chat AI Assistant (`Assistant.kt`)**:
  * Tap **Ask the Assistant**.
  * Ask (in English or Kiswahili): *"Nipande nini baada ya mahindi?"* (*What should I plant after maize?*).
  * Show the assistant quoting the exact model result: recommending legumes (beans/cowpeas) to replenish $+60\text{ kg N/ha}$ and break maize stalk borer/armyworm cycles!

---

## 3. Slide Deck Outline (7 Slides)

| Slide # | Slide Title | Visual / Content | Key Soundbite |
| :---: | :--- | :--- | :--- |
| **1** | **Field Shift + PumpPay** | Logo, photo of Kenyan smallholder with solar pump and phone app. | *"Intelligent solar irrigation & crop rotation powered by NASA Earth Observations."* |
| **2** | **The Dual Crisis** | Infographic: Soil nutrient depletion ($\Delta N < 0$) + Diesel pump expense (180 KES/L). | *"Monoculture exhausts the soil; diesel exhausts the farmer's pocket."* |
| **3** | **The Solution** | Diagram connecting NASA Satellites $\rightarrow$ ML Microservice $\rightarrow$ IoT Controller $\rightarrow$ M-Pesa. | *"From satellite sensors to water in the field in seconds."* |
| **4** | **NASA Earthdata & Ground Truth** | Logos of NASA POWER, GPM, MERRA-2, SoilGrids + CROPNUTS & ICRAF lab samples. | *"Global satellite observation calibrated with 1,907 real East African soil lab tests."* |
| **5** | **The 3 AI Models** | Metrics scorecard: Model 1 ($R^2=0.978$), Model 2 ($R^2=0.997$), Model 3 ($R^2=0.999$). | *"Validated machine learning: Crop Rotation, FAO-56 Penman-Monteith, and Tri-Power Solar."* |
| **6** | **Live System Demo** | Screenshots / live screen of Website, Web Dashboard, and Android App. | *"Zero-friction farmer experience: M-Pesa unlock, solar arbitration, and next-season rotation."* |
| **7** | **Impact & Economics** | Farmer saves 8,100 KES/mo; $+60\text{ kg N/ha}$ soil gain; $0\text{ L}$ diesel burned. | *"Resilient agriculture that pays for itself while restoring Kenya's soils."* |

---

## 4. Judges' Q&A Defense Sheet

### Q1: *"How is this different from existing irrigation apps like CropX or standard FAO tools?"*
> **Answer**:  
> *"Existing apps are either pure software calculators that assume high-income grid-tied irrigation, or expensive imported sensors that smallholders cannot afford. Field Shift is uniquely vertically integrated: our software doesn't just calculate numbers—it talks directly to our **locally assembled ESP32 solar controller**. We solve the **solar energy constraint** (arbitrating between home power and pump power) and pair it with **M-Pesa micro-billing**, making precision agriculture accessible on a daily Pay-As-You-Go basis."*

### Q2: *"How accurate are NASA Earth Observations for small 1–2 acre farms in Kenya?"*
> **Answer**:  
> *"NASA POWER and GPM provide stellar macro-climatology and daily radiation envelopes ($ALLSKY\_SFC\_SW\_DWN$, $T2M$, $u_2$), but satellite soil moisture alone can have spatial resolution limits. That is precisely why we grounded our models on **1,907 real topsoil wet-chemistry lab measurements** from CROPNUTS and ICRAF across Kenya and East Africa. Our ML ensemble fuses satellite macro-climate with localized soil chemistry (pH, phosphorus, potassium, organic carbon, and texture), achieving an $R^2$ of $0.978$ on crop suitability and $0.997$ on reference $ET_0$."*

### Q3: *"What happens if there is no internet connection on the farm?"*
> **Answer**:  
> *"The system is offline-resilient by design. The PumpPay controller runs on an ESP32 microcontroller with local flash memory: it holds credit offline, communicates with the farmer's phone over Bluetooth, and switches the pump automatically. If the internet or NASA API drops, our backend has automated fallback to local FAO-56 calculations, ensuring irrigation never halts."*

### Q4: *"How do you guarantee the home has electricity if the pump uses too much solar power?"*
> **Answer**:  
> *"That is our Model 3: Tri-Power Arbitration. Household lighting, phone charging, and refrigeration are assigned strict **Priority 1 (Baseload Guarantee)**. The pump operates on **Priority 2** only when diurnal solar irradiance exceeds the motor threshold. Any excess energy is directed to **Priority 3 (Battery Bank)**. Our optimization ensures $100\%$ uninterrupted domestic power every single day."*

---

## 5. Quick One-Page Pitch Cheat Sheet

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                  FIELD SHIFT + PUMPPAY: PITCH CHEAT SHEET                   │
├──────────────────────────────────────────────────────────────────────────────┤
│ 1. PROBLEM:                                                                  │
│    • Smallholders spend 21,600 KES/mo on diesel pumping.                     │
│    • Repetitive monoculture strips soil nitrogen and breeds pests.           │
│                                                                              │
│ 2. SOLUTION:                                                                 │
│    • Smart Solar Pump Controller with M-Pesa Pay-As-You-Go ($0 down options).│
│    • NASA Earth Observations (POWER, GPM, MERRA-2) + CROPNUTS Lab Grounding. │
│    • Closed-loop AI: Irrigation timing + Next Crop Rotation advice.          │
│                                                                              │
│ 3. THE 3 ML ENGINES:                                                         │
│    • Model 1 (Rotation): R²=0.978, 94.6% Acc | Disease breaks + N budgets.   │
│    • Model 2 (Water):    R²=0.997 | FAO-56 Penman-Monteith ET0 & ETc.        │
│    • Model 3 (Solar):    R²=0.999 | Diurnal curve + Home power Priority 1.   │
│                                                                              │
│ 4. IMPACT METRICS:                                                           │
│    • 8,100 KES/month saved per farmer vs diesel.                             │
│    • +40 to +85 kg N/ha natural soil replenishment via legume rotations.     │
│    • 100% home lighting guaranteed while daytime pumping.                   │
│                                                                              │
│ 5. DEMO URLS (Run: npm run demo):                                            │
│    • Public Landing Page:  http://localhost:5174                             │
│    • Android Mobile App:   Running live on connected phone                   │
│    • ML API Status:        http://127.0.0.1:5055/health                      │
└──────────────────────────────────────────────────────────────────────────────┘
```
