# Data Source Status Tracking

This register tracks the operational readiness and availability status of all datasets and APIs used by the **Kenya Agricultural Intelligence & Water Management Platform**.

---

## 1. Status Register

| Dataset Name | Provider | Purpose | Native Format | Acquisition Method | Status | Last Checked | Access / Auth |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **NASA ECOSTRESS L2 LSTE** | NASA / JPL (ISS) | Land surface temperature & plant water stress ($ET$) | HDF5 (`.h5`) | Direct Download (LP DAAC) | **Available locally** | 2026-11-15 | Free NASA Earthdata Login |
| **FAO GAEZ v4 Soils & AEZ** | FAO / IIASA | Agro-ecological zones, soil quality classes & RCP projections | GeoTIFF (`.tif`) | Extracted Archive | **Available locally** | 2026-11-15 | Free Open Data |
| **NASA GLDAS Noah Hydrology** | NASA GES DISC | Multi-depth soil moisture & runoff baseline | NetCDF-4 (`.nc4`) | CMR API / Harmony | **Available locally (Sample + Fetcher)** | 2026-11-15 | Free NASA Earthdata Login |
| **ASF SAR Catalog (NISAR)** | Alaska Satellite Facility | Radar metadata for surface moisture & rough terrain | CSV (`.csv`) | Search Query Export | **Available locally** | 2026-11-15 | Free Open Access |
| **NASA Earthdata Client (`earthaccess`)** | NASA Earthdata | Unified cloud & S3 direct access for SMAP, GPM, MODIS, ECOSTRESS | Python Client | earthaccess.login (URS) | **Configured (Deferred auth)** | 2026-11-15 | Free Earthdata Login |
| **NASA POWER Daily API** | NASA Langley Research Center | Historical daily temperature, precipitation, wind, humidity & solar irradiance | JSON / CSV | HTTP REST API | **API configured** | 2026-11-15 | Free Open API (No key required) |
| **ISRIC SoilGrids 250m API** | ISRIC – World Soil Information | Soil pH, organic carbon, nitrogen, sand/silt/clay & bulk density | JSON | HTTP REST API | **API configured** | 2026-11-15 | Free Open Access (CC-BY 4.0) |
| **OpenWeatherMap API** | OpenWeather Ltd. | Current weather & 5-day / 3-hour precipitation/solar forecasts | JSON | Backend Proxy API | **Pending integration** | 2026-11-15 | Free / Developer tier (Key on backend) |
| **Farmer Crop History** | Smallholder Farmers / Extensionists | Multi-season planting dates, crop varieties, yields, and observed diseases | CSV / JSON | Farmer Input via App | **Available locally (Template Ready)** | 2026-11-15 | First-party field records |
| **IoT Controller Telemetry** | PumpPay Solar Controllers | In-situ soil moisture (10/30/60cm), battery SoC, and solar pump runtime | JSON / MQTT | Local Hardware Telemetry | **Pending integration (Mock Active)** | 2026-11-15 | Proprietary Hardware Broker |
| **NASA SMAP L3/L4** | NASA NSIDC DAAC | Satellite surface and root-zone soil moisture | HDF5 / GeoTIFF | Earthdata Download | **Pending download** | 2026-11-15 | Free NASA Earthdata Login |
| **NASA GPM / TRMM (3B42 Daily v7)** | NASA GES DISC | High-resolution satellite precipitation estimates & climatology | NetCDF (`.nc`) | Giovanni Time-Averaged Map | **Available locally** | 2026-11-15 | Free NASA Earthdata |
| **NASA MERRA-2 (SPEEDLML)** | NASA GMAO / GES DISC | Surface aerodynamic wind speed ($u_2$) for Penman-Monteith ET | NetCDF (`.nc`) | Giovanni Time-Averaged Map | **Available locally** | 2026-11-15 | Free NASA Earthdata |
| **MODIS (MOD13Q1 / MOD11A2)** | NASA LP DAAC | 250m NDVI vegetation indices & 1km thermal temperature | HDF-EOS / GeoTIFF | Earthdata Download | **Pending download** | 2026-11-15 | Free NASA Earthdata Login |
| **Sentinel-2 L2A** | ESA / Copernicus | Multispectral canopy reflectance & NDVI calculations | GeoTIFF / SAFE | Copernicus Data Space | **Pending download** | 2026-11-15 | Free Copernicus Login |
| **SRTM 30m DEM** | NASA / USGS | Digital elevation model, terrain slope, and runoff vectors | GeoTIFF (`.tif`) | LP DAAC / OpenTopography | **Pending download** | 2026-11-15 | Free Open Access |

---

## 2. Definitions of Status Categories

- **Available locally**: Raw data files are physically present on disk in `data/raw/`, verified uncorrupted, and ready for feature extraction.
- **API configured**: Ingestion client scripts are written and tested; ready to query points on-demand without bulk downloads.
- **Pending integration**: Data schemas or frontend interfaces are prepared, waiting for backend proxy endpoints or live hardware connections.
- **Pending download**: Data sources have been identified and prioritized, but physical raster/NetCDF files have not yet been downloaded.
- **Requires subscription or access**: Restricted or commercial tier requiring credentials, paid subscription, or proprietary permissions.

---

## 3. Storage & Bandwidth Management Notes

- **Disk Conservation**: Because local disk space is restricted (~5.4 GB free), global multi-gigabyte raster archives are replaced by point API calls (NASA POWER, SoilGrids) whenever high-density spatial grids are not required.
- **Local Cache**: All API responses are stored locally in `data/raw/<provider>/` with timestamped filenames to prevent duplicate network calls and respect API rate limits.
