package io.stamethyst.backend.render

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import io.stamethyst.R

internal data class AndroidGameModeSnapshot(
    val rawMode: Int,
    @param:StringRes val displayNameResId: Int,
    @param:StringRes val descriptionResId: Int,
    val supported: Boolean
)

internal object AndroidGameModeSupport {
    private const val BATTERY_MODE_RENDER_SCALE_CAP = 0.85f

    fun readCurrentMode(context: Context): AndroidGameModeSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return AndroidGameModeSnapshot(
                rawMode = GameManager.GAME_MODE_UNSUPPORTED,
                displayNameResId = R.string.settings_game_mode_name_unavailable,
                descriptionResId = R.string.settings_game_mode_desc_api_unavailable,
                supported = false
            )
        }
        val gameManager = context.getSystemService(GameManager::class.java)
            ?: return AndroidGameModeSnapshot(
                rawMode = GameManager.GAME_MODE_UNSUPPORTED,
                displayNameResId = R.string.settings_game_mode_name_unavailable,
                descriptionResId = R.string.settings_game_mode_desc_service_unavailable,
                supported = false
            )
        val mode = try {
            gameManager.gameMode
        } catch (_: Throwable) {
            GameManager.GAME_MODE_UNSUPPORTED
        }
        return when (mode) {
            GameManager.GAME_MODE_STANDARD -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayNameResId = R.string.settings_game_mode_name_standard,
                descriptionResId = R.string.settings_game_mode_desc_standard,
                supported = true
            )

            GameManager.GAME_MODE_PERFORMANCE -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayNameResId = R.string.settings_game_mode_name_performance,
                descriptionResId = R.string.settings_game_mode_desc_performance,
                supported = true
            )

            GameManager.GAME_MODE_BATTERY -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayNameResId = R.string.settings_game_mode_name_battery,
                descriptionResId = R.string.settings_game_mode_desc_battery,
                supported = true
            )

            GameManager.GAME_MODE_CUSTOM -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayNameResId = R.string.settings_game_mode_name_custom,
                descriptionResId = R.string.settings_game_mode_desc_custom,
                supported = true
            )

            else -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayNameResId = R.string.settings_game_mode_name_unsupported,
                descriptionResId = R.string.settings_game_mode_desc_unsupported,
                supported = false
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolveTargetFps(requestedTargetFps: Int, snapshot: AndroidGameModeSnapshot): Int {
        return requestedTargetFps
    }

    fun resolveRenderScale(requestedRenderScale: Float, snapshot: AndroidGameModeSnapshot): Float {
        return when (snapshot.rawMode) {
            GameManager.GAME_MODE_BATTERY -> requestedRenderScale.coerceAtMost(
                BATTERY_MODE_RENDER_SCALE_CAP
            )

            else -> requestedRenderScale
        }
    }

    fun reportGameState(context: Context, isLoading: Boolean, inForeground: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val gameManager = context.getSystemService(GameManager::class.java) ?: return
        val mode = if (inForeground && !isLoading) {
            GameState.MODE_GAMEPLAY_INTERRUPTIBLE
        } else {
            GameState.MODE_NONE
        }
        try {
            gameManager.setGameState(GameState(isLoading, mode))
        } catch (_: Throwable) {
        }
    }
}
