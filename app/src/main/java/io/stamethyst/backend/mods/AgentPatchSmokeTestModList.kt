package io.stamethyst.backend.mods

import java.io.File

/**
 * Resolved launch set for an AI patch smoke test.
 *
 * [filesByModId] preserves launch order: built-in mods first, then the dependency closure of the
 * always-loaded roots, then the roots themselves, then any mod the agent selected. Values are
 * deduplicated by mod id.
 */
internal data class AgentPatchSmokeModSelection(
    val filesByModId: Map<String, File>,
    /** Dependencies that are referenced but not installed, so the run cannot be reproduced. */
    val unresolvedDependencies: List<String>,
    /** Mods the agent asked for that the launcher does not have installed. */
    val unknownSelectedModIds: List<String> = emptyList(),
) {
    val files: List<File>
        get() = filesByModId.values.toList()
}

/**
 * Pure mod-selection logic for the AI patch smoke test.
 *
 * The always-loaded set is the parent mod, the patch mod under test, the parent mod's prerequisites,
 * and the launcher's built-in mods, so the baseline never depends on the user's optional-mod
 * selection and an unrelated enabled mod cannot cause the failure.
 *
 * On top of that baseline the agent may request extra optional mods with [selectedModIds], which is
 * how a patch that is meant to work alongside another mod is verified together with it. A selected
 * mod brings its own dependencies and a mod the launcher does not have is reported through
 * [AgentPatchSmokeModSelection.unknownSelectedModIds] instead of being silently ignored.
 */
internal object AgentPatchSmokeTestModList {
    fun resolve(
        builtInJars: Map<String, File>,
        requiredModIds: Set<String>,
        rootModIds: List<String>,
        rootJars: Map<String, File>,
        dependenciesByModId: Map<String, List<String>>,
        jarByModId: Map<String, File>,
        selectedModIds: Collection<String> = emptyList(),
    ): AgentPatchSmokeModSelection {
        val required = requiredModIds.map(ModManager::normalizeModId).toHashSet()
        val resolved = LinkedHashMap<String, File>()
        val unresolved = LinkedHashSet<String>()
        val unknownSelected = LinkedHashSet<String>()

        fun add(modId: String, jar: File?) {
            val normalized = ModManager.normalizeModId(modId)
            if (normalized.isEmpty() || jar == null || !jar.isFile) return
            resolved.putIfAbsent(normalized, jar)
        }

        fun normalize(list: Collection<String>): List<String> = list
            .map(ModManager::normalizeModId)
            .filter { it.isNotEmpty() }

        /** Adds every dependency of [roots] that the launcher can supply, dependencies first. */
        fun addDependencyClosure(roots: Collection<String>) {
            val visited = HashSet(roots)
            val queue = ArrayDeque(roots)
            while (queue.isNotEmpty()) {
                val modId = queue.removeFirst()
                normalize(dependenciesByModId[modId].orEmpty()).forEach { dependency ->
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
        }

        builtInJars.forEach { (modId, jar) -> add(modId, jar) }

        // Roots are the always-loaded mods plus anything given an explicit jar, so the patch mod
        // participates even when the caller lists only the parent in [rootModIds].
        val rootJarByModId = rootJars.entries.associate { ModManager.normalizeModId(it.key) to it.value }
        val rootOrder = LinkedHashSet<String>()
        rootOrder.addAll(normalize(rootModIds))
        rootOrder.addAll(normalize(rootJars.keys))

        addDependencyClosure(rootOrder)

        // The parent and the patch have no installed-list entry of their own (the patch never does),
        // so their jars are supplied explicitly.
        rootOrder.forEach { modId ->
            add(modId, rootJarByModId[modId] ?: jarByModId[modId])
        }

        // Built-in mods are always loaded, so selecting one is a no-op rather than an error.
        val selected = normalize(selectedModIds).filterNot { it in required }
        val selectedRoots = ArrayList<String>()
        selected.forEach { modId ->
            val jar = jarByModId[modId]
            if (jar != null && jar.isFile) {
                add(modId, jar)
                selectedRoots.add(modId)
            } else {
                unknownSelected.add(modId)
            }
        }
        addDependencyClosure(selectedRoots)

        return AgentPatchSmokeModSelection(
            filesByModId = resolved,
            unresolvedDependencies = unresolved.toList(),
            unknownSelectedModIds = unknownSelected.toList(),
        )
    }
}
