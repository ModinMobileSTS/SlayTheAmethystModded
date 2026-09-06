"""Collect and analyze device-local frame/scheduler evidence.

The game writes frame-probe incidents and JVM/GDX diagnostics to its STS
directory. This helper adds an offline atrace circular buffer, dumpsys
framestats, and process snapshots. It never needs a network profiler or an
in-process Java agent, so it remains useful when Arthas misses a stop-the-world
pause or a driver/VSync stall.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from pathlib import Path


def adb(serial: str, *args: str, timeout: int = 30) -> str:
    result = subprocess.run(
        ["adb", "-s", serial, *args], text=True, encoding="utf-8",
        errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=timeout, check=False,
    )
    return result.stdout


def device_uptime_ms(serial: str) -> int | None:
    raw = adb(serial, "shell", "cat", "/proc/uptime").strip()
    try:
        return int(float(raw.split()[0]) * 1000)
    except (IndexError, ValueError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--duration", type=int, default=180)
    parser.add_argument("--buffer-kb", type=int, default=65536)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    start_epoch_ms = int(time.time() * 1000)
    start_uptime_ms = device_uptime_ms(args.device)
    meta = {
        "device": args.device,
        "startedEpochMs": start_epoch_ms,
        "startedDeviceUptimeMs": start_uptime_ms,
        "categories": ["gfx", "view", "wm", "am", "sched", "freq", "idle"],
        "durationSeconds": args.duration,
    }
    (out / "trace-meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    adb(args.device, "shell", "atrace", "-b", str(args.buffer_kb), "-c", "--async_start",
        "gfx", "view", "wm", "am", "sched", "freq", "idle", timeout=30)
    deadline = time.monotonic() + max(1, args.duration)
    snapshots = (out / "process-samples.tsv").open("w", encoding="utf-8")
    snapshots.write("epoch_ms\tpid\tpss_kb\tthreads\n")
    try:
        while time.monotonic() < deadline:
            epoch = int(time.time() * 1000)
            pid = adb(args.device, "shell", "pidof", "io.stamethyst:game").strip()
            if pid:
                mem = adb(args.device, "shell", "dumpsys", "meminfo", "io.stamethyst:game")
                match = re.search(r"^\s*TOTAL\s+(\d+)", mem, re.MULTILINE)
                threads = adb(args.device, "shell", "sh", "-c", f"ls /proc/{pid}/task 2>/dev/null | wc -l").strip()
                snapshots.write(f"{epoch}\t{pid}\t{match.group(1) if match else ''}\t{threads}\n")
                snapshots.flush()
            time.sleep(2)
    finally:
        snapshots.close()
        trace = adb(args.device, "shell", "atrace", "--async_stop", timeout=60)
        (out / "atrace.txt").write_text(trace, encoding="utf-8", errors="replace")
        gfx = adb(args.device, "shell", "dumpsys", "gfxinfo", "io.stamethyst:game", "framestats")
        (out / "gfxinfo-framestats.txt").write_text(gfx, encoding="utf-8", errors="replace")
        meta["endedEpochMs"] = int(time.time() * 1000)
        meta["endedDeviceUptimeMs"] = device_uptime_ms(args.device)
        (out / "trace-meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
