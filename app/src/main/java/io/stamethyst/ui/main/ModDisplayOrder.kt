package io.stamethyst.ui.main

import android.content.Context
import android.content.SharedPreferences
import io.stamethyst.backend.mods.resolveReplacedModKey
import org.json.JSONArray

/** Presentation order only: never changes the game's load priority. */
internal class ModDisplayOrder(context: Context) {
    private val preferences = context.getSharedPreferences("mod_display_order", Context.MODE_PRIVATE)

    fun read(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("paths", "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    fun save(paths: List<String>) {
        preferences.edit().putString("paths", JSONArray(paths).toString()).apply()
    }

    fun rebindInstalledPaths(installedKeys: Set<String>, replacements: Map<String, String>) {
        if (replacements.isEmpty()) return
        val previous = read()
        val updated = rebindModDisplayOrder(previous, installedKeys, replacements)
        if (updated != previous) save(updated)
    }

    fun observe(onChange: (List<String>) -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "paths") onChange(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onChange(read())
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

internal fun rebindModDisplayOrder(
    order: List<String>,
    installedKeys: Set<String>,
    replacements: Map<String, String>,
): List<String> {
    return order.map { key ->
        resolveReplacedModKey(key, installedKeys, replacements).takeIf(installedKeys::contains) ?: key
    }.distinct()
}

internal fun reorderModPaths(order: List<String>, selected: Set<String>, target: String, after: Boolean): List<String> {
    if (target in selected || target !in order) return order
    val moving = order.filter { it in selected }
    if (moving.isEmpty()) return order
    val remaining = order.filterNot { it in selected }.toMutableList()
    remaining.addAll(remaining.indexOf(target) + if (after) 1 else 0, moving)
    return remaining
}
