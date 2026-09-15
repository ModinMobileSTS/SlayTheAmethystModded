package io.stamethyst.ui.main

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RendererCardRenderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val activity = "io.stamethyst.ui.main.RendererCardTestActivity"

    private fun launch(dark: Boolean = false, gles: Boolean = false, width: Int = 393, scale: Float = 1f,
                       locale: String = "zh-CN", available: Boolean = true, disabled: Boolean = false, theme: String = "COLORLESS") {
        context.startActivity(Intent().setClassName(context.packageName, activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("dark", dark).putExtra("gles", gles).putExtra("manual", gles)
            .putExtra("width", width).putExtra("fontScale", scale).putExtra("locale", locale)
            .putExtra("available", available).putExtra("disabled", disabled).putExtra("theme", theme))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
        Thread.sleep(800)
    }

    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "renderer-verification/$name.png")
        file.parentFile!!.mkdirs()
        assertTrue(device.takeScreenshot(file))
        val bitmap = BitmapFactory.decodeFile(file.path)
        assertNotNull(bitmap)
        val colors = HashSet<Int>()
        for (y in 80 until bitmap.height / 2 step 4) for (x in 40 until bitmap.width - 40 step 4) colors.add(bitmap.getPixel(x, y))
        assertTrue("Renderer output is blank: $name", colors.size > 30)
        bitmap.recycle()
    }

    @Test fun themesWidthsAndFontScalesRender() {
        for (dark in listOf(false, true)) for (gles in listOf(false, true)) {
            launch(dark, gles)
            capture("${if (dark) "dark" else "light"}-${if (gles) "gles" else "mobileglues"}")
        }
        for (locale in listOf("en", "zh-CN", "zh-TW")) {
            launch(gles = true, width = 320, scale = 2f, locale = locale)
            capture("narrow-large-$locale")
            assertNotNull(device.findObject(By.descContains("MobileGlues")))
        }
        launch(width = 360)
        capture("compact-360")
        launch(theme = "ZHANSHIGE")
        capture("custom-theme")
    }

    @Test fun cancelConfirmRestoreAndDisabledStates() {
        launch()
        val selector = By.desc("当前 MobileGlues，切换到 GLES2")
        val initialBounds = device.findObject(selector).visibleBounds
        device.findObject(selector).click()
        assertTrue(device.wait(Until.hasObject(By.text("切换到 GLES2？")), 3_000))
        capture("switch-dialog")
        device.findObject(By.text("取消")).click()
        assertNotNull(device.wait(Until.findObject(selector), 3_000))
        device.findObject(selector).click()
        device.wait(Until.findObject(By.text("确认切换")), 3_000).click()
        val switched = device.wait(Until.findObject(By.desc("当前 GLES2，切换到 MobileGlues")), 3_000)
        assertNotNull(switched)
        assertEquals("Switching must not resize the selector", initialBounds.height(), switched.visibleBounds.height())
        assertNotNull(device.findObject(By.text("已保存，下次启动生效")))
        device.findObject(By.desc("恢复自动选择")).click()
        assertTrue(device.wait(Until.hasObject(By.text("恢复自动选择？")), 3_000))
        capture("restore-dialog")
        device.findObject(By.text("恢复自动")).click()
        assertNotNull(device.wait(Until.findObject(selector), 3_000))
        assertNull(device.findObject(By.desc("恢复自动选择")))
        assertNotNull(device.findObject(By.text("已保存，下次启动生效")))
        launch(gles = true, available = false)
        val unavailable = device.findObject(By.desc("当前 GLES2，切换到 MobileGlues"))
        assertFalse(unavailable.isEnabled)
        unavailable.click()
        assertNull(device.findObject(By.text("切换到 MobileGlues？")))
        launch(disabled = true)
        assertFalse(device.findObject(selector).isEnabled)
    }
}
