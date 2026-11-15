package io.stamethyst.config

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.ui.preferences.LauncherPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModPageLayoutTest {
    @Test fun unsetAndUnknownLayoutsKeepThe166Default() {
        val preferences = MemoryPreferences()
        assertEquals(ModPageLayout.COMPACT, LauncherPreferences.DEFAULT_MOD_PAGE_LAYOUT)
        assertEquals(ModPageLayout.COMPACT, LauncherPreferences.readModPageLayout(context(preferences)))
        preferences.edit().putString("mod_page_layout", "future-layout").apply()
        assertEquals(ModPageLayout.COMPACT, LauncherPreferences.readModPageLayout(context(preferences)))
    }

    @Test fun bothLayoutsPersistAcrossContextRecreationWithoutTouchingOtherSettings() {
        val preferences = MemoryPreferences()
        preferences.edit().putBoolean("mod_enabled_example", true)
            .putString("mod_display_order", "b,a")
            .putFloat("chrome_background_opacity", 0.6f).apply()
        val before = preferences.all
        listOf(ModPageLayout.CLASSIC, ModPageLayout.COMPACT, ModPageLayout.CLASSIC).forEach { layout ->
            LauncherPreferences.saveModPageLayout(context(preferences), layout)
            assertEquals(layout, LauncherPreferences.readModPageLayout(context(preferences)))
            assertEquals(before, preferences.all.filterKeys { it != "mod_page_layout" })
            assertTrue(preferences.all.containsKey("mod_page_layout"))
        }
    }

    private fun context(preferences: SharedPreferences) = object : ContextWrapper(Application()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String) = values.containsKey(key)
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
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
