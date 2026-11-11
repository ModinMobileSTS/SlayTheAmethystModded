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
from scripts.tools.harness._status import harness_status
from scripts.tools.harness.run import run_start


def run_single_room(ctx: HarnessContext, resolved_out_dir: Path) -> int:
    logcat_capture: Any = None
    logcat_since = ""
    try:
        clear_runtime_signals(ctx)
        logcat_since = device_logcat_timestamp(ctx)
        try:
            logcat_capture = start_logcat_capture(ctx, resolved_out_dir, logcat_since)
        except Exception as exc:
            ctx.result.setdefault("artifacts", {})["harnessLogcatError"] = str(exc)

        run_start(ctx, resolved_out_dir)
        import time

        deadline = time.monotonic() + max(1, ctx.options.timeout_seconds)
        status = harness_status(ctx)
        while time.monotonic() < deadline:
            if status["processes"]["game"].strip() or status["observedState"] in (
                "FAIL", "CRASH_MARKER", "LOGCAT_CRASH"
            ):
                break
            time.sleep(max(0.25, ctx.options.poll_interval_seconds))
            status = harness_status(
                ctx,
                read_local_text_tail(logcat_capture.log_path, max_bytes=262144) if logcat_capture else None,
                str(logcat_capture.log_path) if logcat_capture else "",
            )
        ctx.result["statusSnapshot"] = status
        success = bool(status["processes"]["game"].strip()) and status["observedState"] not in (
            "FAIL", "CRASH_MARKER", "LOGCAT_CRASH"
        )
        set_result_success(
            ctx,
            success,
            "SINGLE_ROOM_STARTED" if success else status["observedState"],
            "Single-room game is running; use -Command exit for graceful shutdown."
            if success
            else f"Single-room game did not start: {status['observedState']}",
        )
        return 0 if success else 1
    finally:
        if logcat_capture is not None:
            stop_logcat_capture(ctx, logcat_capture)
            if ctx.result.get("statusSnapshot") is not None:
                from scripts.tools.harness._status import update_status_harness_logcat
                update_status_harness_logcat(ctx, ctx.result["statusSnapshot"], logcat_capture.log_path)
        elif logcat_since.strip():
            try:
                logcat_path = harness_logcat_dump(ctx, resolved_out_dir, logcat_since)
                if ctx.result.get("statusSnapshot") is not None:
                    from scripts.tools.harness._status import update_status_harness_logcat
                    update_status_harness_logcat(ctx, ctx.result["statusSnapshot"], logcat_path)
            except Exception as exc:
                ctx.result.setdefault("artifacts", {})["harnessLogcatError"] = str(exc)
