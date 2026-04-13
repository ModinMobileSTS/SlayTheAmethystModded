package io.stamethyst.ui.main

import android.content.Context
import java.util.LinkedHashSet

internal object ModSuggestionReadStateStore {
    fun loadReadKeys(context: Context): Set<String> {
        return prefs(context)
            .getStringSet(KEY_READ_SUGGESTIONS, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun markRead(context: Context, readKey: String): Boolean {
        val normalizedKey = readKey.trim()
        if (normalizedKey.isEmpty()) {
            return false
        }

        val existing = loadReadKeys(context)
        if (existing.contains(normalizedKey)) {
            return false
        }

        val updated = LinkedHashSet(existing)
        updated.add(normalizedKey)
        prefs(context).edit()
            .putStringSet(KEY_READ_SUGGESTIONS, updated)
            .apply()
        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_MAIN_MOD_SUGGESTION_STATE, Context.MODE_PRIVATE)

    private const val PREFS_MAIN_MOD_SUGGESTION_STATE = "MainModSuggestionState"
    private const val KEY_READ_SUGGESTIONS = "read_suggestions"
}
