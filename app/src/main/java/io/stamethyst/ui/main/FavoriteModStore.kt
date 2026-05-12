package io.stamethyst.ui.main

import android.content.Context
import java.util.LinkedHashSet

internal object FavoriteModStore {
    fun loadFavoriteKeys(context: Context): Set<String> {
        return prefs(context)
            .getStringSet(KEY_FAVORITE_MODS, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun setFavorite(context: Context, favoriteKey: String, favorite: Boolean): Boolean {
        val normalizedKey = favoriteKey.trim()
        if (normalizedKey.isEmpty()) {
            return false
        }

        val existing = loadFavoriteKeys(context)
        val alreadyFavorite = existing.contains(normalizedKey)
        if (alreadyFavorite == favorite) {
            return false
        }

        val updated = LinkedHashSet(existing)
        if (favorite) {
            updated.add(normalizedKey)
        } else {
            updated.remove(normalizedKey)
        }
        prefs(context).edit()
            .putStringSet(KEY_FAVORITE_MODS, updated)
            .apply()
        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_MAIN_MOD_FAVORITES, Context.MODE_PRIVATE)

    private const val PREFS_MAIN_MOD_FAVORITES = "MainModFavorites"
    private const val KEY_FAVORITE_MODS = "favorite_mods"
}
