package io.stamethyst.backend.update

import android.content.Context
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.launch.ComponentInstaller
import io.stamethyst.backend.launch.MtsStartupCacheCoordinator
import io.stamethyst.backend.mods.MtsLoaderCrashPatcher
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import okhttp3.OkHttpClient
import okhttp3.Request

object MtsComponentUpdateService {
    private const val LOADER_CLASS_ENTRY = "com/evacipated/cardcrawl/modthespire/Loader.class"
    private const val UPDATE_URL =
        "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/ModTheSpire.jar"
    private const val OUTDATED_SHA256 =
        "27a5a343970f750117ff7b7d5dd0c46baef6c104706531574b46220ae95cf715"
    private const val OUTDATED_CONTENT_FINGERPRINT_EXCLUDING_LOADER =
        "6f1911b02d963448160b3ad308bb4eeae0adc9aeaeeb4d8b0c8d364e4290cf6f"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "SlayTheAmethyst-MtsComponentUpdate"
    private val FINGERPRINT_SEPARATOR = byteArrayOf(0, 0xFF.toByte())

    fun isBundledMtsOutdated(context: Context): Boolean {
        return isMtsJarOutdated(RuntimePaths.importedMtsJar(context))
    }

    internal fun isMtsJarOutdated(mtsJar: File): Boolean {
        if (!mtsJar.isFile) {
            return false
        }
        if (calculateSha256Hex(mtsJar).equals(OUTDATED_SHA256, ignoreCase = true)) {
            return true
        }
        return runCatching {
            calculateJarContentFingerprintExcludingLoader(mtsJar)
                .equals(OUTDATED_CONTENT_FINGERPRINT_EXCLUDING_LOADER, ignoreCase = true)
        }.getOrDefault(false)
    }

    fun installUpdate(
        context: Context,
        preferredUserSource: UpdateSource,
        onProgress: (MtsComponentUpdateProgress) -> Unit = {},
    ): MtsComponentUpdateResult {
        ComponentInstaller.ensureInstalled(context)

        val targetFile = RuntimePaths.importedMtsJar(context)
        val targetParent = targetFile.parentFile
            ?: throw IOException("ModTheSpire.jar has no parent directory.")
        if (!targetParent.exists() && !targetParent.mkdirs()) {
            throw IOException("Failed to create ${targetParent.absolutePath}")
        }

        val clients = WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        )
        val normalizedPreferredSource = UpdateSource.normalizePreferredUserSource(preferredUserSource.id)
        val bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context)
        val candidates = UpdateSource.downloadCandidates(
            preferredUserSource = normalizedPreferredSource,
            metadataSource = normalizedPreferredSource,
            bypassAcceleratedLinks = bypassAcceleratedLinks,
        )
        return GithubMirrorFallback.run(candidates) { source ->
            val requestUrl = source.buildUrl(UPDATE_URL)
            val tempFile = File(targetParent, "${targetFile.name}.download")
            try {
                downloadToFile(
                    client = clients.pick(source.usesGithubAcceleration),
                    requestUrl = requestUrl,
                    targetFile = tempFile,
                    source = source,
                    onProgress = onProgress,
                )
                validateMtsJar(tempFile)
                MtsLoaderCrashPatcher.ensurePatchedMtsJar(tempFile)
                validateMtsJar(tempFile)
                replaceFile(tempFile, targetFile)
                MtsStartupCacheCoordinator.invalidate(context)
                MtsComponentUpdateResult(source = source)
            } finally {
                if (tempFile.exists() && !tempFile.delete()) {
                    tempFile.deleteOnExit()
                }
            }
        }.value
    }

    private fun downloadToFile(
        client: OkHttpClient,
        requestUrl: String,
        targetFile: File,
        source: UpdateSource,
        onProgress: (MtsComponentUpdateProgress) -> Unit,
    ) {
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw response.toGithubMirrorHttpException()
            }
            val body = response.body
            val totalBytes = body.contentLength().takeIf { it > 0L }
            var downloadedBytes = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloadedBytes += read.toLong()
                        onProgress(
                            MtsComponentUpdateProgress(
                                source = source,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
            if (downloadedBytes <= 0L) {
                throw IOException("Downloaded ModTheSpire.jar is empty.")
            }
        }
    }

    private fun replaceFile(sourceFile: File, targetFile: File) {
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.replace-backup")
        if (backupFile.exists() && !backupFile.delete()) {
            throw IOException("Failed to remove stale backup ${backupFile.absolutePath}")
        }
        var originalMoved = false
        try {
            if (targetFile.exists()) {
                if (!targetFile.renameTo(backupFile)) {
                    throw IOException("Failed to back up ${targetFile.absolutePath}")
                }
                originalMoved = true
            }
            if (!sourceFile.renameTo(targetFile)) {
                sourceFile.copyTo(targetFile, overwrite = true)
                if (!sourceFile.delete()) {
                    sourceFile.deleteOnExit()
                }
            }
            if (backupFile.exists() && !backupFile.delete()) {
                backupFile.deleteOnExit()
            }
        } catch (error: Throwable) {
            if (originalMoved && targetFile.exists()) {
                targetFile.delete()
            }
            if (originalMoved && backupFile.exists()) {
                backupFile.renameTo(targetFile)
            }
            throw error
        }
    }

    private fun validateMtsJar(file: File) {
        ZipFile(file).use { zipFile ->
            zipFile.getEntry(LOADER_CLASS_ENTRY)
                ?: throw IOException("Downloaded ModTheSpire.jar is invalid.")
        }
    }

    private fun calculateJarContentFingerprintExcludingLoader(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipFile(file).use { zipFile ->
            val entries = zipFile.entries()
                .asSequence()
                .filter { entry -> !entry.isDirectory && entry.name != LOADER_CLASS_ENTRY }
                .sortedBy { entry -> entry.name }
            entries.forEach { entry ->
                digest.update(entry.name.toByteArray(StandardCharsets.UTF_8))
                digest.update(FINGERPRINT_SEPARATOR)
                zipFile.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        digest.update(buffer, 0, read)
                    }
                }
                digest.update(FINGERPRINT_SEPARATOR)
            }
        }
        return digestToHex(digest.digest())
    }

    private fun calculateSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digestToHex(digest.digest())
    }

    private fun digestToHex(bytes: ByteArray): String {
        return bytes.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
        }
    }
}

data class MtsComponentUpdateProgress(
    val source: UpdateSource,
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val progressPercent: Int?
        get() = totalBytes
            ?.takeIf { it > 0L }
            ?.let { total -> ((downloadedBytes * 100L) / total).toInt().coerceIn(0, 100) }
}

data class MtsComponentUpdateResult(
    val source: UpdateSource,
)
