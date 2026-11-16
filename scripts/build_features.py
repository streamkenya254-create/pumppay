#!/usr/bin/env python3
"""
Multi-Source Agronomic & Earth Observation Feature Synthesizer
--------------------------------------------------------------
Fuses:
  1. FAO GAEZ GeoTIFFs (Soil constraints, terrain slope, water scarcity, AEZ)
  2. NASA GLDAS / SMAP NetCDF (Root-zone soil moisture, evapotranspiration)
  3. NASA POWER Daily API / cached observations (Solar irradiance, temp, rainfall)
  4. ISRIC SoilGrids REST API / cached chemistry (pH, organic carbon, clay/sand/silt)
  5. Farmer Crop History logs (multi-season sequence, disease history)

Outputs standardized ML feature matrices ready for model training in data/features/.
"""

import os
import sys
import json
import csv
import pandas as pd
from typing import Dict, List, Any

from sample_raster_data import KENYA_BENCHMARK_FARMS, extract_all_geospatial_features
from farmer_crop_history_manager import validate_crop_history_file, profile_rotation_sequence
from crop_water_engine import calculate_et0_fao56, calculate_irrigation_requirement
from crop_rotation_engine import CropRotationEngine

OUTPUT_DIR = "data/features"

# Benchmark soil chemical properties calibrated from SoilGrids 250m for Kenyan agro-zones
SOILGRIDS_REFERENCE = {
    "nakuru_rongai": {"ph": 6.4, "soc_g_kg": 22.5, "clay_pct": 32.0, "sand_pct": 42.0, "cec_cmol_kg": 24.0},
    "machakos_yatta": {"ph": 5.8, "soc_g_kg": 14.2, "clay_pct": 28.0, "sand_pct": 54.0, "cec_cmol_kg": 16.5},
    "meru_imenti": {"ph": 5.4, "soc_g_kg": 38.0, "clay_pct": 45.0, "sand_pct": 25.0, "cec_cmol_kg": 32.0},
    "uasin_gishu_moiben": {"ph": 5.6, "soc_g_kg": 28.4, "clay_pct": 36.0, "sand_pct": 38.0, "cec_cmol_kg": 21.0},
    "kilifi_south": {"ph": 6.8, "soc_g_kg": 11.0, "clay_pct": 18.0, "sand_pct": 72.0, "cec_cmol_kg": 14.0}
}

# NASA POWER seasonal historical averages (Long Rains MAM / Short Rains OND)
NASA_POWER_REFERENCE = {
    "nakuru_rongai": {"daily_solar_kwh_m2": 5.85, "t_mean_c": 19.5, "t_max_c": 26.2, "t_min_c": 12.8, "rh_pct": 68.0, "wind_m_s": 2.3, "annual_precip_mm": 980.0},
    "machakos_yatta": {"daily_solar_kwh_m2": 6.10, "t_mean_c": 22.8, "t_max_c": 29.5, "t_min_c": 16.1, "rh_pct": 58.0, "wind_m_s": 2.8, "annual_precip_mm": 640.0},
    "meru_imenti": {"daily_solar_kwh_m2": 5.40, "t_mean_c": 18.2, "t_max_c": 24.8, "t_min_c": 11.6, "rh_pct": 74.0, "wind_m_s": 1.9, "annual_precip_mm": 1250.0},
    "uasin_gishu_moiben": {"daily_solar_kwh_m2": 5.90, "t_mean_c": 17.5, "t_max_c": 25.0, "t_min_c": 10.0, "rh_pct": 65.0, "wind_m_s": 2.1, "annual_precip_mm": 1100.0},
    "kilifi_south": {"daily_solar_kwh_m2": 6.30, "t_mean_c": 27.5, "t_max_c": 32.0, "t_min_c": 23.0, "rh_pct": 78.0, "wind_m_s": 3.6, "annual_precip_mm": 890.0}
}

def build_unified_farm_feature_matrix() -> pd.DataFrame:
    """Builds unified multi-source geospatial and climatic feature dataset per farm."""
    records = []

    for farm_id, coords in KENYA_BENCHMARK_FARMS.items():
        lat = coords["lat"]
        lon = coords["lon"]
        county = coords["county"]
        zone = coords["zone"]

        # 1. Sample GAEZ & GLDAS
        geo_feats = extract_all_geospatial_features(lat, lon)
        gaez = geo_feats.get("gaez", {})
        gldas = geo_feats.get("gldas_hydrology", {})
        trmm = geo_feats.get("trmm_gpm", {})
        merra = geo_feats.get("merra2", {})

        # 2. Climate metrics (prioritizes cached NASA POWER climatology JSON)
        clim_file = os.path.join("data/raw/nasa_power/climatology", f"climatology_{farm_id}.json")
        gwettop_val = None
        if os.path.exists(clim_file):
            try:
                with open(clim_file, "r", encoding="utf-8") as f_clim:
                    c_data = json.load(f_clim)
                    params = c_data.get("properties", {}).get("parameter", {})
                    # Read annual or average
                    solar_raw = float(params.get("ALLSKY_SFC_SW_DWN", {}).get("ANN", 20.0))
                    # NASA POWER reports ALLSKY_SFC_SW_DWN in MJ/m^2/day (e.g. ~20.8 MJ/m^2/day)
                    if solar_raw > 12.0:
                        solar_mj = solar_raw
                        solar_kwh = round(solar_raw / 3.6, 2)
                    else:
                        solar_kwh = solar_raw
                        solar_mj = round(solar_raw * 3.6, 2)

                    t_mean = float(params.get("T2M", {}).get("ANN", 20.0))
                    t_max = float(params.get("T2M_MAX", {}).get("ANN", t_mean + 5.5))
                    t_min = float(params.get("T2M_MIN", {}).get("ANN", t_mean - 6.5))
                    rh = float(params.get("RH2M", {}).get("ANN", 65.0))
                    wind = float(params.get("WS2M", {}).get("ANN", 2.2))
                    precip_daily = float(params.get("PRECTOTCORR", {}).get("ANN", 2.5))
                    precip_annual = round(precip_daily * 365.25, 1)
                    gwettop_val = float(params.get("GWETTOP", {}).get("ANN", 0.55))
                    clim = {
                        "daily_solar_kwh_m2": solar_kwh,
                        "daily_solar_mj_m2": solar_mj,
                        "t_mean_c": t_mean,
                        "t_max_c": t_max,
                        "t_min_c": t_min,
                        "rh_pct": rh,
                        "wind_m_s": wind,
                        "annual_precip_mm": precip_annual,
                        "gwettop": gwettop_val
                    }
            except Exception as e:
                clim = NASA_POWER_REFERENCE.get(farm_id, {})
        else:
            clim = NASA_POWER_REFERENCE.get(farm_id, {})

        solar_mj = clim.get("daily_solar_mj_m2", clim.get("daily_solar_kwh_m2", 5.5) * 3.6)
        et0 = calculate_et0_fao56(
            t_mean_c=clim.get("t_mean_c", 20.0),
            t_max_c=clim.get("t_max_c", 26.0),
            t_min_c=clim.get("t_min_c", 14.0),
            rh_mean_pct=clim.get("rh_pct", 65.0),
            solar_radiation_mj_m2_day=solar_mj,
            wind_speed_2m_m_s=clim.get("wind_m_s", 2.0),
            altitude_m=gaez.get("altmed05", 1500.0) or 1500.0,
            lat_deg=lat
        )

        # 3. Soil chemistry
        soil = SOILGRIDS_REFERENCE.get(farm_id, {})

        row = {
            "farm_id": farm_id,
            "county": county,
            "agro_zone": zone,
            "latitude": lat,
            "longitude": lon,
            "altitude_m": gaez.get("altmed05"),
            "terrain_slope_pct": gaez.get("slpmed05"),
            "water_scarcity_class": gaez.get("wscar_cls05"),
            "soil_quality_maize_sq1": gaez.get("sq1b_mze"),
            "gldas_soil_moi_0_10cm": gldas.get("SoilMoi0_10cm_inst"),
            "gldas_root_zone_moist": gldas.get("RootMoist_inst"),
            "gldas_surface_temp_c": gldas.get("Tair_degC"),
            "gldas_shortwave_w_m2": gldas.get("SWdown_f_tavg"),
            "trmm_gpm_precip_mm_day": trmm.get("trmm_precip_mm_day"),
            "trmm_gpm_annual_precip_mm": trmm.get("trmm_annual_precip_mm"),
            "merra2_wind_speed_m_s": merra.get("merra2_wind_speed_m_s"),
            "nasa_power_solar_kwh_m2_day": clim.get("daily_solar_kwh_m2"),
            "nasa_power_gwettop_surface_wetness": clim.get("gwettop", 0.55),
            "nasa_power_tmean_c": clim.get("t_mean_c"),
            "nasa_power_annual_precip_mm": clim.get("annual_precip_mm"),
            "fao56_reference_et0_mm_day": et0,
            "soilgrids_ph": soil.get("ph"),
            "soilgrids_soc_g_kg": soil.get("soc_g_kg"),
            "soilgrids_clay_pct": soil.get("clay_pct"),
            "soilgrids_sand_pct": soil.get("sand_pct"),
            "soilgrids_cec_cmol_kg": soil.get("cec_cmol_kg"),
        }
        records.append(row)

    df = pd.DataFrame(records)
    return df

def build_crop_rotation_training_dataset(history_csv: str = "data/raw/farmer_records/crop_history_template.csv") -> pd.DataFrame:
    """Combines historical sequences with recommendation targets to form ML training set."""
    if not os.path.exists(history_csv):
        print(f"[WARN] History CSV not found at {history_csv}", file=sys.stderr)
        return pd.DataFrame()

    records = validate_crop_history_file(history_csv)
    profiles = profile_rotation_sequence(records)

    engine = CropRotationEngine()
    training_rows = []

    # Map each historical record to its context
    farm_map = {
        "FARM-NRB-001": "nakuru_rongai",
        "FARM-MCK-002": "machakos_yatta",
        "FARM-MRU-003": "meru_imenti",
        "FARM-ELD-004": "uasin_gishu_moiben",
        "FARM-KLF-005": "kilifi_south"
    }

    # Group records by farm
    by_farm = {}
    for r in records:
        fid = r["farm_id"]
        by_farm.setdefault(fid, []).append(r)

    for fid, history_list in by_farm.items():
        history_list.sort(key=lambda x: (x["year"], x["planting_date"]))
        benchmark_key = farm_map.get(fid, "nakuru_rongai")
        soil = SOILGRIDS_REFERENCE.get(benchmark_key, {"ph": 6.2, "soc_g_kg": 20.0})
        clim = NASA_POWER_REFERENCE.get(benchmark_key, {"daily_solar_kwh_m2": 5.8, "annual_precip_mm": 800.0})

        # Slide through seasons to create sequential transition training samples
        for i in range(1, len(history_list)):
            window = history_list[:i]
            target = history_list[i]

            prev_crop = window[-1]["crop_name"]
            prev_family = window[-1]["crop_family"]
            prev_pests = window[-1]["pest_or_disease_observed"]

            water_cap = {
                "predicted_rainfall_mm": clim["annual_precip_mm"] / 2.0,
                "solar_irrigation_cap_mm": 150.0
            }

            evals = engine.evaluate_rotation_options(
                farmer_history=window,
                soil_profile=soil,
                water_capacity=water_cap,
                farmer_priority="BALANCED"
            )

            # Match target crop
            target_eval = next((e for e in evals if e["crop_name"].lower() == target["crop_name"].lower()), None)
            target_score = target_eval["recommendation_score"] if target_eval else 50.0
            target_rank = target_eval["rank"] if target_eval else 10

            row = {
                "farm_id": fid,
                "transition_index": i,
                "previous_crop": prev_crop,
                "previous_family": prev_family,
                "previous_pests": prev_pests,
                "soil_ph": soil["ph"],
                "soil_soc": soil["soc_g_kg"],
                "soil_clay_pct": soil.get("clay_pct", 30.0),
                "solar_irradiance_kwh": clim["daily_solar_kwh_m2"],
                "rainfall_capacity_mm": water_cap["predicted_rainfall_mm"],
                "candidate_target_crop": target["crop_name"],
                "candidate_target_family": target["crop_family"],
                "target_actual_yield_t_ha": target["yield_t_ha"],
                "target_pest_observed": target["pest_or_disease_observed"],
                "target_recommendation_score": target_score,
                "target_recommendation_rank": target_rank,
                "is_monoculture_repeat": int(prev_family == target["crop_family"]),
                "is_nitrogen_enriching": int(target["crop_family"] == "Fabaceae")
            }
            training_rows.append(row)

    return pd.DataFrame(training_rows)

def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    print("========================================================================")
    print("  SYNTHESIZING MULTI-SOURCE AGRONOMIC & EO FEATURE MATRICES")
    print("========================================================================")

    # 1. Unified Farm Feature Matrix
    farm_df = build_unified_farm_feature_matrix()
    farm_csv_path = os.path.join(OUTPUT_DIR, "farm_features_unified.csv")
    farm_df.to_csv(farm_csv_path, index=False)
    print(f"\n✅ Unified Farm Geospatial & Climate Matrix saved to: {farm_csv_path}")
    print(f"   Shape: {farm_df.shape[0]} rows x {farm_df.shape[1]} columns")

    # 2. Crop Rotation Training Dataset
    rotation_df = build_crop_rotation_training_dataset()
    if not rotation_df.empty:
        rotation_csv_path = os.path.join(OUTPUT_DIR, "crop_rotation_training_set.csv")
        rotation_df.to_csv(rotation_csv_path, index=False)
        print(f"\n✅ Crop Rotation Sequence Dataset saved to: {rotation_csv_path}")
        print(f"   Shape: {rotation_df.shape[0]} samples x {rotation_df.shape[1]} features")

    print("\nFeature matrix synthesis complete. All training matrices ready in data/features/.")

if __name__ == "__main__":
    main()
