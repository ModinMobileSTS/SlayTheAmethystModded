package io.stamethyst

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import io.stamethyst.ui.LauncherContent
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.settings.SettingsScreenViewModel

class LauncherActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DEBUG_LAUNCH_MODE = "io.stamethyst.debug_launch_mode"
        const val EXTRA_CRASH_OCCURRED = "io.stamethyst.crash_occurred"
        const val EXTRA_CRASH_CODE = "io.stamethyst.crash_code"
        const val EXTRA_CRASH_IS_SIGNAL = "io.stamethyst.crash_is_signal"
        const val EXTRA_CRASH_DETAIL = "io.stamethyst.crash_detail"
    }

    private val mainViewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsScreenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LauncherIconManager.syncSelection(this)

        setContent {
            MaterialTheme {
                LauncherContent(
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel,
                )
            }
        }

        mainViewModel.handleIncomingIntent(this, intent) {
            settingsViewModel.onShareCrashReport(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainViewModel.handleIncomingIntent(this, intent) {
            settingsViewModel.onShareCrashReport(this)
        }
    }
}
