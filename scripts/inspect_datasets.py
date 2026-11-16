#!/usr/bin/env python3
"""
Dataset Integrity & Storage Inspector
------------------------------------
Audits all directories in `data/raw/`, `data/processed/`, and `data/features/`,
verifying presence, file formats, and byte footprints.
"""

import os
import sys

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
DATA_DIR = os.path.join(BASE_DIR, "data")
RAW_DIR = os.path.join(DATA_DIR, "raw")

DATASET_DIRECTORIES = [
    ("ecostress", "NASA ECOSTRESS L2 LSTE", [".h5"]),
    ("gaez", "FAO GAEZ Agro-Ecological & Soil GeoTIFFs", [".tif", ".tfw", ".xml"]),
    ("smap", "NASA GLDAS Noah / SMAP Hydrology", [".nc4", ".h5", ".csv", ".txt"]),
    ("sentinel2", "Sentinel-2 & SAR Satellite Catalogs", [".csv", ".tif", ".safe"]),
    ("nasa_power", "NASA POWER Daily Climate & Solar API Cache", [".json", ".csv"]),
    ("soilgrids", "ISRIC SoilGrids 250m REST API Cache", [".json"]),
    ("farmer_records", "Farmer Multi-Season Crop History Logs", [".csv", ".json"]),
    ("soil_chemistry", "ICRAF & CROPNUTS Wet Chemistry Ground Truth", [".csv"]),
    ("openweathermap", "OpenWeatherMap Forecast Snapshots", [".json"]),
    ("gpm", "NASA GPM / TRMM Precipitation Estimates", [".tif", ".nc4", ".nc"]),
    ("merra2", "NASA MERRA-2 Wind & Atmospheric Dynamics", [".nc4", ".nc"]),
    ("modis", "MODIS NDVI & LST Vegetation Dynamics", [".hdf", ".tif", ".nc4"]),
    ("srtm", "SRTM 30m Digital Elevation Models", [".tif"])
]

def format_size(bytes_val: int) -> str:
    for unit in ["B", "KB", "MB", "GB"]:
        if bytes_val < 1024.0:
            return f"{bytes_val:3.1f} {unit}"
        bytes_val /= 1024.0
    return f"{bytes_val:.1f} TB"

def inspect_subdirs():
    print("=" * 72)
    print("  KENYA AGRI-INTEL DATASET INVENTORY AUDIT")
    print("=" * 72)

    total_all_bytes = 0
    total_all_files = 0

    for dir_name, label, expected_exts in DATASET_DIRECTORIES:
        full_path = os.path.join(RAW_DIR, dir_name)
        if not os.path.exists(full_path):
            status = "Directory missing"
            count = 0
            size_str = "0 B"
        else:
            files = []
            dir_size = 0
            for root, _, filenames in os.walk(full_path):
                for f in filenames:
                    fpath = os.path.join(root, f)
                    if os.path.isfile(fpath):
                        files.append(f)
                        dir_size += os.path.getsize(fpath)

            count = len(files)
            size_str = format_size(dir_size)
            total_all_bytes += dir_size
            total_all_files += count

            if count == 0:
                status = "Empty (Pending download / ingest)"
            else:
                exts = set(os.path.splitext(f)[1].lower() for f in files)
                matching = any(e in exts for e in expected_exts)
                status = f"Active ({count} files, formats: {', '.join(sorted(exts))})" if matching else "Populated (unrecognized format)"

        print(f"\n📁 data/raw/{dir_name}/ - {label}")
        print(f"   Status: {status} | Total Size: {size_str}")

    print("\n" + "-" * 72)
    print(f"Grand Total in data/raw/: {total_all_files} files | {format_size(total_all_bytes)}")
    print("=" * 72)

if __name__ == "__main__":
    inspect_subdirs()
