package io.stamethyst.backend.mods

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.Locale

internal data class ModManifestNameRewriteResult(
    val manifestEntryName: String,
    val previousName: String,
    val newName: String,
    val changed: Boolean
)

internal object ModManifestNameRewriter {
    private const val MANIFEST_FILE_NAME = "ModTheSpire.json"
    private const val DEFAULT_NAME_KEY = "name"

    private data class ManifestEntryPayload(
        val entryName: String,
        val bytes: ByteArray
    )

    @Throws(IOException::class)
    fun rewriteNameInPlace(
        modJar: File,
        requestedName: String,
        onRewriteProgress: ((Long) -> Unit)? = null
    ): ModManifestNameRewriteResult {
        if (!modJar.isFile) {
            throw IOException("Mod jar not found: ${modJar.absolutePath}")
        }
        val newName = requestedName.trim()
        if (newName.isEmpty()) {
            throw IOException("Mod name cannot be empty")
        }
        if (newName.contains('/') || newName.contains('\\')) {
            throw IOException("Mod name cannot contain path separators")
        }

        val payload = readManifestEntry(modJar)
        val rawJson = payload.bytes.toString(StandardCharsets.UTF_8)
        val root = try {
            JSONTokener(rawJson).nextValue()
        } catch (error: JSONException) {
            throw IOException("ModTheSpire.json is not valid JSON", error)
        }
        val manifestObject = resolveManifestObject(root)
        val existingKey = findNameKey(manifestObject)
        val targetKey = existingKey ?: DEFAULT_NAME_KEY
        val previousName = existingKey
            ?.let { stringifyManifestName(manifestObject.opt(it)) }
            .orEmpty()
            .trim()

        if (previousName == newName) {
            return ModManifestNameRewriteResult(
                manifestEntryName = payload.entryName,
                previousName = previousName,
                newName = newName,
                changed = false
            )
        }

        manifestObject.put(targetKey, newName)
        val updatedJson = when (root) {
            is JSONObject -> root.toString(2)
            is JSONArray -> root.toString(2)
            else -> throw IOException("ModTheSpire.json does not contain a mod object")
        }.trimEnd().plus("\n")
        rewriteJarEntry(
            modJar = modJar,
            targetEntryName = payload.entryName,
            replacementBytes = updatedJson.toByteArray(StandardCharsets.UTF_8),
            onRewriteProgress = onRewriteProgress
        )
        ModJarManifestParser.clearCacheFor(modJar)
        return ModManifestNameRewriteResult(
            manifestEntryName = payload.entryName,
            previousName = previousName,
            newName = newName,
            changed = true
        )
    }

    @Throws(IOException::class)
    private fun readManifestEntry(modJar: File): ManifestEntryPayload {
        ZipArchiveInputStream(BufferedInputStream(FileInputStream(modJar))).use { zipInput ->
            while (true) {
                val entry = zipInput.nextZipEntry ?: break
                if (!entry.isDirectory && isManifestEntry(entry.name)) {
                    return ManifestEntryPayload(
                        entryName = entry.name,
                        bytes = JarFileIoUtils.readAll(zipInput)
                    )
                }
            }
        }
        throw IOException("ModTheSpire.json not found in ${modJar.name}")
    }

    @Throws(IOException::class)
    private fun resolveManifestObject(root: Any?): JSONObject {
        if (root is JSONObject) {
            return root
        }
        if (root is JSONArray) {
            for (index in 0 until root.length()) {
                val item = root.opt(index)
                if (item is JSONObject) {
                    return item
                }
            }
        }
        throw IOException("ModTheSpire.json does not contain a mod object")
    }

    private fun findNameKey(manifestObject: JSONObject): String? {
        MOD_NAME_JSON_KEYS.forEach { key ->
            if (manifestObject.has(key)) {
                return key
            }
        }
        MOD_NAME_JSON_KEYS.forEach { key ->
            val matchedKey = findJsonKeyIgnoreCase(manifestObject, key)
            if (!matchedKey.isNullOrEmpty()) {
                return matchedKey
            }
        }
        return null
    }

    private fun findJsonKeyIgnoreCase(obj: JSONObject, key: String): String? {
        val iterator = obj.keys()
        while (iterator.hasNext()) {
            val current = iterator.next()
            if (current != null && current.equals(key, ignoreCase = true)) {
                return current
            }
        }
        return null
    }

    private fun stringifyManifestName(value: Any?): String {
        if (value == null || value === JSONObject.NULL || value is JSONObject) {
            return ""
        }
        if (value is JSONArray) {
            val parts = ArrayList<String>()
            for (index in 0 until value.length()) {
                val text = stringifyManifestName(value.opt(index)).trim()
                if (text.isNotEmpty()) {
                    parts.add(text)
                }
            }
            return parts.joinToString(", ")
        }
        return value.toString()
    }

    @Throws(IOException::class)
    private fun rewriteJarEntry(
        modJar: File,
        targetEntryName: String,
        replacementBytes: ByteArray,
        onRewriteProgress: ((Long) -> Unit)?
    ) {
        val previousLastModified = modJar.lastModified()
        val sourceLength = modJar.length().coerceAtLeast(0L)
        val tempJar = File(modJar.absolutePath + ".manifestname.tmp")
        val writtenEntries = LinkedHashSet<String>()
        var replaced = false
        try {
            val countingInput = CountingInputStream(FileInputStream(modJar))
            ZipArchiveInputStream(BufferedInputStream(countingInput)).use { zipInput ->
                FileOutputStream(tempJar, false).use { outputStream ->
                    ZipArchiveOutputStream(outputStream).use { zipOut ->
                        onRewriteProgress?.invoke(0L)
                        while (true) {
                            val entry = zipInput.nextZipEntry ?: break
                            val entryName = entry.name ?: continue
                            if (!writtenEntries.add(entryName)) {
                                continue
                            }

                            val outEntry = ZipArchiveEntry(entryName)
                            if (entry.time > 0L) {
                                outEntry.time = entry.time
                            }
                            zipOut.putArchiveEntry(outEntry)
                            if (!entry.isDirectory) {
                                if (!replaced && entryName == targetEntryName) {
                                    zipOut.write(replacementBytes)
                                    replaced = true
                                    onRewriteProgress?.invoke(countingInput.bytesRead.coerceAtMost(sourceLength))
                                } else {
                                    copyZipEntryWithProgress(zipInput, zipOut) {
                                        onRewriteProgress?.invoke(countingInput.bytesRead.coerceAtMost(sourceLength))
                                    }
                                }
                            }
                            zipOut.closeArchiveEntry()
                        }
                        onRewriteProgress?.invoke(sourceLength)
                    }
                }
            }

            if (!replaced) {
                throw IOException("Failed to rewrite $targetEntryName in ${modJar.name}")
            }
            JarFileIoUtils.moveFileReplacing(tempJar, modJar)
            modJar.setLastModified(maxOf(System.currentTimeMillis(), previousLastModified + 2000L))
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun copyZipEntryWithProgress(
        input: InputStream,
        output: OutputStream,
        onChunkCopied: () -> Unit
    ) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
            onChunkCopied()
        }
    }

    private fun isManifestEntry(entryName: String?): Boolean {
        val normalizedName = normalizeEntryName(entryName)
        if (normalizedName.isEmpty()) {
            return false
        }
        return normalizedName.lowercase(Locale.ROOT)
            .endsWith(MANIFEST_FILE_NAME.lowercase(Locale.ROOT))
    }

    private fun normalizeEntryName(entryName: String?): String {
        if (entryName == null) {
            return ""
        }
        var normalized = entryName.replace('\\', '/').trim()
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1)
        }
        return normalized
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) {
                bytesRead = bytesRead.saturatingAdd(1L)
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) {
                bytesRead = bytesRead.saturatingAdd(count.toLong())
            }
            return count
        }

        private fun Long.saturatingAdd(other: Long): Long {
            return if (this > Long.MAX_VALUE - other) {
                Long.MAX_VALUE
            } else {
                this + other
            }
        }
    }
}
