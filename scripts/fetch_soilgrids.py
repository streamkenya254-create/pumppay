#!/usr/bin/env python3
"""
ISRIC SoilGrids REST API Client
-------------------------------
Queries the open ISRIC SoilGrids 250m REST API for physical and chemical
soil attributes for Kenyan agricultural coordinates.

Endpoint: https://rest.isric.org/soilgrids/v2.0/properties/query
License: Creative Commons Attribution 4.0 International (CC BY 4.0)
"""

import os
import sys
import json
import argparse
import urllib.request
import urllib.parse

PROPERTIES = ["phh2o", "soc", "nitrogen", "clay", "sand", "silt", "cec", "bdod"]
DEPTHS = ["0-5cm", "5-15cm", "15-30cm", "30-60cm"]

BENCHMARK_FARMS = {
    "nakuru_rongai": {"name": "Nakuru (Rongai)", "lat": -0.1742, "lon": 35.8643},
    "machakos_yatta": {"name": "Machakos (Yatta)", "lat": -1.1458, "lon": 37.5381},
    "meru_imenti": {"name": "Meru (Imenti South)", "lat": 0.0521, "lon": 37.6483},
    "uasin_gishu_moiben": {"name": "Uasin Gishu (Moiben)", "lat": 0.6842, "lon": 35.3489},
    "kilifi_south": {"name": "Kilifi (South)", "lat": -3.7842, "lon": 39.7541},
}

API_BASE_URL = "https://rest.isric.org/soilgrids/v2.0/properties/query"

def fetch_soilgrids(lat: float, lon: float, properties: list = None, depths: list = None) -> dict:
    if properties is None:
        properties = PROPERTIES
    if depths is None:
        depths = DEPTHS

    params = [("lon", f"{lon:.4f}"), ("lat", f"{lat:.4f}")]
    for p in properties:
        params.append(("property", p))
    for d in depths:
        params.append(("depth", d))

    query_string = urllib.parse.urlencode(params)
    url = f"{API_BASE_URL}?{query_string}"
    print(f"[SoilGrids] Querying: lat={lat:.4f}, lon={lon:.4f}")

    req = urllib.request.Request(
        url,
        headers={"User-Agent": "KenyaAgriWaterPlatform/1.0 (NASA Space Apps 2026)"}
    )

    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            if response.status == 200:
                data = json.loads(response.read().decode("utf-8"))
                return data
            else:
                raise RuntimeError(f"HTTP {response.status}: {response.reason}")
    except Exception as e:
        print(f"[ERROR] Failed to query SoilGrids API: {e}", file=sys.stderr)
        raise

def simplify_soil_profile(raw_data: dict) -> dict:
    """Extract and scale raw SoilGrids values into standard agronomic units."""
    layers = raw_data.get("properties", {}).get("layers", [])
    summary = {}
    for layer in layers:
        prop_name = layer.get("name")
        depths_data = {}
        for d in layer.get("depths", []):
            label = d.get("label")
            mean_val = d.get("values", {}).get("mean")
            if mean_val is not None:
                # SoilGrids scaling conventions:
                if prop_name == "phh2o":
                    scaled = mean_val / 10.0  # pH units
                elif prop_name == "soc":
                    scaled = mean_val / 10.0  # g/kg
                elif prop_name == "nitrogen":
                    scaled = mean_val / 100.0  # g/kg
                elif prop_name in ["clay", "sand", "silt"]:
                    scaled = mean_val / 10.0  # %
                elif prop_name == "bdod":
                    scaled = mean_val / 100.0  # g/cm^3
                else:
                    scaled = mean_val
                depths_data[label] = round(scaled, 2)
        summary[prop_name] = depths_data
    return summary

def save_soilgrids_data(data: dict, summary: dict, filename: str, output_dir: str = "data/raw/soilgrids"):
    os.makedirs(output_dir, exist_ok=True)
    filepath = os.path.join(output_dir, filename)
    payload = {
        "summary_agronomic": summary,
        "raw_response": data
    }
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    print(f"[SoilGrids] Saved profile to: {filepath}")

def main():
    parser = argparse.ArgumentParser(description="Fetch ISRIC SoilGrids 250m soil physical/chemical profile.")
    parser.add_argument("--farm", choices=list(BENCHMARK_FARMS.keys()), default="nakuru_rongai",
                        help="Benchmark farm key")
    parser.add_argument("--lat", type=float, help="Custom latitude")
    parser.add_argument("--lon", type=float, help="Custom longitude")

    args = parser.parse_args()

    if args.lat is not None and args.lon is not None:
        lat, lon = args.lat, args.lon
        farm_name = f"custom_{lat:.2f}_{lon:.2f}"
    else:
        farm_info = BENCHMARK_FARMS[args.farm]
        lat, lon = farm_info["lat"], farm_info["lon"]
        farm_name = args.farm

    try:
        raw_data = fetch_soilgrids(lat, lon)
        summary = simplify_soil_profile(raw_data)
        filename = f"soilgrids_{farm_name}.json"
        save_soilgrids_data(raw_data, summary, filename)
    except Exception as err:
        print(f"[FAIL] Ingestion aborted: {err}")
        sys.exit(1)

if __name__ == "__main__":
    main()
