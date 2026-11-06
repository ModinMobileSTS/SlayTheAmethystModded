package io.stamethyst.achievement

import android.view.MotionEvent
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.stamethyst.backend.steamcloud.SteamAchievementCatalog
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SteamAchievementNotificationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun localRendererLoadsFontsAndRareGlowAndLetsGameTouchesThrough() {
        lateinit var view: SteamAchievementNotificationView
        val ready = AtomicBoolean()
        val shown = AtomicBoolean()
        var result: JSONObject? = null
        compose.setContent {
            AndroidView(
                factory = { context -> SteamAchievementNotificationView(context) { ready.set(true) }.also { view = it } },
                modifier = Modifier.size(283.dp, 210.dp),
            )
        }
        compose.waitUntil(10_000) { ready.get() }
        compose.runOnIdle {
            view.show(SteamAchievementCatalog.entries.first { it.apiName == "eternal_one" }, { shown.set(true) }, {})
        }
        compose.waitUntil(5_000) { shown.get() }
        compose.runOnIdle {
            view.evaluateJavascript(
                """JSON.stringify({
                    width: document.querySelector('.DesktopToastPopup').offsetWidth,
                    height: document.querySelector('.DesktopToastPopup').offsetHeight,
                    regular: document.fonts.check('400 12px "Motiva Sans"'),
                    light: document.fonts.check('300 12px "Motiva Sans"'),
                    glows: document.querySelectorAll('.RareAchievementIconGlow').length,
                    title: document.querySelector('.Name').textContent,
                    naturalWidth: document.querySelector('.StandardLogoDimensions img').naturalWidth
                })""".trimIndent(),
            ) { json -> result = JSONObject(org.json.JSONTokener(json).nextValue() as String) }
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
            try { assertFalse(view.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        compose.waitUntil(5_000) { result != null }
        val measured = requireNotNull(result)
        assertEquals(283, measured.getInt("width"))
        assertEquals(70, measured.getInt("height"))
        assertEquals(1, measured.getInt("glows"))
        assertTrue(measured.getBoolean("regular"))
        assertTrue(measured.getBoolean("light"))
        assertTrue(measured.getString("title").isNotBlank())
        assertTrue(measured.getInt("naturalWidth") > 0)
        compose.runOnIdle { view.destroy() }
    }
}
