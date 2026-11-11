from __future__ import annotations

import json as _json
import socket
import unittest
from unittest.mock import MagicMock, patch


class TestConnectorClient(unittest.TestCase):

    def test_send_request_json_roundtrip(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [
            b'{"result":{"ok":true}}\n',
            b"",
        ]
        client._sock = mock_sock
        response = client.send_request({"method": "ping"})
        mock_sock.sendall.assert_called_once()
        sent_bytes = mock_sock.sendall.call_args[0][0]
        sent = _json.loads(sent_bytes.decode("utf-8"))
        self.assertEqual(sent, {"method": "ping"})
        self.assertEqual(response, {"result": {"ok": True}})

    def test_close_shuts_down_socket_and_clears_state(self):
        from scripts.tools.connector.client import ConnectorClient
        mock_sock = MagicMock()
        client = ConnectorClient(port=1)
        client._sock = mock_sock
        client.close()
        mock_sock.close.assert_called_once()

    def test_devices_returns_device_list(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"devices":[{"serial":"x","state":"device"}]}\n')
        result = client.devices()
        self.assertEqual(
            result, [{"serial": "x", "state": "device"}])

    def test_select_sends_serial_and_timeout(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.return_value = b'{"ok":true}\n'
        result = client.select(serial="abc", timeout_ms=5000)
        self.assertTrue(result)
        self.assertEqual("abc", client._selected_serial)

    def test_status_returns_dict(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"serial":"abc","state":"online"}\n')
        result = client.status()
        self.assertEqual(result["serial"], "abc")

    def test_forward_returns_port_info(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"ok":true,"port":9099}\n')
        result = client.forward(port=9099)
        self.assertEqual(result["port"], 9099)

    def test_unforward_returns_ok(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.return_value = b'{"ok":true}\n'
        result = client.unforward(port=9099)
        self.assertTrue(result)

    def test_shell_returns_stdout(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.return_value = (
            b'{"exit":0,"stdout":"hello","stderr":""}\n')
        result = client.shell("echo hello")
        self.assertEqual(result["stdout"], "hello")

    def test_arthas_health_methods_use_daemon_rpc(self):
        from scripts.tools.connector.client import ConnectorClient

        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._sock.recv.side_effect = [
            b'{"ok":true,"state":"ready"}\n',
            b'{"ok":true,"state":"ready"}\n',
            b'{"ok":true}\n',
            b'{"ok":true}\n',
        ]

        self.assertEqual("ready", client.arthas_status()["state"])
        self.assertTrue(client.arthas_ensure(arthas_port=18099)["ok"])
        self.assertTrue(client.arthas_reset(arthas_port=18099)["ok"])
        self.assertTrue(client.arthas_shutdown(arthas_port=18099)["ok"])

    def test_adb_install_logcat_helpers(self):
        from scripts.tools.connector.client import ConnectorClient
        client = ConnectorClient(port=1)
        client._sock = MagicMock()
        client._selected_serial = "approved-test-device"
        client._sock.recv.side_effect = [
            b'{"exit":0,"stdout":"Success"}\n',
            b'{"exit":0,"stdout":"ok"}\n',
            b'{"ok":true,"capture_id":"lc1","local_path":"/tmp/a.txt"}\n',
            b'{"ok":true,"capture_id":"lc1","exit":0}\n',
        ]
        self.assertEqual(client.install("/tmp/app.apk")["exit"], 0)
        self.assertEqual(client.adb(["shell", "echo"])["stdout"], "ok")
        start = client.logcat_start(local_path="/tmp/a.txt")
        self.assertEqual(start["capture_id"], "lc1")
        stop = client.logcat_stop("lc1")
        self.assertTrue(stop["ok"])

    @patch("socket.socket")
    def test_connect_stream_sends_request_and_returns_stream_id(self, mock_socket_cls):
        from scripts.tools.connector.client import ConnectorClient
        mock_new_sock = MagicMock()
        mock_new_sock.recv.return_value = b'{"ok":true}\n'
        mock_socket_cls.return_value = mock_new_sock

        client = ConnectorClient(port=1)
        mock_sock = MagicMock()
        mock_sock.recv.return_value = b'{"stream_id":"s1"}\n'
        client._sock = mock_sock
        client._selected_serial = "device-a"
        stream = client.connect_stream(port=8099)
        sent = mock_sock.sendall.call_args[0][0]
        req = _json.loads(sent.decode("utf-8"))
        self.assertEqual(req["method"], "connect_stream")
        self.assertEqual(req["params"]["port"], 8099)
        self.assertEqual(stream.stream_id, "s1")
        mock_new_sock.connect.assert_called_once_with(("127.0.0.1", 1))
        self.assertIs(client._sock, mock_new_sock)
        mock_new_sock.sendall.assert_called()

    @patch("socket.socket")
    def test_connect_stream_preserves_handshake_remainder(self, mock_socket_cls):
        from scripts.tools.connector.client import ConnectorClient

        mock_new_sock = MagicMock()
        mock_socket_cls.return_value = mock_new_sock
        client = ConnectorClient(port=1)
        mock_sock = MagicMock()
        mock_sock.recv.return_value = b'{"stream_id":"s1"}\ninitial prompt'
        client._sock = mock_sock

        stream = client.connect_stream(port=8099)

        self.assertEqual(stream.read(), b"initial prompt")
        stream.close()

    @patch("socket.socket")
    def test_stream_raw_io(self, mock_socket_cls):
        from scripts.tools.connector.client import ConnectorClient, Stream
        mock_new_sock = MagicMock()
        mock_socket_cls.return_value = mock_new_sock

        client = ConnectorClient(port=1)
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = [b'{"stream_id":"st"}\n']
        client._sock = mock_sock
        stream = client.connect_stream(port=8099)
        self.assertIsInstance(stream, Stream)
        self.assertEqual(stream.stream_id, "st")
        stream.write(b"hello\n")
        self.assertEqual(mock_sock.sendall.call_count, 2)
        mock_sock.sendall.assert_any_call(b"hello\n")
        # The client keeps a fresh control socket for future requests while
        # the request socket is owned by the returned stream.
        self.assertIs(client._sock, mock_new_sock)


if __name__ == "__main__":
    unittest.main()
