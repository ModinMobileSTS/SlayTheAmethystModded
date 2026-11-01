package io.stamethyst

import android.app.Activity

/** Production bridge; no downloaded Godot types cross this boundary. */
internal interface GodotBootSession {
    fun publish(progress: Int, message: String, ready: Boolean)
    fun close()

    companion object {
        fun enabled(activity: Activity): Boolean {
            return io.stamethyst.config.LauncherConfig.readBootOverlayStyle(activity) ==
                io.stamethyst.config.BootOverlayStyle.SLING_BREAK &&
                io.stamethyst.backend.minigame.SlingBreakService.supported() &&
                io.stamethyst.backend.minigame.SlingBreakInstallation.read(activity) != null
        }

        fun create(activity: Activity, enter: Runnable): GodotBootSession? {
            if (!enabled(activity)) return null
            return runCatching {
                io.stamethyst.godot.GodotMinigameBoot(activity, enter)
            }.onFailure { android.util.Log.e("GodotMinigame", "Boot minigame unavailable", it) }.getOrNull()
        }
    }
}
