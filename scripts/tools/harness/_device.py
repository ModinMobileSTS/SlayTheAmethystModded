from typing import Any
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from scripts.tools.lib.sts_harness import (
    file_timestamp,
    format_command_for_log,
    limit_text,
    quote_android_shell,
    read_local_text_tail,
    utc_timestamp,
)
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._runner import CommandResult, adb, adb_shell_script
from scripts.tools.harness._status_query import StatusQueryError, run_status_query, status_path_probe_script


def resolve_device_sts_root(ctx: HarnessContext, *, strict: bool = False) -> dict[str, Any]:
    package_name = ctx.application_id or ""
    candidates = [
        f"/sdcard/Android/data/{package_name}/files/sts",
        f"/storage/emulated/0/Android/data/{package_name}/files/sts",
    ]
    if strict:
        roots = [{"root": candidate, "accessMode": "shell"} for candidate in candidates]
        roots.append({"root": "files/sts", "accessMode": "run-as"})
        for root in roots:
            script = status_path_probe_script(root["root"]) + """if [ "$sts_presence" = exists ]; then
    (cd "$sts_path" && ls -a >/dev/null) || exit $?
fi
printf '%s\n' "$sts_presence"
"""
            output = run_status_query(ctx, script, label="resolve storage", sts_root=root)
            if output == "exists\n":
                return root
            if output != "missing\n":
                raise StatusQueryError("resolve storage query returned invalid presence data.")
        return roots[0]
    for candidate in candidates:
        probe = adb_shell_script(ctx, f"ls {quote_android_shell(candidate)} >/dev/null 2>&1", allow_failure=True)
        if probe.exit_code == 0:
            return {"root": candidate, "accessMode": "shell"}
    run_as = adb(ctx, ["exec-out", "run-as", package_name, "sh", "-c", "ls 'files/sts' >/dev/null 2>&1"], timeout_seconds=5, allow_failure=True)
    if run_as.exit_code == 0:
        return {"root": "files/sts", "accessMode": "run-as"}
    return {"root": candidates[0], "accessMode": "shell"}


def read_remote_sts_text(
    ctx: HarnessContext,
    sts_root: dict[str, Any],
    relative_path: str,
    tail_lines: int = 0,
    *,
    timeout_seconds: int = 5,
    strict: bool = False,
) -> str:
    trimmed = relative_path.lstrip("/")
    root_path = str(sts_root["root"])
    remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
    quoted = quote_android_shell(remote_path)
    if strict:
        reader = f"tail -n {int(tail_lines)}" if tail_lines > 0 else "cat"
        script = status_path_probe_script(remote_path) + f"""if [ "$sts_presence" = missing ]; then
    printf 'missing\\n'
else
    [ -f "$sts_path" ] || {{ printf 'Expected a regular log file\\n' >&2; exit 1; }}
    printf 'exists\\n'
    {reader} "$sts_path" || exit $?
fi
"""
        output = run_status_query(ctx, script, label=f"read {relative_path}", sts_root=sts_root,
                                  timeout_seconds=timeout_seconds)
        if output == "missing\n":
            return ""
        if not output.startswith("exists\n"):
            raise StatusQueryError(f"read {relative_path} query returned invalid presence data.")
        return output[len("exists\n"):]
    if tail_lines > 0:
        script = f"if [ -f {quoted} ]; then tail -n {tail_lines} {quoted}; fi"
    else:
        script = f"if [ -f {quoted} ]; then cat {quoted}; fi"
    if sts_root["accessMode"] == "run-as":
        return adb(ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", script], timeout_seconds=timeout_seconds, allow_failure=True).output
    return adb_shell_script(ctx, script, timeout_seconds=timeout_seconds, allow_failure=True).output


def parse_remote_path_state_output(relative_path: str, text: str | None) -> dict[str, Any]:
    exists = False
    item_type: str | None = None
    bytes_value: int | None = None
    mtime_epoch_seconds: int | None = None
    child_count: int | None = None
    jar_count: int | None = None
    for line in (text or "").splitlines():
        trimmed_line = line.strip()
        if trimmed_line == "exists=1":
            exists = True
        elif trimmed_line.startswith("type="):
            item_type = trimmed_line[len("type="):]
        elif trimmed_line.startswith("bytes="):
            try:
                bytes_value = int(trimmed_line[len("bytes="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("mtimeEpochSeconds="):
            try:
                mtime_epoch_seconds = int(trimmed_line[len("mtimeEpochSeconds="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("childCount="):
            try:
                child_count = int(trimmed_line[len("childCount="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("jarCount="):
            try:
                jar_count = int(trimmed_line[len("jarCount="):])
            except ValueError:
                pass
    return {
        "relativePath": relative_path,
        "exists": exists,
        "type": item_type,
        "bytes": bytes_value,
        "mtimeEpochSeconds": mtime_epoch_seconds,
        "childCount": child_count,
        "jarCount": jar_count,
    }


def clear_runtime_signals(ctx: HarnessContext) -> None:
    sts_root = resolve_device_sts_root(ctx)
    for relative_path in ("boot_bridge_events.log", "latest.log", ".harness_exit_request"):
        remote_path = f"{sts_root['root']}/{relative_path}"
        quoted = quote_android_shell(remote_path)
        if sts_root["accessMode"] == "run-as":
            adb(ctx, ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", f"rm -f {quoted}"], allow_failure=True)
        else:
            adb_shell_script(ctx, f"rm -f {quoted}", allow_failure=True)


def remote_sts_root_script(
    ctx: HarnessContext,
    sts_root: dict[str, Any],
    script: str,
    *,
    timeout_seconds: int = 5,
    allow_failure: bool = True,
) -> CommandResult:
    if sts_root["accessMode"] == "run-as":
        return adb(
            ctx,
            ["exec-out", "run-as", ctx.application_id or "", "sh", "-c", script],
            timeout_seconds=timeout_seconds,
            allow_failure=allow_failure,
        )
    return adb_shell_script(ctx, script, timeout_seconds=timeout_seconds, allow_failure=allow_failure)


def device_logcat_timestamp(ctx: HarnessContext) -> str:
    result = adb_shell_script(ctx, "date '+%m-%d %H:%M:%S.000' 2>/dev/null", timeout_seconds=5, allow_failure=True)
    if result.exit_code != 0:
        return ""
    for line in result.output.strip().splitlines():
        trimmed = line.strip()
        if re.match(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}$", trimmed):
            return trimmed
    return ""


def remote_sts_path_state(ctx: HarnessContext, sts_root: dict[str, Any], relative_path: str, *, strict: bool = False) -> dict[str, Any]:
    trimmed = relative_path.lstrip("/")
    root_path = str(sts_root["root"])
    remote_path = root_path if not trimmed else f"{root_path}/{trimmed}"
    quoted = quote_android_shell(remote_path)
    if strict:
        script = status_path_probe_script(remote_path) + """if [ "$sts_presence" = missing ]; then
    printf 'exists=0\n'
else
    if [ -f "$sts_path" ]; then kind=file
    elif [ -d "$sts_path" ]; then kind=directory
    else kind=other
    fi
    printf 'exists=1\ntype=%s\n' "$kind"
    stat -c 'bytes=%s
mtimeEpochSeconds=%Y' "$sts_path" || exit $?
fi
"""
        output = run_status_query(ctx, script, label=f"path {relative_path}", sts_root=sts_root)
        if output != "exists=0\n" and not re.fullmatch(
            r"exists=1\ntype=(file|directory|other)\nbytes=[0-9]+\nmtimeEpochSeconds=-?[0-9]+\n", output,
        ):
            raise StatusQueryError(f"path {relative_path} query returned invalid metadata.")
        state = parse_remote_path_state_output(relative_path, output)
        if state["exists"] and state["type"] != "file":
            state["bytes"] = 0
        return state
    state_script = f"""if [ -e {quoted} ]; then
  echo exists=1
  if [ -f {quoted} ]; then
    echo type=file
    size=$(wc -c < {quoted} 2>/dev/null | tr -d '[:space:]')
    echo bytes=$size
  elif [ -d {quoted} ]; then
    echo type=directory
    echo bytes=0
  else
    echo type=other
    echo bytes=0
  fi
  mtime=$(stat -c %Y {quoted} 2>/dev/null || echo '')
  echo mtimeEpochSeconds=$mtime
else
  echo exists=0
fi
"""
    result = remote_sts_root_script(ctx, sts_root, state_script)
    exists = False
    item_type: str | None = None
    bytes_value: int | None = None
    mtime_epoch_seconds: int | None = None
    for line in result.output.splitlines():
        trimmed_line = line.strip()
        if trimmed_line == "exists=1":
            exists = True
        elif trimmed_line.startswith("type="):
            item_type = trimmed_line[len("type="):]
        elif trimmed_line.startswith("bytes="):
            try:
                bytes_value = int(trimmed_line[len("bytes="):])
            except ValueError:
                pass
        elif trimmed_line.startswith("mtimeEpochSeconds="):
            try:
                mtime_epoch_seconds = int(trimmed_line[len("mtimeEpochSeconds="):])
            except ValueError:
                pass
    return {
        "relativePath": relative_path,
        "exists": exists,
        "type": item_type,
        "bytes": bytes_value,
        "mtimeEpochSeconds": mtime_epoch_seconds,
    }


def harness_logcat_dump(ctx: HarnessContext, output_directory: Path, since_timestamp: str = "") -> Path:
    if ctx.connector is None:
        raise RuntimeError("Harness connector is not initialized.")
    output_directory.mkdir(parents=True, exist_ok=True)
    log_path = output_directory / f"harness-logcat-dump-{file_timestamp()}.txt"
    resp = ctx.connector.logcat_dump(
        since=since_timestamp.strip(),
        local_path=str(log_path),
        timeout_ms=15000,
    )
    if isinstance(resp, dict) and "error" in resp:
        # fallback without -T
        if since_timestamp.strip():
            resp = ctx.connector.logcat_dump(local_path=str(log_path), timeout_ms=30000)
        if isinstance(resp, dict) and "error" in resp:
            log_path.write_text("", encoding="utf-8")
    if not log_path.exists():
        text = ""
        if isinstance(resp, dict):
            text = str(resp.get("stdout", "") or "")
        log_path.write_text(text, encoding="utf-8", errors="replace")
    ctx.result.setdefault("artifacts", {})["harnessLogcatDump"] = str(log_path)
    ctx.result.setdefault("artifacts", {})["harnessLogcat"] = str(log_path)
    return log_path


@dataclass
class LogcatCapture:
    capture_id: str
    log_path: Path
    stderr_path: Path
    started_at: datetime
    command: str
    process: Any = None  # legacy compat; unused when via connector
    stdout_stream: Any = None
    stderr_stream: Any = None


def start_logcat_capture(ctx: HarnessContext, output_directory: Path, since_timestamp: str = "") -> LogcatCapture:
    if ctx.connector is None:
        raise RuntimeError("Harness connector is not initialized.")
    output_directory.mkdir(parents=True, exist_ok=True)
    timestamp = file_timestamp()
    log_path = output_directory / f"harness-logcat-{timestamp}.txt"
    stderr_path = output_directory / f"harness-logcat-{timestamp}.stderr.txt"
    resp = ctx.connector.logcat_start(
        since=since_timestamp.strip() if since_timestamp.strip() else "",
        local_path=str(log_path),
    )
    if isinstance(resp, dict) and "error" in resp:
        err = resp["error"]
        message = err.get("message", str(err)) if isinstance(err, dict) else str(err)
        raise RuntimeError(f"logcat_start failed: {message}")
    capture_id = str(resp.get("capture_id", ""))
    actual_path = Path(str(resp.get("local_path", log_path)))
    actual_stderr = Path(str(resp.get("stderr_path", stderr_path)))
    ctx.result.setdefault("artifacts", {})["harnessLogcat"] = str(actual_path)
    ctx.result.setdefault("artifacts", {})["harnessLogcatStderr"] = str(actual_stderr)
    return LogcatCapture(
        capture_id=capture_id,
        log_path=actual_path,
        stderr_path=actual_stderr,
        started_at=datetime.now(timezone.utc),
        command=format_command_for_log("connector-logcat", [capture_id]),
    )


def stop_logcat_capture(ctx: HarnessContext, capture: Any | None) -> None:
    if capture is None:
        return
    ended = datetime.now(timezone.utc)
    stopped_by_harness = False
    exit_code: int | None = None
    if getattr(capture, "capture_id", None) and ctx.connector is not None:
        resp = ctx.connector.logcat_stop(capture.capture_id)
        if isinstance(resp, dict) and "error" not in resp:
            stopped_by_harness = bool(resp.get("stopped_by_daemon", False))
            exit_code = resp.get("exit")
            if resp.get("local_path"):
                capture.log_path = Path(str(resp["local_path"]))
            if resp.get("stderr_path"):
                capture.stderr_path = Path(str(resp["stderr_path"]))
        elif isinstance(resp, dict) and "error" in resp:
            exit_code = 1
    stderr_tail = read_local_text_tail(capture.stderr_path, max_bytes=4000) if capture.stderr_path.exists() else ""
    ctx.operations.append({
        "command": capture.command,
        "exitCode": exit_code,
        "startedAt": utc_timestamp(capture.started_at),
        "endedAt": utc_timestamp(ended),
        "durationMs": int((ended - capture.started_at).total_seconds() * 1000),
        "timedOut": False,
        "outputTail": limit_text(stderr_tail),
        "stopped_by_harness": stopped_by_harness,
        "via": "connector",
    })
