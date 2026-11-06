package io.stamethyst.ui.settings.sections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.common.LocalSettingsActionGate
import kotlinx.coroutines.delay

@Composable
internal fun SettingsSearchDeveloperWarning(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var accepted by rememberSaveable {
        mutableStateOf(LauncherPreferences.isDeveloperSettingsWarningDismissed(context))
    }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var remainingSeconds by remember { mutableIntStateOf(5) }

    LaunchedEffect(pendingAction) {
        if (pendingAction != null) {
            remainingSeconds = 5
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
            }
        }
    }

    CompositionLocalProvider(LocalSettingsActionGate provides { action ->
        if (accepted) action() else pendingAction = action
    }) {
        content()
    }

    if (pendingAction != null) {
        fun accept() {
            accepted = true
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
        DeveloperSettingsWarningDialog(
            remainingSeconds = remainingSeconds,
            onConfirm = ::accept,
            onDismissPermanently = {
                LauncherPreferences.setDeveloperSettingsWarningDismissed(context, true)
                accept()
            },
        )
    }
}
