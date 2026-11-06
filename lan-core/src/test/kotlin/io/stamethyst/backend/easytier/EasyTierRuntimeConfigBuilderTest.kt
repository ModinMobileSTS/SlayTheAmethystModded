package io.stamethyst.backend.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTierRuntimeConfigBuilderTest {
    @Test
    fun build_advertisesEphemeralUdpListenerWithoutChangingBootstrapPeer() {
        for (mode in EasyTierNetworkMode.values()) {
            for (cidr in listOf("", "10.126.42.17/24")) {
                val session = EasyTierRoomSessionConfig(
                    sessionId = "lan_udp_test",
                    roomId = "UDP Test",
                    mode = mode,
                    entryNodeUrl = "tcp://online.example.com:55550",
                    configServerUrl = "udp://config.example.com:22020",
                    networkSecret = "test-secret",
                    assignedIpv4Cidr = cidr,
                )

                val config = EasyTierRuntimeConfigBuilder.build(session, "player-test")

                assertEquals(
                    listOf("listeners = [\"udp://0.0.0.0:0\"]"),
                    config.toml.lineSequence().filter { it.startsWith("listeners =") }.toList(),
                )
                assertTrue(config.toml.indexOf("listeners =") < config.toml.indexOf("[network_identity]"))
                assertEquals(listOf(session.entryNodeUrl), config.peerUrls)
                assertTrue(config.toml.contains("uri = \"${session.entryNodeUrl}\""))
                assertFalse(config.toml.contains(session.configServerUrl))
                assertTrue(config.toml.contains("network_secret = \"test-secret\""))
                assertTrue(config.toml.contains("network_name = \"${config.networkName}\""))
                assertTrue(config.toml.contains("dhcp = ${cidr.isEmpty()}"))
                if (cidr.isNotEmpty()) assertTrue(config.toml.contains("ipv4 = \"$cidr\""))
                assertFalse(config.toml.contains("disable_p2p"))
                assertFalse(config.toml.contains("disable_udp_hole_punching"))
            }
        }
    }
}
