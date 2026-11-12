from typing import Any

from scripts.tools.lib.env_device import get_test_device_serial
from scripts.tools.lib.device_safety import require_explicit_device


class HarnessOrchestrator:
    """Lightweight orchestration helper used by connector-based tests."""

    def __init__(
        self,
        connector: Any,
        application_id: str,
        device_serial: str | None = None,
    ) -> None:
        self._conn = connector
        self._app_id = application_id
        self._device_serial = device_serial or get_test_device_serial()
        self._explicit_device_serial = device_serial

    def build_and_install(self, apk_path: str) -> None:
        serial = require_explicit_device(self._explicit_device_serial)
        if not self._conn.select(serial):
            raise RuntimeError(f"Failed to select explicitly requested install device: {serial}")
        resp = self._conn.install(apk_path, replace=True, timeout_ms=180000)
        if isinstance(resp, dict) and "error" in resp:
            raise RuntimeError(f"install failed: {resp['error']}")
        if isinstance(resp, dict) and int(resp.get("exit", 1)) != 0:
            raise RuntimeError(f"install failed: {resp.get('stdout', '')}")

    def start(self, mode: str = "mts", debug_mode: bool = False, autoplay: bool = False) -> None:
        cmd = (
            f"am start -n {self._app_id}/.MainActivity "
            f"--es launchMode {mode}"
        )
        if debug_mode:
            cmd += " --ez io.stamethyst.debug_mode true"
        if autoplay:
            cmd += " --ez io.stamethyst.debug_autoplay true"
        self._conn.shell(cmd)

    def stop(self) -> None:
        self._conn.shell(f"am force-stop {self._app_id}")

    def game_status(self) -> dict[str, Any]:
        result = self._conn.shell(f"pidof {self._app_id}")
        running = result.get("exit", -1) == 0 and bool(result.get("stdout", "").strip())
        return {"running": running}
