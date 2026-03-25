package io.stamethyst.ui.main

import io.stamethyst.config.RuntimePaths
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
        addAssignmentPathCandidates(keys, storage)
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

internal fun resolveModStoragePathCandidates(storagePath: String): List<String> {
    val candidates = LinkedHashSet<String>()
    addAssignmentPathCandidates(candidates, storagePath)
    return candidates.toList()
}

internal fun resolveExistingModStoragePath(
    storagePath: String,
    exists: (String) -> Boolean = { candidate -> File(candidate).isFile }
): String? {
    return resolveModStoragePathCandidates(storagePath).firstOrNull(exists)
}

private fun addAssignmentPathCandidates(
    keys: MutableSet<String>,
    storagePath: String
) {
    val normalizedStorage = storagePath.trim()
    if (normalizedStorage.isEmpty()) {
        return
    }
    keys.add(normalizedStorage)
    resolveSiblingOptionalModStorageCandidates(normalizedStorage).forEach { keys.add(it) }
    resolveLegacyInternalStorageCandidates(normalizedStorage).forEach { legacyPath ->
        keys.add(legacyPath)
        resolveSiblingOptionalModStorageCandidates(legacyPath).forEach { keys.add(it) }
    }
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

    val packageAndRelative = resolvePackageAndRelativePath(normalizedPath) ?: return emptyList()
    val (packageName, relativePath) = packageAndRelative
    return (
        RuntimePaths.legacyInternalStsRootCandidates(packageName) +
            RuntimePaths.legacyExternalStsRootCandidates(packageName)
        )
        .map { candidateRoot -> "$candidateRoot/$relativePath" }
        .filter { it.replace('\\', '/') != normalizedPath }
        .distinct()
}

private fun resolveSiblingOptionalModStorageCandidates(storagePath: String): List<String> {
    val normalizedPath = storagePath.trim().replace('\\', '/')
    if (normalizedPath.isEmpty()) {
        return emptyList()
    }

    val candidates = LinkedHashSet<String>()
    if (normalizedPath.contains(MODS_LIBRARY_MARKER)) {
        candidates.add(normalizedPath.replace(MODS_LIBRARY_MARKER, MODS_MARKER))
    }
    if (normalizedPath.contains(MODS_MARKER)) {
        candidates.add(normalizedPath.replace(MODS_MARKER, MODS_LIBRARY_MARKER))
    }
    candidates.remove(normalizedPath)
    return candidates.toList()
}

private fun resolvePackageAndRelativePath(normalizedPath: String): Pair<String, String>? {
    val externalPackageMarker = "/Android/data/"
    val externalPackageStart = normalizedPath.indexOf(externalPackageMarker)
    if (externalPackageStart >= 0) {
        val packageNameStart = externalPackageStart + externalPackageMarker.length
        val packageNameEnd = normalizedPath.indexOf("/files/", packageNameStart)
        if (packageNameEnd > packageNameStart) {
            val relativePath = extractRelativePath(normalizedPath, packageNameEnd)
            if (!relativePath.isNullOrEmpty()) {
                return normalizedPath.substring(packageNameStart, packageNameEnd).trim() to relativePath
            }
        }
    }

    val internalUserMarker = "/data/user/0/"
    val internalDataMarker = "/data/data/"
    val internalPackageStart = when {
        normalizedPath.startsWith(internalUserMarker) -> internalUserMarker.length
        normalizedPath.startsWith(internalDataMarker) -> internalDataMarker.length
        else -> -1
    }
    if (internalPackageStart < 0) {
        return null
    }
    val packageNameEnd = normalizedPath.indexOf("/files/", internalPackageStart)
    if (packageNameEnd <= internalPackageStart) {
        return null
    }
    val relativePath = extractRelativePath(normalizedPath, packageNameEnd) ?: return null
    return normalizedPath.substring(internalPackageStart, packageNameEnd).trim() to relativePath
}

private fun extractRelativePath(path: String, packageNameEnd: Int): String? {
    val relativeMarker = "/files/sts/"
    val relativeStart = path.indexOf(relativeMarker, packageNameEnd)
    if (relativeStart < 0) {
        return null
    }
    return path.substring(relativeStart + relativeMarker.length).takeIf { it.isNotEmpty() }
}

private const val MODS_MARKER = "/files/sts/mods/"
private const val MODS_LIBRARY_MARKER = "/files/sts/mods_library/"
