package top.apricityx.workshop.workshop

import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetManifestRequestCode_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetServersForSteamPipe_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_ServerInfo
import top.apricityx.workshop.steam.proto.ContentManifestMetadata
import top.apricityx.workshop.steam.proto.ContentManifestPayload
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

class SteamDepotSingleFileDownloaderTest {
    @Test
    fun sharedRuntimeDepotManifestReproducesMissingDesktopJarFailure() {
        val root = Files.createTempDirectory("steam-shared-runtime-depot").toFile()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().writeUtf8("{\"response\":{\"serverlist\":[{\"endpoint\":\"cm.example:443\",\"type\":\"websockets\"}]}}"))
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().write(manifestZipForSharedRuntimeDepot()))
                    .build(),
            )

            val httpClient = OkHttpClient.Builder()
                // SteamCdnTransport intentionally uses the endpoint advertised by Steam.
                // Rewrite it only inside this test so the CDN request reaches MockWebServer.
                .addInterceptor { chain ->
                    val request = chain.request()
                    val rewrittenUrl = server.url(request.url.encodedPath)
                    chain.proceed(request.newBuilder().url(rewrittenUrl).build())
                }
                .build()
            val session = SharedRuntimeDepotSession()
            val downloader = SteamDepotSingleFileDownloader(
                client = httpClient,
                directoryClient = SteamDirectoryClient(
                    client = httpClient,
                    apiBaseUrl = server.url("/").newBuilder().build(),
                ),
                sessionFactory = { session },
                sessionConnector = { _, _ -> session.currentSession.value },
            )

            val error = runCatching {
                runBlocking {
                    downloader.download(
                        request = SteamDepotFileDownloadRequest(
                            // These values match the candidate printed by the reported error.
                            appId = 228980u,
                            depotId = 228983u,
                            manifestId = 8124929965194586177uL,
                            fileName = "desktop-1.0.jar",
                            outputFile = File(root, "desktop-1.0.jar"),
                            depotKey = null,
                        ),
                        emitProgress = {},
                    )
                }
            }.exceptionOrNull()

            assertTrue(error is WorkshopDownloadException)
            assertEquals(
                "Steam depot 228983 manifest 8124929965194586177 did not contain desktop-1.0.jar",
                error?.message,
            )
            assertEquals("/ISteamDirectory/GetCMListForConnect/v1/", server.takeRequest().target)
            assertEquals("/depot/228983/manifest/8124929965194586177/5/123456", server.takeRequest().target)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    private fun manifestZipForSharedRuntimeDepot(): ByteArray {
        val payload = ContentManifestPayload.newBuilder()
            .addMappings(
                ContentManifestPayload.FileMapping.newBuilder()
                    .setFilename("vcredist_x86.exe")
                    .setSize(1234L)
                    .build(),
            )
            .build()
        val metadata = ContentManifestMetadata.newBuilder()
            .setDepotId(228983)
            .setGidManifest(8124929965194586177L)
            .setFilenamesEncrypted(false)
            .build()

        val manifest = ByteArrayOutputStream().also { output ->
            val data = DataOutputStream(output)
            writeSection(data, 0x71F617D0u, payload)
            writeSection(data, 0x1F4812BEu, metadata)
            writeUInt32Le(data, 0x32C415ABu)
            data.flush()
        }.toByteArray()

        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.bin"))
                zip.write(manifest)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private fun writeSection(data: DataOutputStream, magic: UInt, message: MessageLite) {
        writeUInt32Le(data, magic)
        writeUInt32Le(data, message.serializedSize.toUInt())
        data.write(message.toByteArray())
    }

    private fun writeUInt32Le(data: DataOutputStream, value: UInt) {
        data.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
    }

    private class SharedRuntimeDepotSession : SteamCmSession {
        override val currentSession = MutableStateFlow(
            SessionContext(
                sessionId = 1,
                steamId = 0L,
                cellId = 0u,
                heartbeatSeconds = 60,
            ),
        )

        override suspend fun connect(servers: List<CmServer>) = Unit

        override suspend fun connectAnonymous(servers: List<CmServer>): SessionContext = currentSession.value

        override suspend fun connectWithRefreshToken(
            servers: List<CmServer>,
            account: SteamAccountSession,
        ): SessionContext = currentSession.value

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : MessageLite> callServiceMethod(
            methodName: String,
            request: MessageLite,
            parser: Parser<T>,
        ): T = when (methodName) {
            "ContentServerDirectory.GetManifestRequestCode#1" -> {
                CContentServerDirectory_GetManifestRequestCode_Response.newBuilder()
                    .setManifestRequestCode(123456L)
                    .build() as T
            }

            "ContentServerDirectory.GetServersForSteamPipe#1" -> {
                CContentServerDirectory_GetServersForSteamPipe_Response.newBuilder()
                    .addServers(
                        CContentServerDirectory_ServerInfo.newBuilder()
                            .setType("CDN")
                            .setHost("cdn.test")
                            .setVhost("cdn.test")
                            .setHttpsSupport("mandatory")
                            .setNumEntriesInClientList(1)
                            .build(),
                    )
                    .build() as T
            }

            else -> error("Unexpected Steam service method: $methodName")
        }

        override suspend fun <T : MessageLite> sendClientMessage(
            emsg: Int,
            request: MessageLite,
            responseEmsg: Int,
            parser: Parser<T>,
        ): T = error("Unexpected Steam client message: $emsg")

        override suspend fun sendClientMessage(emsg: Int, request: MessageLite) =
            error("Unexpected Steam client message: $emsg")

        override suspend fun sendClientMessage(emsg: Int, request: MessageLite, routingAppId: UInt) =
            error("Unexpected Steam client message: $emsg")

        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray =
            error("Depot key should not be requested by this downloader test")

        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo =
            error("App info should not be requested by this downloader test")

        override fun close() = Unit
    }
}
