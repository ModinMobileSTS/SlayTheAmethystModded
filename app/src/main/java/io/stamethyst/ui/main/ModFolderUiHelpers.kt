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
        resolveLegacyInternalStorageCandidates(storage).forEach { keys.add(it) }
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
    val storage = mod.storagePath.trim()
    if (storage.isNotEmpty()) {
        return storage
    }
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

private fun resolveLegacyInternalStorageCandidates(storagePath: String): List<String> {
    val normalizedPath = storagePath.trim().replace('\\', '/')
    if (normalizedPath.isEmpty()) {
        return emptyList()
    }

    val packageMarker = "/Android/data/"
    val packageStart = normalizedPath.indexOf(packageMarker)
    if (packageStart < 0) {
        return emptyList()
    }
    val packageNameStart = packageStart + packageMarker.length
    val packageNameEnd = normalizedPath.indexOf("/files/", packageNameStart)
    if (packageNameEnd <= packageNameStart) {
        return emptyList()
    }

    val relativeMarker = "/files/sts/"
    val relativeStart = normalizedPath.indexOf(relativeMarker, packageNameEnd)
    if (relativeStart < 0) {
        return emptyList()
    }

    val packageName = normalizedPath.substring(packageNameStart, packageNameEnd).trim()
    if (packageName.isEmpty()) {
        return emptyList()
    }
    val relativePath = normalizedPath.substring(relativeStart + relativeMarker.length)
    if (relativePath.isEmpty()) {
        return emptyList()
    }

    val candidates = LinkedHashSet<String>()
    candidates.add("/data/user/0/$packageName/files/sts/$relativePath")
    candidates.add("/data/data/$packageName/files/sts/$relativePath")
    return candidates.toList()
}
