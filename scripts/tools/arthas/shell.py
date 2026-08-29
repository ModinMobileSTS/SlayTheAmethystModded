from __future__ import annotations

import re
import socket
import time
from typing import Callable

from scripts.tools.connector.client import Stream

_TYPE_NOT_PRESENT = "TypeNotPresentException"
_STREAMING_COMMANDS = {"monitor", "watch", "trace", "stack"}
_PROMPT = re.compile(rb"\[arthas@[^\]\r\n]+\]\$ ?")


class ArthasQueryTimeout(RuntimeError):
    pass


class ArthasShell:

    def __init__(
        self,
        stream: Stream,
        reconnect_fn: Callable[[], Stream] | None = None,
    ) -> None:
        self._stream = stream
        self._sock = stream._sock
        self._reconnect_fn = reconnect_fn
        self._retried = False

    def command(self, cmd: str, timeout: float = 15, duration: float | None = None) -> str:
        try:
            if cmd.strip().split(maxsplit=1)[0].lower() in _STREAMING_COMMANDS:
                return self._streaming_command(cmd, duration if duration is not None else timeout)
            self._drain_prompt()
            self._stream.write((cmd + "\n").encode("utf-8"))
            result = self._read_output(timeout)
        except (ArthasQueryTimeout, OSError, RuntimeError):
            if self._retried or self._reconnect_fn is None:
                raise
            self._reconnect()
            return self.command(cmd, timeout, duration)
        if (
            not self._retried
            and self._reconnect_fn is not None
            and _TYPE_NOT_PRESENT in result
        ):
            self._reconnect()
            return self.command(cmd, timeout)
        return result

    def _reconnect(self) -> None:
        self._retried = True
        self._stream.close()
        self._stream = self._reconnect_fn()
        self._sock = self._stream._sock

    def _streaming_command(self, cmd: str, duration: float) -> str:
        if duration < 0:
            raise ValueError("streaming command duration must be non-negative")
        self._drain_prompt()
        self._stream.write((cmd + "\n").encode("utf-8"))
        deadline = time.monotonic() + duration
        buf = bytearray()
        self._sock.settimeout(min(0.5, max(0.05, duration)))
        while time.monotonic() < deadline:
            try:
                chunk = self._stream.read(8192)
            except socket.timeout:
                continue
            except OSError as exc:
                raise RuntimeError(f"Arthas streaming command failed: {exc}") from exc
            if not chunk:
                raise RuntimeError("Arthas streaming command closed before duration elapsed")
            buf.extend(chunk)
        self._stream.write(b"\x03")
        try:
            result = self._read_output(max(1.0, min(5.0, duration)), initial=bytes(buf))
        except Exception as exc:
            raise ArthasQueryTimeout(
                f"Arthas streaming command timed out after {duration:g}s; partial output was not complete"
            ) from exc
        return result

    def _drain_prompt(self) -> None:
        self._sock.settimeout(0.5)
        while True:
            try:
                data = self._stream.read(8192)
                if not data:
                    break
            except Exception:
                break

    def _read_output(self, timeout: float, initial: bytes = b"") -> str:
        self._sock.settimeout(timeout)
        buf = initial
        while True:
            if _PROMPT.search(buf):
                break
            try:
                chunk = self._stream.read(8192)
            except socket.timeout as exc:
                raise ArthasQueryTimeout(
                    "Arthas command timed out before a complete prompt was received"
                ) from exc
            except OSError as exc:
                raise RuntimeError(f"Arthas shell read failed: {exc}") from exc
            if not chunk:
                raise RuntimeError("Arthas shell closed before a complete prompt was received")
            buf += chunk
            if _PROMPT.search(buf):
                break
        text = buf.decode("utf-8", errors="replace").strip()
        match = list(_PROMPT.finditer(buf))[-1]
        text = buf[:match.start()].decode("utf-8", errors="replace").strip()
        return text

    def close(self) -> None:
        self._stream.close()
