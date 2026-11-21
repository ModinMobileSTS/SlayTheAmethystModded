package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkshopModeMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reversingDuringHeaderRetractionKeepsBothPagesUntilTheHandoffCompletes() {
        compose.mainClock.autoAdvance = false
        val ai = mutableStateOf(false)
        lateinit var motion: WorkshopModeMotion
        compose.setContent {
            motion = rememberWorkshopModeMotion(ai.value)
            Box(Modifier.testTag("header").workshopModeHeader(motion, true).size(120.dp))
        }
        fun height() = compose.onNodeWithTag("header").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        val expanded = height()
        compose.runOnIdle { ai.value = true }
        compose.mainClock.advanceTimeBy(96)
        val mid = height()
        assertTrue(mid > 0f && mid < expanded)
        compose.runOnIdle {
            assertTrue(motion.hasBrowse && motion.hasChat)
            ai.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(height() in 1f..(expanded - 1f))
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(expanded, height(), .5f)
        compose.runOnIdle { assertTrue(motion.hasBrowse && !motion.hasChat) }
    }
}
