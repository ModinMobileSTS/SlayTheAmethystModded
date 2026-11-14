import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts.tools.lib.sts_harness import HarnessOptions
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._status import (
    extract_startup_cache_log_evidence,
    find_crash_marker,
    find_harness_logcat_crash,
    find_single_room_result,
    harness_status,
    last_non_blank_line,
    parse_boot_bridge_events,
    process_pid_text,
)


class ParseBootBridgeEventsTest(unittest.TestCase):
    def test_empty_text(self):
        result = parse_boot_bridge_events("")
        self.assertEqual(result["eventCount"], 0)
        self.assertIsNone(result["latestEvent"])
        self.assertIsNone(result["terminalEvent"])

    def test_parses_events(self):
        text = "LOAD\t50\tLoading assets\nREADY\t100\tReady\n"
        result = parse_boot_bridge_events(text)
        self.assertEqual(result["eventCount"], 2)
        self.assertEqual(result["latestEvent"]["type"], "READY")
        self.assertEqual(result["terminalEvent"]["type"], "READY")

    def test_none_input(self):
        result = parse_boot_bridge_events(None)
        self.assertEqual(result["eventCount"], 0)

    def test_fail_terminal(self):
        text = "LOAD\t50\tLoading\nFAIL\t0\tTimeout\n"
        result = parse_boot_bridge_events(text)
        self.assertEqual(result["terminalEvent"]["type"], "FAIL")


class FindCrashMarkerTest(unittest.TestCase):
    def test_finds_game_crashed(self):
        self.assertEqual(find_crash_marker("Game crashed."), "Game crashed.")

    def test_finds_lwjgl_exception(self):
        self.assertEqual(
            find_crash_marker('Exception in thread "LWJGL Application" blah'),
            'Exception in thread "LWJGL Application"',
        )

    def test_returns_none_when_clean(self):
        self.assertIsNone(find_crash_marker("Everything is fine"))

    def test_handles_none(self):
        self.assertIsNone(find_crash_marker(None))


class FindSingleRoomResultTest(unittest.TestCase):
    def test_finds_result(self):
        text = "[amethyst-autoplay] single_room result outcome=WIN character=IRONCLAD turns=5"
        result = find_single_room_result(text)
        self.assertEqual(result["outcome"], "WIN")
        self.assertEqual(result["character"], "IRONCLAD")
        self.assertEqual(result["turns"], "5")

    def test_no_result_returns_none(self):
        self.assertIsNone(find_single_room_result("regular log line"))

    def test_empty_text_returns_none(self):
        self.assertIsNone(find_single_room_result(""))


class FindHarnessLogcatCrashTest(unittest.TestCase):
    def test_finds_fatal_exception(self):
        text = "FATAL EXCEPTION: main\nProcess: com.example.app"
        result = find_harness_logcat_crash(text, "com.example.app")
        self.assertIsNotNone(result)
        self.assertEqual(result["marker"], "FATAL EXCEPTION")

    def test_ignores_foreground_process_lifecycle_logs(self):
        text = "\n".join(
            [
                "07-28 08:43:10.068 D ForegroundUtils: handleForegroundActivitiesChanged process: com.example.app uid: 10079 pid: 9142 FG:false",
                "07-28 08:43:13.002 I ActivityManager: Force stopping com.example.app appid=10079 user=0: from pid 9579",
                "07-28 08:43:13.499 D ForegroundUtils: handleForegroundActivitiesChanged process: com.example.app uid: 10079 pid: 9592 FG:true",
            ]
        )
        self.assertIsNone(find_harness_logcat_crash(text, "com.example.app"))

    def test_detects_force_finishing_with_package(self):
        text = "I ActivityManager: Force finishing activity com.example.app/.LauncherActivity"
        result = find_harness_logcat_crash(text, "com.example.app")
        self.assertIsNotNone(result)
        self.assertIn("Force finishing", result["marker"])

    def test_no_crash_returns_none(self):
        self.assertIsNone(find_harness_logcat_crash("all good", "com.example.app"))

    def test_empty_text_returns_none(self):
        self.assertIsNone(find_harness_logcat_crash("", "com.example.app"))


class LastNonBlankLineTest(unittest.TestCase):
    def test_returns_last_non_blank(self):
        self.assertEqual(last_non_blank_line("a\n\nb\n  c  "), "c")

    def test_all_blank_returns_none(self):
        self.assertIsNone(last_non_blank_line("  \n\n  "))

    def test_none_returns_none(self):
        self.assertIsNone(last_non_blank_line(None))


class ProcessPidTextTest(unittest.TestCase):
    @patch("scripts.tools.harness._status.adb_shell_script")
    def test_returns_pid(self, mock_shell):
        mock_shell.return_value = MagicMock(exit_code=0, output="12345\n")
        ctx = HarnessContext(
            options=HarnessOptions(
                command="smoke", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=300, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
        )
        self.assertEqual(process_pid_text(ctx, "com.example.app"), "12345")


class ExtractStartupCacheLogEvidenceTest(unittest.TestCase):
    def test_detects_cache_hit(self):
        text = "Launching cached MTS patch jar\n"
        result = extract_startup_cache_log_evidence(text)
        self.assertEqual(result["mode"], "cache-hit")
        self.assertTrue(result["sawCacheHit"])

    def test_detects_cache_build(self):
        text = "Writing MTS patch cache jar\n"
        result = extract_startup_cache_log_evidence(text)
        self.assertEqual(result["mode"], "cache-build")

    def test_unknown_mode(self):
        result = extract_startup_cache_log_evidence("nothing here")
        self.assertEqual(result["mode"], "unknown")

    def test_handles_none(self):
        result = extract_startup_cache_log_evidence(None)
        self.assertEqual(result["mode"], "unknown")


class HarnessStatusTest(unittest.TestCase):
    def _make_ctx(self):
        return HarnessContext(
            options=HarnessOptions(
                command="smoke", launch_mode="mts_basemod", device_serial="emulator-5554",
                out_dir="", timeout_seconds=300, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            ),
            repo_root=Path("/fake/repo"),
            application_id="com.example.app",
            resolved_device_serial="emulator-5554",
        )

    @patch("scripts.tools.harness._status.package_version_info")
    @patch("scripts.tools.harness._status.process_pid_text")
    @patch("scripts.tools.harness._status.desktop_jar_patch_snapshot")
    @patch("scripts.tools.harness._status.read_remote_sts_text")
    @patch("scripts.tools.harness._status.resolve_device_sts_root")
    def test_not_running_state(self, mock_root, mock_read, mock_snap, mock_pid, mock_pkg):
        mock_root.return_value = {"root": "/data/sts", "accessMode": "shell"}
        mock_read.return_value = ""
        mock_snap.return_value = {"inProgress": False}
        mock_pid.return_value = ""
        mock_pkg.return_value = {"versionName": None, "versionCode": None}
        ctx = self._make_ctx()
        result = harness_status(ctx)
        self.assertEqual(result["observedState"], "NOT_RUNNING")

    @patch("scripts.tools.harness._status.package_version_info")
    @patch("scripts.tools.harness._status.process_pid_text")
    @patch("scripts.tools.harness._status.desktop_jar_patch_snapshot")
    @patch("scripts.tools.harness._status.read_remote_sts_text")
    @patch("scripts.tools.harness._status.resolve_device_sts_root")
    def test_ready_state(self, mock_root, mock_read, mock_snap, mock_pid, mock_pkg):
        mock_root.return_value = {"root": "/data/sts", "accessMode": "shell"}
        mock_read.return_value = "READY\t100\tReady\n"
        mock_snap.return_value = {"inProgress": False}
        mock_pid.side_effect = lambda _ctx, _name: "12345"
        mock_pkg.return_value = {"versionName": "1.0", "versionCode": "1"}
        ctx = self._make_ctx()
        result = harness_status(ctx)
        self.assertEqual(result["observedState"], "READY")
