from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.tools import local_adb as adb


class LocalAdbTests(unittest.TestCase):
    def setUp(self):
        parent = adb.ROOT / "agent-tmp"
        parent.mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.binary = self.root / "adb"
        self.binary.touch()
        self.c = {"STS_ADB_BINARY": str(self.binary), "STS_ADB_SERVER_PORT": "16037",
                  "STS_ADB_DEVICE_ENDPOINT": "192.0.2.1:45678"}
        self.addCleanup(patch.stopall)
        patch.object(adb, "ROOT", self.root).start()
        patch.object(adb, "STATE", self.root / "state").start()
        patch.dict(os.environ, {}, clear=True).start()

    def write_config(self, **overrides):
        values = {**self.c, **overrides}
        (self.root / ".env.local").write_text("\n".join(f"{k}={v}" for k, v in values.items()))

    def test_config_and_environment_precedence(self):
        self.write_config()
        os.environ["STS_ADB_SERVER_PORT"] = "17037"
        self.assertEqual(adb.config()["STS_ADB_SERVER_PORT"], "17037")

    def test_global_server_and_invalid_endpoint_rejected(self):
        for change in ({"STS_ADB_SERVER_PORT": "5037"}, {"STS_ADB_DEVICE_ENDPOINT": "bad"},
                       {"STS_ADB_BINARY": "adb"}, {"UNSUPPORTED": "value"}):
            self.write_config(**change)
            with self.assertRaises(RuntimeError):
                adb.config()

    def test_child_environment_replaces_legacy_config(self):
        os.environ.update(STS_CONNECTOR_PORT="1234", STS_TEST_DEVICE="old", ADB_SERVER_SOCKET="tcp:elsewhere:5037")
        env = adb.environment(self.c)
        self.assertNotIn("STS_CONNECTOR_PORT", env)
        self.assertEqual(env["STS_TEST_DEVICE"], self.c["STS_ADB_DEVICE_ENDPOINT"])
        self.assertEqual(env["ADB_SERVER_SOCKET"], "tcp:127.0.0.1:16037")
        self.assertEqual(os.environ["STS_CONNECTOR_PORT"], "1234")

    def test_every_command_explicitly_selects_project_server(self):
        self.assertEqual(adb.command(self.c, "devices"),
                         [str(self.binary), "-H", "127.0.0.1", "-P", "16037", "devices"])

    def test_unknown_listener_is_never_contacted_or_stopped(self):
        self.write_config()
        with patch.object(adb, "listening", return_value=True), patch.object(adb.subprocess, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "unowned"):
                adb.ensure()
            run.assert_not_called()

    def test_replaced_server_pid_refused(self):
        self.write_config()
        adb.STATE.mkdir()
        (adb.STATE / "state.json").write_text(json.dumps({"identity": adb.identity(self.c), "adbPid": 99999999}))
        with patch.object(adb, "listening", return_value=True), patch.object(adb.subprocess, "run") as run:
            with self.assertRaisesRegex(RuntimeError, "unowned"):
                adb.ensure()
            run.assert_not_called()

    def test_connector_identity_must_match_workspace(self):
        with patch.object(adb, "request", return_value={"localAdbIdentity": {"root": "other"}}):
            self.assertFalse(adb.connector_owned({"connectorPort": 1234}, self.c))
        with patch.object(adb, "request", return_value={"localAdbIdentity": adb.identity(self.c)}):
            self.assertTrue(adb.connector_owned({"connectorPort": 1234}, self.c))

    def test_lock_released_on_error(self):
        with self.assertRaises(RuntimeError):
            with adb.locked():
                raise RuntimeError("test")
        with adb.locked():
            pass

    def test_healthy_instances_reused(self):
        self.write_config()
        state = {"identity": adb.identity(self.c), "adbPid": 123, "connectorPort": 23456}
        with patch.object(adb, "read_state", return_value=state), \
             patch.object(adb, "listening", return_value=True), \
             patch.object(adb, "server_owned", return_value=True), \
             patch.object(adb, "connector_owned", return_value=True), \
             patch.object(adb.subprocess, "Popen") as popen, \
             patch.object(adb.subprocess, "run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = "device\n"
            env = adb.ensure()
            self.assertEqual(env["STS_CONNECTOR_PORT"], "23456")
            popen.assert_not_called()
            for call in run.call_args_list:
                self.assertIn("16037", call.args[0])
                self.assertNotIn("5037", call.args[0])

    def test_offline_device_does_not_start_connector(self):
        self.write_config()
        state = {"identity": adb.identity(self.c), "adbPid": 123}
        with patch.object(adb, "read_state", return_value=state), \
             patch.object(adb, "listening", return_value=True), \
             patch.object(adb, "server_owned", return_value=True), \
             patch.object(adb.subprocess, "Popen") as popen, \
             patch.object(adb.subprocess, "run") as run:
            run.return_value.returncode = 1
            with self.assertRaisesRegex(RuntimeError, "not online"):
                adb.ensure()
            popen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
