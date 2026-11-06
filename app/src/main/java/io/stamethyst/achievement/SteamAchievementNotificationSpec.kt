package io.stamethyst.achievement

import kotlin.math.ceil
import kotlin.math.floor

/** Desktop Steam defaults, not the Steam Deck / Big Picture toast. See docs/steam-achievement-notification.md. */
internal object SteamAchievementNotificationSpec {
    const val WIDTH_DP = 283
    const val HEIGHT_DP = 70
    const val MAX_VISIBLE = 3
    const val ENTER_MS = 300L
    const val HOLD_MS = 5_000L
    const val EXIT_MS = 300L
    const val TOTAL_MS = ENTER_MS + HOLD_MS + EXIT_MS
    const val SOUND_DEBOUNCE_MS = 50L
    const val RARE_THRESHOLD_PERCENT = 10.0

    fun isRare(globalUnlockPercent: Double?): Boolean =
        globalUnlockPercent != null && globalUnlockPercent >= 0.0 &&
            globalUnlockPercent < RARE_THRESHOLD_PERCENT

    fun canPlaySound(nowMs: Long, lastPlayedMs: Long?): Boolean =
        lastPlayedMs == null || nowMs - lastPlayedMs >= SOUND_DEBOUNCE_MS

    /**
     * Steam's bottom-right window stack uses ceil(height * progress) for the stack height and
     * floor(height * (1 - progress)) for the window offset. With one toast this travels TWO
     * heights, not one, and is linear. Preserve the rounding rather than using a spring/easing.
     */
    fun translationY(elapsedMs: Long, heightPx: Int): Float {
        val progress = when {
            elapsedMs < 0 -> 0.0
            elapsedMs < ENTER_MS -> elapsedMs.toDouble() / ENTER_MS
            elapsedMs < ENTER_MS + HOLD_MS -> 1.0
            elapsedMs < TOTAL_MS -> 1.0 - (elapsedMs - ENTER_MS - HOLD_MS).toDouble() / EXIT_MS
            else -> 0.0
        }
        return (heightPx - ceil(heightPx * progress) + floor(heightPx * (1.0 - progress))).toFloat()
    }
}
