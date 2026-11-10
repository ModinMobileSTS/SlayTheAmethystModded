from __future__ import annotations

import os
import unittest
from unittest.mock import MagicMock, patch


class TestArthasDeviceResolution(unittest.TestCase):
    def test_parse_query_accepts_duration_before_command(self) -> None:
        from scripts.tools.arthas.__main__ import _parse_args

        args = _parse_args([
            "--device", "192.0.2.1:45678", "query", "--duration", "20",
            "monitor Foo bar",
        ])

        self.assertEqual(args.query_parts, ["monitor Foo bar"])
        self.assertEqual(args.duration, 20.0)

    def test_resolve_device_prefers_cli_device(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        self.assertEqual("192.0.2.1:45678", resolve_device(conn, "192.0.2.1:45678"))
        conn.devices.assert_not_called()

    def test_resolve_device_uses_env_when_cli_missing(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        with patch(
            "scripts.tools.arthas.__main__.get_test_device_serial",
            return_value="localhost:25555",
        ):
            self.assertEqual("localhost:25555", resolve_device(conn, None))
        conn.devices.assert_not_called()

    def test_resolve_device_auto_selects_single_online_device(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        conn.devices.return_value = [
            {"serial": "192.0.2.1:45678", "state": "device"},
        ]
        with patch(
            "scripts.tools.arthas.__main__.get_test_device_serial",
            return_value="auto",
        ):
            self.assertEqual("192.0.2.1:45678", resolve_device(conn, None))

    def test_resolve_device_rejects_multiple_devices_without_explicit_serial(self) -> None:
        from scripts.tools.arthas.__main__ import resolve_device

        conn = MagicMock()
        conn.devices.return_value = [
            {"serial": "192.0.2.1:45678", "state": "device"},
            {"serial": "localhost:25555", "state": "device"},
        ]
        with patch(
            "scripts.tools.arthas.__main__.get_test_device_serial",
            return_value="auto",
        ):
            with self.assertRaises(SystemExit) as ctx:
                resolve_device(conn, None)
        self.assertIn("192.0.2.1:45678", str(ctx.exception))
        self.assertIn("localhost:25555", str(ctx.exception))


class TestArthasMainCommands(unittest.TestCase):
    def test_start_selects_explicit_device_and_uses_daemon_health_check(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        mgr = MagicMock()
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "ArthasManager", return_value=mgr), patch.object(
            arthas_main, "resolve_device", return_value="192.0.2.1:45678"
        ):
            code = arthas_main.main(["--device", "192.0.2.1:45678", "start"])

        self.assertEqual(0, code)
        conn.connect.assert_called_once()
        conn.select.assert_called_once_with("192.0.2.1:45678")
        mgr.start.assert_called_once()
        conn.close.assert_called_once()

    def test_query_selects_explicit_device_and_cleans_up(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_arthas_stream.return_value = stream
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "resolve_device", return_value="localhost:25555"
        ), patch.object(arthas_main, "run_query") as run_query:
            code = arthas_main.main(
                ["--device", "localhost:25555", "query", "version"]
            )

        self.assertEqual(0, code)
        conn.select.assert_called_once_with("localhost:25555")
        conn.connect_arthas_stream.assert_called_once_with(agent_port=9099, arthas_port=8099)
        run_query.assert_called_once()
        self.assertEqual("version", run_query.call_args.args[1])
        stream.close.assert_called_once()
        conn.close.assert_called_once()

    def test_query_cleans_up_on_failure(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_arthas_stream.return_value = stream
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "resolve_device", return_value="192.0.2.1:45678"
        ), patch.object(arthas_main, "run_query", side_effect=RuntimeError("boom")):
            code = arthas_main.main(["--device", "192.0.2.1:45678", "query", "version"])

        self.assertEqual(1, code)
        stream.close.assert_called_once()
        conn.close.assert_called_once()

    def test_reconnect_keeps_resolved_device(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        stream = MagicMock()
        conn.connect_arthas_stream.return_value = stream
        captured = {}

        def capture_run_query(stream_arg, command, reconnect_fn=None, stdout=None):
            captured["reconnect_fn"] = reconnect_fn

        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "resolve_device", return_value="192.0.2.1:45678"
        ), patch.object(arthas_main, "run_query", side_effect=capture_run_query):
            arthas_main.main(["--device", "192.0.2.1:45678", "query", "trace Foo bar"])

        reconnect = captured["reconnect_fn"]
        self.assertIsNotNone(reconnect)
        reconnect_conn = MagicMock()
        reconnect_stream = MagicMock()
        reconnect_conn.connect_arthas_stream.return_value = reconnect_stream
        with patch.object(arthas_main, "ConnectorClient", return_value=reconnect_conn):
            result = reconnect()

        self.assertIs(result, reconnect_stream)
        reconnect_conn.select.assert_called_once_with("192.0.2.1:45678")
        reconnect_conn.connect_arthas_stream.assert_called_once_with(agent_port=9099, arthas_port=8099)

    def test_stop_selects_device_and_invokes_manager_stop(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        mgr = MagicMock()
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "ArthasManager", return_value=mgr
        ), patch.object(arthas_main, "resolve_device", return_value="192.0.2.1:45678"):
            code = arthas_main.main(["--device", "192.0.2.1:45678", "stop"])

        self.assertEqual(0, code)
        conn.select.assert_called_once_with("192.0.2.1:45678")
        mgr.stop.assert_called_once()
        mgr.shutdown.assert_not_called()
        conn.close.assert_called_once()

    def test_shutdown_selects_device_and_invokes_manager_shutdown(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        mgr = MagicMock()
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "ArthasManager", return_value=mgr
        ), patch.object(arthas_main, "resolve_device", return_value="192.0.2.1:45678"):
            code = arthas_main.main(["--device", "192.0.2.1:45678", "shutdown"])

        self.assertEqual(0, code)
        conn.select.assert_called_once_with("192.0.2.1:45678")
        mgr.shutdown.assert_called_once()
        mgr.stop.assert_not_called()
        conn.close.assert_called_once()

    def test_shutdown_parses_arthas_port(self) -> None:
        from scripts.tools.arthas import __main__ as arthas_main

        conn = MagicMock()
        mgr = MagicMock()
        with patch.object(arthas_main, "ConnectorClient", return_value=conn), patch.object(
            arthas_main, "ArthasManager", return_value=mgr
        ), patch.object(arthas_main, "resolve_device", return_value="192.0.2.1:45678"):
            arthas_main.main(["--device", "192.0.2.1:45678", "--arthas-port", "18099", "shutdown"])

        mgr.shutdown.assert_called_once_with(port=18099)


# ArthasManager stop/shutdown semantics are covered by test_arthas_manager.py.


if __name__ == "__main__":
    unittest.main()
