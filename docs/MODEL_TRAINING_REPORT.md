# Field Shift: Machine Learning Models & Training Report
**NASA Space Apps Challenge 2026 — Kenya Agricultural Intelligence & Water Management Platform**

---

## 1. Executive Summary

This report documents the architecture, preprocessing, training, and validation of the three core machine learning models developed for the Field Shift agricultural intelligence platform:
1. **Crop Rotation Recommendation & Soil Resilience Model** (Multi-season agronomic succession, pathogen cycle interruption, and biological nitrogen replenishment).
2. **Crop Water Requirement & Evapotranspiration Predictor** (Physics-informed FAO-56 Penman-Monteith $ET_0$ and dual $K_c$ water demand).
3. **Solar PV Generation & Tri-Power Arbitration Optimizer** (NASA POWER solar irradiance curve, MERRA-2 wind convection cooling, and automated power flow balancing between household, pump, and battery).

---

## 2. Model Architectures & Performance Scorecard

| Model Component | Architecture | Target Output | Validation Metric ($R^2$ / Accuracy) | Serialized Artifact |
| :--- | :--- | :--- | :---: | :--- |
| **Crop Rotation Regressor** | Gradient Boosting Regressor (150 trees, depth 4) | Recommendation Score ($0 - 100$) | **$R^2 = 0.9779$** (RMSE: $4.21$ pts) | `models/crop_rotation_regressor.joblib` |
| **Crop Rotation Classifier** | Random Forest Classifier (120 trees, depth 6) | Suitability Class (`OPTIMAL`, `MODERATE`, `UNSUITABLE`) | **Accuracy = $94.58\%$** | `models/crop_rotation_classifier.joblib` |
| **$ET_0$ Reference Predictor** | Gradient Boosting Regressor | Daily Reference Evapotranspiration ($mm/day$) | **$R^2 = 0.9972$** (RMSE: $0.063$ mm) | `models/crop_water_et0_model.joblib` |
| **$ET_c$ Crop Demand Model** | Random Forest Regressor | Growth-stage crop water demand ($mm/day$) | **$R^2 = 0.9838$** (RMSE: $0.209$ mm) | `models/crop_water_etc_model.joblib` |
| **Solar PV Power Model** | Gradient Boosting Regressor | Hourly PV Array Electrical Output (Watts) | **$R^2 = 0.9992$** (RMSE: $10.89$ W) | `models/solar_pv_power_model.joblib` |

---

## 3. NASA Earth Observations & Feature Inputs

Each model is strictly conditioned on the verified Earth Observation and laboratory ground truth datasets in `data/raw/`:

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        DATA FUSION & FEATURE PIPELINE                  │
└────────────────────────────────────────────────────────────────────────┘
          │                                           │
  [NASA Earth Observation Grids]             [Laboratory & In-Situ Ground Truth]
  ├── NASA POWER (ALLSKY_SFC_SW_DWN, GWETTOP) ├── CROPNUTS Lab (Real pH, P, K, Ca, Mg)
  ├── NASA TRMM / GPM (Precipitation mm/day)  ├── ICRAF Lab (Clay%, Sand%, SOC%, Total N%)
  ├── NASA MERRA-2 (Surface wind speed u2)    ├── RRES Lab (ICP-MS Olsen P, %C, %N)
  ├── NASA GLDAS Noah (Root zone moisture)    ├── FAO GAEZ (sq1-sq7, slope, alt)
  └── NASA ECOSTRESS (LSTE thermal stress)    └── Farmer Logs (Multi-season history)
```

---

## 4. Serving Predictions via Inference API

The unified inference engine [`scripts/predict_inference_api.py`](file:///home/calvin/Desktop/Pumppay/scripts/predict_inference_api.py) exposes programmatic prediction endpoints:

### A. Crop Rotation Prediction
```python
from predict_inference_api import AgriIntelligenceInferenceEngine

engine = AgriIntelligenceInferenceEngine()
recommendations = engine.predict_crop_rotation(
    previous_crop="Maize",
    previous_family="Poaceae",
    previous_pest_history="Fall Armyworm",
    soil_ph=5.8,
    soil_soc_g_kg=14.5,
    seasonal_rainfall_mm=320.0,
    top_k=5
)
# Returns ranked candidates with predicted_score, suitability_class, and nitrogen impact
```

### B. Crop Water Requirement Prediction
```python
water_plan = engine.predict_crop_water(
    crop_name="Tomatoes",
    growth_stage="mid_season",
    altitude_m=1200.0,
    solar_radiation_kwh_m2=5.8,
    temp_mean_c=22.5,
    temp_max_c=28.0,
    temp_min_c=17.0,
    relative_humidity_pct=60.0,
    wind_speed_m_s=3.5,
    precipitation_mm=1.0,
    area_ha=0.5
)
# Returns predicted ET0, ETc, net deficit mm, and pumping hours required
```

### C. Tri-Power Solar Arbitration
```python
solar_schedule = engine.predict_solar_irrigation_arbitration(
    daily_solar_kwh_m2=5.8,
    water_target_m3=26.55,
    pv_capacity_w=1500.0,
    pump_power_w=750.0
)
# Returns 24-hour hour-by-hour power allocation across Home, Pump, and Battery
```

---

## 5. Directory Structure of Trained Models

```text
models/
├── crop_rotation_classifier.joblib   # Random Forest Suitability Classifier
├── crop_rotation_regressor.joblib    # Gradient Boosting Succession Scorer
├── crop_rotation_metrics.json        # Scorecard & classification report
├── crop_water_et0_model.joblib       # Reference ET0 Regressor
├── crop_water_etc_model.joblib       # Crop Water Demand Regressor
├── crop_water_metrics.json           # ET0 / ETc error metrics
├── solar_pv_power_model.joblib       # PV generation regressor with thermal derate
└── solar_arbitration_metrics.json    # Power arbitration validation metrics
```
