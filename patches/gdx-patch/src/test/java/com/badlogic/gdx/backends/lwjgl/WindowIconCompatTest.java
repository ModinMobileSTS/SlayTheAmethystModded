package com.badlogic.gdx.backends.lwjgl;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WindowIconCompatTest {
	@Test
	public void androidBridgeSkipsSingleAndMultipleIconsForAnyBackend () {
		for (String backend : new String[] {"opengles_mobileglues", "opengles2_native", "opengles2",
			"opengles3_desktopgl_zink_kopper", "vulkan_zink", "future_backend"}) {
			assertFalse(WindowIconCompat.shouldLoadIcons(1, backend));
			assertFalse(WindowIconCompat.shouldLoadIcons(2, backend));
		}
	}

	@Test
	public void desktopRetainsIconLoading () {
		assertTrue(WindowIconCompat.shouldLoadIcons(1, ""));
		assertTrue(WindowIconCompat.shouldLoadIcons(2, null));
		assertTrue(WindowIconCompat.shouldLoadIcons(1, "  "));
	}

	@Test
	public void noIconsNeverLoads () {
		assertFalse(WindowIconCompat.shouldLoadIcons(0, ""));
		assertFalse(WindowIconCompat.shouldLoadIcons(0, "opengles_mobileglues"));
	}
}
