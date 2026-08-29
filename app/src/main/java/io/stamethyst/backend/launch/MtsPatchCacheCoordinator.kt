package io.stamethyst.backend.launch

import android.content.Context
import io.stamethyst.backend.fs.FileTreeCleaner
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.ZipFile

internal object MtsPatchCacheCoordinator {
    private const val MIN_CACHE_JAR_BYTES = 1024L * 1024L
    private val SEPARATOR_BYTE = byteArrayOf('|'.code.toByte())
    private const val PROPERTY_ENABLED = "amethyst.mts.patch_cache.enabled"
    private const val PROPERTY_CURRENT = "amethyst.mts.patch_cache.current"
    private const val PROPERTY_JAR = "amethyst.mts.patch_cache.jar"
    private const val PROPERTY_BASE_JAR = "amethyst.mts.patch_cache.base_jar"
    private const val PROPERTY_MARKER = "amethyst.mts.patch_cache.marker"
    private const val PROPERTY_PACKAGE_DIR = "amethyst.mts.patch_cache.package_dir"
    private const val PROPERTY_EXPECTED = "amethyst.mts.patch_cache.expected"
    private const val PROPERTY_GAME_DIR = "amethyst.mts.patch_cache.game_dir"
    private const val FINGERPRINT_POOL_CAP = 4

    @JvmStatic
    fun expectedMarker(context: Context): String = buildCacheMarkerValue(
        desktopJar = RuntimePaths.importedStsJar(context),
        mtsJar = RuntimePaths.importedMtsJar(context),
        baseModJar = RuntimePaths.importedBaseModJar(context),
        stsLibJar = RuntimePaths.importedStsLibJar(context),
        bootBridgeJar = RuntimePaths.bootBridgeJar(context),
        gdxPatchJar = RuntimePaths.gdxPatchJar(context),
        modFileList = RuntimePaths.mtsModFileList(context),
        bundledMods = listOf(
            RuntimePaths.importedAmethystRuntimeCompatJar(context),
            RuntimePaths.importedAmethystFloatingToolsJar(context),
            RuntimePaths.importedRamSaverJar(context)
        ),
        gdxPatchDigestCache = RuntimePaths.mtsPatchCacheGdxPatchDigestCache(context)
    )

    @JvmStatic
    fun isCacheCurrent(context: Context): Boolean {
        return isCacheCurrent(
            markerFile = RuntimePaths.mtsPatchCacheMarker(context),
            cachedJar = RuntimePaths.mtsPatchCacheJar(context),
            packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
            expectedMarker = expectedMarker(context)
        )
    }

    @JvmStatic
    fun invalidate(context: Context) {
        RuntimePaths.mtsPatchCacheMarker(context).delete()
    }

    @JvmStatic
    fun clear(context: Context) {
        deleteCacheFiles(RuntimePaths.knownMtsPatchCacheArtifacts(context))
    }

    @Throws(IOException::class)
    fun appendRuntimeProperties(context: Context, args: MutableList<String>, enabled: Boolean) {
        val expectedMarker = if (enabled) expectedMarker(context) else ""
        appendRuntimeProperties(
            args = args,
            enabled = enabled,
            cacheCurrent = enabled && isCacheCurrent(
                markerFile = RuntimePaths.mtsPatchCacheMarker(context),
                cachedJar = RuntimePaths.mtsPatchCacheJar(context),
                packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
                expectedMarker = expectedMarker
            ),
            cachedJar = RuntimePaths.mtsPatchCacheJar(context),
            baseJar = RuntimePaths.importedStsJar(context),
            markerFile = RuntimePaths.mtsPatchCacheMarker(context),
            packageDir = RuntimePaths.mtsPatchCachePackageDir(context),
            expectedMarker = expectedMarker,
            gameDir = RuntimePaths.stsRoot(context)
        )
    }

    internal fun appendRuntimeProperties(
        args: MutableList<String>,
        enabled: Boolean,
        cacheCurrent: Boolean,
        cachedJar: File,
        baseJar: File,
        markerFile: File,
        packageDir: File,
        expectedMarker: String,
        gameDir: File
    ) {
        args.add("-D$PROPERTY_ENABLED=$enabled")
        args.add("-D$PROPERTY_CURRENT=$cacheCurrent")
        args.add("-D$PROPERTY_JAR=${cachedJar.absolutePath}")
        args.add("-D$PROPERTY_BASE_JAR=${baseJar.absolutePath}")
        args.add("-D$PROPERTY_MARKER=${markerFile.absolutePath}")
        args.add("-D$PROPERTY_PACKAGE_DIR=${packageDir.absolutePath}")
        args.add("-D$PROPERTY_EXPECTED=$expectedMarker")
        args.add("-D$PROPERTY_GAME_DIR=${gameDir.absolutePath}")
    }

    internal fun isCacheCurrent(
        markerFile: File,
        cachedJar: File,
        packageDir: File,
        expectedMarker: String
    ): Boolean {
        if (expectedMarker.isEmpty() || !cachedJar.isFile || cachedJar.length() < MIN_CACHE_JAR_BYTES) {
            return false
        }
        if (!hasPackageJars(packageDir)) {
            return false
        }
        val actualMarker = try {
            markerFile.takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
                ?.trim()
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
        return actualMarker == expectedMarker
    }

    private fun hasPackageJars(packageDir: File): Boolean {
        val files = packageDir.listFiles() ?: return false
        return files.any { file ->
            file.isFile && file.name.endsWith(".jar", ignoreCase = true) && file.length() > 0L
        }
    }

    private fun deleteCacheFiles(files: List<File>) {
        files.forEach { file ->
            runCatching {
                if (file.isDirectory) {
                    FileTreeCleaner.deleteRecursively(file)
                } else {
                    file.delete()
                }
            }
        }
    }

    internal fun buildCacheMarkerValue(
        desktopJar: File,
        mtsJar: File,
        baseModJar: File,
        stsLibJar: File,
        bootBridgeJar: File,
        gdxPatchJar: File,
        modFileList: File,
        bundledMods: List<File> = emptyList(),
        gdxPatchDigestCache: File? = null
    ): String {
        val coreLines = listOf(
            "schema|9",
            jarFingerprint("desktop", desktopJar),
            jarFingerprint("modthespire", mtsJar),
            jarFingerprint("basemod", baseModJar),
            jarFingerprint("stslib", stsLibJar),
            jarFingerprint("bootbridge", bootBridgeJar),
            persistedFileFingerprint("gdxpatch", gdxPatchJar, gdxPatchDigestCache),
            textFileFingerprint("mod_file_list", modFileList)
        )
        val labelledModFiles = readModFiles(modFileList).mapIndexed { index, modFile ->
            "mod[$index]" to modFile
        } + bundledMods.mapIndexed { index, modFile ->
            "bundled[$index]" to modFile
        }
        val modFingerprints = fingerprintInParallel(labelledModFiles)
        return sha256((coreLines + modFingerprints).joinToString(separator = "\n"))
    }

    /**
     * Fingerprints every jar on a small fixed pool and returns the results in input order.
     *
     * The marker is computed on the launch path before the game process can spawn, and its
     * per-jar cost is an open plus a central-directory seek against flash storage, so the
     * serial fan-out over dozens of enabled mods was storage-bound wall time. Work is bound
     * by storage as much as by CPU, so the pool stays small; the cap mirrors the cache
     * build's package-jar pool.
     *
     * Order is part of the marker value: results are reassembled in input order regardless
     * of completion order, so the digest stays deterministic across launches.
     */
    private fun fingerprintInParallel(labelledFiles: List<Pair<String, File>>): List<String> {
        if (labelledFiles.isEmpty()) {
            return emptyList()
        }
        val threadCount = minOf(Runtime.getRuntime().availableProcessors(), FINGERPRINT_POOL_CAP, labelledFiles.size)
        if (threadCount <= 1) {
            return labelledFiles.map { (label, file) -> jarFingerprint(label, file) }
        }
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            return labelledFiles.map { (label, file) ->
                pool.submit(Callable { jarFingerprint(label, file) })
            }.map { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun readModFiles(modFileList: File): List<File> {
        if (!modFileList.isFile) {
            return emptyList()
        }
        return try {
            modFileList.readLines(StandardCharsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map(::File)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Fingerprints a jar by its central directory rather than by mtime.
     *
     * Size and mtime alone let a jar that was rebuilt in place — same size, mtime
     * preserved or reset by a copy — pass as unchanged, which yields a stale cache hit
     * against mod bytecode that no longer exists. Hashing the whole file would be
     * correct but has to read every byte of every mod on each launch, which cancels out
     * the cache hit it is protecting.
     *
     * The central directory is the middle ground: it already stores a per-entry CRC32
     * that the writer computed over the entry's uncompressed bytes, so any content
     * change moves it. Reading it costs a few KB of seeks instead of the whole archive.
     *
     * Falls back to size and mtime when the file is not a readable zip, so non-jar or
     * corrupt entries still contribute something rather than silently collapsing to a
     * constant.
     */
    private fun jarFingerprint(label: String, file: File): String {
        if (!file.isFile) {
            return "$label|${file.absolutePath}|-1|-1"
        }
        val entryDigest = try {
            ZipFile(file).use { zip ->
                val digest = MessageDigest.getInstance("SHA-256")
                // Enumeration order follows the central directory, which is stable for a
                // given archive, so no extra sort is needed to keep this deterministic.
                for (entry in zip.entries()) {
                    digest.update(entry.name.toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                    digest.update(entry.size.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                    digest.update(entry.crc.toString().toByteArray(StandardCharsets.UTF_8))
                    digest.update(SEPARATOR_BYTE)
                }
                digestToHex(digest.digest())
            }
        } catch (_: Throwable) {
            null
        }
        if (entryDigest == null) {
            return "$label|${file.absolutePath}|${file.length()}|${file.lastModified()}|nozip"
        }
        return "$label|${file.absolutePath}|${file.length()}|$entryDigest"
    }

    /**
     * Full-content digest of a launcher-shipped component jar, recorded in a sidecar so a
     * launch does not re-read every byte of the patch jar.
     *
     * The marker deliberately avoids trusting size plus mtime for user mod jars: a mod
     * rebuilt in place can keep both, which would produce a stale cache hit over mod
     * bytecode that no longer exists. The GDX patch jar is a different trust domain — it
     * ships with the launcher and is replaced wholesale by the component installer rather
     * than edited in place — so its size and mtime identify the installed artifact well
     * enough to reuse a previously computed digest. Any mismatch recomputes and re-records;
     * a missing or corrupt sidecar degrades to the old full read with no correctness change.
     */
    private fun persistedFileFingerprint(label: String, file: File, digestCache: File?): String {
        if (!file.isFile) {
            return "$label|${file.absolutePath}|-1|missing"
        }
        val identity = "${file.length()}|${file.lastModified()}"
        val recorded = digestCache?.let { readRecordedDigest(it, identity) }
        if (recorded != null) {
            return "$label|${file.absolutePath}|${file.length()}|$recorded"
        }
        val digest = fullFileSha256(file)
        if (digest != null && digestCache != null) {
            writeRecordedDigest(digestCache, "$identity|$digest")
        }
        return "$label|${file.absolutePath}|${file.length()}|${digest ?: "unreadable"}"
    }

    private fun readRecordedDigest(digestCache: File, identity: String): String? {
        return try {
            val text = digestCache.takeIf(File::isFile)
                ?.readText(StandardCharsets.UTF_8)
                ?.trim()
                .orEmpty()
            // The recorded line is "<size>|<mtime>|<digest>" and identity already contains
            // the first two fields joined by '|', so match the prefix instead of splitting:
            // a split would also cut the identity apart and never compare equal.
            val prefix = "$identity|"
            if (!text.startsWith(prefix)) {
                return null
            }
            // The digest is a full SHA-256 hex string; anything else is a corrupt or foreign
            // sidecar and is treated as absent.
            val digest = text.substring(prefix.length)
            if (digest.length == 64) digest else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeRecordedDigest(digestCache: File, line: String) {
        try {
            val parent = digestCache.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                return
            }
            val tempFile = File(parent, digestCache.name + ".tmp")
            tempFile.writeText(line, StandardCharsets.UTF_8)
            if (!tempFile.renameTo(digestCache)) {
                tempFile.delete()
            }
        } catch (_: Throwable) {
            // Best effort. Losing the sidecar only costs one extra full read on the next launch.
        }
    }

    private fun fullFileSha256(file: File): String? {
        return try {
            val messageDigest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    messageDigest.update(buffer, 0, count)
                }
            }
            digestToHex(messageDigest.digest())
        } catch (_: Throwable) {
            null
        }
    }

    private fun textFileFingerprint(label: String, file: File): String {
        val exists = file.isFile
        val length = if (exists) file.length() else -1L
        val contentHash = if (exists) {
            try {
                sha256(file.readText(StandardCharsets.UTF_8))
            } catch (_: Throwable) {
                "unreadable"
            }
        } else {
            "missing"
        }
        return "$label|${file.absolutePath}|$length|$contentHash"
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .let(::digestToHex)

    private fun digestToHex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
