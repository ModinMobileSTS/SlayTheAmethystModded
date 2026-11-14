package io.stamethyst.config

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.core.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigBlurTest {
    @Test
    fun existingUsersKeepBlurEnabledWithoutANewPreference() {
        val context = context(InMemoryPreferences())

        assertFalse(LauncherConfig.DEFAULT_LAUNCHER_BLUR_DISABLED)
        assertFalse(LauncherPreferences.readLauncherBlurDisabled(context))
        assertFalse(SettingsRepository.loadLauncherBlurDisabled(context))
    }

    @Test
    fun disablingAndReenablingBlurPersistAcrossContextRecreation() {
        val preferences = InMemoryPreferences()
        LauncherPreferences.saveLauncherBlurDisabled(context(preferences), true)

        assertTrue(SettingsRepository.loadLauncherBlurDisabled(context(preferences)))

        LauncherPreferences.saveLauncherBlurDisabled(context(preferences), false)

        assertFalse(SettingsRepository.loadLauncherBlurDisabled(context(preferences)))
    }

    @Test
    fun changingBlurDoesNotChangeBackgroundOpacity() {
        val context = context(InMemoryPreferences())
        LauncherPreferences.saveChromeBackgroundOpacity(context, 0.6f)

        LauncherPreferences.saveLauncherBlurDisabled(context, true)
        assertEquals(0.6f, LauncherPreferences.readChromeBackgroundOpacity(context), 0f)

        LauncherPreferences.saveLauncherBlurDisabled(context, false)
        assertEquals(0.6f, LauncherPreferences.readChromeBackgroundOpacity(context), 0f)
    }

    private fun context(preferences: SharedPreferences) = object : ContextWrapper(Application()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private class InMemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = Editor()

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
