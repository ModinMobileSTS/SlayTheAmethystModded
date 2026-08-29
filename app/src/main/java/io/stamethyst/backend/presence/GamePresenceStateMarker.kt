package io.stamethyst.backend.presence

import android.content.Context
import android.util.Log
import io.stamethyst.backend.steamcloud.SteamCloudAtomicFileStore
import io.stamethyst.backend.steamcloud.SteamGamePresenceDiagnosticsStore
import io.stamethyst.config.RuntimePaths
import java.io.File

object GamePresenceStateMarker {
    private const val TAG = "GamePresenceState"

    fun markGameActive(context: Context, launchMode: String) {
        writeState(context, GamePresenceState.Game, launchMode)
    }

    fun markLauncherActive(context: Context) {
        writeState(context, GamePresenceState.Launcher, "")
    }

    fun readCurrentState(context: Context): GamePresenceSnapshot {
        val file = gamePresenceStateFile(context)
        val lines = runCatching { file.readLines() }
            .onFailure { error ->
                Log.w(TAG, "Unable to read game presence state from ${file.absolutePath}", error)
                SteamGamePresenceDiagnosticsStore.appendEvent(
                    context,
                    "state_read_failed",
                    "path=${file.absolutePath}; error=${error.javaClass.simpleName}",
                )
            }
            .getOrNull()
            .orEmpty()
        val state = when (lines.getOrNull(0)?.trim()) {
            GamePresenceState.Game.wireValue -> GamePresenceState.Game
            else -> GamePresenceState.Launcher
        }
        val launchMode = lines.getOrNull(1)?.trim().orEmpty()
        return GamePresenceSnapshot(
            state = state,
            launchMode = launchMode
        )
    }

    private fun writeState(
        context: Context,
        state: GamePresenceState,
        launchMode: String
    ) {
        runCatching {
            val file = gamePresenceStateFile(context)
            file.parentFile?.mkdirs()
            SteamCloudAtomicFileStore.writeText(
                file,
                state.wireValue + "\n" +
                    launchMode + "\n"
            )
            SteamGamePresenceDiagnosticsStore.appendEvent(
                context,
                "state_written",
                "state=${state.wireValue}; launchMode=${launchMode.ifBlank { "<none>" }}; path=${file.absolutePath}",
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to write game presence state=$state", error)
            SteamGamePresenceDiagnosticsStore.appendEvent(
                context,
                "state_write_failed",
                "state=${state.wireValue}; error=${error.javaClass.simpleName}",
            )
        }
    }

    private fun gamePresenceStateFile(context: Context): File =
        File(RuntimePaths.stsRoot(context), ".game_presence_state")
}

data class GamePresenceSnapshot(
    val state: GamePresenceState,
    val launchMode: String
)
