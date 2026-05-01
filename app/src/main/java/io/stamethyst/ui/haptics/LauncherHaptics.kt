package io.stamethyst.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import io.stamethyst.ui.preferences.LauncherPreferences

object LauncherHaptics {
    fun perform(view: View, feedbackConstant: Int) {
        if (!LauncherPreferences.isHapticFeedbackEnabled(view.context)) {
            return
        }
        view.performHapticFeedback(feedbackConstant)
    }

    fun vibrateToggle(context: Context) {
        if (!LauncherPreferences.isHapticFeedbackEnabled(context)) {
            return
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) {
                return
            }
            vibrator.vibrate(VibrationEffect.createOneShot(14L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
