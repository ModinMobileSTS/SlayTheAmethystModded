import socket
import unittest

from scripts.tools.connector.client import ConnectorClient, ConnectorError


class ConnectorClientTest(unittest.TestCase):
    def test_recv_json_raises_when_daemon_closes_without_response(self):
        client_socket, daemon_socket = socket.socketpair()
        client = ConnectorClient(port=1, auto_start=False)
        client._sock.close()
        client._sock = client_socket
        daemon_socket.close()
        try:
            with self.assertRaisesRegex(ConnectorError, "closed the control connection"):
                client._recv_json()
        finally:
            client.close()


if __name__ == "__main__":
    unittest.main()
