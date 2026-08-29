"""Connector daemon lifecycle management.

Usage:
    python -m scripts.tools.connector daemon  [--port PORT]
    python -m scripts.tools.connector start   [--port PORT]
    python -m scripts.tools.connector stop    [--port PORT]
    python -m scripts.tools.connector restart [--port PORT]
    python -m scripts.tools.connector status  [--port PORT]
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import textwrap
import time

DEFAULT_PORT_ENV = "STS_CONNECTOR_PORT"


def _resolve_port(args_port: int | None) -> int:
    if args_port is not None:
        return args_port
    env_val = os.environ.get(DEFAULT_PORT_ENV)
    if env_val:
        return int(env_val)
    raise SystemExit(f"Error: specify --port or set {DEFAULT_PORT_ENV}")


def _ping(port: int) -> bool:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(2)
        sock.connect(("127.0.0.1", port))
        sock.sendall(b'{"method":"ping"}\n')
        data = sock.recv(4096)
        sock.close()
        return b"pong" in data
    except Exception:
        return False


def _send_quit(port: int) -> bool:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(2)
        sock.connect(("127.0.0.1", port))
        sock.sendall(b'{"method":"quit"}\n')
        sock.recv(4096)
        sock.close()
        return True
    except Exception:
        return False


def _start_daemon(port: int) -> subprocess.Popen:
    proc = subprocess.Popen(
        [sys.executable, "-m", "scripts.tools.connector", "daemon", "--port", str(port)],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        start_new_session=True,
    )
    try:
        line = proc.stdout.readline()
        info = json.loads(line.strip())
        if info.get("port") != port:
            proc.terminate()
            proc.wait(timeout=5)
            raise SystemExit(f"Daemon started on unexpected port {info.get('port')}")
    except (json.JSONDecodeError, SystemExit):
        raise
    except Exception as e:
        proc.terminate()
        proc.wait(timeout=5)
        raise SystemExit(f"Failed to read daemon ready signal: {e}")
    finally:
        if proc.stdout is not None:
            proc.stdout.close()
    return proc


def cmd_start(args: argparse.Namespace) -> None:
    port = _resolve_port(args.port)
    if _ping(port):
        print(f"Connector daemon is already running on port {port}.")
        return
    _start_daemon(port)
    if _ping(port):
        print(f"Connector daemon started on port {port}.")
    else:
        raise SystemExit(f"Failed to start connector daemon on port {port}.")


def cmd_stop(args: argparse.Namespace) -> None:
    port = _resolve_port(args.port)
    if not _ping(port):
        print(f"No connector daemon running on port {port}.")
        return
    if _send_quit(port):
        print("Connector daemon stopped.")
    else:
        raise SystemExit(f"Failed to stop connector daemon on port {port}.")


def cmd_restart(args: argparse.Namespace) -> None:
    port = _resolve_port(args.port)
    cmd_stop(args)
    deadline = time.monotonic() + 5.0
    while _ping(port) and time.monotonic() < deadline:
        time.sleep(0.1)
    time.sleep(0.1)
    cmd_start(args)


def cmd_status(args: argparse.Namespace) -> None:
    port = _resolve_port(args.port)
    if _ping(port):
        print(f"Connector daemon running on port {port}.")
    else:
        print(f"Connector daemon not running on port {port}.")


def main() -> None:
    daemon_cmd = len(sys.argv) >= 2 and sys.argv[1] == "daemon"

    parser = argparse.ArgumentParser(
        prog="python -m scripts.tools.connector",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description="Connector daemon lifecycle management.",
        epilog=textwrap.dedent(f"""\
            Environment:
              {DEFAULT_PORT_ENV}    default port for start/stop/restart/status

            Examples:
              python -m scripts.tools.connector start --port 15555
              python -m scripts.tools.connector status
              python -m scripts.tools.connector stop --port 15555
        """),
    )
    sub = parser.add_subparsers(dest="command")
    if not daemon_cmd:
        sub.add_parser("daemon", help="Run daemon in foreground")
    for name in ("start", "stop", "restart", "status"):
        p = sub.add_parser(name, help=f"{name.capitalize()} the daemon")
        p.add_argument("--port", type=int, default=None,
                       help=f"TCP port (default: ${DEFAULT_PORT_ENV})")

    if daemon_cmd:
        sys.argv.pop(1)
        from scripts.tools.connector.daemon import main as daemon_main
        daemon_main()
        return

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        raise SystemExit(1)

    if args.command == "start":
        cmd_start(args)
    elif args.command == "stop":
        cmd_stop(args)
    elif args.command == "restart":
        cmd_restart(args)
    elif args.command == "status":
        cmd_status(args)


if __name__ == "__main__":
    main()
