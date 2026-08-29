package io.stamethyst.backend.steamcloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import top.apricityx.workshop.steam.protocol.OkHttpSteamCmSession
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamPacketCodec
import top.apricityx.workshop.steam.protocol.SteamWebSocketFactory
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesCloudSteamclient
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverFriends
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver2

/** Synchronous app facade over the proxy-capable protocol session. */
internal class SteamCloudProtocolClient(
    private val httpClient: OkHttpClient,
    webSocketFactory: SteamWebSocketFactory,
    externalSession: SteamCmSession? = null,
) : AutoCloseable {
    private val ownsSession = externalSession == null
    private val session: SteamCmSession =
        externalSession ?: OkHttpSteamCmSession(httpClient, webSocketFactory = webSocketFactory)
    private val directory = SteamDirectoryClient(httpClient)

    fun logOn(accountName: String, refreshToken: String, steamId64: String): Long = runBlocking {
        val servers = directory.loadServers()
        session.connectWithRefreshToken(
            servers,
            SteamAccountSession(
                accountName = accountName,
                steamId = steamId64.toLongOrNull() ?: 0L,
                refreshToken = refreshToken,
            ),
        ).steamId
    }

    fun getUserStats(appId: Long, steamId64: Long, timeoutMs: Long): SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse =
        runBlocking {
            val request = SteammessagesClientserverUserstats.CMsgClientGetUserStats.newBuilder()
                .setGameId(appId)
                .setSteamIdForUser(steamId64)
                .setSchemaLocalVersion(0)
                .setCrcStats(0)
                .build()
            withTimeout(timeoutMs) {
                session.sendClientMessage(
                    SteamPacketCodec.emsgClientGetUserStats,
                    request,
                    SteamPacketCodec.emsgClientGetUserStatsResponse,
                    SteammessagesClientserverUserstats.CMsgClientGetUserStatsResponse.parser(),
                    appId.toUInt(),
                )
            }
        }

    fun storeUserStat(
        appId: Long,
        steamId64: Long,
        crcStats: Int,
        statId: Int,
        statValue: Int,
        timeoutMs: Long,
    ): SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse = runBlocking {
        val stat = SteammessagesClientserverUserstats.CMsgClientStoreUserStats2.Stats
            .newBuilder()
            .setStatId(statId)
            .setStatValue(statValue)
            .build()
        val request = SteammessagesClientserverUserstats.CMsgClientStoreUserStats2.newBuilder()
            .setGameId(appId)
            .setSettorSteamId(steamId64)
            .setSetteeSteamId(steamId64)
            .setCrcStats(crcStats)
            .setExplicitReset(false)
            .addStats(stat)
            .build()
        withTimeout(timeoutMs) {
            session.sendClientMessage(
                SteamPacketCodec.emsgClientStoreUserStats2,
                request,
                SteamPacketCodec.emsgClientStoreUserStatsResponse,
                SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse.parser(),
                appId.toUInt(),
            )
        }
    }

    fun sendGamesPlayed(appId: Long) = runBlocking {
        val builder = SteammessagesClientserver.CMsgClientGamesPlayed.newBuilder()
        if (appId > 0) {
            builder.addGamesPlayed(
                SteammessagesClientserver.CMsgClientGamesPlayed.GamePlayed
                    .newBuilder().setGameId(appId).build(),
            )
        }
        session.sendClientMessage(SteamPacketCodec.emsgClientGamesPlayedWithDataBlob, builder.build())
    }

    fun sendPersonaOnline() = runBlocking {
        val request = SteammessagesClientserverFriends.CMsgClientChangeStatus.newBuilder()
            .setPersonaState(1)
            .setPersonaSetByUser(true)
            .build()
        session.sendClientMessage(SteamPacketCodec.emsgClientChangeStatus, request)
    }

    fun isSessionActive(): Boolean = session.currentSession.value != null

    /**
     * Uploads rich presence key-value pairs via CMsgClientRichPresenceUpload (EMsg 7501).
     * The proto field rich_presence_kv expects a binary VDF blob: a root container node
     * (type=0x00, name="\x00") wrapping child string nodes (type=0x01) of the form:
     *   [0x01][key\0][value\0]
     * terminated with [0x08] (end-of-object). Fire-and-forget; mirrors sendPersonaOnline().
     */
    fun sendRichPresence(kvPairs: Map<String, String>) = runBlocking {
        val vdf = encodeVdfKv(kvPairs)
        val request = SteammessagesClientserver2.CMsgClientRichPresenceUpload.newBuilder()
            .setRichPresenceKv(com.google.protobuf.ByteString.copyFrom(vdf))
            .build()
        session.sendClientMessage(SteamPacketCodec.emsgClientRichPresenceUpload, request, 646570U)
    }

    /** Encodes a flat string→string map as a binary VDF blob expected by rich_presence_kv.
     * The root node must be keyed "RP"; the Steam CM parses it as kvObj.RP on the receiving end.
     */
    private fun encodeVdfKv(kvPairs: Map<String, String>): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        // Root node: type=0x00 (sub-object), name="RP" (null-terminated)
        buf.write(0x00)
        buf.write("RP".toByteArray(Charsets.UTF_8))
        buf.write(0x00) // null-terminate root key
        for ((key, value) in kvPairs) {
            buf.write(0x01) // type: string
            buf.write(key.toByteArray(Charsets.UTF_8))
            buf.write(0x00) // null-terminate key
            buf.write(value.toByteArray(Charsets.UTF_8))
            buf.write(0x00) // null-terminate value
        }
        buf.write(0x08) // end of sub-object
        buf.write(0x08) // end of root
        return buf.toByteArray()
    }

    fun getAppFileChangelist(appId: Int): SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Response = service(
        "Cloud.GetAppFileChangelist#1",
        SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Request.newBuilder()
            .setAppid(appId)
            .setSyncedChangeNumber(0L)
            .build(),
        SteammessagesCloudSteamclient.CCloud_GetAppFileChangelist_Response.parser(),
    )

    fun clientFileDownload(appId: Int, filename: String): SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Response = service(
        "Cloud.ClientFileDownload#1",
        SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Request.newBuilder()
            .setAppid(appId).setFilename(filename).setRealm(1).setForceProxy(false).build(),
        SteammessagesCloudSteamclient.CCloud_ClientFileDownload_Response.parser(),
    )

    fun clientDeleteFile(request: SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Request) = service(
        "Cloud.ClientDeleteFile#1", request,
        SteammessagesCloudSteamclient.CCloud_ClientDeleteFile_Response.parser(),
    )

    fun beginAppUploadBatch(request: SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Request) = service(
        "Cloud.BeginAppUploadBatch#1", request,
        SteammessagesCloudSteamclient.CCloud_BeginAppUploadBatch_Response.parser(),
    )

    fun beginHttpUpload(request: SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Request) = service(
        "Cloud.BeginHTTPUpload#1", request,
        SteammessagesCloudSteamclient.CCloud_BeginHTTPUpload_Response.parser(),
    )

    fun commitHttpUpload(request: SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Request) = service(
        "Cloud.CommitHTTPUpload#1", request,
        SteammessagesCloudSteamclient.CCloud_CommitHTTPUpload_Response.parser(),
    )

    fun completeAppUploadBatch(request: SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Request) = service(
        "Cloud.CompleteAppUploadBatchBlocking#1", request,
        SteammessagesCloudSteamclient.CCloud_CompleteAppUploadBatch_Response.parser(),
    )

    private fun <T : com.google.protobuf.MessageLite> service(
        method: String,
        request: com.google.protobuf.MessageLite,
        parser: com.google.protobuf.Parser<T>,
    ): T = runBlocking { session.callServiceMethod(method, request, parser) }

    override fun close() {
        if (ownsSession) {
            session.close()
        }
    }
}
