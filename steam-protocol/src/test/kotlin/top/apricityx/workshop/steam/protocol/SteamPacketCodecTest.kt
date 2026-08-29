package top.apricityx.workshop.steam.protocol

import com.google.protobuf.Empty
import org.junit.Assert.assertEquals
import org.junit.Test
import top.apricityx.workshop.steam.proto.CMsgProtoBufHeader

class SteamPacketCodecTest {
    @Test
    fun clientUserStatsUsesSteamCmMessageIds() {
        assertEquals(818, SteamPacketCodec.emsgClientGetUserStats)
        assertEquals(819, SteamPacketCodec.emsgClientGetUserStatsResponse)
        assertEquals(820, SteamPacketCodec.emsgClientStoreUserStats)
        assertEquals(821, SteamPacketCodec.emsgClientStoreUserStatsResponse)
        assertEquals(5466, SteamPacketCodec.emsgClientStoreUserStats2)
    }

    @Test
    fun clientGamesPlayedUsesSteamCmMessageId() {
        assertEquals(5410, SteamPacketCodec.emsgClientGamesPlayedWithDataBlob)

        val packet = SteamPacketCodec.encode(
            emsg = SteamPacketCodec.emsgClientGamesPlayedWithDataBlob,
            header = CMsgProtoBufHeader.getDefaultInstance(),
            body = Empty.getDefaultInstance(),
        )

        assertEquals(5410, SteamPacketCodec.peekBaseMessageId(packet))
    }

    @Test
    fun clientLogonUsesCurrentSteamProtocolVersion() {
        assertEquals(65580, SteamPacketCodec.clientLogonProtocol)
    }

    @Test
    fun packetHeaderPreservesRoutingAppId() {
        val packet = SteamPacketCodec.encode(
            emsg = SteamPacketCodec.emsgClientGetUserStats,
            header = CMsgProtoBufHeader.newBuilder()
                .setRoutingAppid(646570)
                .build(),
            body = Empty.getDefaultInstance(),
        )

        assertEquals(646570, SteamPacketCodec.decode(packet).header.routingAppid)
    }
}
