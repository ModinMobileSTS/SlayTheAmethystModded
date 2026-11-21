package top.apricityx.workshop.workshop

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectTransferCountersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun singleJarReportsPartialWritesBeforeFileCompletion() = runBlocking {
        val body = ByteArray(131072)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).protocol(Protocol.HTTP_1_1)
                .message("OK").body(body.toResponseBody()).build()
        }.build()
        val progress = mutableListOf<DownloadEvent.Progress>()
        DirectWorkshopDownloader(client).download(
            WorkshopDownloadRequest(646570u, 1uL, tmp.newFolder()),
            ResolvedWorkshopItem.DirectUrlItem("mod.jar", "https://example.invalid/mod", body.size.toLong(), "Mod", "{}"),
            { if (it is DownloadEvent.Progress) progress += it }, {},
        )
        assertTrue(progress.any { it.completedFiles == 0 && it.writeProgressBytes in 1L until body.size.toLong() })
        assertTrue(progress.all { it.writeTotalBytes == body.size.toLong() })
        assertTrue(progress.zipWithNext().all { (a, b) -> a.writeProgressBytes <= b.writeProgressBytes })
        assertEquals(body.size.toLong(), progress.last().writeProgressBytes)
    }

    @Test fun resumeCountsOnlyNewPayloadAndCompletedCacheCountsNoTraffic() = runBlocking {
        val dir = tmp.newFolder()
        dir.resolve("example.jar.part").writeBytes(ByteArray(80))
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("bytes=80-", chain.request().header("Range"))
            Response.Builder().request(chain.request()).code(206).protocol(Protocol.HTTP_1_1)
                .message("Partial Content").body(ByteArray(20).toResponseBody()).build()
        }.build()
        val request = WorkshopDownloadRequest(646570u, 1uL, dir)
        val item = ResolvedWorkshopItem.DirectUrlItem("example.jar", "https://example.invalid/content", 100, "Example", "{}")
        val progress = mutableListOf<DownloadEvent.Progress>()
        val downloader = DirectWorkshopDownloader(client)
        suspend fun collect() = downloader.download(request, item, { if (it is DownloadEvent.Progress) progress += it }, {})
        collect()
        assertEquals(100L, progress.last().writtenBytes)
        assertEquals(20L, progress.last().receivedBytes)
        assertEquals(20L, progress.last().diskWriteBytes)
        assertEquals(80L, progress.first().writeProgressBytes)
        assertEquals(100L, progress.last().writeProgressBytes)
        progress.clear()
        collect()
        assertEquals(100L, progress.single().writtenBytes)
        assertEquals(0L, progress.single().receivedBytes)
        assertEquals(0L, progress.single().diskWriteBytes)
        assertEquals(1, calls)
    }
}
