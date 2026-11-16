#!/usr/bin/env python3
"""
Field Shift: AI Crop Rotation Recommendation & Soil Resilience Engine
---------------------------------------------------------------------
NASA Space Apps Challenge 2026 Decision-Support Engine:
Evaluates multi-season farmer historical logs, SoilGrids chemical profile,
GAEZ agro-ecological suitability, and NASA POWER solar-water capacity to
recommend resilient crop rotation sequences that restore soil health.
"""

from typing import List, Dict, Any, Optional

CANDIDATE_CROPS_CATALOG: Dict[str, Dict[str, Any]] = {
    "Cowpeas": {
        "family": "Fabaceae",
        "category": "Legume / Pulse",
        "nitrogen_fixation_kg_ha": 60.0,
        "nutrient_demand": "LOW",
        "water_need_mm_season": 350.0,
        "ph_range": (5.5, 7.5),
        "drought_resilience": "HIGH",
        "cash_potential": "MODERATE",
        "food_security_value": "HIGH",
        "common_pathogens": ["Anthracnose"],
        "break_benefits": "Excellent break crop for cereal monoculture; enriches root-zone nitrogen."
    },
    "Pigeon Peas": {
        "family": "Fabaceae",
        "category": "Perennial Legume",
        "nitrogen_fixation_kg_ha": 85.0,
        "nutrient_demand": "LOW",
        "water_need_mm_season": 400.0,
        "ph_range": (5.0, 7.5),
        "drought_resilience": "VERY_HIGH",
        "cash_potential": "HIGH",
        "food_security_value": "HIGH",
        "common_pathogens": ["Fusarium wilt"],
        "break_benefits": "Deep taproot breaks hardpans, pulls subsoil phosphorus, fixes high nitrogen."
    },
    "Common Beans": {
        "family": "Fabaceae",
        "category": "Legume / Pulse",
        "nitrogen_fixation_kg_ha": 40.0,
        "nutrient_demand": "MODERATE",
        "water_need_mm_season": 450.0,
        "ph_range": (6.0, 7.0),
        "drought_resilience": "MODERATE",
        "cash_potential": "HIGH",
        "food_security_value": "VERY_HIGH",
        "common_pathogens": ["Bean Rust", "Angular Leaf Spot", "Root Rot"],
        "break_benefits": "Standard legume intercrop/rotation to replenish nitrogen after cereal crops."
    },
    "French Beans": {
        "family": "Fabaceae",
        "category": "Horticultural Legume",
        "nitrogen_fixation_kg_ha": 35.0,
        "nutrient_demand": "MODERATE",
        "water_need_mm_season": 420.0,
        "ph_range": (6.0, 6.8),
        "drought_resilience": "LOW",
        "cash_potential": "VERY_HIGH",
        "food_security_value": "LOW",
        "common_pathogens": ["Anthracnose", "Rust", "Thrips"],
        "break_benefits": "High export market value; fixes moderate nitrogen under solar drip irrigation."
    },
    "Maize": {
        "family": "Poaceae",
        "category": "Cereal / Staple",
        "nitrogen_fixation_kg_ha": -90.0,  # Heavy N consumer
        "nutrient_demand": "HIGH",
        "water_need_mm_season": 550.0,
        "ph_range": (5.5, 7.2),
        "drought_resilience": "MODERATE",
        "cash_potential": "MODERATE",
        "food_security_value": "ESSENTIAL",
        "common_pathogens": ["Fall Armyworm", "Maize Lethal Necrosis (MLND)", "Striga", "Stalk Borer"],
        "break_benefits": "High biomass generation, primary household calorie source."
    },
    "Sorghum": {
        "family": "Poaceae",
        "category": "Cereal / Drought-Resilient",
        "nitrogen_fixation_kg_ha": -50.0,
        "nutrient_demand": "LOW",
        "water_need_mm_season": 380.0,
        "ph_range": (5.0, 8.5),
        "drought_resilience": "VERY_HIGH",
        "cash_potential": "HIGH",
        "food_security_value": "HIGH",
        "common_pathogens": ["Head Smut", "Ergot"],
        "break_benefits": "Exceptional drought adaptation; thrives in semi-arid Machakos/Eastern zones."
    },
    "Tomatoes": {
        "family": "Solanaceae",
        "category": "Horticultural / Cash",
        "nitrogen_fixation_kg_ha": -70.0,
        "nutrient_demand": "HIGH",
        "water_need_mm_season": 600.0,
        "ph_range": (6.0, 6.8),
        "drought_resilience": "LOW",
        "cash_potential": "VERY_HIGH",
        "food_security_value": "MODERATE",
        "common_pathogens": ["Bacterial Wilt (Ralstonia)", "Early Blight", "Late Blight", "Tuta Absoluta", "Nematodes"],
        "break_benefits": "Maximum profit potential per square meter under controlled solar drip irrigation."
    },
    "Irish Potatoes": {
        "family": "Solanaceae",
        "category": "Tuber / Cash",
        "nitrogen_fixation_kg_ha": -65.0,
        "nutrient_demand": "HIGH",
        "water_need_mm_season": 500.0,
        "ph_range": (5.2, 6.5),
        "drought_resilience": "LOW",
        "cash_potential": "HIGH",
        "food_security_value": "HIGH",
        "common_pathogens": ["Late Blight", "Bacterial Wilt (Ralstonia)", "Cyst Nematodes"],
        "break_benefits": "Excellent highland cash crop; requires loose soils and strict wilt break."
    },
    "Sukuma Wiki": {
        "family": "Brassicaceae",
        "category": "Leafy Green / Vegetable",
        "nitrogen_fixation_kg_ha": -55.0,
        "nutrient_demand": "HIGH",
        "water_need_mm_season": 480.0,
        "ph_range": (6.0, 7.5),
        "drought_resilience": "MODERATE",
        "cash_potential": "HIGH",
        "food_security_value": "HIGH",
        "common_pathogens": ["Black Rot (Xanthomonas)", "Diamondback Moth", "Aphids"],
        "break_benefits": "Bio-fumigation effect; breaks nematode cycles of Solanaceae."
    },
    "Onions": {
        "family": "Alliaceae",
        "category": "Bulb / Vegetable",
        "nitrogen_fixation_kg_ha": -40.0,
        "nutrient_demand": "MODERATE",
        "water_need_mm_season": 450.0,
        "ph_range": (6.0, 7.0),
        "drought_resilience": "LOW",
        "cash_potential": "VERY_HIGH",
        "food_security_value": "MODERATE",
        "common_pathogens": ["Purple Blotch", "Thrips", "Downy Mildew"],
        "break_benefits": "Natural allicin compounds suppress soil-borne fungal pathogens."
    }
}

class CropRotationEngine:
    def __init__(self, catalog: Dict[str, Dict[str, Any]] = CANDIDATE_CROPS_CATALOG):
        self.catalog = catalog

    def evaluate_rotation_options(
        self,
        farmer_history: List[Dict[str, Any]],
        soil_profile: Dict[str, Any],
        water_capacity: Dict[str, Any],
        farmer_priority: str = "BALANCED"  # "SOIL_RESTORATION", "CASH_GENERATION", "FOOD_SECURITY", "BALANCED"
    ) -> List[Dict[str, Any]]:
        """
        Ranks candidate crops based on botanical disease break, soil nutrient budget,
        soil pH/texture compatibility, and water availability.
        """
        # Extract past sequence
        history_crops = [h.get("crop_name") for h in farmer_history]
        history_families = [h.get("crop_family") for h in farmer_history]
        latest_crop = history_crops[-1] if history_crops else None
        latest_family = history_families[-1] if history_families else None

        # Check past 2 seasons for pest issues
        recent_pests = []
        for h in farmer_history[-2:]:
            p = h.get("pest_or_disease_observed", "")
            if p and p.lower() not in ["none", "nil", ""]:
                recent_pests.append(p)

        # Soil parameters
        soil_ph = float(soil_profile.get("ph", 6.2))
        soil_soc = float(soil_profile.get("organic_carbon_g_kg", 20.0))

        # Water capacity (mm available for the upcoming season = rainfall + solar pump)
        predicted_rainfall_mm = float(water_capacity.get("predicted_rainfall_mm", 350.0))
        solar_irrigation_cap_mm = float(water_capacity.get("solar_irrigation_cap_mm", 200.0))
        total_available_water_mm = predicted_rainfall_mm + solar_irrigation_cap_mm

        scored_candidates = []

        for crop_name, profile in self.catalog.items():
            crop_family = profile["family"]
            score = 70.0  # Base neutral score
            reasons = []
            warnings = []

            # -------------------------------------------------------------
            # 1. BOTANICAL DISEASE BREAK / SUCCESSION CHECK
            # -------------------------------------------------------------
            if latest_family and crop_family == latest_family:
                # Monoculture penalty!
                penalty = -45.0
                score += penalty
                warnings.append(f"Monoculture risk: Repeated planting of {crop_family} increases soil pathogen reservoir.")

                # If this family repeated 2+ seasons consecutively
                if len(history_families) >= 2 and history_families[-2] == crop_family:
                    score -= 30.0
                    warnings.append(f"CRITICAL: 3rd consecutive season of {crop_family} risks catastrophic pest build-up.")
            else:
                # Botanical break bonus
                bonus = 25.0
                score += bonus
                reasons.append(f"Pathogen break: Swapping from {latest_family or 'previous'} to {crop_family} disrupts pest lifecycles.")

            # Pathogen cross-infection check
            for pest in recent_pests:
                for pathogen in profile["common_pathogens"]:
                    if pathogen.lower() in pest.lower() or pest.lower() in pathogen.lower():
                        score -= 35.0
                        warnings.append(f"Direct pathogen risk: Field experienced '{pest}', which attacks {crop_name}.")

            # -------------------------------------------------------------
            # 2. NITROGEN / SOIL HEALTH NUTRIENT BALANCE
            # -------------------------------------------------------------
            delta_n = profile["nitrogen_fixation_kg_ha"]
            if delta_n > 0:
                # Legume nitrogen fixation bonus
                if latest_family in ["Poaceae", "Solanaceae", "Brassicaceae"]:
                    score += 25.0
                    reasons.append(f"Nitrogen enrichment: Biological fixation restores ~{delta_n} kg N/ha after heavy nutrient extraction.")
                else:
                    score += 10.0
            else:
                # Nutrient depleter
                if latest_family in ["Poaceae", "Solanaceae"]:
                    score -= 15.0
                    warnings.append(f"Nutrient drain: Consecutive heavy feeders deplete subsoil nutrients without legume rotation.")

            # -------------------------------------------------------------
            # 3. SOIL pH & CHEMISTRY COMPATIBILITY
            # -------------------------------------------------------------
            ph_min, ph_max = profile["ph_range"]
            if ph_min <= soil_ph <= ph_max:
                score += 10.0
                reasons.append(f"Soil chemistry optimal: Farm pH ({soil_ph}) matches ideal range ({ph_min} - {ph_max}).")
            else:
                ph_diff = min(abs(soil_ph - ph_min), abs(soil_ph - ph_max))
                penalty = min(ph_diff * 15.0, 25.0)
                score -= penalty
                warnings.append(f"Suboptimal soil pH: Farm pH is {soil_ph}; {crop_name} prefers {ph_min} - {ph_max}.")

            # -------------------------------------------------------------
            # 4. WATER SECURITY & DROUGHT RESILIENCE
            # -------------------------------------------------------------
            water_need = profile["water_need_mm_season"]
            water_balance = total_available_water_mm - water_need

            if water_balance >= 0:
                score += 15.0
                reasons.append(f"Water secure: Total water ({total_available_water_mm:.0f} mm) covers crop requirement ({water_need:.0f} mm).")
            else:
                deficit = abs(water_balance)
                if profile["drought_resilience"] in ["HIGH", "VERY_HIGH"]:
                    score -= 5.0
                    warnings.append(f"Moderate water deficit ({deficit:.0f} mm), but {crop_name} has high drought tolerance.")
                else:
                    score -= min(deficit * 0.20, 35.0)
                    warnings.append(f"Water stress risk: Deficit of {deficit:.0f} mm without additional irrigation will depress yield.")

            # -------------------------------------------------------------
            # 5. FARMER STRATEGIC PRIORITY ALIGNMENT
            # -------------------------------------------------------------
            if farmer_priority == "SOIL_RESTORATION":
                if delta_n > 0:
                    score += 20.0
                if profile["category"] in ["Legume / Pulse", "Perennial Legume"]:
                    score += 15.0
            elif farmer_priority == "CASH_GENERATION":
                if profile["cash_potential"] == "VERY_HIGH":
                    score += 25.0
                elif profile["cash_potential"] == "HIGH":
                    score += 15.0
            elif farmer_priority == "FOOD_SECURITY":
                if profile["food_security_value"] in ["ESSENTIAL", "VERY_HIGH"]:
                    score += 25.0

            # Normalize score [0, 100]
            final_score = round(max(min(score, 100.0), 5.0), 1)

            scored_candidates.append({
                "crop_name": crop_name,
                "crop_family": crop_family,
                "category": profile["category"],
                "recommendation_score": final_score,
                "rank": 0,  # assigned after sorting
                "nitrogen_balance_kg_ha": delta_n,
                "seasonal_water_need_mm": water_need,
                "water_deficit_surplus_mm": round(water_balance, 1),
                "drought_resilience": profile["drought_resilience"],
                "cash_potential": profile["cash_potential"],
                "food_security_value": profile["food_security_value"],
                "agronomic_justification": reasons,
                "risk_alerts": warnings,
                "break_benefits": profile["break_benefits"]
            })

        # Sort descending by recommendation score
        scored_candidates.sort(key=lambda x: x["recommendation_score"], reverse=True)
        for idx, item in enumerate(scored_candidates, start=1):
            item["rank"] = idx

        return scored_candidates

def main():
    print("========================================================================")
    print("  NASA SPACE APPS FIELD SHIFT: CROP ROTATION ADVISORY DEMO")
    print("========================================================================")

    engine = CropRotationEngine()

    # Scenario: Smallholder in Machakos (Yatta) who planted Maize for 2 consecutive seasons
    # followed by Tomatoes that had Bacterial Wilt outbreak.
    # Current soil: pH 5.8 (slightly acidic), low SOC (14 g/kg).
    # Season forecast: Semi-arid, 320 mm rainfall + Pumppay solar pump providing 120 mm.
    history = [
        {"season": "Long Rains", "year": 2024, "crop_name": "Maize", "crop_family": "Poaceae", "pest_or_disease_observed": "Fall Armyworm"},
        {"season": "Short Rains", "year": 2024, "crop_name": "Maize", "crop_family": "Poaceae", "pest_or_disease_observed": "None"},
        {"season": "Long Rains", "year": 2025, "crop_name": "Tomatoes", "crop_family": "Solanaceae", "pest_or_disease_observed": "Bacterial Wilt"}
    ]

    soil = {"ph": 5.8, "organic_carbon_g_kg": 14.0}
    water = {"predicted_rainfall_mm": 320.0, "solar_irrigation_cap_mm": 120.0}

    print("Historical Succession on Parcel:")
    for h in history:
        print(f"  - {h['year']} {h['season']}: {h['crop_name']} ({h['crop_family']}) [Pest: {h['pest_or_disease_observed']}]")
    print(f"Soil Profile: pH {soil['ph']} | Water Available: {water['predicted_rainfall_mm'] + water['solar_irrigation_cap_mm']} mm\n")

    recommendations = engine.evaluate_rotation_options(
        farmer_history=history,
        soil_profile=soil,
        water_capacity=water,
        farmer_priority="SOIL_RESTORATION"
    )

    print("--- TOP 5 RECOMMENDED ROTATION STRATEGIES ---")
    for r in recommendations[:5]:
        print(f"\n#{r['rank']} {r['crop_name']} ({r['crop_family']}) - Score: {r['recommendation_score']}/100")
        print(f"    Category: {r['category']} | N-Impact: {r['nitrogen_balance_kg_ha']:+0.1f} kg/ha | Water Need: {r['seasonal_water_need_mm']} mm")
        print(f"    EO Justification: {r['break_benefits']}")
        if r['risk_alerts']:
            print(f"    Risks/Alerts: {'; '.join(r['risk_alerts'])}")

    print("\n--- CAUTION: LOWEST RANKED CROPS (DISEASE/WATER RESTRICTIONS) ---")
    for r in recommendations[-2:]:
        print(f"❌ #{r['rank']} {r['crop_name']} ({r['crop_family']}) - Score: {r['recommendation_score']}/100")
        print(f"    Reason for restriction: {'; '.join(r['risk_alerts'])}")

if __name__ == "__main__":
    main()
