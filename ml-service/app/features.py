import numpy as np

FEATURE_NAMES = [
    "cpu", "memory", "rps",
    "lag1", "lag2", "lag3", "lag4",
    "mean5", "slope5", "diff1",
]


def make_features(cpu, memory, rps, recent_cpu):
    """Build model inputs from the current reading and the recent CPU history.

    recent_cpu is oldest-first and its last value is the current CPU.
    """
    window = [float(v) for v in recent_cpu][-5:]
    while len(window) < 5:
        window.insert(0, window[0])
    arr = np.array(window, dtype=float)

    x = np.arange(5) - 2.0
    slope = float(np.dot(x, arr) / 10.0)  # least-squares slope over 5 points

    return [
        float(cpu), float(memory), float(rps),
        arr[-2], arr[-3], arr[-4], arr[-5],
        float(arr.mean()), slope, float(arr[-1] - arr[-2]),
    ]