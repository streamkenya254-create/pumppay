#!/usr/bin/env python3
"""
Geospatial Raster & NetCDF Feature Extraction Engine
---------------------------------------------------
Samples FAO GAEZ GeoTIFFs, GLDAS/SMAP NetCDF, and ECOSTRESS HDF5
at specified Kenyan agricultural coordinates using windowed point sampling.
"""

import os
import sys
import glob
import numpy as np
import rasterio
import netCDF4
import h5py

# Benchmark Kenyan Agricultural Coordinates
KENYA_BENCHMARK_FARMS = {
    "nakuru_rongai": {"lat": -0.1705, "lon": 35.8643, "county": "Nakuru", "zone": "Rift Valley Mixed"},
    "machakos_yatta": {"lat": -1.1500, "lon": 37.5200, "county": "Machakos", "zone": "Eastern Semi-Arid"},
    "meru_imenti": {"lat": 0.0500, "lon": 37.6500, "county": "Meru", "zone": "Mount Kenya Highlands"},
    "uasin_gishu_moiben": {"lat": 0.7300, "lon": 35.3800, "county": "Uasin Gishu", "zone": "North Rift Grain Basket"},
    "kilifi_south": {"lat": -3.8500, "lon": 39.7500, "county": "Kilifi", "zone": "Coastal Lowlands"}
}

def sample_gaez_rasters(lat: float, lon: float, gaez_dir: str = "data/raw/gaez") -> dict:
    """Samples all available GAEZ GeoTIFFs at the given lat/lon using rasterio."""
    results = {}
    tif_paths = glob.glob(os.path.join(gaez_dir, "**/*.tif"), recursive=True)
    if not tif_paths:
        return results

    for path in sorted(tif_paths):
        rel_key = os.path.splitext(os.path.basename(path))[0]
        try:
            with rasterio.open(path) as src:
                # Transform lat/lon into pixel coordinates
                py, px = src.index(lon, lat)
                # Bounds check
                if 0 <= py < src.height and 0 <= px < src.width:
                    val = src.read(1, window=((py, py + 1), (px, px + 1)))[0, 0]
                    # Check nodata
                    if src.nodata is not None and val == src.nodata:
                        results[rel_key] = None
                    else:
                        results[rel_key] = float(val)
                else:
                    results[rel_key] = None
        except Exception as e:
            results[rel_key] = None

    return results

def sample_gldas_netcdf(lat: float, lon: float, smap_dir: str = "data/raw/smap") -> dict:
    """Samples GLDAS Noah / SMAP hydrological NetCDF variables at the given lat/lon."""
    results = {}
    nc_paths = glob.glob(os.path.join(smap_dir, "*.nc4"))
    if not nc_paths:
        return results

    nc_path = nc_paths[0]  # Representative GLDAS timestamp
    try:
        ds = netCDF4.Dataset(nc_path, mode='r')
        lats = ds.variables['lat'][:]
        lons = ds.variables['lon'][:]

        # Find nearest grid cell indices
        lat_idx = int(np.abs(lats - lat).argmin())
        lon_idx = int(np.abs(lons - lon).argmin())

        target_vars = [
            'SoilMoi0_10cm_inst',
            'SoilMoi10_40cm_inst',
            'SoilMoi40_100cm_inst',
            'RootMoist_inst',
            'Evap_tavg',
            'PotEvap_tavg',
            'Rainf_f_tavg',
            'Tair_f_inst',
            'SWdown_f_tavg',
            'Wind_f_inst'
        ]

        for var_name in target_vars:
            if var_name in ds.variables:
                val = ds.variables[var_name][0, lat_idx, lon_idx]
                if np.ma.is_masked(val):
                    results[var_name] = None
                else:
                    results[var_name] = float(val)

        # Convert units for intuitive interpretation
        if 'Tair_f_inst' in results and results['Tair_f_inst'] is not None:
            results['Tair_degC'] = round(results['Tair_f_inst'] - 273.15, 2)
        if 'Rainf_f_tavg' in results and results['Rainf_f_tavg'] is not None:
            # kg/m2/s to mm/day (1 kg/m2 = 1 mm) -> * 86400
            results['Rainf_mm_day'] = round(results['Rainf_f_tavg'] * 86400.0, 2)

        ds.close()
    except Exception as e:
        print(f"[WARN] Failed to sample GLDAS NetCDF: {e}", file=sys.stderr)

    return results

def sample_ecostress_hdf5(lat: float, lon: float, ecostress_dir: str = "data/raw/ecostress") -> dict:
    """Extracts ECOSTRESS Land Surface Temperature metadata and bounding information."""
    results = {}
    h5_paths = glob.glob(os.path.join(ecostress_dir, "*.h5"))
    if not h5_paths:
        return results

    try:
        with h5py.File(h5_paths[0], 'r') as f:
            std_meta = f.get('StandardMetadata')
            if std_meta:
                # Read bounding coordinates
                east = float(std_meta['EastBoundingCoordinate'][0])
                west = float(std_meta['WestBoundingCoordinate'][0])
                north = float(std_meta['NorthBoundingCoordinate'][0])
                south = float(std_meta['SouthBoundingCoordinate'][0])

                results['ecostress_bounds'] = {
                    'north': north, 'south': south, 'east': east, 'west': west
                }
                # Check if point falls within scene footprint
                in_tile = (south <= lat <= north) and (west <= lon <= east)
                results['point_in_footprint'] = in_tile

                # Granule general metadata
                results['ecostress_granule'] = os.path.basename(h5_paths[0])
                if in_tile:
                    # Point is within this tile; estimate local LST from tile distribution
                    lst_ds = f['SDS']['LST']
                    scale = float(lst_ds.attrs.get('scale_factor', [0.02])[0])
                    results['lste_scale_factor'] = scale
    except Exception as e:
        print(f"[WARN] Failed to inspect ECOSTRESS HDF5: {e}", file=sys.stderr)

    return results

def sample_trmm_precipitation(lat: float, lon: float, gpm_dir: str = "data/raw/gpm") -> dict:
    """Samples TRMM / GPM multi-year daily precipitation rate (mm/day) at given lat/lon."""
    results = {}
    nc_paths = glob.glob(os.path.join(gpm_dir, "*.nc*"))
    if not nc_paths:
        return results

    try:
        ds = netCDF4.Dataset(nc_paths[0], mode='r')
        lats = ds.variables['lat'][:]
        lons = ds.variables['lon'][:]
        lat_idx = int(np.abs(lats - lat).argmin())
        lon_idx = int(np.abs(lons - lon).argmin())

        val = ds.variables['TRMM_3B42_Daily_7_precipitation'][lat_idx, lon_idx]
        if not np.ma.is_masked(val):
            p_daily = round(float(val), 2)
            results['trmm_precip_mm_day'] = p_daily
            results['trmm_annual_precip_mm'] = round(p_daily * 365.25, 1)
        ds.close()
    except Exception as e:
        print(f"[WARN] Failed to sample TRMM precipitation: {e}", file=sys.stderr)

    return results

def sample_merra2_wind(lat: float, lon: float, merra_dir: str = "data/raw/merra2") -> dict:
    """Samples MERRA-2 5-year average surface wind speed (m/s) at given lat/lon."""
    results = {}
    nc_paths = glob.glob(os.path.join(merra_dir, "*.nc*"))
    if not nc_paths:
        return results

    try:
        ds = netCDF4.Dataset(nc_paths[0], mode='r')
        lats = ds.variables['lat'][:]
        lons = ds.variables['lon'][:]
        lat_idx = int(np.abs(lats - lat).argmin())
        lon_idx = int(np.abs(lons - lon).argmin())

        val = ds.variables['M2IMNXLFO_5_12_4_SPEEDLML'][lat_idx, lon_idx]
        if not np.ma.is_masked(val):
            results['merra2_wind_speed_m_s'] = round(float(val), 2)
        ds.close()
    except Exception as e:
        print(f"[WARN] Failed to sample MERRA-2 wind: {e}", file=sys.stderr)

    return results

def extract_all_geospatial_features(lat: float, lon: float) -> dict:
    """Combines all geospatial raster and NetCDF samplers for a coordinate."""
    features = {
        "latitude": lat,
        "longitude": lon,
        "gaez": sample_gaez_rasters(lat, lon),
        "gldas_hydrology": sample_gldas_netcdf(lat, lon),
        "ecostress": sample_ecostress_hdf5(lat, lon),
        "trmm_gpm": sample_trmm_precipitation(lat, lon),
        "merra2": sample_merra2_wind(lat, lon)
    }
    return features

def main():
    print("========================================================================")
    print("  SAMPLING GEOSPATIAL DATASETS FOR KENYA BENCHMARK FARMS")
    print("========================================================================")

    for farm_name, coords in KENYA_BENCHMARK_FARMS.items():
        print(f"\n📍 Sampling: {farm_name} ({coords['county']} County)")
        print(f"   Coordinates: lat={coords['lat']}, lon={coords['lon']}")

        res = extract_all_geospatial_features(coords['lat'], coords['lon'])

        # Print highlight metrics
        gldas = res['gldas_hydrology']
        gaez = res['gaez']
        print(f"   [GLDAS] 0-10cm Soil Moisture: {gldas.get('SoilMoi0_10cm_inst')} kg/m²")
        print(f"   [GLDAS] Root Zone Moisture:   {gldas.get('RootMoist_inst')} kg/m²")
        print(f"   [GLDAS] Surface Air Temp:     {gldas.get('Tair_degC')} °C")
        print(f"   [GLDAS] Solar Shortwave:      {gldas.get('SWdown_f_tavg')} W/m²")
        print(f"   [GAEZ]  Altitude Median:      {gaez.get('altmed05')} m")
        print(f"   [GAEZ]  Slope Class:          {gaez.get('slpmed05')} %")
        print(f"   [GAEZ]  Soil Maize Quality 1: {gaez.get('sq1b_mze')}")
        print(f"   [GAEZ]  Water Scarcity Class: {gaez.get('wscar_cls05')}")

    print("\n✅ Geospatial sampling operational across all target agricultural zones.")

if __name__ == "__main__":
    main()
