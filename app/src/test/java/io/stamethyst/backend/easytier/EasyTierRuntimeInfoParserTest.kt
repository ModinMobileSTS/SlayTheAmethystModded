package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EasyTierRuntimeInfoParserTest {
    @Test
    fun parse_classifiesRoutesByDestinationAndIgnoresClosedConnectionsAndServers() {
        val info = requireNotNull(EasyTierRuntimeInfoParser.parse("""
            {"map":{"runtime":{
              "running":true,"my_node_info":{"virtual_ipv4":"10.0.0.1/24"},
              "peers":[
                {"peer_id":2,"conns":[{"tunnel":{"tunnel_type":"udp"}}]},
                {"peer_id":3,"conns":[{"tunnel":{"tunnel_type":"tcp"}}]},
                {"peer_id":4,"conns":[{"is_closed":true,"tunnel":{"tunnel_type":"udp"}}]},
                {"peer_id":99,"conns":[{"tunnel":{"tunnel_type":"tcp"}}]}
              ],
              "routes":[
                {"peer_id":1,"ipv4_addr":"10.0.0.1/24","cost":0},
                {"peer_id":2,"ipv4_addr":{"address":{"addr":167772162},"network_length":24},"cost":1},
                {"peer_id":3,"ipv4_addr":"10.0.0.3/24","cost":2,"next_hop_peer_id":99},
                {"peer_id":4,"ipv4_addr":"10.0.0.4/24","cost":1},
                {"peer_id":5,"ipv4_addr":"10.0.0.5/24"},
                {"peer_id":6,"ipv4_addr":"10.0.0.6/24","cost":0},
                {"peer_id":7,"proxy_cidrs":["10.0.0.7/32"],"cost":2},
                {"peer_id":99,"ipv4_addr":"10.0.0.99/24","cost":1,"feature_flag":{"is_public_server":true}}
              ]
            }}}
        """.trimIndent(), "runtime"))
        assertEquals(listOf(
            EasyTierPeerConnection("10.0.0.2", EasyTierPeerConnectionKind.Direct, "udp"),
            EasyTierPeerConnection("10.0.0.3", EasyTierPeerConnectionKind.Relay),
        ), info.peerConnections)
    }

    @Test
    fun parse_refreshesCamelCaseRoutesWithoutKeepingPreviousDirectState() {
        fun parse(cost: Int, conns: String) = requireNotNull(EasyTierRuntimeInfoParser.parse("""
            {"map":{"runtime":{"running":true,
              "peers":[{"peerId":"4294967295","conns":$conns}],
              "routes":[{"peerId":"4294967295","ipv4Addr":"10.0.0.2/24","cost":$cost}]
            }}}
        """.trimIndent(), "runtime"))
        assertEquals(EasyTierPeerConnection("10.0.0.2", EasyTierPeerConnectionKind.Direct, "tcp/udp"),
            parse(1, """[{"tunnel":{"tunnelType":"udp"}},{"tunnel":{"tunnelType":"tcp"}},{"isClosed":true,"tunnel":{"tunnelType":"quic"}}]""").peerConnections.single())
        assertEquals(EasyTierPeerConnectionKind.Relay, parse(2, "[]").peerConnections.single().kind)
        assertEquals(emptyList<EasyTierPeerConnection>(), parse(1, "[]").peerConnections)
    }

    @Test
    fun parse_doesNotUseAnotherRoomsRuntime() {
        assertNull(EasyTierRuntimeInfoParser.parse("""{"map":{"old-room":{"running":true}}}""", "new-room"))
    }

    @Test
    fun parse_readsSnakeCaseProtobufJson() {
        val info = EasyTierRuntimeInfoParser.parse(
            rawJson = """
                {
                  "map": {
                    "sts-android-lan": {
                      "running": true,
                      "my_node_info": {
                        "virtual_ipv4": {
                          "address": { "addr": 168430090 },
                          "network_length": 24
                        }
                      },
                      "routes": [
                        { "proxy_cidrs": ["10.20.30.40/24", "0.0.0.0/0"] },
                        { "proxy_cidrs": ["10.20.30.0/24"] }
                      ],
                      "peers": [{}, {}]
                    }
                  }
                }
            """.trimIndent(),
            instanceName = "sts-android-lan",
        )

        assertNotNull(info)
        requireNotNull(info)
        assertEquals(true, info.running)
        assertEquals("10.10.10.10/24", info.virtualIpv4Cidr)
        assertEquals(
            listOf("10.10.10.0/24", "10.20.30.0/24"),
            info.routeCidrs
        )
        assertEquals(2, info.peerCount)
    }

    @Test
    fun parse_readsCamelCaseAndStringIpv4Fallback() {
        val info = EasyTierRuntimeInfoParser.parse(
            rawJson = """
                {
                  "map": {
                    "runtime": {
                      "running": false,
                      "errorMessage": "dial failed",
                      "myNodeInfo": { "virtualIpv4": "10.0.0.9/24" },
                      "routes": []
                    }
                  }
                }
            """.trimIndent(),
            instanceName = "runtime",
        )

        requireNotNull(info)
        assertEquals(false, info.running)
        assertEquals("dial failed", info.errorMessage)
        assertEquals("10.0.0.0/24", info.routeCidrs.single())
    }
}
