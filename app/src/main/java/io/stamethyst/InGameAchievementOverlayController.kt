package io.stamethyst

import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import io.stamethyst.achievement.SteamAchievementNotificationSound
import io.stamethyst.achievement.SteamAchievementNotificationSpec
import io.stamethyst.achievement.SteamAchievementNotificationView
import io.stamethyst.backend.steamcloud.AchievementSyncLogStore
import io.stamethyst.backend.steamcloud.SteamAchievementCatalog
import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Non-interactive launcher-owned overlay for achievement events emitted by the game JVM. */
internal class InGameAchievementOverlayController(
    private val activity: StsGameActivity,
) {
    private val queuedApiNames = ArrayDeque<String>()
    private val displayedApiNames = mutableSetOf<String>()
    private val activeApiNames = mutableListOf<String>()
    private var notificationView: SteamAchievementNotificationView? = null
    private var sound: SteamAchievementNotificationSound? = null

    fun attachToHost(host: FrameLayout) {
        if (notificationView?.parent === host) return
        detachView()
        if (sound == null) {
            sound = runCatching { SteamAchievementNotificationSound(activity, ::showNext) }
                .onFailure { Log.w(TAG, "Achievement audio unavailable", it) }
                .getOrNull()
        }
        // A local WebView may be unavailable on devices with a disabled/broken provider. A toast
        // must never prevent the game from launching; do not silently replace it with a fake beep.
        val view = runCatching { SteamAchievementNotificationView(activity, ::showNext) }
            .onFailure { Log.w(TAG, "Achievement renderer unavailable", it) }
            .getOrNull() ?: return
        notificationView = view
        val density = activity.resources.displayMetrics.density
        host.addView(
            view,
            FrameLayout.LayoutParams(
                (SteamAchievementNotificationSpec.WIDTH_DP * density).roundToInt(),
                (SteamAchievementNotificationSpec.HEIGHT_DP * SteamAchievementNotificationSpec.MAX_VISIBLE * density).roundToInt(),
                Gravity.BOTTOM or Gravity.RIGHT,
            ),
        )
    }

    fun enqueue(apiNames: Collection<String>) {
        val accepted = mutableListOf<String>()
        apiNames.forEach { apiName ->
            if (apiName in SteamAchievementCatalog.apiNames && displayedApiNames.add(apiName)) {
                queuedApiNames.addLast(apiName)
                accepted += apiName
            }
        }
        AchievementSyncLogStore.append(
            activity,
            "overlay_queued",
            "count=${accepted.size} ids=${accepted.sorted().joinToString(",")}",
        )
        showNext()
    }

    fun onDestroy() {
        detachView()
        queuedApiNames.clear()
        displayedApiNames.clear()
        activeApiNames.clear()
        sound?.release()
        sound = null
    }

    private fun showNext() {
        val view = notificationView ?: return
        if (!view.ready || sound?.ready == false) return
        while (activeApiNames.size < SteamAchievementNotificationSpec.MAX_VISIBLE && queuedApiNames.isNotEmpty()) {
            val apiName = queuedApiNames.removeFirst()
            val entry = SteamAchievementCatalog.entries.first { it.apiName == apiName }
            activeApiNames += apiName
            view.show(
                entry,
                onShown = {
                    AchievementSyncLogStore.append(activity, "overlay_shown", "id=$apiName")
                    sound?.play()
                },
                onFinished = { finishActive(apiName) },
            )
        }
    }

    private fun finishActive(apiName: String) {
        AchievementSyncLogStore.append(activity, "overlay_dismissed", "id=$apiName")
        activeApiNames.remove(apiName)
        showNext()
    }

    private fun detachView() {
        val view = notificationView ?: return
        notificationView = null
        // Recreating the host (for example after changing the game display surface) must not
        // lose an achievement that was already removed from the queue.
        activeApiNames.asReversed().forEach(queuedApiNames::addFirst)
        activeApiNames.clear()
        (view.parent as? FrameLayout)?.removeView(view)
        view.destroy()
    }

    private companion object {
        const val TAG = "AchievementOverlay"
    }
}
