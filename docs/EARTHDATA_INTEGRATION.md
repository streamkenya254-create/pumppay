# NASA Earthdata Integration Guide: Kenya Field Shift Platform

This document details the NASA Earthdata integration architecture, the `earthaccess` client setup, target satellite collections for Kenya, and operational recovery for Earthdata authorization.

---

## 1. Overview & Purpose

The Kenya Agricultural Intelligence & Water Management Platform ("Field Shift" / Pumppay) integrates NASA Earth Observation (EO) datasets to drive:
1. **Multi-season Crop Rotation Recommendations**: Evaluating soil health resilience, moisture retention, and pathogen break cycles.
2. **Crop Water Requirement Predictions ($ET_c$)**: Computing daily evapotranspiration and net irrigation deficit via FAO-56 Penman-Monteith.
3. **Solar Energy Availability & Tri-Power Arbitration**: Deciding when to pump, how much water to use, and how to split solar power between the pump, the battery, and the home.

---

## 2. Authentication Architecture (`earthaccess`)

The platform uses the official NASA-supported Python library [`earthaccess`](https://github.com/nsidc/earthaccess) (v0.19.0) installed in `.venv/`.

### Authentication Strategies:
1. **Interactive Terminal (`strategy="interactive"`)**:
   Prompts for username and masked password directly in the local terminal, automatically saving them to `~/.netrc` with secure `0600` permissions:
   ```bash
   .venv/bin/python scripts/nasa_earthdata_auth.py --login
   ```
2. **Local Machine File (`strategy="netrc"`)**:
   Reads credentials directly from `~/.netrc`:
   ```text
   machine urs.earthdata.nasa.gov
       login <your_username>
       password <your_password>
   ```
3. **Environment Variables (`strategy="environment"`)**:
   For automated or headless environments:
   ```bash
   export EARTHDATA_USERNAME="your_username"
   export EARTHDATA_PASSWORD="your_password"
   # Or using a bearer token:
   export EARTHDATA_TOKEN="your_token"
   ```

### Troubleshooting: "You do not have permission to view this page"
If you encounter this error in the browser when attempting to authorize apps:
1. **Profile Completion**: Log into [https://urs.earthdata.nasa.gov](https://urs.earthdata.nasa.gov) directly. If the account was newly registered, NASA requires accepting the End User License Agreement (EULA) and confirming your email address.
2. **Authorized Applications Page**: Go to **Profile** &rarr; **Authorized Apps** ([https://urs.earthdata.nasa.gov/users/profile/authorized_apps](https://urs.earthdata.nasa.gov/users/profile/authorized_apps)). If a direct link gives permission denied, navigate via the top-right profile avatar &rarr; Edit Profile &rarr; Applications.
3. **Approve DAACs**: Authorize `NASA GESDISC DATA ARCHIVE`, `LP DAAC`, and `NSIDC DAAC`.
4. **Deferred Mode**: If browser access is restricted, the platform continues seamlessly using the **existing local raw datasets** (`data/raw/ecostress/`, `data/raw/gaez/`, `data/raw/smap/GLDAS`, `data/raw/farmer_records/`) and the **NASA POWER Daily API**.

---

## 3. Prioritized NASA Earthdata Collections for Kenya

When downloading from NASA Earthdata, use spatial bounding box for Kenya:
- **South**: `-4.7°`, **North**: `5.5°`
- **West**: `33.9°`, **East**: `41.9°`

| Dataset | Short Name | Collection Entry Title | DAAC | Spatial / Temporal Res | Role in Field Shift |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **SMAP** | `SPL3SMP_E` | SMAP Enhanced L3 Radiometer Daily 9 km Soil Moisture | NSIDC | 9 km / Daily | Root-zone & surface soil moisture; triggers irrigation deficit |
| **SMAP L4** | `SPL4SMGP` | SMAP L4 Global 3-hourly 9 km Surface & Root-Zone | NSIDC | 9 km / 3-hourly | Deep root-zone water availability |
| **GPM IMERG** | `GPM_3IMERGDF` | GPM IMERG Final Daily 0.1° Precipitation | GES DISC | 0.1° (~10 km) / Daily | Rainfall accumulation; effective rainfall calculation ($P_{eff}$) |
| **MODIS** | `MOD13Q1` | MODIS/Terra Vegetation Indices 16-Day L3 250m | LP DAAC | 250 m / 16-day | NDVI & EVI vegetation vigor; crop stage monitoring |
| **MODIS LST**| `MOD11A2` | MODIS/Terra Land Surface Temp 8-Day 1km | LP DAAC | 1 km / 8-day | Regional thermal baseline |
| **ECOSTRESS**| `ECO2LSTE` | ECOSTRESS Land Surface Temp & Emissivity L2 70m | LP DAAC | 70 m / Swath (~1-5 days) | Field-scale crop canopy thermal water stress |
| **ECOSTRESS**| `ECO4ESIPTJPL`| ECOSTRESS Evapotranspiration L4 PT-JPL 70m | LP DAAC | 70 m / Swath | Ground truth validation for FAO-56 Penman-Monteith |

---

## 4. Complementary Data Source Alignment

NASA Earthdata complements, rather than replaces, existing project datasets:

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        FIELD SHIFT DATA PIPELINE                       │
└────────────────────────────────────────────────────────────────────────┘
          │                                           │
  [NASA Earthdata Cloud]                    [Existing & In-Situ Data]
  ├── SMAP (Soil moisture 9km)              ├── GAEZ (Soil qualities & AEZ 300m)
  ├── GPM (Satellite rainfall 10km)         ├── GLDAS (Soil moisture snapshot)
  ├── MODIS (NDVI vegetation 250m)          ├── NASA POWER (Daily solar & climate API)
  └── ECOSTRESS (Canopy thermal 70m)        ├── OpenWeatherMap (Forecast API proxy)
                                            ├── SoilGrids (pH, SOC, texture 250m)
                                            └── Farmer Logs (Historical crop sequence)
                                                      │
                                                      ▼
                                       ┌───────────────────────────────┐
                                       │   scripts/build_features.py   │
                                       └───────────────────────────────┘
                                                      │
                       ┌──────────────────────────────┼──────────────────────────────┐
                       ▼                              ▼                              ▼
             [Task 1: Crop Rotation]       [Task 2: Water Demand]        [Task 3: Solar Arbitrator]
             Botanical disease break       FAO-56 Penman-Monteith        Hourly irradiance curve
             $\Delta N$ legume budget      Crop Kc coefficient stages    Home vs. Pump vs. Battery
             SoilGrids pH suitability      Pumping hours required        Direct-drive solar window
```

---

## 5. Current Operational Status
- **Authentication**: Setup complete via `scripts/nasa_earthdata_auth.py` (standing by for credentials).
- **Fallback Ingestion**: Active using existing files in `data/raw/` and NASA POWER API.
- **Feature Extraction**: Operational via `scripts/sample_raster_data.py` and `scripts/build_features.py`.
