package io.stamethyst.backend.mods

import android.content.Context
import io.stamethyst.backend.workshop.WorkshopJsonFileStore
import java.io.File
import org.json.JSONObject

/** Remaps saved and in-memory launch selections when an import replaces a jar's storage key. */
internal class ModSelectionReplacementStore(context: Context) {
    private val file = File(context.filesDir, "mod_selection_replacements.json")

    fun load(): Map<String, String> = WorkshopJsonFileStore.withFileLock(file, lock) { read() }

    fun record(sourcePath: String, targetPath: String) = WorkshopJsonFileStore.withFileLock(file, lock) {
        if (sourcePath == targetPath) return@withFileLock
        val replacements = read().toMutableMap()
        // Unique import filenames can be reused by a later update. The new target is now a
        // live key, not an alias, and older aliases should point straight to the latest jar.
        replacements.remove(targetPath)
        replacements.replaceAll { _, value -> if (value == sourcePath) targetPath else value }
        replacements[sourcePath] = targetPath
        WorkshopJsonFileStore.writeAtomically(file, JSONObject(replacements).toString())
    }

    private fun read(): Map<String, String> = WorkshopJsonFileStore.readJsonOrDefault(file, emptyMap()) { text ->
        val json = JSONObject(text)
        json.keys().asSequence().associateWith { json.getString(it) }
    }

    private companion object {
        val lock = Any()
    }
}

internal fun rebindReplacedModSelection(
    selection: Collection<String>,
    installedKeys: Set<String>,
    replacements: Map<String, String>,
): Set<String> = selection.mapTo(LinkedHashSet()) { key ->
    var current = key
    val visited = HashSet<String>()
    while (current !in installedKeys && visited.add(current)) {
        current = replacements[current] ?: break
    }
    current
}
