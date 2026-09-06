"""Offline analyzer for atrace, gfxinfo, frame-probe, and JVM evidence."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from statistics import mean, median


SCHED_RE = re.compile(
    r"^\s*(?P<comm>.+)-(?P<tid>\d+)\s+\(\s*(?P<tgid>\d+)\)\s+"
    r"\[\s*(?P<cpu>\d+)\].*?\s(?P<ts>\d+\.\d+): sched_switch: "
    r"prev_comm=(?P<prev_comm>\S+) prev_pid=(?P<prev_pid>\d+) "
    r"prev_prio=(?P<prev_prio>\d+) prev_state=(?P<prev_state>\S+) ==>",
)
NEXT_RE = re.compile(r"next_comm=(?P<next_comm>\S+) next_pid=(?P<next_pid>\d+)")
TRACE_TS_RE = re.compile(r"\s(?P<ts>\d+\.\d+):")


def load_jsonl(path: Path) -> list[dict]:
    rows = []
    if not path.is_file():
        return rows
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            value = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            rows.append(value)
    return rows


def parse_gfxinfo(path: Path) -> dict[str, float | int]:
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    result: dict[str, float | int] = {}
    patterns = {
        "total_frames": r"Total frames rendered:\s*(\d+)",
        "janky_frames": r"Janky frames:\s*(\d+)",
        "missed_vsync": r"Number Missed Vsync:\s*(\d+)",
        "slow_issue_draw": r"Number Slow issue draw commands:\s*(\d+)",
        "slow_ui": r"Number Slow UI thread:\s*(\d+)",
        "p50_ms": r"50th percentile:\s*(\d+)ms",
        "p90_ms": r"90th percentile:\s*(\d+)ms",
        "p95_ms": r"95th percentile:\s*(\d+)ms",
        "p99_ms": r"99th percentile:\s*(\d+)ms",
        "gpu_p50_ms": r"50th gpu percentile:\s*(\d+)ms",
        "gpu_p90_ms": r"90th gpu percentile:\s*(\d+)ms",
        "gpu_p95_ms": r"95th gpu percentile:\s*(\d+)ms",
        "gpu_p99_ms": r"99th gpu percentile:\s*(\d+)ms",
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if match:
            result[key] = int(match.group(1))
    return result


def parse_sched(path: Path, target_pid: int | None) -> dict:
    if not path.is_file() or target_pid is None:
        return {"targetPid": target_pid, "matchedEvents": 0, "note": "missing trace or target PID"}

    target = str(target_pid)
    target_tids: set[str] = {target}
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    events: list[dict] = []
    parsed_lines: list[tuple[re.Match, re.Match]] = []
    for raw in lines:
        match = SCHED_RE.match(raw)
        if not match:
            continue
        next_match = NEXT_RE.search(raw)
        if next_match:
            parsed_lines.append((match, next_match))
        if match.group("tgid") == target:
            target_tids.add(match.group("tid"))

    for match, next_match in parsed_lines:
        if match.group("tid") not in target_tids and match.group("prev_pid") not in target_tids \
                and next_match.group("next_pid") not in target_tids:
            continue
        events.append({
            "ts": float(match.group("ts")),
            "tid": match.group("tid"),
            "cpu": int(match.group("cpu")),
            "prevState": match.group("prev_state"),
            "prevPid": match.group("prev_pid"),
            "nextPid": next_match.group("next_pid"),
            "nextComm": next_match.group("next_comm"),
        })

    events.sort(key=lambda item: item["ts"])

    last_off: dict[str, dict] = {}
    intervals: list[dict] = []
    for event in events:
        prev_pid = event["prevPid"]
        next_pid = event["nextPid"]
        if prev_pid in target_tids:
            last_off[prev_pid] = event
        if next_pid in last_off:
            previous = last_off.pop(next_pid)
            duration_ms = max(0.0, (event["ts"] - previous["ts"]) * 1000.0)
            intervals.append({
                "tid": next_pid,
                "durationMs": duration_ms,
                "state": previous["prevState"],
                "nextComm": previous["nextComm"],
                "cpu": previous["cpu"],
            })

    def stats(rows: list[dict]) -> dict:
        durations = sorted(float(row["durationMs"]) for row in rows)
        if not durations:
            return {"count": 0}
        quantile = lambda p: durations[min(len(durations) - 1, int((len(durations) - 1) * p))]
        return {
            "count": len(durations),
            "meanMs": round(mean(durations), 3),
            "medianMs": round(median(durations), 3),
            "p95Ms": round(quantile(0.95), 3),
            "p99Ms": round(quantile(0.99), 3),
            "maxMs": round(durations[-1], 3),
            "over16Ms": sum(value > 16.0 for value in durations),
            "over33Ms": sum(value > 33.0 for value in durations),
            "over100Ms": sum(value > 100.0 for value in durations),
        }

    main = [row for row in intervals if row["tid"] == target]
    by_state = Counter(row["state"] for row in main if row["durationMs"] > 33.0)
    by_next = Counter(row["nextComm"] for row in main if row["durationMs"] > 33.0)
    return {
        "targetPid": target_pid,
        "targetThreadCount": len(target_tids),
        "matchedEvents": len(events),
        "mainThread": stats(main),
        "allProcessThreads": stats(intervals),
        "mainLongWaitStates": dict(by_state),
        "mainLongWaitNextTasks": dict(by_next.most_common(20)),
        "note": "S means voluntary sleep/wait, D is uninterruptible sleep, R is runnable/preempted; this is scheduler evidence, not Java stack causality.",
    }


def parse_jvm_gc(path: Path) -> dict:
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    pauses = []
    explicit = 0
    for match in re.finditer(r"\[GC pause \(([^\]]+)\).*?,\s*([0-9.]+) secs\]", text):
        pauses.append(float(match.group(2)) * 1000.0)
        explicit += int("System.gc" in match.group(1))
    return {
        "pauseCount": len(pauses),
        "explicitPauseCount": explicit,
        "maxPauseMs": round(max(pauses), 3) if pauses else 0.0,
        "p95PauseMs": round(sorted(pauses)[min(len(pauses) - 1, int((len(pauses) - 1) * 0.95))], 3) if pauses else 0.0,
    }


def build_report(root: Path) -> dict:
    incidents = load_jsonl(root / "frame-probe-incidents.jsonl")
    target_pid = None
    samples = root / "process-samples.tsv"
    if samples.is_file():
        for line in samples.read_text(encoding="utf-8", errors="replace").splitlines()[1:]:
            fields = line.split("\t")
            if len(fields) > 1 and fields[1].isdigit():
                target_pid = int(fields[1])
                break
    long_incidents = [row for row in incidents if float(row.get("totalMs", 0.0)) > 33.0]
    report = {
        "frameProbe": {
            "incidentCount": len(incidents),
            "longIncidentCount": len(long_incidents),
            "maxTotalMs": max((float(row.get("totalMs", 0.0)) for row in incidents), default=0.0),
            "maxRenderMs": max((float(row.get("renderMs", 0.0)) for row in incidents), default=0.0),
        },
        "gfxinfo": parse_gfxinfo(root / "gfxinfo-framestats.txt"),
        "scheduler": parse_sched(root / "atrace.txt", target_pid),
        "jvmGc": parse_jvm_gc(root / "jvm_gc.log"),
        "interpretation": [
            "Java trace explains only time while the game thread is executing Java; scheduler and gfxinfo are needed for native/driver/VSync waits.",
            "A long scheduler wait in state D/S near a long frame is evidence against a purely Java method hotspot.",
            "GPU percentile and issue-draw metrics are Android graphics-pipeline evidence, not direct GPU-driver call stacks.",
        ],
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", default="")
    args = parser.parse_args()
    report = build_report(Path(args.input))
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        Path(args.output).write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
