import unittest
import subprocess
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts.tools.lib.sts_harness import HarnessOptions
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._runner import (
    CommandResult,
    build_adb_args,
    run_native,
)


class RunNativeTest(unittest.TestCase):
    def _make_ctx(self, **overrides):
        kwargs = dict(
            options=HarnessOptions(
                command="smoke",
                launch_mode="mts_basemod",
                device_serial="",
                out_dir="",
                timeout_seconds=300,
                poll_interval_seconds=2,
                force_jvm_crash=False,
                force_runtime_crash=False,
                debug_mode=False,
                autoplay=False,
                skip_install=False,
                no_stop_after_smoke=False,
                mods=[],
                mod_list_file="",
                enable_all_mods=False,
                disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
        )
        kwargs.update(overrides)
        return HarnessContext(**kwargs)

    @patch("subprocess.run")
    def test_run_native_returns_command_result(self, mock_run):
        mock_run.return_value = MagicMock(
            returncode=0, stdout="hello\n", stderr=""
        )
        ctx = self._make_ctx()
        result = run_native(ctx, "echo", ["hello"])
        self.assertIsInstance(result, CommandResult)
        self.assertEqual(result.exit_code, 0)
        self.assertIn("hello", result.output)

    @patch("subprocess.run")
    def test_run_native_appends_operation(self, mock_run):
        mock_run.return_value = MagicMock(
            returncode=0, stdout="ok", stderr=""
        )
        ctx = self._make_ctx()
        self.assertEqual(len(ctx.operations), 0)
        run_native(ctx, "test-cmd", ["--arg"])
        self.assertEqual(len(ctx.operations), 1)
        self.assertEqual(ctx.operations[0]["exitCode"], 0)

    @patch("subprocess.run")
    def test_run_native_raises_on_nonzero_exit(self, mock_run):
        mock_run.return_value = MagicMock(
            returncode=1, stdout="", stderr="error"
        )
        ctx = self._make_ctx()
        with self.assertRaises(RuntimeError):
            run_native(ctx, "failing-cmd")

    @patch("subprocess.run")
    def test_run_native_allow_failure_suppresses_raise(self, mock_run):
        mock_run.return_value = MagicMock(
            returncode=1, stdout="", stderr="error"
        )
        ctx = self._make_ctx()
        result = run_native(ctx, "failing-cmd", allow_failure=True)
        self.assertEqual(result.exit_code, 1)

    @patch("subprocess.run")
    def test_run_native_timeout_raises(self, mock_run):
        mock_run.side_effect = subprocess.TimeoutExpired(
            cmd=["slow"], timeout=1, output=b"partial"
        )
        ctx = self._make_ctx()
        with self.assertRaises(RuntimeError):
            run_native(ctx, "slow", timeout_seconds=1)

    @patch("subprocess.run")
    def test_run_native_timeout_with_allow_failure(self, mock_run):
        mock_run.side_effect = subprocess.TimeoutExpired(
            cmd=["slow"], timeout=1, output=b"timed out"
        )
        ctx = self._make_ctx()
        result = run_native(ctx, "slow", timeout_seconds=1, allow_failure=True)
        self.assertEqual(result.exit_code, -1)
        self.assertTrue(ctx.operations[-1]["timedOut"])


class CommandResultTest(unittest.TestCase):
    def test_command_result_attributes(self):
        result = CommandResult(exit_code=0, output="test output")
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(result.output, "test output")


class BuildAdbArgsTest(unittest.TestCase):
    def _make_ctx(self, device_serial=""):
        return HarnessContext(
            options=HarnessOptions(
                command="smoke",
                launch_mode="mts_basemod",
                device_serial=device_serial,
                out_dir="",
                timeout_seconds=300,
                poll_interval_seconds=2,
                force_jvm_crash=False,
                force_runtime_crash=False,
                debug_mode=False,
                autoplay=False,
                skip_install=False,
                no_stop_after_smoke=False,
                mods=[],
                mod_list_file="",
                enable_all_mods=False,
                disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
            resolved_device_serial=device_serial,
        )

    def test_no_serial_appends_arguments_only(self):
        ctx = self._make_ctx(device_serial="")
        result = build_adb_args(ctx, ["shell", "echo"])
        self.assertEqual(result, ["shell", "echo"])

    def test_with_serial_prepends_device_args(self):
        ctx = self._make_ctx(device_serial="emulator-5554")
        result = build_adb_args(ctx, ["shell", "echo"])
        self.assertEqual(result, ["-s", "emulator-5554", "shell", "echo"])


class AdbTest(unittest.TestCase):
    def _make_ctx(self, connector=None, device_serial=""):
        return HarnessContext(
            options=HarnessOptions(
                command="smoke",
                launch_mode="mts_basemod",
                device_serial=device_serial,
                out_dir="",
                timeout_seconds=300,
                poll_interval_seconds=2,
                force_jvm_crash=False,
                force_runtime_crash=False,
                debug_mode=False,
                autoplay=False,
                skip_install=False,
                no_stop_after_smoke=False,
                mods=[],
                mod_list_file="",
                enable_all_mods=False,
                disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
            connector=connector,
            resolved_device_serial=device_serial,
        )

    def test_raises_when_no_connector(self):
        from scripts.tools.harness._runner import adb
        ctx = self._make_ctx(connector=None)
        with self.assertRaises(RuntimeError):
            adb(ctx, ["devices"])

    def test_adb_uses_connector_shell(self):
        from scripts.tools.harness._runner import adb
        connector = MagicMock()
        connector.shell.return_value = {"exit": 0, "stdout": "hello", "stderr": ""}
        ctx = self._make_ctx(connector=connector)
        result = adb(ctx, ["shell", "echo hello"])
        self.assertEqual(result.exit_code, 0)
        self.assertIn("hello", result.output)
        connector.shell.assert_called_once()


    def test_install_preserves_fastdeploy_arguments_through_connector(self):
        from scripts.tools.harness._runner import adb
        connector = MagicMock()
        connector.adb.return_value = {"exit": 0, "stdout": "installed", "stderr": ""}
        ctx = self._make_ctx(connector=connector)
        args = ["install", "--fastdeploy", "-r", "/fake/app.apk"]
        result = adb(ctx, args, timeout_seconds=180)
        self.assertEqual(result.exit_code, 0)
        connector.adb.assert_called_once_with(
            args, timeout_ms=180000, capture="text", local_path="",
        )
        connector.install.assert_not_called()


class GradleTest(unittest.TestCase):
    def _make_ctx(self, gradle_wrapper="/fake/gradlew"):
        return HarnessContext(
            options=HarnessOptions(
                command="smoke",
                launch_mode="mts_basemod",
                device_serial="",
                out_dir="",
                timeout_seconds=300,
                poll_interval_seconds=2,
                force_jvm_crash=False,
                force_runtime_crash=False,
                debug_mode=False,
                autoplay=False,
                skip_install=False,
                no_stop_after_smoke=False,
                mods=[],
                mod_list_file="",
                enable_all_mods=False,
                disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
            gradle_wrapper=Path(gradle_wrapper) if gradle_wrapper is not None else None,
        )

    def test_raises_when_no_gradle_wrapper(self):
        from scripts.tools.harness._runner import gradle
        ctx = self._make_ctx(gradle_wrapper=None)
        with self.assertRaises(RuntimeError):
            gradle(ctx, [":app:assembleDebug"])

    @patch("subprocess.run")
    def test_gradle_appends_stacktrace_and_console_flags(self, mock_run):
        from scripts.tools.harness._runner import gradle
        mock_run.return_value = MagicMock(
            returncode=0, stdout="BUILD SUCCESSFUL", stderr=""
        )
        ctx = self._make_ctx(gradle_wrapper="/fake/gradlew")
        result = gradle(ctx, [":app:assembleDebug"])
        self.assertEqual(result.exit_code, 0)
        call_args = mock_run.call_args[0][0]
        self.assertIn("--stacktrace", call_args)
        self.assertIn("--console=plain", call_args)
