"""Compare routing strategies while one server at a time carries heavy background load.

Every strategy gets the same request stream and the same rotating hot-spot schedule.
Strategy order is rotated between rounds so no strategy always runs first.
"""
import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

import pandas as pd

CONTROL = "http://127.0.0.1:8080"
BACKENDS = {
    "server-a": "http://127.0.0.1:9001",
    "server-b": "http://127.0.0.1:9002",
    "server-c": "http://127.0.0.1:9003",
}
SERVER_IDS = list(BACKENDS)
STRATEGIES = ["roundrobin", "weighted", "predictive"]


def http_get(url, timeout=15.0):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        return err.code, None
    except Exception:
        return 0, None


class BackgroundLoad:
    """Keeps a few busy requests running on whichever server is currently 'hot'."""

    def __init__(self, per_server):
        self.hot = None
        for server_id, url in BACKENDS.items():
            for _ in range(per_server):
                threading.Thread(
                    target=self._worker, args=(server_id, url), daemon=True
                ).start()

    def _worker(self, server_id, url):
        while True:
            if self.hot == server_id:
                status, _ = http_get(f"{url}/backend/work?ms=300&threads=1", timeout=30.0)
                if status != 200:
                    time.sleep(0.2)
            else:
                time.sleep(0.05)


def check_system():
    status, servers = http_get(f"{CONTROL}/api/servers", timeout=5.0)
    if status != 200 or not servers:
        sys.exit("Cannot reach the control service on port 8080. Is it running?")
    healthy = [s["serverId"] for s in servers if s["status"] == "HEALTHY"]
    if len(healthy) != 3:
        sys.exit(f"Expected 3 healthy servers, found {healthy}. Start all three backends first.")

    status, _ = http_get(f"{CONTROL}/api/route/work?strategy=roundrobin&ms=10", timeout=10.0)
    if status != 200:
        sys.exit("/api/route/work did not answer. Did you rebuild after the Java edits?")

    status, body = http_get(f"{CONTROL}/api/servers/predicted", timeout=5.0)
    if status != 200 or body is None:
        sys.exit("/api/servers/predicted did not answer.")
    print("Predictive strategy reports:", body.get("strategy"))
    if body.get("strategy") != "PREDICTIVE_ML_MODEL":
        print("WARNING: predictive routing is falling back to the weighted strategy. "
              "Check that the ML service is running.")


def run_once(strategy, round_no, args, background):
    background.hot = SERVER_IDS[0]
    time.sleep(args.settle)  # let the collector notice the first hot server

    start = time.time()
    stop_at = start + args.duration
    rows = []
    lock = threading.Lock()

    def hot_at(moment):
        return SERVER_IDS[int((moment - start) // args.phase) % len(SERVER_IDS)]

    def driver():
        while time.time() < stop_at:
            background.hot = hot_at(time.time())
            time.sleep(0.1)

    def client():
        local = []
        while time.time() < stop_at:
            sent = time.time()
            status, body = http_get(
                f"{CONTROL}/api/route/work?strategy={strategy}&ms={args.work_ms}")
            latency_ms = (time.time() - sent) * 1000.0
            local.append({
                "round": round_no,
                "strategy": strategy,
                "t_sec": round(sent - start, 3),
                "server": (body or {}).get("serverId", ""),
                "status": status,
                "latency_ms": round(latency_ms, 2),
                "hot_server": hot_at(sent),
            })
        with lock:
            rows.extend(local)

    threads = [threading.Thread(target=driver)]
    threads += [threading.Thread(target=client) for _ in range(args.clients)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    background.hot = None
    time.sleep(args.cooldown)  # let CPU readings settle before the next run
    return rows


def summarize(df, args, rounds_done):
    total_seconds = args.duration * rounds_done
    summary = []
    for strategy in STRATEGIES:
        g = df[df.strategy == strategy]
        if g.empty:
            continue
        ok = g[g.status == 200]
        row = {
            "strategy": strategy,
            "requests": len(g),
            "errors_pct": round(100.0 * (1 - len(ok) / len(g)), 2),
            "throughput_rps": round(len(ok) / total_seconds, 1),
        }
        if len(ok):
            row.update({
                "mean_ms": round(ok.latency_ms.mean(), 1),
                "p50_ms": round(ok.latency_ms.quantile(0.50), 1),
                "p95_ms": round(ok.latency_ms.quantile(0.95), 1),
                "p99_ms": round(ok.latency_ms.quantile(0.99), 1),
                "to_hot_server_pct": round(100.0 * (ok.server == ok.hot_server).mean(), 1),
            })
            for sid in SERVER_IDS:
                row[f"share_{sid[-1]}_pct"] = round(100.0 * (ok.server == sid).mean(), 1)
        summary.append(row)
    return pd.DataFrame(summary)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--duration", type=int, default=60, help="seconds per run")
    parser.add_argument("--phase", type=int, default=20, help="seconds each server stays hot")
    parser.add_argument("--clients", type=int, default=4, help="concurrent request senders")
    parser.add_argument("--work-ms", type=int, default=100)
    parser.add_argument("--bg-per-server", type=int, default=3)
    parser.add_argument("--settle", type=int, default=6)
    parser.add_argument("--cooldown", type=int, default=10)
    args = parser.parse_args()

    cores = os.cpu_count() or 0
    print(f"Logical CPU cores: {cores}")
    if cores < 8:
        print("WARNING: fewer than 8 cores. CPU contention may blur the differences.")

    check_system()

    runs = args.rounds * len(STRATEGIES)
    minutes = runs * (args.settle + args.duration + args.cooldown) / 60.0
    print(f"{runs} runs, about {minutes:.1f} minutes. Do not use the computer heavily meanwhile.\n")

    background = BackgroundLoad(args.bg_per_server)
    all_rows = []
    rounds_done = 0

    try:
        for round_no in range(1, args.rounds + 1):
            shift = (round_no - 1) % len(STRATEGIES)
            order = STRATEGIES[shift:] + STRATEGIES[:shift]
            for strategy in order:
                print(f"Round {round_no}/{args.rounds}: running '{strategy}' ...")
                all_rows.extend(run_once(strategy, round_no, args, background))
            rounds_done = round_no
    except KeyboardInterrupt:
        print("\nStopped early. Summarizing what was collected.")

    if not all_rows:
        sys.exit("No data collected.")

    df = pd.DataFrame(all_rows)
    out_dir = Path(__file__).resolve().parent / "results"
    out_dir.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    df.to_csv(out_dir / f"requests_{stamp}.csv", index=False)

    summary = summarize(df, args, max(rounds_done, 1))
    summary.to_csv(out_dir / f"summary_{stamp}.csv", index=False)

    print("\n=== SUMMARY (all rounds together) ===")
    print(summary.to_string(index=False))

    ok = df[df.status == 200]
    if len(ok):
        per_round = (ok.groupby(["strategy", "round"]).latency_ms.quantile(0.95)
                     .round(1).unstack("round"))
        print("\n=== p95 latency (ms) per round, to check consistency ===")
        print(per_round.to_string())

    print(f"\nSaved to {out_dir}")


if __name__ == "__main__":
    main()