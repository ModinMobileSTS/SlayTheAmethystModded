package io.stamethyst.backend.render

import android.app.GameManager
import android.content.Context
import android.os.Build

internal data class AndroidGameModeSnapshot(
    val rawMode: Int,
    val displayName: String,
    val description: String,
    val supported: Boolean
)

internal object AndroidGameModeSupport {
    fun readCurrentMode(context: Context): AndroidGameModeSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return AndroidGameModeSnapshot(
                rawMode = GameManager.GAME_MODE_UNSUPPORTED,
                displayName = "不可用",
                description = "Android 12 以下系统不提供 Game Mode API。",
                supported = false
            )
        }
        val gameManager = context.getSystemService(GameManager::class.java)
            ?: return AndroidGameModeSnapshot(
                rawMode = GameManager.GAME_MODE_UNSUPPORTED,
                displayName = "不可用",
                description = "当前设备没有提供 GameManager 服务。",
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
                displayName = "STANDARD",
                description = "系统没有启用特殊游戏模式，启动器将按你设置的目标 FPS 运行。",
                supported = true
            )

            GameManager.GAME_MODE_PERFORMANCE -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayName = "PERFORMANCE",
                description = "系统偏向性能模式，启动器将按你设置的目标 FPS 运行。",
                supported = true
            )

            GameManager.GAME_MODE_BATTERY -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayName = "BATTERY",
                description = "系统偏向续航模式，但启动器不会再额外压低你设置的目标 FPS。",
                supported = true
            )

            GameManager.GAME_MODE_CUSTOM -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayName = "CUSTOM",
                description = "系统正在使用自定义游戏模式，启动器目前按普通模式处理。",
                supported = true
            )

            else -> AndroidGameModeSnapshot(
                rawMode = mode,
                displayName = "UNSUPPORTED",
                description = "当前系统没有为这个应用提供可用的 Game Mode。",
                supported = false
            )
        }
    }

    fun resolveTargetFps(requestedTargetFps: Int, snapshot: AndroidGameModeSnapshot): Int {
        return requestedTargetFps
    }
}
