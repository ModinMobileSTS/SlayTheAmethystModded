package io.stamethyst.ui.main

internal object NewlyImportedModHighlightStore {
    private val storagePaths = LinkedHashSet<String>()

    @Synchronized
    fun mark(paths: Collection<String>): Boolean {
        var changed = false
        paths.forEach { path ->
            val normalized = path.trim()
            if (normalized.isNotEmpty() && storagePaths.add(normalized)) {
                changed = true
            }
        }
        return changed
    }

    @Synchronized
    fun contains(path: String): Boolean {
        return storagePaths.any { storedPath -> matchesModStoragePathCandidate(storedPath, path) }
    }

    @Synchronized
    fun clear(path: String): Boolean {
        val normalized = path.trim()
        if (normalized.isEmpty()) {
            return false
        }
        val matching = storagePaths.filter { storedPath -> matchesModStoragePathCandidate(storedPath, normalized) }
        return storagePaths.removeAll(matching.toSet())
    }

    @Synchronized
    fun clearAll(): Boolean {
        if (storagePaths.isEmpty()) {
            return false
        }
        storagePaths.clear()
        return true
    }
}
