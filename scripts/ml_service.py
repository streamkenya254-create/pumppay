#!/usr/bin/env python3
"""
Field Shift ML Microservice
---------------------------
High-performance REST API serving the 3 trained ML models:
  1. Crop Rotation Recommendation & Suitability Engine
  2. FAO-56 Crop Water Requirement (ET0 & ETc) Predictor
  3. Diurnal Solar PV Power & Tri-Power Controller Arbitrator

Serves JSON endpoints on port 5055 with CORS enabled.
"""

import os
import sys
import json
import math
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Dict, Any, List

# Ensure scripts dir is on python path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from predict_inference_api import AgriIntelligenceInferenceEngine
from solar_irrigation_optimizer import SolarIrrigationArbitrator

# Initialize ML Engine
ENGINE = AgriIntelligenceInferenceEngine()

# Crop family and agronomic mapping for the 8 target crops
CROP_FAMILY_MAP = {
    "maize": {"family": "Poaceae", "name": "Maize", "n_budget": -90.0, "drought": "MODERATE"},
    "beans": {"family": "Fabaceae", "name": "Common Beans", "n_budget": 40.0, "drought": "MODERATE"},
    "tomato": {"family": "Solanaceae", "name": "Tomatoes", "n_budget": -70.0, "drought": "LOW"},
    "kale": {"family": "Brassicaceae", "name": "Sukuma Wiki", "n_budget": -55.0, "drought": "MODERATE"},
    "cabbage": {"family": "Brassicaceae", "name": "Sukuma Wiki", "n_budget": -60.0, "drought": "MODERATE"},
    "onion": {"family": "Alliaceae", "name": "Onions", "n_budget": -40.0, "drought": "LOW"},
    "potato": {"family": "Solanaceae", "name": "Irish Potatoes", "n_budget": -65.0, "drought": "LOW"},
    "watermelon": {"family": "Cucurbitaceae", "name": "Watermelon", "n_budget": -50.0, "drought": "MODERATE"}
}

SOIL_PROPERTY_MAP = {
    "sand": {"clay_pct": 5.0, "soc_g_kg": 6.0, "aw_mm_m": 70, "fc": 0.15, "wp": 0.05},
    "loamy_sand": {"clay_pct": 9.0, "soc_g_kg": 10.0, "aw_mm_m": 90, "fc": 0.20, "wp": 0.08},
    "sandy_loam": {"clay_pct": 15.0, "soc_g_kg": 14.0, "aw_mm_m": 120, "fc": 0.25, "wp": 0.11},
    "loam": {"clay_pct": 22.0, "soc_g_kg": 18.0, "aw_mm_m": 150, "fc": 0.31, "wp": 0.14},
    "clay_loam": {"clay_pct": 34.0, "soc_g_kg": 22.0, "aw_mm_m": 170, "fc": 0.36, "wp": 0.18},
    "clay": {"clay_pct": 46.0, "soc_g_kg": 26.0, "aw_mm_m": 180, "fc": 0.40, "wp": 0.21}
}

class MLRequestHandler(BaseHTTPRequestHandler):
    def _send_json(self, status: int, data: Dict[str, Any]):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.end_headers()

    def do_GET(self):
        if self.path == "/health":
            self._send_json(200, {
                "ok": True,
                "status": "operational",
                "service": "FieldShift-AgriIntelligence-ML",
                "models": [
                    "crop_rotation_regressor (R2=0.978)",
                    "crop_rotation_classifier (Acc=94.6%)",
                    "crop_water_et0_model (R2=0.997)",
                    "crop_water_etc_model (R2=0.984)",
                    "solar_pv_power_model (R2=0.999)"
                ]
            })
        else:
            self._send_json(404, {"error": "Not found"})

    def do_POST(self):
        content_len = int(self.headers.get("Content-Length", 0))
        post_body = self.rfile.read(content_len) if content_len > 0 else b"{}"
        try:
            payload = json.loads(post_body.decode("utf-8")) if post_body else {}
        except Exception as e:
            return self._send_json(400, {"error": f"Invalid JSON payload: {str(e)}"})

        if self.path == "/predict/rotation":
            self.handle_rotation(payload)
        elif self.path == "/predict/crop-water":
            self.handle_crop_water(payload)
        elif self.path == "/predict/solar-arbitration":
            self.handle_solar_arbitration(payload)
        elif self.path == "/predict/plan":
            self.handle_unified_plan(payload)
        else:
            self._send_json(404, {"error": "Unknown POST route"})

    def handle_rotation(self, data: Dict[str, Any]):
        crop = str(data.get("crop", "maize")).lower()
        crop_meta = CROP_FAMILY_MAP.get(crop, {"family": "Poaceae", "name": "Maize"})
        soil = str(data.get("soil", "loam")).lower()
        soil_props = SOIL_PROPERTY_MAP.get(soil, SOIL_PROPERTY_MAP["loam"])

        ph = float(data.get("soil_ph", 6.2))
        rainfall = float(data.get("rainfall_mm", 380.0))
        pest_history = str(data.get("pest_history", "None"))
        top_k = int(data.get("top_k", 4))

        results = ENGINE.predict_crop_rotation(
            previous_crop=crop_meta["name"],
            previous_family=crop_meta["family"],
            previous_pest_history=pest_history,
            soil_ph=ph,
            soil_soc_g_kg=soil_props["soc_g_kg"],
            soil_clay_pct=soil_props["clay_pct"],
            seasonal_rainfall_mm=rainfall,
            top_k=top_k
        )
        self._send_json(200, {
            "ok": True,
            "current_crop": crop,
            "current_family": crop_meta["family"],
            "recommendations": results
        })

    def handle_crop_water(self, data: Dict[str, Any]):
        crop = str(data.get("crop", "tomato")).lower()
        crop_meta = CROP_FAMILY_MAP.get(crop, {"name": "Tomatoes"})
        stage = str(data.get("stage", "mid-season")).replace("-", "_")

        alt = float(data.get("elevM", 1150.0))
        rs_kwh = float(data.get("rs", 21.0)) / 3.6 if float(data.get("rs", 21.0)) > 10.0 else float(data.get("rs", 5.8))
        tmax = float(data.get("tmax", 27.0))
        tmin = float(data.get("tmin", 16.0))
        tmean = (tmax + tmin) / 2.0
        rh = float(data.get("rh", 60.0))
        u2 = float(data.get("u2", 2.2))
        rain = float(data.get("rain", 0.0))
        area_ha = float(data.get("areaM2", 4000.0)) / 10000.0
        flow_m3_h = float(data.get("flowLpm", 60.0)) * 0.06

        pred = ENGINE.predict_crop_water(
            crop_name=crop_meta["name"],
            growth_stage=stage,
            altitude_m=alt,
            solar_radiation_kwh_m2=rs_kwh,
            temp_mean_c=tmean,
            temp_max_c=tmax,
            temp_min_c=tmin,
            relative_humidity_pct=rh,
            wind_speed_m_s=u2,
            precipitation_mm=rain,
            area_ha=area_ha,
            pump_flow_rate_m3_h=flow_m3_h
        )
        self._send_json(200, {"ok": True, "prediction": pred})

    def handle_solar_arbitration(self, data: Dict[str, Any]):
        solar_kwh = float(data.get("dailySolarKwh", 5.8))
        water_m3 = float(data.get("targetWaterM3", 20.0))
        pv_w = float(data.get("panelWp", 1200.0))
        pump_w = float(data.get("pumpWatts", 750.0))

        plan = ENGINE.predict_solar_irrigation_arbitration(
            daily_solar_kwh_m2=solar_kwh,
            water_target_m3=water_m3,
            pv_capacity_w=pv_w,
            pump_power_w=pump_w
        )
        self._send_json(200, {"ok": True, "plan": plan})

    def handle_unified_plan(self, data: Dict[str, Any]):
        """
        Unified handler for plan.js:
        Ingests farm profile and NASA POWER weather, runs ML crop water + solar arbitration + rotation.
        """
        farm = data.get("farm", {})
        crop = str(farm.get("crop", "tomato")).lower()
        soil = str(farm.get("soil", "loam")).lower()
        soil_props = SOIL_PROPERTY_MAP.get(soil, SOIL_PROPERTY_MAP["loam"])
        area_m2 = float(farm.get("areaM2", 4000.0))
        flow_lpm = float(farm.get("flowLpm", 60.0))
        eff = float(farm.get("efficiency", 0.75))
        elev_m = float(farm.get("elevM", 1150.0))
        panel_wp = float(farm.get("panelWp", 1200.0))
        pump_watts = float(farm.get("pumpWatts", 750.0))
        planted_on = str(farm.get("plantedOn", "2026-09-01"))

        # Calculate crop days
        try:
            planted_dt = datetime.strptime(planted_on, "%Y-%m-%d")
            day_of_crop = max(0, (datetime.utcnow() - planted_dt).days)
        except Exception:
            day_of_crop = 45

        # Determine stage
        crop_meta = CROP_FAMILY_MAP.get(crop, {"family": "Solanaceae", "name": "Tomatoes"})
        if day_of_crop < 25:
            stage_name = "initial"
            kc_val = 0.5
        elif day_of_crop < 60:
            stage_name = "development"
            kc_val = 0.8
        elif day_of_crop < 105:
            stage_name = "mid-season"
            kc_val = 1.15
        elif day_of_crop < 135:
            stage_name = "late"
            kc_val = 0.75
        else:
            stage_name = "harvest"
            kc_val = 0.65

        # Weather values (from payload or defaults)
        weather = data.get("weather", {})
        tmax = float(weather.get("tmax", 27.5))
        tmin = float(weather.get("tmin", 16.5))
        rh = float(weather.get("rh", 62.0))
        u2 = float(weather.get("u2", 2.1))
        rs_raw = float(weather.get("rs", 20.8))
        rs_kwh = rs_raw / 3.6 if rs_raw > 12.0 else rs_raw
        rain = float(weather.get("rain", 0.0))

        # 1. ML Crop Water Prediction
        water_pred = ENGINE.predict_crop_water(
            crop_name=crop_meta["name"],
            growth_stage=stage_name.replace("-", "_"),
            altitude_m=elev_m,
            solar_radiation_kwh_m2=rs_kwh,
            temp_mean_c=(tmax + tmin) / 2.0,
            temp_max_c=tmax,
            temp_min_c=tmin,
            relative_humidity_pct=rh,
            wind_speed_m_s=u2,
            precipitation_mm=rain,
            area_ha=area_m2 / 10000.0,
            pump_flow_rate_m3_h=flow_lpm * 0.06
        )

        et0 = water_pred["predicted_et0_mm_day"]
        etc = water_pred["predicted_etc_mm_day"]

        # Soil depletion balance
        root_m = 0.3 + (0.5 * min(1.0, day_of_crop / 60.0))
        taw_mm = soil_props["aw_mm_m"] * root_m
        raw_mm = 0.45 * taw_mm

        # Estimate depletion Dr (default ~ 65% of RAW if not provided)
        depletion_mm = float(data.get("depletionMm", raw_mm * 1.1))
        action = "irrigate" if depletion_mm >= raw_mm else ("light" if depletion_mm >= 0.7 * raw_mm else "skip")

        net_mm = depletion_mm if action == "irrigate" else (depletion_mm * 0.5 if action == "light" else 0.0)
        gross_mm = net_mm / eff
        need_litres = int(round(gross_mm * area_m2))

        # Solar pump limit
        cap_litres = int(round(flow_lpm * 60 * 6.0))
        litres = min(need_litres, cap_litres)
        minutes = int(round(litres / flow_lpm)) if flow_lpm > 0 else 0

        # 2. Solar Tri-Power Arbitration
        solar_plan = ENGINE.predict_solar_irrigation_arbitration(
            daily_solar_kwh_m2=rs_kwh,
            water_target_m3=need_litres / 1000.0,
            pv_capacity_w=panel_wp,
            pump_power_w=pump_watts
        )
        
        # Derive optimal pumping window from hourly schedule
        pumping_hours = [
            h for h in solar_plan.get("hourly_schedule", [])
            if h.get("pump_consumption_w", 0) > 0 or h.get("pump_status") == "SOLAR_DIRECT"
        ]
        if pumping_hours:
            start_h = pumping_hours[0]["hour"]
            end_h = min(23, pumping_hours[-1]["hour"] + 1)
            opt_window = {"start": f"{start_h:02d}:00", "end": f"{end_h:02d}:00"}
        else:
            opt_window = {"start": "10:30", "end": "14:30"}

        # 3. Next Crop Rotation Recommendation
        rotations = ENGINE.predict_crop_rotation(
            previous_crop=crop_meta["name"],
            previous_family=crop_meta["family"],
            soil_ph=6.2,
            soil_soc_g_kg=soil_props["soc_g_kg"],
            soil_clay_pct=soil_props["clay_pct"],
            top_k=3
        )

        top_rot = rotations[0] if rotations else None
        rotation_advice = (
            f"Rotate next to {top_rot['crop_name']} ({top_rot['crop_family']}): "
            f"Score {top_rot['predicted_score']}/100 [{top_rot['suitability_class']}], "
            f"N impact: {top_rot['nitrogen_impact_kg_ha']:+0.0f} kg/ha"
        ) if top_rot else "Rotate to legumes to replenish soil nitrogen."

        reasons = [
            f"Soil depletion is {depletion_mm:.1f} mm of {taw_mm:.1f} mm available (stress point: {raw_mm:.1f} mm).",
            f"Peak solar window {opt_window['start']}–{opt_window['end']} (100% home power protected). Next rotation: {top_rot['crop_name']} ({top_rot['crop_family']}, {top_rot['nitrogen_impact_kg_ha']:+0.0f} kg N/ha).",
            f"Recommended next rotation: {rotation_advice}"
        ]

        self._send_json(200, {
            "ok": True,
            "action": action,
            "crop": crop,
            "stage": stage_name,
            "kc": round(water_pred["crop_kc"], 2),
            "et0Today": et0,
            "etcToday": etc,
            "depletionMm": round(depletion_mm, 1),
            "tawMm": round(taw_mm, 1),
            "rawMm": round(raw_mm, 1),
            "netMm": round(net_mm, 1),
            "grossMm": round(gross_mm, 1),
            "needLitres": need_litres,
            "litres": litres,
            "minutes": minutes,
            "daysToRefill": max(1, math.ceil(need_litres / max(cap_litres, 1))),
            "window": opt_window,
            "reasons": reasons,
            "soilLeftPct": int(round(max(0.0, (taw_mm - depletion_mm) / taw_mm) * 100)),
            "stressPct": int(round(max(0.0, (taw_mm - raw_mm) / taw_mm) * 100)),
            "dailyUseLitres": int(round(etc * area_m2)),
            "rotationRecommendations": rotations,
            "rotationAdvice": rotation_advice,
            "solarArbitration": solar_plan["summary"],
            "mlModel": "FieldShift Multi-Task ML Ensemble (NASA Earth Observations + Cropnuts Lab)"
        })

def run_server(port: int = 5055):
    server_address = ("0.0.0.0", port)
    httpd = HTTPServer(server_address, MLRequestHandler)
    print(f"🚀 Field Shift ML Service active on http://0.0.0.0:{port}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping ML service...")
        httpd.server_close()

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 5055
    run_server(port)
