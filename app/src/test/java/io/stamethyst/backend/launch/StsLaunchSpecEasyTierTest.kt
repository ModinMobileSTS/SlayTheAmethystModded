package io.stamethyst.backend.launch

import io.stamethyst.backend.easytier.EasyTierConnectionSnapshot
import io.stamethyst.backend.easytier.EasyTierConnectionStatus
import io.stamethyst.backend.easytier.EasyTierNetworkMode
import io.stamethyst.backend.easytier.EasyTierSessionController
import io.stamethyst.backend.easytier.resolveEasyTierPollSuccessStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StsLaunchSpecEasyTierTest {
    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_returnsPortOnlyWhenSnapshotMissing() {
        assertEquals(
            mapOf(
                "amethyst.runtime_compat.together_in_spire_easytier_autofill" to "true",
                "amethyst.easytier.together_in_spire.port" to "33455",
            ),
            StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
                snapshot = null,
                autofillEnabled = true,
            ),
        )
    }

    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_omitsConnectionDefaultsWhenDisabled() {
        assertEquals(
            mapOf(
                "amethyst.runtime_compat.together_in_spire_easytier_autofill" to "false",
            ),
            StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
                snapshot = connectedSnapshot(),
                autofillEnabled = false,
            ),
        )
    }

    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_includesOwnerIpAndRoomMetadata() {
        val properties = StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
            snapshot = connectedSnapshot(),
            autofillEnabled = true,
        )

        assertEquals(
            "true",
            properties["amethyst.runtime_compat.together_in_spire_easytier_autofill"],
        )
        assertEquals("33455", properties["amethyst.easytier.together_in_spire.port"])
        assertEquals("10.144.0.1", properties["amethyst.easytier.together_in_spire.host_ip"])
        assertEquals("10.144.0.2/24", properties["amethyst.easytier.assigned_ipv4_cidr"])
        assertEquals("player-2", properties["amethyst.easytier.current_player_id"])
        assertEquals("player-1", properties["amethyst.easytier.room_owner_player_id"])
        assertEquals("room-1", properties["amethyst.easytier.room_id"])
    }

    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_withholdsIpUntilVpnIsConnected() {
        val properties = StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
            snapshot = EasyTierConnectionSnapshot(
                enabled = true,
                canConnect = true,
                status = EasyTierConnectionStatus.SESSION_READY,
                mode = EasyTierNetworkMode.Room,
                assignedIpv4Cidr = "10.144.0.2/24",
                roomOwnerIpv4Cidr = "10.144.0.1/24",
            ),
            autofillEnabled = true,
        )

        assertEquals(
            mapOf(
                "amethyst.runtime_compat.together_in_spire_easytier_autofill" to "true",
                "amethyst.easytier.together_in_spire.port" to "33455",
            ),
            properties,
        )
    }

    /**
     * The player-visible consequence of the status downgrade, pinned end to end.
     *
     * A session that recovered from a transient poll failure kept every part of its state except its
     * status, which stayed on `SESSION_READY`. Because these launch properties are gated on
     * `CONNECTED`, relaunching the game in that state silently dropped the host address and the
     * player could not rejoin the room they were still connected to.
     */
    @Test
    fun buildEasyTierTogetherInSpireJvmProperties_survivesATransientPollFailure() {
        val connected = connectedSnapshot()
        val recovered = connected.copy(
            status = resolveEasyTierPollSuccessStatus(
                sessionState = "connected",
                current = EasyTierSessionController.buildFailureSnapshot(
                    previous = connected.copy(connectedAtMs = 2_000L),
                    summary = "timeout",
                ),
            ),
        )

        assertEquals(EasyTierConnectionStatus.CONNECTED, recovered.status)
        assertEquals(
            "10.144.0.1",
            StsLaunchSpec.buildEasyTierTogetherInSpireJvmProperties(
                snapshot = recovered,
                autofillEnabled = true,
            )["amethyst.easytier.together_in_spire.host_ip"],
        )
    }

    @Test
    fun extractEasyTierIpv4Host_stripsPrefixAndRejectsInvalidInput() {
        assertEquals("10.144.0.1", StsLaunchSpec.extractEasyTierIpv4Host("10.144.0.1/24"))
        assertEquals("", StsLaunchSpec.extractEasyTierIpv4Host("not-an-ip"))
        assertEquals("", StsLaunchSpec.extractEasyTierIpv4Host("300.1.1.1/24"))
        assertEquals("", StsLaunchSpec.extractEasyTierIpv4Host(""))
    }

    private fun connectedSnapshot() = EasyTierConnectionSnapshot(
        enabled = true,
        canConnect = true,
        status = EasyTierConnectionStatus.CONNECTED,
        mode = EasyTierNetworkMode.Room,
        roomId = "room-1",
        assignedIpv4Cidr = "10.144.0.2/24",
        currentPlayerId = "player-2",
        roomOwnerPlayerId = "player-1",
        roomOwnerIpv4Cidr = "10.144.0.1/24",
    )
}
