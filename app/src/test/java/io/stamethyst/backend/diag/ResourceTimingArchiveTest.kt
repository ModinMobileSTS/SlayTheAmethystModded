package io.stamethyst.backend.diag

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResourceTimingArchiveTest {
    private fun directory(): File {
        val parent = File("../agent-tmp/resource-timings-tests").apply { mkdirs() }
        return Files.createTempDirectory(parent.toPath(), "archive-").toFile()
    }

    private fun export(directory: File, prefix: String = "sts/resource_timings"): Pair<Int, Map<String, String>> {
        val bytes = ByteArrayOutputStream()
        var count = 0
        ZipOutputStream(bytes).use { count = ResourceTimingArchive.write(it, directory, prefix) }
        val entries = LinkedHashMap<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return count to entries
    }

    @Test fun closedSessionExportsCurrentPreviousAndStatusWithOnlyCompleteJsonLines() {
        val directory = directory()
        File(directory, "events.jsonl").writeText("{\"id\":1}\n{\"id\":")
        File(directory, "summary.json").writeText("{\"closed_cleanly\":true}")
        File(directory, "previous-events.jsonl").writeText("{\"id\":0}\n")
        File(directory, "previous-summary.json").writeText("{}")
        File(directory, "unrelated.txt").writeText("ignore")
        val (count, entries) = export(directory)
        assertEquals(5, count)
        assertEquals("{\"id\":1}\n", entries["sts/resource_timings/events.jsonl"])
        assertTrue(entries.containsKey("sts/resource_timings/previous-events.jsonl"))
        val status = JSONObject(entries.getValue("sts/resource_timings/export_status.json"))
        assertTrue(status.getBoolean("flush_acknowledged"))
        assertEquals("{\"id\":1}\n".toByteArray().size.toLong(), status.getLong("events_prefix_bytes"))
        assertFalse(status.getBoolean("prefix_complete"))
        assertFalse(entries.keys.any { it.endsWith("unrelated.txt") })
    }

    @Test fun liveExportWaitsForWriterAcknowledgementAndIncludesFlushedPrefix() {
        val directory = directory()
        File(directory, "events.jsonl").writeText("{\"id\":1}\n")
        File(directory, "summary.json").writeText("{\"closed_cleanly\":false}")
        val writer = Thread {
            val request = File(directory, "flush.request")
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((!request.isFile || request.length() == 0L) && System.nanoTime() < deadline) Thread.yield()
            if (!request.isFile) return@Thread
            val token = request.readText()
            File(directory, "events.jsonl").appendText("{\"id\":2}\n")
            File(directory, "flush.ack").writeText("$token\n${File(directory, "events.jsonl").length()}\n{\"written\":2}")
            File(directory, "summary.json").writeText("{\"written\":99}")
        }
        writer.start()
        val (_, entries) = export(directory, "sts/performance/resource_timings")
        writer.join(5000)
        assertFalse(writer.isAlive)
        assertEquals("{\"id\":1}\n{\"id\":2}\n", entries["sts/performance/resource_timings/events.jsonl"])
        assertTrue(JSONObject(entries.getValue("sts/performance/resource_timings/export_status.json")).getBoolean("flush_acknowledged"))
        assertEquals(2, JSONObject(entries.getValue("sts/performance/resource_timings/summary.json")).getInt("written"))
        assertTrue(JSONObject(entries.getValue("sts/performance/resource_timings/export_status.json")).getBoolean("prefix_complete"))
    }

    @Test fun absentDiagnosticsDirectoryAddsNothing() {
        val (count, entries) = export(File(directory(), "missing"))
        assertEquals(0, count)
        assertTrue(entries.isEmpty())
    }

    @Test fun unresponsiveOrCrashedWriterExportsPrefixAndExplicitIncompleteStatus() {
        val directory = directory()
        File(directory, "events.jsonl").writeText("{\"id\":1}\n{")
        File(directory, "summary.json").writeText("{\"closed_cleanly\":false,\"error\":\"disk failure\"}")
        val (_, entries) = export(directory)
        assertEquals("{\"id\":1}\n", entries["sts/resource_timings/events.jsonl"])
        assertFalse(JSONObject(entries.getValue("sts/resource_timings/export_status.json")).getBoolean("flush_acknowledged"))
    }

    @Test fun partialAcknowledgementDoesNotClaimMatchingSnapshot() {
        val directory = directory()
        File(directory, "events.jsonl").writeText("{\"id\":1}\n")
        File(directory, "summary.json").writeText("{\"closed_cleanly\":false}")
        val writer = Thread {
            val request = File(directory, "flush.request")
            val deadline = System.nanoTime() + 5_000_000_000L
            while ((!request.isFile || request.length() == 0L) && System.nanoTime() < deadline) Thread.yield()
            if (!request.isFile) return@Thread
            File(directory, "flush.ack").writeText("${request.readText()}\n9\n{\"written\":")
        }
        writer.start()
        val (_, entries) = export(directory)
        writer.join(5000)
        assertFalse(writer.isAlive)
        val status = JSONObject(entries.getValue("sts/resource_timings/export_status.json"))
        assertFalse(status.getBoolean("flush_acknowledged"))
        assertEquals("{\"id\":1}\n", entries["sts/resource_timings/events.jsonl"])
    }
}
