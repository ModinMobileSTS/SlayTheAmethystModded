package io.stamethyst.ui.settings.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.backend.render.ForceHighRefreshRateController
import io.stamethyst.config.LauncherConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Uses the existing mockable Android JVM runtime; real windows/main-loop dispatch need a device. */
class SettingsForceHighRefreshRateResetTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(File(repositoryRoot(), "agent-tmp").apply { mkdirs() })

    @Test
    fun resetPersistsDefaultOnWorkerAndNotifiesBothObserversOnlyWhenRefreshed() {
        val fixture = Fixture(temporaryFolder.newFolder())
        val context = fixture.context()
        val firstObserver = mutableListOf<Boolean>()
        val secondObserver = mutableListOf<Boolean>()
        val firstListener: (Boolean) -> Unit = { firstObserver += it }
        val secondListener: (Boolean) -> Unit = { secondObserver += it }
        val worker = Executors.newSingleThreadExecutor()
        ForceHighRefreshRateController.addListener(firstListener)
        ForceHighRefreshRateController.addListener(secondListener)
        try {
            ForceHighRefreshRateController.setEnabled(context, true)
            assertTrue(LauncherConfig.isForceHighRefreshRateEnabled(context))

            worker.submit {
                SettingsRepository.resetLauncherSettingsToDefaults(context)
            }.get(10, TimeUnit.SECONDS)

            assertFalse(LauncherConfig.DEFAULT_FORCE_HIGH_REFRESH_RATE)
            assertFalse(LauncherConfig.isForceHighRefreshRateEnabled(fixture.context()))
            // The persistence executor must not update Compose observers (or attached windows).
            assertEquals(listOf(true), firstObserver)
            assertEquals(listOf(true), secondObserver)

            val writesAfterReset = fixture.writeCount
            ForceHighRefreshRateController.refreshFromPreferences(context)

            assertEquals(listOf(true, false), firstObserver)
            assertEquals(listOf(true, false), secondObserver)
            assertEquals(writesAfterReset, fixture.writeCount)
        } finally {
            worker.shutdownNow()
            ForceHighRefreshRateController.removeListener(firstListener)
            ForceHighRefreshRateController.removeListener(secondListener)
        }
    }

    @Test
    fun resetWhileAlreadyDisabledStillAllowsManualEnableAndDisable() {
        val fixture = Fixture(temporaryFolder.newFolder())
        val context = fixture.context()
        assertFalse(LauncherConfig.isForceHighRefreshRateEnabled(context))
        SettingsRepository.resetLauncherSettingsToDefaults(context)
        assertFalse(LauncherConfig.isForceHighRefreshRateEnabled(fixture.context()))

        val changes = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { changes += it }
        ForceHighRefreshRateController.addListener(listener)
        try {
            val writesAfterReset = fixture.writeCount
            ForceHighRefreshRateController.setEnabled(context, true)
            assertTrue(LauncherConfig.isForceHighRefreshRateEnabled(fixture.context()))
            assertEquals(writesAfterReset + 1, fixture.writeCount)

            ForceHighRefreshRateController.setEnabled(context, false)
            assertFalse(LauncherConfig.isForceHighRefreshRateEnabled(fixture.context()))
            assertEquals(listOf(true, false), changes)
            assertEquals(writesAfterReset + 2, fixture.writeCount)
        } finally {
            ForceHighRefreshRateController.removeListener(listener)
        }
    }

    private class Fixture(root: File) {
        private val files = File(root, "files").apply { mkdirs() }
        private val externalFiles = File(root, "external-files").apply { mkdirs() }
        private val preferences = mutableMapOf<String, InMemoryPreferences>()
        val writeCount: Int get() = preferences.values.sumOf { it.writeCount }

        fun context(): Context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = files
            override fun getExternalFilesDir(type: String?): File = externalFiles
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "io.stamethyst.test"
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getOrPut(name) { InMemoryPreferences() }
        }
    }

    private class InMemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var writeCount = 0
            private set

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
                writeCount++
                return true
            }
            override fun apply() { commit() }
        }
    }

    private companion object {
        fun repositoryRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
            it.parentFile
        }.first { File(it, "app/src/main/java/io/stamethyst/ui/settings/core/SettingsRepository.kt").isFile }
    }
}
