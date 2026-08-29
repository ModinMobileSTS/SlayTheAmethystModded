package com.badlogic.gdx.backends.lwjgl;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Guards the parse contracts that the per-frame property snapshot replaced. The snapshot fields
 * themselves resolve at class initialization, so these tests exercise the parsers instead. */
public class LwjglHotLoopConfigTest {
	@Test
	public void parseBoolean_fallsBackForAbsentBlankAndUnknownValues () {
		assertTrue(LwjglHotLoopConfig.parseBoolean(null, true));
		assertFalse(LwjglHotLoopConfig.parseBoolean(null, false));
		assertTrue(LwjglHotLoopConfig.parseBoolean("   ", true));
		assertFalse(LwjglHotLoopConfig.parseBoolean("", false));
		assertTrue(LwjglHotLoopConfig.parseBoolean("maybe", true));
		assertFalse(LwjglHotLoopConfig.parseBoolean("maybe", false));
	}

	@Test
	public void parseBoolean_acceptsAllHistoricalTokens () {
		assertTrue(LwjglHotLoopConfig.parseBoolean("true", false));
		assertTrue(LwjglHotLoopConfig.parseBoolean(" TRUE ", false));
		assertTrue(LwjglHotLoopConfig.parseBoolean("1", false));
		assertTrue(LwjglHotLoopConfig.parseBoolean("On", false));
		assertFalse(LwjglHotLoopConfig.parseBoolean("false", true));
		assertFalse(LwjglHotLoopConfig.parseBoolean(" FALSE ", true));
		assertFalse(LwjglHotLoopConfig.parseBoolean("0", true));
		assertFalse(LwjglHotLoopConfig.parseBoolean("Off", true));
	}

	@Test
	public void parseForceDefaultFramebuffer_returnsNullWhenUnset () {
		assertNull(LwjglHotLoopConfig.parseForceDefaultFramebuffer(null));
	}

	@Test
	public void parseForceDefaultFramebuffer_treatsAnyNonOffValueAsEnabled () {
		assertEquals(Boolean.TRUE, LwjglHotLoopConfig.parseForceDefaultFramebuffer("true"));
		assertEquals(Boolean.TRUE, LwjglHotLoopConfig.parseForceDefaultFramebuffer("1"));
		// An explicitly configured but meaningless value stays enabled, matching the previous
		// opt-out semantics of the property.
		assertEquals(Boolean.TRUE, LwjglHotLoopConfig.parseForceDefaultFramebuffer(""));
		assertEquals(Boolean.TRUE, LwjglHotLoopConfig.parseForceDefaultFramebuffer("yes"));
	}

	@Test
	public void parseForceDefaultFramebuffer_honoursOffTokens () {
		assertEquals(Boolean.FALSE, LwjglHotLoopConfig.parseForceDefaultFramebuffer("0"));
		assertEquals(Boolean.FALSE, LwjglHotLoopConfig.parseForceDefaultFramebuffer("false"));
		assertEquals(Boolean.FALSE, LwjglHotLoopConfig.parseForceDefaultFramebuffer(" OFF "));
	}

	@Test
	public void parsePositiveInt_rejectsNonPositiveAndMalformedValues () {
		assertEquals(0, LwjglHotLoopConfig.parsePositiveInt(null));
		assertEquals(0, LwjglHotLoopConfig.parsePositiveInt(""));
		assertEquals(0, LwjglHotLoopConfig.parsePositiveInt("abc"));
		assertEquals(0, LwjglHotLoopConfig.parsePositiveInt("0"));
		assertEquals(0, LwjglHotLoopConfig.parsePositiveInt("-720"));
	}

	@Test
	public void parsePositiveInt_acceptsTrimmedPositiveValues () {
		assertEquals(2400, LwjglHotLoopConfig.parsePositiveInt("2400"));
		assertEquals(1080, LwjglHotLoopConfig.parsePositiveInt(" 1080 "));
	}

	@Test
	public void snapshotDefaults_matchLauncherlessDefaults () {
		// The launcher always publishes these as -D arguments; a plain unit-test JVM has none of
		// them, so the snapshot must land on the historical defaults.
		assertNull(LwjglHotLoopConfig.FORCE_DEFAULT_FRAMEBUFFER_OVERRIDE);
		assertTrue(LwjglHotLoopConfig.DEFAULT_FBO_REBIND_CACHE_ENABLED);
		assertFalse(LwjglHotLoopConfig.POST_RENDER_CLEAR_ENABLED);
		assertEquals(0, LwjglHotLoopConfig.PHYSICAL_WIDTH_OVERRIDE);
		assertEquals(0, LwjglHotLoopConfig.PHYSICAL_HEIGHT_OVERRIDE);
		assertEquals(0, LwjglHotLoopConfig.VIRTUAL_WIDTH_OVERRIDE);
		assertEquals(0, LwjglHotLoopConfig.VIRTUAL_HEIGHT_OVERRIDE);
		assertTrue(LwjglHotLoopConfig.GLOBAL_ATLAS_FILTER_COMPAT_ENABLED);
		assertFalse(LwjglHotLoopConfig.RUNTIME_TEXTURE_COMPAT_ENABLED);
		assertFalse(LwjglHotLoopConfig.RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_ENABLED);
		assertFalse(LwjglHotLoopConfig.GLOBAL_TEXTURE_COMPAT_VERBOSE_ENABLED);
		assertFalse(LwjglHotLoopConfig.GPU_RESOURCE_SUMMARY_LOG_ENABLED);
	}

	@Test
	public void physicalSizeSource_prefersAndroidSurfaceBridgeOverStaleDisplaySize () {
		assertEquals(1728, LwjglHotLoopConfig.preferLivePhysicalSize(1728, 2400, 2400));
		assertEquals(1080, LwjglHotLoopConfig.preferLivePhysicalSize(1080, 1080, 1080));
	}

	@Test
	public void physicalSizeSource_fallsBackWhenAndroidSurfaceIsUnavailable () {
		assertEquals(2400, LwjglHotLoopConfig.preferLivePhysicalSize(0, 2400, 1080));
		assertEquals(1080, LwjglHotLoopConfig.preferLivePhysicalSize(0, 0, 1080));
	}
}
