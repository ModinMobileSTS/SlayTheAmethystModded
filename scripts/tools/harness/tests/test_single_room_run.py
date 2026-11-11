import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.single_room_run import run_single_room


class SingleRoomRunTest(unittest.TestCase):
    @patch("scripts.tools.harness.single_room_run.device_logcat_timestamp", return_value="")
    @patch("scripts.tools.harness.single_room_run.clear_runtime_signals")
    @patch("scripts.tools.harness.single_room_run.run_start")
    @patch("scripts.tools.harness.single_room_run.harness_status")
    def test_start_leaves_running_game_without_stop(self, status, run_start, clear, timestamp):
        status.return_value = {
            "observedState": "RUNNING_WITHOUT_TERMINAL_EVENT",
            "processes": {"game": "123"},
        }
        ctx = HarnessContext(
            options=SimpleNamespace(skip_install=False, timeout_seconds=1, poll_interval_seconds=1),
            repo_root=Path("."), application_id="io.stamethyst",
            resolved_device_serial="device", result={},
        )
        self.assertEqual(run_single_room(ctx, Path("out")), 0)
        run_start.assert_called_once()
        self.assertEqual(ctx.result["status"], "SINGLE_ROOM_STARTED")


if __name__ == "__main__":
    unittest.main()
