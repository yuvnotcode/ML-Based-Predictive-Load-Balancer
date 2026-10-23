from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

app = FastAPI(
    title="Predictive Load Balancer ML Service",
    version="0.1.0",
    description="Separate Python prediction API. Current predictor is a temporary trend baseline, not the final trained model.",
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
    return {"status": "UP", "service": "ml-service"}


@app.post("/predict", response_model=PredictionResponse)
def predict(request: PredictionRequest) -> PredictionResponse:
    """Temporary trend extrapolation baseline; replace with a trained model next."""
    history = request.recent_cpu_percent
    x = list(range(len(history)))
    n = len(history)
    mean_x = sum(x) / n
    mean_y = sum(history) / n
    denominator = sum((xi - mean_x) ** 2 for xi in x)
    slope = (
        sum((xi - mean_x) * (yi - mean_y) for xi, yi in zip(x, history)) / denominator
        if denominator else 0.0
    )
    projected_cpu = history[-1] + slope * 3
    projected_load = (
            0.75 * projected_cpu
            + 0.15 * request.memory_percent
            + 0.10 * min(request.requests_per_second, 100)
    )
    projected_load = min(100.0, max(0.0, projected_load))

    return PredictionResponse(
        server_id=request.server_id,
        current_load_percent=round(request.cpu_percent, 2),
        predicted_load_percent=round(projected_load, 2),
        prediction_horizon_seconds=3,
        model_version="baseline-0.1",
        method="linear-trend-baseline-not-trained-ml",
    )