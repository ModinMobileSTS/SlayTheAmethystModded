package io.stamethyst

import android.app.Activity
import android.content.Context

/** Production bridge; no downloaded Godot types cross this boundary. */
internal interface GodotBootSession {
    /** Called during onCreate, after the controller owns this session but before its first frame. */
    fun start()
    fun publish(progress: Int, message: String, ready: Boolean)
    fun close()

    companion object {
        fun enabled(context: Context): Boolean {
            return io.stamethyst.config.LauncherConfig.readBootOverlayStyle(context) ==
                io.stamethyst.config.BootOverlayStyle.GODOT_SLING_BREAK &&
                io.stamethyst.backend.minigame.SlingBreakService.supported() &&
                io.stamethyst.backend.minigame.SlingBreakInstallation.read(context) != null
        }

        fun create(activity: Activity, enter: Runnable): GodotBootSession? {
            if (!enabled(activity)) return null
            return runCatching {
                io.stamethyst.godot.GodotMinigameBoot(activity, enter)
            }.onFailure { android.util.Log.e("GodotMinigame", "Boot minigame unavailable", it) }.getOrNull()
        }
    }
}
