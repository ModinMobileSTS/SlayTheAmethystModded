import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.perf_bench import run_perf_bench


class PerfBenchTest(unittest.TestCase):
    @patch("scripts.tools.harness.perf_bench._stop_game")
    @patch("scripts.tools.harness.perf_bench.start_logcat_capture")
    @patch("scripts.tools.harness.perf_bench.device_logcat_timestamp", return_value="")
    @patch("scripts.tools.harness.perf_bench.clear_runtime_signals")
    @patch("scripts.tools.harness.perf_bench.run_start")
    @patch("scripts.tools.harness.perf_bench.harness_status")
    def test_startup_failure_after_game_pid_is_not_reported_as_no_incidents(
        self, status, run_start, clear, timestamp, start_logcat, stop_game
    ):
        status.return_value = {
            "observedState": "FAIL",
            "processes": {"game": "123"},
        }
        ctx = HarnessContext(
            options=SimpleNamespace(
                skip_install=True,
                timeout_seconds=1,
                poll_interval_seconds=1,
                autoplay=False,
                autoplay_mode="normal",
                autoplay_save_mode="fresh",
                single_room_character="",
                perf_bench_character="",
            ),
            repo_root=Path("."),
            application_id="io.stamethyst",
            resolved_device_serial="device",
            result={},
        )

        self.assertEqual(run_perf_bench(ctx, Path("out")), 0)

        run_start.assert_called_once()
        self.assertFalse(ctx.result["success"])
        self.assertEqual(ctx.result["status"], "FAIL")

    @patch("scripts.tools.harness.perf_trace.start_tracer")
    @patch("scripts.tools.harness.perf_bench._pull_incidents")
    @patch("scripts.tools.harness.perf_bench._stop_game")
    @patch("scripts.tools.harness.perf_bench.start_logcat_capture", return_value=None)
    @patch("scripts.tools.harness.perf_bench.device_logcat_timestamp", return_value="")
    @patch("scripts.tools.harness.perf_bench.clear_runtime_signals")
    @patch("scripts.tools.harness.perf_bench.run_start")
    @patch("scripts.tools.harness.perf_bench.harness_status")
    def test_profiler_is_not_started_by_default(
        self,
        status,
        run_start,
        clear,
        timestamp,
        start_logcat,
        stop_game,
        pull_incidents,
        start_tracer,
    ):
        status.side_effect = [
            {"observedState": "READY", "processes": {"game": "123"}},
            {"observedState": "NOT_RUNNING", "processes": {"game": ""}},
        ]
        ctx = HarnessContext(
            options=SimpleNamespace(
                skip_install=True,
                timeout_seconds=1,
                poll_interval_seconds=0,
                autoplay=False,
                autoplay_mode="normal",
                autoplay_save_mode="fresh",
                single_room_character="",
                perf_bench_character="",
                perf_bench_enable_profiler=False,
            ),
            repo_root=Path("."),
            application_id="io.stamethyst",
            resolved_device_serial="device",
            result={},
            connector=object(),
        )

        self.assertEqual(run_perf_bench(ctx, Path("out")), 0)

        start_tracer.assert_not_called()
        self.assertEqual(ctx.result["status"], "NO_INCIDENTS")


if __name__ == "__main__":
    unittest.main()
