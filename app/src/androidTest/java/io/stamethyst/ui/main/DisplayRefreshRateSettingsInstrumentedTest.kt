package io.stamethyst.ui.main

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayRefreshRateSettingsInstrumentedTest {
    @Test
    fun opensDisplaySettingsWithNewTaskFlag() {
        val context = RecordingContext()

        openDisplayRefreshRateSettings(context)

        assertEquals(listOf(Settings.ACTION_DISPLAY_SETTINGS), context.intents.map { it.action })
        assertTrue(context.intents.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun unavailableDisplaySettingsFallsBackToSettingsHome() {
        val context = RecordingContext { intent ->
            if (intent.action == Settings.ACTION_DISPLAY_SETTINGS) throw ActivityNotFoundException()
        }

        openDisplayRefreshRateSettings(context)

        assertEquals(
            listOf(Settings.ACTION_DISPLAY_SETTINGS, Settings.ACTION_SETTINGS),
            context.intents.map { it.action },
        )
    }

    @Test
    fun blockedSettingsDoNotCrashTheLauncher() {
        val context = RecordingContext { throw SecurityException() }

        openDisplayRefreshRateSettings(context)

        assertEquals(2, context.intents.size)
    }

    private class RecordingContext(
        private val onStartActivity: (Intent) -> Unit = {},
    ) : ContextWrapper(null) {
        val intents = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            intents += intent
            onStartActivity(intent)
        }
    }
}
