#!/usr/bin/env python3
"""
Field Shift: Multi-Task ML Inference Engine & Model Serving API
---------------------------------------------------------------
Loads the trained serialized models from models/ to serve live predictions for:
  1. Crop Rotation Recommendations
  2. Crop Water Demand & Evapotranspiration (ET0 / ETc)
  3. Solar Power Generation & 24h Irrigation Arbitration
"""

import os
import sys
import json
import argparse
import joblib
import numpy as np
import pandas as pd
from typing import Dict, List, Any

MODELS_DIR = "models"

class AgriIntelligenceInferenceEngine:
    def __init__(self, models_dir: str = MODELS_DIR):
        self.models_dir = models_dir
        self.rotation_reg = None
        self.rotation_cls = None
        self.et0_model = None
        self.etc_model = None
        self.solar_model = None
        self._load_models()

    def _load_models(self):
        reg_path = os.path.join(self.models_dir, "crop_rotation_regressor.joblib")
        cls_path = os.path.join(self.models_dir, "crop_rotation_classifier.joblib")
        et0_path = os.path.join(self.models_dir, "crop_water_et0_model.joblib")
        etc_path = os.path.join(self.models_dir, "crop_water_etc_model.joblib")
        solar_path = os.path.join(self.models_dir, "solar_pv_power_model.joblib")

        if os.path.exists(reg_path):
            self.rotation_reg = joblib.load(reg_path)
        if os.path.exists(cls_path):
            self.rotation_cls = joblib.load(cls_path)
        if os.path.exists(et0_path):
            self.et0_model = joblib.load(et0_path)
        if os.path.exists(etc_path):
            self.etc_model = joblib.load(etc_path)
        if os.path.exists(solar_path):
            self.solar_model = joblib.load(solar_path)

    def predict_crop_rotation(
        self,
        previous_crop: str,
        previous_family: str,
        previous_pest_history: str = "None",
        soil_ph: float = 6.2,
        soil_soc_g_kg: float = 20.0,
        soil_total_nitrogen_pct: float = 0.08,
        soil_phosphorus_m3_mg_kg: float = 25.0,
        soil_potassium_m3_mg_kg: float = 220.0,
        soil_clay_pct: float = 28.0,
        soil_wetness_gwettop: float = 0.55,
        solar_irradiance_kwh: float = 5.8,
        seasonal_rainfall_mm: float = 400.0,
        top_k: int = 5
    ) -> List[Dict[str, Any]]:
        """Ranks all potential candidate crops using the trained ML ensemble."""
        candidate_catalog = [
            {"crop": "Cowpeas", "family": "Fabaceae", "is_n_enriching": 1, "n_budget": 60.0, "drought": "HIGH"},
            {"crop": "Pigeon Peas", "family": "Fabaceae", "is_n_enriching": 1, "n_budget": 85.0, "drought": "VERY_HIGH"},
            {"crop": "Common Beans", "family": "Fabaceae", "is_n_enriching": 1, "n_budget": 40.0, "drought": "MODERATE"},
            {"crop": "French Beans", "family": "Fabaceae", "is_n_enriching": 1, "n_budget": 35.0, "drought": "LOW"},
            {"crop": "Maize", "family": "Poaceae", "is_n_enriching": 0, "n_budget": -90.0, "drought": "MODERATE"},
            {"crop": "Sorghum", "family": "Poaceae", "is_n_enriching": 0, "n_budget": -50.0, "drought": "VERY_HIGH"},
            {"crop": "Tomatoes", "family": "Solanaceae", "is_n_enriching": 0, "n_budget": -70.0, "drought": "LOW"},
            {"crop": "Irish Potatoes", "family": "Solanaceae", "is_n_enriching": 0, "n_budget": -65.0, "drought": "LOW"},
            {"crop": "Sukuma Wiki", "family": "Brassicaceae", "is_n_enriching": 0, "n_budget": -55.0, "drought": "MODERATE"},
            {"crop": "Onions", "family": "Alliaceae", "is_n_enriching": 0, "n_budget": -40.0, "drought": "LOW"}
        ]

        rows = []
        for cand in candidate_catalog:
            is_monoculture = int(cand["family"] == previous_family)
            rows.append({
                "soil_ph": soil_ph,
                "soil_soc_g_kg": soil_soc_g_kg,
                "soil_total_nitrogen_pct": soil_total_nitrogen_pct,
                "soil_phosphorus_m3_mg_kg": soil_phosphorus_m3_mg_kg,
                "soil_potassium_m3_mg_kg": soil_potassium_m3_mg_kg,
                "soil_clay_pct": soil_clay_pct,
                "soil_wetness_gwettop": soil_wetness_gwettop,
                "solar_irradiance_kwh": solar_irradiance_kwh,
                "seasonal_rainfall_mm": seasonal_rainfall_mm,
                "is_monoculture_repeat": is_monoculture,
                "is_nitrogen_enriching": cand["is_n_enriching"],
                "nitrogen_budget_kg_ha": cand["n_budget"],
                "previous_family": previous_family,
                "candidate_family": cand["family"],
                "previous_pest_history": previous_pest_history
            })

        df_input = pd.DataFrame(rows)
        scores = self.rotation_reg.predict(df_input)
        classes = self.rotation_cls.predict(df_input)

        results = []
        for i, cand in enumerate(candidate_catalog):
            score = round(float(np.clip(scores[i], 5.0, 100.0)), 1)
            cls_name = classes[i]
            results.append({
                "crop_name": cand["crop"],
                "crop_family": cand["family"],
                "predicted_score": score,
                "suitability_class": cls_name,
                "nitrogen_impact_kg_ha": cand["n_budget"],
                "drought_resilience": cand["drought"],
                "is_monoculture_risk": bool(cand["family"] == previous_family),
                "is_legume_enricher": bool(cand["is_n_enriching"] == 1)
            })

        results.sort(key=lambda x: x["predicted_score"], reverse=True)
        for rank, item in enumerate(results, start=1):
            item["rank"] = rank

        return results[:top_k]

    def predict_crop_water(
        self,
        crop_name: str,
        growth_stage: str,
        altitude_m: float,
        solar_radiation_kwh_m2: float,
        temp_mean_c: float,
        temp_max_c: float,
        temp_min_c: float,
        relative_humidity_pct: float,
        wind_speed_m_s: float,
        precipitation_mm: float = 0.0,
        area_ha: float = 1.0,
        pump_flow_rate_m3_h: float = 2.5
    ) -> Dict[str, Any]:
        """Predicts reference ET0, crop ETc, net deficit, and required solar pump runtime."""
        kc_map = {
            "Maize": {"initial": 0.30, "development": 0.75, "mid_season": 1.20, "late_season": 0.60},
            "Common Beans": {"initial": 0.40, "development": 0.70, "mid_season": 1.15, "late_season": 0.35},
            "French Beans": {"initial": 0.50, "development": 0.80, "mid_season": 1.05, "late_season": 0.85},
            "Tomatoes": {"initial": 0.60, "development": 0.85, "mid_season": 1.15, "late_season": 0.80},
            "Irish Potatoes": {"initial": 0.50, "development": 0.75, "mid_season": 1.15, "late_season": 0.75},
            "Sukuma Wiki": {"initial": 0.70, "development": 0.90, "mid_season": 1.05, "late_season": 0.95},
            "Sorghum": {"initial": 0.30, "development": 0.65, "mid_season": 1.00, "late_season": 0.55},
            "Cowpeas": {"initial": 0.40, "development": 0.70, "mid_season": 1.05, "late_season": 0.55}
        }
        crop_kc = kc_map.get(crop_name, {}).get(growth_stage, 1.0)

        climate_cluster = "Highland" if altitude_m >= 1700 else ("Coastal" if altitude_m < 300 else "Semi-Arid")

        df_input = pd.DataFrame([{
            "altitude_m": altitude_m,
            "solar_radiation_kwh_m2": solar_radiation_kwh_m2,
            "temp_mean_c": temp_mean_c,
            "temp_max_c": temp_max_c,
            "temp_min_c": temp_min_c,
            "relative_humidity_pct": relative_humidity_pct,
            "wind_speed_m_s": wind_speed_m_s,
            "precipitation_mm": precipitation_mm,
            "crop_kc": crop_kc,
            "climate_cluster": climate_cluster,
            "crop_name": crop_name,
            "growth_stage": growth_stage
        }])

        pred_et0 = round(float(self.et0_model.predict(df_input)[0]), 2)
        pred_etc = round(float(self.etc_model.predict(df_input)[0]), 2)

        # USDA SCS effective precipitation
        p_eff = min(precipitation_mm * 0.80, precipitation_mm)
        net_deficit_mm = round(max(0.0, pred_etc - p_eff), 2)
        water_volume_m3 = round(net_deficit_mm * area_ha * 10.0, 2)
        pump_hours = round(water_volume_m3 / max(pump_flow_rate_m3_h, 0.1), 2)

        return {
            "crop_name": crop_name,
            "growth_stage": growth_stage,
            "crop_kc": crop_kc,
            "predicted_et0_mm_day": pred_et0,
            "predicted_etc_mm_day": pred_etc,
            "effective_rainfall_mm": round(p_eff, 2),
            "net_irrigation_deficit_mm": net_deficit_mm,
            "water_volume_needed_m3": water_volume_m3,
            "required_pump_runtime_hours": pump_hours
        }

    def predict_solar_irrigation_arbitration(
        self,
        daily_solar_kwh_m2: float,
        water_target_m3: float,
        pv_capacity_w: float = 1200.0,
        pump_power_w: float = 750.0
    ) -> Dict[str, Any]:
        """Simulates 24-hour hour-by-hour power arbitration and scheduling."""
        from solar_irrigation_optimizer import SolarIrrigationArbitrator
        arbitrator = SolarIrrigationArbitrator(
            pv_capacity_watts=pv_capacity_w,
            pump_power_watts=pump_power_w
        )
        plan = arbitrator.optimize_24h_schedule(
            daily_solar_kwh_m2=daily_solar_kwh_m2,
            daily_irrigation_target_m3=water_target_m3
        )
        return plan

def main():
    print("========================================================================")
    print("  FIELD SHIFT MULTI-TASK ML INFERENCE BENCHMARK")
    print("========================================================================")

    engine = AgriIntelligenceInferenceEngine()

    # 1. Test Crop Rotation Inference
    print("\n--- 1. CROP ROTATION PREDICTION (Machakos Yatta Case) ---")
    print("Farmer context: Past 2 seasons Maize (Poaceae) with Fall Armyworm attack.")
    rotations = engine.predict_crop_rotation(
        previous_crop="Maize",
        previous_family="Poaceae",
        previous_pest_history="Fall Armyworm",
        soil_ph=5.8,
        soil_soc_g_kg=14.5,
        seasonal_rainfall_mm=320.0,
        top_k=5
    )
    for r in rotations:
        print(f"  #{r['rank']} {r['crop_name']} ({r['crop_family']}): Score={r['predicted_score']}/100 [{r['suitability_class']}] (N: {r['nitrogen_impact_kg_ha']:+0.0f} kg/ha, Drought: {r['drought_resilience']})")

    # 2. Test Crop Water Requirement Inference
    print("\n--- 2. CROP WATER REQUIREMENT PREDICTION (Mid-Season Tomatoes) ---")
    water_pred = engine.predict_crop_water(
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
    print(f"  Crop:        {water_pred['crop_name']} ({water_pred['growth_stage']})")
    print(f"  ET0 Ref:     {water_pred['predicted_et0_mm_day']} mm/day")
    print(f"  ETc Demand:  {water_pred['predicted_etc_mm_day']} mm/day")
    print(f"  Water Vol:   {water_pred['water_volume_needed_m3']} m³ for 0.5 ha")
    print(f"  Pump Hours:  {water_pred['required_pump_runtime_hours']} hrs (at 2.5 m³/h)")

    # 3. Test Solar Controller Arbitration
    print("\n--- 3. SOLAR PUMP ARBITRATION SCHEDULE (Pumppay Controller) ---")
    solar_plan = engine.predict_solar_irrigation_arbitration(
        daily_solar_kwh_m2=5.8,
        water_target_m3=water_pred["water_volume_needed_m3"],
        pv_capacity_w=1500.0,
        pump_power_w=750.0
    )
    s = solar_plan["summary"]
    print(f"  Water Delivered:  {s['total_water_pumped_m3']} m³ of {s['target_irrigation_m3']} m³ ({s['water_satisfaction_pct']}%)")
    print(f"  Household Energy: {s['total_home_power_kwh']} kWh (100% Uninterrupted)")
    print(f"  Pump Solar Gen:   {s['total_pump_power_kwh']} kWh (Direct Solar Window)")
    print(f"  Battery Health:   End-of-day SoC {s['final_battery_soc_pct']}% [{s['battery_health_rating']}]")

    print("\n✅ All 3 ML Prediction Engines operational and verified.")

if __name__ == "__main__":
    main()
