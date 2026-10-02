package com.badlogic.gdx.backends.lwjgl;

/** Keeps desktop window icons out of Amethyst's Android LWJGL bridge. */
final class WindowIconCompat {
	private WindowIconCompat () {
	}

	static boolean shouldLoadIcons (int iconCount, String effectiveRendererBackend) {
		// StsLaunchSpec always publishes amethyst.renderer.effective_backend on Android,
		// including non-GLES backends. Do not infer the platform from the GL context type.
		// The bridge's pre-window setIcon path reads icons[1] even for one icon, and
		// incorrectly treats RGBA pixel data as a GLFWImage struct buffer. Android has
		// no desktop window icon; its application icon is supplied by the manifest.
		boolean androidBridge = effectiveRendererBackend != null && !effectiveRendererBackend.trim().isEmpty();
		return iconCount > 0 && !androidBridge;
	}
}
