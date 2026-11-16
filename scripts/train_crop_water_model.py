#!/usr/bin/env python3
"""
Field Shift: Crop Water Requirement & Evapotranspiration ML Model Trainer
-------------------------------------------------------------------------
Trains a machine learning regression model to predict:
  1. Reference Evapotranspiration (ET0 mm/day)
  2. Crop Water Demand (ETc mm/day)
  3. Net Irrigation Deficit (Inet mm/day)

Inputs:
  - Solar irradiance (kWh/m²/day) [NASA POWER]
  - Temperature extremes (Tmean, Tmax, Tmin °C) [NASA POWER]
  - Relative humidity (%) [NASA POWER]
  - Surface wind speed u2 (m/s) [NASA MERRA-2]
  - Precipitation rate (mm/day) [NASA TRMM / GPM]
  - Altitude (meters) [FAO GAEZ]
  - Crop type & growth stage
"""

import os
import sys
import json
import joblib
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline
from sklearn.ensemble import GradientBoostingRegressor, RandomForestRegressor
from sklearn.metrics import r2_score, mean_squared_error, mean_absolute_error

from crop_water_engine import calculate_et0_fao56, calculate_irrigation_requirement, CROP_AGRONOMIC_PROFILES

MODELS_DIR = "models"
OUTPUT_DATA = "data/training/crop_water_training_matrix.csv"

def generate_water_training_dataset(num_samples: int = 1200) -> pd.DataFrame:
    """Simulates realistic microclimatic variations across Kenya agro-climatic zones."""
    np.random.seed(101)

    crops = list(CROP_AGRONOMIC_PROFILES.keys())
    stages = ["initial", "development", "mid_season", "late_season"]
    stage_to_day = {"initial": 15, "development": 35, "mid_season": 60, "late_season": 100}

    # Kenyan agro-ecological altitude & climate clusters
    clusters = [
        {"name": "Highland", "alt_range": (1800, 2400), "temp_range": (12, 24), "rh_range": (60, 85), "wind_range": (1.5, 3.8), "solar_range": (4.5, 6.2)},
        {"name": "Semi-Arid", "alt_range": (900, 1400), "temp_range": (18, 32), "rh_range": (40, 65), "wind_range": (2.5, 6.0), "solar_range": (5.2, 6.8)},
        {"name": "Coastal", "alt_range": (10, 200), "temp_range": (24, 34), "rh_range": (68, 88), "wind_range": (3.5, 7.5), "solar_range": (5.0, 6.5)},
        {"name": "Lake_Basin", "alt_range": (1100, 1500), "temp_range": (18, 30), "rh_range": (55, 80), "wind_range": (1.8, 4.2), "solar_range": (4.8, 6.4)}
    ]

    records = []

    for i in range(num_samples):
        cluster = np.random.choice(clusters)
        alt = np.random.uniform(*cluster["alt_range"])
        t_min = np.random.uniform(cluster["temp_range"][0], cluster["temp_range"][0] + 5)
        t_max = np.random.uniform(cluster["temp_range"][1] - 5, cluster["temp_range"][1])
        t_mean = (t_max + t_min) / 2.0
        rh = np.random.uniform(*cluster["rh_range"])
        wind = np.random.uniform(*cluster["wind_range"])
        solar_kwh = np.random.uniform(*cluster["solar_range"])
        solar_mj = solar_kwh * 3.6

        # Precipitation distribution (often 0 mm, occasionally rainfall events)
        is_rainy = np.random.rand() < 0.28
        precip_mm = round(np.random.exponential(scale=6.5), 1) if is_rainy else 0.0

        # Effective precipitation (USDA SCS)
        p_eff = min(precip_mm * 0.80, precip_mm)

        crop = np.random.choice(crops)
        stage = np.random.choice(stages)
        day_in_season = stage_to_day[stage]

        # Ground truth physics calculation via FAO-56
        et0 = calculate_et0_fao56(
            t_mean_c=t_mean,
            t_max_c=t_max,
            t_min_c=t_min,
            rh_mean_pct=rh,
            solar_radiation_mj_m2_day=solar_mj,
            wind_speed_2m_m_s=wind,
            altitude_m=alt
        )

        irrig_res = calculate_irrigation_requirement(
            crop_name=crop,
            day_in_season=day_in_season,
            et0_mm_day=et0,
            effective_rainfall_mm_day=p_eff,
            area_ha=1.0,
            pump_flow_rate_m3_h=2.5
        )

        records.append({
            "sample_id": f"WATER-{i+1:05d}",
            "climate_cluster": cluster["name"],
            "altitude_m": round(alt, 1),
            "solar_radiation_kwh_m2": round(solar_kwh, 2),
            "temp_mean_c": round(t_mean, 1),
            "temp_max_c": round(t_max, 1),
            "temp_min_c": round(t_min, 1),
            "relative_humidity_pct": round(rh, 1),
            "wind_speed_m_s": round(wind, 2),
            "precipitation_mm": precip_mm,
            "crop_name": crop,
            "growth_stage": stage,
            "day_in_season": day_in_season,
            "crop_kc": irrig_res["crop_coefficient_kc"],
            "target_et0_mm": irrig_res["reference_et0_mm"],
            "target_etc_mm": irrig_res["crop_water_demand_etc_mm"],
            "target_net_irrigation_mm": irrig_res["net_irrigation_deficit_mm"],
            "target_pump_runtime_hours": irrig_res["pump_runtime_hours"]
        })

    df = pd.DataFrame(records)
    df.to_csv(OUTPUT_DATA, index=False)
    print(f"[WATER MODEL] Created calibrated training dataset with {len(df)} samples: {OUTPUT_DATA}")
    return df

def train_crop_water_models():
    os.makedirs(MODELS_DIR, exist_ok=True)
    print("========================================================================")
    print("  TRAINING CROP WATER REQUIREMENT & ET0 ML REGRESSION MODEL")
    print("========================================================================")

    df = generate_water_training_dataset(num_samples=1500)

    num_features = [
        "altitude_m",
        "solar_radiation_kwh_m2",
        "temp_mean_c",
        "temp_max_c",
        "temp_min_c",
        "relative_humidity_pct",
        "wind_speed_m_s",
        "precipitation_mm",
        "crop_kc"
    ]
    cat_features = [
        "climate_cluster",
        "crop_name",
        "growth_stage"
    ]

    X = df[num_features + cat_features]
    y_et0 = df["target_et0_mm"]
    y_etc = df["target_etc_mm"]

    X_train, X_test, y_et0_train, y_et0_test, y_etc_train, y_etc_test = train_test_split(
        X, y_et0, y_etc, test_size=0.20, random_state=42
    )

    preprocessor = ColumnTransformer(
        transformers=[
            ("num", StandardScaler(), num_features),
            ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), cat_features)
        ]
    )

    # 1. ET0 Regressor
    et0_model = Pipeline([
        ("preprocessor", preprocessor),
        ("regressor", GradientBoostingRegressor(
            n_estimators=150,
            learning_rate=0.08,
            max_depth=4,
            random_state=42
        ))
    ])

    print("[WATER MODEL] Training ET0 Reference Evapotranspiration Predictor...")
    et0_model.fit(X_train, y_et0_train)

    y_et0_pred = et0_model.predict(X_test)
    r2_et0 = r2_score(y_et0_test, y_et0_pred)
    rmse_et0 = np.sqrt(mean_squared_error(y_et0_test, y_et0_pred))
    mae_et0 = mean_absolute_error(y_et0_test, y_et0_pred)

    print(f"[WATER MODEL] ET0 Prediction Metrics:")
    print(f"   R² Score: {r2_et0:.4f}")
    print(f"   RMSE:     {rmse_et0:.3f} mm/day")
    print(f"   MAE:      {mae_et0:.3f} mm/day")

    # 2. ETc Crop Water Demand Regressor
    etc_model = Pipeline([
        ("preprocessor", preprocessor),
        ("regressor", RandomForestRegressor(
            n_estimators=120,
            max_depth=8,
            random_state=42
        ))
    ])

    print("\n[WATER MODEL] Training ETc Crop Water Demand Predictor...")
    etc_model.fit(X_train, y_etc_train)

    y_etc_pred = etc_model.predict(X_test)
    r2_etc = r2_score(y_etc_test, y_etc_pred)
    rmse_etc = np.sqrt(mean_squared_error(y_etc_test, y_etc_pred))
    mae_etc = mean_absolute_error(y_etc_test, y_etc_pred)

    print(f"[WATER MODEL] ETc Crop Demand Metrics:")
    print(f"   R² Score: {r2_etc:.4f}")
    print(f"   RMSE:     {rmse_etc:.3f} mm/day")
    print(f"   MAE:      {mae_etc:.3f} mm/day")

    # Save models
    et0_path = os.path.join(MODELS_DIR, "crop_water_et0_model.joblib")
    etc_path = os.path.join(MODELS_DIR, "crop_water_etc_model.joblib")
    metrics_path = os.path.join(MODELS_DIR, "crop_water_metrics.json")

    joblib.dump(et0_model, et0_path)
    joblib.dump(etc_model, etc_path)

    metrics = {
        "model_type": "GradientBoosting + RandomForest Regressor",
        "task": "FAO-56 ET0 and ETc Crop Water Requirement Prediction",
        "train_samples": len(X_train),
        "test_samples": len(X_test),
        "et0_metrics": {
            "r2_score": round(float(r2_et0), 4),
            "rmse_mm_day": round(float(rmse_et0), 4),
            "mae_mm_day": round(float(mae_et0), 4)
        },
        "etc_metrics": {
            "r2_score": round(float(r2_etc), 4),
            "rmse_mm_day": round(float(rmse_etc), 4),
            "mae_mm_day": round(float(mae_etc), 4)
        }
    }

    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)

    print(f"\n✅ Serialized ET0 Model to: {et0_path}")
    print(f"✅ Serialized ETc Model to: {etc_path}")
    print(f"✅ Water Metrics saved to:   {metrics_path}")

if __name__ == "__main__":
    train_crop_water_models()
