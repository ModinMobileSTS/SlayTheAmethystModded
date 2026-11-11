from __future__ import annotations

import json
import os
import socket as _socket
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.lib.env_device import get_test_device_serial


def _start_fake_connector_daemon() -> tuple[_socket.socket, int]:
    """Start a minimal TCP server that mimics the connector protocol."""
    server = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
    server.setsockopt(_socket.SOL_SOCKET, _socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    port = server.getsockname()[1]

    def handle():
        server.settimeout(5)
        try:
            conn, _ = server.accept()
            conn.settimeout(5)
            reader = conn.makefile("r", encoding="utf-8", newline="\n")
            line = reader.readline().strip()
            req = json.loads(line)
            resp = json.dumps({"pong": True}) + "\n"
            conn.sendall(resp.encode("utf-8"))
            conn.close()
        except Exception:
            pass

    t = threading.Thread(target=handle, daemon=True)
    t.start()
    return server, port, t


def _start_daemon() -> ConnectorClient:
    proc = subprocess.Popen(
        ["python3", "-m", "scripts.tools.connector.daemon"],
        cwd=os.path.join(os.path.dirname(__file__), "..", "..", ".."),
        stdout=subprocess.PIPE,
        text=True,
    )
    info = json.loads(proc.stdout.readline().strip())
    proc.stdout.close()
    time.sleep(0.3)
    client = ConnectorClient(port=info["port"])
    client.connect()
    client._daemon_proc = proc
    return client


def _stop_daemon(conn: ConnectorClient) -> None:
    try:
        conn.send_request({"method": "quit"})
    except Exception:
        pass
    conn.close()
    if hasattr(conn, "_daemon_proc"):
        conn._daemon_proc.wait(timeout=5)


def _free_port() -> int:
    with _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _quit_daemon(port: int) -> None:
    try:
        with _socket.create_connection(("127.0.0.1", port), timeout=2) as sock:
            sock.sendall(b'{"method":"quit"}\n')
            sock.recv(4096)
    except OSError:
        pass


class TestConnectorDaemonIntegration(unittest.TestCase):

    def test_start_cli_exits_when_output_streams_are_combined(self):
        port = _free_port()
        repo_root = Path(__file__).resolve().parents[3]
        # This exercises legacy CLI startup, independent of a developer's local config.
        script = (
            "import sys; from pathlib import Path; "
            "from scripts.tools import local_adb; "
            "local_adb.ROOT = Path(sys.argv[1]); "
            "sys.argv = ['connector', 'start', '--port', sys.argv[2]]; "
            "from scripts.tools.connector.__main__ import main; main()"
        )
        try:
            result = subprocess.run(
                [sys.executable, "-c", script, str(repo_root / "agent-tmp/legacy-cli-test"), str(port)],
                cwd=repo_root,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=5,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn(f"Connector daemon started on port {port}.", result.stdout)
        finally:
            _quit_daemon(port)

    def test_ping_pong_over_tcp(self):
        server, port, thread = _start_fake_connector_daemon()

        client = ConnectorClient(port=port)
        client.connect()
        response = client.send_request({"method": "ping"})
        client.close()

        thread.join(timeout=3)
        server.close()

        self.assertEqual(response, {"pong": True})

    def test_daemon_devices_integration(self):
        conn = _start_daemon()
        try:
            response = conn.send_request({"method": "ping"})
            self.assertEqual(response, {"pong": True})
        finally:
            _stop_daemon(conn)

    def test_daemon_real_device_flow(self):
        if not get_test_device_serial() or get_test_device_serial() == "auto":
            self.skipTest("set STS_TEST_DEVICE to run real-device connector integration")
        import tempfile

        conn = _start_daemon()
        try:
            resp = conn.send_request({"method": "ping"})
            self.assertEqual(resp, {"pong": True})

            devices = conn.devices()
            self.assertIsInstance(devices, list)
            serials = [d["serial"] for d in devices]

            expected_device = get_test_device_serial()
            if expected_device == "auto":
                expected_device = serials[0]
            self.assertIn(expected_device, serials)

            ok = conn.select(expected_device, timeout_ms=10000)
            self.assertTrue(ok)

            status = conn.status()
            self.assertIn("serial", status)

            result = conn.shell("echo integration-test-ok")
            self.assertEqual(result["exit"], 0)
            self.assertIn("integration-test-ok", result["stdout"])

            fw = conn.forward(port=9099)
            self.assertTrue(fw.get("ok"))
            un = conn.unforward(port=9099)
            self.assertTrue(un)

            with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
                f.write("push-test-content\n")
                local_push = f.name
            local_pull = f"{local_push}.pulled"
            resp = conn.send_request({
                "method": "push",
                "params": {
                    "local": local_push,
                    "remote": "/sdcard/sts-daemon-push-test.txt",
                },
            })
            self.assertTrue(resp.get("ok"))
            resp = conn.send_request({
                "method": "pull",
                "params": {
                    "remote": "/sdcard/sts-daemon-push-test.txt",
                    "local": local_pull,
                },
            })
            self.assertTrue(resp.get("ok"))
            pulled = open(local_pull).read()
            self.assertEqual(pulled, "push-test-content\n")
            os.unlink(local_push)
            os.unlink(local_pull)
            conn.shell("rm -f /sdcard/sts-daemon-push-test.txt")
        finally:
            _stop_daemon(conn)


if __name__ == "__main__":
    unittest.main()
