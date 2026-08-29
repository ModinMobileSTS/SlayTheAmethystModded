package io.stamethyst.backend.resources

import android.content.Context
import android.net.Uri
import io.stamethyst.backend.github.WattToolkitAcceleratedHttp
import io.stamethyst.backend.network.NetworkAccelerationPolicy
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.backend.update.toGithubMirrorHttpException
import io.stamethyst.config.RuntimePaths
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream
import okhttp3.Request

internal data class ArthasResourceDownloadProgress(
    val source: UpdateSource,
    val downloadedBytes: Long,
    val totalBytes: Long?,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0L }?.let {
            ((downloadedBytes * 100L) / it).toInt().coerceIn(0, 100)
        }
}

internal data class ArthasResourcePackState(
    val installed: Boolean,
    val version: String = "",
    val valid: Boolean = false,
)

internal object ArthasResourcePackService {
    private const val DOWNLOAD_URL =
        "https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/arthas-resource.zip"
    private const val ARCHIVE_SHA256 =
        "1a4bf49cd415f756cc9370dbfa2bf290a25230ad8de8e51fb9233ffb5b889d0a"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "SlayTheAmethyst-ArthasResource"
    const val MANIFEST_NAME = "arthas-resource.properties"
    const val SCHEMA_VERSION = "1"
    private const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 32L * 1024L * 1024L
    private val JAR_NAMES = listOf("arthas-core.jar", "arthas-spy.jar", "arthas-bridge.jar")
    private val ALLOWED_ENTRIES = (JAR_NAMES + MANIFEST_NAME).toSet()

    fun state(context: Context): ArthasResourcePackState {
        val current = RuntimePaths.arthasResourceCurrentDir(context)
        val manifestFile = File(current, MANIFEST_NAME)
        if (!manifestFile.isFile) return ArthasResourcePackState(installed = false)
        return runCatching {
            val manifest = Properties().apply {
                FileInputStream(manifestFile).use(::load)
            }
            validateInstalledDirectory(current, manifest)
            ArthasResourcePackState(
                installed = true,
                version = manifest.getProperty("packageVersion").orEmpty(),
                valid = true,
            )
        }.getOrElse {
            ArthasResourcePackState(installed = true, valid = false)
        }
    }

    fun isInstalled(context: Context): Boolean = state(context).let { it.installed && it.valid }

    @Throws(IOException::class)
    fun downloadAndInstall(
        context: Context,
        preferredSource: UpdateSource,
        onProgress: (ArthasResourceDownloadProgress) -> Unit = {},
    ): ArthasResourcePackState {
        val root = RuntimePaths.arthasResourceRoot(context).apply { mkdirs() }
        val archive = File(root, "download.zip")
        val clients = WattToolkitAcceleratedHttp.createClientPair(
            context = context,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
            followRedirects = true,
        )
        val normalizedSource = UpdateSource.normalizePreferredUserSource(preferredSource.id)
        val candidates = UpdateSource.downloadCandidates(
            preferredUserSource = normalizedSource,
            metadataSource = normalizedSource,
            bypassAcceleratedLinks = NetworkAccelerationPolicy.shouldBypassAcceleratedLinks(context),
        )
        try {
            GithubMirrorFallback.run(candidates) { source ->
                archive.delete()
                val request = Request.Builder()
                    .url(source.buildUrl(DOWNLOAD_URL))
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .build()
                clients.pick(source.usesGithubAcceleration).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw response.toGithubMirrorHttpException()
                    val body = response.body
                    val totalBytes = body.contentLength().takeIf { it > 0L }
                    if (totalBytes != null && totalBytes > MAX_ARCHIVE_BYTES) {
                        throw IOException("Arthas resource archive is too large")
                    }
                    var downloadedBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(archive, false).use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                downloadedBytes += read
                                if (downloadedBytes > MAX_ARCHIVE_BYTES) {
                                    throw IOException("Arthas resource archive is too large")
                                }
                                output.write(buffer, 0, read)
                                onProgress(
                                    ArthasResourceDownloadProgress(
                                        source = source,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                    )
                                )
                            }
                        }
                    }
                    if (downloadedBytes <= 0L || sha256(archive) != ARCHIVE_SHA256) {
                        throw IOException("Arthas resource archive checksum mismatch")
                    }
                }
            }
            return installArchive(context, archive)
        } finally {
            archive.delete()
        }
    }

    @Throws(IOException::class)
    fun install(context: Context, source: Uri): ArthasResourcePackState {
        val root = RuntimePaths.arthasResourceRoot(context).apply { mkdirs() }
        val archive = File(root, "incoming.zip")
        try {
            context.contentResolver.openInputStream(source).use { input ->
                if (input == null) throw IOException("Unable to open Arthas resource archive")
                FileOutputStream(archive, false).use { output ->
                    copyBounded(input, output, MAX_ARCHIVE_BYTES)
                }
            }
            return installArchive(context, archive)
        } finally {
            archive.delete()
        }
    }

    @Throws(IOException::class)
    internal fun installArchive(context: Context, archive: File): ArthasResourcePackState {
        val staging = RuntimePaths.arthasResourceStagingDir(context)
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            if (!archive.isFile || archive.length() > MAX_ARCHIVE_BYTES) {
                throw IOException("Arthas resource archive is missing or too large")
            }
            extractExactArchive(archive, staging)
            val manifest = Properties().apply {
                FileInputStream(File(staging, MANIFEST_NAME)).use(::load)
            }
            validateInstalledDirectory(staging, manifest)
            replaceCurrent(context, staging)
            return state(context).also {
                if (!it.valid) throw IOException("Installed Arthas resource pack failed validation")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractExactArchive(archive: File, staging: File) {
        val seen = LinkedHashSet<String>()
        var expandedBytes = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name !in ALLOWED_ENTRIES || name.contains('/') || name.contains('\\')) {
                    throw IOException("Unexpected Arthas resource entry: $name")
                }
                if (!seen.add(name)) throw IOException("Duplicate Arthas resource entry: $name")
                val destination = File(staging, name)
                FileOutputStream(destination, false).use { output ->
                    expandedBytes += copyBounded(zip, output, MAX_EXPANDED_BYTES - expandedBytes)
                }
                zip.closeEntry()
            }
        }
        if (seen != ALLOWED_ENTRIES) {
            throw IOException("Arthas resource archive entries do not match required files")
        }
    }

    private fun validateInstalledDirectory(directory: File, manifest: Properties) {
        val installedEntries = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        if (installedEntries != ALLOWED_ENTRIES) {
            throw IOException("Installed Arthas resource entries do not match required files")
        }
        if (manifest.getProperty("schemaVersion") != SCHEMA_VERSION) {
            throw IOException("Unsupported Arthas resource schema")
        }
        if (manifest.getProperty("packageVersion").orEmpty().isBlank()) {
            throw IOException("Arthas resource version is missing")
        }
        JAR_NAMES.forEach { name ->
            val file = File(directory, name)
            val expectedSize = manifest.getProperty("$name.size")?.toLongOrNull()
                ?: throw IOException("Missing size for $name")
            val expectedHash = manifest.getProperty("$name.sha256").orEmpty().lowercase()
            if (!file.isFile || file.length() != expectedSize || expectedSize <= 0L) {
                throw IOException("Invalid Arthas resource size: $name")
            }
            if (!expectedHash.matches(Regex("[0-9a-f]{64}")) || sha256(file) != expectedHash) {
                throw IOException("Arthas resource checksum mismatch: $name")
            }
        }
    }

    private fun replaceCurrent(context: Context, staging: File) {
        val current = RuntimePaths.arthasResourceCurrentDir(context)
        val previous = RuntimePaths.arthasResourcePreviousDir(context)
        previous.deleteRecursively()
        if (current.exists() && !current.renameTo(previous)) {
            throw IOException("Unable to preserve previous Arthas resource pack")
        }
        if (!staging.renameTo(current)) {
            if (previous.exists()) previous.renameTo(current)
            throw IOException("Unable to activate Arthas resource pack")
        }
        previous.deleteRecursively()
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        if (maxBytes <= 0L) throw IOException("Arthas resource size limit exceeded")
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("Arthas resource size limit exceeded")
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
