from __future__ import annotations

import base64
import json
import socket
import unittest
from unittest.mock import MagicMock, patch

from scripts.tools.lib.agent_client import AgentClient, AgentError


class TestAgentClient(unittest.TestCase):

    @staticmethod
    def _mock_client(response_line: str) -> AgentClient:
        client = AgentClient()
        mock_sock = MagicMock()
        mock_reader = MagicMock()
        mock_writer = MagicMock()
        mock_reader.readline.return_value = response_line
        client._sock = mock_sock
        client._reader = mock_reader
        client._writer = mock_writer
        return client

    def test_connect_opens_tcp_to_host_port(self):
        with patch("socket.socket") as mock_socket_cls:
            mock_sock = MagicMock()
            mock_socket_cls.return_value = mock_sock
            client = AgentClient(host="127.0.0.1", port=9099)
            client.connect()
            mock_socket_cls.assert_called_once_with(
                socket.AF_INET, socket.SOCK_STREAM)
            mock_sock.connect.assert_called_once_with(("127.0.0.1", 9099))

    def test_connect_with_connector_forwards_port(self):
        mock_conn = MagicMock()
        mock_stream = MagicMock()
        mock_conn.connect_stream.return_value = mock_stream
        client = AgentClient(connector=mock_conn, port=9099)
        client.connect()
        mock_conn.connect_stream.assert_called_once_with(port=9099)

    def test_attach_returns_agent_id(self):
        client = self._mock_client("OK agent-1")
        aid = client.attach("tracing@classes=com.example.*")
        self.assertEqual(aid, "agent-1")

    def test_attach_error_raises(self):
        client = self._mock_client("ERROR bad spec")
        with self.assertRaises(AgentError):
            client.attach("bad")

    def test_observe_returns_parsed_state(self):
        client = self._mock_client('STATE {"mode":"GAMEPLAY"}')
        state = client.observe()
        self.assertEqual(state, {"mode": "GAMEPLAY"})

    def test_ready_uses_explicit_console_handshake(self):
        client = self._mock_client("READY")
        self.assertTrue(client.ready())

    def test_ready_rejects_console_not_loaded(self):
        client = self._mock_client("ERROR BaseMod DevConsole not loaded")
        self.assertFalse(client.ready())

    def test_execute_returns_result(self):
        client = self._mock_client(
            'RESULT {"queued":true,"command":"PLAY_CARD"}')
        result = client.execute("PLAY_CARD", {})
        self.assertEqual(result["queued"], True)
        self.assertEqual(result["command"], "PLAY_CARD")

    def test_dump_class_decodes_b64(self):
        raw = bytes([0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 50])
        b64 = base64.b64encode(raw).decode("ascii")
        client = self._mock_client(f"BYTECODE {b64}")
        result = client.dump_class("com.example.Foo")
        self.assertEqual(result[:4], b'\xca\xfe\xba\xbe')

    def test_load_agent_sends_correct_command(self):
        client = self._mock_client("OK")
        client.load_agent("/sdcard/arthas/agent.jar", "http-port=8563")
        client._writer.write.assert_called()

    def test_close_cleans_up(self):
        client = self._mock_client("BYE")
        mock_sock = client._sock
        client.close()
        mock_sock.close.assert_called_once()
        self.assertIsNone(client._sock)


if __name__ == "__main__":
    unittest.main()
