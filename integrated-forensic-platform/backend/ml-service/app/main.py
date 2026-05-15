from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

try:
    import joblib
    import numpy as np
    import pandas as pd
except Exception:  # pragma: no cover - keeps the API useful before dependencies are installed
    joblib = None
    np = None
    pd = None


class PredictionRequest(BaseModel):
    features: dict[str, Any] = Field(default_factory=dict)


def find_ml_root() -> Path:
    env_value = os.getenv("FORENSIC_ML_ASSET_DIR")
    if env_value:
        return Path(env_value).expanduser().resolve()

    current = Path(__file__).resolve()
    for parent in current.parents:
        candidate = parent / "demo" / "ML_Dataset" / "ML_Dataset"
        if candidate.exists():
            return candidate
        sibling = parent / ".." / "demo" / "ML_Dataset" / "ML_Dataset"
        if sibling.resolve().exists():
            return sibling.resolve()

    return current.parents[4] / "demo" / "ML_Dataset" / "ML_Dataset"


ML_ROOT = find_ml_root()
MODEL_DIR = ML_ROOT / "Exported_Model_Assets"
MODEL_PATH = MODEL_DIR / "Forensic_RandomForest_Engine.joblib"
SCALER_PATH = MODEL_DIR / "Forensic_StandardScaler.joblib"
FEATURE_NAMES_PATH = MODEL_DIR / "Feature_Names.json"

app = FastAPI(
    title="Forensic ML Service",
    version="0.1.0",
    description="Prediction, XAI asset, and forensic report facade for the integrated platform.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

if ML_ROOT.exists():
    app.mount("/api/ml/assets", StaticFiles(directory=ML_ROOT), name="ml-assets")

_feature_names: list[str] | None = None
_model: Any | None = None
_scaler: Any | None = None


def load_feature_names() -> list[str]:
    global _feature_names
    if _feature_names is not None:
        return _feature_names
    if not FEATURE_NAMES_PATH.exists():
        _feature_names = []
        return _feature_names
    _feature_names = json.loads(FEATURE_NAMES_PATH.read_text(encoding="utf-8"))
    return _feature_names


def load_model() -> tuple[Any | None, Any | None]:
    global _model, _scaler
    if _model is not None or _scaler is not None:
        return _model, _scaler
    if joblib is None or not MODEL_PATH.exists():
        return None, None
    _model = joblib.load(MODEL_PATH)
    _scaler = joblib.load(SCALER_PATH) if SCALER_PATH.exists() else None
    return _model, _scaler


def numeric_value(value: Any) -> float:
    if value is None or value == "":
        return 0.0
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def fallback_prediction(features: dict[str, Any]) -> tuple[str, dict[str, float]]:
    sttl = numeric_value(features.get("sttl"))
    ct_state_ttl = numeric_value(features.get("ct_state_ttl"))
    dst_src = numeric_value(features.get("ct_dst_src_ltm"))
    sbytes = numeric_value(features.get("sbytes"))
    score = min(1.0, (sttl / 255.0) * 0.35 + min(ct_state_ttl / 6.0, 1.0) * 0.25 + min(dst_src / 20.0, 1.0) * 0.25 + min(sbytes / 2000.0, 1.0) * 0.15)
    label = "Attack" if score >= 0.45 else "Normal"
    return label, {"Normal": round(1.0 - score, 4), "Attack": round(score, 4)}


def evidence_hash(payload: dict[str, Any]) -> str:
    encoded = json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def report_path(method: str) -> Path:
    allowed = {
        "SHAP": ML_ROOT / "SHAP" / "Forensic_Evidence_Report.json",
        "LIME": ML_ROOT / "LIME" / "Forensic_Evidence_Report.json",
        "Permutation_Importance": ML_ROOT / "Permutation_Importance" / "Forensic_Evidence_Report.json",
        "PDP": ML_ROOT / "PDP" / "Forensic_Evidence_Report.json",
    }
    if method not in allowed:
        raise HTTPException(status_code=404, detail=f"Unknown report method: {method}")
    return allowed[method]


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "ml_root": str(ML_ROOT),
        "model_available": MODEL_PATH.exists(),
    }


@app.get("/api/ml/metadata")
def metadata() -> dict[str, Any]:
    feature_names = load_feature_names()
    explainability_assets: dict[str, list[str]] = {}
    for folder in ["SHAP", "LIME", "Permutation_Importance", "PDP", "Exported_Model_Assets"]:
        asset_dir = ML_ROOT / folder
        if asset_dir.exists():
            explainability_assets[folder] = sorted(path.name for path in asset_dir.glob("*.png"))

    report_counts: dict[str, int] = {}
    for method in ["SHAP", "LIME", "Permutation_Importance", "PDP"]:
        path = report_path(method)
        if path.exists():
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
                report_counts[method] = len(payload) if isinstance(payload, list) else 1
            except json.JSONDecodeError:
                report_counts[method] = 0
        else:
            report_counts[method] = 0

    return {
        "service": "forensic-ml",
        "ml_root": str(ML_ROOT),
        "model_available": MODEL_PATH.exists() and joblib is not None,
        "feature_count": len(feature_names),
        "explainability_assets": explainability_assets,
        "report_counts": report_counts,
    }


@app.post("/api/ml/predict")
def predict(request: PredictionRequest) -> dict[str, Any]:
    feature_names = load_feature_names()
    if feature_names:
        row = [numeric_value(request.features.get(name)) for name in feature_names]
        missing_count = sum(1 for name in feature_names if name not in request.features)
        filled_count = len(feature_names) - missing_count
    else:
        row = [numeric_value(value) for value in request.features.values()]
        missing_count = 0
        filled_count = len(row)

    model, scaler = load_model()
    probability: dict[str, float] | None = None

    if model is not None and np is not None and row:
        if feature_names and pd is not None:
            x = pd.DataFrame([row], columns=feature_names)
        else:
            x = np.array([row], dtype=float)
        if scaler is not None:
            x = scaler.transform(x)
        prediction_value = model.predict(x)[0]
        prediction: str | int | float = prediction_value.item() if hasattr(prediction_value, "item") else prediction_value
        if hasattr(model, "predict_proba"):
            probabilities = model.predict_proba(x)[0]
            labels = getattr(model, "classes_", range(len(probabilities)))
            probability = {str(label): round(float(value), 6) for label, value in zip(labels, probabilities)}
    else:
        prediction, probability = fallback_prediction(request.features)

    payload = {
        "features": request.features,
        "prediction": prediction,
        "probability": probability,
    }

    return {
        "prediction": prediction,
        "probability": probability,
        "filled_feature_count": filled_count,
        "missing_feature_count": missing_count,
        "evidence_hash": evidence_hash(payload),
    }


@app.get("/api/ml/reports/{method}")
def reports(method: str, limit: int = Query(20, ge=1, le=200)) -> list[dict[str, Any]]:
    path = report_path(method)
    if not path.exists():
        raise HTTPException(status_code=404, detail=f"Report not found: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=500, detail=f"Invalid JSON report: {exc}") from exc
    if isinstance(payload, list):
        return payload[:limit]
    return [payload]
