package top.apricityx.workshop.workshop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class DirectWorkshopDownloader(
    private val client: OkHttpClient,
) {
    suspend fun download(
        request: WorkshopDownloadRequest,
        item: ResolvedWorkshopItem.DirectUrlItem,
        emit: suspend (DownloadEvent) -> Unit,
        log: suspend (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val outputFile = File(request.outputDir, sanitizeFileName(item.fileName))
        val partialFile = File(request.outputDir, "${outputFile.name}.part")
        outputFile.parentFile?.mkdirs()
        if (outputFile.isFile()) {
            if (item.size == null || outputFile.length() == item.size) {
                log("Reusing completed direct download ${outputFile.name}")
                emit(
                    DownloadEvent.Progress(
                        writtenBytes = outputFile.length(),
                        totalBytes = item.size,
                        completedFiles = 1,
                        totalFiles = 1,
                    ),
                )
                emitCompletedFile(outputFile, emit)
                return@withContext
            }

            log("Found incomplete direct download file, moving it to partial cache")
            if (!partialFile.exists()) {
                outputFile.copyTo(partialFile, overwrite = true)
            }
            outputFile.delete()
        }

        log("Starting direct file_url download: ${item.fileUrl}")
        var lastError: Throwable? = null

        for (attempt in 1..MAX_DIRECT_DOWNLOAD_ATTEMPTS) {
            val existingBytes = partialFile.takeIf(File::exists)?.length() ?: 0L
            if (existingBytes > 0L) {
                log("Resuming direct file_url download from $existingBytes bytes")
            }

            try {
                val request = Request.Builder()
                    .url(item.fileUrl)
                    .apply {
                        if (existingBytes > 0L) {
                            header("Range", "bytes=$existingBytes-")
                        }
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    if (existingBytes > 0L && response.code == 200) {
                        log("Direct download server ignored range request, restarting from zero")
                        partialFile.delete()
                        return@use
                    }

                    if (!response.isSuccessful) {
                        throw WorkshopDownloadException("Direct download failed: ${response.code}")
                    }

                    val append = existingBytes > 0L && response.code == 206
                    val totalBytes = item.size ?: response.body?.contentLength()
                        ?.takeIf { it >= 0 }
                        ?.let { contentLength ->
                            if (append) existingBytes + contentLength else contentLength
                        }

                    if (append) {
                        emit(
                            DownloadEvent.Progress(
                                writtenBytes = existingBytes,
                                totalBytes = totalBytes,
                                completedFiles = 0,
                                totalFiles = 1,
                            ),
                        )
                    }

                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(partialFile, append).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var written = existingBytes

                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (Thread.currentThread().isInterrupted) throw InterruptedException("Direct workshop download interrupted")
                                val read = input.read(buffer)
                                if (read == -1) {
                                    break
                                }
                                output.write(buffer, 0, read)
                                written += read
                                emit(
                                    DownloadEvent.Progress(
                                        writtenBytes = written,
                                        totalBytes = totalBytes,
                                        completedFiles = 0,
                                        totalFiles = 1,
                                    ),
                                )
                            }
                        }
                    } ?: throw WorkshopDownloadException("Direct download body was empty")
                }

                if (!partialFile.isFile) {
                    continue
                }
                if (!partialFile.renameTo(outputFile)) {
                    partialFile.copyTo(outputFile, overwrite = true)
                    partialFile.delete()
                }
                emitCompletedFile(outputFile, emit)
                return@withContext
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                log("Direct download attempt $attempt/$MAX_DIRECT_DOWNLOAD_ATTEMPTS failed: ${error.message}")
            }
        }

        throw WorkshopDownloadException("Direct download exhausted retries", lastError)
    }

    private fun sanitizeFileName(fileName: String): String =
        fileName.replace('\\', '_').replace('/', '_').ifBlank { "workshop.bin" }

    private suspend fun emitCompletedFile(
        outputFile: File,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        emit(
            DownloadEvent.FileCompleted(
                DownloadedFileInfo(
                    relativePath = outputFile.name,
                    sizeBytes = outputFile.length(),
                    modifiedEpochMillis = outputFile.lastModified(),
                ),
            ),
        )
    }

    companion object {
        private const val MAX_DIRECT_DOWNLOAD_ATTEMPTS = 3
    }
}
