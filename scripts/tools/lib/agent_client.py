"""Unified client for the device-side game-probe TCP protocol.

替代原先 AgentBridge + AgentProtocol 的分离设计。通过 connector daemon
的 connect_stream 建立透传通道，不直接开 TCP 连接。

使用流程:
    conn = ConnectorClient()
    conn.connect()
    conn.select(get_test_device_serial())  # 从 STS_CONNECTOR_PORT/TOKEN 或 STS_TEST_DEVICE 读取
    stream = conn.connect_stream(port=9099)

    client = AgentClient(stream=stream)
    agent_id = client.attach("tracing@classes=com.megacrit.*")
    state = client.observe()
    client.load_agent("/data/...arthas-bridge.jar", "port=8099")
    client.close()
"""

from __future__ import annotations

import base64
import json
import socket
import time
from pathlib import Path
from typing import Any, Callable


class AgentError(Exception):
    pass


class AgentClient:
    """Unified client for game-probe TCP protocol.

    ｜ 协议层：
    ｜ AgentClient (Python)
    ｜   通过 connector 获取端口转发
    ｜   直接 TCP 连接到 127.0.0.1:<port>
    ｜   发送 ATTACH / DETACH / OBSERVE / EXEC / LOAD_AGENT 等命令
    ｜
    ｜ 设备端：
    ｜ GameProbe (Java agent, :9099)
    """

    def __init__(
        self,
        connector: Any = None,
        host: str = "127.0.0.1",
        port: int = 9099,
        stream: Any = None,
    ) -> None:
        self._connector = connector
        self._host = host
        self._port = port
        self._stream = stream
        self._sock: socket.socket | None = None
        self._reader: Any = None
        self._writer: Any = None
        self._forwarded = False

    # ── Connection lifecycle ──────────────────────────────────────

    def connect(self, timeout: float = 10.0) -> None:
        """建立与设备端 game-probe 的连接（已有 stream 时跳过）。"""
        if self._stream is not None:
            return

        if self._connector is not None:
            from scripts.tools.connector.client import Stream
            self._stream = self._connector.connect_stream(port=self._port)
            return

        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.settimeout(timeout)
        self._sock.connect((self._host, self._port))
        self._reader = self._sock.makefile("r", encoding="utf-8", newline="\n")
        self._writer = self._sock.makefile("w", encoding="utf-8", newline="\n")

    def close(self) -> None:
        """关闭连接并清理端口转发。"""
        if self._stream is not None:
            self._stream.close()
            return
        try:
            self._send("QUIT")
            self._read_line()
        except Exception:
            pass
        try:
            self._sock.close()
        except Exception:
            pass
        self._sock = None
        self._reader = None
        self._writer = None

        if self._forwarded and self._connector is not None:
            try:
                self._connector.unforward(port=self._port)
            except Exception:
                pass
            self._forwarded = False

    def is_connected(self) -> bool:
        return self._stream is not None or self._sock is not None

    # ── Basic protocol I/O ────────────────────────────────────────

    def send(self, line: str) -> str:
        self._send(line)
        return self._read_line()

    def _send(self, line: str) -> None:
        if self._stream is not None:
            self._stream.write((line + "\n").encode("utf-8"))
            return
        self._writer.write(line + "\n")
        self._writer.flush()

    def _read_line(self) -> str:
        if self._stream is not None:
            return self._stream.readline().decode("utf-8").rstrip("\r")
        return self._reader.readline().rstrip("\n\r")

    # ── Agent lifecycle (原 AgentBridge) ───────────────────────────

    def attach(self, spec: str, args: dict[str, Any] | None = None) -> str:
        """附加一个 monitor agent，返回 agent_id。"""
        args_json = json.dumps(args or {})
        resp = self.send(f"ATTACH {spec} {args_json}")
        if resp.startswith("OK "):
            return resp[3:]
        raise AgentError(resp)

    def detach(self, agent_id: str) -> None:
        resp = self.send(f"DETACH {agent_id}")
        if resp != "OK":
            raise AgentError(resp)

    def list_agents(self) -> list[dict[str, str]]:
        resp = self.send("LIST")
        if not resp.startswith("MONITORS"):
            raise AgentError(resp)
        agents: list[dict[str, str]] = []
        parts = resp.split()[1:]
        for entry in parts:
            fields = entry.split(":")
            if len(fields) >= 2:
                agents.append({
                    "id": fields[0],
                    "spec": fields[1],
                    "state": fields[2] if len(fields) > 2 else "unknown",
                })
        return agents

    def status(self, agent_id: str) -> dict[str, Any]:
        resp = self.send(f"STATUS {agent_id}")
        if not resp.startswith("STATUS "):
            raise AgentError(resp)
        parts = resp.split()
        return {
            "id": parts[1],
            "state": parts[2],
            "uptime_ms": int(parts[3]),
            "event_count": int(parts[4]),
        }

    # ── OBSERVE / EXEC (原 AgentProtocol) ──────────────────────────

    def observe(self) -> dict[str, Any]:
        """获取当前游戏状态快照。"""
        resp = self.send("OBSERVE")
        if resp.startswith("STATE "):
            return json.loads(resp[6:])
        if resp.startswith("ERROR "):
            raise AgentError(resp)
        return json.loads(resp) if resp else {}

    def ready(self) -> bool:
        """Return whether the game classloader and BaseMod console are usable."""
        resp = self.send("READY")
        if resp == "READY":
            return True
        if resp.startswith("ERROR "):
            return False
        return False

    def execute(self, command: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """执行一条游戏命令。"""
        args_json = json.dumps(params or {})
        resp = self.send(f"EXEC {command} {args_json}")
        if resp.startswith("RESULT "):
            return json.loads(resp[7:])
        if resp.startswith("ERROR "):
            raise AgentError(resp)
        return {"executed": False, "response": resp}

    def console_exec(self, command: str) -> dict[str, Any]:
        """Execute a BaseMod DevConsole command."""
        resp = self.send(f"CONSOLE {command}")
        if resp.startswith("RESULT "):
            return json.loads(resp[7:])
        if resp.startswith("ERROR "):
            raise AgentError(resp)
        return {"executed": False, "error": f"unexpected response: {resp}"}

    # ── Performance ───────────────────────────────────────────────

    def perf_start(self, agent_id: str) -> None:
        resp = self.send(f"PERF_START {agent_id}")
        if resp != "OK":
            raise AgentError(resp)

    def perf_stop(self, agent_id: str) -> dict[str, Any]:
        resp = self.send(f"PERF_STOP {agent_id}")
        if resp.startswith("PERF "):
            return json.loads(resp[5:])
        if resp.startswith("OK"):
            return {"status": "ok"}
        raise AgentError(resp)

    # ── Class dump / redefine / hot reload ─────────────────────────

    def dump_class(self, class_name: str) -> bytes:
        resp = self.send(f"DUMP_CLASS {class_name}")
        if resp.startswith("BYTECODE "):
            return base64.b64decode(resp[9:])
        if resp.startswith("ERROR "):
            raise AgentError(resp)
        raise AgentError(f"Unexpected DUMP_CLASS response: {resp}")

    def redefine_class(self, class_bytes: bytes) -> None:
        b64 = base64.b64encode(class_bytes).decode("ascii")
        resp = self.send(f"REDEFINE_CLASS {b64}")
        if resp != "OK":
            raise AgentError(resp)

    def dump_and_save(self, class_name: str, output_path: str) -> bytes:
        data = self.dump_class(class_name)
        with open(output_path, "wb") as f:
            f.write(data)
        return data

    def load_and_redefine(self, class_name: str, class_file_path: str) -> None:
        with open(class_file_path, "rb") as f:
            data = f.read()
        if data[:4] != b'\xca\xfe\xba\xbe':
            raise ValueError(f"Not a valid class file: {class_file_path}")
        self.redefine_class(data)

    # ── LOAD_AGENT — 动态加载外部 agent (如 Arthas) ────────────────

    def load_agent(self, jar_path: str, agent_args: str = "") -> None:
        """通过 game-probe 动态加载一个外部 Java agent JAR。

        Args:
            jar_path: 设备上 agent JAR 的路径
            agent_args: 传给 agentmain 的参数（如 "http-port=8563"）

        原理:
            game-probe 通过 Instrumentation.appendToSystemClassLoaderSearch()
            将 JAR 加入类路径，然后反射调用 agent-class 的 agentmain() 方法。
            参见 AgentSession.handleLoadAgent()。
        """
        line = f"LOAD_AGENT {jar_path}"
        if agent_args:
            line += f" {agent_args}"
        resp = self.send(line)
        if resp != "OK":
            raise AgentError(resp)

    # ── Event subscription ────────────────────────────────────────

    def subscribe_events(
        self,
        callback: Callable[[str], None],
        timeout_seconds: float | None = None,
        poll_interval: float = 0.2,
    ) -> int:
        count = 0
        deadline = time.monotonic() + timeout_seconds if timeout_seconds else None
        while True:
            remain = None
            if deadline:
                remain = deadline - time.monotonic()
                if remain <= 0:
                    break
                timeout = min(remain, poll_interval)
            else:
                timeout = poll_interval
            if self._sock is not None:
                self._sock.settimeout(timeout)
            elif self._stream is not None and hasattr(self._stream, "_sock"):
                try:
                    self._stream._sock.settimeout(timeout)
                except Exception:
                    pass
            try:
                line = self._read_line()
            except socket.timeout:
                if deadline and time.monotonic() >= deadline:
                    break
                continue
            except OSError:
                break
            if not line:
                continue
            if line.startswith("DATA "):
                rest = line[5:]
                first_space = rest.index(" ")
                callback(rest[first_space + 1:])
                count += 1
            elif line.startswith("DATA"):
                callback(line[4:].strip())
                count += 1
        return count

    def subscribe_and_capture(
        self,
        agent_id: str,
        output_path: Path,
        timeout_seconds: float | None = None,
    ) -> int:
        resp = self.send(f"SUBSCRIBE {agent_id}")
        if resp != "OK":
            raise AgentError(resp)
        count = 0
        try:
            with output_path.open("w", encoding="utf-8") as f:
                def write_line(json_str: str) -> None:
                    nonlocal count
                    f.write(json_str + "\n")
                    f.flush()
                    count += 1
                count = self.subscribe_events(
                    write_line,
                    timeout_seconds,
                    poll_interval=1.0,
                )
        finally:
            try:
                self.send(f"UNSUBSCRIBE {agent_id}")
            except Exception:
                pass
        return count

    # ── Context manager ───────────────────────────────────────────

    def __enter__(self) -> AgentClient:
        self.connect()
        return self

    def __exit__(self, *args: Any) -> None:
        self.close()
