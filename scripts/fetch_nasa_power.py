#!/usr/bin/env python3
"""
NASA POWER Agro-Climatology & Telemetry API Ingestion Engine
------------------------------------------------------------
Retrieves solar irradiance, precipitation, temperature, relative humidity,
wind speed, and surface soil wetness (GWETTOP) for Kenyan agricultural parcels.

Supported Endpoints:
  1. Daily Precision Telemetry:    /api/temporal/daily/point
  2. 12-Month Aggregated Timelines: /api/temporal/monthly/point
  3. Multi-Year Agroclimate Baseline: /api/temporal/climatology/point

Parameters:
  - T2M: Temperature at 2 meters (°C, daily mean, min, max)
  - PRECTOTCORR: Precipitation corrected (mm/day)
  - GWETTOP: Surface soil wetness index (0.0 - 1.0)
  - RH2M: Relative humidity at 2 meters (%)
  - WS2M: Wind speed at 2 meters (m/s)
  - ALLSKY_SFC_SW_DWN: All-sky surface shortwave solar downward irradiance
"""

import os
import sys
import json
import argparse
from datetime import datetime
import urllib.request
import urllib.parse
from typing import Dict, List, Any

# Primary parameters requested for Field Shift & solar irrigation
AGRI_PARAMETERS = [
    "T2M",
    "T2M_MAX",
    "T2M_MIN",
    "PRECTOTCORR",
    "GWETTOP",
    "RH2M",
    "WS2M",
    "ALLSKY_SFC_SW_DWN"
]

BENCHMARK_FARMS = {
    "nakuru_rongai": {"name": "Nakuru (Rongai)", "lat": -0.1742, "lon": 35.8643, "county": "Nakuru"},
    "machakos_yatta": {"name": "Machakos (Yatta)", "lat": -1.1458, "lon": 37.5381, "county": "Machakos"},
    "meru_imenti": {"name": "Meru (Imenti South)", "lat": 0.0521, "lon": 37.6483, "county": "Meru"},
    "uasin_gishu_moiben": {"name": "Uasin Gishu (Moiben)", "lat": 0.6842, "lon": 35.3489, "county": "Uasin Gishu"},
    "kilifi_south": {"name": "Kilifi (South)", "lat": -3.7842, "lon": 39.7541, "county": "Kilifi"}
}

ENDPOINT_MAP = {
    "daily": "https://power.larc.nasa.gov/api/temporal/daily/point",
    "monthly": "https://power.larc.nasa.gov/api/temporal/monthly/point",
    "climatology": "https://power.larc.nasa.gov/api/temporal/climatology/point"
}

def fetch_power_point(
    temporal_mode: str,
    lat: float,
    lon: float,
    start: str = None,
    end: str = None,
    parameters: List[str] = None,
    community: str = "AG"
) -> Dict[str, Any]:
    """
    Queries NASA POWER API for a point coordinate under daily, monthly, or climatology modes.
    """
    if temporal_mode not in ENDPOINT_MAP:
        raise ValueError(f"Invalid mode '{temporal_mode}'. Must be one of {list(ENDPOINT_MAP.keys())}")

    if parameters is None:
        parameters = AGRI_PARAMETERS

    base_url = ENDPOINT_MAP[temporal_mode]

    query_params = {
        "parameters": ",".join(parameters),
        "community": community,
        "longitude": f"{lon:.4f}",
        "latitude": f"{lat:.4f}",
        "format": "JSON",
        "header": "true",
        "time-standard": "UTC"
    }

    if temporal_mode in ["daily", "monthly"]:
        if not start or not end:
            raise ValueError(f"Temporal mode '{temporal_mode}' requires start and end arguments.")
        query_params["start"] = start
        query_params["end"] = end

    url = f"{base_url}?{urllib.parse.urlencode(query_params)}"
    print(f"[NASA POWER] Querying {temporal_mode.upper()}: lat={lat:.4f}, lon={lon:.4f}")

    req = urllib.request.Request(
        url,
        headers={"User-Agent": "KenyaAgriWaterPlatform/2.0 (NASA Space Apps 2026 Field Shift)"}
    )

    with urllib.request.urlopen(req, timeout=30) as response:
        if response.status == 200:
            return json.loads(response.read().decode("utf-8"))
        else:
            raise RuntimeError(f"HTTP {response.status}: {response.reason}")

def save_cached_data(data: dict, filename: str, subfolder: str = "daily") -> str:
    """Saves data to data/raw/nasa_power/<subfolder>/"""
    target_dir = os.path.join("data/raw/nasa_power", subfolder)
    os.makedirs(target_dir, exist_ok=True)
    out_path = os.path.join(target_dir, filename)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    print(f"[NASA POWER] Saved {filename} ({os.path.getsize(out_path) / 1024:.1f} KB)")
    return out_path

def ingest_all_benchmark_climatology():
    """Pulls multi-year baseline climatology for all 5 benchmark agricultural zones."""
    print("\n--- INGESTING NASA POWER MULTI-YEAR CLIMATOLOGY BASELINES ---")
    for farm_id, coords in BENCHMARK_FARMS.items():
        try:
            data = fetch_power_point(
                temporal_mode="climatology",
                lat=coords["lat"],
                lon=coords["lon"]
            )
            fn = f"climatology_{farm_id}.json"
            save_cached_data(data, fn, subfolder="climatology")
        except Exception as e:
            print(f"[WARN] Failed to fetch climatology for {farm_id}: {e}", file=sys.stderr)

def ingest_recent_14day_telemetry():
    """Pulls 14-day precision daily telemetry for all benchmark farms."""
    print("\n--- INGESTING NASA POWER 14-DAY DAILY TELEMETRY ---")
    # Query recent 14-day window (e.g. 2024-10-15 to 2024-10-28)
    start_date = "20241015"
    end_date = "20241028"

    for farm_id, coords in BENCHMARK_FARMS.items():
        try:
            data = fetch_power_point(
                temporal_mode="daily",
                lat=coords["lat"],
                lon=coords["lon"],
                start=start_date,
                end=end_date
            )
            fn = f"daily_14day_{farm_id}_{start_date}_{end_date}.json"
            save_cached_data(data, fn, subfolder="daily")
        except Exception as e:
            print(f"[WARN] Failed to fetch 14-day daily for {farm_id}: {e}", file=sys.stderr)

def main():
    parser = argparse.ArgumentParser(description="NASA POWER Agro-Climatology and Telemetry Ingestion")
    parser.add_argument("--mode", choices=["daily", "monthly", "climatology", "all-climatology", "recent-14day"],
                        default="all-climatology", help="Ingestion mode")
    parser.add_argument("--farm", choices=list(BENCHMARK_FARMS.keys()), default="nakuru_rongai")
    parser.add_argument("--start", help="Start date (YYYYMMDD for daily, YYYY for monthly)")
    parser.add_argument("--end", help="End date (YYYYMMDD for daily, YYYY for monthly)")
    parser.add_argument("--lat", type=float, help="Custom latitude")
    parser.add_argument("--lon", type=float, help="Custom longitude")

    args = parser.parse_args()

    if args.mode == "all-climatology":
        ingest_all_benchmark_climatology()
        return

    if args.mode == "recent-14day":
        ingest_recent_14day_telemetry()
        return

    # Single point query
    lat = args.lat if args.lat is not None else BENCHMARK_FARMS[args.farm]["lat"]
    lon = args.lon if args.lon is not None else BENCHMARK_FARMS[args.farm]["lon"]
    farm_tag = args.farm if args.lat is None else f"custom_{lat:.2f}_{lon:.2f}"

    try:
        data = fetch_power_point(
            temporal_mode=args.mode,
            lat=lat,
            lon=lon,
            start=args.start,
            end=args.end
        )
        date_tag = f"_{args.start}_{args.end}" if args.start else ""
        fn = f"{args.mode}_{farm_tag}{date_tag}.json"
        save_cached_data(data, fn, subfolder=args.mode)
    except Exception as err:
        print(f"[FAIL] Query failed: {err}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
