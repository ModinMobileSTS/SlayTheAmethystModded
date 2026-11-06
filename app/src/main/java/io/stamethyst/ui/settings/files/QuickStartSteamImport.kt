package io.stamethyst.ui.settings.files

import android.content.Context
import io.stamethyst.backend.mods.StsJarValidator
import io.stamethyst.backend.steam.SteamStsJarDownloadService
import io.stamethyst.config.RuntimePaths
import java.io.File

/** The downloaded JAR is disposable only after the validated target has been committed. */
internal object QuickStartSteamImport {
    fun importDownloadedJar(
        context: Context,
        source: File,
        importJar: (File, File) -> Unit = { downloaded, target ->
            SettingsFileService.importFileToFileAtomically(downloaded, target, StsJarValidator::validate)
        },
        cleanupSource: () -> Boolean = { SteamStsJarDownloadService.clearQuickStartSteamCache(context) },
    ): Boolean {
        importJar(source, RuntimePaths.importedStsJar(context))
        // Prewarming/cloud sync only need the installed target. Cleanup failure must not
        // turn an already committed import into a failed download; the junk button retries.
        return runCatching(cleanupSource).getOrDefault(false)
    }
}
