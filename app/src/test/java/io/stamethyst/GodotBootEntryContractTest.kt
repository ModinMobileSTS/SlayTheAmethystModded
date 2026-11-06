package io.stamethyst

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Manifest/ordering regressions can reintroduce a landscape flash despite a portrait policy. */
class GodotBootEntryContractTest {
    private fun source(path: String): File = listOf(File(path), File("app", path)).first { it.isFile }

    @Test
    fun godotAndNormalEntriesUseTheSameGameProcessButDifferentStartingOrientations() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(source("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        fun activity(name: String): Element = (0 until activities.length)
            .map { activities.item(it) as Element }
            .first { it.android("name") == "io.stamethyst.$name" }

        val godot = activity("GodotBootGameActivity")
        val normal = activity("StsGameActivity")
        assertEquals("portrait", godot.android("screenOrientation"))
        assertEquals("sensorLandscape", normal.android("screenOrientation"))
        for (entry in listOf(godot, normal)) {
            assertEquals(":game", entry.android("process"))
            assertEquals("false", entry.android("exported"))
            assertEquals("true", entry.android("hardwareAccelerated"))
            assertTrue(entry.android("configChanges").split('|').containsAll(listOf("orientation", "screenSize")))
        }
        assertEquals("@style/Theme.SlayTheAmethyst.Game.GodotBoot", godot.android("theme"))
    }

    @Test
    fun portraitEntrySuppressesTheMainGamePreviewWithoutMakingItsSurfaceTranslucent() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("src/main/res/values/godot_window.xml"))
        val styles = document.getElementsByTagName("style")
        val style = (0 until styles.length).map { styles.item(it) as Element }
            .first { it.getAttribute("name") == "Theme.SlayTheAmethyst.Game.GodotBoot" }
        assertEquals("Theme.SlayTheAmethyst.Game", style.getAttribute("parent"))
        val items = style.getElementsByTagName("item")
        val values = (0 until items.length).map { items.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
        assertEquals("true", values["android:windowDisablePreview"])
        assertFalse(values["android:windowIsTranslucent"] == "true")
    }

    @Test
    fun minigameStartsAfterSessionAssignmentNotAfterTheGameDecorHasDrawn() {
        val controller = source("src/main/java/io/stamethyst/BootOverlayController.kt").readText()
        assertTrue(controller.indexOf("godotSession = GodotBootSession.create") < controller.indexOf("godotSession?.start()"))
        val boot = source("src/main/java/io/stamethyst/godot/GodotMinigameBoot.java").readText()
        assertTrue(boot.contains("@Override public void start()"))
        assertFalse(boot.contains("getDecorView().post"))
    }

    @Test
    fun minigameBackReachesTheSaveGateAndItsProcessExitCannotKillTheLauncher() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(source("src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        val entries = (0 until activities.length).map { activities.item(it) as Element }
            .associateBy { it.android("name") }
        val minigame = entries.getValue("io.stamethyst.godot.GodotMinigameActivity")
        assertEquals("false", minigame.android("enableOnBackInvokedCallback"))
        assertEquals("false", minigame.android("exported"))
        assertEquals(":minigame", minigame.android("process"))
        assertEquals(":game", entries.getValue("io.stamethyst.GodotBootGameActivity").android("process"))
        assertEquals("", entries.getValue("io.stamethyst.LauncherActivity").android("process"))
    }

    private fun Element.android(name: String): String =
        getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
