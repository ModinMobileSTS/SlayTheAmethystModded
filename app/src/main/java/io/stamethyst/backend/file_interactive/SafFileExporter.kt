package io.stamethyst.backend.file_interactive

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

internal fun interface SafFileExportProgressCallback {
    fun onProgress(percent: Int)
}

internal object SafFileExporter {
    @Throws(IOException::class)
    fun exportFile(
        context: Context,
        source: File,
        targetUri: Uri,
        progressCallback: SafFileExportProgressCallback? = null
    ) {
        if (!source.isFile) {
            throw IOException("Source file not found: ${source.absolutePath}")
        }
        reportProgress(progressCallback, 0)

        val directWriteError = try {
            writeWithFileDescriptor(context, source, targetUri, progressCallback)
            null
        } catch (error: Throwable) {
            error
        }

        if (directWriteError != null) {
            reportProgress(progressCallback, 0)
            try {
                writeWithOutputStream(context, source, targetUri, progressCallback)
            } catch (fallbackError: Throwable) {
                fallbackError.addSuppressed(directWriteError)
                throw wrapAsIoException(fallbackError)
            }
        }

        verifyExportSize(context, source.length(), targetUri)
        reportProgress(progressCallback, 100)
    }

    @Throws(IOException::class)
    private fun writeWithFileDescriptor(
        context: Context,
        source: File,
        targetUri: Uri,
        progressCallback: SafFileExportProgressCallback?
    ) {
        context.contentResolver.openFileDescriptor(targetUri, "rwt").use { descriptor ->
            if (descriptor == null) {
                throw IOException("Cannot open target file descriptor")
            }
            FileInputStream(source).use { input ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    copyStream(input, output, source.length(), progressCallback)
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun writeWithOutputStream(
        context: Context,
        source: File,
        targetUri: Uri,
        progressCallback: SafFileExportProgressCallback?
    ) {
        context.contentResolver.openOutputStream(targetUri, "wt").use { output ->
            if (output == null) {
                throw IOException("Cannot open target stream")
            }
            FileInputStream(source).use { input ->
                copyStream(input, output, source.length(), progressCallback)
                output.flush()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyStream(
        input: FileInputStream,
        output: OutputStream,
        totalBytes: Long,
        progressCallback: SafFileExportProgressCallback?
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var writtenBytes = 0L
        var lastReportedPercent = -1
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            output.write(buffer, 0, read)
            writtenBytes += read.toLong()
            if (totalBytes > 0L) {
                val percent = ((writtenBytes.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt()
                if (percent > lastReportedPercent) {
                    lastReportedPercent = percent
                    reportProgress(progressCallback, percent)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun verifyExportSize(context: Context, sourceLength: Long, targetUri: Uri) {
        if (sourceLength <= 0L) {
            return
        }
        repeat(5) { attempt ->
            val targetLength = resolveTargetLength(context, targetUri)
            if (targetLength == null) {
                return
            }
            if (targetLength >= sourceLength) {
                return
            }
            if (attempt < 4) {
                Thread.sleep(120)
            } else {
                throw IOException("Export incomplete: $targetLength / $sourceLength bytes")
            }
        }
    }

    private fun resolveTargetLength(context: Context, targetUri: Uri): Long? {
        try {
            context.contentResolver.openFileDescriptor(targetUri, "r").use { descriptor ->
                val statSize = descriptor?.statSize ?: -1L
                if (statSize >= 0L) {
                    return statSize
                }
            }
        } catch (_: Throwable) {
        }

        try {
            context.contentResolver.query(
                targetUri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        val size = cursor.getLong(sizeIndex)
                        if (size >= 0L) {
                            return size
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun wrapAsIoException(error: Throwable): IOException {
        return if (error is IOException) {
            error
        } else {
            IOException(error.message ?: error.javaClass.simpleName, error)
        }
    }

    private fun reportProgress(
        progressCallback: SafFileExportProgressCallback?,
        percent: Int
    ) {
        progressCallback?.onProgress(percent.coerceIn(0, 100))
    }
}
