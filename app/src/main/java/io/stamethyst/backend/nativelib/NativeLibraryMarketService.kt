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

object NativeLibraryMarketService {
    private const val METADATA_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlayTheAmethystResource/main/native/metadata.json"
    private const val FILE_DOWNLOAD_BASE_URL =
        "https://raw.githubusercontent.com/ModinMobileSTS/SlayTheAmethystResource/main/native/res/"
    private const val BUNDLED_NATIVE_ASSET_DIR = "components/bundled_runtime_natives"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val USER_AGENT = "SlayTheAmethyst-NativeMarket"

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
    ) {
        RuntimePaths.ensureBaseDirs(context)
        validateInstallable(context, entry)

        val stagingDir = File(
            context.cacheDir,
            "native-market-staging/${entry.id}.${System.nanoTime()}"
        )
        prepareCleanDirectory(stagingDir)
        try {
            entry.files.forEach { file ->
                GithubMirrorFallback.run(source) { candidate ->
                    downloadFile(
                        candidate.buildUrl(file.downloadUrl),
                        File(stagingDir, file.fileName)
                    )
                }
            }

            val packageDir = RuntimePaths.nativeMarketPackageDir(context, entry.id)
            prepareCleanDirectory(packageDir)
            stagingDir.listFiles()
                ?.filter(File::isFile)
                ?.sortedBy(File::getName)
                .orEmpty()
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

    private fun isPackageInstalled(context: Context, entry: NativeLibraryMarketCatalogEntry): Boolean {
        return entry.files.isNotEmpty() && entry.files.all { file ->
            val installedFile = File(RuntimePaths.nativeMarketPackageDir(context, entry.id), file.fileName)
            installedFile.isFile && installedFile.length() > 0L
        }
    }

    private fun isPackageBundled(context: Context, entry: NativeLibraryMarketCatalogEntry): Boolean {
        return entry.files.isNotEmpty() && entry.files.all { file ->
            hasBundledNativeAsset(context.assets, file.fileName) ||
                findFileByName(RuntimePaths.gdxPatchNativesDir(context), file.fileName)?.isFile == true
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
        if (isPackageBundled(context, entry)) {
            throw IOException("Native package ${entry.displayName} is already bundled with this build.")
        }

        val packagesRoot = RuntimePaths.nativeMarketPackagesDir(context)
        val installedOwners = LinkedHashSet<String>()
        packagesRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.filter { it.name != entry.id }
            ?.forEach { packageDir ->
                packageDir.listFiles()
                    ?.filter(File::isFile)
                    ?.forEach { installedOwners += "${it.name}|${packageDir.name}" }
            }

        entry.files.forEach { file ->
            val conflictingOwner = installedOwners.firstOrNull { owner ->
                owner.startsWith("${file.fileName}|")
            } ?: return@forEach
            val ownerPackageId = conflictingOwner.substringAfter('|')
            throw IOException(
                "Native file ${file.fileName} is already provided by installed package $ownerPackageId."
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
                packageDir.listFiles()
                    ?.filter(File::isFile)
                    ?.sortedBy(File::getName)
                    .orEmpty()
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
    private fun downloadFile(requestUrl: String, targetFile: File) {
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
                        input.copyTo(output)
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
