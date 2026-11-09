package io.stamethyst.backend.diag

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Runs in the diagnostics process. The game writer acknowledges a bounded, flushed prefix. */
internal object ResourceTimingArchive {
    fun write(zip: ZipOutputStream, directory: File, prefix: String): Int {
        if (!directory.isDirectory) return 0
        val events = File(directory, "events.jsonl")
        var acknowledged = false
        var length = events.length()
        var summarySnapshot: String? = null
        val closedCleanly = runCatching {
            JSONObject(File(directory, "summary.json").readTextOrEmpty()).optBoolean("closed_cleanly")
        }.getOrDefault(false)
        if (events.isFile && !closedCleanly) {
            val token = UUID.randomUUID().toString()
            val request = File(directory, "flush.request")
            runCatching { request.writeText(token, Charsets.UTF_8) }
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val ack = File(directory, "flush.ack").readTextOrEmpty().lines()
                if (ack.firstOrNull() == token) {
                    val prefixLength = ack.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }
                    val snapshot = ack.getOrNull(2)?.takeIf {
                        runCatching { JSONObject(it) }.isSuccess
                    }
                    if (prefixLength != null && snapshot != null) {
                        length = prefixLength
                        summarySnapshot = snapshot
                        acknowledged = true
                        break
                    }
                }
                Thread.sleep(20) // Only the background diagnostics process waits.
            }
        } else if (events.isFile) {
            acknowledged = true
        }
        if (!acknowledged) length = events.length()
        var count = 0
        var exportedLength = 0L
        for (name in listOf("events.jsonl", "summary.json", "previous-events.jsonl", "previous-summary.json")) {
            val file = File(directory, name)
            if (!file.isFile) continue
            zip.putNextEntry(ZipEntry("$prefix/$name"))
            if (name.endsWith(".jsonl")) {
                val copied = copyCompleteLines(file, zip, if (name == "events.jsonl") length else file.length())
                if (name == "events.jsonl") exportedLength = copied
            }
            else if (name == "summary.json" && summarySnapshot != null) zip.write(summarySnapshot.toByteArray(Charsets.UTF_8))
            else zip.write(file.readTextOrEmpty().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            count++
        }
        zip.putNextEntry(ZipEntry("$prefix/export_status.json"))
        zip.write(("{\"schema\":1,\"flush_acknowledged\":$acknowledged,\"events_prefix_bytes\":$exportedLength," +
            "\"requested_prefix_bytes\":$length,\"prefix_complete\":${exportedLength == length}," +
            "\"note\":\"Live export is a flushed prefix; an unacknowledged export may exclude queued events. " +
            "A crash or forced process exit can lose unflushed events. Check summary error, unsaved and closed_cleanly.\"}\n")
            .toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
        return count + 1
    }

    private fun File.readTextOrEmpty(): String = runCatching { readText(Charsets.UTF_8) }.getOrDefault("")

    /** Never export a torn JSONL record, even after a crash or an unacknowledged live snapshot. */
    private fun copyCompleteLines(file: File, zip: ZipOutputStream, limit: Long): Long {
        RandomAccessFile(file, "r").use { input ->
            var end = minOf(limit, input.length())
            while (end > 0) {
                input.seek(end - 1)
                if (input.read() == 10) break
                end--
            }
            input.seek(0)
            val buffer = ByteArray(64 * 1024)
            var remaining = end
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                zip.write(buffer, 0, read)
                remaining -= read
            }
            return end - remaining
        }
    }
}
