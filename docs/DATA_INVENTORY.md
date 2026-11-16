# Data Inventory: Kenya Agricultural Intelligence & Water Management Platform

This document presents a comprehensive audit of all datasets currently available, expected, or integrated via APIs for the Kenya Agricultural Intelligence and Water Management Platform (NASA Space Apps Hackathon 2026 – Field Shift).

---

## 1. Summary of Data Assets

| Storage Category | Count | Status | Notes |
| :--- | :--- | :--- | :--- |
| **Locally Downloaded Files** | 35+ files | Active & Verified | ECOSTRESS L2 HDF5, GLDAS NetCDF4, GAEZ GeoTIFFs, ASF SAR CSV |
| **API-Accessed Datasets** | 3 APIs | Configured | NASA POWER Daily Point API, ISRIC SoilGrids API, OpenWeatherMap (backend proxy) |
| **Farmer & In-Situ Data** | Schema defined | Staged for input | Farmer multi-season crop history & IoT solar pump controller telemetry |
| **Pending Downloads** | 4 Datasets | Staged in pipeline | Sentinel-2 L2A tiles, NASA SMAP L3/L4, NASA GPM IMERG, SRTM DEM |

---

## 2. Physically Available Local Datasets (`data/raw/`)

### A. NASA ECOSTRESS Level 2 Land Surface Temperature & Emissivity (LSTE)
- **Directory**: `data/raw/ecostress/`
- **File**: `ECOv002_L2_LSTE_47375_020_20261112T142725_0713_01.h5` (152.3 MB)
- **Format**: Hierarchical Data Format version 5 (HDF5 / `.h5`)
- **Data Source**: NASA Earthdata / LP DAAC (International Space Station ECOSTRESS payload)
- **Acquisition Timestamp**: November 12, 2026
- **Variables**: Land Surface Temperature (LST in Kelvin), Surface Emissivity (bands 4 & 5), Quality Control (QC) flags, geolocated latitude/longitude arrays.
- **Completeness**: **Complete**. Self-contained granule covering East Africa / Kenya transect at ~70m spatial resolution.
- **Role in Project**: High-resolution ground truth for crop canopy thermal water stress and reference evapotranspiration ($ET$).

### B. NASA GLDAS (Global Land Data Assimilation System)
- **Directory**: `data/raw/smap/`
- **Files**:
  - `GLDAS_NOAH025_3H.A20000101.0300.021.nc4` (19.0 MB)
  - `download_files_GLDAS_NOAH025_3H_2.1.py` (7 KB CMR automated download script)
  - `subset_links_*.txt` (13 subset link manifests for Giovanni / Harmony NCALDAS and GLDAS time-series)
- **Format**: NetCDF-4 (`.nc4`), Python script, and plain text URL lists.
- **Data Source**: NASA Goddard Earth Sciences Data and Information Services Center (GES DISC) / Harmony
- **Variables**: Soil moisture (0–10cm, 10–40cm, 40–100cm, 100–200cm), soil temperature, canopy water storage, evapotranspiration, and surface runoff.
- **Completeness**: **Partially Downloaded (Sample + Automated Fetcher)**. 1 complete sample granule present; automated download script and Harmony staging link manifests available for batch retrieval.
- **Role in Project**: Multi-depth root zone soil moisture baseline to benchmark in-situ IoT probes.

### C. FAO GAEZ (Global Agro-Ecological Zones) Raster Stacks
- **Directory**: `data/raw/gaez/LR/`
- **Files**: 46 GeoTIFF rasters across categorized subfolders:
  - `soi/`: `hwsd_domi.tif`, `sq1b_mze.tif` to `sq7b_mze.tif` (Harmonized World Soil Database soil quality classes 1–7 for maize: nutrient availability, nutrient retention, rooting depth, oxygen availability, salinity, toxicity, workability).
  - `soi2/`: `siHr_sss_mze.tif`, `siHr_sst_mze.tif`, `siLr_sss_mze.tif`, `siLr_sst_mze.tif` (Suitability index for maize under rainfed vs. irrigated, high vs. low inputs).
  - `ter/`: `altmed05.tif` (Median altitude), `slpmed05.tif` (Median slope), slope classes `slp05cl1.tif` through `slp05cl8.tif`, `slp05clA.tif` through `slp05clF.tif`.
  - `wat/`: `faoirr00.tif` (Areas equipped for irrigation), `faowat00.tif` (Surface water bodies), `hydromajsq05.tif`, `wscar_cls05.tif` (Water scarcity classes).
  - `aez/`: `aez_v9v2red_5m_CRUTS32_Hist_8110_100_avg.tif` (Historical baseline AEZ classification), `aez_v9v2red_5m_ENSEMBLE_rcp4p5_2020s.tif`, `aez_v9v2red_5m_ENSEMBLE_rcp4p5_2050s.tif` (RCP 4.5 climate ensemble projections for 2020s and 2050s).
- **Format**: GeoTIFF (`.tif`) with accompanying world files (`.tfw`) and auxiliary metadata (`.aux.xml`).
- **Data Source**: Food and Agriculture Organization (FAO) / International Institute for Applied Systems Analysis (IIASA) GAEZ v4.
- **Completeness**: **Complete for Essential Agro-Ecological Layers**. Uncompressed and preserved with world georeferencing.
- **Role in Project**: Defines agro-ecological boundaries, soil workability constraints, and future climate scenario adaptation pathways.

### D. Alaska Satellite Facility (ASF) SAR Metadata Catalog
- **Directory**: `data/raw/sentinel2/` (staged for radar/optical Earth observation cataloging)
- **File**: `asf-results-2026-11-14_07-26-18.csv` (2.1 MB)
- **Format**: Comma-Separated Values (CSV)
- **Data Source**: Alaska Satellite Facility (ASF) Distributed Active Archive Center (DAAC)
- **Variables**: Granule name, sensor (`L-SAR` / NISAR), orbit, start/end time, bounding coordinates, processing level (`RRSD`), download URLs.
- **Completeness**: **Complete Catalog Search Result**.
- **Role in Project**: Metadata index for targeting synthetic aperture radar acquisitions over Kenyan agricultural areas.

---

## 3. API-Accessed Datasets (No Bulky Local Downloads Required)

### A. NASA POWER Daily Point API
- **Provider**: NASA Langley Research Center
- **Endpoint**: `https://power.larc.nasa.gov/api/temporal/daily/point`
- **Access Protocol**: HTTP REST / GET (Free, no authorization key required)
- **Query Resolution**: Coordinate-level point query for farm parcels (`latitude`, `longitude`, `start`, `end`)
- **Parameters**:
  - `T2M`: Temperature at 2 Meters (°C)
  - `T2M_MAX`: Maximum Temperature at 2 Meters (°C)
  - `T2M_MIN`: Minimum Temperature at 2 Meters (°C)
  - `PRECTOTCORR`: Corrected Total Precipitation (mm/day)
  - `RH2M`: Relative Humidity at 2 Meters (%)
  - `WS2M`: Wind Speed at 2 Meters (m/s)
  - `ALLSKY_SFC_SW_DWN`: All Sky Surface Shortwave Downward Irradiance ($kWh/m^2/day$)
- **Status**: **API Configured & Ready**. Ingestion script provided in `scripts/fetch_nasa_power.py`. Output stored in `data/raw/nasa_power/`.

### B. ISRIC SoilGrids REST API
- **Provider**: ISRIC – World Soil Information
- **Endpoint**: `https://rest.isric.org/soilgrids/v2.0/properties/query`
- **Access Protocol**: HTTP REST / GET (Free, Open Data)
- **Query Resolution**: Coordinate-level point query for farm parcels (`lat`, `lon`)
- **Parameters**:
  - `phh2o`: Soil pH in $H_2O$
  - `soc`: Soil Organic Carbon ($dg/kg$)
  - `nitrogen`: Total Nitrogen ($cg/kg$)
  - `clay`, `silt`, `sand`: Soil particle size fractions ($g/kg$)
  - `cec`: Cation Exchange Capacity ($mmol(c)/kg$)
  - `bdod`: Bulk Density of fine earth ($cg/cm^3$)
  - Depths: `0-5cm`, `5-15cm`, `15-30cm`, `30-60cm`
- **Status**: **API Configured & Ready**. Ingestion script provided in `scripts/fetch_soilgrids.py`. Output stored in `data/raw/soilgrids/`.

### C. OpenWeatherMap API (Via Node.js Backend Proxy)
- **Provider**: OpenWeather Ltd.
- **Endpoints**:
  - Current Weather: `https://api.openweathermap.org/data/2.5/weather`
  - 5-Day / 3-Hour Forecast: `https://api.openweathermap.org/data/2.5/forecast`
- **Security Constraint**: API key must remain strictly on the Node.js backend. Never exposed in React or `VITE_` client variables.
- **Status**: **Architecture Ready**. Frontend service interface prepared; waiting for backend server implementation.

---

## 4. Farmer-Supplied & IoT Controller Datasets

### A. Farmer Multi-Season Crop History
- **Directory**: `data/raw/farmer_records/`
- **Template File**: `data/raw/farmer_records/crop_history_template.csv`
- **Data Source**: Mobile app onboarding / extension officer surveys
- **Expected Schema**:
  1. `farm_id`: Unique identifier (e.g., `farm-nkr-001`)
  2. `season`: Kenyan agricultural season (e.g., `Long Rains`, `Short Rains`, `Continuous`)
  3. `year`: Cultivation year (e.g., `2023`, `2024`)
  4. `crop_name`: Common crop name (e.g., `Maize`, `French Beans`, `Irish Potatoes`)
  5. `crop_family`: Botanical family (e.g., `Poaceae`, `Fabaceae`, `Solanaceae`)
  6. `planting_date`: ISO date (`YYYY-MM-DD`)
  7. `harvest_date`: ISO date (`YYYY-MM-DD`)
  8. `area_ha`: Cultivated area in hectares
  9. `yield_t_ha`: Actual harvest yield (metric tons per hectare or bags/acre)
  10. `fertilizer_used`: Type and application rate (e.g., `DAP 50kg/acre, CAN topdress`)
  11. `irrigation_used`: Boolean / system type (`drip`, `rainfed`, `sprinkler`)
  12. `pest_or_disease_observed`: Specific pathogens noted (e.g., `Bacterial wilt`, `Rust`, `Fall armyworm`)
- **Status**: **Schema & Validator Implemented**. Managed by `scripts/farmer_crop_history_manager.py`.

### B. IoT Irrigation & Energy Controller Telemetry
- **Directory**: Staged for live MQTT / REST ingest on Node.js backend
- **Parameters**: `farm_id`, `controller_id`, `timestamp`, `soil_moisture_10cm`, `soil_moisture_30cm`, `soil_moisture_60cm`, `ambient_temp_c`, `battery_soc_pct`, `pv_power_watts`, `pv_voltage`, `pump_status`, `flow_rate_lpm`, `volume_pumped_liters`.
- **Status**: **Telemetry Mock Model Operational in Frontend**; awaiting live hardware broker connectivity.

---

## 5. Datasets Expected But Not Yet Downloaded (Pending)

| Dataset | Planned Source | Target Directory | Acquisition Strategy |
| :--- | :--- | :--- | :--- |
| **NASA SMAP L3/L4** | NASA NSIDC DAAC | `data/raw/smap/` | Earthdata Search / Harmony API for calibrated surface & root zone soil moisture |
| **NASA GPM IMERG** | NASA GES DISC | `data/raw/gpm/` | Daily accumulated precipitation GeoTIFFs (Late Run) |
| **Sentinel-2 L2A** | Copernicus Data Space | `data/raw/sentinel2/` | Cloud-free Bottom-Of-Atmosphere reflectance tiles for NDVI & vegetation index calculation |
| **SRTM 30m DEM** | NASA LP DAAC | `data/raw/srtm/` | 1-arc-second digital elevation model for fine-scale parcel slope and topographic wetness |

---

## 6. Storage Guidelines & Integrity Rules

1. **Non-destructive Processing**: Raw files in `data/raw/` must never be overwritten, modified, or forced into generic CSVs.
2. **Derived Storage Separation**:
   - Cleaned/reprojected arrays $\rightarrow$ `data/processed/`
   - Unified tabular feature matrices $\rightarrow$ `data/features/`
   - Machine learning training targets & ground truth $\rightarrow$ `data/labels/`
3. **Space Management**:
   - Gridded global data should be ingested via Point APIs whenever possible to conserve local drive space.
