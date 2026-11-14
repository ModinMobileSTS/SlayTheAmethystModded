import re
from pathlib import Path
from typing import Any

from scripts.tools.lib.sts_harness import (
    SINGLE_ROOM_RESULT_PREFIX,
    STARTUP_CACHE_EVIDENCE_PATTERNS,
    limit_text,
    quote_android_shell,
    text_contains,
)
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._device import (
    read_remote_sts_text,
    remote_sts_path_state,
    resolve_device_sts_root,
)
from scripts.tools.harness._runner import adb_shell_script
from scripts.tools.harness._status_query import StatusQueryError, run_status_query
from scripts.tools.lib.device_safety import require_explicit_device


def require_status_device(device_serial: str, resolved_device_serial: str) -> None:
    try:
        serial = require_explicit_device(device_serial)
    except RuntimeError:
        raise StatusQueryError(
            "Status query requires an explicit device serial (--device-serial); "
            "automatic selection cannot authorize raw queries."
        ) from None
    if serial != resolved_device_serial:
        raise StatusQueryError("Status query target differs from the explicitly selected device.")


def checked_pid_text(result: Any) -> str:
    text = result.output.strip()
    # Android pidof returns 1 with no output when no process matches. Transport,
    # permission and shell errors must remain query failures, even with no PID.
    if result.exit_code == 1 and not text:
        return ""
    if result.exit_code != 0:
        raise StatusQueryError(f"Process status query failed (exit {result.exit_code}): {limit_text(text, 1000)}")
    if text and not re.fullmatch(r"[1-9][0-9]*(?:\s+[1-9][0-9]*)*", text):
        raise StatusQueryError("Process status query returned invalid PID output.")
    return " ".join(text.split())


def parse_boot_bridge_events(text: str | None) -> dict[str, Any]:
    latest = None
    terminal = None
    count = 0
    for line in re.split(r"\r?\n", text or ""):
        trimmed = line.strip()
        if not trimmed:
            continue
        parts = trimmed.split("\t", 2)
        event_type = parts[0].strip().upper()
        progress = None
        if len(parts) >= 2:
            try:
                progress = int(parts[1].strip())
            except ValueError:
                pass
        message = parts[2].strip() if len(parts) >= 3 else ""
        event = {"type": event_type, "progress": progress, "message": message}
        latest = event
        count += 1
        if event_type in ("READY", "FAIL"):
            terminal = event
    return {"eventCount": count, "latestEvent": latest, "terminalEvent": terminal}


def find_crash_marker(text: str | None) -> str | None:
    for marker in (
        "Game crashed.",
        "Exception occurred in CardCrawlGame render method!",
        'Exception in thread "LWJGL Application"',
        "Forced runtime crash for expected-exit verification",
    ):
        if text_contains(text, marker):
            return marker
    return None


def find_single_room_result(text: str | None) -> dict[str, Any] | None:
    if not text or not text.strip():
        return None
    result_line = None
    for line in re.split(r"\r?\n", text):
        if SINGLE_ROOM_RESULT_PREFIX in line:
            result_line = line.strip()
    if result_line is None:
        return None
    payload = result_line.split(SINGLE_ROOM_RESULT_PREFIX, 1)[1].strip()
    values: dict[str, str] = {}
    for match in re.finditer(r"(\w+)=([^ ]+)", payload):
        values[match.group(1)] = match.group(2)
    return {
        "line": result_line,
        "outcome": values.get("outcome"),
        "character": values.get("character"),
        "monster": values.get("monster"),
        "turns": values.get("turns"),
        "playerHp": values.get("playerHp"),
        "monsterHp": values.get("monsterHp"),
        "detail": values.get("detail"),
    }


def _harness_process_names(package_name: str) -> tuple[str, ...]:
    if not package_name.strip():
        return ()
    return tuple(
        name
        for name in (
            package_name,
            f"{package_name}:game",
            f"{package_name}:steamcloud",
            f"{package_name}:diag",
            f"{package_name}:logcat",
        )
        if name
    )


def _line_has_strong_process_identity(line: str, process_names: tuple[str, ...]) -> str | None:
    # Case-sensitive: must not match vendor lifecycle logs like
    # "handleForegroundActivitiesChanged process: io.stamethyst".
    for name in process_names:
        for needle in (
            f"Process: {name}",
            f"Cmdline: {name}",
            f"Cmd line: {name}",
            f">>> {name}",
        ):
            if needle in line:
                return needle
        if "Force finishing" in line and name in line:
            return f"Force finishing:{name}"
    return None


def _window_has_strong_process_identity(window_text: str, process_names: tuple[str, ...]) -> bool:
    return any(_line_has_strong_process_identity(line, process_names) for line in re.split(r"\r?\n", window_text))


def find_harness_logcat_crash(text: str | None, package_name: str) -> dict[str, Any] | None:
    if not text or not text.strip():
        return None
    lines = re.split(r"\r?\n", text)
    markers = (
        "FATAL EXCEPTION",
        "Fatal signal",
        "AndroidRuntime",
        "Game crashed.",
        "Game body patch failed before launch",
        "Exception occurred in CardCrawlGame render method!",
        'Exception in thread "LWJGL Application"',
        "java.lang.OutOfMemoryError",
    )
    generic_crash_markers = (
        "FATAL EXCEPTION",
        "Fatal signal",
        "AndroidRuntime",
        "java.lang.OutOfMemoryError",
    )
    runtime_log_markers = (
        "Game crashed.",
        "Game body patch failed before launch",
        "Exception occurred in CardCrawlGame render method!",
        'Exception in thread "LWJGL Application"',
    )
    process_names = _harness_process_names(package_name)
    for index, line in enumerate(lines):
        marker_matched = None
        for marker in markers:
            if text_contains(line, marker):
                marker_matched = marker
                break
        if marker_matched is None:
            strong_identity = _line_has_strong_process_identity(line, process_names)
            if strong_identity is not None:
                marker_matched = strong_identity
        if marker_matched is None:
            continue
        start = max(0, index - 12)
        end = min(len(lines) - 1, index + 90)
        window_text = "\n".join(lines[start : end + 1])
        package_matched = any(text_contains(window_text, name) for name in process_names)
        strong_package_matched = _window_has_strong_process_identity(window_text, process_names)
        runtime_log_marker = marker_matched in runtime_log_markers
        if marker_matched in generic_crash_markers and not strong_package_matched:
            continue
        if not package_matched and not runtime_log_marker and not strong_package_matched:
            continue
        return {
            "marker": marker_matched,
            "line": line.strip(),
            "packageMatched": package_matched or strong_package_matched,
            "excerpt": limit_text(window_text, 5000),
        }
    return None


def last_non_blank_line(text: str | None) -> str | None:
    last = None
    for line in re.split(r"\r?\n", text or ""):
        trimmed = line.strip()
        if trimmed:
            last = trimmed
    return last


def extract_startup_cache_log_evidence(text: str | None, max_lines: int = 80) -> dict[str, Any]:
    evidence_lines: list[str] = []
    timing_lines: list[dict[str, Any]] = []
    saw_cache_hit = False
    saw_cache_build = False
    saw_cache_miss = False
    for raw_line in re.split(r"\r?\n", text or ""):
        line = raw_line.strip()
        if not line:
            continue
        matched = any(pattern in line for pattern in STARTUP_CACHE_EVIDENCE_PATTERNS)
        if matched:
            evidence_lines.append(line)
            if "Launching cached MTS patch jar" in line:
                saw_cache_hit = True
            if "Writing MTS patch cache jar" in line or "MTS patch cache is ready" in line:
                saw_cache_build = True
            if "Patch cache miss:" in line:
                saw_cache_miss = True
        if matched or "took=" in line or " elapsedMs=" in line or " took " in line:
            timing_match = re.search(
                r"(?P<label>.*?)(?:\s+took=|\s+took\s+|\s+elapsedMs=|Time Elapsed:\s*)"
                r"(?P<ms>\d+(?:\.\d+)?)ms",
                line,
            )
            if timing_match:
                label = timing_match.group("label").strip()
                try:
                    elapsed_ms: float | int = float(timing_match.group("ms"))
                    if elapsed_ms.is_integer():
                        elapsed_ms = int(elapsed_ms)
                except ValueError:
                    elapsed_ms = timing_match.group("ms")
                timing_lines.append(
                    {
                        "label": limit_text(label, 180),
                        "elapsedMs": elapsed_ms,
                        "line": line,
                    }
                )
    if saw_cache_hit:
        mode = "cache-hit"
    elif saw_cache_build:
        mode = "cache-build"
    elif saw_cache_miss:
        mode = "cache-miss"
    else:
        mode = "unknown"
    return {
        "mode": mode,
        "sawCacheHit": saw_cache_hit,
        "sawCacheBuild": saw_cache_build,
        "sawCacheMiss": saw_cache_miss,
        "evidenceLines": evidence_lines[-max_lines:],
        "timings": timing_lines[-max_lines:],
    }


def process_pid_text(ctx: HarnessContext, process_name: str) -> str:
    result = adb_shell_script(ctx, f"pidof {quote_android_shell(process_name)}", allow_failure=True)
    return checked_pid_text(result)


def package_version_info(ctx: HarnessContext, *, strict: bool = False) -> dict[str, Any]:
    quoted = quote_android_shell(ctx.application_id or "")
    if strict:
        # Do not pipe through grep: that masks dumpsys failures. Some Android
        # services print permission errors with exit=0, so validate fields too.
        output = run_status_query(ctx, f"dumpsys package {quoted}", label="package")
    else:
        output = adb_shell_script(ctx, f"dumpsys package {quoted} 2>/dev/null | grep -E 'version(Name|Code)=' || true", timeout_seconds=5, allow_failure=True).output
    version_name = None
    version_code = None
    for line in output.splitlines():
        trimmed = line.strip()
        if trimmed.startswith("versionName="):
            version_name = trimmed[len("versionName="):]
        elif trimmed.startswith("versionCode="):
            version_code = trimmed[len("versionCode="):].split(" ")[0]
    if strict and (version_name is None or version_code is None or not version_code.isdecimal()):
        raise StatusQueryError("package query did not return valid installed-package version information.")
    return {"versionName": version_name, "versionCode": version_code}


def desktop_jar_patch_snapshot(ctx: HarnessContext, sts_root: dict[str, Any], *, strict: bool = False) -> dict[str, Any]:
    desktop_jar = remote_sts_path_state(ctx, sts_root, "desktop-1.0.jar", strict=strict)
    temp_jar = remote_sts_path_state(ctx, sts_root, "desktop-1.0.jar.patching.tmp", strict=strict)
    backup_jar = remote_sts_path_state(ctx, sts_root, "desktop-1.0.jar.patching.backup", strict=strict)
    return {
        "desktopJar": desktop_jar,
        "tempJar": temp_jar,
        "backupJar": backup_jar,
        "inProgress": bool(temp_jar["exists"] or backup_jar["exists"]),
    }


def harness_status(
    ctx: HarnessContext,
    harness_logcat_text: str | None = None,
    harness_logcat_path: str = "",
) -> dict[str, Any]:
    require_status_device(ctx.options.device_serial, ctx.resolved_device_serial)
    # Query PIDs first so connector/transport failures stop collection before
    # optional remote log/path probes can turn error output into state evidence.
    package_name = ctx.application_id or ""
    launcher_pid = process_pid_text(ctx, package_name)
    game_pid = process_pid_text(ctx, f"{package_name}:game")
    diag_pid = process_pid_text(ctx, f"{package_name}:diag")
    logcat_pid = process_pid_text(ctx, f"{package_name}:logcat")
    sts_root = resolve_device_sts_root(ctx, strict=True)
    boot_text = read_remote_sts_text(ctx, sts_root, "boot_bridge_events.log", strict=True)
    latest_log_tail = read_remote_sts_text(ctx, sts_root, "latest.log", tail_lines=120, strict=True)
    desktop_jar_patch = desktop_jar_patch_snapshot(ctx, sts_root, strict=True)
    boot = parse_boot_bridge_events(boot_text)
    crash_marker = find_crash_marker(latest_log_tail)
    single_room_result = find_single_room_result(latest_log_tail)

    runtime_signal_state = None
    terminal = boot["terminalEvent"]
    if terminal is not None:
        runtime_signal_state = terminal["type"]
    elif crash_marker is not None:
        runtime_signal_state = "CRASH_MARKER"

    observed_state = "NOT_RUNNING"
    if terminal is not None and terminal["type"] == "FAIL":
        observed_state = "FAIL"
    elif crash_marker is not None:
        observed_state = "CRASH_MARKER"
    elif single_room_result is not None:
        observed_state = "SINGLE_ROOM_COMPLETE"
        runtime_signal_state = "SINGLE_ROOM_COMPLETE"
    elif terminal is not None and terminal["type"] == "READY" and game_pid.strip():
        observed_state = "READY"
    elif launcher_pid.strip() and desktop_jar_patch["inProgress"]:
        observed_state = "PATCHING_DESKTOP_JAR"
    elif game_pid.strip():
        observed_state = "RUNNING_WITHOUT_TERMINAL_EVENT"
    elif launcher_pid.strip():
        observed_state = "LAUNCHER_RUNNING"

    harness_logcat = None
    if harness_logcat_text is not None:
        crash = find_harness_logcat_crash(harness_logcat_text, package_name)
        if crash is not None and runtime_signal_state is None:
            runtime_signal_state = "LOGCAT_CRASH"
        harness_logcat = {
            "artifact": harness_logcat_path,
            "lastNonBlankLine": last_non_blank_line(harness_logcat_text),
            "crash": crash,
        }

    return {
        "observedState": observed_state,
        "runtimeSignalState": runtime_signal_state,
        "applicationId": package_name,
        "deviceSerial": ctx.resolved_device_serial,
        "package": package_version_info(ctx, strict=True),
        "processes": {
            "launcher": launcher_pid,
            "game": game_pid,
            "diag": diag_pid,
            "logcat": logcat_pid,
        },
        "storage": sts_root,
        "desktopJarPatch": desktop_jar_patch,
        "bootBridge": boot,
        "latestLog": {
            "lastNonBlankLine": last_non_blank_line(latest_log_tail),
            "crashMarker": crash_marker,
            "singleRoomResult": single_room_result,
        },
        "harnessLogcat": harness_logcat,
    }


def update_status_harness_logcat(ctx: HarnessContext, status: dict[str, Any] | None, logcat_path: Path | str) -> None:
    import time as _time
    from scripts.tools.lib.sts_harness import read_local_text_tail
    if status is None or not str(logcat_path).strip():
        return
    previous_crash = None
    if status.get("harnessLogcat") is not None:
        previous_crash = status["harnessLogcat"].get("crash")
    logcat_text = read_local_text_tail(logcat_path, max_bytes=262144)
    crash = find_harness_logcat_crash(logcat_text, ctx.application_id or "")
    if crash is None and previous_crash is not None:
        crash = previous_crash
    status["harnessLogcat"] = {
        "artifact": str(logcat_path),
        "lastNonBlankLine": last_non_blank_line(logcat_text),
        "crash": crash,
    }
    if crash is not None and status.get("observedState") not in ("READY", "FAIL", "CRASH_MARKER", "LOGCAT_CRASH"):
        status["observedState"] = "LOGCAT_CRASH"
        status["runtimeSignalState"] = "LOGCAT_CRASH"
    elif crash is not None and status.get("runtimeSignalState") is None:
        status["runtimeSignalState"] = "LOGCAT_CRASH"


def wait_harness_status(
    ctx: HarnessContext,
    logcat_capture: Any = None,
    *,
    timeout_seconds: int = 300,
    poll_interval_seconds: int = 2,
    autoplay_mode: str = "normal",
) -> dict[str, Any]:
    import time as _time
    from scripts.tools.lib.sts_harness import read_local_text_tail
    safe_timeout = max(1, timeout_seconds)
    safe_poll = max(0.25, poll_interval_seconds)
    deadline = _time.monotonic() + safe_timeout
    latest_status = None
    saw_game_process = False
    game_exit_first_seen = None
    while True:
        logcat_text = None
        logcat_path = ""
        if logcat_capture is not None:
            logcat_path = str(logcat_capture.log_path)
            logcat_text = read_local_text_tail(logcat_capture.log_path, max_bytes=262144)
        latest_status = harness_status(ctx, logcat_text, logcat_path)
        terminal_states = (
            ("SINGLE_ROOM_COMPLETE", "FAIL", "CRASH_MARKER")
            if autoplay_mode == "single_room"
            else ("READY", "FAIL", "CRASH_MARKER")
        )
        if latest_status["observedState"] in terminal_states:
            return latest_status
        if latest_status.get("harnessLogcat") is not None and latest_status["harnessLogcat"].get("crash") is not None:
            latest_status["observedState"] = "LOGCAT_CRASH"
            latest_status["runtimeSignalState"] = "LOGCAT_CRASH"
            return latest_status
        if latest_status["processes"]["game"].strip():
            saw_game_process = True
            game_exit_first_seen = None
        elif saw_game_process:
            now = _time.monotonic()
            if game_exit_first_seen is None:
                game_exit_first_seen = now
            elif now - game_exit_first_seen >= safe_poll:
                latest_status["observedState"] = "PROCESS_EXITED"
                if latest_status.get("runtimeSignalState") is None:
                    latest_status["runtimeSignalState"] = "PROCESS_EXITED"
                return latest_status
        if _time.monotonic() >= deadline:
            return latest_status
        _time.sleep(safe_poll)
