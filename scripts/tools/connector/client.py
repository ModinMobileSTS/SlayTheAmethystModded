import json
import os
import socket
import subprocess
import sys
import time
from typing import Any


class ConnectorError(RuntimeError):
    def __init__(self, message: str, code: int | None = None, response: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.response = response or {}


def resolve_connector_port(explicit: int | None = None) -> int:
    if explicit is not None:
        return int(explicit)
    env = os.environ.get("STS_CONNECTOR_PORT", "").strip()
    if env:
        return int(env)
    raise RuntimeError(
        "Connector port is required. Set STS_CONNECTOR_PORT or pass -ConnectorPort / --connector-port. "
        "Start the daemon with: python -m scripts.tools.connector start --port <port>"
    )


class ConnectorClient:

    _DAEMON_START_TIMEOUT = 5

    def __init__(self, port: int | None = None, *, auto_start: bool = True) -> None:
        """auto_start: if True (default, arthas-compatible), spawn daemon on connect failure.
        Harness should pass auto_start=False and require an already-running daemon.
        """
        self._port = resolve_connector_port(port)
        self._auto_start = auto_start
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._recv_buffer = b""
        self._selected_serial: str | None = None

    @property
    def port(self) -> int:
        return self._port

    def connect(self) -> None:
        try:
            self._sock.connect(("127.0.0.1", self._port))
        except (ConnectionRefusedError, OSError):
            if not self._auto_start:
                raise RuntimeError(
                    f"Connector daemon is not running on 127.0.0.1:{self._port}. "
                    f"Start it with: python -m scripts.tools.connector start --port {self._port}"
                ) from None
            self._start_daemon()
            self._sock.connect(("127.0.0.1", self._port))

    def _start_daemon(self) -> None:
        proc = subprocess.Popen(
            [sys.executable, "-m", "scripts.tools.connector", "daemon",
             "--port", str(self._port)],
            stdout=subprocess.PIPE,
            text=True,
            start_new_session=True,
        )
        try:
            line = proc.stdout.readline()
            info = json.loads(line.strip())
            if info.get("port") != self._port:
                proc.terminate()
                proc.wait(timeout=5)
                raise RuntimeError(
                    f"Daemon started on unexpected port {info.get('port')}")
        except json.JSONDecodeError:
            proc.terminate()
            proc.wait(timeout=5)
            raise RuntimeError("Failed to read daemon ready signal")
        time.sleep(0.3)

    def send_request(self, request: dict[str, Any]) -> dict[str, Any]:
        body = json.dumps(request, ensure_ascii=False)
        self._send(body)
        return self._recv_json()

    def send_request_ok(self, request: dict[str, Any]) -> dict[str, Any]:
        resp = self.send_request(request)
        if isinstance(resp, dict) and "error" in resp and isinstance(resp["error"], dict):
            err = resp["error"]
            raise ConnectorError(
                str(err.get("message", "connector error")),
                code=err.get("code"),
                response=resp,
            )
        return resp

    def devices(self) -> list[dict[str, Any]]:
        resp = self.send_request({"method": "devices"})
        return resp.get("devices", [])

    def select(self, serial: str, timeout_ms: int = 5000) -> bool:
        resp = self.send_request({
            "method": "select",
            "params": {"serial": serial, "timeout_ms": timeout_ms},
        })
        selected = resp.get("ok", False)
        if selected:
            self._selected_serial = serial
        return selected

    def status(self) -> dict[str, Any]:
        return self.send_request({"method": "status"})

    def forward(self, port: int) -> dict[str, Any]:
        return self.send_request({
            "method": "forward", "params": {"port": port}})

    def unforward(self, port: int) -> bool:
        resp = self.send_request({
            "method": "unforward", "params": {"port": port}})
        return resp.get("ok", False)

    def shell(self, command: str, timeout_ms: int = 30000) -> dict[str, Any]:
        return self.send_request({
            "method": "shell",
            "params": {"command": command, "timeout_ms": timeout_ms},
        })

    def adb(
        self,
        args: list[str],
        *,
        timeout_ms: int = 30000,
        capture: str = "text",
        local_path: str = "",
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "args": list(args),
            "timeout_ms": timeout_ms,
            "capture": capture,
        }
        if local_path:
            params["local_path"] = local_path
        return self.send_request({"method": "adb", "params": params})

    def install(self, local: str, *, replace: bool = True, timeout_ms: int = 180000) -> dict[str, Any]:
        return self.send_request({
            "method": "install",
            "params": {"local": local, "replace": replace, "timeout_ms": timeout_ms},
        })

    def push(self, local: str, remote: str, *, timeout_ms: int = 30000) -> bool:
        resp = self.send_request({
            "method": "push",
            "params": {"local": local, "remote": remote, "timeout_ms": timeout_ms},
        })
        return resp.get("ok", False)

    def pull(self, remote: str, local: str, *, timeout_ms: int = 30000) -> bool:
        resp = self.send_request({
            "method": "pull",
            "params": {"remote": remote, "local": local, "timeout_ms": timeout_ms},
        })
        return resp.get("ok", False)

    def logcat_dump(
        self,
        *,
        since: str = "",
        local_path: str = "",
        timeout_ms: int = 15000,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {"timeout_ms": timeout_ms}
        if since:
            params["since"] = since
        if local_path:
            params["local_path"] = local_path
        return self.send_request({"method": "logcat_dump", "params": params})

    def logcat_start(self, *, since: str = "", local_path: str = "") -> dict[str, Any]:
        params: dict[str, Any] = {}
        if since:
            params["since"] = since
        if local_path:
            params["local_path"] = local_path
        return self.send_request({"method": "logcat_start", "params": params})

    def logcat_stop(self, capture_id: str) -> dict[str, Any]:
        return self.send_request({
            "method": "logcat_stop",
            "params": {"capture_id": capture_id},
        })

    def logcat_status(self, capture_id: str = "") -> dict[str, Any]:
        params: dict[str, Any] = {}
        if capture_id:
            params["capture_id"] = capture_id
        return self.send_request({"method": "logcat_status", "params": params})

    def _send(self, line: str) -> None:
        self._sock.sendall((line + "\n").encode("utf-8"))

    def _recv_json(self) -> dict[str, Any]:
        buffer = self._recv_buffer
        while True:
            if b"\n" in buffer:
                line, self._recv_buffer = buffer.split(b"\n", 1)
                return json.loads(line.decode("utf-8"))
            chunk = self._sock.recv(4096)
            if not chunk:
                break
            buffer += chunk
        if buffer:
            self._recv_buffer = b""
            return json.loads(buffer.decode("utf-8"))
        raise ConnectorError("Connector daemon closed the control connection without a response")

    def close(self) -> None:
        try:
            self._sock.close()
        except Exception:
            pass

    def connect_stream(self, port: int) -> "Stream":
        return self._connect_stream("connect_stream", {"port": port})

    def arthas_status(self) -> dict[str, Any]:
        return self.send_request_ok({"method": "arthas_status"})

    def arthas_ensure(self, *, agent_port: int = 9099, arthas_port: int = 8099) -> dict[str, Any]:
        return self.send_request_ok({
            "method": "arthas_ensure",
            "params": {"agent_port": agent_port, "arthas_port": arthas_port},
        })

    def arthas_reset(self, *, agent_port: int = 9099, arthas_port: int = 8099) -> dict[str, Any]:
        return self.send_request_ok({
            "method": "arthas_reset",
            "params": {"agent_port": agent_port, "arthas_port": arthas_port},
        })

    def arthas_shutdown(self, *, arthas_port: int = 8099) -> dict[str, Any]:
        return self.send_request_ok({
            "method": "arthas_shutdown", "params": {"arthas_port": arthas_port},
        })

    def connect_arthas_stream(self, *, agent_port: int = 9099, arthas_port: int = 8099) -> "Stream":
        return self._connect_stream(
            "arthas_connect_stream",
            {"agent_port": agent_port, "arthas_port": arthas_port},
        )

    def _connect_stream(self, method: str, params: dict[str, Any]) -> "Stream":
        resp = self.send_request_ok({"method": method, "params": params})
        stream_id = resp.get("stream_id", "unknown")
        sock = self._sock
        initial_data = self._recv_buffer
        self._recv_buffer = b""
        # The daemon switches this request socket into raw passthrough mode
        # after sending the handshake. Opening a second control connection
        # would leave the actual passthrough socket orphaned.
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.connect(("127.0.0.1", self._port))
        if self._selected_serial:
            self.select(self._selected_serial)
        return Stream(sock=sock, stream_id=stream_id, initial_data=initial_data)


class Stream:
    def __init__(self, sock: socket.socket, stream_id: str, *, initial_data: bytes = b"") -> None:
        self._sock = sock
        self.stream_id = stream_id
        self._buffer = initial_data

    def write(self, data: bytes) -> None:
        self._sock.sendall(data)

    def readline(self) -> bytes:
        buffer = b""
        while True:
            chunk = self._sock.recv(1)
            if not chunk:
                break
            if chunk == b"\n":
                return buffer
            buffer += chunk
        return buffer

    def read(self, size: int = 4096) -> bytes:
        if self._buffer:
            data = self._buffer[:size]
            self._buffer = self._buffer[size:]
            return data
        return self._sock.recv(size)

    def close(self) -> None:
        try:
            self._sock.close()
        except Exception:
            pass
