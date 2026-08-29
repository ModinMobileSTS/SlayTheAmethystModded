"""
perf-bench: run a full autoplay dungeon run and produce a structured
performance report from the frame-probe incidents file.

Flow
----
1. Install APK (unless -SkipInstall).
2. Launch in normal autoplay mode (full dungeon, floor 1 onward).
3. Poll until the :game process exits or timeout expires.
4. Pull frame-probe-incidents.jsonl from the device.
5. Analyse: compute p50/p95/p99/max totalMs, renderMs, swapMs;
   count flush-spike frames; count GC-stall frames.
6. Compare against perf_bench_baseline.json (15% tolerance).
7. Print formatted summary to stdout.
8. Write perf-result.json to the output directory.
9. Always return 0 (regressions are informational, not build errors).

async-profiler / flamegraph
---------------------------
libasyncProfiler.so crashes the JVM on some Android kernels during
System.load() initialisation (SI_TKILL / abort via perf_event_open
denial). Automated profiling is therefore NOT integrated here.

To capture a flamegraph manually after reproducing a regression:
  1. python scripts/tools/main.py sts-harness -Command single-room \
       -SingleRoomCharacter IRONCLAD -SingleRoomMonster Lagavulin \
       -SingleRoomCards Strike_R,Strike_R,Defend_R,Defend_R,Bash
  2. In Arthas shell (via scripts/tools/arthas/manager.py):
       profiler start -e itimer -d 30 --format html -f /sdcard/flame.html
  3. adb pull /sdcard/flame.html .
"""

from __future__ import annotations

import json
import math
import time as _time
from pathlib import Path
from typing import Any

from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._device import (
    clear_runtime_signals,
    device_logcat_timestamp,
    harness_logcat_dump,
    start_logcat_capture,
    stop_logcat_capture,
)
from scripts.tools.harness._runner import adb
from scripts.tools.harness._status import harness_status
from scripts.tools.harness.install import run_install
from scripts.tools.harness.run import run_start

DEFAULT_CHARACTER = "IRONCLAD"

FALLBACK_THRESHOLDS: dict[str, Any] = {
    "total_p99_ms": 50.0,
    "total_p95_ms": 25.0,
    "render_p99_ms": 48.0,
    "render_p95_ms": 24.0,
    "flush_spike_frames": 50,
    "gc_stall_frames": 20,
    "gc_stall_max_ms": 300.0,
}

INCIDENTS_FILE_NAME = "frame-probe-incidents.jsonl"
PERF_RESULT_FILE_NAME = "perf-result.json"
REGRESSION_TOLERANCE = 0.15


# ---------------------------------------------------------------------------
# Public entry point
# ---------------------------------------------------------------------------

def run_perf_bench(ctx: HarnessContext, resolved_out_dir: Path) -> int:
    logcat_capture: Any = None
    logcat_since = ""
    tracer: Any = None
    tracer_collected = False
    try:
        # ── 1. Optionally install ─────────────────────────────────────────
        if not ctx.options.skip_install:
            run_install(ctx)

        # ── 2. Configure normal autoplay (full dungeon run from floor 1) ──
        ctx.options.autoplay = True
        ctx.options.autoplay_mode = "normal"
        ctx.options.autoplay_save_mode = "fresh"
        ctx.result["autoplay"] = True
        ctx.result["autoplayMode"] = ctx.options.autoplay_mode
        ctx.result["autoplaySaveMode"] = ctx.options.autoplay_save_mode
        if not ctx.options.single_room_character.strip():
            char = getattr(ctx.options, "perf_bench_character", "").strip()
            ctx.options.single_room_character = char or DEFAULT_CHARACTER

        # ── 3. Start game ─────────────────────────────────────────────────
        clear_runtime_signals(ctx)
        logcat_since = device_logcat_timestamp(ctx)
        try:
            logcat_capture = start_logcat_capture(ctx, resolved_out_dir, logcat_since)
        except Exception as exc:
            ctx.result.setdefault("artifacts", {})["harnessLogcatError"] = str(exc)

        # Use the dedicated autoplay task. It force-stops a stale launcher/game
        # process and always launches through MTS, which is required by the
        # bundled autoplay driver.
        run_start(ctx, resolved_out_dir, use_autoplay_task=True)

        # ── 4. Poll until :game process exits or timeout ──────────────────
        deadline = _time.monotonic() + max(1, ctx.options.timeout_seconds)
        status = None
        saw_game = False
        tracer_started = False

        while True:
            status = harness_status(ctx)
            obs = status.get("observedState", "")
            game_pid = status.get("processes", {}).get("game", "").strip()
            if obs in ("FAIL", "CRASH_MARKER", "LOGCAT_CRASH"):
                break
            if game_pid:
                saw_game = True
                # MTS is still defining patched classes while the game PID first
                # appears. Retransforming SpriteBatch before READY can split its
                # libGDX types across class loaders, so defer Arthas until boot.
                if (
                    obs == "READY"
                    and not tracer_started
                    and ctx.connector is not None
                    and getattr(ctx.options, "perf_bench_enable_profiler", False)
                ):
                    tracer_started = True
                    try:
                        from scripts.tools.harness.perf_trace import start_tracer
                        trace_dir = resolved_out_dir / "arthas-trace"
                        trace_duration = max(
                            30.0,
                            min(180.0, max(1, ctx.options.timeout_seconds) * 0.6),
                        )
                        tracer = start_tracer(
                            connector=ctx.connector,
                            device_serial=ctx.resolved_device_serial,
                            out_dir=trace_dir,
                            duration_s=trace_duration,
                        )
                        ctx.result.setdefault("artifacts", {})["traceDir"] = str(trace_dir)
                    except Exception as exc:
                        ctx.result.setdefault("artifacts", {})["tracerError"] = str(exc)
            elif saw_game:
                break
            if _time.monotonic() >= deadline:
                break
            _time.sleep(ctx.options.poll_interval_seconds)

        ctx.result["statusSnapshot"] = status

        observed = (status or {}).get("observedState", "")
        if observed in ("FAIL", "CRASH_MARKER", "LOGCAT_CRASH"):
            set_result_success(ctx, False, observed,
                               f"perf-bench: game failed during startup ({observed})")
            _print_error_summary(f"Game failed during startup: {observed}")
            return 0

        if not saw_game:
            logcat_artifact = ctx.result.get("artifacts", {}).get("harnessLogcat", "")
            message = "perf-bench: game process did not appear before the benchmark timeout"
            if logcat_artifact:
                message = f"{message}. Logcat: {logcat_artifact}"
            set_result_success(ctx, False, "START_TIMEOUT", message)
            _print_error_summary(message)
            return 0

        # ── 5. Pull incidents file ────────────────────────────────────────
        local_incidents = resolved_out_dir / INCIDENTS_FILE_NAME
        _pull_incidents(ctx, local_incidents)
        ctx.result.setdefault("artifacts", {})["incidentsFile"] = str(local_incidents)

        # ── 6. Analyse ────────────────────────────────────────────────────
        if not local_incidents.is_file() or local_incidents.stat().st_size == 0:
            set_result_success(ctx, True, "NO_INCIDENTS",
                               "perf-bench: incidents file not found or empty; "
                               "make sure performanceDeepDiagnostics is enabled.")
            _print_error_summary(
                "No incidents file found.\n"
                "  Enable: Developer settings -> Deep performance diagnostics"
            )
            return 0

        metrics = _analyse(local_incidents)
        ctx.result["metrics"] = metrics

        # ── 7. Load baseline and compare ──────────────────────────────────
        baseline_path = _resolve_baseline_path(ctx)
        if baseline_path.is_file():
            thresholds = json.loads(baseline_path.read_text(encoding="utf-8"))
        else:
            thresholds = FALLBACK_THRESHOLDS.copy()
            ctx.result["baselineNote"] = (
                f"Baseline not found at {baseline_path}; using built-in defaults."
            )

        regressions = _compare(metrics, thresholds)
        ctx.result["regressions"] = regressions
        ctx.result["thresholds"] = thresholds

        # ── 8. Optionally update baseline ─────────────────────────────────
        if getattr(ctx.options, "perf_bench_update_baseline", False):
            _write_baseline(baseline_path, metrics)
            ctx.result["baselineUpdated"] = str(baseline_path)

        # ── 9. Write perf-result.json ─────────────────────────────────────
        perf_result_path = resolved_out_dir / PERF_RESULT_FILE_NAME
        perf_result_path.write_text(
            json.dumps({"metrics": metrics, "thresholds": thresholds,
                        "regressions": regressions}, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        ctx.result.setdefault("artifacts", {})["perfResult"] = str(perf_result_path)

        # ── 10. Collect tracer report (join with 30s grace) ───────────────
        trace_report = ""
        if tracer is not None:
            try:
                from scripts.tools.harness.perf_trace import collect_tracer_report
                trace_report, trace_report_path = collect_tracer_report(
                    tracer,
                    resolved_out_dir / "arthas-trace",
                    join_timeout=30.0,
                )
                ctx.result.setdefault("artifacts", {})["flushSpikeReport"] = str(trace_report_path)
                tracer_collected = True
            except Exception as exc:
                ctx.result.setdefault("artifacts", {})["tracerCollectError"] = str(exc)

        # ── 11. Set result and print summary ──────────────────────────────
        success = len(regressions) == 0
        status_key = "PERF_PASS" if success else "PERF_REGRESSION"
        set_result_success(ctx, True, status_key, _metrics_summary(metrics))
        _print_summary(metrics, thresholds, regressions, perf_result_path)
        if trace_report:
            print(trace_report, flush=True)
        return 0

    finally:
        try:
            _stop_game(ctx)
        except Exception:
            pass
        if tracer is not None and not tracer_collected:
            try:
                from scripts.tools.harness.perf_trace import collect_tracer_report
                _, trace_report_path = collect_tracer_report(
                    tracer,
                    resolved_out_dir / "arthas-trace",
                    join_timeout=10.0,
                )
                ctx.result.setdefault("artifacts", {})[
                    "flushSpikeReport"
                ] = str(trace_report_path)
            except Exception as exc:
                ctx.result.setdefault("artifacts", {})["tracerCollectError"] = str(exc)
        try:
            if logcat_capture is not None:
                stop_logcat_capture(ctx, logcat_capture)
            elif logcat_since.strip():
                try:
                    harness_logcat_dump(ctx, resolved_out_dir, logcat_since)
                except Exception:
                    pass
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _stop_game(ctx: HarnessContext) -> None:
    from scripts.tools.harness._runner import gradle
    device_args = (
        [f"-PdeviceSerial={ctx.resolved_device_serial}"]
        if ctx.resolved_device_serial.strip() else []
    )
    try:
        gradle(ctx, [":app:stsStop", *device_args], timeout_seconds=30)
    except Exception:
        pass


def _resolve_baseline_path(ctx: HarnessContext) -> Path:
    explicit = getattr(ctx.options, "perf_bench_baseline", "").strip()
    if explicit:
        return Path(explicit)
    return ctx.repo_root / "scripts" / "tools" / "harness" / "perf_bench_baseline.json"


def _pull_incidents(ctx: HarnessContext, local_path: Path) -> None:
    from scripts.tools.harness._device import resolve_device_sts_root
    try:
        sts_root = resolve_device_sts_root(ctx)
        remote = f"{sts_root['root']}/{INCIDENTS_FILE_NAME}"
        adb(ctx, ["pull", remote, str(local_path)], timeout_seconds=120, allow_failure=True)
    except Exception:
        pass


def _analyse(incidents_path: Path) -> dict[str, Any]:
    incidents: list[dict] = []
    for raw in incidents_path.read_text(encoding="utf-8", errors="replace").splitlines():
        raw = raw.strip()
        if not raw:
            continue
        try:
            incidents.append(json.loads(raw))
        except Exception:
            pass

    if not incidents:
        return {"frame_count": 0}

    totals   = [x["totalMs"] for x in incidents]
    renders  = [x.get("renderMs", 0.0) for x in incidents]
    swaps    = [x.get("swapMs", 0.0) for x in incidents]
    flushes  = [x.get("flushes", 0) for x in incidents]

    inc_sorted = sorted(incidents, key=lambda x: x.get("frame", 0))
    gc_stall_frames = sum(
        1 for prev, curr in zip(inc_sorted, inc_sorted[1:])
        if prev.get("heapMb", 0) - curr.get("heapMb", 0) > 30
        and curr["totalMs"] > 20
    )
    gc_stall_max = max(
        (curr["totalMs"]
         for prev, curr in zip(inc_sorted, inc_sorted[1:])
         if prev.get("heapMb", 0) - curr.get("heapMb", 0) > 30
         and curr["totalMs"] > 20),
        default=0.0,
    )

    def pct(lst: list[float], p: float) -> float:
        if not lst:
            return 0.0
        s = sorted(lst)
        idx = min(int(math.ceil(len(s) * p / 100.0)) - 1, len(s) - 1)
        return s[max(idx, 0)]

    return {
        "frame_count":         len(incidents),
        "total_p50_ms":        round(pct(totals, 50), 2),
        "total_p95_ms":        round(pct(totals, 95), 2),
        "total_p99_ms":        round(pct(totals, 99), 2),
        "total_max_ms":        round(max(totals), 2),
        "render_p50_ms":       round(pct(renders, 50), 2),
        "render_p95_ms":       round(pct(renders, 95), 2),
        "render_p99_ms":       round(pct(renders, 99), 2),
        "render_max_ms":       round(max(renders), 2),
        "swap_p99_ms":         round(pct(swaps, 99), 2),
        "swap_max_ms":         round(max(swaps), 2),
        "flush_p95":           int(pct(flushes, 95)),
        "flush_max":           int(max(flushes)),
        "flush_spike_frames":  sum(1 for f in flushes if f > 800),
        "gc_stall_frames":     gc_stall_frames,
        "gc_stall_max_ms":     round(gc_stall_max, 2),
        "severe_frame_count":  sum(1 for t in totals if t > 33),
        "severe_frame_pct":    round(100.0 * sum(1 for t in totals if t > 33) / len(totals), 2),
    }


def _compare(metrics: dict, thresholds: dict) -> list[dict]:
    regressions = []
    for key, threshold in thresholds.items():
        if key not in metrics:
            continue
        value = metrics[key]
        if isinstance(threshold, (int, float)) and isinstance(value, (int, float)):
            limit = threshold * (1.0 + REGRESSION_TOLERANCE)
            if value > limit:
                regressions.append({
                    "metric":    key,
                    "value":     value,
                    "threshold": threshold,
                    "limit":     round(limit, 2),
                })
    return regressions


def _write_baseline(path: Path, metrics: dict) -> None:
    baseline = {k: v for k, v in metrics.items() if k in FALLBACK_THRESHOLDS}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(baseline, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")


def _print_error_summary(msg: str) -> None:
    w = 62
    print("\n" + "=" * w)
    print("  perf-bench  [ERROR]")
    print("-" * w)
    for line in msg.splitlines():
        print(f"  {line}")
    print("=" * w + "\n", flush=True)


def _print_summary(
    metrics: dict,
    thresholds: dict,
    regressions: list,
    result_path: Path,
) -> None:
    reg_keys = {r["metric"] for r in regressions}
    w = 62

    lines = []
    lines.append("=" * w)
    label = "PASS" if not regressions else f"REGRESSION  ({len(regressions)} items)"
    lines.append(f"  perf-bench  [{label}]")
    lines.append("-" * w)

    lines.append("  Frame timing")
    for key, disp in [
        ("total_p50_ms",  "p50 total"),
        ("total_p95_ms",  "p95 total"),
        ("total_p99_ms",  "p99 total"),
        ("total_max_ms",  "max total"),
        ("render_p99_ms", "p99 render"),
        ("swap_p99_ms",   "p99 swap"),
    ]:
        val = metrics.get(key)
        if val is None:
            continue
        thr = thresholds.get(key)
        flag = "  ✗" if key in reg_keys else ""
        thr_s = f"  (limit {thr:.0f} ms)" if thr is not None else ""
        lines.append(f"    {disp:<18} {val:>7.1f} ms{thr_s}{flag}")

    lines.append("")
    lines.append("  Jank")
    for key, disp, unit in [
        ("severe_frame_count", ">33ms frames",       ""),
        ("severe_frame_pct",   ">33ms pct",          " %"),
        ("flush_spike_frames", "flush spike frames", ""),
        ("gc_stall_frames",    "GC stall frames",    ""),
        ("gc_stall_max_ms",    "GC stall max",       " ms"),
    ]:
        val = metrics.get(key)
        if val is None:
            continue
        thr = thresholds.get(key)
        flag = "  ✗" if key in reg_keys else ""
        thr_s = f"  (limit {thr})" if thr is not None else ""
        lines.append(f"    {disp:<26} {val:>7.1f}{unit}{thr_s}{flag}")

    lines.append("")
    lines.append("  SpriteBatch")
    for key, disp in [("flush_p95", "flush p95"), ("flush_max", "flush max")]:
        val = metrics.get(key)
        if val is not None:
            lines.append(f"    {disp:<26} {val:>7}")

    lines.append("")
    lines.append(f"  Frames analysed: {metrics.get('frame_count', 0)}")
    lines.append(f"  Result file:     {result_path}")

    if regressions:
        lines.append("")
        lines.append("  Regressions:")
        for r in regressions:
            lines.append(
                f"    ✗  {r['metric']:<30} "
                f"got {r['value']:.1f}  limit {r['limit']:.1f}"
            )
        lines.append("")
        lines.append("  Flame graph (manual — see module docstring for instructions):")
        lines.append("    python scripts/tools/main.py sts-harness -Command single-room \\")
        lines.append("      -SingleRoomCharacter IRONCLAD -SingleRoomMonster Lagavulin \\")
        lines.append("      -SingleRoomCards Strike_R,Strike_R,Defend_R,Defend_R,Bash")

    lines.append("=" * w)
    print("\n" + "\n".join(lines) + "\n", flush=True)


def _metrics_summary(m: dict) -> str:
    return (
        f"frames={m.get('frame_count')} "
        f"total_p99={m.get('total_p99_ms')}ms "
        f"render_p99={m.get('render_p99_ms')}ms "
        f"flush_spike={m.get('flush_spike_frames')} "
        f"gc_stall={m.get('gc_stall_frames')}"
    )
