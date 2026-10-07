package io.stamethyst.backend.steamcloud

import android.content.Context
import android.content.SharedPreferences
import io.stamethyst.config.LauncherConfig
import io.stamethyst.config.RuntimePaths
import io.stamethyst.config.SteamCloudSaveMode
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Uncached, independently locked control state. Disabling sync never waits for network RPCs. */
internal object SteamCloudControlStore {
    @Serializable data class State(
        val disabled: Boolean = false,
        val mode: String = SteamCloudSaveMode.DEFAULT.persistedValue,
        val backgroundLaunchRequested: Boolean = false,
        val independentSwitchPending: Boolean = false,
        val pendingSteamId: String = "",
        val blacklist: Set<String> = LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS,
    )
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = ReentrantLock(true)
    fun file(context: Context): File = File(RuntimePaths.storageRoot(context), "steam-cloud/control-v2.json")
    fun <T> locked(context: Context, block: () -> T): T = lock.withLock {
        if (lock.holdCount > 1) return@withLock block()
        RandomAccessFile(File(context.filesDir, "steam-cloud-control.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }
    fun read(context: Context, legacy: SharedPreferences? = null): State = locked(context) {
        val file = file(context)
        if (file.exists()) json.decodeFromString<State>(file.readText())
        else {
            val migrated = State(
                disabled = legacy?.getBoolean("steam_cloud_sync_disabled", false) ?: false,
                mode = legacy?.getString("steam_cloud_save_mode", SteamCloudSaveMode.DEFAULT.persistedValue)
                    ?: SteamCloudSaveMode.DEFAULT.persistedValue,
                independentSwitchPending = legacy?.getBoolean("steam_cloud_independent_switch_pending", false) ?: false,
                pendingSteamId = legacy?.getString("steam_cloud_pending_profile_steam_id", "").orEmpty(),
                blacklist = legacy?.getStringSet("steam_cloud_sync_blacklist_paths", LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS)
                    ?: LauncherConfig.DEFAULT_STEAM_CLOUD_SYNC_BLACKLIST_PATHS,
            )
            write(file, migrated)
            migrated
        }
    }
    fun update(context: Context, legacy: SharedPreferences? = null, transform: (State) -> State) = locked(context) {
        write(file(context), transform(read(context, legacy)))
    }
    fun write(file: File, state: State) {
        SteamCloudAtomicFileStore.writeTextWithoutBackup(file, json.encodeToString(state))
    }
    fun modeReplacement(context: Context, staged: File) = SteamCloudPathReplacement(staged, file(context),
        rollbackJsonKeys = setOf("mode", "independentSwitchPending", "pendingSteamId"))
}
