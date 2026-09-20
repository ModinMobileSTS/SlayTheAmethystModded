package io.stamethyst.backend.mods

import java.io.File

/**
 * Resolved launch order for an AI patch smoke test.
 *
 * [filesByModId] preserves launch order: built-in mods first, then the parent mod's dependency
 * closure, then the parent mod, then the patch mod. Values are deduplicated by canonical path.
 */
internal data class AgentPatchSmokeModSelection(
    val filesByModId: Map<String, File>,
    val unresolvedDependencies: List<String>,
) {
    val files: List<File>
        get() = filesByModId.values.toList()
}

/**
 * Pure mod-selection logic for the AI patch smoke test.
 *
 * A smoke test must not depend on the user's optional-mod selection: the patch is validated against
 * the parent mod, the parent mod's prerequisites, and the launcher's built-in mods only. This keeps
 * a smoke result reproducible and makes an unrelated enabled mod unable to cause the failure.
 */
internal object AgentPatchSmokeTestModList {
    fun resolve(
        builtInJars: Map<String, File>,
        requiredModIds: Set<String>,
        rootModIds: List<String>,
        explicitJars: Map<String, File>,
        dependenciesByModId: Map<String, List<String>>,
        jarByModId: Map<String, File>,
    ): AgentPatchSmokeModSelection {
        val required = requiredModIds.map(ModManager::normalizeModId).toHashSet()
        val resolved = LinkedHashMap<String, File>()
        val unresolved = LinkedHashSet<String>()

        fun add(modId: String, jar: File?) {
            val normalized = ModManager.normalizeModId(modId)
            if (normalized.isEmpty() || jar == null || !jar.isFile) return
            resolved.putIfAbsent(normalized, jar)
        }

        builtInJars.forEach { (modId, jar) -> add(modId, jar) }

        val visited = HashSet<String>()
        val queue = ArrayDeque<String>()
        rootModIds.map(ModManager::normalizeModId)
            .filter { it.isNotEmpty() }
            .forEach { id ->
                visited.add(id)
                queue.addLast(id)
            }
        while (queue.isNotEmpty()) {
            val modId = queue.removeFirst()
            val dependencies = dependenciesByModId[modId].orEmpty()
            dependencies.map(ModManager::normalizeModId)
                .filter { it.isNotEmpty() }
                .forEach { dependency ->
                    if (dependency in required) return@forEach
                    if (!visited.add(dependency)) return@forEach
                    val jar = jarByModId[dependency]
                    if (jar == null) {
                        unresolved.add(dependency)
                        return@forEach
                    }
                    add(dependency, jar)
                    queue.addLast(dependency)
                }
        }

        explicitJars.forEach { (modId, jar) -> add(modId, jar) }

        return AgentPatchSmokeModSelection(
            filesByModId = resolved,
            unresolvedDependencies = unresolved.toList(),
        )
    }
}
