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
    fun minigameNavigationBarOverrideIsApi27OnlyAndRetainsSharedWindowFlags() {
        fun style(directory: String, name: String): Element {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(source("src/main/res/$directory/godot_window.xml"))
            val styles = document.getElementsByTagName("style")
            return (0 until styles.length).map { styles.item(it) as Element }
                .first { it.getAttribute("name") == name }
        }
        fun values(style: Element): Map<String, String> {
            val items = style.getElementsByTagName("item")
            return (0 until items.length).map { items.item(it) as Element }
                .associate { it.getAttribute("name") to it.textContent.trim() }
        }

        val base = style("values", "GodotPrototypeWindowBase")
        assertEquals("android:style/Theme.Material.Light.NoActionBar", base.getAttribute("parent"))
        val shared = values(base)
        assertEquals("true", shared["android:windowIsTranslucent"])
        assertEquals("@android:color/transparent", shared["android:windowBackground"])
        assertEquals("false", shared["android:windowIsFloating"])
        assertEquals("true", shared["android:windowDisablePreview"])
        assertEquals("true", shared["android:windowFullscreen"])
        assertEquals("true", shared["android:windowActionModeOverlay"])
        assertEquals("@android:color/transparent", shared["android:statusBarColor"])
        assertEquals("@android:color/transparent", shared["android:navigationBarColor"])
        assertEquals("false", shared["android:windowLightStatusBar"])
        assertFalse(shared.containsKey("android:windowLightNavigationBar"))

        val default = style("values", "GodotPrototypeWindow")
        val api27 = style("values-v27", "GodotPrototypeWindow")
        assertEquals("GodotPrototypeWindowBase", default.getAttribute("parent"))
        assertEquals("GodotPrototypeWindowBase", api27.getAttribute("parent"))
        assertTrue(values(default).isEmpty())
        assertEquals(mapOf("android:windowLightNavigationBar" to "false"), values(api27))
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
