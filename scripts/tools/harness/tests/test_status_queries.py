"""Execute status shell bodies only against host fixtures; ADB/network are mocked."""
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.connector.daemon import Daemon
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._device import read_remote_sts_text, remote_sts_path_state
from scripts.tools.harness._status import StatusQueryError, harness_status, package_version_info
from scripts.tools.harness._status_query import run_status_query
from scripts.tools.lib.sts_harness import Harness


ROOT = Path(__file__).resolve().parents[4]


class StrictStatusQueryShellTests(unittest.TestCase):
    def setUp(self):
        (ROOT / "agent-tmp").mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / "agent-tmp")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.storage = {"root": str(self.root), "accessMode": "shell"}
        self.prefix = ""
        self.calls = []
        self.responses = []
        self.connector = MagicMock()
        self.connector.shell.side_effect = self.local_shell
        self.connector.adb.side_effect = self.local_adb
        self.ctx = HarnessContext(options=SimpleNamespace(device_serial="fixture"), repo_root=self.root,
                                  application_id="io.test", connector=self.connector, resolved_device_serial="fixture")
        for target in ("socket.socket", "socket.create_connection", "scripts.tools.local_adb.config",
                       "scripts.tools.local_adb.ensure"):
            mock = patch(target, side_effect=AssertionError("No device/config/network I/O allowed"))
            mock.start()
            self.addCleanup(mock.stop)

    def local_shell(self, script, **kwargs):
        # Only generated queries reach this fake connector, and only local fixture
        # paths/dumpsys shell functions are used. No real adb process is launched.
        self.calls.append(script)
        result = subprocess.run(["sh", "-c", self.prefix + script], cwd=self.root,
                                env={"PATH": os.defpath}, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, timeout=5)
        # Match daemon._run_adb's text capture: stderr is already merged, never
        # appended behind the success frame by the harness runner.
        response = {"exit": result.returncode, "stdout": result.stdout, "stderr": ""}
        self.responses.append(response)
        return response

    def local_adb(self, args, **kwargs):
        self.assertEqual(args[:5], ["exec-out", "run-as", "io.test", "sh", "-c"])
        return self.local_shell(args[5])

    def test_missing_optional_file_and_missing_parent_are_confirmed(self):
        for relative_path in ("missing.log", "missing-parent/missing.log"):
            with self.subTest(path=relative_path):
                self.assertEqual(read_remote_sts_text(self.ctx, self.storage, relative_path, strict=True), "")
                state = remote_sts_path_state(self.ctx, self.storage, relative_path, strict=True)
                self.assertFalse(state["exists"])

    def test_existing_text_tail_and_stat_preserve_content(self):
        path = self.root / "quote'file.log"
        path.write_text("first\nsecond\n")
        self.assertEqual(read_remote_sts_text(self.ctx, self.storage, path.name, strict=True), "first\nsecond\n")
        self.assertEqual(read_remote_sts_text(self.ctx, self.storage, path.name, tail_lines=1, strict=True), "second\n")
        state = remote_sts_path_state(self.ctx, self.storage, path.name, strict=True)
        self.assertTrue(state["exists"])
        self.assertEqual(state["bytes"], path.stat().st_size)
        self.assertEqual(state["mtimeEpochSeconds"], int(path.stat().st_mtime))

    def test_parent_listing_permission_failure_is_not_missing(self):
        self.prefix = "ls() { printf 'Permission denied\\n' >&2; return 1; };\n"
        with self.assertRaises(StatusQueryError):
            read_remote_sts_text(self.ctx, self.storage, "missing.log", strict=True)
        with self.assertRaises(StatusQueryError):
            remote_sts_path_state(self.ctx, self.storage, "missing.log", strict=True)

    def test_dangling_entry_is_not_confused_with_missing_file(self):
        (self.root / "broken.log").symlink_to(self.root / "not-present")
        with self.assertRaisesRegex(StatusQueryError, "Cannot inspect existing path"):
            read_remote_sts_text(self.ctx, self.storage, "broken.log", strict=True)

    def test_read_tail_and_stat_errors_preserve_failure(self):
        (self.root / "latest.log").write_text("READY\t100\tready\n")
        for command, query in (
            ("cat", lambda: read_remote_sts_text(self.ctx, self.storage, "latest.log", strict=True)),
            ("tail", lambda: read_remote_sts_text(self.ctx, self.storage, "latest.log", tail_lines=1, strict=True)),
            ("stat", lambda: remote_sts_path_state(self.ctx, self.storage, "latest.log", strict=True)),
        ):
            with self.subTest(command=command):
                self.prefix = f"{command}() {{ printf 'Permission denied\\n' >&2; return 1; }};\n"
                with self.assertRaises(StatusQueryError):
                    query()

    def test_dumpsys_failure_cannot_be_hidden_by_version_lines_or_pipeline(self):
        self.prefix = "dumpsys() { printf 'versionName=1\\nversionCode=1\\n'; return 1; };\n"
        with self.assertRaises(StatusQueryError):
            package_version_info(self.ctx, strict=True)
        self.assertNotIn("| grep", self.calls[-1])
        self.assertNotIn("|| true", self.calls[-1])

    def test_dumpsys_zero_exit_permission_denial_fails_validation(self):
        self.prefix = "dumpsys() { printf 'Permission Denial\\n'; };\n"
        with self.assertRaises(StatusQueryError):
            package_version_info(self.ctx, strict=True)

    def test_valid_dumpsys_response(self):
        self.prefix = "dumpsys() { printf 'versionName=1.6.6\\nversionCode=166 minSdk=26\\n'; };\n"
        self.assertEqual(package_version_info(self.ctx, strict=True), {"versionName": "1.6.6", "versionCode": "166"})

    def test_partial_command_output_never_gets_success_marker(self):
        with self.assertRaises(StatusQueryError):
            run_status_query(self.ctx, "printf 'exists=0\\n'; false", label="fixture")

    def test_stderr_on_zero_exit_cannot_be_hidden_by_success_frame(self):
        with self.assertRaises(StatusQueryError):
            run_status_query(self.ctx, "printf 'exists=0\\n'; printf 'Permission denied\\n' >&2", label="fixture")

    def test_stdout_is_preserved_exactly_in_shell_and_run_as(self):
        for access_mode in ("shell", "run-as"):
            for text in ("", "no final newline", "first\nsecond\n\n"):
                with self.subTest(access_mode=access_mode, text=text):
                    storage = {**self.storage, "accessMode": access_mode}
                    self.assertEqual(run_status_query(self.ctx, f"printf '%s' '{text}'", label="fixture",
                                                      sts_root=storage), text)

    def test_nonzero_body_preserves_stderr_and_exit_code_without_frame(self):
        for access_mode in ("shell", "run-as"):
            with self.subTest(access_mode=access_mode):
                with self.assertRaisesRegex(StatusQueryError, "exit 7"):
                    run_status_query(self.ctx, "printf 'partial\\n'; printf 'failure\\n\\n' >&2; exit 7",
                                     label="fixture", sts_root={**self.storage, "accessMode": access_mode})
                self.assertEqual(self.responses[-1], {"exit": 7, "stdout": "partial\nfailure\n\n", "stderr": ""})

    def test_zero_exit_stderr_is_failure_with_or_without_partial_stdout(self):
        for access_mode in ("shell", "run-as"):
            for output in ("", "partial"):
                for error in ("failure", "failure\n\n", "\n"):
                    with self.subTest(access_mode=access_mode, output=output, error=error):
                        with self.assertRaises(StatusQueryError):
                            run_status_query(self.ctx, f"printf '%s' '{output}'; printf '%s' '{error}' >&2",
                                             label="fixture", sts_root={**self.storage, "accessMode": access_mode})
                        self.assertEqual(self.responses[-1], {"exit": 1, "stdout": output + error, "stderr": ""})

    def test_status_chain_rejects_merged_stderr_for_both_entries_and_access_modes(self):
        # Real select/client/daemon dispatch/runner/status, with only the ADB
        # execution boundary mapped to local fixtures and POSIX sh.
        (self.root / "latest.log").write_text("READY fixture body\n")
        self.prefix = "dumpsys() { printf 'versionName=1.6.6\\nversionCode=166 minSdk=26\\n'; };\n"
        daemon = Daemon.__new__(Daemon)
        daemon._adb_path = "never-run-adb"
        client = ConnectorClient.__new__(ConnectorClient)
        client._selected_serial = None
        session = {}
        client.send_request = lambda req: daemon._dispatch(req, None, session)
        failure_stage = None
        use_run_as = False
        injected_stages = []

        def fake_adb(args, **kwargs):
            self.assertEqual(kwargs["serial"], "fixture")
            if args[0] == "shell":
                script = args[1]
            else:
                self.assertEqual(args[:5], ["exec-out", "run-as", "io.test", "sh", "-c"])
                script = args[5]
            if script.startswith("pidof "):
                return {"exit": 0, "stdout": "12345\n", "stderr": ""}
            label = script.splitlines()[0].removeprefix("# sts-status-query: ")
            external = self.root / "absent-external" if use_run_as else self.root
            for base in ("/sdcard/Android/data/io.test/files/sts", "/storage/emulated/0/Android/data/io.test/files/sts"):
                script = script.replace(base, str(external))
            script = script.replace("files/sts", str(self.root))
            # Package metadata always uses shell, even when file storage uses run-as.
            if label == failure_stage and (label == "package" or not use_run_as or args[0] == "exec-out"):
                injected_stages.append((label, args[0]))
                script = script.replace("set -e\n", "set -e\nprintf 'fixture stderr\\n' >&2\n", 1)
            return self.local_shell(script)

        daemon._run_adb = MagicMock(side_effect=fake_adb)
        with patch("scripts.tools.connector.daemon._adb_devices", return_value=[{"serial": "fixture", "state": "device"}]):
            harness = Harness(SimpleNamespace(command="status", device_serial="fixture", connector_port=1))
            harness.repo_root = self.root
            harness.application_id = "io.test"
            harness.connector = client
            harness.select_device()
            for use_run_as in (False, True):
                for legacy in (False, True):
                    entry = (lambda ctx: harness.harness_status()) if legacy else harness_status
                    with patch("scripts.tools.harness.status.harness_status", side_effect=entry):
                        failure_stage = None
                        self.assertEqual(harness.run_command(self.root), 0)
                        for failure_stage in ("resolve storage", "read latest.log", "path desktop-1.0.jar", "package"):
                            with self.subTest(run_as=use_run_as, legacy=legacy, stage=failure_stage):
                                injected_stages.clear()
                                self.assertEqual(harness.run_command(self.root), 1)
                                expected_access = "exec-out" if use_run_as and failure_stage != "package" else "shell"
                                self.assertEqual(injected_stages, [(failure_stage, expected_access)])
                                self.assertFalse(harness.result["success"])
                                self.assertEqual(harness.result["status"], "STATUS_QUERY_FAILED")
                                self.assertEqual(harness.result["statusSnapshot"]["observedState"], "UNKNOWN")
                                self.assertIn("fixture stderr", harness.result["message"])
                                self.assertEqual(self.responses[-1]["stderr"], "")
                                self.assertNotIn("__STS_STATUS_OK_", self.responses[-1]["stdout"])

    def test_nonstatus_default_keeps_existing_tolerant_behavior(self):
        self.connector.shell.side_effect = None
        self.connector.shell.return_value = {"exit": 1, "stderr": "mock failure"}
        self.assertEqual(read_remote_sts_text(self.ctx, self.storage, "latest.log"), "mock failure")
        self.assertFalse(remote_sts_path_state(self.ctx, self.storage, "latest.log")["exists"])
