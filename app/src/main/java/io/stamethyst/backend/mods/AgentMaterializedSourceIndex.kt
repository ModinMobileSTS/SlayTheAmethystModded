package io.stamethyst.backend.mods

import java.io.File

/** The source tree is launcher-owned; rebuild the index on a cold snapshot, then count new paths. */
internal class AgentMaterializedSourceIndex(
    private val maxSnapshots: Int = 8,
    private val maxPaths: Int = 32_768,
    private val scan: (File) -> Set<String> = { root ->
        root.walkTopDown().filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
    },
) {
    private data class Key(val root: String, val hash: String, val fileKey: String)
    private val snapshots = LinkedHashMap<Key, MutableSet<String>>(8, 0.75f, true)

    @Synchronized
    fun invalidate(root: File) {
        val path = root.canonicalPath
        snapshots.keys.removeAll { it.root == path }
    }

    @Synchronized
    fun count(root: File, hash: String, materializedFile: File): Int {
        val canonicalRoot = root.canonicalFile
        val identity = AgentSourceFileIdentity.read(canonicalRoot)
        val key = identity?.takeIf { it.kind == "directory" }?.let { Key(it.path, hash, it.fileKey) }
        val paths = key?.let(snapshots::get) ?: scan(canonicalRoot).toMutableSet()
        val relative = materializedFile.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
        if (materializedFile.isFile) paths.add(relative) else paths.remove(relative)
        if (key != null && paths.size <= maxPaths) {
            snapshots[key] = paths
            while (snapshots.size > maxSnapshots || snapshots.values.sumOf { it.size } > maxPaths) {
                val oldest = snapshots.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        } else if (key != null) {
            snapshots.remove(key)
        }
        return paths.size
    }

    companion object {
        val shared = AgentMaterializedSourceIndex()
    }
}
