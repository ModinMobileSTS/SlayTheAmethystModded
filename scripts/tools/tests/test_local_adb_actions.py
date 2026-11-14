import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import sys
import unittest
from unittest.mock import patch

from scripts.tools import local_adb, local_adb_actions as actions


class LocalAdbActionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=local_adb.ROOT / "agent-tmp")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "test.apk").touch()
        self.serial = "192.0.2.1:45678"

    def prepare(self, action, **data):
        return actions.prepare(self.root, action, data)[0]

    def test_install_defaults_fastdeploy_and_allows_opt_out(self):
        for fast in (True, False):
            args = self.prepare("install", apkPath="test.apk", deviceSerial=self.serial, fastDeploy=fast)
            self.assertEqual(args, ["install", *(["--fastdeploy"] if fast else []), "-r", str(self.root / "test.apk")])
        self.assertIn("--fastdeploy", self.prepare("install", apkPath="test.apk", deviceSerial=self.serial))

    def test_install_rejects_auto_device_and_escaping_paths(self):
        for serial in (None, "", "auto", "default", "all", "*", "-s", "a b"):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                self.prepare("install", apkPath="test.apk", deviceSerial=serial)
        for path in ("../outside.apk", "missing.apk", "test.txt"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.prepare("install", apkPath=path, deviceSerial=self.serial)
        (self.root / "escape.apk").symlink_to(self.root.parent / "outside.apk")
        with self.assertRaises(ValueError):
            self.prepare("install", apkPath="escape.apk", deviceSerial=self.serial)

    def test_instrument_extras_are_remote_shell_quoted(self):
        value = "spaces; $(touch /bad) ' quoted"
        args = self.prepare("instrument", runner="io.test/.Runner", testClasses=["io.test.Foo#bar"], extras={"payload": value})
        self.assertEqual(args[0], "shell")
        self.assertEqual(shlex.split(args[1]), ["am", "instrument", "-w", "-r", "-e", "class", "io.test.Foo#bar", "-e", "payload", value, "io.test/.Runner"])
        self.assertNotIn("install", args[1])

    def test_structured_action_mapping(self):
        cases = [
            ("device-info", {}, ["getprop"]),
            ("package-info", {"packageName": "io.test"}, ["dumpsys", "package", "io.test"]),
            ("list-instrumentation", {}, ["pm", "list", "instrumentation"]),
            ("tap", {"x": 0, "y": 100}, ["input", "tap", "0", "100"]),
            ("swipe", {"x": 1, "y": 2, "endX": 3, "endY": 4}, ["input", "swipe", "1", "2", "3", "4", "300"]),
            ("keyevent", {"keyCode": 4}, ["input", "keyevent", "4"]),
            ("start-activity", {"component": "io.test/.Main"}, ["am", "start", "-W", "-n", "io.test/.Main"]),
            ("force-stop", {"packageName": "io.test"}, ["am", "force-stop", "io.test"]),
        ]
        for action, data, expected in cases:
            with self.subTest(action=action):
                args = self.prepare(action, **data)
                self.assertEqual(args[0], "shell")
                self.assertEqual(shlex.split(args[1]), expected)
        self.assertEqual(self.prepare("screenshot"), ["exec-out", "screencap", "-p"])

    def test_invalid_requests_never_connect(self):
        cases = [("shell", {}), ("tap", {"x": -1, "y": 2}), ("tap", {"x": True, "y": 2}),
                 ("force-stop", {"packageName": "io.test;reboot"}), ("device-info", {"command": "reboot"}),
                 ("instrument", {"runner": "io.test/.Runner", "extras": {"class": "Foo"}}),
                 ("instrument", {"runner": "bad;reboot"}), ("device-info", {"timeoutSeconds": 0}),
                 ("device-info", {"timeoutSeconds": 3601}), ("device-info", [])]
        with patch.object(local_adb, "ensure") as ensure:
            for action, data in cases:
                with self.subTest(action=action, data=data), self.assertRaises(ValueError):
                    actions.run(action, data)
            ensure.assert_not_called()

    def run_fake(self, action, data, stdout=b"ok", code=0, error=None):
        c = {"STS_ADB_BINARY": "/fake/adb", "STS_ADB_SERVER_PORT": "16037", "STS_ADB_DEVICE_ENDPOINT": self.serial}
        def execute(args, **kwargs):
            self.assertEqual(args[:7], ["/fake/adb", "-H", "127.0.0.1", "-P", "16037", "-s", self.serial])
            if error:
                raise error
            kwargs["stdout"].write(stdout)
            kwargs["stderr"].write(b"diagnostic")
            return subprocess.CompletedProcess(args, code)
        with patch.object(local_adb, "ROOT", self.root), patch.object(local_adb, "config", return_value=c), \
             patch.object(local_adb, "ensure", return_value={}) as ensure, \
             patch.object(subprocess, "run", side_effect=execute) as run:
            result = actions.run(action, data)
            ensure.assert_called_once()
            self.assertEqual(run.call_args.kwargs["timeout"], data.get("timeoutSeconds", 120))
            return result

    def test_execution_captures_artifact_bounds_output_and_preserves_failure(self):
        result = self.run_fake("instrument", {"runner": "io.test/.Runner", "timeoutSeconds": 55}, b"x" * 13000, 7)
        self.assertEqual(result["exitCode"], 7)
        self.assertEqual(len(result["stdout"]), actions.PREVIEW_BYTES)
        self.assertTrue(result["truncated"])
        self.assertEqual((self.root / result["artifact"]).stat().st_size, 13000)

    def test_device_mismatch_refused_before_ensure(self):
        with patch.object(local_adb, "config", return_value={"STS_ADB_DEVICE_ENDPOINT": self.serial}), patch.object(local_adb, "ensure") as ensure:
            with self.assertRaisesRegex(ValueError, "match"):
                actions.run("device-info", {"deviceSerial": "other"})
            ensure.assert_not_called()

    def test_device_info_only_returns_selected_properties(self):
        result = self.run_fake("device-info", {}, b"[ro.product.model]: [Test]\n[private]: [secret]\n")
        self.assertEqual(result["device"]["model"], "Test")
        self.assertNotIn("secret", json.dumps(result))
        self.assertNotIn("secret", (self.root / result["artifact"]).read_text())

    def test_screenshot_and_failure_cleanup(self):
        result = self.run_fake("screenshot", {}, b"\x89PNG\r\n\x1a\nimage")
        self.assertEqual(result["stdout"], "")
        self.assertTrue((self.root / result["artifact"]).exists())
        before = set(self.root.rglob("*.png"))
        with self.assertRaisesRegex(RuntimeError, "Screenshot"):
            self.run_fake("screenshot", {}, b"not png")
        self.assertEqual(set(self.root.rglob("*.png")), before)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_fake("screenshot", {}, error=subprocess.TimeoutExpired("adb", 120))
        self.assertEqual(set(self.root.rglob("*.png")), before)

    def test_cli_dispatches_json_and_propagates_exit_code(self):
        with patch.object(sys, "argv", ["local_adb", "instrument", "--request", '{"runner":"io.test/.Runner"}']), \
             patch.object(actions, "run", return_value={"exitCode": 7}) as run, \
             patch("builtins.print") as output:
            with self.assertRaises(SystemExit) as caught:
                local_adb.main()
            self.assertEqual(caught.exception.code, 7)
            run.assert_called_once_with("instrument", {"runner": "io.test/.Runner"})
            self.assertEqual(json.loads(output.call_args.args[0]), {"exitCode": 7})

    def test_cli_rejects_malformed_requests_without_dispatch(self):
        for argv in (["device-info"], ["device-info", "--request", "{"], ["device-info", "--request", "{}", "extra"]):
            with self.subTest(argv=argv), patch.object(sys, "argv", ["local_adb", *argv]), \
                 patch.object(actions, "run") as run, patch("sys.stderr"):
                with self.assertRaises(SystemExit) as caught:
                    local_adb.main()
                self.assertEqual(caught.exception.code, 2)
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
