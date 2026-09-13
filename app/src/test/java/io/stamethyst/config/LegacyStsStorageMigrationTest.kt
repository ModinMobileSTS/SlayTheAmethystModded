package io.stamethyst.config

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.backend.workshop.WorkshopMetadataStore
import java.io.File
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStsStorageMigrationTest {
    @Test
    fun migrationRewritesWorkshopAbsoluteJarPaths() {
        val root = Files.createTempDirectory("legacy-sts-storage-migration").toFile()
        val filesDir = File(root, "internal-files").apply { mkdirs() }
        val externalFilesDir = File(root, "external-files").apply { mkdirs() }
        val preferences = LinkedHashMap<String, InMemorySharedPreferences>()
        val context = object : ContextWrapper(Application()) {
            override fun getFilesDir(): File = filesDir

            override fun getExternalFilesDir(type: String?): File = externalFilesDir

            override fun getApplicationContext(): Context = this

            override fun getPackageName(): String = "io.stamethyst.test"

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getOrPut(name) { InMemorySharedPreferences() }
        }
        val oldJar = File(filesDir, "sts/mods_library/Workshop.jar").apply {
            parentFile?.mkdirs()
            writeText("jar")
        }
        val oldMetadata = File(filesDir, "workshop/index.json").apply {
            parentFile?.mkdirs()
            writeText(
                JSONObject()
                    .put(
                        "items",
                        JSONArray().put(
                            JSONObject()
                                .put("appId", 646570)
                                .put("publishedFileId", "123")
                                .put("title", "Workshop Mod")
                                .put("description", "description")
                                .put("previewUrl", "")
                                .put("versionText", "1")
                                .put("updatedAtMillis", 1)
                                .put("installedAtMillis", 1)
                                .put("localJarPath", oldJar.absolutePath)
                                .put("localJarPaths", JSONArray().put(oldJar.absolutePath))
                                .put("cardState", "ImportedPatched")
                                .put("statusText", "installed")
                        )
                    )
                    .toString()
            )
        }

        LegacyStsStorageMigration.migrateIfNeeded(context)

        val newJar = File(externalFilesDir, "sts/mods_library/Workshop.jar")
        assertTrue(newJar.isFile)
        val record = WorkshopMetadataStore(context).findByPublishedFileId(646570u, 123uL)
        assertEquals(newJar.absolutePath, record?.localJarPath)
        assertEquals(listOf(newJar.absolutePath), record?.localJarPaths)
        assertTrue(File(externalFilesDir, "workshop/index.json").isFile)
        assertTrue(oldMetadata.isFile)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

        override fun getString(key: String, defValue: String?): String? =
            synchronized(values) { values[key] as? String ?: defValue }

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

        override fun getInt(key: String, defValue: Int): Int = synchronized(values) { values[key] as? Int ?: defValue }

        override fun getLong(key: String, defValue: Long): Long = synchronized(values) { values[key] as? Long ?: defValue }

        override fun getFloat(key: String, defValue: Float): Float = synchronized(values) { values[key] as? Float ?: defValue }

        override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(values) { values[key] as? Boolean ?: defValue }

        override fun contains(key: String): Boolean = synchronized(values) { values.containsKey(key) }

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { pending[key] = values?.toMutableSet() }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }

            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                synchronized(values) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
