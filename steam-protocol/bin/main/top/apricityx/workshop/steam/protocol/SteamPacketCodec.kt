package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CMsgMulti
import top.apricityx.workshop.steam.proto.CMsgProtoBufHeader
import com.google.protobuf.MessageLite
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

object SteamPacketCodec {
    const val protoMask: Int = Int.MIN_VALUE
    const val emsgMulti: Int = 1
    const val emsgServiceMethod: Int = 146
    const val emsgServiceMethodResponse: Int = 147
    const val emsgServiceMethodCallFromClient: Int = 151
    const val emsgClientHeartBeat: Int = 703
    const val emsgClientLogOnResponse: Int = 751
    const val emsgClientLoggedOff: Int = 757
    const val emsgClientSessionToken: Int = 850
    const val emsgClientGetDepotDecryptionKey: Int = 5438
    const val emsgClientGetDepotDecryptionKeyResponse: Int = 5439
    const val emsgClientServerUnavailable: Int = 5500
    const val emsgServiceMethodCallFromClientNonAuthed: Int = 9804
    const val emsgClientHello: Int = 9805
    const val emsgClientLogon: Int = 5514
    const val emsgClientPICSProductInfoRequest: Int = 8903
    const val emsgClientPICSProductInfoResponse: Int = 8904
    const val clientLogonProtocol: Int = 65581

    fun makeMessageId(emsg: Int, proto: Boolean = true): Int = if (proto) emsg or protoMask else emsg

    fun getBaseMessageId(raw: Int): Int = raw and Int.MAX_VALUE

    fun isProto(raw: Int): Boolean = raw and protoMask != 0

    fun peekBaseMessageId(rawPacket: ByteArray): Int {
        require(rawPacket.size >= 4) { "Steam packet too short: ${rawPacket.size} bytes" }
        val rawMessageId = ByteBuffer.wrap(rawPacket, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return getBaseMessageId(rawMessageId)
    }

    fun isProtoPacket(rawPacket: ByteArray): Boolean {
        require(rawPacket.size >= 4) { "Steam packet too short: ${rawPacket.size} bytes" }
        val rawMessageId = ByteBuffer.wrap(rawPacket, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return isProto(rawMessageId)
    }

    fun buildWebSocketUri(endpoint: String): String = "wss://$endpoint/cmsocket/"

    fun encode(
        emsg: Int,
        header: CMsgProtoBufHeader,
        body: MessageLite,
    ): ByteArray {
        val headerBytes = header.toByteArray()
        val bodyBytes = body.toByteArray()
        return ByteBuffer
            .allocate(8 + headerBytes.size + bodyBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(makeMessageId(emsg))
            .putInt(headerBytes.size)
            .put(headerBytes)
            .put(bodyBytes)
            .array()
    }

    fun decode(rawPacket: ByteArray): SteamPacket {
        require(rawPacket.size >= 8) { "Steam packet too short: ${rawPacket.size} bytes" }

        val buffer = ByteBuffer.wrap(rawPacket).order(ByteOrder.LITTLE_ENDIAN)
        val rawMessageId = buffer.int
        check(isProto(rawMessageId)) { "Non-protobuf Steam packet is not supported in this demo" }
        val headerLength = buffer.int
        require(headerLength >= 0 && rawPacket.size >= 8 + headerLength) {
            "Invalid Steam packet header length: $headerLength"
        }

        val headerBytes = ByteArray(headerLength)
        buffer.get(headerBytes)
        val bodyBytes = ByteArray(buffer.remaining())
        buffer.get(bodyBytes)

        return SteamPacket(
            emsg = getBaseMessageId(rawMessageId),
            header = CMsgProtoBufHeader.parseFrom(headerBytes),
            body = bodyBytes,
        )
    }

    fun decodeLegacyPacket(rawPacket: ByteArray): LegacySteamPacket {
        require(rawPacket.size >= LEGACY_EXTENDED_HEADER_SIZE) {
            "Legacy Steam packet too short: ${rawPacket.size} bytes"
        }
        val buffer = ByteBuffer.wrap(rawPacket).order(ByteOrder.LITTLE_ENDIAN)
        val rawMessageId = buffer.int
        check(!isProto(rawMessageId)) { "Legacy Steam packet decoder only accepts non-protobuf packets" }

        val headerSize = buffer.get().toInt() and 0xFF
        val headerVersion = buffer.short.toInt() and 0xFFFF
        require(headerSize >= LEGACY_EXTENDED_HEADER_SIZE && rawPacket.size >= headerSize) {
            "Invalid legacy Steam packet header size: $headerSize"
        }
        require(headerVersion == LEGACY_EXTENDED_HEADER_VERSION) {
            "Unsupported legacy Steam packet header version: $headerVersion"
        }

        val targetJobId = buffer.long
        val sourceJobId = buffer.long
        val headerCanary = buffer.get().toInt() and 0xFF
        require(headerCanary == LEGACY_EXTENDED_HEADER_CANARY) {
            "Invalid legacy Steam packet canary: $headerCanary"
        }
        val steamId = buffer.long
        val sessionId = buffer.int
        val bodyBytes = rawPacket.copyOfRange(headerSize, rawPacket.size)

        return LegacySteamPacket(
            emsg = getBaseMessageId(rawMessageId),
            header = LegacySteamHeader(
                headerSize = headerSize,
                headerVersion = headerVersion,
                targetJobId = targetJobId,
                sourceJobId = sourceJobId,
                steamId = steamId,
                sessionId = sessionId,
            ),
            body = bodyBytes,
        )
    }

    fun decodeLegacyLoggedOffBody(packet: LegacySteamPacket): LegacyLoggedOffBody {
        require(packet.emsg == emsgClientLoggedOff) {
            "Legacy logged-off decoder only accepts EMsg.ClientLoggedOff"
        }
        require(packet.body.size >= 12) { "Legacy logged-off packet too short: ${packet.body.size} bytes" }
        val buffer = ByteBuffer.wrap(packet.body).order(ByteOrder.LITTLE_ENDIAN)
        return LegacyLoggedOffBody(
            resultCode = buffer.int,
            minReconnectHintSeconds = buffer.int,
            maxReconnectHintSeconds = buffer.int,
        )
    }

    fun decodeLegacyServerUnavailableBody(packet: LegacySteamPacket): LegacyServerUnavailableBody {
        require(packet.emsg == emsgClientServerUnavailable) {
            "Legacy server-unavailable decoder only accepts EMsg.ClientServerUnavailable"
        }
        require(packet.body.size >= 16) {
            "Legacy server-unavailable packet too short: ${packet.body.size} bytes"
        }
        val buffer = ByteBuffer.wrap(packet.body).order(ByteOrder.LITTLE_ENDIAN)
        return LegacyServerUnavailableBody(
            jobIdSent = buffer.long,
            emsgSent = buffer.int,
            serverTypeUnavailable = buffer.int,
        )
    }

    fun expandMulti(packet: SteamPacket): List<ByteArray> {
        require(packet.emsg == emsgMulti) { "Steam multi decoder only accepts EMsg.Multi" }
        val multi = CMsgMulti.parseFrom(packet.body)
        val stream = if (multi.sizeUnzipped > 0) {
            GZIPInputStream(ByteArrayInputStream(multi.messageBody.toByteArray()))
        } else {
            ByteArrayInputStream(multi.messageBody.toByteArray())
        }
        return readMultiPackets(stream)
    }

    internal fun readMultiPackets(stream: InputStream): List<ByteArray> {
        return buildList {
            while (true) {
                val header = ByteArray(4)
                val bytesRead = stream.readFullyCompat(header)
                if (bytesRead == 0) {
                    break
                }
                if (bytesRead != 4) {
                    throw EOFException("Unexpected EOF while reading multi-packet chunk length")
                }
                val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                require(length > 0) { "Invalid sub-packet length: $length" }
                add(stream.readExactlyCompat(length))
            }
        }
    }

    data class LegacySteamPacket(
        val emsg: Int,
        val header: LegacySteamHeader,
        val body: ByteArray,
    )

    data class LegacySteamHeader(
        val headerSize: Int,
        val headerVersion: Int,
        val targetJobId: Long,
        val sourceJobId: Long,
        val steamId: Long,
        val sessionId: Int,
    )

    data class LegacyLoggedOffBody(
        val resultCode: Int,
        val minReconnectHintSeconds: Int,
        val maxReconnectHintSeconds: Int,
    )

    data class LegacyServerUnavailableBody(
        val jobIdSent: Long,
        val emsgSent: Int,
        val serverTypeUnavailable: Int,
    )

    private const val LEGACY_EXTENDED_HEADER_SIZE = 36
    private const val LEGACY_EXTENDED_HEADER_VERSION = 2
    private const val LEGACY_EXTENDED_HEADER_CANARY = 239
}
