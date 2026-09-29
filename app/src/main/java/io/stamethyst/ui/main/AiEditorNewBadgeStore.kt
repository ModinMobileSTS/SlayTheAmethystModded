package io.stamethyst.ui.main

import android.content.Context
import androidx.compose.runtime.mutableStateOf

internal object AiEditorNewBadgeStore {
    private val hasSeenEditor = mutableStateOf<Boolean?>(null)

    fun shouldShow(context: Context): Boolean {
        val cached = hasSeenEditor.value
        if (cached != null) {
            return !cached
        }
        val seen = preferences(context).getBoolean(KEY_HAS_SEEN_EDITOR, false)
        hasSeenEditor.value = seen
        return !seen
    }

    fun markSeen(context: Context) {
        preferences(context).edit().putBoolean(KEY_HAS_SEEN_EDITOR, true).apply()
        hasSeenEditor.value = true
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "AiEditorDiscovery"
    private const val KEY_HAS_SEEN_EDITOR = "has_seen_editor"
}
