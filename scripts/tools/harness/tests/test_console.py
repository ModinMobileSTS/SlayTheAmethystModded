import os
import unittest
from pathlib import Path
from unittest.mock import ANY, MagicMock, patch

from scripts.tools.lib.agent_client import AgentClient, AgentError
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.console import run_console

TEST_DEVICE_SERIAL = os.environ.get("TEST_DEVICE_SERIAL", "auto")


class ConsoleExecTest(unittest.TestCase):
    def _make_mock_client(self, result):
        client = MagicMock(spec=AgentClient)
        client.console_exec.return_value = result
        return client

    def _make_ctx(self, console_command=""):
        from scripts.tools.lib.sts_harness import HarnessOptions
        return HarnessContext(
            options=HarnessOptions(
                command="console", launch_mode="mts_basemod",
                device_serial=TEST_DEVICE_SERIAL, out_dir="", timeout_seconds=120,
                poll_interval_seconds=2, force_jvm_crash=False, force_runtime_crash=False,
                debug_mode=False, autoplay=False, skip_install=False,
                no_stop_after_smoke=False, mods=[], mod_list_file="",
                enable_all_mods=False, disable_all_mods=False,
                console_command=console_command,
            ),
            repo_root=MagicMock(),
            result={"artifacts": {}},
            connector=MagicMock(),
        )

    def test_one_shot_execute(self):
        ctx = self._make_ctx("gold 999")
        mock_client = self._make_mock_client(
            {"executed": True, "command": "gold 999", "output": "ok"})
        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client):
            run_console(ctx, Path("/tmp/test"))
            mock_client.console_exec.assert_called_once_with("gold 999")
            self.assertTrue(ctx.result["success"])
            self.assertEqual(ctx.result["status"], "CONSOLE_EXECUTED")

    def test_one_shot_rejected_command_reports_error(self):
        ctx = self._make_ctx("gold 999")
        mock_client = self._make_mock_client(
            {"executed": False, "error": "BaseMod not loaded"})
        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client):
            run_console(ctx, Path("/tmp/test"))
        mock_client.console_exec.assert_called_once_with("gold 999")
        self.assertFalse(ctx.result["success"])
        self.assertEqual(ctx.result["status"], "ERROR")
        self.assertIn("BaseMod not loaded", ctx.result["message"])
        mock_client.close.assert_called_once()

    def test_default_port_uses_agent_client(self):
        from scripts.tools.harness.agent import _connect_agent

        ctx = self._make_ctx("gold 999")
        mock_connector = MagicMock()
        mock_stream = MagicMock()
        mock_connector.connect_stream.return_value = mock_stream
        ctx.connector = mock_connector
        with patch("scripts.tools.harness.agent.AgentClient") as client_type:
            instance = MagicMock()
            client_type.return_value = instance
            client = _connect_agent(ctx)
        client_type.assert_called_once_with(connector=mock_connector, port=9099)
        instance.connect.assert_called_once_with()
        self.assertIs(client, instance)

    def test_interactive_mode_when_no_command(self):
        ctx = self._make_ctx("")
        mock_client = self._make_mock_client({"executed": True, "command": "help", "output": "ok"})

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client), \
             patch("scripts.tools.harness.console.input", side_effect=["help", "exit"]):
            run_console(ctx, Path("/tmp/test"))
            self.assertEqual(ctx.result["status"], "CONSOLE_SESSION_COMPLETE")

    def test_handles_agent_error(self):
        ctx = self._make_ctx("gold 999")
        mock_client = MagicMock(spec=AgentClient)
        mock_client.console_exec.side_effect = AgentError("connection failed")

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client):
            run_console(ctx, Path("/tmp/test"))
            self.assertFalse(ctx.result["success"])
            self.assertEqual(ctx.result["status"], "ERROR")
            self.assertIn("connection failed", ctx.result["message"])

    def test_non_executed_result_is_failure(self):
        ctx = self._make_ctx("help")
        mock_client = self._make_mock_client(
            {"executed": False, "error": "BaseMod DevConsole is unavailable"})

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client):
            run_console(ctx, Path("/tmp/test"))

        self.assertFalse(ctx.result["success"])
        self.assertEqual(ctx.result["status"], "ERROR")
        self.assertIn("BaseMod DevConsole is unavailable", ctx.result["message"])

    def test_handles_generic_exception(self):
        ctx = self._make_ctx("gold 999")
        mock_client = MagicMock(spec=AgentClient)
        mock_client.console_exec.side_effect = RuntimeError("boom")

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client):
            run_console(ctx, Path("/tmp/test"))
            self.assertFalse(ctx.result["success"])
            self.assertEqual(ctx.result["status"], "ERROR")
            self.assertIn("boom", ctx.result["message"])

    def test_cleans_up_connection(self):
        ctx = self._make_ctx("")
        mock_client = self._make_mock_client({"executed": False, "error": "x"})

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client), \
             patch("scripts.tools.harness.console.input", side_effect=["exit"]):
            run_console(ctx, Path("/tmp/test"))
            mock_client.close.assert_called_once()

    def test_error_does_not_prevent_cleanup(self):
        ctx = self._make_ctx("")
        mock_client = self._make_mock_client({"executed": False, "error": "x"})

        with patch("scripts.tools.harness.console._connect_agent", return_value=mock_client), \
             patch("scripts.tools.harness.console.input", side_effect=RuntimeError("input crash")):
            try:
                run_console(ctx, Path("/tmp/test"))
            except RuntimeError:
                pass
            mock_client.close.assert_called_once()


class AgentClientConsoleTest(unittest.TestCase):
    def test_console_exec_success(self):
        client = AgentClient()
        client._stream = MagicMock()
        client._stream.readline.return_value = (
            b'RESULT {"executed":true,"command":"gold 999","output":"ok"}\n'
        )
        result = client.console_exec("gold 999")
        self.assertTrue(result["executed"])
        self.assertEqual(result["command"], "gold 999")

    def test_console_exec_error_response(self):
        client = AgentClient()
        client._stream = MagicMock()
        client._stream.readline.return_value = b"ERROR BaseMod not loaded\n"
        with self.assertRaises(AgentError):
            client.console_exec("gold 999")


if __name__ == "__main__":
    unittest.main()
