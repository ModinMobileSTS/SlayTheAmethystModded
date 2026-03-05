package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
import java.io.File

internal fun resolveAssignedFolderId(
    mod: ModItemUi,
    folderAssignments: Map<String, String>,
    validFolderIds: Set<String>
): String? {
    resolveAssignmentKeyCandidates(mod).forEach { candidate ->
        val folderId = folderAssignments[candidate]
        if (!folderId.isNullOrBlank() && validFolderIds.contains(folderId)) {
            return folderId
        }
    }
    return null
}

internal fun resolveAssignmentKeyCandidates(mod: ModItemUi): List<String> {
    val keys = LinkedHashSet<String>()
    val storage = mod.storagePath.trim()
    if (storage.isNotEmpty()) {
        keys.add(storage)
    }
    val storedId = resolveStoredOptionalModId(mod)
    if (!storedId.isNullOrBlank()) {
        keys.add(storedId)
    }
    val normalizedManifest = normalizeModId(mod.manifestModId)
    if (normalizedManifest.isNotEmpty()) {
        keys.add(normalizedManifest)
    }
    return keys.toList()
}

internal fun resolveStoredOptionalModId(mod: ModItemUi): String? {
    val normalizedModId = normalizeModId(mod.modId)
    if (normalizedModId.isNotEmpty()) {
        return normalizedModId
    }
    val normalizedManifest = normalizeModId(mod.manifestModId)
    return normalizedManifest.ifEmpty { null }
}

internal fun normalizeModId(raw: String?): String {
    return raw?.trim()?.lowercase().orEmpty()
}

internal fun resolveModDisplayName(mod: ModItemUi, showModFileName: Boolean = false): String {
    if (showModFileName) {
        val fromFile = resolveModFileNameWithoutJar(mod.storagePath)
        if (!fromFile.isNullOrBlank()) {
            return fromFile
        }
    }
    return mod.name.ifBlank {
        mod.manifestModId.ifBlank {
            mod.modId.ifBlank {
                "Unknown"
            }
        }
    }
}

internal fun resolveModFileNameWithoutJar(storagePath: String): String? {
    val path = storagePath.trim()
    if (path.isEmpty()) {
        return null
    }
    val fileName = File(path).name.trim()
    if (fileName.isEmpty()) {
        return null
    }
    return if (fileName.endsWith(".jar", ignoreCase = true)) {
        fileName.substring(0, fileName.length - 4).ifBlank { fileName }
    } else {
        fileName
    }
}
