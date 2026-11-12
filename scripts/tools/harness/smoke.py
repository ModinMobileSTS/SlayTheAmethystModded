from pathlib import Path
from typing import Any

from scripts.tools.lib.sts_harness import read_local_text_tail
from scripts.tools.harness._context import HarnessContext, set_result_success
from scripts.tools.harness._device import (
    clear_runtime_signals,
    device_logcat_timestamp,
    harness_logcat_dump,
    start_logcat_capture,
    stop_logcat_capture,
)
from scripts.tools.harness._status import (
    harness_status,
    update_status_harness_logcat,
    wait_harness_status,
)
from scripts.tools.harness.agent import run_agent_attach
from scripts.tools.harness.logs import run_logs
from scripts.tools.harness.run import run_start, run_stop
from scripts.tools.harness.screenshot import run_screenshot


def run_smoke(ctx: HarnessContext, resolved_out_dir: Path) -> int:
    status: dict[str, Any] | None = None
    logcat_capture: Any = None
    logcat_since = ""
    start_requested = False
    try:
        clear_runtime_signals(ctx)
        logcat_since = device_logcat_timestamp(ctx)
        try:
            logcat_capture = start_logcat_capture(ctx, resolved_out_dir, logcat_since)
        except Exception as exc:
            ctx.result.setdefault("artifacts", {})["harnessLogcatError"] = str(exc)
        run_start(ctx, resolved_out_dir)
        start_requested = True
        status = wait_harness_status(
            ctx, logcat_capture,
            timeout_seconds=ctx.options.timeout_seconds,
            poll_interval_seconds=ctx.options.poll_interval_seconds,
            autoplay_mode=ctx.options.autoplay_mode,
        )
        ctx.result["statusSnapshot"] = status
        if (
            ctx.options.agent_command == "attach"
            and ctx.options.agent_spec
            and status.get("observedState") == "READY"
        ):
            try:
                run_agent_attach(ctx, resolved_out_dir)
            except Exception as exc:
                ctx.result.setdefault("artifacts", {})["agentError"] = str(exc)
        try:
            run_screenshot(ctx, resolved_out_dir)
        except Exception as exc:
            ctx.result.setdefault("artifacts", {})["screenshotError"] = str(exc)
        try:
            run_logs(ctx, resolved_out_dir)
        except Exception as exc:
            ctx.result.setdefault("artifacts", {})["logsError"] = str(exc)
    finally:
        if not ctx.options.no_stop_after_smoke and start_requested:
            try:
                run_stop(ctx)
            except Exception as exc:
                ctx.result.setdefault("artifacts", {})["stopError"] = str(exc)
        if logcat_capture is not None:
            stop_logcat_capture(ctx, logcat_capture)
            update_status_harness_logcat(ctx, ctx.result.get("statusSnapshot"), logcat_capture.log_path)
        elif logcat_since.strip():
            try:
                logcat_path = harness_logcat_dump(ctx, resolved_out_dir, logcat_since)
                if ctx.result.get("statusSnapshot") is None:
                    logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
                    ctx.result["statusSnapshot"] = harness_status(ctx, logcat_text, str(logcat_path))
                else:
                    update_status_harness_logcat(ctx, ctx.result.get("statusSnapshot"), logcat_path)
            except Exception as exc:
                ctx.result.setdefault("artifacts", {}).setdefault("harnessLogcatError", str(exc))
        status = ctx.result.get("statusSnapshot")

    if status is None:
        raise RuntimeError("Smoke run did not produce a status snapshot.")
    if ctx.options.autoplay_mode == "single_room":
        expected_state = "SINGLE_ROOM_COMPLETE"
    else:
        expected_state = "FAIL" if ctx.options.force_jvm_crash else "CRASH_MARKER" if ctx.options.force_runtime_crash else "READY"
    if ctx.options.autoplay_mode == "single_room":
        single_room_result = status.get("latestLog", {}).get("singleRoomResult")
        outcome = single_room_result.get("outcome") if isinstance(single_room_result, dict) else None
        success = status["observedState"] == "SINGLE_ROOM_COMPLETE" and outcome in ("monsters_defeated", "player_dead")
    elif ctx.options.force_runtime_crash:
        success = (
            status["observedState"] in ("CRASH_MARKER", "LOGCAT_CRASH")
            or status.get("latestLog", {}).get("crashMarker") is not None
            or (status.get("harnessLogcat") is not None and status["harnessLogcat"].get("crash") is not None)
        )
    else:
        success = status["observedState"] == expected_state
    message = (
        f"Smoke run reached expected state: {expected_state}"
        if success
        else f"Smoke run expected {expected_state} but observed {status['observedState']}"
    )
    if ctx.options.autoplay_mode == "single_room":
        single_room_result = status.get("latestLog", {}).get("singleRoomResult")
        if isinstance(single_room_result, dict) and single_room_result.get("outcome"):
            detail = single_room_result.get("detail")
            message = (
                f"Single-room run completed with outcome={single_room_result.get('outcome')}"
                if success
                else f"Single-room run failed with outcome={single_room_result.get('outcome')}"
            )
            if detail:
                message = f"{message} detail={detail}"
    if not success:
        hints = []
        if ctx.result.get("artifacts", {}).get("logsZip"):
            hints.append(f"Logs zip: {ctx.result['artifacts']['logsZip']}")
        if ctx.result.get("artifacts", {}).get("harnessLogcat"):
            hints.append(f"Harness logcat: {ctx.result['artifacts']['harnessLogcat']}")
        if hints:
            message = f"{message}. {'; '.join(hints)}"
    set_result_success(ctx, success, status["observedState"], message)
    return 0 if success else 1
