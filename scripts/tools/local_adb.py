"""Workspace-owned wireless ADB and connector. No fallback to the global server."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / "agent-tmp/local-adb"
KEYS = {"STS_ADB_BINARY", "STS_ADB_SERVER_PORT", "STS_ADB_DEVICE_ENDPOINT"}


def config() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ROOT / ".env.local"
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, sep, value = line.partition("=")
            if not sep or key.strip() not in KEYS:
                raise RuntimeError("Unsupported local ADB configuration key")
            values[key.strip()] = value.strip().strip("\"'")
    values.update({k: os.environ[k] for k in KEYS if k in os.environ})
    binary = Path(values.get("STS_ADB_BINARY", "")).expanduser()
    if not binary.is_absolute() or not binary.is_file():
        raise RuntimeError("Set STS_ADB_BINARY to an absolute adb binary path in .env.local")
    port = int(values.get("STS_ADB_SERVER_PORT", "15037"))
    if not 1024 <= port <= 65535 or port == 5037:
        raise RuntimeError("ADB server port must be 1024..65535, excluding 5037")
    endpoint = values.get("STS_ADB_DEVICE_ENDPOINT", "")
    host, sep, remote_port = endpoint.rpartition(":")
    if not sep or not host or not remote_port.isdecimal() or not 1 <= int(remote_port) <= 65535:
        raise RuntimeError("Set STS_ADB_DEVICE_ENDPOINT to host:wireless-debugging-port in .env.local")
    values["STS_ADB_BINARY"] = str(binary.resolve())
    values["STS_ADB_SERVER_PORT"] = str(port)
    return values


def command(c: dict[str, str], *args: str) -> list[str]:
    return [c["STS_ADB_BINARY"], "-H", "127.0.0.1", "-P", c["STS_ADB_SERVER_PORT"], *args]


def environment(c: dict[str, str]) -> dict[str, str]:
    env = dict(os.environ)
    env.update(c)
    env["ADB_SERVER_SOCKET"] = "tcp:127.0.0.1:" + c["STS_ADB_SERVER_PORT"]
    env["ANDROID_ADB_SERVER_PORT"] = c["STS_ADB_SERVER_PORT"]
    env["ANDROID_SERIAL"] = c["STS_ADB_DEVICE_ENDPOINT"]
    env["ADB_MDNS_AUTO_CONNECT"] = "0"
    env["ADB_LOCAL_TRANSPORT_MAX_PORT"] = "0"
    # Do not inherit a previous connector's port or device selection.
    env.pop("STS_CONNECTOR_PORT", None)
    env["STS_TEST_DEVICE"] = c["STS_ADB_DEVICE_ENDPOINT"]
    return env


@contextmanager
def locked():
    if sys.platform != "linux":
        raise RuntimeError("Managed local ADB currently requires Linux")
    import fcntl
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    with open(STATE / "lifecycle.lock", "a") as lock:
        deadline = time.monotonic() + 40
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() > deadline:
                    raise RuntimeError("Local ADB lifecycle lock busy")
                time.sleep(.1)
        try:
            yield
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def listening(port: int) -> bool:
    with socket.socket() as sock:
        sock.settimeout(.3)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def request(port: int, method: str) -> dict:
    with socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
        sock.sendall((json.dumps({"method": method}) + "\n").encode())
        with sock.makefile("rb") as stream:
            return json.loads(stream.readline(65536))


def identity(c: dict[str, str]) -> dict:
    return {"root": str(ROOT), "binary": c["STS_ADB_BINARY"], "serverPort": c["STS_ADB_SERVER_PORT"]}


def read_state() -> dict:
    path = STATE / "state.json"
    return json.loads(path.read_text()) if path.exists() else {}


def write_state(state: dict) -> None:
    path = STATE / "state.json.tmp"
    path.write_text(json.dumps(state))
    path.replace(STATE / "state.json")


def server_owned(state: dict, c: dict[str, str]) -> bool:
    """Verify the original foreground server, not just a stale port registration (Linux)."""
    try:
        pid = int(state["adbPid"])
        proc = Path(f"/proc/{pid}")
        argv = (proc / "cmdline").read_bytes().split(b"\0")
        return ((proc / "exe").resolve() == Path(c["STS_ADB_BINARY"]).resolve()
                and b"nodaemon" in argv
                and f"tcp:{c['STS_ADB_SERVER_PORT']}".encode() in argv)
    except (OSError, KeyError, ValueError):
        return False


def connector_owned(state: dict, c: dict[str, str]) -> bool:
    try:
        status = request(state["connectorPort"], "local_adb_identity")
        return status.get("localAdbIdentity") == identity(c)
    except (OSError, ValueError, KeyError):
        return False


def validate_expected_serial(c: dict[str, str], expected_serial: str | None) -> None:
    """Check a caller's target without connecting; configuration is not install consent."""
    if expected_serial is not None and expected_serial != c["STS_ADB_DEVICE_ENDPOINT"]:
        raise RuntimeError("Expected device serial differs from the configured local wireless device; refusing operation")


def ensure(*, expected_serial: str | None = None) -> dict[str, str]:
    c = config()
    validate_expected_serial(c, expected_serial)
    env = environment(c)
    with locked():
        state = read_state()
        port = int(c["STS_ADB_SERVER_PORT"])
        same = state.get("identity") == identity(c)
        if state and not same:
            raise RuntimeError("Local ADB configuration changed; stop the old managed instance before changing configuration")
        if listening(port) and (not same or not server_owned(state, c)):
            raise RuntimeError("Configured ADB port is occupied by an unowned service; choose another port")
        if not listening(port):
            log = open(STATE / "adb.log", "ab")
            try:
                proc = subprocess.Popen([c["STS_ADB_BINARY"], "--one-device", "__sts_wireless_only__", "-L", f"tcp:{port}", "server", "nodaemon"],
                                        env=env, stdout=log, stderr=log, start_new_session=True)
            finally:
                log.close()
            state.update(identity=identity(c), adbPid=proc.pid)
            write_state(state)
            deadline = time.monotonic() + 10
            while not listening(port):
                if proc.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Project ADB failed to start; inspect agent-tmp/local-adb/adb.log")
                time.sleep(.1)
        try:
            subprocess.run(command(c, "connect", c["STS_ADB_DEVICE_ENDPOINT"]), env=env,
                           check=True, capture_output=True, timeout=15)
        except subprocess.TimeoutExpired:
            raise RuntimeError(
                "Wireless ADB connect timed out after 15s; check the phone's network/VPN and "
                "wireless debugging port, then retry. See agent-tmp/local-adb/adb.log"
            ) from None
        except subprocess.CalledProcessError as exc:
            raise RuntimeError(
                f"Wireless ADB connect failed (exit {exc.returncode}); check the phone's "
                "network and debugging port. See agent-tmp/local-adb/adb.log"
            ) from None
        try:
            result = subprocess.run(command(c, "-s", c["STS_ADB_DEVICE_ENDPOINT"], "get-state"),
                                    env=env, capture_output=True, text=True, timeout=10)
        except subprocess.TimeoutExpired:
            raise RuntimeError(
                "Wireless ADB device-state check timed out after 10s; check the phone's "
                "connection and debugging authorization. See agent-tmp/local-adb/adb.log"
            ) from None
        if result.returncode or result.stdout.strip() != "device":
            raise RuntimeError("Configured wireless device is not online/authorized; check its connection port and pairing")
        if not connector_owned(state, c):
            with socket.socket() as sock:
                sock.bind(("127.0.0.1", 0))
                connector_port = sock.getsockname()[1]
            env["STS_LOCAL_ADB_IDENTITY"] = json.dumps(identity(c))
            with open(STATE / "connector.log", "ab") as log:
                proc = subprocess.Popen([sys.executable, "-m", "scripts.tools.connector", "daemon", "--port", str(connector_port)],
                                        cwd=ROOT, env=env, stdout=log, stderr=log, start_new_session=True)
            state["connectorPort"] = connector_port
            state["connectorPid"] = proc.pid
            write_state(state)
            deadline = time.monotonic() + 10
            while not connector_owned(state, c):
                if proc.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Project connector failed to start; inspect agent-tmp/local-adb/connector.log")
                time.sleep(.1)
        env["STS_CONNECTOR_PORT"] = str(state["connectorPort"])
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-serial", help="Exact expected device; checked before local ADB lifecycle or device commands")
    parser.add_argument("action", choices=["ensure", "status", "stop", "run", "adb"])
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.action not in ("run", "adb") and args.args:
        parser.error("Lifecycle actions accept no trailing arguments; put --expected-serial before the action")
    if args.expected_serial is not None:
        validate_expected_serial(config(), args.expected_serial)
    if args.action in ("run", "adb"):
        command_args = args.args[1:] if args.args[:1] == ["--"] else args.args
        if not command_args or (args.action == "run" and command_args[0].startswith("-")):
            parser.error("run requires a command after --")
        if args.action == "adb":
            if command_args[0].startswith("-") or command_args[0] in ("kill-server", "start-server", "server", "connect", "disconnect", "pair", "attach", "usb", "tcpip"):
                parser.error("Use lifecycle actions for server management; device comes from local configuration")
        env = ensure(expected_serial=args.expected_serial)
        if args.action == "adb":
            command_args = command(env, "-s", env["STS_ADB_DEVICE_ENDPOINT"], *command_args)
        raise SystemExit(subprocess.call(command_args, cwd=ROOT, env=env))
    if args.action == "ensure":
        ensure(expected_serial=args.expected_serial)
        print(json.dumps({"ready": True}))
    elif args.action == "status":
        c = config()
        with locked():
            state = read_state()
            print(json.dumps({"managed": state.get("identity") == identity(c),
                              "serverReady": server_owned(state, c) and listening(int(c["STS_ADB_SERVER_PORT"])),
                              "connectorReady": connector_owned(state, c)}))
    else:
        c = config()
        with locked():
            state = read_state()
            if not state:
                return
            if state.get("identity") != identity(c):
                raise RuntimeError("Configuration does not match managed instance; refusing stop")
            if connector_owned(state, c):
                request(state["connectorPort"], "quit")
            if listening(int(c["STS_ADB_SERVER_PORT"])):
                if not server_owned(state, c):
                    raise RuntimeError("ADB process ownership changed; refusing stop")
                subprocess.run(command(c, "kill-server"), env=environment(c), check=True, capture_output=True, timeout=10)
            (STATE / "state.json").unlink(missing_ok=True)
        print(json.dumps({"stopped": True}))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as exc:
        # Device endpoints and credentials must never be echoed in tool errors.
        print(f"Local ADB error: {exc}" if isinstance(exc, RuntimeError) else "Local ADB operation failed; inspect local configuration/logs", file=sys.stderr)
        raise SystemExit(1)
