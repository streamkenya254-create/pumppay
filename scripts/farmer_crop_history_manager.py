#!/usr/bin/env python3
"""
Farmer Crop History Manager & Rotation Sequence Validator
---------------------------------------------------------
Validates, parses, and profiles farmer multi-season crop history records
to support the ML Crop Rotation Recommendation engine.
"""

import os
import sys
import csv
import json
from datetime import datetime
from collections import defaultdict

REQUIRED_COLUMNS = [
    "farm_id",
    "season",
    "year",
    "crop_name",
    "crop_family",
    "planting_date",
    "harvest_date",
    "area_ha",
    "yield_t_ha",
    "fertilizer_used",
    "irrigation_used",
    "pest_or_disease_observed"
]

# Agronomic botanical family categorization for Kenyan crops
BOTANICAL_FAMILIES = {
    "Poaceae": ["Maize", "Sorghum", "Wheat", "Barley", "Rhodes Grass", "Millet", "Sugarcane"],
    "Fabaceae": ["Common Beans", "French Beans", "Pigeon Peas", "Cowpeas", "Desmodium", "Peas", "Soybeans"],
    "Solanaceae": ["Tomatoes", "Irish Potatoes", "Eggplant", "Capsicum", "Chili"],
    "Cucurbitaceae": ["Watermelons", "Butternut", "Pumpkins", "Cucumber"],
    "Brassicaceae": ["Cabbage", "Kale", "Sukuma Wiki", "Broccoli"],
    "Alliaceae": ["Onions", "Garlic", "Leeks"],
    "Proteaceae": ["Macadamia"],
    "Lauraceae": ["Avocado", "Hass Avocado"],
}

def validate_crop_history_file(csv_filepath: str) -> list:
    """Validates CSV structure and types, returning cleaned list of record dicts."""
    if not os.path.isfile(csv_filepath):
        raise FileNotFoundError(f"Crop history file not found: {csv_filepath}")

    records = []
    with open(csv_filepath, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        missing_cols = set(REQUIRED_COLUMNS) - set(reader.fieldnames or [])
        if missing_cols:
            raise ValueError(f"CSV missing mandatory columns: {missing_cols}")

        for line_num, row in enumerate(reader, start=2):
            try:
                area = float(row["area_ha"])
                yield_val = float(row["yield_t_ha"])
                year = int(row["year"])
                irrigation = row["irrigation_used"].strip().lower() in ["true", "1", "yes"]

                # Date parsing sanity check
                datetime.strptime(row["planting_date"].strip(), "%Y-%m-%d")
                datetime.strptime(row["harvest_date"].strip(), "%Y-%m-%d")

                records.append({
                    "farm_id": row["farm_id"].strip(),
                    "season": row["season"].strip(),
                    "year": year,
                    "crop_name": row["crop_name"].strip(),
                    "crop_family": row["crop_family"].strip(),
                    "planting_date": row["planting_date"].strip(),
                    "harvest_date": row["harvest_date"].strip(),
                    "area_ha": area,
                    "yield_t_ha": yield_val,
                    "fertilizer_used": row["fertilizer_used"].strip(),
                    "irrigation_used": irrigation,
                    "pest_or_disease_observed": row["pest_or_disease_observed"].strip(),
                })
            except Exception as e:
                print(f"[WARN] Line {line_num} invalid or unparseable: {e}", file=sys.stderr)

    return records

def profile_rotation_sequence(records: list) -> dict:
    """Computes sequence transition metrics per farm parcel."""
    by_farm = defaultdict(list)
    for r in records:
        by_farm[r["farm_id"]].append(r)

    profiles = {}
    for farm_id, history in by_farm.items():
        # Sort chronologically
        history.sort(key=lambda x: (x["year"], x["planting_date"]))
        families = [h["crop_family"] for h in history]
        crops = [h["crop_name"] for h in history]

        # Check consecutive family monoculture penalties
        repeat_family_count = 0
        for i in range(1, len(families)):
            if families[i] == families[i - 1]:
                repeat_family_count += 1

        # Legume frequency (nitrogen benefit check)
        legume_seasons = sum(1 for f in families if f == "Fabaceae")
        legume_ratio = round(legume_seasons / max(len(families), 1), 2)

        profiles[farm_id] = {
            "total_recorded_seasons": len(history),
            "crop_sequence": crops,
            "family_sequence": families,
            "monoculture_repeat_violations": repeat_family_count,
            "legume_rotation_ratio": legume_ratio,
            "latest_crop": crops[-1] if crops else None,
            "latest_family": families[-1] if families else None,
            "latest_pests": history[-1]["pest_or_disease_observed"] if history else None,
            "soil_health_risk": "HIGH" if repeat_family_count >= 2 else ("MODERATE" if legume_ratio < 0.25 else "OPTIMAL")
        }

    return profiles

def main():
    default_csv = "data/raw/farmer_records/crop_history_template.csv"
    filepath = sys.argv[1] if len(sys.argv) > 1 else default_csv

    print(f"[VALIDATOR] Loading crop history from: {filepath}")
    records = validate_crop_history_file(filepath)
    print(f"[VALIDATOR] Successfully verified {len(records)} historical crop records.")

    profiles = profile_rotation_sequence(records)
    print("\n--- Farm Rotation Succession Profiles ---")
    print(json.dumps(profiles, indent=2))

if __name__ == "__main__":
    main()
