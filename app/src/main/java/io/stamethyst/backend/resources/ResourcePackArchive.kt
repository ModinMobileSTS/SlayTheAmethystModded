package io.stamethyst.backend.resources

import android.content.Context
import io.stamethyst.BuildConfig
import io.stamethyst.R
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.launch.progressText
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class ResourcePackFileRecord(
    val path: String,
    val size: Long,
    val sha256: String
)

internal data class ResourcePackManifest(
    val schemaVersion: Int,
    val resourcePackVersion: String,
    val abi: String,
    val packId: String,
    val files: List<ResourcePackFileRecord>
)

internal data class ResourcePackGenerationValidation(
    val manifest: ResourcePackManifest?,
    val issues: List<String>
)

internal object ResourcePackArchive {
    private const val MAX_EXPANDED_BYTES = 1L * 1024L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_DIAGNOSTIC_ISSUES = 32
    private const val PACK_ID_LENGTH = 64

    fun buildManifest(root: File, version: String): ResourcePackManifest {
        val files = listContentFiles(root).sortedBy(ResourcePackFileRecord::path)
        if (files.isEmpty()) {
            throw IOException("Resource pack contains no content files")
        }
        val packId = computePackId(version, ResourcePackContract.ABI, files)
        return ResourcePackManifest(
            schemaVersion = ResourcePackContract.SCHEMA_VERSION,
            resourcePackVersion = version,
            abi = ResourcePackContract.ABI,
            packId = packId,
            files = files
        )
    }

    fun writeGenerationMetadata(root: File, manifest: ResourcePackManifest) {
        val manifestFile = File(root, ResourcePackContract.MANIFEST_FILE_NAME)
        val properties = Properties()
        properties["schemaVersion"] = manifest.schemaVersion.toString()
        properties["resourcePackVersion"] = manifest.resourcePackVersion
        properties["abi"] = manifest.abi
        properties["packId"] = manifest.packId
        properties["fileCount"] = manifest.files.size.toString()
        manifest.files.forEachIndexed { index, file ->
            properties["file.$index.path"] = file.path
            properties["file.$index.size"] = file.size.toString()
            properties["file.$index.sha256"] = file.sha256
        }
        writeProperties(manifestFile, properties)

        val marker = File(root, ResourcePackContract.INSTALL_MARKER_FILE_NAME)
        marker.writeText(
            "version=${manifest.resourcePackVersion}\n" +
                "appVersion=${BuildConfig.VERSION_NAME}\n" +
                "packId=${manifest.packId}\n" +
                "schemaVersion=${ResourcePackContract.SCHEMA_VERSION}\n",
            StandardCharsets.UTF_8
        )
    }

    fun validateGeneration(root: File, expectedVersion: String): ResourcePackGenerationValidation {
        val issues = ArrayList<String>()
        if (!root.isDirectory) {
            return ResourcePackGenerationValidation(
                null,
                listOf("generation directory is missing: ${root.absolutePath}")
            )
        }
        val manifest = readManifest(File(root, ResourcePackContract.MANIFEST_FILE_NAME), issues)
            ?: return ResourcePackGenerationValidation(null, limitIssues(issues))
        if (manifest.schemaVersion != ResourcePackContract.SCHEMA_VERSION) {
            issues += "unsupported manifest schema ${manifest.schemaVersion}"
        }
        if (manifest.resourcePackVersion != expectedVersion) {
            issues += "resource pack version ${manifest.resourcePackVersion} != $expectedVersion"
        }
        if (manifest.abi != ResourcePackContract.ABI) {
            issues += "resource pack ABI ${manifest.abi} != ${ResourcePackContract.ABI}"
        }
        if (!isSafePackId(manifest.packId)) {
            issues += "invalid resource pack id"
        }
        val markerVersion = readMarkerProperty(root, "version")
        if (markerVersion != expectedVersion) {
            issues += "install marker version ${markerVersion ?: "missing"} != $expectedVersion"
        }
        if (readMarkerProperty(root, "packId") != manifest.packId) {
            issues += "install marker pack id does not match manifest"
        }
        if (readMarkerProperty(root, "schemaVersion")?.toIntOrNull() != ResourcePackContract.SCHEMA_VERSION) {
            issues += "install marker schema is invalid"
        }
        issues += ResourcePackContract.collectMissingContent(root)

        val actualRecords = runCatching { listContentFiles(root).sortedBy(ResourcePackFileRecord::path) }
            .getOrElse { error ->
                issues += summarizeError(error)
                emptyList()
            }
        val expectedByPath = manifest.files.associateBy(ResourcePackFileRecord::path)
        val actualByPath = actualRecords.associateBy(ResourcePackFileRecord::path)
        manifest.files.forEach { expected ->
            val actual = actualByPath[expected.path]
            if (actual == null) {
                issues += "manifest file missing: ${expected.path}"
            } else {
                if (actual.size != expected.size) {
                    issues += "size mismatch: ${expected.path}"
                }
                if (!actual.sha256.equals(expected.sha256, ignoreCase = true)) {
                    issues += "hash mismatch: ${expected.path}"
                }
            }
        }
        actualRecords.filter { actual -> actual.path !in expectedByPath }
            .forEach { extra -> issues += "unexpected file: ${extra.path}" }
        if (manifest.files.size != actualRecords.size) {
            issues += "manifest file count ${manifest.files.size} != ${actualRecords.size}"
        }
        val computedPackId = runCatching {
            computePackId(manifest.resourcePackVersion, manifest.abi, actualRecords)
        }.getOrNull()
        if (computedPackId != null && computedPackId != manifest.packId) {
            issues += "pack id does not match content"
        }
        return ResourcePackGenerationValidation(manifest, limitIssues(issues))
    }

    fun extractArchive(
        archiveFile: File,
        targetDir: File,
        progressCallback: StartupProgressCallback?,
        context: Context
    ) {
        prepareDirectory(targetDir)
        var expandedBytes = 0L
        ZipFile(archiveFile).use { zipFile ->
            val entries = zipFile.entries().asSequence().toList()
            if (entries.size > MAX_ENTRY_COUNT) {
                throw IOException("Resource pack contains too many entries: ${entries.size}")
            }
            val seenPaths = LinkedHashSet<String>()
            val files = entries.filterNot(ZipEntry::isDirectory)
            files.forEachIndexed { index, entry ->
                throwIfInterrupted()
                val targetFile = resolveZipTarget(targetDir, entry)
                val normalizedPath = targetDir.canonicalFile.toPath()
                    .relativize(targetFile.canonicalFile.toPath())
                    .toString()
                    .replace(File.separatorChar, '/')
                if (!seenPaths.add(normalizedPath)) {
                    throw IOException("Resource pack contains duplicate entry: $normalizedPath")
                }
                if (!normalizedPath.startsWith("assets/") && !normalizedPath.startsWith("lib/")) {
                    throw IOException("Unexpected resource pack entry: $normalizedPath")
                }
                if (entry.size > MAX_ENTRY_BYTES) {
                    throw IOException("Resource pack entry is too large: $normalizedPath")
                }
                if (targetFile.exists() && targetFile.isDirectory) {
                    throw IOException("Resource pack entry conflicts with a directory: $normalizedPath")
                }
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw IOException("Failed to create directory: ${parent.absolutePath}")
                }
                var entryBytes = 0L
                zipFile.getInputStream(entry).use { input ->
                    FileOutputStream(targetFile, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            throwIfInterrupted()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            entryBytes += read
                            expandedBytes += read
                            if (entryBytes > MAX_ENTRY_BYTES || expandedBytes > MAX_EXPANDED_BYTES) {
                                throw IOException("Resource pack expanded content is too large")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (entry.size >= 0L && entryBytes != entry.size) {
                    throw IOException("Resource pack entry size mismatch: $normalizedPath")
                }
                if (progressCallback != null) {
                    val percent = ((index + 1) * 100 / files.size.coerceAtLeast(1)).coerceIn(0, 100)
                    reportProgress(
                        progressCallback,
                        72 + ((percent * 24) / 100),
                        context.progressText(R.string.startup_progress_extracting_external_resources, percent)
                    )
                }
            }
        }
    }

    fun readMarkerProperty(root: File, key: String): String? {
        val marker = File(root, ResourcePackContract.INSTALL_MARKER_FILE_NAME)
        if (!marker.isFile) return null
        return runCatching {
            marker.readLines(StandardCharsets.UTF_8)
                .firstOrNull { line -> line.startsWith("$key=") }
                ?.substringAfter("$key=")
                ?.trim()
        }.getOrNull()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                throwIfInterrupted()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHexString()
    }

    fun isSafePackId(packId: String): Boolean {
        return packId.length == PACK_ID_LENGTH &&
            packId.all { character -> character in "0123456789abcdefABCDEF" }
    }

    private fun listContentFiles(root: File): List<ResourcePackFileRecord> {
        if (!root.isDirectory) {
            throw IOException("Resource pack root is not a directory: ${root.absolutePath}")
        }
        val canonicalRoot = root.canonicalFile.toPath()
        val records = ArrayList<ResourcePackFileRecord>()
        root.walkTopDown().forEach { file ->
            if (file.isDirectory && Files.isSymbolicLink(file.toPath())) {
                throw IOException("Resource pack contains a symbolic link: ${file.absolutePath}")
            }
            if (!file.isFile) return@forEach
            if (Files.isSymbolicLink(file.toPath())) {
                throw IOException("Resource pack contains a symbolic link: ${file.absolutePath}")
            }
            val path = file.canonicalFile.toPath()
            if (!path.startsWith(canonicalRoot)) {
                throw IOException("Resource pack file escapes its root: ${file.absolutePath}")
            }
            val relative = canonicalRoot.relativize(path).toString().replace(File.separatorChar, '/')
            if (relative == ResourcePackContract.INSTALL_MARKER_FILE_NAME ||
                relative == ResourcePackContract.MANIFEST_FILE_NAME
            ) {
                return@forEach
            }
            if (!relative.startsWith("assets/") && !relative.startsWith("lib/")) {
                throw IOException("Unexpected resource pack entry: $relative")
            }
            records += ResourcePackFileRecord(
                path = relative,
                size = file.length(),
                sha256 = sha256(file)
            )
            if (records.size > MAX_ENTRY_COUNT) {
                throw IOException("Resource pack contains too many files: ${records.size}")
            }
        }
        return records
    }

    private fun computePackId(
        version: String,
        abi: String,
        files: List<ResourcePackFileRecord>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("version=$version\nabi=$abi\n".toByteArray(StandardCharsets.UTF_8))
        files.sortedBy(ResourcePackFileRecord::path).forEach { file ->
            digest.update(
                "${file.path}\u0000${file.size}\u0000${file.sha256}\n".toByteArray(StandardCharsets.UTF_8)
            )
        }
        return digest.digest().toHexString()
    }

    private fun readManifest(file: File, issues: MutableList<String>): ResourcePackManifest? {
        if (!file.isFile) {
            issues += "manifest is missing"
            return null
        }
        val properties = Properties()
        try {
            file.reader(StandardCharsets.UTF_8).use(properties::load)
        } catch (error: Throwable) {
            issues += "manifest cannot be read: ${summarizeError(error)}"
            return null
        }
        val schema = properties.getProperty("schemaVersion")?.toIntOrNull()
        val version = properties.getProperty("resourcePackVersion")?.trim().orEmpty()
        val abi = properties.getProperty("abi")?.trim().orEmpty()
        val packId = properties.getProperty("packId")?.trim().orEmpty()
        val count = properties.getProperty("fileCount")?.toIntOrNull()
        if (schema == null || version.isEmpty() || abi.isEmpty() || packId.isEmpty() || count == null || count < 0) {
            issues += "manifest fields are incomplete"
            return null
        }
        if (count > MAX_ENTRY_COUNT) {
            issues += "manifest contains too many files: $count"
            return null
        }
        val files = ArrayList<ResourcePackFileRecord>(count)
        val seenPaths = LinkedHashSet<String>()
        for (index in 0 until count) {
            val path = properties.getProperty("file.$index.path")?.trim().orEmpty()
            val size = properties.getProperty("file.$index.size")?.toLongOrNull()
            val hash = properties.getProperty("file.$index.sha256")?.trim().orEmpty()
            if (path.isEmpty() ||
                (!path.startsWith("assets/") && !path.startsWith("lib/")) ||
                path.contains("../") ||
                !seenPaths.add(path) ||
                size == null ||
                size < 0L ||
                !isSha256(hash)
            ) {
                issues += "manifest entry $index is invalid"
                continue
            }
            files += ResourcePackFileRecord(path, size, hash.lowercase(Locale.ROOT))
        }
        return ResourcePackManifest(schema, version, abi, packId, files)
    }

    private fun resolveZipTarget(targetDir: File, entry: ZipEntry): File {
        val rawName = entry.name.replace('\\', '/')
        if (rawName.startsWith('/') || rawName.matches(Regex("^[A-Za-z]:/.*"))) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        if (rawName.isEmpty() || rawName.startsWith("../") || rawName.contains("/../")) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        val targetRoot = targetDir.canonicalFile.toPath()
        val targetFile = File(targetDir, rawName)
        if (!targetFile.canonicalFile.toPath().startsWith(targetRoot)) {
            throw IOException("Unsafe resource pack entry: ${entry.name}")
        }
        return targetFile
    }

    private fun prepareDirectory(directory: File) {
        val parent = directory.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Resource staging path is not a directory: ${directory.absolutePath}")
            }
            deleteTreeForCleanup(directory)
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }
    }

    private fun writeProperties(file: File, properties: Properties) {
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create metadata directory: ${parent.absolutePath}")
        }
        file.outputStream().use { output -> properties.store(output, null) }
    }

    internal fun isSha256(value: String): Boolean {
        return value.length == PACK_ID_LENGTH &&
            value.all { character -> character in "0123456789abcdefABCDEF" }
    }

    private fun limitIssues(issues: List<String>): List<String> {
        return issues.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_DIAGNOSTIC_ISSUES)
            .toList()
    }

    private fun summarizeError(error: Throwable): String {
        return error.message?.trim()?.takeIf(String::isNotEmpty) ?: error.javaClass.simpleName
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw IOException("External resource preparation cancelled")
        }
    }

    private fun reportProgress(callback: StartupProgressCallback?, percent: Int, message: String) {
        callback?.onProgress(percent.coerceIn(0, 100), message)
    }

    private fun deleteTreeForCleanup(file: File?) {
        if (file == null || !file.exists()) return
        val wasInterrupted = Thread.interrupted()
        try {
            FileTreeCleaner.deleteRecursively(file)
            if (file.exists()) FileTreeCleaner.deleteRecursively(file)
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
