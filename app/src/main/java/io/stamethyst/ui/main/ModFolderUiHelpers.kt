package io.stamethyst.ui.main

import io.stamethyst.config.RuntimePaths
import io.stamethyst.model.ModItemUi
import java.io.File
import java.security.MessageDigest

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

internal fun resolveModSuggestionText(
    mod: ModItemUi,
    suggestions: Map<String, String>
): String? {
    if (suggestions.isEmpty()) {
        return null
    }
    val candidateIds = LinkedHashSet<String>()
    val normalizedManifest = normalizeModId(mod.manifestModId)
    if (normalizedManifest.isNotEmpty()) {
        candidateIds.add(normalizedManifest)
    }
    val normalizedModId = normalizeModId(mod.modId)
    if (normalizedModId.isNotEmpty()) {
        candidateIds.add(normalizedModId)
    }
    candidateIds.forEach { candidateId ->
        val suggestion = suggestions[candidateId]?.trim()
        if (!suggestion.isNullOrEmpty()) {
            return suggestion
        }
    }
    return null
}

internal fun resolveModSuggestionReadKey(mod: ModItemUi, suggestionText: String): String? {
    val normalizedSuggestion = suggestionText.trim()
    if (normalizedSuggestion.isEmpty()) {
        return null
    }
    val suggestionIdentity = resolveModSuggestionIdentity(mod) ?: return null
    return "$suggestionIdentity|${sha256Hex(normalizedSuggestion)}"
}

internal fun collectEnabledUnreadSuggestionModDisplayNames(
    mods: List<ModItemUi>,
    suggestions: Map<String, String>,
    readSuggestionKeys: Set<String>,
    showModFileName: Boolean = false
): List<String> {
    if (mods.isEmpty() || suggestions.isEmpty()) {
        return emptyList()
    }
    return mods.mapNotNull { mod ->
        if (!mod.enabled) {
            return@mapNotNull null
        }
        val suggestionText = resolveModSuggestionText(mod, suggestions) ?: return@mapNotNull null
        val readKey = resolveModSuggestionReadKey(mod, suggestionText)
        if (readKey != null && readSuggestionKeys.contains(readKey)) {
            return@mapNotNull null
        }
        resolveModDisplayName(mod, showModFileName = showModFileName)
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

private fun resolveModSuggestionIdentity(mod: ModItemUi): String? {
    val normalizedManifest = normalizeModId(mod.manifestModId)
    if (normalizedManifest.isNotEmpty()) {
        return "manifest:$normalizedManifest"
    }

    val normalizedModId = normalizeModId(mod.modId)
    if (normalizedModId.isNotEmpty()) {
        return "mod:$normalizedModId"
    }

    val normalizedStoragePath = mod.storagePath.trim().replace('\\', '/')
    if (normalizedStoragePath.isNotEmpty()) {
        return "path:$normalizedStoragePath"
    }
    return null
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val chars = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        chars[index * 2] = HEX_DIGITS[unsigned ushr 4]
        chars[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0f]
    }
    return String(chars)
}

private val HEX_DIGITS = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
)

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
