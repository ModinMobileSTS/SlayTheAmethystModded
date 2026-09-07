#!/usr/bin/env python3
"""Offline Android spike collector and analyzer.

The collector deliberately uses only adb shell tools. No network, root, or
Perfetto UI is required on the device. Raw traces are pulled to the host and
the analyzer produces a JSON/Markdown report locally.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


DEFAULT_CATEGORIES = (
    "sched freq idle gfx view dalvik binder_driver disk memory thermal power wm am"
)
DEFAULT_PACKAGE = "io.stamethyst:game"
PERFETTO_CONFIG = """
buffers: { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "power/cpu_frequency_switch_start"
      ftrace_events: "power/cpu_frequency_switch_end"
    }
  }
}
data_sources: { config { name: "linux.process_stats" } }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
data_sources: {
  config {
    name: "track_event"
    track_event_config {
      enabled_categories: "gfx"
      enabled_categories: "view"
      enabled_categories: "android.gfx"
      enabled_categories: "android.view"
    }
  }
}
""".strip()


def perfetto_config_for_duration(duration_seconds: float) -> str:
    return f"duration_ms: {max(1, int(duration_seconds * 1000))}\n{PERFETTO_CONFIG}\n"

PERFETTO_SQL = """
SELECT 'long_slice' AS metric, s.ts, s.dur, s.name, s.category,
       t.tid, t.name AS thread_name, p.pid, p.name AS process_name
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
LEFT JOIN process p ON t.upid = p.upid
WHERE s.dur >= 33000000
  AND (p.name LIKE 'io.stamethyst%' OR t.name = 'LWJGL Application')
ORDER BY s.dur DESC
LIMIT 100;

SELECT 'thread_state' AS metric, st.state,
       COUNT(*) AS slices, SUM(st.dur) AS total_ns, MAX(st.dur) AS max_ns
FROM thread_state st
JOIN thread t USING (utid)
LEFT JOIN process p USING (upid)
WHERE p.name LIKE 'io.stamethyst%' OR t.name = 'LWJGL Application'
GROUP BY st.state
ORDER BY total_ns DESC;

SELECT 'sched' AS metric, COUNT(*) AS slices, SUM(s.dur) AS running_ns,
       MAX(s.dur) AS max_running_ns
FROM sched_slice s
JOIN thread t USING (utid)
LEFT JOIN process p USING (upid)
WHERE p.name LIKE 'io.stamethyst%' OR t.name = 'LWJGL Application';
""".strip()


def run_adb(serial: str, args: list[str], *, timeout: float = 30.0, input_text: str | None = None) -> str:
    result = subprocess.run(
        ["adb", "-s", serial, *args],
        input=input_text,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed ({result.returncode}): {result.stdout.strip()}")
    return result.stdout


def run_adb_bytes(serial: str, args: list[str], *, timeout: float = 90.0) -> bytes:
    result = subprocess.run(
        ["adb", "-s", serial, *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed ({result.returncode})")
    return result.stdout


def run_adb_allow_failure(serial: str, args: list[str], *, timeout: float = 30.0) -> str:
    try:
        return run_adb(serial, args, timeout=timeout)
    except Exception as exc:
        return f"[command failed] {exc}\n"


def device_shell(serial: str, command: str, *, timeout: float = 30.0) -> str:
    return run_adb(serial, ["shell", "sh", "-c", command], timeout=timeout)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", errors="replace")


def collect(args: argparse.Namespace) -> int:
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    run_adb(args.device, ["get-state"], timeout=10.0)
    started_epoch_ms = int(time.time() * 1000)
    boot_seconds = run_adb_allow_failure(args.device, ["shell", "cat", "/proc/uptime"], timeout=10.0)
    try:
        boot_time_epoch_ms = started_epoch_ms - int(float(boot_seconds.split()[0]) * 1000)
    except (ValueError, IndexError):
        boot_time_epoch_ms = None

    write_text(output / "device-info.txt", "\n".join([
        run_adb_allow_failure(args.device, ["shell", "getprop"]),
        run_adb_allow_failure(args.device, ["shell", "dumpsys", "display"]),
        run_adb_allow_failure(args.device, ["shell", "dumpsys", "SurfaceFlinger"]),
    ]))
    write_text(output / "gfxinfo-before.txt", run_adb_allow_failure(
        args.device, ["shell", "dumpsys", "gfxinfo", args.package, "reset"]
    ))

    categories = args.categories or DEFAULT_CATEGORIES
    remote_perfetto = f"/data/misc/perfetto-traces/sts-spike-{int(time.time())}.pftrace"
    perfetto_args = ["shell", "perfetto", "--background-wait", "--txt", "-c", "-", "-o", remote_perfetto]
    trace_error = ""
    try:
        perfetto_pid = run_adb(
            args.device,
            perfetto_args,
            timeout=30.0,
            input_text=perfetto_config_for_duration(args.duration),
        ).strip()
    except Exception as exc:
        perfetto_pid = ""
        trace_error = str(exc)

    samples_path = output / "process-samples.tsv"
    write_text(samples_path, "epoch_ms\tpid\ttotal_pss_kb\tnative_pss_kb\tdalvik_pss_kb\tgraphics_pss_kb\tjava_heap_kb\tnative_heap_kb\ttemperature_tenth_c\tbattery_level\tbattery_voltage_mv\n")
    deadline = time.monotonic() + max(1, args.duration)
    while time.monotonic() < deadline:
        epoch_ms = int(time.time() * 1000)
        pid = run_adb_allow_failure(args.device, ["shell", "pidof", args.package], timeout=10).strip()
        if pid.startswith("[command failed]"):
            pid = ""
        mem = run_adb_allow_failure(args.device, ["shell", "dumpsys", "meminfo", args.package], timeout=15)
        battery = run_adb_allow_failure(args.device, ["shell", "dumpsys", "battery"], timeout=10)
        def field(pattern: str, text: str) -> str:
            match = re.search(pattern, text, re.MULTILINE)
            return match.group(1) if match else ""
        row = [
            str(epoch_ms), pid,
            field(r"^\s*TOTAL\s+(\d+)", mem),
            field(r"^\s*Native Heap\s+(\d+)", mem),
            field(r"^\s*Dalvik Heap\s+(\d+)", mem),
            field(r"^\s*Graphics\s*:?\s+(\d+)", mem),
            field(r"^\s*Java Heap:\s+(\d+)", mem),
            field(r"^\s*Native Heap:\s+(\d+)", mem),
            field(r"^\s*temperature:\s*(\d+)", battery),
            field(r"^\s*level:\s*(\d+)", battery),
            field(r"^\s*voltage:\s*(\d+)", battery),
        ]
        with samples_path.open("a", encoding="utf-8") as handle:
            handle.write("\t".join(row) + "\n")
        time.sleep(max(0.25, args.sample_interval))

    # Perfetto writes the trace on-device and exits after the configured duration.
    # Pulling a finished file avoids blocking atrace async_dump on large buffers.
    try:
        if perfetto_pid:
            # --background-wait returns after data sources start, not after the
            # configured duration. Wait for the output file before pulling it.
            file_deadline = time.monotonic() + max(15.0, args.duration + 15.0)
            while time.monotonic() < file_deadline:
                probe = run_adb_allow_failure(
                    args.device,
                    ["shell", "test", "-s", remote_perfetto],
                    timeout=10.0,
                )
                if not probe.startswith("[command failed]"):
                    break
                time.sleep(1.0)
        run_adb(args.device, ["pull", remote_perfetto, str(output / "device-trace.pftrace")], timeout=120.0)
        run_adb_allow_failure(args.device, ["shell", "rm", "-f", remote_perfetto])
    except Exception as exc:
        write_text(output / "perfetto-pull-error.txt", str(exc) + "\n")
    write_text(output / "gfxinfo-after.txt", run_adb_allow_failure(
        args.device, ["shell", "dumpsys", "gfxinfo", args.package, "framestats"]
    ))
    write_text(output / "jvm-gc.txt", run_adb_allow_failure(
        args.device, ["shell", "cat", "/sdcard/Android/data/io.stamethyst/files/sts/jvm_gc.log"]
    ))
    write_text(output / "cpuinfo.txt", run_adb_allow_failure(args.device, ["shell", "dumpsys", "cpuinfo"]))
    write_text(output / "meminfo-final.txt", run_adb_allow_failure(
        args.device, ["shell", "dumpsys", "meminfo", args.package]
    ))
    write_text(output / "battery-final.txt", run_adb_allow_failure(args.device, ["shell", "dumpsys", "battery"]))
    write_text(output / "game-log.txt", run_adb_allow_failure(
        args.device, ["shell", "tail", "-n", "2000", "/sdcard/Android/data/io.stamethyst/files/sts/latest.log"]
    ))
    try:
        incident_bytes = run_adb_bytes(
            args.device,
            ["exec-out", "cat", "/sdcard/Android/data/io.stamethyst/files/sts/frame-probe-incidents.jsonl"],
            timeout=30.0,
        )
        all_incidents = []
        for line in incident_bytes.decode("utf-8", errors="replace").splitlines():
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            timestamp = item.get("t")
            if isinstance(timestamp, (int, float)) and started_epoch_ms <= timestamp <= int(time.time() * 1000):
                all_incidents.append(item)
        write_text(
            output / "frame-probe-incidents.jsonl",
            "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in all_incidents),
        )
    except Exception as exc:
        write_text(output / "frame-probe-pull-error.txt", str(exc) + "\n")
    write_text(output / "collection.json", json.dumps({
        "device": args.device,
        "package": args.package,
        "startedEpochMs": started_epoch_ms,
        "endedEpochMs": int(time.time() * 1000),
        "bootTimeEpochMs": boot_time_epoch_ms,
        "durationSeconds": args.duration,
        "categories": categories,
        "perfettoConfig": perfetto_config_for_duration(args.duration),
        "bufferKb": args.buffer_kb,
        "traceStartError": trace_error,
        "perfettoPid": perfetto_pid,
        "remotePerfettoPath": remote_perfetto,
        "offline": True,
    }, indent=2))
    return 0


def parse_incidents(path: Path) -> list[dict[str, Any]]:
    incidents = []
    if not path.is_file():
        return incidents
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            value = json.loads(line)
            if isinstance(value, dict) and isinstance(value.get("t"), (int, float)):
                incidents.append(value)
        except json.JSONDecodeError:
            continue
    return incidents


def parse_samples(path: Path) -> list[dict[str, float]]:
    if not path.is_file():
        return []
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    if not lines:
        return []
    keys = lines[0].split("\t")
    result = []
    for line in lines[1:]:
        values = line.split("\t")
        row: dict[str, float] = {}
        for key, value in zip(keys, values):
            try:
                row[key] = float(value)
            except ValueError:
                continue
        result.append(row)
    return result


def parse_atrace(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    lines = text.splitlines()
    sched_switches = sum("sched_switch" in line for line in lines)
    gfx_lines = [line for line in lines if "Choreographer" in line or "doFrame" in line or "actual_frame_timeline_slice" in line]
    gc_lines = [line for line in lines if "GC_" in line or "gc pause" in line.lower() or "dvm_gc" in line.lower()]
    return {
        "bytes": len(text.encode("utf-8")),
        "lines": len(lines),
        "schedSwitchEvents": sched_switches,
        "gfxTimelineLines": len(gfx_lines),
        "gcLines": len(gc_lines),
        "traceHeader": lines[0] if lines else "",
    }


def parse_gfxinfo(path: Path) -> dict[str, Any]:
    """Parse Android framestats when the surface reports them."""
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    rows = []
    in_profile = False
    for line in text.splitlines():
        if line.strip() == "---PROFILEDATA---":
            in_profile = not in_profile
            continue
        if not in_profile or not line.strip() or line.startswith("Flags,"):
            continue
        fields = line.split(",")
        if len(fields) < 18:
            continue
        try:
            intended = int(fields[2])
            completed = int(fields[17])
            if intended > 0 and completed > intended:
                rows.append((completed - intended) / 1_000_000.0)
        except ValueError:
            continue
    return {
        "available": bool(rows),
        "profileRows": len(rows),
        "durationP95Ms": percentile(rows, .95),
        "durationP99Ms": percentile(rows, .99),
        "durationMaxMs": max(rows, default=None),
        "note": "No Android surface framestats were reported" if not rows else "Parsed PROFILEDATA rows",
    }


def parse_gc_log(path: Path) -> dict[str, Any]:
    """Extract pause durations without confusing concurrent phases for pauses."""
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    pauses = []
    explicit = []
    for line in text.splitlines():
        match = re.search(r"GC pause \(([^)]*)\).*?,\s*([0-9.]+) secs\)", line)
        if not match:
            match = re.search(r"GC pause \(([^)]*)\).*?,\s*([0-9.]+) secs", line)
        if match:
            duration_ms = float(match.group(2)) * 1000.0
            pauses.append(duration_ms)
            if "System.gc" in match.group(1):
                explicit.append(duration_ms)
    return {
        "available": bool(pauses),
        "pauseCount": len(pauses),
        "explicitPauseCount": len(explicit),
        "pauseP95Ms": percentile(pauses, .95),
        "pauseMaxMs": max(pauses, default=None),
        "explicitPauseMaxMs": max(explicit, default=None),
    }


def parse_gdx_summaries(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""
    summaries = []
    for line in text.splitlines():
        if "GpuResources summary reason=" not in line:
            continue
        fields = dict(re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^ ]+)", line))
        try:
            summaries.append({
                "reason": fields["reason"],
                "heapUsedMb": int(fields["heapUsedMb"]),
                "texturesLive": int(fields["texturesLive"]),
                "textureBytes": int(fields["textureBytes"]),
                "textureBytesPeak": int(fields["textureBytesPeak"]),
                "textureUploads": int(fields["textureUploads"]),
                "textureReleases": int(fields["textureReleases"]),
                "frameBuffersLive": int(fields["frameBuffersLive"]),
                "frameBufferBytes": int(fields["frameBufferBytes"]),
            })
        except (KeyError, ValueError):
            continue
    return {
        "summaryCount": len(summaries),
        "first": summaries[0] if summaries else None,
        "last": summaries[-1] if summaries else None,
        "maxTextureBytesPeak": max((item["textureBytesPeak"] for item in summaries), default=None),
        "maxHeapUsedMb": max((item["heapUsedMb"] for item in summaries), default=None),
    }


def find_trace_processor(explicit: str | None = None) -> str | None:
    candidates = [explicit, os.environ.get("TRACE_PROCESSOR_SHELL")]
    candidates.extend(("trace_processor_shell", "trace_processor"))
    for candidate in candidates:
        if not candidate:
            continue
        resolved = shutil.which(candidate) or (candidate if Path(candidate).is_file() else None)
        if resolved:
            return resolved
    return None


def run_perfetto_sql(trace_path: Path, processor: str | None) -> dict[str, Any]:
    if not trace_path.is_file():
        return {"available": False, "reason": "trace file missing"}
    if processor is None:
        return {
            "available": False,
            "reason": "trace_processor_shell not found",
            "sql": PERFETTO_SQL,
        }
    queries = [[processor, "query", str(trace_path), PERFETTO_SQL]]
    errors = []
    for command in queries:
        try:
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=120)
            if result.returncode != 0:
                errors.append(result.stdout[-1000:])
                continue
            raw = result.stdout.strip()
            sql_lines = [
                line for line in raw.splitlines()
                if not line.startswith("Loading trace:")
                and not line.startswith("[")
                and "Query execution time:" not in line
            ]
            raw = "\n".join(sql_lines).strip()
            try:
                rows = json.loads(raw)
            except json.JSONDecodeError:
                rows = raw.splitlines()
            if isinstance(rows, list):
                clean_rows = [
                    row for row in rows
                    if isinstance(row, str)
                    and row.startswith('"')
                    and not row.startswith('"metric"')
                ]
            else:
                clean_rows = rows
            return {
                "available": True,
                "processor": processor,
                "query": PERFETTO_SQL,
                "longSlices": clean_rows,
                "queryResultRows": len(clean_rows),
                "schedulerRowsAvailable": any(
                    '"sched",' in row and ',0,"[NULL]"' not in row
                    for row in clean_rows
                ),
            }
        except Exception as exc:
            errors.append(str(exc))
    return {"available": False, "processor": processor, "reason": "query failed", "errors": errors,
            "sql": PERFETTO_SQL}


def perfetto_metadata(path: Path, processor: str | None = None) -> dict[str, Any]:
    if not path.is_file():
        return {"available": False, "bytes": 0, "traceProcessor": processor or shutil.which("trace_processor_shell")}
    return {
        "available": True,
        "bytes": path.stat().st_size,
        "traceProcessor": processor or shutil.which("trace_processor_shell"),
        "analysis": "raw trace retained; install trace_processor_shell for SQL scheduling analysis",
    }


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    index = min(len(values) - 1, int((len(values) - 1) * fraction))
    return values[index]


def analyze(args: argparse.Namespace) -> int:
    root = Path(args.input).resolve()
    incidents = parse_incidents(root / "frame-probe-incidents.jsonl")
    samples = parse_samples(root / "process-samples.tsv")
    total = [float(row.get("totalMs", 0.0)) for row in incidents]
    render = [float(row.get("renderMs", 0.0)) for row in incidents]
    long_incidents = [row for row in incidents if float(row.get("totalMs", 0.0)) > args.long_frame_ms]
    gpu_upload_lines = []
    game_log = root / "game-log.txt"
    if game_log.is_file():
        gpu_upload_lines = [line for line in game_log.read_text(errors="replace").splitlines() if "texture_upload" in line.lower() or "uploadImageData" in line]
    trace_path = root / "device-trace.pftrace"
    processor = find_trace_processor(getattr(args, "trace_processor", None))
    report = {
        "input": str(root),
        "incidentCount": len(incidents),
        "longFrameCount": len(long_incidents),
        "frameTimingMs": {
            "totalP50": percentile(total, .50), "totalP95": percentile(total, .95),
            "totalP99": percentile(total, .99), "totalMax": max(total, default=None),
            "renderP50": percentile(render, .50), "renderP95": percentile(render, .95),
            "renderP99": percentile(render, .99), "renderMax": max(render, default=None),
        },
        "longFrames": sorted([
            {key: row.get(key) for key in ("t", "frame", "totalMs", "renderMs", "swapMs", "heapMb", "flushes", "switches", "room", "floor", "tag", "action")}
            for row in long_incidents
        ], key=lambda row: float(row.get("totalMs") or 0), reverse=True)[:args.top],
        "processSamples": {
            "count": len(samples),
            "totalP95Kb": percentile([x["total_pss_kb"] for x in samples if "total_pss_kb" in x], .95),
            "totalMaxKb": max((x["total_pss_kb"] for x in samples if "total_pss_kb" in x), default=None),
            "nativeMaxKb": max((x["native_pss_kb"] for x in samples if "native_pss_kb" in x), default=None),
            "dalvikMaxKb": max((x["dalvik_pss_kb"] for x in samples if "dalvik_pss_kb" in x), default=None),
            "temperatureMaxTenthC": max((x["temperature_tenth_c"] for x in samples if "temperature_tenth_c" in x), default=None),
        },
        "atrace": parse_atrace(root / "atrace.txt"),
        "gfxinfo": parse_gfxinfo(root / "gfxinfo-after.txt"),
        "jvmGc": parse_gc_log(root / "jvm-gc.txt"),
        "gdx": parse_gdx_summaries(game_log),
        "perfetto": perfetto_metadata(trace_path, processor),
        "perfettoSql": run_perfetto_sql(trace_path, processor),
        "textureUploadLogLines": len(gpu_upload_lines),
        "limitations": [
            "atrace and frame-probe use different clocks; this report does not claim per-frame causality",
            "gfxinfo framestats is a post-run Android UI timing view, not a GPU hardware counter",
            "battery current is omitted when the device denies power-supply sysfs access",
            "this device's Perfetto trace currently reports FrameTimeline but no sched_slice/thread_state; kernel scheduler tracing may be permission-limited",
        ],
    }
    (root / "spike-analysis.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    markdown = [
        "# Offline Spike Analysis",
        "",
        f"- incidents: {report['incidentCount']}",
        f"- long frames (>{args.long_frame_ms:g} ms): {report['longFrameCount']}",
        f"- total p95/p99/max: {report['frameTimingMs']['totalP95']} / {report['frameTimingMs']['totalP99']} / {report['frameTimingMs']['totalMax']} ms",
        f"- render p95/p99/max: {report['frameTimingMs']['renderP95']} / {report['frameTimingMs']['renderP99']} / {report['frameTimingMs']['renderMax']} ms",
        f"- sampled PSS p95/max: {report['processSamples']['totalP95Kb']} / {report['processSamples']['totalMaxKb']} KB",
        f"- atrace sched_switch events: {report['atrace']['schedSwitchEvents']}",
        f"- Perfetto trace bytes: {report['perfetto']['bytes']}",
        f"- Perfetto SQL available: {report['perfettoSql']['available']}",
        f"- JVM GC pause p95/max: {report['jvmGc']['pauseP95Ms']} / {report['jvmGc']['pauseMaxMs']} ms",
        f"- GDX texture bytes peak: {report['gdx']['maxTextureBytesPeak']}",
        f"- slow texture-upload log lines: {report['textureUploadLogLines']}",
        "",
        "## Long Frames",
    ]
    for row in report["longFrames"]:
        markdown.append(
            f"- frame {row.get('frame')}: {row.get('totalMs')} ms, render {row.get('renderMs')} ms, "
            f"flushes {row.get('flushes')}, room {row.get('room')}, action {row.get('action')}, tag {row.get('tag')}"
        )
    markdown.extend(["", "## Limits", *[f"- {item}" for item in report["limitations"]]])
    write_text(root / "spike-analysis.md", "\n".join(markdown) + "\n")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    collect_parser = subparsers.add_parser("collect")
    collect_parser.add_argument("--device", required=True)
    collect_parser.add_argument("--output", required=True)
    collect_parser.add_argument("--package", default=DEFAULT_PACKAGE)
    collect_parser.add_argument("--duration", type=float, default=180.0)
    collect_parser.add_argument("--sample-interval", type=float, default=2.0)
    collect_parser.add_argument("--buffer-kb", type=int, default=65536)
    collect_parser.add_argument("--categories", default=DEFAULT_CATEGORIES)
    collect_parser.set_defaults(handler=collect)
    analyze_parser = subparsers.add_parser("analyze")
    analyze_parser.add_argument("--input", required=True)
    analyze_parser.add_argument("--long-frame-ms", type=float, default=33.0)
    analyze_parser.add_argument("--top", type=int, default=30)
    analyze_parser.add_argument("--trace-processor", default=None,
                                help="Path to trace_processor_shell; also reads TRACE_PROCESSOR_SHELL")
    analyze_parser.set_defaults(handler=analyze)
    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
