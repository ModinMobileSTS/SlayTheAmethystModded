package io.stamethyst.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import io.stamethyst.LauncherActivity
import io.stamethyst.R
import io.stamethyst.ui.preferences.LauncherPreferences
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class SettingsHeaderRenderingInstrumentedTest {
    private lateinit var context: Context
    private lateinit var device: UiDevice
    private var previousFirstRunSetupCompleted = false
    private var previousBasicTutorialNoticeDismissed = false
    private var previousLastWorkshopUpdateCheckAtMs = 0L

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext.applicationContext
        device = UiDevice.getInstance(instrumentation)
        previousFirstRunSetupCompleted = LauncherPreferences.isFirstRunSetupCompleted(context)
        previousBasicTutorialNoticeDismissed =
            LauncherPreferences.isBasicTutorialNoticeDismissed(context)
        previousLastWorkshopUpdateCheckAtMs =
            LauncherPreferences.readLastWorkshopUpdateCheckAtMs(context)

        LauncherPreferences.setFirstRunSetupCompleted(context, true)
        LauncherPreferences.setBasicTutorialNoticeDismissed(context, true)
        LauncherPreferences.saveLastWorkshopUpdateCheckAtMs(context, System.currentTimeMillis())
    }

    @After
    fun tearDown() {
        runCatching { device.executeShellCommand("am force-stop ${context.packageName}") }
        LauncherPreferences.setFirstRunSetupCompleted(context, previousFirstRunSetupCompleted)
        LauncherPreferences.setBasicTutorialNoticeDismissed(
            context,
            previousBasicTutorialNoticeDismissed,
        )
        LauncherPreferences.saveLastWorkshopUpdateCheckAtMs(
            context,
            previousLastWorkshopUpdateCheckAtMs,
        )
    }

    @Test
    fun settingsHeadersRemainRenderedWithoutScrollInvalidation() {
        launchSettings()

        val aboutTitle = context.getString(R.string.settings_category_about_title)
        scrollToAndClick(aboutTitle)
        assertHeaderTextRenderedAfterSettling(aboutTitle, "About header before scrolling")

        device.pressBack()
        Thread.sleep(NAVIGATION_SETTLE_MS)
        waitForHeaderText(context.getString(R.string.settings_title))
        scrollToTop()

        val gameTitle = context.getString(R.string.settings_category_game_title)
        scrollToAndClick(gameTitle)
        assertHeaderTextRenderedAfterSettling(gameTitle, "Game header while idle")

        val scrollableContent = waitForScrollableContent()
        assertTrue(
            "Game settings content did not scroll down",
            scrollableContent.scroll(Direction.DOWN, 0.8f),
        )
        scrollToTop(scrollableContent)
        assertHeaderTextRenderedAfterSettling(gameTitle, "Game header after scrolling")
    }

    private fun launchSettings() {
        runCatching { device.wakeUp() }
        runCatching { device.executeShellCommand("wm dismiss-keyguard") }
        device.executeShellCommand("am force-stop ${context.packageName}")

        val component = "${context.packageName}/${LauncherActivity::class.java.name}"
        val launchResult = device.executeShellCommand(
            "am start -W -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -n $component",
        )
        assertTrue("Launcher did not start: $launchResult", launchResult.contains("Status: ok"))

        val settingsDescription = context.getString(R.string.main_dock_settings)
        val settingsDockItem = device.wait(
            Until.findObject(By.desc(settingsDescription)),
            LAUNCH_TIMEOUT_MS,
        ) ?: throw AssertionError("Settings dock item was not reachable")
        settingsDockItem.click()
        Thread.sleep(NAVIGATION_SETTLE_MS)
        waitForHeaderText(context.getString(R.string.settings_title))
    }

    private fun scrollToAndClick(text: String) {
        var scrollAttempts = 0
        while (scrollAttempts++ < MAX_SCROLL_ATTEMPTS) {
            findSettingsDestination(text)?.let { item ->
                item.click()
                Thread.sleep(NAVIGATION_SETTLE_MS)
                return
            }
            val scrollableContent = waitForScrollableContent()
            if (!scrollableContent.scroll(Direction.DOWN, 0.8f)) {
                break
            }
        }
        throw AssertionError("Could not find settings destination: $text")
    }

    private fun scrollToTop(scrollableContent: UiObject2 = waitForScrollableContent()) {
        repeat(MAX_SCROLL_ATTEMPTS) {
            if (!scrollableContent.scroll(Direction.UP, 0.8f)) {
                return
            }
        }
    }

    private fun waitForScrollableContent(): UiObject2 {
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.findObjects(By.scrollable(true))
                .maxByOrNull { item -> item.visibleBounds.height() }
                ?.let { return it }
            Thread.sleep(UI_POLL_INTERVAL_MS)
        }
        throw AssertionError("No scrollable settings content was found")
    }

    private fun findSettingsDestination(text: String): UiObject2? {
        val dockTop = device.displayHeight * 3 / 4
        return device.findObjects(By.text(text))
            .firstOrNull { item -> item.visibleBounds.centerY() < dockTop }
    }

    private fun waitForHeaderText(text: String): UiObject2 {
        val headerBottom = device.displayHeight / 3
        val deadline = System.currentTimeMillis() + UI_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.findObjects(By.text(text))
                .firstOrNull { item -> item.visibleBounds.centerY() < headerBottom }
                ?.let { return it }
            Thread.sleep(UI_POLL_INTERVAL_MS)
        }
        throw AssertionError("Header text was not found: $text")
    }

    private fun assertHeaderTextRenderedAfterSettling(title: String, stage: String) {
        Thread.sleep(RENDER_SETTLE_MS)
        val titleNode = waitForHeaderText(title)
        val screenshot = device.takeScreenshot()
        try {
            assertRenderedForegroundPixels(screenshot, titleNode.visibleBounds, stage)
        } finally {
            screenshot.recycle()
        }
    }

    private fun assertRenderedForegroundPixels(bitmap: Bitmap, nodeBounds: Rect, stage: String) {
        val bounds = Rect(nodeBounds)
        assertTrue(
            "$stage accessibility bounds were outside the screenshot: $nodeBounds",
            bounds.intersect(0, 0, bitmap.width, bitmap.height),
        )
        assertTrue(
            "$stage accessibility bounds were empty: $bounds",
            bounds.width() > 2 && bounds.height() > 2,
        )

        val width = bounds.width()
        val height = bounds.height()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, bounds.left, bounds.top, width, height)

        val luminance = IntArray(pixels.size)
        val histogram = IntArray(256)
        pixels.forEachIndexed { index, color ->
            val value = color.luminance()
            luminance[index] = value
            histogram[value]++
        }

        val medianLuminance = histogram.medianValue(luminance.size)
        val contrastingPixels = luminance.count { value ->
            abs(value - medianLuminance) >= MIN_TEXT_CONTRAST
        }
        var edgePixels = 0
        for (y in 0 until height - EDGE_SAMPLE_DISTANCE) {
            for (x in 0 until width - EDGE_SAMPLE_DISTANCE) {
                val value = luminance[y * width + x]
                val horizontal = luminance[y * width + x + EDGE_SAMPLE_DISTANCE]
                val vertical = luminance[(y + EDGE_SAMPLE_DISTANCE) * width + x]
                if (
                    abs(value - horizontal) >= MIN_TEXT_EDGE_CONTRAST ||
                    abs(value - vertical) >= MIN_TEXT_EDGE_CONTRAST
                ) {
                    edgePixels++
                }
            }
        }

        val requiredContrastingPixels = max(MIN_CONTRASTING_PIXELS, luminance.size / 200)
        val requiredEdgePixels = max(MIN_EDGE_PIXELS, luminance.size / 400)
        assertTrue(
            "$stage had no visible contrasting text pixels inside $bounds: " +
                "contrasting=$contrastingPixels/$requiredContrastingPixels, " +
                "edges=$edgePixels/$requiredEdgePixels, median=$medianLuminance",
            contrastingPixels >= requiredContrastingPixels && edgePixels >= requiredEdgePixels,
        )
    }

    private fun Int.luminance(): Int {
        return (
            Color.red(this) * 299 +
                Color.green(this) * 587 +
                Color.blue(this) * 114
            ) / 1000
    }

    private fun IntArray.medianValue(totalPixels: Int): Int {
        val midpoint = totalPixels / 2
        var accumulated = 0
        forEachIndexed { value, count ->
            accumulated += count
            if (accumulated >= midpoint) {
                return value
            }
        }
        return lastIndex
    }

    private companion object {
        private const val LAUNCH_TIMEOUT_MS = 30_000L
        private const val UI_TIMEOUT_MS = 10_000L
        private const val NAVIGATION_SETTLE_MS = 700L
        private const val RENDER_SETTLE_MS = 1_200L
        private const val UI_POLL_INTERVAL_MS = 100L
        private const val MAX_SCROLL_ATTEMPTS = 10
        private const val EDGE_SAMPLE_DISTANCE = 2
        private const val MIN_TEXT_CONTRAST = 24
        private const val MIN_TEXT_EDGE_CONTRAST = 20
        private const val MIN_CONTRASTING_PIXELS = 12
        private const val MIN_EDGE_PIXELS = 8
    }
}
