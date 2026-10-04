package top.apricityx.workshop.workshop

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.google.protobuf.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetManifestRequestCode_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_GetServersForSteamPipe_Response
import top.apricityx.workshop.steam.proto.CContentServerDirectory_ServerInfo
import top.apricityx.workshop.steam.proto.ContentManifestMetadata
import top.apricityx.workshop.steam.proto.ContentManifestPayload
import top.apricityx.workshop.steam.protocol.CdnServer
import top.apricityx.workshop.steam.protocol.CmServer
import top.apricityx.workshop.steam.protocol.SessionContext
import top.apricityx.workshop.steam.protocol.SteamAccountSession
import top.apricityx.workshop.steam.protocol.SteamAppProductInfo
import top.apricityx.workshop.steam.protocol.SteamCmSession
import top.apricityx.workshop.steam.protocol.SteamDirectoryClient

class SteamDepotDownloadPerformanceTest {
    private val data = "verified desktop jar fixture".encodeToByteArray()
    private val suppliedCmServers = listOf(CmServer("cm.test:443", "websockets"))

    @Test(timeout = 10_000)
    fun reusesCmDirectoryAndResolvesMetadataConcurrently() = runBlocking {
        withFixture { root, server ->
            serveFile(server)
            val session = TestSession(listOf(testCdnServer("cdn.test")), requireParallelMetadata = true)
            val client = routedClient(mapOf("cdn.test" to server))
            withTimeout(2_000) {
                download(root, client, session)
            }
            assertArrayEquals(data, File(root, "desktop-1.0.jar").readBytes())
            assertEquals(2, server.requestCount) // manifest + chunk, no second CM directory request
            assertEquals(1, session.serverRpcs.get())
        }
    }

    @Test(timeout = 10_000)
    fun fasterManifestWinsAndCancelsTheSlowEdge() = runBlocking {
        withFixture { root, slow ->
            MockWebServer().use { fast ->
                fast.start()
                slow.enqueue(MockResponse.Builder().body(Buffer().write(manifestZip())).headersDelay(2, TimeUnit.SECONDS).build())
                val slowFailed = CountDownLatch(1)
                fast.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.url.encodedPath.contains("/manifest/")) {
                            check(slow.takeRequest(1, TimeUnit.SECONDS) != null)
                            return MockResponse.Builder().body(Buffer().write(manifestZip())).build()
                        }
                        return MockResponse.Builder().body(Buffer().write(data)).build()
                    }
                }
                val session = TestSession(listOf(testCdnServer("slow.test"), testCdnServer("fast.test")))
                val listener = object : EventListener() {
                    override fun callFailed(call: Call, ioe: IOException) {
                        if (call.request().url.host == "slow.test") slowFailed.countDown()
                    }
                }
                val client = routedClient(mapOf("slow.test" to slow, "fast.test" to fast), listener)
                val started = System.nanoTime()
                withTimeout(1_500) { download(root, client, session) }
                assertTrue("Should not wait for the slow manifest's headers", (System.nanoTime() - started) / 1_000_000 < 1_500)
                assertTrue("Losing manifest socket must be cancelled", slowFailed.await(1, TimeUnit.SECONDS))
                assertArrayEquals(data, File(root, "desktop-1.0.jar").readBytes())
            }
        }
    }

    @Test(timeout = 10_000)
    fun malformedOrWrongManifestCannotWinTheRace() = runBlocking {
        withFixture { root, wrong ->
            MockWebServer().use { valid ->
                valid.start()
                wrong.enqueue(MockResponse.Builder().body(Buffer().write(manifestZip(manifestId = 999L))).build())
                valid.enqueue(MockResponse.Builder().body(Buffer().write(manifestZip())).headersDelay(100, TimeUnit.MILLISECONDS).build())
                valid.enqueue(MockResponse.Builder().body(Buffer().write(data)).build())
                val session = TestSession(listOf(testCdnServer("wrong.test"), testCdnServer("valid.test")))
                download(root, routedClient(mapOf("wrong.test" to wrong, "valid.test" to valid)), session)
                assertArrayEquals(data, File(root, "desktop-1.0.jar").readBytes())
                assertEquals(1, wrong.requestCount) // failed edge stays in cooldown during chunks
            }
        }
    }

    @Test(timeout = 10_000)
    fun allManifestFailuresFinishAndWeightedHostsAreNotRetried() = runBlocking {
        withFixture { root, server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            val weighted = testCdnServer("cdn.test").copy(numEntriesInClientList = 8)
            val error = runCatching {
                withTimeout(2_000) { download(root, routedClient(mapOf("cdn.test" to server)), TestSession(listOf(weighted))) }
            }.exceptionOrNull()
            assertTrue(error is WorkshopDownloadException)
            assertEquals("Unable to download Steam depot manifest", error!!.message)
            assertEquals(1, server.requestCount)
            assertFalse(File(root, "desktop-1.0.jar").exists())
        }
    }

    @Test(timeout = 10_000)
    fun workerCancellationDoesNotLeaveTheManifestWinnerWaitingForever() = runBlocking {
        withFixture { root, server ->
            val checks = AtomicInteger()
            val client = routedClient(mapOf("cdn.test" to server))
            val error = runCatching {
                withTimeout(2_000) {
                    download(root, client, TestSession(listOf(testCdnServer("cdn.test"))), waitIfPaused = {
                        // The first five checks surround metadata setup; the sixth is in the race worker.
                        if (checks.incrementAndGet() >= 6) throw CancellationException("cancelled by importer")
                    })
                }
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals("cancelled by importer", error!!.message)
            assertEquals(0, server.requestCount)
        }
    }

    @Test(timeout = 10_000)
    fun externalCancellationAbortsManifestSockets() = runBlocking {
        withFixture { root, server ->
            server.enqueue(MockResponse.Builder().body(Buffer().write(manifestZip())).headersDelay(2, TimeUnit.SECONDS).build())
            val failed = CountDownLatch(1)
            val client = routedClient(mapOf("cdn.test" to server), object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
            })
            val task = async(Dispatchers.IO) { download(root, client, TestSession(listOf(testCdnServer("cdn.test")))) }
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)
            withTimeout(1_000) { task.cancelAndJoin() }
            assertTrue(failed.await(1, TimeUnit.SECONDS))
            assertFalse(File(root, "desktop-1.0.jar").exists())
        }
    }

    private suspend fun download(
        root: File,
        client: OkHttpClient,
        session: TestSession,
        waitIfPaused: suspend () -> Unit = {},
    ): File = SteamDepotSingleFileDownloader(
        client = client,
        directoryClient = SteamDirectoryClient(client),
        sessionFactory = { session },
        sessionConnector = { _, servers ->
            assertEquals(suppliedCmServers, servers)
            session.currentSession.value
        },
    ).download(
        request = SteamDepotFileDownloadRequest(646570u, 646571u, 123uL, fileName = "desktop-1.0.jar", outputFile = File(root, "desktop-1.0.jar"), depotKey = null),
        emitProgress = {},
        waitIfPaused = waitIfPaused,
        cmServers = suppliedCmServers,
    )

    private fun routedClient(servers: Map<String, MockWebServer>, listener: EventListener = EventListener.NONE): OkHttpClient =
        OkHttpClient.Builder().eventListener(listener).addInterceptor { chain ->
            val request = chain.request()
            val server = servers[request.url.host] ?: error("Unexpected directory request: ${request.url}")
            val url = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(url).build())
        }.build()

    private fun serveFile(server: MockWebServer) {
        server.enqueue(MockResponse.Builder().body(Buffer().write(manifestZip())).build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(data)).build())
    }

    private suspend fun withFixture(block: suspend (File, MockWebServer) -> Unit) {
        val repository = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val temporaryRoot = File(repository, "agent-tmp").apply { mkdirs() }
        val root = Files.createTempDirectory(temporaryRoot.toPath(), "steam-download-test-").toFile()
        try {
            MockWebServer().use { server -> server.start(); block(root, server) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifestZip(manifestId: Long = 123L): ByteArray {
        val chunk = ContentManifestPayload.FileMapping.ChunkData.newBuilder()
            .setSha(ByteString.copyFrom(ByteArray(20) { 1 }))
            .setCrc(steamAdler32(data).toInt()).setOffset(0L)
            .setCbCompressed(data.size).setCbOriginal(data.size)
        val payload = ContentManifestPayload.newBuilder().addMappings(
            ContentManifestPayload.FileMapping.newBuilder().setFilename("desktop-1.0.jar")
                .setSize(data.size.toLong()).setShaContent(ByteString.copyFrom(MessageDigest.getInstance("SHA-1").digest(data)))
                .addChunks(chunk),
        ).build()
        val metadata = ContentManifestMetadata.newBuilder().setDepotId(646571).setGidManifest(manifestId).build()
        val bytes = ByteArrayOutputStream()
        fun uint(value: UInt) { bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()) }
        fun section(magic: UInt, message: MessageLite) { uint(magic); uint(message.serializedSize.toUInt()); bytes.write(message.toByteArray()) }
        section(0x71F617D0u, payload)
        section(0x1F4812BEu, metadata)
        uint(0x32C415ABu)
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("manifest.bin")); zip.write(bytes.toByteArray()); zip.closeEntry() }
        }.toByteArray()
    }

    private class TestSession(private val servers: List<CdnServer>, private val requireParallelMetadata: Boolean = false) : SteamCmSession {
        override val currentSession = MutableStateFlow(SessionContext(1, 0L, 42u, 60))
        val serverRpcs = AtomicInteger()
        private val codeEntered = CompletableDeferred<Unit>()
        private val serversEntered = CompletableDeferred<Unit>()
        override suspend fun connect(servers: List<CmServer>) = Unit
        override suspend fun connectAnonymous(servers: List<CmServer>) = currentSession.value
        override suspend fun connectWithRefreshToken(servers: List<CmServer>, account: SteamAccountSession) = currentSession.value
        override fun close() = Unit
        override suspend fun requestDepotDecryptionKey(appId: UInt, depotId: UInt): ByteArray = error("Unexpected key request")
        override suspend fun requestAppProductInfo(appId: UInt): SteamAppProductInfo = error("Unexpected appinfo request")
        override suspend fun sendClientMessage(emsg: Int, request: MessageLite) = Unit
        override suspend fun sendClientMessage(emsg: Int, request: MessageLite, routingAppId: UInt) = Unit
        override suspend fun <T : MessageLite> sendClientMessage(emsg: Int, request: MessageLite, responseEmsg: Int, parser: Parser<T>): T = error("Unexpected message")

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : MessageLite> callServiceMethod(methodName: String, request: MessageLite, parser: Parser<T>): T = when (methodName) {
            "ContentServerDirectory.GetManifestRequestCode#1" -> {
                codeEntered.complete(Unit)
                if (requireParallelMetadata) serversEntered.await()
                CContentServerDirectory_GetManifestRequestCode_Response.newBuilder().setManifestRequestCode(456L).build() as T
            }
            "ContentServerDirectory.GetServersForSteamPipe#1" -> {
                serverRpcs.incrementAndGet()
                serversEntered.complete(Unit)
                if (requireParallelMetadata) codeEntered.await()
                CContentServerDirectory_GetServersForSteamPipe_Response.newBuilder().addAllServers(servers.map {
                    CContentServerDirectory_ServerInfo.newBuilder().setType(it.type).setHost(it.host).setVhost(it.vHost)
                        .setHttpsSupport(it.httpsSupport).setNumEntriesInClientList(it.numEntriesInClientList).build()
                }).build() as T
            }
            else -> error("Unexpected service: $methodName")
        }
    }
}
