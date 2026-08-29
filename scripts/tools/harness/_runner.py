import os
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from scripts.tools.lib.sts_harness import (
    format_command_for_log,
    limit_text,
    utc_timestamp,
)
from scripts.tools.harness._context import HarnessContext


@dataclass
class CommandResult:
    exit_code: int
    output: str


def run_native(
    ctx: HarnessContext,
    file_path: str | Path,
    arguments: list[str] | tuple[str, ...] = (),
    *,
    cwd: Path | None = None,
    timeout_seconds: int = 0,
    allow_failure: bool = False,
) -> CommandResult:
    started = datetime.now(timezone.utc)
    command = [str(file_path), *[str(a) for a in arguments]]
    output = ""
    exit_code = 0
    timed_out = False
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd or ctx.repo_root),
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_seconds if timeout_seconds > 0 else None,
            check=False,
        )
        exit_code = completed.returncode
        output = "\n".join(part for part in (completed.stdout, completed.stderr) if part)
    except subprocess.TimeoutExpired as exc:
        timed_out = True
        exit_code = -1
        stdout = exc.stdout.decode("utf-8", errors="replace") if isinstance(exc.stdout, bytes) else (exc.stdout or "")
        stderr = exc.stderr.decode("utf-8", errors="replace") if isinstance(exc.stderr, bytes) else (exc.stderr or "")
        output = "\n".join(part for part in (stdout, stderr) if part)

    ended = datetime.now(timezone.utc)
    operation = {
        "command": format_command_for_log(file_path, list(arguments)),
        "exitCode": exit_code,
        "startedAt": utc_timestamp(started),
        "endedAt": utc_timestamp(ended),
        "durationMs": int((ended - started).total_seconds() * 1000),
        "timedOut": timed_out,
        "outputTail": limit_text(output),
    }
    ctx.operations.append(operation)

    if timed_out and not allow_failure:
        raise RuntimeError(
            f"Command timed out after {timeout_seconds}s: {operation['command']}\n"
            f"{limit_text(output, 2000)}"
        )
    if exit_code != 0 and not allow_failure:
        raise RuntimeError(
            f"Command failed with exit code {exit_code}: {operation['command']}\n"
            f"{limit_text(output, 2000)}"
        )
    return CommandResult(exit_code=exit_code, output=output)


def build_adb_args(ctx: HarnessContext, arguments: list[str] | tuple[str, ...]) -> list[str]:
    """Build display-only adb argv (device serial is owned by connector)."""
    adb_args: list[str] = []
    if ctx.resolved_device_serial.strip():
        adb_args.extend(["-s", ctx.resolved_device_serial])
    adb_args.extend([str(a) for a in arguments])
    return adb_args


def _require_connector(ctx: HarnessContext) -> Any:
    if ctx.connector is None:
        raise RuntimeError(
            "Harness connector is not initialized. Set STS_CONNECTOR_PORT or pass "
            "-ConnectorPort, then start: python -m scripts.tools.connector start --port <port>"
        )
    return ctx.connector


def adb(
    ctx: HarnessContext,
    arguments: list[str] | tuple[str, ...],
    *,
    timeout_seconds: int = 10,
    allow_failure: bool = False,
    capture: str = "text",
    local_path: str = "",
) -> CommandResult:
    connector = _require_connector(ctx)
    args = [str(a) for a in arguments]
    started = datetime.now(timezone.utc)
    timed_out = False
    exit_code = 0
    output = ""
    command_label = format_command_for_log("connector-adb", build_adb_args(ctx, args))
    try:
        if len(args) >= 1 and args[0] == "shell" and capture == "text" and not local_path:
            # Prefer shell method for single-string scripts used throughout harness.
            if len(args) == 2:
                resp = connector.shell(args[1], timeout_ms=max(1, int(timeout_seconds * 1000)))
            else:
                resp = connector.adb(args, timeout_ms=max(1, int(timeout_seconds * 1000)), capture="text")
        elif len(args) >= 1 and args[0] == "push" and len(args) >= 3:
            ok = connector.push(args[1], args[2], timeout_ms=max(1, int(timeout_seconds * 1000)))
            resp = {"exit": 0 if ok else 1, "stdout": "", "stderr": "" if ok else "push failed"}
            if not ok:
                # push returns bool; try raw for error detail
                raw = connector.adb(args, timeout_ms=max(1, int(timeout_seconds * 1000)), capture="text")
                if "error" not in raw:
                    resp = raw
        elif len(args) >= 1 and args[0] == "pull" and len(args) >= 3:
            ok = connector.pull(args[1], args[2], timeout_ms=max(1, int(timeout_seconds * 1000)))
            resp = {"exit": 0 if ok else 1, "stdout": "", "stderr": "" if ok else "pull failed"}
            if not ok:
                raw = connector.adb(args, timeout_ms=max(1, int(timeout_seconds * 1000)), capture="text")
                if "error" not in raw:
                    resp = raw
        elif len(args) >= 1 and args[0] == "install":
            local = args[-1]
            replace = "-r" in args
            resp = connector.install(local, replace=replace, timeout_ms=max(1, int(timeout_seconds * 1000)))
        else:
            resp = connector.adb(
                args,
                timeout_ms=max(1, int(timeout_seconds * 1000)),
                capture=capture,
                local_path=local_path,
            )

        if isinstance(resp, dict) and "error" in resp:
            err = resp["error"]
            message = err.get("message", str(err)) if isinstance(err, dict) else str(err)
            code = err.get("code") if isinstance(err, dict) else None
            if code == -32003:
                timed_out = True
                exit_code = -1
            else:
                exit_code = 1
            output = message
        else:
            exit_code = int(resp.get("exit", 0) if isinstance(resp, dict) else 0)
            if capture == "binary" and local_path:
                output = f"wrote {local_path} bytes={resp.get('bytes', 0)}"
            else:
                output = str(resp.get("stdout", "") or "")
                stderr = str(resp.get("stderr", "") or "")
                if stderr:
                    output = "\n".join(part for part in (output, stderr) if part)
    except Exception as exc:
        exit_code = 1
        output = str(exc)
        if "timeout" in output.lower():
            timed_out = True
            exit_code = -1

    ended = datetime.now(timezone.utc)
    operation = {
        "command": command_label,
        "exitCode": exit_code,
        "startedAt": utc_timestamp(started),
        "endedAt": utc_timestamp(ended),
        "durationMs": int((ended - started).total_seconds() * 1000),
        "timedOut": timed_out,
        "outputTail": limit_text(output),
        "via": "connector",
    }
    ctx.operations.append(operation)

    if timed_out and not allow_failure:
        raise RuntimeError(
            f"Command timed out after {timeout_seconds}s: {operation['command']}\n"
            f"{limit_text(output, 2000)}"
        )
    if exit_code != 0 and not allow_failure:
        raise RuntimeError(
            f"Command failed with exit code {exit_code}: {operation['command']}\n"
            f"{limit_text(output, 2000)}"
        )
    return CommandResult(exit_code=exit_code, output=output)


def adb_shell_script(
    ctx: HarnessContext,
    script: str,
    *,
    timeout_seconds: int = 5,
    allow_failure: bool = False,
) -> CommandResult:
    return adb(ctx, ["shell", script], timeout_seconds=timeout_seconds, allow_failure=allow_failure)


def gradle(
    ctx: HarnessContext,
    arguments: list[str] | tuple[str, ...],
    *,
    timeout_seconds: int = 0,
) -> CommandResult:
    if not ctx.gradle_wrapper:
        raise RuntimeError("Harness is not initialized.")
    gradle_args = [*arguments, "--stacktrace", "--console=plain"]
    if os.name == "nt":
        command_processor = os.environ.get("COMSPEC") or "cmd.exe"
        return run_native(
            ctx,
            command_processor,
            ["/c", str(ctx.gradle_wrapper), *gradle_args],
            timeout_seconds=timeout_seconds,
        )
    if not os.access(ctx.gradle_wrapper, os.X_OK):
        return run_native(
            ctx,
            "bash",
            [str(ctx.gradle_wrapper), *gradle_args],
            timeout_seconds=timeout_seconds,
        )
    return run_native(ctx, ctx.gradle_wrapper, gradle_args, timeout_seconds=timeout_seconds)
