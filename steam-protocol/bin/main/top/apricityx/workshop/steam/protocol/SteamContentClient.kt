package top.apricityx.workshop.steam.protocol

import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetCDNAuthToken_Request
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetCDNAuthToken_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetManifestRequestCode_Request
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetManifestRequestCode_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetServersForSteamPipe_Request
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetServersForSteamPipe_Response
import java.time.Instant

class SteamContentClient(
    private val session: SteamCmSession,
    private val directoryClient: SteamDirectoryClient,
) {
    suspend fun getServersForSteamPipe(
        cellId: UInt = session.currentSession.value?.cellId ?: 0u,
        maxServers: UInt = 20u,
    ): List<CdnServer> {
        return runCatching {
            val response = session.callServiceMethod(
                methodName = "ContentServerDirectory.GetServersForSteamPipe#1",
                request = CContentServerDirectory_GetServersForSteamPipe_Request.newBuilder()
                    .setCellId(cellId.toInt())
                    .setMaxServers(maxServers.toInt())
                    .build(),
                parser = CContentServerDirectory_GetServersForSteamPipe_Response.parser(),
            )
            response.serversList.map {
                CdnServer(
                    type = it.type,
                    sourceId = it.sourceId,
                    cellId = it.cellId,
                    load = it.load,
                    weightedLoad = it.weightedLoad,
                    numEntriesInClientList = it.numEntriesInClientList,
                    steamChinaOnly = it.steamChinaOnly,
                    host = it.host,
                    vHost = it.vhost,
                    useAsProxy = it.useAsProxy,
                    proxyRequestPathTemplate = it.proxyRequestPathTemplate.takeIf(String::isNotBlank),
                    httpsSupport = it.httpsSupport,
                    allowedAppIds = it.allowedAppIdsList.map(Int::toUInt),
                    priorityClass = it.priorityClass.toUInt(),
                )
            }
        }.getOrElse {
            directoryClient.loadContentServers(cellId, maxServers)
        }
    }

    suspend fun getManifestRequestCode(
        appId: UInt,
        depotId: UInt,
        manifestId: ULong,
        branch: String = "public",
        branchPasswordHash: String? = null,
    ): ULong {
        val request = CContentServerDirectory_GetManifestRequestCode_Request.newBuilder()
            .setAppId(appId.toInt())
            .setDepotId(depotId.toInt())
            .setManifestId(manifestId.toLong())
            .apply {
                if (branch != "public") {
                    setAppBranch(branch)
                    branchPasswordHash?.let(::setBranchPasswordHash)
                }
            }
            .build()

        val response = session.callServiceMethod(
            methodName = "ContentServerDirectory.GetManifestRequestCode#1",
            request = request,
            parser = CContentServerDirectory_GetManifestRequestCode_Response.parser(),
        )
        return response.manifestRequestCode.toULong()
    }

    suspend fun getCdnAuthToken(
        appId: UInt,
        depotId: UInt,
        hostName: String,
    ): CdnAuthToken {
        val response = session.callServiceMethod(
            methodName = "ContentServerDirectory.GetCDNAuthToken#1",
            request = CContentServerDirectory_GetCDNAuthToken_Request.newBuilder()
                .setAppId(appId.toInt())
                .setDepotId(depotId.toInt())
                .setHostName(hostName)
                .build(),
            parser = CContentServerDirectory_GetCDNAuthToken_Response.parser(),
        )
        return CdnAuthToken(
            token = response.token.trim().removePrefix("?"),
            expiration = Instant.ofEpochSecond(response.expirationTime.toLong()),
        )
    }
}
