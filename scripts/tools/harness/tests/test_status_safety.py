"""Real selection/client/daemon/runner/status code, with no sockets or real ADB."""
from pathlib import Path
import re
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.connector.daemon import Daemon
from scripts.tools.harness._status import StatusQueryError, process_pid_text
from scripts.tools.harness._status import harness_status
from scripts.tools.harness.status import run_status
from scripts.tools.lib.sts_harness import Harness


class StatusSafetyTests(unittest.TestCase):
    def setUp(self):
        self.addCleanup(patch.stopall)
        for target in ("socket.socket", "socket.create_connection", "subprocess.run", "subprocess.Popen"):
            patch(target, side_effect=AssertionError("No external I/O allowed in status regression")).start()
        patch("scripts.tools.connector.daemon._adb_devices", return_value=[
            {"serial": "approved-fake-device", "state": "device"},
        ]).start()
        # Constructor bypasses are deliberate: constructors open sockets/resolve adb.
        self.daemon = Daemon.__new__(Daemon)
        self.daemon._adb_path = "never-run-adb"
        self.daemon._run_adb = MagicMock(side_effect=self.fake_adb)
        self.session = {}
        self.client = ConnectorClient.__new__(ConnectorClient)
        self.client._selected_serial = None
        self.client.send_request = MagicMock(side_effect=lambda req: self.daemon._dispatch(req, None, self.session))
        self.running = True
        self.all_pids = False
        self.failure_stage = None
        self.failure_response = None
        self.missing_roots = False
        self.run_as_root = False
        self.log_text = ""

    def framed_response(self, script, payload):
        token = re.search(r"__STS_STATUS_OK_[a-f0-9]+__", script).group()
        return {"exit": 0, "stdout": payload + f"\n{token}\n", "stderr": ""}

    def fake_adb(self, args, **kwargs):
        self.assertEqual(kwargs["serial"], "approved-fake-device")
        if args[0] == "shell":
            script = args[1]
        else:
            self.assertEqual(args[:5], ["exec-out", "run-as", "io.test", "sh", "-c"])
            script = args[5]
        if script.startswith("pidof "):
            if self.all_pids or (self.running and ":game" in script):
                return {"exit": 0, "stdout": "12345\n", "stderr": ""}
            return {"exit": 1, "stdout": "", "stderr": ""}
        label = script.splitlines()[0].removeprefix("# sts-status-query: ")
        if label == self.failure_stage:
            if callable(self.failure_response):
                return self.failure_response(script)
            return self.failure_response
        if label == "resolve storage":
            missing = self.missing_roots or (self.run_as_root and args[0] == "shell")
            return self.framed_response(script, "missing\n" if missing else "exists\n")
        if label.startswith("read "):
            return self.framed_response(script, "exists\n" + self.log_text if self.log_text else "missing\n")
        if label.startswith("path "):
            return self.framed_response(script, "exists=0\n")
        if label == "package":
            return self.framed_response(script, "  versionName=1.6.6\n  versionCode=166 minSdk=26\n")
        self.fail(f"Unexpected status query: {script}")

    def harness(self, serial="approved-fake-device"):
        harness = Harness(SimpleNamespace(command="status", device_serial=serial, connector_port=1))
        harness.repo_root = Path("/fake/repo")
        harness.application_id = "io.test"
        harness.connector = self.client
        harness.select_device()
        return harness

    def test_auto_status_fails_unknown_without_elevating_selection(self):
        harness = self.harness("")
        self.assertIsNone(self.session["explicit_serial"])
        self.assertEqual(harness.run_command(Path("/unused-output")), 1)
        self.assertFalse(harness.result["success"])
        self.assertEqual(harness.result["status"], "STATUS_QUERY_FAILED")
        self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
        self.assertIn("explicit device serial", harness.result["message"])
        # Even direct callers bypassing the snapshot preflight cannot parse the
        # daemon's permission error as a PID, or gain installation permission.
        with self.assertRaisesRegex(StatusQueryError, "Process status query failed"):
            process_pid_text(harness._build_context(), "io.test:game")
        with self.assertRaisesRegex(RuntimeError, "explicit device"):
            self.client.install("/fake/app.apk")
        self.assertIn("error", self.client.adb(["uninstall", "io.test"]))
        self.daemon._run_adb.assert_not_called()
        self.assertIsNone(self.session["explicit_serial"])

    def test_explicit_target_preserves_running_and_not_running_queries(self):
        harness = self.harness()
        for running, expected in ((True, "RUNNING_WITHOUT_TERMINAL_EVENT"), (False, "NOT_RUNNING")):
            with self.subTest(running=running):
                self.running = running
                self.assertEqual(harness.run_command(Path("/unused-output")), 0)
                self.assertTrue(harness.result["success"])
                snapshot = harness.result["statusSnapshot"]
                self.assertEqual(snapshot["observedState"], expected)
                self.assertEqual(snapshot["processes"]["game"], "12345" if running else "")

    def test_pid_errors_produce_failure_unknown_in_both_status_paths(self):
        harness = self.harness()
        for response in (
            {"exit": 1, "stdout": "device offline", "stderr": ""},
            {"exit": -1, "stdout": ""},
            {"exit": 1, "stdout": "12345"},
            {"exit": 0, "stdout": "An explicit device serial is required"},
            {"exit": 0, "stdout": "12345\npermission denied"},
            {"exit": 0, "stdout": "0"},
            {"exit": 0, "stdout": "-123"},
        ):
            with self.subTest(response=response):
                self.daemon._run_adb.side_effect = None
                self.daemon._run_adb.return_value = response
                run_status(harness._build_context())
                self.assertFalse(harness.result["success"])
                self.assertEqual(harness.result["status"], "STATUS_QUERY_FAILED")
                self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
                with self.assertRaises(StatusQueryError):
                    harness.harness_status()

    def test_legacy_status_rejects_auto_before_remote_probes(self):
        harness = self.harness("")
        with self.assertRaisesRegex(StatusQueryError, "explicit device serial"):
            harness.harness_status()
        with self.assertRaises(StatusQueryError):
            harness.process_pid_text("io.test:game")
        self.daemon._run_adb.assert_not_called()

    def test_pid_parser_accepts_multiple_numeric_pids(self):
        harness = self.harness()
        self.daemon._run_adb.side_effect = None
        self.daemon._run_adb.return_value = {"exit": 0, "stdout": "123\t456\n"}
        self.assertEqual(process_pid_text(harness._build_context(), "io.test"), "123 456")
        self.assertEqual(harness.process_pid_text("io.test"), "123 456")

    def test_declared_target_does_not_override_auto_connector_provenance(self):
        harness = self.harness("")
        harness.options.device_serial = "approved-fake-device"
        run_status(harness._build_context())
        self.assertFalse(harness.result["success"])
        self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
        self.daemon._run_adb.assert_not_called()
        self.assertIsNone(self.session["explicit_serial"])

    def test_resolved_target_mismatch_fails_before_remote_query(self):
        harness = self.harness()
        harness.resolved_device_serial = "different-device"
        run_status(harness._build_context())
        self.assertFalse(harness.result["success"])
        self.assertIn("target differs", harness.result["message"])
        self.daemon._run_adb.assert_not_called()

    def test_each_post_pid_stage_failure_is_unknown_and_nonzero_for_both_entries(self):
        harness = self.harness()
        self.all_pids = True
        for stage in ("resolve storage", "read boot_bridge_events.log", "read latest.log",
                      "path desktop-1.0.jar", "path desktop-1.0.jar.patching.tmp",
                      "path desktop-1.0.jar.patching.backup", "package"):
            for response in (
                {"exit": 1, "stdout": "Permission denied", "stderr": ""},
                {"exit": 1, "stdout": ""},  # not a missing-file response
                {"error": {"code": -32003, "message": "transport timeout"}},
                {"exit": 0, "stdout": "missing\n"},  # unframed/truncated reply
            ):
                for legacy in (False, True):
                    with self.subTest(stage=stage, response=response, legacy=legacy):
                        self.failure_stage = stage
                        self.failure_response = response
                        self.daemon._run_adb.reset_mock()
                        entry = (lambda ctx: harness.harness_status()) if legacy else harness_status
                        with patch("scripts.tools.harness.status.harness_status", side_effect=entry):
                            self.assertEqual(harness.run_command(Path("/unused-output")), 1)
                        self.assertFalse(harness.result["success"])
                        self.assertEqual(harness.result["status"], "STATUS_QUERY_FAILED")
                        self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
                        self.assertIn(stage, harness.result["message"])
                        commands = [call.args[0] for call in self.daemon._run_adb.call_args_list]
                        pid_count = 5 if legacy else 4
                        self.assertEqual(sum(args[1].startswith("pidof ") for args in commands), pid_count)
                        self.assertTrue(all(args[1].startswith("pidof ") for args in commands[:pid_count]))

    def test_confirmed_optional_absence_is_successful_for_both_entries(self):
        harness = self.harness()
        for missing_roots, run_as_root in ((False, False), (True, False), (False, True)):
            for legacy in (False, True):
                with self.subTest(missing_roots=missing_roots, run_as_root=run_as_root, legacy=legacy):
                    self.missing_roots, self.run_as_root = missing_roots, run_as_root
                    entry = (lambda ctx: harness.harness_status()) if legacy else harness_status
                    with patch("scripts.tools.harness.status.harness_status", side_effect=entry):
                        self.assertEqual(harness.run_command(Path("/unused-output")), 0)
                    self.assertTrue(harness.result["success"])
                    snapshot = harness.result["statusSnapshot"]
                    self.assertEqual(snapshot["bootBridge"]["eventCount"], 0)
                    self.assertFalse(snapshot["desktopJarPatch"]["inProgress"])
                    self.assertEqual(snapshot["package"]["versionCode"], "166")
                    self.assertEqual(snapshot["storage"]["accessMode"], "run-as" if run_as_root else "shell")

    def test_zero_exit_package_permission_error_is_not_valid_version_data(self):
        harness = self.harness()
        self.failure_stage = "package"
        self.failure_response = lambda script: self.framed_response(script, "Permission Denial: cannot dump package\n")
        self.assertEqual(harness.run_command(Path("/unused-output")), 1)
        self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
        with self.assertRaises(StatusQueryError):
            harness.harness_status()

    def test_invalid_successful_metadata_is_not_absence(self):
        harness = self.harness()
        for stage, payload in (("resolve storage", ""), ("path desktop-1.0.jar", "exists=1\n"),
                               ("read latest.log", "Permission denied\n")):
            with self.subTest(stage=stage):
                self.failure_stage = stage
                self.failure_response = lambda script: self.framed_response(script, payload)
                self.assertEqual(harness.run_command(Path("/unused-output")), 1)
                with self.assertRaises(StatusQueryError):
                    harness.harness_status()

    def test_run_as_permission_errors_remain_query_failures(self):
        harness = self.harness()
        self.run_as_root = True
        for stage in ("read boot_bridge_events.log", "path desktop-1.0.jar"):
            with self.subTest(stage=stage):
                self.failure_stage = stage
                self.failure_response = {"exit": 1, "stdout": "run-as: permission denied", "stderr": ""}
                self.assertEqual(harness.run_command(Path("/unused-output")), 1)
                self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
                self.assertEqual(self.daemon._run_adb.call_args.args[0][0], "exec-out")
                with self.assertRaises(StatusQueryError):
                    harness.harness_status()
