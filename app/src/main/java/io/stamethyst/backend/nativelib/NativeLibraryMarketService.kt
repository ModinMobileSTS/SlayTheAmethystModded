package io.stamethyst.backend.nativelib

import android.content.Context
import android.content.res.AssetManager
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.backend.update.GithubMirrorFallback
import io.stamethyst.backend.update.UpdateSource
import io.stamethyst.config.RuntimePaths
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONTokener

data class NativeLibraryMarketCatalogFile(
    val fileName: String,
    val downloadUrl: String
)

data class NativeLibraryMarketCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val files: List<NativeLibraryMarketCatalogFile>
)

enum class NativeLibraryMarketAvailability {
    NOT_INSTALLED,
    INSTALLED,
    BUNDLED
}

data class NativeLibraryMarketPackageState(
    val catalogEntry: NativeLibraryMarketCatalogEntry,
    val availability: NativeLibraryMarketAvailability
)

data class NativeLibraryMarketInstallProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Long,
    val progressPercent: Int?
)

object NativeLibraryMarketService {
    private const val METADATA_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlayTheAmethystResource/main/native/metadata.json"
    private const val FILE_DOWNLOAD_BASE_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlayTheAmethystResource/main/native/res/"
    private const val BUNDLED_NATIVE_ASSET_DIR = "components/bundled_runtime_natives"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "SlayTheAmethyst-NativeMarket"
    private const val DOWNLOAD_PROGRESS_REPORT_INTERVAL_NS = 200_000_000L
    private const val DOWNLOAD_PROGRESS_REPORT_STEP_BYTES = 64L * 1024L

    fun fetchCatalog(source: UpdateSource): List<NativeLibraryMarketCatalogEntry> {
        return GithubMirrorFallback.run(source) { candidate ->
            parseCatalog(requestText(candidate.buildUrl(METADATA_URL)))
        }.value
    }

    internal fun parseCatalog(responseText: String): List<NativeLibraryMarketCatalogEntry> {
        val parsed = runCatching { JSONTokener(responseText).nextValue() as? JSONArray }.getOrNull()
            ?: return emptyList()
        val entries = ArrayList<NativeLibraryMarketCatalogEntry>()
        val seenIds = LinkedHashSet<String>()
        for (index in 0 until parsed.length()) {
            val item = parsed.optJSONObject(index) ?: continue
            val filesJson = item.optJSONArray("files") ?: item.optJSONArray("card_files") ?: JSONArray()
            val files = parseFiles(filesJson)
            if (files.isEmpty()) {
                continue
            }
            val displayName = firstNonBlank(
                item.optString("name").trim(),
                item.optString("card_name").trim(),
                files.first().fileName
            )
            val entryId = buildPackageId(
                explicitId = firstNonBlank(
                    item.optString("id").trim(),
                    item.optString("package_id").trim()
                ),
                displayName = displayName,
                files = files
            )
            if (!seenIds.add(entryId)) {
                continue
            }
            entries += NativeLibraryMarketCatalogEntry(
                id = entryId,
                displayName = displayName,
                description = item.optString("description").trim(),
                files = files
            )
        }
        return entries
    }

    fun resolvePackageStates(
        context: Context,
        catalog: List<NativeLibraryMarketCatalogEntry>
    ): List<NativeLibraryMarketPackageState> {
        return catalog.map { entry ->
            NativeLibraryMarketPackageState(
                catalogEntry = entry,
                availability = when {
                    isPackageInstalled(context, entry) -> NativeLibraryMarketAvailability.INSTALLED
                    isPackageBundled(context, entry) -> NativeLibraryMarketAvailability.BUNDLED
                    else -> NativeLibraryMarketAvailability.NOT_INSTALLED
                }
            )
        }
    }

    @Throws(IOException::class)
    fun installPackage(
        context: Context,
        entry: NativeLibraryMarketCatalogEntry,
        source: UpdateSource,
        progressCallback: ((NativeLibraryMarketInstallProgress) -> Unit)? = null,
    ) {
        RuntimePaths.ensureBaseDirs(context)
        validateInstallable(context, entry)

        val stagingDir = File(
            context.cacheDir,
            "native-market-staging/${entry.id}.${System.nanoTime()}"
        )
        prepareCleanDirectory(stagingDir)
        try {
            val downloadedArtifactsDir = File(stagingDir, "downloads")
            prepareCleanDirectory(downloadedArtifactsDir)
            val expandedLibrariesDir = File(stagingDir, "expanded")
            prepareCleanDirectory(expandedLibrariesDir)
            val cachedDownloads = LinkedHashMap<String, File>()
            entry.files.forEachIndexed { index, file ->
                val downloadIndex = index + 1
                val downloadedArtifact = GithubMirrorFallback.run(source) { candidate ->
                    val requestUrl = candidate.buildUrl(file.downloadUrl)
                    cachedDownloads[requestUrl]?.takeIf(File::isFile)?.let { return@run it }
                    val downloadedFile = File(
                        downloadedArtifactsDir,
                        buildDownloadedArtifactFileName(downloadIndex, file.fileName)
                    )
                    downloadFile(
                        requestUrl,
                        downloadedFile,
                        fileName = file.fileName,
                        fileIndex = downloadIndex,
                        fileCount = entry.files.size,
                        progressCallback = progressCallback
                    )
                    cachedDownloads[requestUrl] = downloadedFile
                    downloadedFile
                }.value
                installDownloadedArtifact(
                    downloadedArtifact = downloadedArtifact,
                    requestedFileName = file.fileName,
                    targetDir = expandedLibrariesDir
                )
                reportInstallProgress(
                    progressCallback = progressCallback,
                    fileName = file.fileName,
                    fileIndex = downloadIndex,
                    fileCount = entry.files.size,
                    downloadedBytes = downloadedArtifact.length().coerceAtLeast(0L),
                    totalBytes = downloadedArtifact.length().takeIf { it > 0L },
                    elapsedNanos = 1L
                )
            }

            val stagedLibraries = collectSharedLibraryFiles(expandedLibrariesDir)
            validateExpandedLibraries(context, entry, stagedLibraries)

            val packageDir = RuntimePaths.nativeMarketPackageDir(context, entry.id)
            prepareCleanDirectory(packageDir)
            stagedLibraries
                .sortedBy(File::getName)
                .forEach { source ->
                    moveOrCopyFile(source, File(packageDir, source.name))
                }

            rebuildActiveDirectory(context)
        } finally {
            FileTreeCleaner.deleteRecursively(stagingDir)
        }
    }

    @Throws(IOException::class)
    fun uninstallPackage(context: Context, packageId: String) {
        FileTreeCleaner.deleteRecursively(RuntimePaths.nativeMarketPackageDir(context, packageId))
        rebuildActiveDirectory(context)
    }

    private fun parseFiles(filesJson: JSONArray): List<NativeLibraryMarketCatalogFile> {
        val files = ArrayList<NativeLibraryMarketCatalogFile>()
        val seenFileNames = LinkedHashSet<String>()
        for (fileIndex in 0 until filesJson.length()) {
            val fileObject = filesJson.optJSONObject(fileIndex) ?: continue
            val rawFileName = firstNonBlank(
                fileObject.optString("file_name").trim(),
                fileObject.optString("fileName").trim(),
                fileObject.optString("name").trim()
            )
            val sanitizedFileName = sanitizeFileName(rawFileName) ?: continue
            if (!seenFileNames.add(sanitizedFileName)) {
                continue
            }
            val explicitUrl = firstNonBlank(
                fileObject.optString("download_url").trim(),
                fileObject.optString("downloadUrl").trim(),
                fileObject.optString("url").trim()
            )
            files += NativeLibraryMarketCatalogFile(
                fileName = sanitizedFileName,
                downloadUrl = explicitUrl.ifBlank { buildDefaultDownloadUrl(sanitizedFileName) }
            )
        }
        return files
    }

    private fun buildPackageId(
        explicitId: String,
        displayName: String,
        files: List<NativeLibraryMarketCatalogFile>
    ): String {
        val normalizedExplicit = slugify(explicitId)
        if (normalizedExplicit.isNotEmpty()) {
            return normalizedExplicit
        }
        val fileDerived = files
            .joinToString(separator = "-") { slugify(File(it.fileName).nameWithoutExtension) }
            .trim('-')
        if (fileDerived.isNotEmpty()) {
            return fileDerived.take(96)
        }
        return slugify(displayName).ifBlank { "native-package" }
    }

    private fun slugify(value: String): String {
        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun sanitizeFileName(value: String): String? {
        if (value.isBlank()) {
            return null
        }
        val fileName = File(value).name.trim()
        if (fileName.isBlank() || fileName != value.trim()) {
            return null
        }
        return fileName
    }

    private fun buildDefaultDownloadUrl(fileName: String): String {
        return FILE_DOWNLOAD_BASE_URL + URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
    }

    private fun firstNonBlank(vararg values: String): String {
        values.forEach { value ->
            if (value.isNotBlank()) {
                return value
            }
        }
        return ""
    }

    private fun expectedSharedLibraryNames(entry: NativeLibraryMarketCatalogEntry): List<String> {
        return entry.files
            .map(NativeLibraryMarketCatalogFile::fileName)
            .filter(::isSharedLibraryFileName)
    }

    private fun isSharedLibraryFileName(fileName: String): Boolean {
        val normalized = fileName.trim()
        return Regex(""".+\.so(?:\..+)?$""", RegexOption.IGNORE_CASE).matches(normalized)
    }

    private fun buildDownloadedArtifactFileName(index: Int, fileName: String): String {
        return "${index.toString().padStart(2, '0')}-${fileName}"
    }

    private fun collectSharedLibraryFiles(root: File): List<File> {
        if (!root.isDirectory) {
            return emptyList()
        }
        val files = ArrayList<File>()
        root.walkTopDown()
            .filter(File::isFile)
            .filter { file -> isSharedLibraryFileName(file.name) }
            .forEach(files::add)
        return files
    }

    @Throws(IOException::class)
    private fun validateExpandedLibraries(
        context: Context,
        entry: NativeLibraryMarketCatalogEntry,
        stagedLibraries: List<File>
    ) {
        if (stagedLibraries.isEmpty()) {
            throw IOException("Native package ${entry.displayName} did not produce any shared libraries.")
        }

        val stagedNames = LinkedHashSet<String>()
        stagedLibraries.forEach { library ->
            if (!stagedNames.add(library.name)) {
                throw IOException("Native package ${entry.displayName} produced duplicate file ${library.name}.")
            }
            if (hasBundledNativeAsset(context.assets, library.name) ||
                findFileByName(RuntimePaths.gdxPatchNativesDir(context), library.name)?.isFile == true
            ) {
                throw IOException("Native file ${library.name} is already bundled with this build.")
            }
        }

        RuntimePaths.nativeMarketPackagesDir(context).listFiles()
            ?.filter(File::isDirectory)
            ?.filter { it.name != entry.id }
            ?.forEach { packageDir ->
                collectSharedLibraryFiles(packageDir).forEach { installedFile ->
                    if (stagedNames.contains(installedFile.name)) {
                        throw IOException(
                            "Native file ${installedFile.name} is already provided by installed package ${packageDir.name}."
                        )
                    }
                }
            }
    }

    @Throws(IOException::class)
    private fun installDownloadedArtifact(
        downloadedArtifact: File,
        requestedFileName: String,
        targetDir: File
    ) {
        if (isZipArchive(downloadedArtifact)) {
            extractSharedLibrariesFromZip(downloadedArtifact, requestedFileName, targetDir)
            return
        }

        if (!isSharedLibraryFileName(requestedFileName)) {
            throw IOException(
                "Downloaded native artifact $requestedFileName is neither a shared library nor a zip archive."
            )
        }

        val targetFile = File(targetDir, requestedFileName)
        ensureCanWriteInstalledFile(targetFile)
        copyFile(downloadedArtifact, targetFile)
    }

    internal fun isZipArchive(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) {
            return false
        }
        return FileInputStream(file).use { input ->
            val signature = ByteArray(4)
            val read = input.read(signature)
            read == 4 &&
                signature[0] == 0x50.toByte() &&
                signature[1] == 0x4B.toByte() &&
                (
                    (signature[2] == 0x03.toByte() && signature[3] == 0x04.toByte()) ||
                        (signature[2] == 0x05.toByte() && signature[3] == 0x06.toByte()) ||
                        (signature[2] == 0x07.toByte() && signature[3] == 0x08.toByte())
                    )
        }
    }

    @Throws(IOException::class)
    internal fun extractSharedLibrariesFromZip(
        archiveFile: File,
        requestedFileName: String,
        targetDir: File
    ): List<File> {
        val extracted = ArrayList<File>()
        ZipFile(archiveFile).use { zipFile ->
            val sharedLibraryEntries = ArrayList<ZipEntry>()
            val enumeration = zipFile.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (!entry.isDirectory && isSharedLibraryFileName(File(entry.name).name)) {
                    sharedLibraryEntries += entry
                }
            }
            if (sharedLibraryEntries.isEmpty()) {
                throw IOException("Zip archive ${archiveFile.name} does not contain any shared libraries.")
            }

            if (isSharedLibraryFileName(requestedFileName)) {
                val matchingEntries = sharedLibraryEntries.filter { entry ->
                    File(entry.name).name == requestedFileName
                }
                if (matchingEntries.isEmpty()) {
                    throw IOException(
                        "Zip archive ${archiveFile.name} does not contain required native file $requestedFileName."
                    )
                }
                if (matchingEntries.size > 1) {
                    throw IOException(
                        "Zip archive ${archiveFile.name} contains duplicate native file $requestedFileName."
                    )
                }
                extracted += extractZipEntryToFile(
                    zipFile = zipFile,
                    entry = matchingEntries.first(),
                    targetFile = File(targetDir, requestedFileName)
                )
                return extracted
            }

            val seenNames = LinkedHashSet<String>()
            sharedLibraryEntries.forEach { entry ->
                val libraryName = File(entry.name).name
                if (!seenNames.add(libraryName)) {
                    throw IOException("Zip archive ${archiveFile.name} contains duplicate native file $libraryName.")
                }
                extracted += extractZipEntryToFile(
                    zipFile = zipFile,
                    entry = entry,
                    targetFile = File(targetDir, libraryName)
                )
            }
        }
        return extracted
    }

    @Throws(IOException::class)
    private fun extractZipEntryToFile(
        zipFile: ZipFile,
        entry: ZipEntry,
        targetFile: File
    ): File {
        ensureCanWriteInstalledFile(targetFile)
        zipFile.getInputStream(entry).use { input ->
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        targetFile.setReadable(true, false)
        targetFile.setWritable(true, true)
        targetFile.setExecutable(true, false)
        return targetFile
    }

    @Throws(IOException::class)
    private fun ensureCanWriteInstalledFile(targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Failed to replace file: ${targetFile.absolutePath}")
        }
    }

    private fun isPackageInstalled(context: Context, entry: NativeLibraryMarketCatalogEntry): Boolean {
        val installedLibraries = collectSharedLibraryFiles(RuntimePaths.nativeMarketPackageDir(context, entry.id))
        if (installedLibraries.isEmpty()) {
            return false
        }
        val expectedLibraries = expectedSharedLibraryNames(entry)
        return if (expectedLibraries.isNotEmpty()) {
            val installedNames = installedLibraries.map(File::getName).toSet()
            expectedLibraries.all(installedNames::contains)
        } else {
            true
        }
    }

    private fun isPackageBundled(context: Context, entry: NativeLibraryMarketCatalogEntry): Boolean {
        val expectedLibraries = expectedSharedLibraryNames(entry)
        if (expectedLibraries.isEmpty()) {
            return false
        }
        return expectedLibraries.all { fileName ->
            hasBundledNativeAsset(context.assets, fileName) ||
                findFileByName(RuntimePaths.gdxPatchNativesDir(context), fileName)?.isFile == true
        }
    }

    private fun hasBundledNativeAsset(assets: AssetManager, fileName: String): Boolean {
        return assetTreeContainsFile(assets, BUNDLED_NATIVE_ASSET_DIR, fileName)
    }

    private fun assetTreeContainsFile(
        assets: AssetManager,
        assetPath: String,
        targetFileName: String
    ): Boolean {
        val children = try {
            assets.list(assetPath)
        } catch (_: IOException) {
            return false
        } ?: return false

        if (children.isEmpty()) {
            return File(assetPath).name == targetFileName
        }

        children.forEach { childName ->
            if (childName == targetFileName) {
                return true
            }
            if (assetTreeContainsFile(assets, "$assetPath/$childName", targetFileName)) {
                return true
            }
        }
        return false
    }

    private fun findFileByName(root: File, fileName: String): File? {
        if (!root.exists()) {
            return null
        }
        if (root.isFile) {
            return root.takeIf { it.name == fileName }
        }
        root.listFiles()?.forEach { child ->
            val found = findFileByName(child, fileName)
            if (found != null) {
                return found
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun validateInstallable(context: Context, entry: NativeLibraryMarketCatalogEntry) {
        if (entry.files.isEmpty()) {
            throw IOException("Native package ${entry.displayName} does not contain any files.")
        }
        val expectedLibraries = expectedSharedLibraryNames(entry)
        if (expectedLibraries.isNotEmpty() && isPackageBundled(context, entry)) {
            throw IOException("Native package ${entry.displayName} is already bundled with this build.")
        }

        val packagesRoot = RuntimePaths.nativeMarketPackagesDir(context)
        val installedOwners = LinkedHashSet<String>()
        packagesRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.filter { it.name != entry.id }
            ?.forEach { packageDir ->
                collectSharedLibraryFiles(packageDir)
                    .forEach { installedOwners += "${it.name}|${packageDir.name}" }
            }

        expectedLibraries.forEach { fileName ->
            val conflictingOwner = installedOwners.firstOrNull { owner ->
                owner.startsWith("$fileName|")
            } ?: return@forEach
            val ownerPackageId = conflictingOwner.substringAfter('|')
            throw IOException(
                "Native file $fileName is already provided by installed package $ownerPackageId."
            )
        }
    }

    @Throws(IOException::class)
    private fun rebuildActiveDirectory(context: Context) {
        val activeDir = RuntimePaths.nativeMarketActiveDir(context)
        prepareCleanDirectory(activeDir)

        val seenNames = LinkedHashSet<String>()
        RuntimePaths.nativeMarketPackagesDir(context).listFiles()
            ?.filter(File::isDirectory)
            ?.sortedBy(File::getName)
            .orEmpty()
            .forEach { packageDir ->
                collectSharedLibraryFiles(packageDir)
                    .sortedBy(File::getName)
                    .forEach { source ->
                        if (!seenNames.add(source.name)) {
                            throw IOException(
                                "Duplicate native file ${source.name} found while rebuilding active directory."
                            )
                        }
                        copyFile(source, File(activeDir, source.name))
                    }
            }
    }

    @Throws(IOException::class)
    private fun requestText(requestUrl: String): String {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            BufferedInputStream(connection.inputStream).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
                return output.toString(StandardCharsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun downloadFile(
        requestUrl: String,
        targetFile: File,
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
        progressCallback: ((NativeLibraryMarketInstallProgress) -> Unit)?
    ) {
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode")
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            val parent = targetFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create directory: ${parent.absolutePath}")
            }
            val tempFile = File(
                parent ?: targetFile.parentFile ?: targetFile.absoluteFile.parentFile ?: targetFile.parentFile,
                "${targetFile.name}.part"
            )
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    try {
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        val startTimeNanos = System.nanoTime()
                        var lastReportNanos = startTimeNanos
                        var lastReportBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            if (read == 0) {
                                continue
                            }
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            val nowNanos = System.nanoTime()
                            val reachedEnd = totalBytes != null && downloadedBytes >= totalBytes
                            val shouldReport =
                                reachedEnd ||
                                    nowNanos - lastReportNanos >= DOWNLOAD_PROGRESS_REPORT_INTERVAL_NS ||
                                    downloadedBytes - lastReportBytes >= DOWNLOAD_PROGRESS_REPORT_STEP_BYTES
                            if (shouldReport) {
                                reportInstallProgress(
                                    progressCallback = progressCallback,
                                    fileName = fileName,
                                    fileIndex = fileIndex,
                                    fileCount = fileCount,
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    elapsedNanos = nowNanos - startTimeNanos
                                )
                                lastReportNanos = nowNanos
                                lastReportBytes = downloadedBytes
                            }
                        }
                        reportInstallProgress(
                            progressCallback = progressCallback,
                            fileName = fileName,
                            fileIndex = fileIndex,
                            fileCount = fileCount,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            elapsedNanos = System.nanoTime() - startTimeNanos
                        )
                    } catch (error: Throwable) {
                        tempFile.delete()
                        throw error
                    }
                }
            }
            if (targetFile.exists() && !targetFile.delete()) {
                tempFile.delete()
                throw IOException("Failed to replace file: ${targetFile.absolutePath}")
            }
            if (!tempFile.renameTo(targetFile)) {
                copyFile(tempFile, targetFile)
                tempFile.delete()
            }
            targetFile.setReadable(true, false)
            targetFile.setWritable(true, true)
            targetFile.setExecutable(true, false)
        } finally {
            connection.disconnect()
        }
    }

    internal fun computeInstallProgressPercent(
        fileIndex: Int,
        fileCount: Int,
        downloadedBytes: Long,
        totalBytes: Long?
    ): Int? {
        if (fileCount <= 0 || fileIndex !in 1..fileCount) {
            return null
        }
        val safeTotalBytes = totalBytes?.takeIf { it > 0L } ?: return null
        val safeDownloadedBytes = downloadedBytes.coerceIn(0L, safeTotalBytes)
        val currentFilePercent = ((safeDownloadedBytes * 100L) / safeTotalBytes).coerceIn(0L, 100L)
        return ((((fileIndex - 1) * 100L) + currentFilePercent) / fileCount).toInt()
    }

    internal fun formatTransferBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = safeBytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "$safeBytes ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    private fun reportInstallProgress(
        progressCallback: ((NativeLibraryMarketInstallProgress) -> Unit)?,
        fileName: String,
        fileIndex: Int,
        fileCount: Int,
        downloadedBytes: Long,
        totalBytes: Long?,
        elapsedNanos: Long
    ) {
        val safeElapsedNanos = elapsedNanos.coerceAtLeast(1L)
        val bytesPerSecond = ((downloadedBytes * 1_000_000_000L) / safeElapsedNanos).coerceAtLeast(0L)
        progressCallback?.invoke(
            NativeLibraryMarketInstallProgress(
                fileName = fileName,
                fileIndex = fileIndex,
                fileCount = fileCount,
                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                totalBytes = totalBytes,
                bytesPerSecond = bytesPerSecond,
                progressPercent = computeInstallProgressPercent(
                    fileIndex = fileIndex,
                    fileCount = fileCount,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes
                )
            )
        )
    }

    @Throws(IOException::class)
    private fun prepareCleanDirectory(directory: File) {
        val parent = directory.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileTreeCleaner.deleteRecursively(directory)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }
    }

    @Throws(IOException::class)
    private fun moveOrCopyFile(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (!source.renameTo(target)) {
            copyFile(source, target)
        }
    }

    @Throws(IOException::class)
    private fun copyFile(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
            }
        }
        target.setLastModified(source.lastModified())
        target.setReadable(true, false)
        target.setWritable(true, true)
        target.setExecutable(true, false)
    }
}
