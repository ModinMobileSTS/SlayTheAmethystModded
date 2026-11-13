package io.stamethyst.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherBannerScrollTest {
    @Test
    fun fastAndSlowScrollsHaveTheSameAnimationTimelineInBothDirections() = withAnimationClock { clock ->
        val slowPage = LauncherBannerPageState()
        val fastPage = LauncherBannerPageState()
        val slow = LauncherBannerScrollConnection(slowPage, this, 8f)
        val fast = LauncherBannerScrollConnection(fastPage, this, 8f)

        slow.scroll(-8f)
        fast.scroll(-4_000f)
        assertEquals("Even a large scroll must not snap the header", 0f, fastPage.collapseFraction, 0f)
        clock.frame(0)
        for (time in listOf(64L, 128L, 192L, 256L)) {
            slow.scroll(-8f)
            fast.scroll(-4_000f, source = NestedScrollSource.SideEffect)
            clock.frame(time)
            assertTrue(slowPage.collapseFraction > 0f && slowPage.collapseFraction < 1f)
            assertEquals(slowPage.collapseFraction, fastPage.collapseFraction, 0.0001f)
        }
        clock.frame(300)
        assertEquals("Continuous scrolling must not restart the animation", 1f, slowPage.collapseFraction, 0f)
        assertEquals(1f, fastPage.collapseFraction, 0f)

        slow.scroll(8f)
        fast.scroll(4_000f)
        assertEquals(1f, fastPage.collapseFraction, 0f)
        clock.frame(400)
        clock.frame(500)
        assertTrue(slowPage.collapseFraction > 0f && slowPage.collapseFraction < 1f)
        assertEquals(slowPage.collapseFraction, fastPage.collapseFraction, 0.0001f)
        clock.frame(700)
        assertEquals(0f, slowPage.collapseFraction, 0f)
        assertEquals(0f, fastPage.collapseFraction, 0f)
    }

    @Test
    fun smallDirectionJitterIsIgnoredAndReversalContinuesFromCurrentFrame() = withAnimationClock { clock ->
        val page = LauncherBannerPageState()
        val connection = LauncherBannerScrollConnection(page, this, 8f)
        repeat(5) {
            connection.scroll(-4f)
            connection.scroll(4f)
        }
        clock.frame(0)
        clock.frame(100)
        assertEquals(0f, page.collapseFraction, 0f)

        connection.scroll(-4f)
        connection.scroll(-4f)
        clock.frame(200)
        clock.frame(320)
        val beforeReverse = page.collapseFraction
        assertTrue(beforeReverse > 0f && beforeReverse < 1f)
        connection.scroll(4f)
        connection.scroll(4f)
        assertEquals("A reversal must not jump to either endpoint", beforeReverse, page.collapseFraction, 0f)
        clock.frame(400)
        assertTrue(page.collapseFraction < beforeReverse)
        clock.frame(700)
        assertEquals(0f, page.collapseFraction, 0f)
    }

    @Test
    fun endingAFlingEarlyDoesNotSnapOrChangeTheAnimationDestination() = withAnimationClock { clock ->
        val page = LauncherBannerPageState()
        val connection = LauncherBannerScrollConnection(page, this, 8f)
        connection.scroll(-800f)
        clock.frame(0)
        clock.frame(48)
        val beforeFlingEnd = page.collapseFraction
        assertTrue(beforeFlingEnd > 0f && beforeFlingEnd < 0.5f)
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity(0f, -20_000f), Velocity.Zero))
        assertEquals(beforeFlingEnd, page.collapseFraction, 0f)
        clock.frame(300)
        assertEquals(1f, page.collapseFraction, 0f)
    }

    @Test
    fun boundariesAndHorizontalScrollDoNotConsumeContentMovement() = withAnimationClock { clock ->
        val page = LauncherBannerPageState()
        val connection = LauncherBannerScrollConnection(page, this, 8f)
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, -800f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset(800f, 0f), Offset.Zero, NestedScrollSource.UserInput))
        connection.scroll(0f, availableY = -800f)
        clock.frame(0)
        clock.frame(300)
        assertEquals("Upward overscroll alone must not collapse a short page", 0f, page.collapseFraction, 0f)

        val collapsedPage = LauncherBannerPageState(initialCollapseFraction = 1f)
        val collapsedConnection = LauncherBannerScrollConnection(collapsedPage, this, 8f)
        collapsedConnection.scroll(0f, availableY = 800f)
        clock.frame(400)
        clock.frame(500)
        assertTrue(collapsedPage.collapseFraction > 0f && collapsedPage.collapseFraction < 1f)
        clock.frame(700)
        assertEquals("Downward overscroll at the top must reveal the header", 0f, collapsedPage.collapseFraction, 0f)
    }

    @Test
    fun explicitExpandReplacesAnInFlightCollapseWithoutCompetingWriters() = withAnimationClock { clock ->
        val page = LauncherBannerPageState()
        val connection = LauncherBannerScrollConnection(page, this, 8f)
        connection.scroll(-800f)
        clock.frame(0)
        clock.frame(100)
        val beforeExpand = page.collapseFraction
        launch(start = CoroutineStart.UNDISPATCHED) { page.expand() }
        connection.scroll(800f, source = NestedScrollSource.SideEffect)
        assertEquals(beforeExpand, page.collapseFraction, 0f)
        clock.frame(200)
        assertTrue(page.collapseFraction < beforeExpand)
        clock.frame(500)
        assertEquals(0f, page.collapseFraction, 0f)
    }

    @Test
    fun restoringDuringAnimationUsesItsDestinationAndKeepsExpandedInset() = withAnimationClock { clock ->
        val page = LauncherBannerPageState().apply { expandedHeightPx = 232 }
        LauncherBannerScrollConnection(page, this, 8f).scroll(-800f)
        clock.frame(0)
        clock.frame(100)
        assertTrue(page.collapseFraction > 0f && page.collapseFraction < 1f)
        val saverScope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(LauncherBannerPageState.Saver) { saverScope.save(page) }
        val restored = requireNotNull(LauncherBannerPageState.Saver.restore(requireNotNull(saved)))
        assertEquals(1f, restored.collapseFraction, 0f)
        assertEquals(232, restored.expandedHeightPx)
    }

    private fun LauncherBannerScrollConnection.scroll(
        consumedY: Float,
        availableY: Float = 0f,
        source: NestedScrollSource = NestedScrollSource.UserInput,
    ) {
        assertEquals(Offset.Zero, onPostScroll(Offset(0f, consumedY), Offset(0f, availableY), source))
    }

    private fun withAnimationClock(block: suspend CoroutineScope.(BroadcastFrameClock) -> Unit) = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            try {
                block(clock)
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    }

    private suspend fun BroadcastFrameClock.frame(timeMillis: Long) {
        sendFrame(timeMillis * 1_000_000)
        yield()
    }
}
