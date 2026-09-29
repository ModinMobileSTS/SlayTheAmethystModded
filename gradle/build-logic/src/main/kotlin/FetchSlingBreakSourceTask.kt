import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.util.Base64
import javax.inject.Inject

/**
 * Downloads the Sling Break web bundle archive from its upstream repository and unpacks the
 * single top-level directory into [outputDirectory].
 *
 * The bundle is treated as a remote build input: the task always revalidates against the
 * upstream, so a new upstream commit is picked up without any manual step. Change detection
 * uses the server's `ETag` / `Last-Modified` validators via a conditional request, so an
 * unchanged upstream costs one header round-trip instead of a full archive download. When the
 * upstream cannot be reached a previous extraction is reused so offline builds still work.
 *
 * Override [sourceUrl] (or the `slingBreak.repository` / `slingBreak.ref` properties) to pin
 * an upstream ref; the plugin falls back to a local `slingBreak.sourceDir` when configured.
 */
abstract class FetchSlingBreakSourceTask : DefaultTask() {

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val stateFile: RegularFileProperty

    init {
        group = "build setup"
        description = "Fetches the Sling Break web bundle from its upstream archive, reusing the last extraction while the upstream content is unchanged."
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun fetch() {
        val url = sourceUrl.get()
        val target = outputDirectory.get().asFile
        val state = readState(stateFile.get().asFile)
        val hasBundle = hasBundle(target)
        // A cached validator is only meaningful for the exact URL it was issued for.
        val validators = state.takeIf { hasBundle && it.url == url && it.isUsable }

        val connection = try {
            openResponse(url, validators)
        } catch (failure: IOException) {
            if (hasBundle) {
                logger.warn(
                    "Sling Break source fetch failed (${failure.message}); reusing the previous " +
                        "extraction at ${target.absolutePath}."
                )
                return
            }
            throw GradleException(
                "Failed to fetch $url (${failure.message}). Set -PslingBreak.sourceUrl=<archive> " +
                    "or -PslingBreak.sourceDir=<local-directory>.",
                failure
            )
        }

        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    logger.lifecycle("Sling Break source unchanged upstream; skipping download.")
                }

                in 200..299 -> install(connection, url, target)

                else -> {
                    val detail = "HTTP ${connection.responseCode}"
                    if (hasBundle) {
                        logger.warn(
                            "Sling Break source fetch failed ($detail); reusing the previous " +
                                "extraction at ${target.absolutePath}."
                        )
                        return
                    }
                    throw GradleException("Failed to fetch $url ($detail).")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun install(connection: HttpURLConnection, url: String, target: File) {
        val archive = File(temporaryDir, "slingbreak-source.tar.gz")
        logger.lifecycle("Fetching Sling Break source: $url")
        download(connection, archive)

        val staging = File(temporaryDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        val extractedRoot = try {
            fileSystemOperations.copy {
                from(archiveOperations.tarTree(archiveOperations.gzip(archive)))
                into(staging)
            }
            staging.listFiles()
                ?.filterNot { it.name.startsWith("pax_global_header") }
                ?.singleOrNull()
                ?.takeIf { it.isDirectory }
                ?: throw GradleException(
                    "Sling Break archive $url did not contain a single top-level directory."
                )
        } catch (failure: IOException) {
            throw GradleException(
                "Failed to unpack Sling Break archive: ${failure.message}",
                failure
            )
        }

        requireBundle(extractedRoot, url)
        target.deleteRecursively()
        target.mkdirs()
        fileSystemOperations.copy {
            from(extractedRoot)
            into(target)
        }
        writeState(
            stateFile.get().asFile,
            FetchState(
                url = url,
                etag = connection.getHeaderField("ETag").orEmpty(),
                lastModified = connection.getHeaderField("Last-Modified").orEmpty()
            )
        )
        logger.lifecycle("Sling Break source ready at ${target.absolutePath}")
    }

    private fun openResponse(url: String, validators: FetchState?): HttpURLConnection {
        // Follow redirects manually so the conditional validators survive the
        // github.com -> codeload.github.com hop, which HttpURLConnection would otherwise drop.
        var current = URI(url).toURL()
        var redirects = 0
        while (true) {
            val connection = openConnection(current) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.setRequestProperty("User-Agent", "SlayTheAmethyst-build")
            proxyAuthorization(current)?.let {
                connection.setRequestProperty("Proxy-Authorization", it)
            }
            validators?.etag?.takeIf { it.isNotEmpty() }
                ?.let { connection.setRequestProperty("If-None-Match", it) }
            validators?.lastModified?.takeIf { it.isNotEmpty() }
                ?.let { connection.setRequestProperty("If-Modified-Since", it) }

            val code = try {
                connection.responseCode
            } catch (failure: IOException) {
                connection.disconnect()
                throw failure
            }
            if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == HTTP_TEMPORARY_REDIRECT ||
                code == HTTP_PERMANENT_REDIRECT
            ) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) {
                    throw IOException("redirect from $current without a Location header")
                }
                if (++redirects > 10) {
                    throw IOException("too many redirects from $url")
                }
                current = current.toURI().resolve(location).toURL()
                continue
            }
            return connection
        }
    }

    private fun openConnection(url: URL): URLConnection {
        val proxy = proxyFor(url)
        return if (proxy == null) url.openConnection() else url.openConnection(proxy)
    }

    private fun proxyFor(url: URL): Proxy? {
        val host = url.host.lowercase()
        val bypass = env("NO_PROXY").split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (bypass.any { it == "*" || host == it.removePrefix(".") || host.endsWith(it) }) return null
        val raw = proxyUrl()
        if (raw.isEmpty()) return null
        return try {
            val uri = URI(raw)
            val port = if (uri.port != -1) uri.port else if ("https".equals(uri.scheme, true)) 443 else 80
            Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, port))
        } catch (failure: RuntimeException) {
            logger.warn("Ignoring malformed proxy URL in HTTPS_PROXY/HTTP_PROXY: ${failure.message}")
            null
        }
    }

    private fun proxyAuthorization(url: URL): String? {
        if (proxyFor(url) == null) return null
        val userInfo = try {
            URI(proxyUrl()).userInfo
        } catch (failure: RuntimeException) {
            null
        } ?: return null
        return "Basic " + Base64.getEncoder().encodeToString(userInfo.toByteArray())
    }

    private fun proxyUrl(): String = env("HTTPS_PROXY").ifEmpty { env("HTTP_PROXY") }

    private fun env(name: String): String =
        System.getenv(name)?.trim().orEmpty().ifEmpty { System.getenv(name.lowercase())?.trim().orEmpty() }

    private fun download(connection: HttpURLConnection, target: File) {
        // Download to a sibling temp file and publish it atomically, so a failed or
        // interrupted download cannot leave a truncated archive that later runs reuse.
        val partial = File(target.parentFile, "${target.name}.part")
        partial.parentFile.mkdirs()
        partial.delete()
        try {
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (partial.length() == 0L) {
                throw IOException("empty response body")
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
        } catch (timeout: SocketTimeoutException) {
            partial.delete()
            throw GradleException(
                "Timed out fetching Sling Break source. Set -PslingBreak.sourceUrl=<archive> " +
                    "or -PslingBreak.sourceDir=<local-directory>.",
                timeout
            )
        } catch (failure: IOException) {
            partial.delete()
            throw GradleException("Failed to download Sling Break archive: ${failure.message}", failure)
        }
    }

    private fun requireBundle(root: File, url: String) {
        if (!File(root, "index.html").isFile || !File(root, "launcher-mode.js").isFile) {
            throw GradleException(
                "Sling Break archive $url is missing index.html or launcher-mode.js."
            )
        }
    }

    private fun hasBundle(dir: File): Boolean =
        File(dir, "index.html").isFile && File(dir, "launcher-mode.js").isFile

    private data class FetchState(
        val url: String = "",
        val etag: String = "",
        val lastModified: String = ""
    ) {
        val isUsable: Boolean get() = etag.isNotEmpty() || lastModified.isNotEmpty()
    }

    private fun readState(file: File): FetchState {
        if (!file.isFile) return FetchState()
        return try {
            val lines = file.readLines()
            FetchState(
                url = lines.getOrNull(0).orEmpty(),
                etag = lines.getOrNull(1).orEmpty(),
                lastModified = lines.getOrNull(2).orEmpty()
            )
        } catch (failure: IOException) {
            logger.warn("Ignoring unreadable Sling Break fetch state ${file.absolutePath}: ${failure.message}")
            FetchState()
        }
    }

    private fun writeState(file: File, state: FetchState) {
        file.parentFile.mkdirs()
        file.writeText("${state.url}\n${state.etag}\n${state.lastModified}\n")
    }

    private companion object {
        const val HTTP_TEMPORARY_REDIRECT = 307
        const val HTTP_PERMANENT_REDIRECT = 308
    }
}
