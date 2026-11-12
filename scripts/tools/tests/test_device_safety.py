"""Device-free regression tests: no adb, connector daemon, or Gradle execution."""

from __future__ import annotations

import ast
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from scripts.tools.connector.client import ConnectorClient
from scripts.tools.connector.daemon import Daemon
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness._runner import adb
from scripts.tools.lib.device_safety import require_explicit_device
from scripts.tools.lib.sts_harness import Harness
from scripts.tools.lib.sts_harness_cli import create_parser
from scripts.tools.ram_saver_ab import collect


ROOT = Path(__file__).resolve().parents[3]


class ExplicitDeviceTest(unittest.TestCase):
    def test_rejects_unspecified_or_automatic_targets(self):
        for serial in (None, "", " ", "auto", "AUTO", "default", "all", "*", "-e", "a b"):
            with self.subTest(serial=serial), self.assertRaisesRegex(RuntimeError, "explicit device"):
                require_explicit_device(serial)

    def test_accepts_exact_serial(self):
        self.assertEqual(require_explicit_device(" emulator-1234 "), "emulator-1234")
        self.assertEqual(require_explicit_device("test-host:1234"), "test-host:1234")

    def test_install_initialization_rejects_before_connector_or_build(self):
        harness = Harness.__new__(Harness)
        harness.options = SimpleNamespace(command="install", device_serial="")
        with patch.object(Harness, "resolve_gradle_wrapper") as build, \
                patch.object(Harness, "connect_connector") as connect:
            with self.assertRaisesRegex(RuntimeError, "explicit device"):
                harness.initialize()
        build.assert_not_called()
        connect.assert_not_called()

    def test_runner_rejects_package_commands_even_with_resolved_device(self):
        for args in (["install", "app.apk"], ["install-multiple", "a.apk", "b.apk"],
                     ["uninstall", "io.stamethyst"]):
            with self.subTest(args=args):
                connector = MagicMock()
                ctx = HarnessContext(
                    options=SimpleNamespace(device_serial=""), repo_root=ROOT,
                    resolved_device_serial="discovered-device", connector=connector,
                )
                with self.assertRaisesRegex(RuntimeError, "explicit device"):
                    adb(ctx, args, allow_failure=True)
                self.assertEqual(connector.mock_calls, [])

    def test_client_install_rejects_auto_before_sending(self):
        client = ConnectorClient(port=1, auto_start=False)
        try:
            with patch.object(client, "send_request") as send:
                for serial in (None, "", "auto"):
                    client._selected_serial = serial
                    with self.subTest(serial=serial), self.assertRaisesRegex(RuntimeError, "explicit device"):
                        client.install("not-a-real.apk")
                send.assert_not_called()
        finally:
            client.close()

    def test_cli_defaults_to_no_install(self):
        for command in ("smoke", "single-room", "perf-bench", "startup-cache-profile", "steam-cloud-sync"):
            with self.subTest(command=command):
                self.assertTrue(create_parser().parse_args(["-Command", command]).skip_install)

    def test_discovery_never_becomes_explicit_selection_on_retries(self):
        harness = Harness.__new__(Harness)
        harness.options = SimpleNamespace(device_serial="")
        harness.resolved_device_serial = ""
        harness.connector = MagicMock()
        harness.connector.devices.return_value = [{"serial": "discovered-device", "state": "device"}]
        harness.select_device()
        harness.select_device()
        self.assertEqual(harness.resolved_device_serial, "discovered-device")
        self.assertEqual([call.args for call in harness.connector.select.call_args_list], [("auto",), ("auto",)])

    def test_failed_client_selection_revokes_install_access(self):
        client = ConnectorClient(port=1, auto_start=False)
        try:
            client._selected_serial = "previous-device"
            with patch.object(client, "send_request", return_value={"error": {"message": "offline"}}) as send:
                self.assertFalse(client.select("other-device"))
                send.reset_mock()
                with self.assertRaisesRegex(RuntimeError, "explicit device"):
                    client.install("not-a-real.apk")
                send.assert_not_called()
        finally:
            client.close()


class ConnectorDeviceSafetyTest(unittest.TestCase):
    def setUp(self):
        # Do not construct a daemon: its constructor resolves adb and opens a socket.
        self.daemon = Daemon.__new__(Daemon)
        self.daemon._adb_path = "never-executed-adb"
        self.session = {"serial": None}
        self.run = MagicMock(return_value={"exit": 0, "stdout": "mock only"})
        self.daemon._run_adb = self.run

    def dispatch(self, method, params=None):
        return self.daemon._dispatch({"method": method, "params": params or {}}, MagicMock(), self.session)

    def test_auto_selection_does_not_authorize_install_or_shell_escape_hatches(self):
        with patch("scripts.tools.connector.daemon._adb_devices", return_value=[
            {"serial": "only-online-device", "state": "device"},
        ]):
            self.assertTrue(self.dispatch("select", {"serial": "auto"})["ok"])
        for method, params in (
            ("install", {"local": "app.apk"}),
            ("adb", {"args": ["install", "app.apk"]}),
            ("adb", {"args": ["uninstall", "io.stamethyst"]}),
            ("shell", {"command": "pm uninstall io.stamethyst"}),
            ("adb", {"args": ["shell", "cmd", "package", "install", "app.apk"]}),
        ):
            with self.subTest(method=method, params=params):
                self.assertIn("explicit device", self.dispatch(method, params)["error"]["message"])
        self.run.assert_not_called()

    def test_missing_selection_rejects_install(self):
        self.assertIn("error", self.dispatch("install", {"local": "app.apk"}))
        self.run.assert_not_called()

    def test_explicit_selection_binds_install_to_that_device(self):
        self.dispatch("select", {"serial": "approved-test-device"})
        self.assertEqual(self.dispatch("install", {"local": "app.apk"})["exit"], 0)
        self.run.assert_called_once_with(
            ["install", "-r", "app.apk"], serial="approved-test-device", timeout_s=180.0, capture="text",
        )

    def test_raw_adb_cannot_override_target(self):
        self.dispatch("select", {"serial": "approved-test-device"})
        for args in (["-s", "other-device", "uninstall", "io.stamethyst"], ["-e", "install", "a.apk"]):
            with self.subTest(args=args):
                self.assertIn("error", self.dispatch("adb", {"args": args}))
        self.run.assert_not_called()

    def test_failed_selection_revokes_previous_target(self):
        self.dispatch("select", {"serial": "previous-device"})
        self.assertIn("error", self.dispatch("select", {"serial": ""}))
        self.assertIn("error", self.dispatch("install", {"local": "app.apk"}))
        self.run.assert_not_called()

    def test_auto_does_not_pick_first_of_multiple_online_devices(self):
        with patch("scripts.tools.connector.daemon._adb_devices", return_value=[
            {"serial": "first-device", "state": "device"},
            {"serial": "second-device", "state": "device"},
        ]):
            self.assertIn("error", self.dispatch("select", {"serial": "auto"}))
        self.assertIsNone(self.session["serial"])
        self.run.assert_not_called()


class NoImplicitDeploymentTest(unittest.TestCase):
    def test_test_flows_have_no_install_calls_or_skip_install_branches(self):
        files = (
            "scripts/tools/harness/smoke.py",
            "scripts/tools/harness/single_room_run.py",
            "scripts/tools/harness/perf_bench.py",
            "scripts/tools/harness/startup_cache.py",
            "scripts/tools/ram_saver_ab.py",
        )
        trees = [(name, ast.parse((ROOT / name).read_text())) for name in files]
        legacy = ast.parse((ROOT / "scripts/tools/lib/sts_harness.py").read_text())
        steam_cloud = next(node for node in ast.walk(legacy)
                           if isinstance(node, ast.FunctionDef) and node.name == "harness_steam_cloud_sync")
        trees.append(("harness_steam_cloud_sync", steam_cloud))
        for name, tree in trees:
            with self.subTest(file=name):
                for node in ast.walk(tree):
                    if isinstance(node, ast.Call):
                        called = node.func.id if isinstance(node.func, ast.Name) else getattr(node.func, "attr", "")
                        self.assertNotIn(called, {"install", "run_install", "harness_install", "build_and_install"})
                        for arg in node.args:
                            if isinstance(arg, ast.Constant) and isinstance(arg.value, str):
                                self.assertNotIn(arg.value, {"install", "uninstall", "install-multiple"})
                    if isinstance(node, ast.Attribute):
                        self.assertNotEqual(node.attr, "skip_install")

    def test_install_shell_rejects_without_running_build_or_adb(self):
        temp_root = ROOT / "agent-tmp"
        temp_root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as directory:
            marker = Path(directory) / "called"
            stub = Path(directory) / "forbidden-tool"
            stub.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 99\n")
            stub.chmod(0o755)
            env = {**os.environ, "ADB_BIN": str(stub), "GRADLE_BIN": str(stub)}
            for args in ([], ["auto"], ["AUTO"], ["*"], ["-d"]):
                with self.subTest(args=args):
                    result = subprocess.run(
                        ["bash", str(ROOT / "scripts/build-debug-install.sh"), *args],
                        env=env, capture_output=True, text=True, timeout=10,
                    )
                    self.assertEqual(result.returncode, 1)
                    self.assertIn("explicit", result.stderr)
                    self.assertFalse(marker.exists())

    def test_ram_saver_collect_requires_device_before_output_or_subprocess(self):
        with patch("scripts.tools.ram_saver_ab.subprocess.run") as run, \
                patch.object(Path, "mkdir") as mkdir:
            for serial in ("", "auto"):
                with self.assertRaisesRegex(RuntimeError, "explicit --device"):
                    collect(serial, Path("a.apk"), Path("unused-output"), 1)
            run.assert_not_called()
            mkdir.assert_not_called()

    def test_gradle_guard_covers_instrumentation_and_package_task_types(self):
        source = (ROOT / "build.gradle.kts").read_text()
        for task_type in ("AndroidTestTask", "DeviceSerialTestTask", "InstallVariantTask", "UninstallTask"):
            self.assertIn(f"this is {task_type}", source)
        self.assertIn("gradle.taskGraph.whenReady", source)
        self.assertIn("allTasks.filter { it.deploysAndroidPackages() }", source)
        self.assertIn("doFirst { throw GradleException(deviceDeploymentDenied) }", source)


if __name__ == "__main__":
    unittest.main()
