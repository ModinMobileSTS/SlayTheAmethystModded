import json
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from scripts.tools.lib.sts_harness import limit_text, quote_android_shell, read_local_text_tail, utc_timestamp
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._device import (
    clear_runtime_signals,
    device_logcat_timestamp,
    harness_logcat_dump,
    remote_sts_path_state,
    remote_sts_root_script,
    resolve_device_sts_root,
    start_logcat_capture,
    stop_logcat_capture,
)
from scripts.tools.harness._runner import adb
from scripts.tools.harness._status import (
    extract_startup_cache_log_evidence,
    harness_status,
    update_status_harness_logcat,
    wait_harness_status,
)
from scripts.tools.harness.run import run_start, run_stop


def clear_startup_caches(ctx: HarnessContext) -> dict[str, Any]:
    sts_root = resolve_device_sts_root(ctx)
    quoted_sts_root = quote_android_shell(str(sts_root["root"]))
    external_script = f"""
cd {quoted_sts_root} || exit 1
rm -f .mts_classpath_cache .mts_patch_cache desktop-1.0-modded.jar mts_patch_cache_debug.log
rm -rf package mts_patch_cache
"""
    external_result = remote_sts_root_script(ctx, sts_root, external_script, timeout_seconds=20, allow_failure=True)
    private_script = """
rm -rf files/mts_patch_cache
rm -f files/sts/.mts_classpath_cache files/sts/.mts_patch_cache files/sts/desktop-1.0-modded.jar files/sts/mts_patch_cache_debug.log
rm -rf files/sts/package files/sts/mts_patch_cache
"""
    private_result = adb(
        ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", private_script],
        timeout_seconds=20, allow_failure=True,
    )
    summary = {
        "storage": sts_root,
        "externalExitCode": external_result.exit_code,
        "externalOutputTail": limit_text(external_result.output, 2000),
        "privateExitCode": private_result.exit_code,
        "privateOutputTail": limit_text(private_result.output, 2000),
    }
    ctx.operations.append({
        "command": "clear-startup-caches",
        "exitCode": 0 if external_result.exit_code == 0 and private_result.exit_code == 0 else 1,
        "startedAt": utc_timestamp(),
        "endedAt": utc_timestamp(),
        "durationMs": 0,
        "timedOut": False,
        "outputTail": json.dumps(summary, ensure_ascii=False),
    })
    return summary


def startup_cache_state(ctx: HarnessContext) -> dict[str, Any]:
    sts_root = resolve_device_sts_root(ctx)
    return {
        "storage": sts_root,
        "classpathMarker": remote_sts_path_state(ctx, sts_root, ".mts_classpath_cache"),
        "legacyExternalPatchMarker": remote_sts_path_state(ctx, sts_root, ".mts_patch_cache"),
        "legacyExternalPatchJar": remote_sts_path_state(ctx, sts_root, "desktop-1.0-modded.jar"),
        "legacyExternalPackageDir": remote_sts_path_state(ctx, sts_root, "package"),
    }


def _run_startup_cache_iteration(
    ctx: HarnessContext,
    phase_dir: Path,
    phase: str,
    index: int,
    *,
    clear_cache_before_run: bool,
) -> dict[str, Any]:
    phase_dir.mkdir(parents=True, exist_ok=True)
    phase_started = datetime.now(timezone.utc)
    phase_started_monotonic = time.monotonic()
    operation_start_index = len(ctx.operations)
    phase_timings: dict[str, int] = {}
    status: dict[str, Any] | None = None
    logcat_capture: Any = None
    logcat_since = ""
    start_requested = False
    clear_summary = None
    if clear_cache_before_run:
        step_start = time.monotonic()
        clear_summary = clear_startup_caches(ctx)
        phase_timings["clearStartupCachesMs"] = int((time.monotonic() - step_start) * 1000)
    step_start = time.monotonic()
    cache_before = startup_cache_state(ctx)
    phase_timings["cacheStateBeforeMs"] = int((time.monotonic() - step_start) * 1000)
    startup_request_monotonic: float | None = None
    try:
        step_start = time.monotonic()
        clear_runtime_signals(ctx)
        phase_timings["clearRuntimeSignalsMs"] = int((time.monotonic() - step_start) * 1000)
        logcat_since = device_logcat_timestamp(ctx)
        try:
            logcat_capture = start_logcat_capture(ctx, phase_dir, logcat_since)
        except Exception:
            pass
        startup_request_monotonic = time.monotonic()
        run_start(ctx)
        start_requested = True
        status = wait_harness_status(ctx, logcat_capture, timeout_seconds=ctx.options.timeout_seconds, poll_interval_seconds=2)
        ctx.result["statusSnapshot"] = status
    finally:
        if start_requested:
            try:
                run_stop(ctx)
            except Exception:
                pass
        if logcat_capture is not None:
            phase_timings["stopLogcatMs"] = int((time.monotonic() - (phase_started_monotonic + phase_timings.get("clearRuntimeSignalsMs", 0) / 1000)) * 1000)
            stop_logcat_capture(ctx, logcat_capture)
            update_status_harness_logcat(ctx, ctx.result.get("statusSnapshot"), logcat_capture.log_path)
        elif logcat_since.strip():
            try:
                logcat_path = harness_logcat_dump(ctx, phase_dir, logcat_since)
                if ctx.result.get("statusSnapshot") is None:
                    logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
                    ctx.result["statusSnapshot"] = harness_status(ctx, logcat_text, str(logcat_path))
                else:
                    update_status_harness_logcat(ctx, ctx.result.get("statusSnapshot"), logcat_path)
            except Exception:
                pass
        status = ctx.result.get("statusSnapshot")
    if status is None:
        return {"phase": phase, "index": index, "success": False, "wallMs": 0, "observedState": None}
    phase_ended = datetime.now(timezone.utc)
    wall_ms = int((time.monotonic() - phase_started_monotonic) * 1000)
    observed_state = status.get("observedState")
    crash = (status.get("harnessLogcat") or {}).get("crash")
    crash_marker = (status.get("latestLog") or {}).get("crashMarker")
    runtime_crash_evidence = bool(crash or crash_marker)
    cache_evidence = None
    if logcat_capture is not None and logcat_capture.log_path.exists():
        logcat_text = read_local_text_tail(logcat_capture.log_path, max_bytes=262144)
        cache_evidence = extract_startup_cache_log_evidence(logcat_text)
    success = observed_state in ("READY", "SINGLE_ROOM_COMPLETE") and not runtime_crash_evidence
    run_ops = ctx.operations[operation_start_index:]
    phase_timings["durationMs"] = wall_ms
    return {
        "phase": phase,
        "index": index,
        "startedAt": utc_timestamp(phase_started),
        "endedAt": utc_timestamp(phase_ended),
        "wallMs": wall_ms,
        "timings": phase_timings,
        "success": success,
        "observedState": observed_state,
        "runtimeSignalState": status.get("runtimeSignalState"),
        "cacheBefore": cache_before,
        "cacheAfter": {} if clear_summary else cache_before,
        "clearSummary": clear_summary,
        "cacheEvidence": cache_evidence,
        "operations": run_ops,
        "crashEvidence": crash,
        "crashMarker": crash_marker,
    }


def run_startup_cache_profile(ctx: HarnessContext, resolved_out_dir: Path) -> int:
    if ctx.options.launch_mode == "vanilla":
        raise RuntimeError("startup-cache-profile requires -LaunchMode mts or mts_basemod.")
    hit_runs = max(0, int(ctx.options.cache_hit_runs))
    phases: list[tuple[str, bool]] = []
    phases.append(("cache-build", not ctx.options.no_clear_startup_cache))
    for run_index in range(hit_runs):
        phase_name = "cache-hit-final" if run_index == hit_runs - 1 else f"cache-hit-{run_index + 1}"
        phases.append((phase_name, False))
    runs: list[dict[str, Any]] = []
    for index, (phase, clear_cache_before_run) in enumerate(phases, start=1):
        phase_dir = resolved_out_dir / f"{index:02d}-{phase}"
        runs.append(_run_startup_cache_iteration(ctx, phase_dir, phase, index, clear_cache_before_run=clear_cache_before_run))
    successful_runs = [run for run in runs if run.get("success")]
    build_run = runs[0] if runs else None
    hit_run_values = runs[1:]
    wall_summary = {
        "cacheBuildWallMs": build_run.get("wallMs") if build_run else None,
        "cacheHits": [{"phase": r.get("phase"), "wallMs": r.get("wallMs")} for r in hit_run_values],
    }
    ctx.result["startupCacheProfile"] = {
        "phases": phases,
        "runs": runs,
        "successfulRuns": len(successful_runs),
        "wallSummary": wall_summary,
    }
    all_success = len(successful_runs) == len(runs)
    set_result_success(ctx, all_success, "STARTUP_CACHE_PROFILE", f"{len(successful_runs)}/{len(runs)} runs successful.")
    return 0 if all_success else 1
