package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.config.RuntimePaths
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import org.json.JSONObject
import org.json.JSONTokener

internal object ImportedModPatchRegistry {
    private const val JSON_KEY_ENTRIES = "entries"
    private const val JSON_KEY_VERSION = "version"
    private const val JSON_VERSION = 1
    private const val JSON_KEY_MOD_ID = "modId"
    private const val JSON_KEY_MOD_NAME = "modName"
    private const val JSON_KEY_PATCHED_ATLAS_ENTRIES = "patchedAtlasEntries"
    private const val JSON_KEY_PATCHED_FILTER_LINES = "patchedFilterLines"
    private const val JSON_KEY_DOWNSCALED_ATLAS_ENTRIES = "downscaledAtlasEntries"
    private const val JSON_KEY_DOWNSCALED_ATLAS_PAGE_ENTRIES = "downscaledAtlasPageEntries"
    private const val JSON_KEY_PATCHED_MANIFEST_ROOT_ENTRIES = "patchedManifestRootEntries"
    private const val JSON_KEY_PATCHED_MANIFEST_ROOT_PREFIX = "patchedManifestRootPrefix"
    private const val JSON_KEY_PATCHED_FRIEREN_ANTI_PIRATE_METHOD =
        "patchedFrierenAntiPirateMethod"
    private const val JSON_KEY_PATCHED_DOWNFALL_CLASS_ENTRIES = "patchedDownfallClassEntries"
    private const val JSON_KEY_PATCHED_DOWNFALL_MERCHANT_CLASS_ENTRIES =
        "patchedDownfallMerchantClassEntries"
    private const val JSON_KEY_PATCHED_DOWNFALL_HEXAGHOST_BODY_CLASS_ENTRIES =
        "patchedDownfallHexaghostBodyClassEntries"
    private const val JSON_KEY_PATCHED_DOWNFALL_BOSS_MECHANIC_PANEL_CLASS_ENTRIES =
        "patchedDownfallBossMechanicPanelClassEntries"
    private const val JSON_KEY_PATCHED_VUPSHION_WEB_BUTTON_CONSTRUCTOR =
        "patchedVupShionWebButtonConstructor"

    fun readAll(context: Context): Map<String, ImportedModPatchInfo> {
        val root = readRoot(storageFile(context)) ?: return emptyMap()
        val entriesObject = root.optJSONObject(JSON_KEY_ENTRIES) ?: return emptyMap()
        val result = LinkedHashMap<String, ImportedModPatchInfo>()
        val keys = entriesObject.keys()
        while (keys.hasNext()) {
            val rawStoragePath = keys.next()
            val normalizedStoragePath = normalizeStoragePath(context, rawStoragePath) ?: continue
            val json = entriesObject.optJSONObject(rawStoragePath) ?: continue
            val patchInfo = json.toImportedModPatchInfo()
            if (patchInfo.hasCompatibilityPatches) {
                result[normalizedStoragePath] = patchInfo
            }
        }
        return result
    }

    @Throws(IOException::class)
    fun put(context: Context, storagePath: String, patchInfo: ImportedModPatchInfo) {
        val normalizedStoragePath = normalizeStoragePath(context, storagePath) ?: return
        val entries = LinkedHashMap(readAll(context))
        if (patchInfo.hasCompatibilityPatches) {
            entries[normalizedStoragePath] = patchInfo
        } else {
            entries.remove(normalizedStoragePath)
        }
        writeAll(context, entries)
    }

    @Throws(IOException::class)
    fun remove(context: Context, storagePath: String) {
        val normalizedStoragePath = normalizeStoragePath(context, storagePath) ?: return
        val entries = LinkedHashMap(readAll(context))
        if (entries.remove(normalizedStoragePath) != null) {
            writeAll(context, entries)
        }
    }

    @Throws(IOException::class)
    fun rename(context: Context, oldStoragePath: String, newStoragePath: String) {
        val normalizedOldStoragePath = normalizeStoragePath(context, oldStoragePath) ?: return
        val normalizedNewStoragePath = normalizeStoragePath(context, newStoragePath) ?: return
        if (normalizedOldStoragePath == normalizedNewStoragePath) {
            return
        }
        val entries = LinkedHashMap(readAll(context))
        val patchInfo = entries.remove(normalizedOldStoragePath) ?: return
        entries[normalizedNewStoragePath] = patchInfo
        writeAll(context, entries)
    }

    @Throws(IOException::class)
    private fun writeAll(context: Context, entries: Map<String, ImportedModPatchInfo>) {
        val file = storageFile(context)
        if (entries.isEmpty()) {
            if (file.exists() && !file.delete()) {
                throw IOException("Failed to delete patch metadata file: ${file.absolutePath}")
            }
            return
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        val root = JSONObject()
        root.put(JSON_KEY_VERSION, JSON_VERSION)
        val entriesObject = JSONObject()
        entries.forEach { (storagePath, patchInfo) ->
            entriesObject.put(storagePath, patchInfo.toJson())
        }
        root.put(JSON_KEY_ENTRIES, entriesObject)
        writeJsonAtomically(file, root)
    }

    private fun readRoot(file: File): JSONObject? {
        if (!file.isFile) {
            return null
        }
        return try {
            val text = file.readText(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                JSONObject()
            } else {
                val parsed = JSONTokener(text).nextValue()
                parsed as? JSONObject
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Throws(IOException::class)
    private fun writeJsonAtomically(file: File, root: JSONObject) {
        val tempFile = File(
            file.parentFile ?: throw IOException("Patch metadata file has no parent directory"),
            ".${file.name}.${System.nanoTime()}.tmp"
        )
        try {
            FileOutputStream(tempFile, false).use { output ->
                output.write(root.toString(2).toByteArray(StandardCharsets.UTF_8))
                output.write('\n'.code)
                output.fd.sync()
            }
            if (file.exists() && !file.delete()) {
                throw IOException("Failed to replace patch metadata file: ${file.absolutePath}")
            }
            if (!tempFile.renameTo(file)) {
                throw IOException("Failed to move patch metadata file into place: ${file.absolutePath}")
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun JSONObject.toImportedModPatchInfo(): ImportedModPatchInfo {
        return ImportedModPatchInfo(
            modId = optString(JSON_KEY_MOD_ID).trim(),
            modName = optString(JSON_KEY_MOD_NAME).trim(),
            patchedAtlasEntries = optInt(JSON_KEY_PATCHED_ATLAS_ENTRIES),
            patchedFilterLines = optInt(JSON_KEY_PATCHED_FILTER_LINES),
            downscaledAtlasEntries = optInt(JSON_KEY_DOWNSCALED_ATLAS_ENTRIES),
            downscaledAtlasPageEntries = optInt(JSON_KEY_DOWNSCALED_ATLAS_PAGE_ENTRIES),
            patchedManifestRootEntries = optInt(JSON_KEY_PATCHED_MANIFEST_ROOT_ENTRIES),
            patchedManifestRootPrefix = optString(JSON_KEY_PATCHED_MANIFEST_ROOT_PREFIX).trim(),
            patchedFrierenAntiPirateMethod =
                optBoolean(JSON_KEY_PATCHED_FRIEREN_ANTI_PIRATE_METHOD, false),
            patchedDownfallClassEntries = optInt(JSON_KEY_PATCHED_DOWNFALL_CLASS_ENTRIES),
            patchedDownfallMerchantClassEntries =
                optInt(JSON_KEY_PATCHED_DOWNFALL_MERCHANT_CLASS_ENTRIES),
            patchedDownfallHexaghostBodyClassEntries =
                optInt(JSON_KEY_PATCHED_DOWNFALL_HEXAGHOST_BODY_CLASS_ENTRIES),
            patchedDownfallBossMechanicPanelClassEntries =
                optInt(JSON_KEY_PATCHED_DOWNFALL_BOSS_MECHANIC_PANEL_CLASS_ENTRIES),
            patchedVupShionWebButtonConstructor =
                optBoolean(JSON_KEY_PATCHED_VUPSHION_WEB_BUTTON_CONSTRUCTOR, false)
        )
    }

    private fun ImportedModPatchInfo.toJson(): JSONObject {
        return JSONObject().apply {
            put(JSON_KEY_MOD_ID, modId)
            put(JSON_KEY_MOD_NAME, modName)
            put(JSON_KEY_PATCHED_ATLAS_ENTRIES, patchedAtlasEntries)
            put(JSON_KEY_PATCHED_FILTER_LINES, patchedFilterLines)
            put(JSON_KEY_DOWNSCALED_ATLAS_ENTRIES, downscaledAtlasEntries)
            put(JSON_KEY_DOWNSCALED_ATLAS_PAGE_ENTRIES, downscaledAtlasPageEntries)
            put(JSON_KEY_PATCHED_MANIFEST_ROOT_ENTRIES, patchedManifestRootEntries)
            put(JSON_KEY_PATCHED_MANIFEST_ROOT_PREFIX, patchedManifestRootPrefix)
            put(
                JSON_KEY_PATCHED_FRIEREN_ANTI_PIRATE_METHOD,
                patchedFrierenAntiPirateMethod
            )
            put(JSON_KEY_PATCHED_DOWNFALL_CLASS_ENTRIES, patchedDownfallClassEntries)
            put(
                JSON_KEY_PATCHED_DOWNFALL_MERCHANT_CLASS_ENTRIES,
                patchedDownfallMerchantClassEntries
            )
            put(
                JSON_KEY_PATCHED_DOWNFALL_HEXAGHOST_BODY_CLASS_ENTRIES,
                patchedDownfallHexaghostBodyClassEntries
            )
            put(
                JSON_KEY_PATCHED_DOWNFALL_BOSS_MECHANIC_PANEL_CLASS_ENTRIES,
                patchedDownfallBossMechanicPanelClassEntries
            )
            put(
                JSON_KEY_PATCHED_VUPSHION_WEB_BUTTON_CONSTRUCTOR,
                patchedVupShionWebButtonConstructor
            )
        }
    }

    private fun normalizeStoragePath(context: Context, rawStoragePath: String?): String? {
        val normalizedStoragePath = RuntimePaths.normalizeLegacyStsPath(context, rawStoragePath)
            ?.trim()
            .orEmpty()
        return normalizedStoragePath.ifEmpty { null }
    }

    private fun storageFile(context: Context): File {
        return RuntimePaths.importedModPatchMetadataFile(context)
    }
}
