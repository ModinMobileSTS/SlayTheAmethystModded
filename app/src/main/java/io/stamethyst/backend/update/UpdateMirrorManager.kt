package io.stamethyst.backend.update

import android.content.Context
import io.stamethyst.ui.preferences.LauncherPreferences

object UpdateMirrorManager {
    fun current(context: Context): UpdateSource {
        return UpdateSource.normalizePreferredUserSource(
            LauncherPreferences.readPreferredUpdateMirrorId(context)
        )
    }

    fun selectableSources(): List<UpdateSource> {
        return UpdateSource.userSelectableSources()
    }

    fun saveCurrent(context: Context, source: UpdateSource) {
        if (!source.userSelectable) {
            return
        }
        LauncherPreferences.savePreferredUpdateMirrorId(context, source.id)
    }

    fun displayNameOf(sourceId: String?): String? {
        return UpdateSource.fromPersistedValue(sourceId)?.displayName
    }
}
