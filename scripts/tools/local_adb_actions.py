"""Structured device operations for local_adb; never accepts arbitrary shell text."""
from __future__ import annotations

import json
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ACTIONS = ("device-info", "package-info", "install", "list-instrumentation", "instrument",
           "screenshot", "tap", "swipe", "keyevent", "start-activity", "force-stop")
# Keep the JSON response within the plugin's 12 KB capture, including JSON escapes.
PREVIEW_BYTES = 800
FIELDS = {
    "device-info": set(), "package-info": {"packageName"},
    "install": {"apkPath", "fastDeploy"}, "list-instrumentation": set(),
    "instrument": {"runner", "testClasses", "extras"}, "screenshot": set(),
    "tap": {"x", "y"}, "swipe": {"x", "y", "endX", "endY", "durationMs"},
    "keyevent": {"keyCode"}, "start-activity": {"component"}, "force-stop": {"packageName"},
}


def token(value, label, pattern):
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise ValueError(f"Invalid {label}")
    return value


def integer(data, name, maximum=100000):
    value = data.get(name)
    if type(value) is not int or not 0 <= value <= maximum:
        raise ValueError(f"Invalid {name}")
    return str(value)


def prepare(root: Path, action: str, data: dict):
    """Validate everything before connection setup or device side effects."""
    if action not in ACTIONS or not isinstance(data, dict):
        raise ValueError("Invalid local ADB action or request")
    if set(data) - FIELDS[action] - {"deviceSerial", "timeoutSeconds"}:
        raise ValueError("Unsupported local ADB request field")
    timeout = data.get("timeoutSeconds", 180 if action == "install" else 120)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError("timeoutSeconds must be 1..3600")
    package = lambda: token(data.get("packageName"), "packageName", r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+")
    shell = None
    if action == "device-info":
        shell = ["getprop"]
    elif action == "package-info":
        shell = ["dumpsys", "package", package()]
    elif action == "install":
        token(data.get("deviceSerial"), "explicit user-approved deviceSerial", r"[^\s-][^\s]*")
        if data["deviceSerial"].lower() in ("auto", "all", "default", "*"):
            raise ValueError("Installation requires explicit user-approved deviceSerial")
        path = data.get("apkPath")
        if not isinstance(path, str) or not path:
            raise ValueError("apkPath is required")
        apk = (root / path).resolve()
        if not apk.is_relative_to(root.resolve()) or not apk.is_file() or apk.suffix.lower() != ".apk":
            raise ValueError("apkPath must be an existing APK inside the repository")
        fast = data.get("fastDeploy", True)
        if type(fast) is not bool:
            raise ValueError("Invalid fastDeploy")
        return ["install", *(["--fastdeploy"] if fast else []), "-r", str(apk)], timeout
    elif action == "list-instrumentation":
        shell = ["pm", "list", "instrumentation"]
    elif action == "instrument":
        runner = token(data.get("runner"), "runner", r"[A-Za-z][A-Za-z0-9_.]*/[A-Za-z_.][A-Za-z0-9_.$]*")
        shell = ["am", "instrument", "-w", "-r"]
        classes = data.get("testClasses", [])
        if not isinstance(classes, list) or len(classes) > 100:
            raise ValueError("Invalid testClasses")
        for cls in classes:
            token(cls, "test class", r"[A-Za-z_][A-Za-z0-9_.$]*(?:#[A-Za-z_][A-Za-z0-9_]*)?")
        if classes:
            shell += ["-e", "class", ",".join(classes)]
        extras = data.get("extras", {})
        if not isinstance(extras, dict) or len(extras) > 50:
            raise ValueError("Invalid extras")
        for key, value in extras.items():
            token(key, "extra key", r"[A-Za-z_][A-Za-z0-9_.-]*")
            if key == "class" or not isinstance(value, str) or len(value) > 4096 or "\0" in value:
                raise ValueError("Invalid extra value (use testClasses for class)")
            shell += ["-e", key, value]
        shell += [runner]
    elif action == "screenshot":
        return ["exec-out", "screencap", "-p"], timeout
    elif action in ("tap", "swipe"):
        shell = ["input", action, integer(data, "x"), integer(data, "y")]
        if action == "swipe":
            shell += [integer(data, "endX"), integer(data, "endY"), integer({"durationMs": 300, **data}, "durationMs", 60000)]
    elif action == "keyevent":
        shell = ["input", "keyevent", integer(data, "keyCode", 1000)]
    elif action == "start-activity":
        shell = ["am", "start", "-W", "-n", token(data.get("component"), "component", r"[A-Za-z][A-Za-z0-9_.]*/[A-Za-z_.][A-Za-z0-9_.$]*")]
    elif action == "force-stop":
        shell = ["am", "force-stop", package()]
    return ["shell", shlex.join(shell)], timeout


def run(action: str, data: dict):
    from scripts.tools import local_adb

    args, timeout = prepare(local_adb.ROOT, action, data)
    c = local_adb.config()
    serial = data.get("deviceSerial")
    if serial is not None and serial != c["STS_ADB_DEVICE_ENDPOINT"]:
        raise ValueError("deviceSerial must match local configuration")
    env = local_adb.ensure()
    directory = local_adb.ROOT / "agent-tmp/local-adb/operations"
    directory.mkdir(parents=True, exist_ok=True)
    # File-backed capture bounds memory even for a noisy test runner.
    with tempfile.NamedTemporaryFile(dir=directory, prefix=action + "-", suffix=".png" if action == "screenshot" else ".log", delete=False) as output:
        path = Path(output.name)
        try:
            with tempfile.TemporaryFile(dir=directory) as errors:
                result = subprocess.run(local_adb.command(c, "-s", c["STS_ADB_DEVICE_ENDPOINT"], *args),
                                        cwd=local_adb.ROOT, env=env, stdout=output, stderr=errors, timeout=timeout)
                output.flush()
                errors.seek(0)
                stderr = errors.read(PREVIEW_BYTES).decode("utf-8", errors="replace")
        except BaseException:
            path.unlink(missing_ok=True)
            raise
    if action == "screenshot":
        with path.open("rb") as stream:
            signature = stream.read(8)
        if result.returncode != 0 or signature != b"\x89PNG\r\n\x1a\n":
            path.unlink(missing_ok=True)
            raise RuntimeError("Screenshot capture failed")
        text = ""
    else:
        with path.open("rb") as stream:
            text = stream.read(PREVIEW_BYTES).decode("utf-8", errors="replace")
    response = {"action": action, "exitCode": result.returncode, "stdout": text,
                "stderr": stderr, "artifact": str(path.relative_to(local_adb.ROOT)),
                "truncated": action != "screenshot" and path.stat().st_size > PREVIEW_BYTES}
    if action == "device-info" and result.returncode == 0:
        props = dict(re.findall(r"^\[([^\]]+)\]: \[(.*)\]$", path.read_text(errors="replace"), re.MULTILINE))
        response["device"] = {key: props.get(prop, "") for key, prop in {
            "model": "ro.product.model", "androidVersion": "ro.build.version.release",
            "sdk": "ro.build.version.sdk", "abi": "ro.product.cpu.abi", "bootCompleted": "sys.boot_completed"}.items()}
        # Do not expose unrelated properties or persist a full device-property dump.
        path.write_text(json.dumps(response["device"]))
        response.update(stdout="", truncated=False)
    return response
