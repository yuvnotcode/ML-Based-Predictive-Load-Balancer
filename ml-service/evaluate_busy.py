from pathlib import Path

import joblib
import numpy as np

from app.features import FEATURE_NAMES
from train import MODEL_DIR, load_dataset

_, _, X_test, y_test = load_dataset()
idx = {name: i for i, name in enumerate(FEATURE_NAMES)}
cpu = X_test[:, idx["cpu"]]
recent_change = np.abs(cpu - X_test[:, idx["lag4"]])  # change over the last ~8 seconds

# Every predictor returns a CPU forecast for each test row.
predictions = {
    "persistence": X_test[:, idx["cpu"]],
    "linear_trend": np.clip(X_test[:, idx["cpu"]] + 3 * X_test[:, idx["slope5"]], 0, 100),
}
for label, path in [
    ("model_v2_new_data", MODEL_DIR / "cpu_forecaster.joblib"),
    ("model_v1_old_data", Path("models_v1") / "cpu_forecaster.joblib"),
]:
    if path.exists():
        model = joblib.load(path)["model"]
        predictions[label] = np.clip(model.predict(X_test), 0, 100)

subsets = {
    "all rows": np.ones(len(cpu), dtype=bool),
    "CPU below 10": cpu < 10,
    "CPU 10 to 30": (cpu >= 10) & (cpu < 30),
    "CPU 30 or above": cpu >= 30,
    "changing (moved 10+ in 8 s)": recent_change >= 10,
    "steady (moved under 10)": recent_change < 10,
}

names = list(predictions)
print("MAE on the test set (lower is better)\n")
print(f"{'subset':30s} {'rows':>5s}  " + "  ".join(f"{n:>18s}" for n in names))
for title, mask in subsets.items():
    n = int(mask.sum())
    if n == 0:
        print(f"{title:30s} {0:5d}")
        continue
    maes = [np.abs(y_test[mask] - predictions[name][mask]).mean() for name in names]
    print(f"{title:30s} {n:5d}  " + "  ".join(f"{v:18.2f}" for v in maes))