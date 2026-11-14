package io.stamethyst.backend.mods

import java.io.File

/** Serializes validation/overlay publication; hot calls only stat cached candidates and outputs. */
internal class AgentCompileClasspathCache(
    private val maxDirectories: Int = 8,
    private val maxEntries: Int = 128,
    private val list: (File) -> List<File>? = { it.listFiles()?.toList() },
) {
    private data class Key(val namespace: String, val identity: AgentSourceFileIdentity)
    private data class Validated(val file: File, val identity: AgentSourceFileIdentity)
    private val directories = LinkedHashMap<AgentSourceFileIdentity, List<File>>(8, 0.75f, true)
    private val validated = LinkedHashMap<Key, Validated>(16, 0.75f, true)

    @Synchronized
    fun resolve(
        namespace: String,
        gameJars: List<File>,
        requiredMods: File,
        parentJar: File?,
        validate: (File) -> File?,
    ): CompileClasspathResolution {
        val directoryIdentity = AgentSourceFileIdentity.read(requiredMods)
        val required = directoryIdentity?.let(directories::get) ?: run {
            // A missing directory is known empty. null from an existing directory is a read
            // failure (for example FD exhaustion), not a successful empty listing to remember.
            val listed = if (directoryIdentity?.kind == "missing") emptyList() else list(requiredMods)
            listed?.filter { it.extension.equals("jar", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                ?.also { candidates ->
                    check(directoryIdentity == null || directoryIdentity == AgentSourceFileIdentity.read(requiredMods)) {
                        "Required mods changed while listing the compile classpath."
                    }
                    if (directoryIdentity != null) {
                        directories[directoryIdentity] = candidates
                        trim(directories, maxDirectories)
                    }
                }.orEmpty()
        }
        val candidates = LinkedHashSet<File>()
        candidates.addAll(gameJars)
        candidates.addAll(required)
        if (parentJar != null) candidates.add(parentJar)
        val entries = ArrayList<File>()
        val unindexable = ArrayList<File>()
        for (jar in candidates) {
            val before = AgentSourceFileIdentity.read(jar)
            if (before?.kind != "file" && !jar.isFile) continue
            val key = before?.takeIf { it.kind == "file" }?.let { Key(namespace, it) }
            val hit = key?.let(validated::get)?.takeIf { cached ->
                cached.identity == if (cached.file == jar) before else AgentSourceFileIdentity.read(cached.file)
            }
            if (hit != null) {
                entries.add(hit.file)
                continue
            }
            if (key != null) validated.remove(key)
            val resolved = validate(jar)
            check(before == null || before == AgentSourceFileIdentity.read(jar)) {
                "Compile classpath archive changed during validation: ${jar.name}"
            }
            if (resolved == null) {
                unindexable.add(jar)
            } else {
                entries.add(resolved)
                val output = if (resolved == jar) before else AgentSourceFileIdentity.read(resolved)
                if (key != null && output?.kind == "file") {
                    validated[key] = Validated(resolved, output)
                    trim(validated, maxEntries)
                }
            }
        }
        return CompileClasspathResolution(entries, unindexable)
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>, limit: Int) {
        while (map.size > limit) {
            val oldest = map.entries.iterator()
            oldest.next()
            oldest.remove()
        }
    }
}
