package io.stamethyst.ui.main

import io.stamethyst.model.ModItemUi
import java.io.File
import java.text.Collator

internal enum class ModSortOrder {
    NAME_ASCENDING,
    NAME_DESCENDING,
    MODIFIED_NEWEST_FIRST,
    MODIFIED_OLDEST_FIRST,
}

/** A one-time display-order replacement, not a persistent automatic-sort or load-priority rule. */
internal fun sortedModDisplayPaths(
    mods: List<ModItemUi>,
    order: ModSortOrder,
    lastModified: (ModItemUi) -> Long? = ::modFileLastModified,
): List<String> {
    val uniqueMods = mods.distinctBy { it.storagePath }
    val sorted = when (order) {
        ModSortOrder.NAME_ASCENDING, ModSortOrder.NAME_DESCENDING -> {
            val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
            val comparator = Comparator<ModItemUi> { first, second ->
                collator.compare(resolveModDisplayName(first), resolveModDisplayName(second))
            }
            uniqueMods.sortedWith(
                if (order == ModSortOrder.NAME_ASCENDING) comparator else comparator.reversed()
            )
        }
        ModSortOrder.MODIFIED_NEWEST_FIRST, ModSortOrder.MODIFIED_OLDEST_FIRST -> {
            val timestamps = uniqueMods.associate { it.storagePath to lastModified(it) }
            uniqueMods.sortedWith { first, second ->
                val a = timestamps[first.storagePath]
                val b = timestamps[second.storagePath]
                when {
                    a == null && b == null -> 0
                    a == null -> 1
                    b == null -> -1
                    order == ModSortOrder.MODIFIED_NEWEST_FIRST -> b.compareTo(a)
                    else -> a.compareTo(b)
                }
            }
        }
    }
    return sorted.map { it.storagePath }
}

private fun modFileLastModified(mod: ModItemUi): Long? = runCatching {
    val path = mod.workshop?.localJarPath?.takeIf { it.isNotBlank() } ?: mod.storagePath
    File(path).lastModified().takeIf { it > 0L }
}.getOrNull()
