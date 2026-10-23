import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import GradientBoostingRegressor, RandomForestRegressor
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from app.features import FEATURE_NAMES, make_features

HORIZON_STEPS = 3          # predict CPU 3 readings ahead (about 6-7 seconds)
CSV_PATH = Path("..") / "control-service" / "data" / "metrics.csv"
MODEL_DIR = Path("models")


def load_dataset():
    df = pd.read_csv(CSV_PATH)
    df["timestamp"] = pd.to_datetime(df["timestamp"])

    X_train, y_train, X_test, y_test = [], [], [], []
    for _, g in df.groupby("server_id"):
        g = g.sort_values("timestamp").reset_index(drop=True)
        cpu = g["cpu_percent"].to_numpy()
        mem = g["memory_percent"].to_numpy()
        rps = g["requests_per_second"].to_numpy()
        n = len(g)
        split = int(n * 0.8)  # first 80% of time = train, last 20% = test

        for i in range(n - HORIZON_STEPS):
            recent = cpu[max(0, i - 19): i + 1]
            features = make_features(cpu[i], mem[i], rps[i], recent)
            target = cpu[i + HORIZON_STEPS]

            if i < split - HORIZON_STEPS:      # target lies fully inside train period
                X_train.append(features)
                y_train.append(target)
            elif i >= split:                   # test period
                X_test.append(features)
                y_test.append(target)
            # rows in between are skipped so train labels never leak into the test period

    return (np.array(X_train), np.array(y_train),
            np.array(X_test), np.array(y_test))


def score(y_true, y_pred):
    return {
        "mae": round(float(mean_absolute_error(y_true, y_pred)), 3),
        "rmse": round(float(np.sqrt(mean_squared_error(y_true, y_pred))), 3),
    }


def main():
    X_train, y_train, X_test, y_test = load_dataset()
    print(f"Train rows: {len(X_train)}   Test rows: {len(X_test)}")
    if len(X_train) < 300 or len(X_test) < 100:
        print("Not enough data yet. Collect more rows and run again.")
        return

    idx = {name: i for i, name in enumerate(FEATURE_NAMES)}
    results = {}

    # Simple baselines (no learning)
    results["baseline_persistence"] = score(y_test, X_test[:, idx["cpu"]])
    trend = np.clip(X_test[:, idx["cpu"]] + HORIZON_STEPS * X_test[:, idx["slope5"]], 0, 100)
    results["baseline_linear_trend"] = score(y_test, trend)

    # Real machine-learning models
    models = {
        "ridge": make_pipeline(StandardScaler(), Ridge(alpha=1.0)),
        "random_forest": RandomForestRegressor(
            n_estimators=200, min_samples_leaf=3, random_state=42, n_jobs=-1),
        "gradient_boosting": GradientBoostingRegressor(random_state=42),
    }
    fitted = {}
    for name, model in models.items():
        model.fit(X_train, y_train)
        pred = np.clip(model.predict(X_test), 0, 100)
        results[name] = score(y_test, pred)
        fitted[name] = model

    print(f"\n{'model':26s} {'MAE':>8s} {'RMSE':>8s}")
    for name, r in results.items():
        print(f"{name:26s} {r['mae']:8.3f} {r['rmse']:8.3f}")

    best_name = min(fitted, key=lambda n: results[n]["rmse"])
    print(f"\nBest ML model (lowest RMSE): {best_name}")

    MODEL_DIR.mkdir(exist_ok=True)
    joblib.dump(
        {
            "model": fitted[best_name],
            "model_name": best_name,
            "feature_names": FEATURE_NAMES,
            "horizon_steps": HORIZON_STEPS,
        },
        MODEL_DIR / "cpu_forecaster.joblib",
        )
    (MODEL_DIR / "results.json").write_text(
        json.dumps({"train_rows": len(X_train), "test_rows": len(X_test),
                    "best_model": best_name, "results": results}, indent=2)
    )
    print("Saved models/cpu_forecaster.joblib and models/results.json")


if __name__ == "__main__":
    main()