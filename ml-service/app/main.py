from pathlib import Path

import joblib
import json
from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.features import make_features

BASE_DIR = Path(__file__).resolve().parent.parent
MODEL_PATH = BASE_DIR / "models" / "cpu_forecaster.joblib"
RESULTS_PATH = BASE_DIR / "models" / "results.json"
SECONDS_PER_STEP = 2  # the collector reads metrics every 2 seconds

# Load the trained model once, when the service starts.
bundle = joblib.load(MODEL_PATH) if MODEL_PATH.exists() else None

app = FastAPI(
    title="Predictive Load Balancer ML Service",
    version="0.2.0",
    description="Predicts a server's CPU load a few seconds ahead using a trained model.",
)


class CamelModel(BaseModel):
    # Accept and return camelCase JSON (what Java uses), e.g. serverId, cpuPercent
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class PredictionRequest(CamelModel):
    server_id: str = Field(min_length=1, max_length=80)
    cpu_percent: float = Field(ge=0, le=100)
    memory_percent: float = Field(ge=0, le=100)
    requests_per_second: float = Field(ge=0)
    recent_cpu_percent: list[float] = Field(min_length=2, max_length=20)


class PredictionResponse(CamelModel):
    server_id: str
    current_load_percent: float
    predicted_load_percent: float
    prediction_horizon_seconds: int
    model_version: str
    method: str


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "UP",
        "service": "ml-service",
        "model": bundle["model_name"] if bundle else "baseline (no trained model found)",
    }


@app.get("/model-info")
def model_info() -> dict:
    if not RESULTS_PATH.exists():
        return {"model": None, "message": "No training results found. Run train.py."}
    return json.loads(RESULTS_PATH.read_text())


def baseline_predict(request: PredictionRequest) -> float:
    """Old trend extrapolation. Only used if no trained model file exists."""
    history = request.recent_cpu_percent
    n = len(history)
    x = list(range(n))
    mean_x = sum(x) / n
    mean_y = sum(history) / n
    denominator = sum((xi - mean_x) ** 2 for xi in x)
    slope = (
        sum((xi - mean_x) * (yi - mean_y) for xi, yi in zip(x, history)) / denominator
        if denominator else 0.0
    )
    return history[-1] + slope * 3


@app.post("/predict", response_model=PredictionResponse)
def predict(request: PredictionRequest) -> PredictionResponse:
    if bundle:
        features = make_features(
            request.cpu_percent,
            request.memory_percent,
            request.requests_per_second,
            request.recent_cpu_percent,
        )
        predicted = float(bundle["model"].predict([features])[0])
        horizon_seconds = bundle["horizon_steps"] * SECONDS_PER_STEP
        version = f"{bundle['model_name']}-v1"
        method = f"trained-{bundle['model_name']}"
    else:
        predicted = baseline_predict(request)
        horizon_seconds = 3
        version = "baseline-0.1"
        method = "linear-trend-baseline-not-trained-ml"

    predicted = min(100.0, max(0.0, predicted))

    return PredictionResponse(
        server_id=request.server_id,
        current_load_percent=round(request.cpu_percent, 2),
        predicted_load_percent=round(predicted, 2),
        prediction_horizon_seconds=horizon_seconds,
        model_version=version,
        method=method,
    )