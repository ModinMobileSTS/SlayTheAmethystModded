package io.stamethyst.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compile-only by default. This verifies modifier/lifecycle wiring, not physical panel Hz. */
class HighRefreshRateModifierInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun voteTracksResumePauseAndWindowFocusWithoutAFrameLoop() {
        lateinit var owner: TestLifecycleOwner
        compose.runOnIdle {
            owner = TestLifecycleOwner()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        val focused = mutableStateOf(true)
        val windowInfo = object : WindowInfo {
            override val isWindowFocused get() = focused.value
        }
        var actual: Modifier = Modifier
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWindowInfo provides windowInfo) {
                val vote = rememberHighRefreshRateModifier()
                SideEffect { actual = vote }
                Box(vote.size(20.dp))
            }
        }
        compose.runOnIdle {
            assertEquals(expectedHighModifier(), actual)
            focused.value = false
        }
        compose.runOnIdle {
            assertEquals(Modifier, actual)
            focused.value = true
        }
        compose.runOnIdle {
            assertEquals(expectedHighModifier(), actual)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        compose.runOnIdle {
            assertEquals(Modifier, actual)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(expectedHighModifier(), actual) }
    }

    @Test fun initiallyPausedContentDoesNotRequestHighUntilResumed() {
        lateinit var owner: TestLifecycleOwner
        compose.runOnIdle {
            owner = TestLifecycleOwner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        val windowInfo = object : WindowInfo { override val isWindowFocused = true }
        var actual: Modifier = Modifier
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWindowInfo provides windowInfo) {
                val vote = rememberHighRefreshRateModifier()
                SideEffect { actual = vote }
                Box(vote.size(20.dp))
            }
        }
        compose.runOnIdle {
            assertEquals(Modifier, actual)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertEquals(expectedHighModifier(), actual) }
    }

    @Test fun diagnosticCanDisableAndEnableTheSameProductionModifier() {
        lateinit var owner: TestLifecycleOwner
        compose.runOnIdle {
            owner = TestLifecycleOwner()
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        val enabled = mutableStateOf(false)
        val windowInfo = object : WindowInfo { override val isWindowFocused = true }
        var actual: Modifier = Modifier
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWindowInfo provides windowInfo) {
                val vote = rememberHighRefreshRateModifier(enabled.value)
                SideEffect { actual = vote }
                Box(vote.size(20.dp))
            }
        }
        compose.runOnIdle {
            assertEquals(Modifier, actual)
            enabled.value = true
        }
        compose.runOnIdle {
            assertEquals(expectedHighModifier(), actual)
            enabled.value = false
        }
        compose.runOnIdle { assertEquals(Modifier, actual) }
    }

    private fun expectedHighModifier(): Modifier =
        if (Build.VERSION.SDK_INT >= 35) Modifier.preferredFrameRate(FrameRateCategory.High) else Modifier

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
}
