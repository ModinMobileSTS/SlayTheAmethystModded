package com.badlogic.gdx.graphics;

import com.badlogic.gdx.InputAdapter;
import optispire.debug.ReleaseNoticeFeed;
import optispire.debug.ReleaseOverlayInput;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseOverlayInputTest {
    private ReleaseNoticeFeed feed() {
        for (int i = 0; i < 30; i++) RamSaverReleaseEvents.record(9000, "input", "input.png",
                RamSaverReleaseEvents.TEXTURE, "test", 0, 1);
        ReleaseNoticeFeed feed = new ReleaseNoticeFeed(); feed.update(1L);
        return feed;
    }

    @Test public void wheelAndTouchScrollHistoryAndLatestButtonReturnsToLive() {
        ReleaseNoticeFeed feed = feed();
        ReleaseOverlayInput input = new ReleaseOverlayInput(feed);
        input.bounds(10, 10, 400, 184, 23);
        assertFalse(input.wheel(2, 1, 1)); assertEquals(0, feed.offset());
        assertTrue(input.wheel(2, 100, 50)); assertEquals(2, feed.offset());
        assertTrue(input.touchDown(100, 140, 0, 0));
        assertTrue(input.touchDragged(100, 94, 0)); assertEquals(4, feed.offset());
        assertTrue(input.touchUp(500, 94, 0, 0));
        assertTrue(input.suppressGamePointer(500, 94));
        input.finishInputFrame(); assertFalse(input.suppressGamePointer(500, 94));
        input.touchDown(350, 15, 0, 0); assertEquals(0, feed.offset()); input.touchUp(350, 15, 0, 0);
    }

    @Test public void outsideGesturesAndKeysRetainOriginalDelegateWhilePanelDragDoesNotLeak() {
        ReleaseOverlayInput input = new ReleaseOverlayInput(feed());
        final int[] calls = new int[4];
        input.delegate(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int p, int b) { calls[0]++; return true; }
            @Override public boolean touchDragged(int x, int y, int p) { calls[1]++; return true; }
            @Override public boolean touchUp(int x, int y, int p, int b) { calls[2]++; return true; }
            @Override public boolean keyDown(int key) { calls[3]++; return true; }
        });
        input.bounds(10, 10, 400, 184, 23);
        assertTrue(input.touchDown(500, 500, 0, 0));
        assertFalse("Gesture begun in game must keep working when it crosses the panel", input.suppressGamePointer(100, 50));
        assertTrue(input.touchDragged(600, 600, 0));
        assertTrue(input.touchUp(600, 600, 0, 0));
        assertTrue(input.keyDown(1));
        assertArrayEquals(new int[] {1, 1, 1, 1}, calls);
        input.touchDown(100, 50, 0, 0);
        input.touchDragged(500, 500, 0); input.touchUp(500, 500, 0, 0);
        assertArrayEquals(new int[] {1, 1, 1, 1}, calls);
    }

    @Test public void scrollbarIsClampedAndCanReachOldestRetainedPage() {
        ReleaseNoticeFeed feed = feed();
        ReleaseOverlayInput input = new ReleaseOverlayInput(feed);
        input.bounds(10, 10, 400, 184, 23);
        input.touchDown(405, 150, 0, 0);
        input.touchDragged(405, 1000, 0);
        assertEquals(feed.maxOffset(), feed.offset());
        input.touchDragged(405, -1000, 0);
        assertEquals(0, feed.offset());
        input.touchUp(405, -1000, 0, 0);
    }
}
