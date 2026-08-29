import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.run import run_start, run_stop


class HarnessRunTest(unittest.TestCase):
    def _ctx(self):
        return HarnessContext(
            options=SimpleNamespace(
                launch_mode="mts_basemod",
                force_jvm_crash=False,
                force_runtime_crash=False,
                debug_mode=True,
                autoplay=False,
                autoplay_save_mode="fresh",
                autoplay_mode="normal",
                single_room_device_spec="",
                disable_card_obtain_effect_ownership_compat=False,
            ),
            repo_root=Path("."),
            resolved_device_serial="localhost:15555",
            result={},
        )

    @patch("scripts.tools.harness.run.gradle")
    def test_start_passes_device_serial_property(self, gradle):
        run_start(self._ctx())

        args = gradle.call_args.args[1]
        self.assertIn("-PdeviceSerial=localhost:15555", args)
        self.assertNotIn("-PandroidDeviceSerial=localhost:15555", args)

    @patch("scripts.tools.harness.run.gradle")
    def test_stop_passes_device_serial_property(self, gradle):
        run_stop(self._ctx())

        args = gradle.call_args.args[1]
        self.assertIn("-PdeviceSerial=localhost:15555", args)
        self.assertNotIn("-PandroidDeviceSerial=localhost:15555", args)
        self.assertEqual(gradle.call_args.kwargs["timeout_seconds"], 30)

    @patch("scripts.tools.harness.run.ensure_single_room_device_spec")
    @patch("scripts.tools.harness.run.gradle")
    def test_single_room_start_passes_spec_to_autoplay_task(self, gradle, ensure_spec):
        ctx = self._ctx()
        ctx.options.autoplay = True
        ctx.options.autoplay_mode = "single_room"
        ensure_spec.return_value = "files/sts/config/autoplay-single-room.properties"

        run_start(ctx, Path("debug-artifacts/harness/test"))

        self.assertEqual(gradle.call_args.args[1][0], ":app:stsStartAutoplay")
        self.assertIn(
            "-PautoplaySingleRoomSpec=files/sts/config/autoplay-single-room.properties",
            gradle.call_args.args[1],
        )
        ensure_spec.assert_called_once_with(ctx, Path("debug-artifacts/harness/test"))

    @patch("scripts.tools.harness.run.gradle")
    def test_perf_start_uses_clean_autoplay_task(self, gradle):
        ctx = self._ctx()
        ctx.options.autoplay = True
        ctx.options.command = "perf-bench"

        run_start(ctx, use_autoplay_task=True)

        args = gradle.call_args.args[1]
        self.assertEqual(args[0], ":app:stsStartAutoplay")
        self.assertIn("-Pautoplay=true", args)
        self.assertIn("-PperformanceDeepDiagnostics=true", args)
        self.assertEqual(gradle.call_args.kwargs["timeout_seconds"], 120)


if __name__ == "__main__":
    unittest.main()
