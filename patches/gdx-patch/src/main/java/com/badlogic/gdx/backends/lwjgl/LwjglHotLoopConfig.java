package com.badlogic.gdx.backends.lwjgl;

import org.lwjgl.opengl.Display;

/** Resolved-once view of the system properties that the LWJGL main loop consults every frame.
 *
 * Every property below is published by the launcher as a {@code -D} JVM argument before the game
 * classes load, and nothing in the runtime calls {@code System.setProperty} for them afterwards, so
 * re-reading them per frame only pays for repeated synchronized {@code Hashtable} lookups on
 * {@code System.getProperties()}. Resolving them into {@code static final} fields lets the JIT fold
 * the checks away, and mirrors the existing pattern in {@code GLTexture} / {@code SpriteBatch}.
 *
 * Values that depend on runtime state (GLES context activity, {@code Display} metrics) are
 * deliberately <em>not</em> cached here; only the property parse result is. Callers combine the
 * cached override with the live context query. */
final class LwjglHotLoopConfig {
	static final String FORCE_DEFAULT_FBO_PROP = "amethyst.lwjgl.force_default_framebuffer";
	static final String DEFAULT_FBO_REBIND_CACHE_PROP = "amethyst.lwjgl.default_framebuffer_rebind_cache";
	static final String POST_RENDER_CLEAR_PROP = "amethyst.lwjgl.diag.post_render_clear";
	static final String VIRTUAL_WIDTH_PROP = "amethyst.gdx.virtual_width";
	static final String VIRTUAL_HEIGHT_PROP = "amethyst.gdx.virtual_height";
	static final String GLFWSTUB_PHYSICAL_WIDTH_PROP = "glfwstub.physicalWidth";
	static final String GLFWSTUB_PHYSICAL_HEIGHT_PROP = "glfwstub.physicalHeight";
	static final String GLOBAL_ATLAS_FILTER_COMPAT_PROP = "amethyst.gdx.global_atlas_filter_compat";
	static final String RUNTIME_TEXTURE_COMPAT_PROP = "amethyst.gdx.runtime_texture_compat";
	static final String RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_PROP = "amethyst.gdx.runtime_texture_compat_periodic_scan";
	static final String GLOBAL_TEXTURE_COMPAT_VERBOSE_PROP = "amethyst.gdx.global_texture_compat_verbose";
	static final String GPU_RESOURCE_DIAG_ENABLED_PROP = "amethyst.gdx.gpu_resource_diag";
	static final String GPU_RESOURCE_SUMMARY_ENABLED_PROP = "amethyst.gdx.gpu_resource_summary";

	/** {@code null} when the property is absent, i.e. when the caller must fall back to the GLES
	 * context default. */
	static final Boolean FORCE_DEFAULT_FRAMEBUFFER_OVERRIDE =
		parseForceDefaultFramebuffer(System.getProperty(FORCE_DEFAULT_FBO_PROP));
	static final boolean DEFAULT_FBO_REBIND_CACHE_ENABLED =
		parseBoolean(System.getProperty(DEFAULT_FBO_REBIND_CACHE_PROP), true);
	static final boolean POST_RENDER_CLEAR_ENABLED = Boolean.getBoolean(POST_RENDER_CLEAR_PROP);
	/** {@code 0} means "not configured". Physical dimensions remain live so the final presenter can
	 * fill a resized Android surface. Virtual dimensions are fixed at JVM launch so the game's
	 * startup-only UI geometry never needs to be rebuilt. */
	static final int PHYSICAL_WIDTH_OVERRIDE = parsePositiveInt(System.getProperty(GLFWSTUB_PHYSICAL_WIDTH_PROP));
	static final int PHYSICAL_HEIGHT_OVERRIDE = parsePositiveInt(System.getProperty(GLFWSTUB_PHYSICAL_HEIGHT_PROP));
	static final int VIRTUAL_WIDTH_OVERRIDE = parsePositiveInt(System.getProperty(VIRTUAL_WIDTH_PROP));
	static final int VIRTUAL_HEIGHT_OVERRIDE = parsePositiveInt(System.getProperty(VIRTUAL_HEIGHT_PROP));

	private static volatile boolean liveSizeBridgeUnavailable = false;

    /** Fixed physical render-buffer width, falling back to the display facade and launch-time override.
     *
     * The Android bridge publishes the cropped game canvas before the JVM starts. Window-mode
     * changes are compositor-only and must not resize the GLFW logical window or render buffer. */
	static int physicalWidth () {
		return preferLivePhysicalSize(nativePhysicalWidth(), displayWidth(), PHYSICAL_WIDTH_OVERRIDE);
	}

	/** Live physical surface height, falling back to the display facade and launch-time override. */
	static int physicalHeight () {
		return preferLivePhysicalSize(nativePhysicalHeight(), displayHeight(), PHYSICAL_HEIGHT_OVERRIDE);
	}

	static int preferLivePhysicalSize (int nativeSize, int displaySize, int fallbackSize) {
		if (nativeSize > 0) return nativeSize;
		if (displaySize > 0) return displaySize;
		return fallbackSize;
	}

	private static int displayWidth () {
		try {
			if (!Display.isCreated()) return 0;
			return Math.max(1, Math.round(Display.getWidth() * PixelScaleCompat.factor()));
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static int displayHeight () {
		try {
			if (!Display.isCreated()) return 0;
			return Math.max(1, Math.round(Display.getHeight() * PixelScaleCompat.factor()));
		} catch (Throwable ignored) {
			return 0;
		}
	}

	/** Fixed logical render-target width chosen by the launcher at startup. */
	static int virtualWidth () {
		return VIRTUAL_WIDTH_OVERRIDE > 0 ? VIRTUAL_WIDTH_OVERRIDE : physicalWidth();
	}

	/** Fixed logical render-target height chosen by the launcher at startup. */
	static int virtualHeight () {
		return VIRTUAL_HEIGHT_OVERRIDE > 0 ? VIRTUAL_HEIGHT_OVERRIDE : physicalHeight();
	}

	private static int nativePhysicalWidth () {
		if (liveSizeBridgeUnavailable) return 0;
		try {
			return org.lwjgl.glfw.CallbackBridge.nativeGetPhysicalWidth();
		} catch (Throwable ignored) {
			liveSizeBridgeUnavailable = true;
			return 0;
		}
	}

	private static int nativePhysicalHeight () {
		if (liveSizeBridgeUnavailable) return 0;
		try {
			return org.lwjgl.glfw.CallbackBridge.nativeGetPhysicalHeight();
		} catch (Throwable ignored) {
			liveSizeBridgeUnavailable = true;
			return 0;
		}
	}
	static final boolean GLOBAL_ATLAS_FILTER_COMPAT_ENABLED =
		parseBoolean(System.getProperty(GLOBAL_ATLAS_FILTER_COMPAT_PROP), true);
	static final boolean RUNTIME_TEXTURE_COMPAT_ENABLED =
		parseBoolean(System.getProperty(RUNTIME_TEXTURE_COMPAT_PROP), false);
	static final boolean RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_ENABLED =
		parseBoolean(System.getProperty(RUNTIME_TEXTURE_COMPAT_PERIODIC_SCAN_PROP), false);
	static final boolean GLOBAL_TEXTURE_COMPAT_VERBOSE_ENABLED =
		parseBoolean(System.getProperty(GLOBAL_TEXTURE_COMPAT_VERBOSE_PROP), false);
	static final boolean GPU_RESOURCE_SUMMARY_LOG_ENABLED =
		parseBoolean(System.getProperty(GPU_RESOURCE_DIAG_ENABLED_PROP), false)
			|| parseBoolean(System.getProperty(GPU_RESOURCE_SUMMARY_ENABLED_PROP), false);

	private LwjglHotLoopConfig () {
	}

	/** Mirrors the historical {@code readBooleanSystemProperty} contract: unrecognised and blank
	 * values fall back to {@code defaultValue} instead of parsing as {@code false}. */
	static boolean parseBoolean (String raw, boolean defaultValue) {
		if (raw == null) return defaultValue;
		raw = raw.trim();
		if (raw.length() == 0) return defaultValue;
		if ("false".equalsIgnoreCase(raw) || "0".equals(raw) || "off".equalsIgnoreCase(raw)) return false;
		if ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "on".equalsIgnoreCase(raw)) return true;
		return defaultValue;
	}

	/** The force-default-FBO switch is opt-out rather than opt-in: any present value that is not an
	 * explicit off token enables it, including a blank one. */
	static Boolean parseForceDefaultFramebuffer (String raw) {
		if (raw == null) return null;
		raw = raw.trim();
		boolean disabled = "0".equals(raw) || "false".equalsIgnoreCase(raw) || "off".equalsIgnoreCase(raw);
		return disabled ? Boolean.FALSE : Boolean.TRUE;
	}

	/** Returns {@code 0} for absent, non-numeric, zero and negative values. */
	static int parsePositiveInt (String raw) {
		if (raw == null) return 0;
		try {
			int value = Integer.parseInt(raw.trim());
			return value > 0 ? value : 0;
		} catch (Throwable ignored) {
			return 0;
		}
	}
}
