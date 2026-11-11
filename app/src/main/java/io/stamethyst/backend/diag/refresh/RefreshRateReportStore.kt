package io.stamethyst.backend.diag.refresh

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object RefreshRateReportStore {
    fun latestFile(context: Context): File = File(context.filesDir, "refresh-rate-diagnostics/latest-report.zip")

    fun writeZip(output: OutputStream, text: String, json: String) {
        ZipOutputStream(output.buffered()).use { zip ->
            listOf("report.txt" to text, "report.json" to json).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun save(context: Context, text: String, json: String) {
        val file = latestFile(context)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Cannot create report directory" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            // ZipOutputStream closes its delegate; keep the atomic stream open until finishWrite.
            val delegate = object : java.io.FilterOutputStream(stream) {
                override fun close() { flush() }
            }
            writeZip(delegate, text, json)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
