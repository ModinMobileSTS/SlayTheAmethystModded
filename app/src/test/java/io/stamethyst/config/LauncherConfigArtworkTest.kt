package io.stamethyst.config

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.stamethyst.ui.preferences.LauncherPreferences
import io.stamethyst.ui.settings.services.BootOverlayImageService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LauncherConfigArtworkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultAndPersistedArtworkSurviveContextRecreation() {
        val prefs = InMemoryPreferences()
        assertEquals(BootOverlayArtwork.POSTER, LauncherPreferences.readBootOverlayImageConfig(context(prefs)).artwork)
        BootOverlayArtwork.entries.reversed().forEach { artwork ->
            LauncherPreferences.saveBootOverlayImageConfig(context(prefs), BootOverlayImageConfig(artwork = artwork))
            val restored = LauncherPreferences.readBootOverlayImageConfig(context(prefs))
            assertEquals(artwork, restored.artwork)
            assertFalse(restored.hasCustomImages)
        }
    }

    @Test
    fun invalidPreferenceFallsBackToNewArtworkAndClearRestoresDefault() {
        val prefs = InMemoryPreferences()
        prefs.edit().putString("boot_overlay_artwork", "unknown").apply()
        assertEquals(BootOverlayArtwork.POSTER, LauncherConfig.readBootOverlayImageConfig(context(prefs)).artwork)
        LauncherConfig.saveBootOverlayImageConfig(context(prefs), BootOverlayImageConfig(artwork = BootOverlayArtwork.ORIGINAL))
        LauncherConfig.clearBootOverlayImageConfig(context(prefs))
        assertEquals(BootOverlayImageConfig(), LauncherConfig.readBootOverlayImageConfig(context(prefs)))
    }

    @Test
    fun savingArtworkRetainsExistingCustomImages() {
        val prefs = InMemoryPreferences()
        val image = File.createTempFile("boot-artwork", ".image")
        try {
            val config = BootOverlayImageConfig(
                startImagePath = image.absolutePath,
                endImagePath = image.absolutePath,
                startImageVersion = 12L,
                endImageVersion = 34L,
                artwork = BootOverlayArtwork.ORIGINAL,
            )
            LauncherConfig.saveBootOverlayImageConfig(context(prefs), config)
            assertEquals(config, LauncherConfig.readBootOverlayImageConfig(context(prefs)))
        } finally {
            image.delete()
        }
    }

    @Test
    fun changingImageModePreservesSelectedArtwork() {
        val host = context(InMemoryPreferences())
        LauncherConfig.saveBootOverlayImageConfig(host, BootOverlayImageConfig(artwork = BootOverlayArtwork.ORIGINAL))
        listOf(BootOverlayImageMode.SINGLE, BootOverlayImageMode.DUAL).forEach { mode ->
            val changed = BootOverlayImageService.saveMode(host, mode)
            assertEquals(mode, changed.mode)
            assertEquals(BootOverlayArtwork.ORIGINAL, changed.artwork)
            assertEquals(changed, LauncherConfig.readBootOverlayImageConfig(host))
        }
    }

    @Test
    fun resettingCustomImagesKeepsOriginalArtworkAndRestoresDualReveal() {
        val filesDir = temporaryFolder.newFolder()
        val host = context(InMemoryPreferences(), filesDir)
        val imagesDir = RuntimePaths.bootOverlayImagesDir(host).apply { mkdirs() }
        val custom = File(imagesDir, BootOverlayImageSlot.START.fileName).apply { writeText("custom") }
        LauncherConfig.saveBootOverlayImageConfig(
            host,
            BootOverlayImageConfig(
                mode = BootOverlayImageMode.SINGLE,
                startImagePath = custom.absolutePath,
                endImagePath = custom.absolutePath,
                startImageVersion = 1L,
                endImageVersion = 1L,
                artwork = BootOverlayArtwork.ORIGINAL,
            ),
        )
        val reset = BootOverlayImageService.reset(host)
        assertEquals(BootOverlayImageConfig(artwork = BootOverlayArtwork.ORIGINAL), reset)
        assertEquals(reset, LauncherConfig.readBootOverlayImageConfig(host))
        assertFalse(custom.exists())
    }

    @Test
    fun bothArtworkPairsArePackagedWithExpectedFormats() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/resource-pack").isDirectory }
        BootOverlayArtwork.entries.forEach { artwork ->
            val directory = if (artwork == BootOverlayArtwork.POSTER) "assets" else "resource-pack"
            listOf(artwork.startResourcePath, artwork.endResourcePath).forEach { path ->
                val image = File(root, "app/src/main/$directory/$path")
                assertTrue("$path must be packaged", image.isFile && image.length() > 0)
                val signature = image.inputStream().use { it.readNBytes(8) }
                val expected = if (artwork == BootOverlayArtwork.POSTER) {
                    byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
                } else {
                    byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                }
                assertTrue("$path must have the expected image format", signature.take(expected.size) == expected.toList())
            }
        }
    }

    private fun context(prefs: SharedPreferences, filesDir: File? = null) = object : ContextWrapper(Application()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        override fun getFilesDir(): File = filesDir ?: super.getFilesDir()
    }

    private class InMemoryPreferences : SharedPreferences {
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
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }
}
