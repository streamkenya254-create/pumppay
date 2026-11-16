#!/usr/bin/env python3
"""
Field Shift: Crop Rotation Recommendation Model Trainer
--------------------------------------------------------
Trains an ensemble ML model (Gradient Boosting Regressor & Random Forest Classifier)
to predict crop succession scores and suitability classes based on:
  - Botanical family succession (disease break vs. monoculture risk)
  - Biological nitrogen balance (delta N from Fabaceae)
  - SoilGrids pH & Soil Organic Carbon
  - NASA POWER soil wetness (GWETTOP) & TRMM seasonal rainfall
"""

import os
import sys
import json
import joblib
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split, cross_val_score
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline
from sklearn.ensemble import GradientBoostingRegressor, RandomForestClassifier
from sklearn.metrics import r2_score, mean_squared_error, mean_absolute_error, accuracy_score, classification_report

MODELS_DIR = "models"
DATA_PATH = "data/training/cleaned_rotation_training.csv"

def train_rotation_models():
    os.makedirs(MODELS_DIR, exist_ok=True)
    print("========================================================================")
    print("  TRAINING CROP ROTATION RECOMMENDATION ML MODEL")
    print("========================================================================")

    if not os.path.exists(DATA_PATH):
        raise FileNotFoundError(f"Cleaned training dataset not found at {DATA_PATH}. Run scripts/data_cleaning_pipeline.py first.")

    df = pd.read_csv(DATA_PATH)
    print(f"[MODEL 1] Loaded {len(df)} samples from {DATA_PATH}")

    # Feature definitions (including real ICRAF & CROPNUTS lab chemistry)
    num_features = [
        "soil_ph",
        "soil_soc_g_kg",
        "soil_total_nitrogen_pct",
        "soil_phosphorus_m3_mg_kg",
        "soil_potassium_m3_mg_kg",
        "soil_clay_pct",
        "soil_wetness_gwettop",
        "solar_irradiance_kwh",
        "seasonal_rainfall_mm",
        "is_monoculture_repeat",
        "is_nitrogen_enriching",
        "nitrogen_budget_kg_ha"
    ]
    cat_features = [
        "previous_family",
        "candidate_family",
        "previous_pest_history"
    ]

    X = df[num_features + cat_features]
    y_reg = df["target_recommendation_score"]
    y_cls = df["suitability_class"]

    # Stratified Train/Test Split (80/20)
    X_train, X_test, y_reg_train, y_reg_test, y_cls_train, y_cls_test = train_test_split(
        X, y_reg, y_cls, test_size=0.20, random_state=42, stratify=y_cls
    )

    print(f"[MODEL 1] Split: Train={len(X_train)} samples | Test={len(X_test)} samples")

    # Preprocessing Pipeline
    preprocessor = ColumnTransformer(
        transformers=[
            ("num", StandardScaler(), num_features),
            ("cat", OneHotEncoder(handle_unknown="ignore", sparse_output=False), cat_features)
        ]
    )

    # 1. Regressor Pipeline (Gradient Boosting for continuous recommendation score [0, 100])
    reg_pipeline = Pipeline([
        ("preprocessor", preprocessor),
        ("regressor", GradientBoostingRegressor(
            n_estimators=150,
            learning_rate=0.08,
            max_depth=4,
            random_state=42
        ))
    ])

    print("[MODEL 1] Training Gradient Boosting Regressor...")
    reg_pipeline.fit(X_train, y_reg_train)

    y_reg_pred = reg_pipeline.predict(X_test)
    r2 = r2_score(y_reg_test, y_reg_pred)
    rmse = np.sqrt(mean_squared_error(y_reg_test, y_reg_pred))
    mae = mean_absolute_error(y_reg_test, y_reg_pred)

    print(f"[MODEL 1] Regression Evaluation:")
    print(f"   R² Score: {r2:.4f}")
    print(f"   RMSE:     {rmse:.2f} points")
    print(f"   MAE:      {mae:.2f} points")

    # 2. Classifier Pipeline (Random Forest for discrete Suitability Class)
    cls_pipeline = Pipeline([
        ("preprocessor", preprocessor),
        ("classifier", RandomForestClassifier(
            n_estimators=120,
            max_depth=6,
            random_state=42
        ))
    ])

    print("\n[MODEL 1] Training Random Forest Classifier...")
    cls_pipeline.fit(X_train, y_cls_train)

    y_cls_pred = cls_pipeline.predict(X_test)
    acc = accuracy_score(y_cls_test, y_cls_pred)

    print(f"[MODEL 1] Classification Evaluation:")
    print(f"   Accuracy: {acc * 100.0:.2f}%")
    print("\n" + classification_report(y_cls_test, y_cls_pred))

    # Serialize trained models & metrics
    reg_path = os.path.join(MODELS_DIR, "crop_rotation_regressor.joblib")
    cls_path = os.path.join(MODELS_DIR, "crop_rotation_classifier.joblib")
    metrics_path = os.path.join(MODELS_DIR, "crop_rotation_metrics.json")

    joblib.dump(reg_pipeline, reg_path)
    joblib.dump(cls_pipeline, cls_path)

    metrics = {
        "model_type": "GradientBoostingRegressor + RandomForestClassifier",
        "task": "Crop Rotation Recommendation & Suitability",
        "train_samples": len(X_train),
        "test_samples": len(X_test),
        "regression": {
            "r2_score": round(float(r2), 4),
            "rmse": round(float(rmse), 4),
            "mae": round(float(mae), 4)
        },
        "classification": {
            "accuracy": round(float(acc), 4)
        },
        "features": {
            "numerical": num_features,
            "categorical": cat_features
        }
    }

    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)

    print(f"✅ Serialized models to: {reg_path} & {cls_path}")
    print(f"✅ Metrics saved to:     {metrics_path}")

if __name__ == "__main__":
    train_rotation_models()
