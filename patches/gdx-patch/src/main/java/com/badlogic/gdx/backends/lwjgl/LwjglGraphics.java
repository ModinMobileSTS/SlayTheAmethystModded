/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backends.lwjgl;

import java.awt.Canvas;
import java.awt.Toolkit;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Cursor.SystemCursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.SharedLibraryLoader;

/** An implementation of the {@link Graphics} interface based on Lwjgl.
 * @author mzechner */
public class LwjglGraphics implements Graphics {
	private static final String RENDER_SCALE_PROP = "amethyst.gdx.render_scale";
	private static final String VIRTUAL_WIDTH_PROP = "amethyst.gdx.virtual_width";
	private static final String VIRTUAL_HEIGHT_PROP = "amethyst.gdx.virtual_height";

	/** The suppored OpenGL extensions */
	static Array<String> extensions;
	static GLVersion glVersion;
	static boolean isGLESContext;

	GL20 gl20;
	GL30 gl30;
	long frameId = -1;
	float deltaTime = 0;
	long frameStart = 0;
	int frames = 0;
	int fps;
	long lastTime = System.nanoTime();
	Canvas canvas;
	boolean vsync = false;
	boolean resize = false;
	LwjglApplicationConfiguration config;
	BufferFormat bufferFormat = new BufferFormat(8, 8, 8, 8, 16, 8, 0, false);
	volatile boolean isContinuous = true;
	volatile boolean requestRendering = false;
	boolean softwareMode;
	boolean usingGL30;

	LwjglGraphics (LwjglApplicationConfiguration config) {
		this.config = config;
	}

	LwjglGraphics (Canvas canvas) {
		this.config = new LwjglApplicationConfiguration();
		config.width = canvas.getWidth();
		config.height = canvas.getHeight();
		this.canvas = canvas;
	}

	LwjglGraphics (Canvas canvas, LwjglApplicationConfiguration config) {
		this.config = config;
		this.canvas = canvas;
	}

	public GL20 getGL20 () {
		return gl20;
	}

	public int getHeight () {
		if (canvas != null)
			return Math.max(1, canvas.getHeight());
		int configured = readPositiveIntProperty(VIRTUAL_HEIGHT_PROP);
		if (configured > 0) return configured;
		return scaledLogicalSize((int)(Display.getHeight() * PixelScaleCompat.factor()));
	}

	public int getWidth () {
		if (canvas != null)
			return Math.max(1, canvas.getWidth());
		int configured = readPositiveIntProperty(VIRTUAL_WIDTH_PROP);
		if (configured > 0) return configured;
		return scaledLogicalSize((int)(Display.getWidth() * PixelScaleCompat.factor()));
	}

	@Override
	public int getBackBufferWidth () {
		return getWidth();
	}

	@Override
	public int getBackBufferHeight () {
		return getHeight();
	}

	private static int scaledLogicalSize (int physicalSize) {
		if (physicalSize <= 0) return 1;
		float scale = readConfiguredRenderScale();
		if (scale >= 0.999f) return physicalSize;
		return Math.max(1, Math.round(physicalSize * scale));
	}

	private static float readConfiguredRenderScale () {
		String configured = System.getProperty(RENDER_SCALE_PROP);
		if (configured == null) return 1f;
		try {
			float parsed = Float.parseFloat(configured.trim());
			if (Float.isNaN(parsed) || Float.isInfinite(parsed)) return 1f;
			if (parsed < 0.1f) return 0.1f;
			if (parsed > 1f) return 1f;
			return parsed;
		} catch (Throwable ignored) {
			return 1f;
		}
	}

	private static int readPositiveIntProperty (String property) {
		String raw = System.getProperty(property);
		if (raw == null) return 0;
		try {
			int value = Integer.parseInt(raw.trim());
			return value > 0 ? value : 0;
		} catch (Throwable ignored) {
			return 0;
		}
	}

	public boolean isGL20Available () {
		return gl20 != null;
	}

	public long getFrameId () {
		return frameId;
	}

	public float getDeltaTime () {
		return deltaTime;
	}

	public float getRawDeltaTime () {
		return deltaTime;
	}

	public GraphicsType getType () {
		return GraphicsType.LWJGL;
	}

	public GLVersion getGLVersion () {
		return glVersion;
	}

	public int getFramesPerSecond () {
		return fps;
	}

	void updateTime () {
		long time = System.nanoTime();
		deltaTime = (time - lastTime) / 1000000000.0f;
		lastTime = time;

		if (time - frameStart >= 1000000000) {
			fps = frames;
			frames = 0;
			frameStart = time;
		}
		frames++;
	}

	void setupDisplay () throws LWJGLException {
		if (config.useHDPI) {
			System.setProperty("org.lwjgl.opengl.Display.enableHighDPI", "true");
		}

		if (canvas != null) {
			Display.setParent(canvas);
		} else {
			boolean displayCreated = false;

			if(!config.fullscreen) {
				displayCreated = setWindowedMode(config.width, config.height);
			} else {
				DisplayMode bestMode = null;
				for(DisplayMode mode: getDisplayModes()) {
					if(mode.width == config.width && mode.height == config.height) {
						if(bestMode == null || bestMode.refreshRate < this.getDisplayMode().refreshRate) {
							bestMode = mode;
						}
					}
				}
				if(bestMode == null) {
					bestMode = this.getDisplayMode();
				}
				displayCreated = setFullscreenMode(bestMode);
			}
			if (!displayCreated) {
				if (config.setDisplayModeCallback != null) {
					config = config.setDisplayModeCallback.onFailure(config);
					if (config != null) {
						displayCreated = setWindowedMode(config.width, config.height);
					}
				}
				if (!displayCreated) {
					throw new GdxRuntimeException("Couldn't set display mode " + config.width + "x" + config.height + ", fullscreen: "
						+ config.fullscreen);
				}
			}
			if (config.iconPaths.size > 0) {
				ArrayList<ByteBuffer> icons = new ArrayList<ByteBuffer>(config.iconPaths.size);
				for (int i = 0, n = config.iconPaths.size; i < n; i++) {
					try {
						Pixmap pixmap = new Pixmap(Gdx.files.getFileHandle(config.iconPaths.get(i), config.iconFileTypes.get(i)));
						if (pixmap.getFormat() != Format.RGBA8888) {
							Pixmap rgba = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), Format.RGBA8888);
							rgba.drawPixmap(pixmap, 0, 0);
							pixmap.dispose();
							pixmap = rgba;
						}
						ByteBuffer iconBuffer = ByteBuffer.allocateDirect(pixmap.getPixels().limit());
						iconBuffer.put(pixmap.getPixels());
						iconBuffer.flip();
						icons.add(iconBuffer);
						pixmap.dispose();
					} catch (Throwable t) {
						System.out.println("[gdx-patch] Skip icon load failure: " + config.iconPaths.get(i) + " (" + t + ")");
					}
				}
				if (!icons.isEmpty()) {
					try {
						Display.setIcon(icons.toArray(new ByteBuffer[0]));
					} catch (Throwable t) {
						System.out.println("[gdx-patch] Skip Display.setIcon failure: " + t);
					}
				}
			}
		}
		Display.setTitle(config.title);
		Display.setResizable(config.resizable);
		Display.setInitialBackground(config.initialBackgroundColor.r, config.initialBackgroundColor.g,
			config.initialBackgroundColor.b);

		Display.setLocation(config.x, config.y);
		createDisplayPixelFormat(config.useGL30, config.gles30ContextMajorVersion, config.gles30ContextMinorVersion);
		initiateGL();
	}

	/**
	 * Only needed when setupDisplay() is not called.
	 */
	void initiateGL() {
		extractVersion();
		extractExtensions();
		initiateGLInstances();
	}

	private static void extractVersion () {
		String versionString = org.lwjgl.opengl.GL11.glGetString(GL11.GL_VERSION);
		String vendorString = org.lwjgl.opengl.GL11.glGetString(GL11.GL_VENDOR);
		String rendererString = org.lwjgl.opengl.GL11.glGetString(GL11.GL_RENDERER);
		boolean rendererForcesGLES = isGlesBackedRenderer();
		boolean versionLooksLikeGLES = versionString != null && versionString.toLowerCase().contains("opengl es");
		isGLESContext = versionLooksLikeGLES || rendererForcesGLES;
		Application.ApplicationType applicationType = isGLESContext ? Application.ApplicationType.Android
			: Application.ApplicationType.Desktop;
		String versionStringForParsing = normalizeVersionStringForParsing(versionString, rendererForcesGLES);
		glVersion = new GLVersion(applicationType, versionStringForParsing, vendorString, rendererString);
		System.out.println("[gdx-patch] GL context detected: type=" + applicationType + ", version=" + versionString
			+ ", vendor=" + vendorString + ", renderer=" + rendererString
			+ ", glesBacked=" + isGLESContext + ", rendererBackend=" + effectiveRendererBackendId());
	}

	private static boolean isGlesBackedRenderer () {
		String rendererBackend = effectiveRendererBackendId();
		return "opengles_mobileglues".equals(rendererBackend);
	}

	private static String effectiveRendererBackendId () {
		return System.getProperty("amethyst.renderer.effective_backend", "").trim();
	}

	private static String normalizeVersionStringForParsing (String rawVersionString, boolean rendererForcesGLES) {
		if (!rendererForcesGLES || rawVersionString == null) return rawVersionString;
		if (rawVersionString.toLowerCase().contains("opengl es")) return rawVersionString;

		String requestedEsVersion = System.getenv("LIBGL_ES");
		if ("3".equals(requestedEsVersion)) {
			return "OpenGL ES 3.0 (" + rawVersionString + ")";
		}
		return "OpenGL ES 2.0 (" + rawVersionString + ")";
	}

	private static void extractExtensions () {
		extensions = new Array<String>();
		if (!isGLESContext && glVersion.isVersionEqualToOrHigher(3, 2) && addExtensionsFromStringi()) return;
		addExtensionsFromLegacyString();
		if (extensions.size == 0) addExtensionsFromStringi();
	}

	private static void addExtensionsFromLegacyString () {
		String extensionString = org.lwjgl.opengl.GL11.glGetString(GL20.GL_EXTENSIONS);
		if (extensionString == null || extensionString.length() == 0) return;
		String[] parts = extensionString.split(" ");
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part != null && part.length() > 0) extensions.add(part);
		}
	}

	private static boolean addExtensionsFromStringi () {
		try {
			int numExtensions = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
			if (numExtensions <= 0) return false;
			for (int i = 0; i < numExtensions; ++i) {
				String extension = org.lwjgl.opengl.GL30.glGetStringi(GL20.GL_EXTENSIONS, i);
				if (extension != null && extension.length() > 0) extensions.add(extension);
			}
			return true;
		} catch (Throwable t) {
			System.out.println("[gdx-patch] glGetStringi extension probe failed: " + t);
			return false;
		}
	}

	/** @return whether the supported OpenGL (not ES) version is compatible with OpenGL ES 3.x. */
	private static boolean fullCompatibleWithGLES3 () {
		// OpenGL ES 3.0 is compatible with OpenGL 4.3 core, see http://en.wikipedia.org/wiki/OpenGL_ES#OpenGL_ES_3.0
		return glVersion.isVersionEqualToOrHigher(4, 3);
	}

	/** @return whether the supported OpenGL (not ES) version is compatible with OpenGL ES 2.x. */
	private static boolean fullCompatibleWithGLES2 () {
		// OpenGL ES 2.0 is compatible with OpenGL 4.1 core
		// see https://www.opengl.org/registry/specs/ARB/ES2_compatibility.txt
		return glVersion.isVersionEqualToOrHigher(4, 1) || extensions.contains("GL_ARB_ES2_compatibility", false);
	}

	private static boolean supportsFBO () {
		if (isGLESContext && glVersion.isVersionEqualToOrHigher(2, 0)) return true;
		// FBO is in core since OpenGL 3.0, see https://www.opengl.org/wiki/Framebuffer_Object
		return glVersion.isVersionEqualToOrHigher(3, 0) || extensions.contains("GL_EXT_framebuffer_object", false)
			|| extensions.contains("GL_ARB_framebuffer_object", false);
	}

	private static void applyGLESWindowHints (boolean useGL30, int gles30ContextMajor, int gles30ContextMinor) {
		try {
			GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API);
			if (useGL30) {
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, Math.max(3, gles30ContextMajor));
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, Math.max(0, gles30ContextMinor));
			} else {
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
				GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0);
			}
		} catch (Throwable t) {
			System.out.println("[gdx-patch] Failed to set GLFW GLES hints: " + t);
		}
	}

	private void createDisplayPixelFormat (boolean useGL30, int gles30ContextMajor, int gles30ContextMinor) {
		try {
			applyGLESWindowHints(useGL30, gles30ContextMajor, gles30ContextMinor);
			if (useGL30) {
				ContextAttribs context = new ContextAttribs(gles30ContextMajor, gles30ContextMinor).withForwardCompatible(false)
					.withProfileCore(true);
				try {
					Display.create(new PixelFormat(config.r + config.g + config.b, config.a, config.depth, config.stencil,
						config.samples), context);
				} catch (Exception e) {
					System.out.println("LwjglGraphics: OpenGL " + gles30ContextMajor + "." + gles30ContextMinor
						+ "+ core profile (GLES 3.0) not supported.");
					createDisplayPixelFormat(false, gles30ContextMajor, gles30ContextMinor);
					return;
				}
				System.out.println("LwjglGraphics: created OpenGL " + gles30ContextMajor + "." + gles30ContextMinor
					+ "+ core profile (GLES 3.0) context. This is experimental!");
				usingGL30 = true;
			} else {
				Display
					.create(new PixelFormat(config.r + config.g + config.b, config.a, config.depth, config.stencil, config.samples));
				usingGL30 = false;
			}
			bufferFormat = new BufferFormat(config.r, config.g, config.b, config.a, config.depth, config.stencil, config.samples,
				false);
		} catch (Exception ex) {
			Display.destroy();
			try {
				Thread.sleep(200);
			} catch (InterruptedException ignored) {
			}
			try {
				applyGLESWindowHints(false, gles30ContextMajor, gles30ContextMinor);
				Display.create(new PixelFormat(0, 16, 8));
				if (getDisplayMode().bitsPerPixel == 16) {
					bufferFormat = new BufferFormat(5, 6, 5, 0, 16, 8, 0, false);
				}
				if (getDisplayMode().bitsPerPixel == 24) {
					bufferFormat = new BufferFormat(8, 8, 8, 0, 16, 8, 0, false);
				}
				if (getDisplayMode().bitsPerPixel == 32) {
					bufferFormat = new BufferFormat(8, 8, 8, 8, 16, 8, 0, false);
				}
			} catch (Exception ex2) {
				Display.destroy();
				try {
					Thread.sleep(200);
				} catch (InterruptedException ignored) {
				}
				try {
					applyGLESWindowHints(false, gles30ContextMajor, gles30ContextMinor);
					Display.create(new PixelFormat());
				} catch (Exception ex3) {
					if (!softwareMode && config.allowSoftwareMode) {
						softwareMode = true;
						System.setProperty("org.lwjgl.opengl.Display.allowSoftwareOpenGL", "true");
						createDisplayPixelFormat(useGL30, gles30ContextMajor, gles30ContextMinor);
						return;
					}
					throw new GdxRuntimeException("OpenGL is not supported by the video driver.", ex3);
				}
				if (getDisplayMode().bitsPerPixel == 16) {
					bufferFormat = new BufferFormat(5, 6, 5, 0, 8, 0, 0, false);
				}
				if (getDisplayMode().bitsPerPixel == 24) {
					bufferFormat = new BufferFormat(8, 8, 8, 0, 8, 0, 0, false);
				}
				if (getDisplayMode().bitsPerPixel == 32) {
					bufferFormat = new BufferFormat(8, 8, 8, 8, 8, 0, 0, false);
				}
			}
		}
	}

	public void initiateGLInstances () {
		if (usingGL30) {
			gl30 = new LwjglGL30FboBindCompat();
			gl20 = gl30;
		} else {
			gl20 = isGLESContext ? new LwjglGL20FboStatusCompat() : new LwjglGL20();
		}

		if (!glVersion.isVersionEqualToOrHigher(2, 0))
			throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
				+ GL11.glGetString(GL11.GL_VERSION) + "\n" + glVersion.getDebugVersionString());

		if (!supportsFBO()) {
			throw new GdxRuntimeException("OpenGL 2.0 or higher with the FBO extension is required. OpenGL version: "
				+ GL11.glGetString(GL11.GL_VERSION) + ", FBO extension: false\n" + glVersion.getDebugVersionString());
		}

		Gdx.gl = gl20;
		Gdx.gl20 = gl20;
		Gdx.gl30 = gl30;
	}

	/**
	 * On GLES-backed contexts, EXTFramebufferObject entry points are often missing.
	 * Prefer GL30 core FBO functions and keep legacy EXT fallback.
	 */
	private static class LwjglGL20FboStatusCompat extends LwjglGL20 {
		private static IntBuffer limitBuffer (IntBuffer source, int count) {
			IntBuffer duplicate = source.duplicate();
			int cappedCount = Math.min(Math.max(count, 0), duplicate.remaining());
			duplicate.limit(duplicate.position() + cappedCount);
			return duplicate;
		}

		private static boolean canUseCorePath () {
			try {
				// Some Android bridge-backed contexts report isCurrent=false even though core FBO entry points work.
				return Display.isCreated();
			} catch (Throwable ignored) {
				return true;
			}
		}

		@Override
		public void glBindFramebuffer (int target, int framebuffer) {
			int remappedFramebuffer = LwjglApplication.remapRequestedFramebufferHandle(framebuffer);
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glBindFramebuffer(target, remappedFramebuffer);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glBindFramebuffer(target, remappedFramebuffer);
		}

		@Override
		public void glBindRenderbuffer (int target, int renderbuffer) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glBindRenderbuffer(target, renderbuffer);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glBindRenderbuffer(target, renderbuffer);
		}

		@Override
		public int glCheckFramebufferStatus (int target) {
			try {
				if (canUseCorePath()) {
					return org.lwjgl.opengl.GL30.glCheckFramebufferStatus(target);
				}
			} catch (Throwable ignored) {
			}
			return super.glCheckFramebufferStatus(target);
		}

		@Override
		public void glDeleteFramebuffers (int n, IntBuffer framebuffers) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glDeleteFramebuffers(limitBuffer(framebuffers, n));
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glDeleteFramebuffers(n, framebuffers);
		}

		@Override
		public void glDeleteFramebuffer (int framebuffer) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glDeleteFramebuffers(framebuffer);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glDeleteFramebuffer(framebuffer);
		}

		@Override
		public void glDeleteRenderbuffers (int n, IntBuffer renderbuffers) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glDeleteRenderbuffers(limitBuffer(renderbuffers, n));
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glDeleteRenderbuffers(n, renderbuffers);
		}

		@Override
		public void glDeleteRenderbuffer (int renderbuffer) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glDeleteRenderbuffers(renderbuffer);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glDeleteRenderbuffer(renderbuffer);
		}

		@Override
		public void glFramebufferRenderbuffer (int target, int attachment, int renderbuffertarget, int renderbuffer) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
		}

		@Override
		public void glFramebufferTexture2D (int target, int attachment, int textarget, int texture, int level) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glFramebufferTexture2D(target, attachment, textarget, texture, level);
		}

		@Override
		public void glGenFramebuffers (int n, IntBuffer framebuffers) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glGenFramebuffers(limitBuffer(framebuffers, n));
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glGenFramebuffers(n, framebuffers);
		}

		@Override
		public int glGenFramebuffer () {
			try {
				if (canUseCorePath()) {
					return org.lwjgl.opengl.GL30.glGenFramebuffers();
				}
			} catch (Throwable ignored) {
			}
			return super.glGenFramebuffer();
		}

		@Override
		public void glGenRenderbuffers (int n, IntBuffer renderbuffers) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glGenRenderbuffers(limitBuffer(renderbuffers, n));
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glGenRenderbuffers(n, renderbuffers);
		}

		@Override
		public int glGenRenderbuffer () {
			try {
				if (canUseCorePath()) {
					return org.lwjgl.opengl.GL30.glGenRenderbuffers();
				}
			} catch (Throwable ignored) {
			}
			return super.glGenRenderbuffer();
		}

		@Override
		public void glGetFramebufferAttachmentParameteriv (int target, int attachment, int pname, IntBuffer params) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameter(target, attachment, pname, params);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glGetFramebufferAttachmentParameteriv(target, attachment, pname, params);
		}

		@Override
		public void glGetRenderbufferParameteriv (int target, int pname, IntBuffer params) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glGetRenderbufferParameter(target, pname, params);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glGetRenderbufferParameteriv(target, pname, params);
		}

		@Override
		public boolean glIsFramebuffer (int framebuffer) {
			try {
				if (canUseCorePath()) {
					return org.lwjgl.opengl.GL30.glIsFramebuffer(framebuffer);
				}
			} catch (Throwable ignored) {
			}
			return super.glIsFramebuffer(framebuffer);
		}

		@Override
		public boolean glIsRenderbuffer (int renderbuffer) {
			try {
				if (canUseCorePath()) {
					return org.lwjgl.opengl.GL30.glIsRenderbuffer(renderbuffer);
				}
			} catch (Throwable ignored) {
			}
			return super.glIsRenderbuffer(renderbuffer);
		}

		@Override
		public void glRenderbufferStorage (int target, int internalformat, int width, int height) {
			try {
				if (canUseCorePath()) {
					org.lwjgl.opengl.GL30.glRenderbufferStorage(target, internalformat, width, height);
					return;
				}
			} catch (Throwable ignored) {
			}
			super.glRenderbufferStorage(target, internalformat, width, height);
		}
	}

	private static class LwjglGL30FboBindCompat extends LwjglGL30 {
		@Override
		public void glBindFramebuffer (int target, int framebuffer) {
			super.glBindFramebuffer(
				target,
				LwjglApplication.remapRequestedFramebufferHandle(framebuffer)
			);
		}
	}

	@Override
	public float getPpiX () {
		return Toolkit.getDefaultToolkit().getScreenResolution();
	}

	@Override
	public float getPpiY () {
		return Toolkit.getDefaultToolkit().getScreenResolution();
	}

	@Override
	public float getPpcX () {
		return (Toolkit.getDefaultToolkit().getScreenResolution() / 2.54f);
	}

	@Override
	public float getPpcY () {
		return (Toolkit.getDefaultToolkit().getScreenResolution() / 2.54f);
	}

	@Override
	public float getDensity () {
		if (config.overrideDensity != -1) return config.overrideDensity / 160f;
		return (Toolkit.getDefaultToolkit().getScreenResolution() / 160f);
	}

	@Override
	public boolean supportsDisplayModeChange () {
		return true;
	}

	@Override
	public Monitor getPrimaryMonitor () {
		return new LwjglMonitor(0, 0, "Primary Monitor");
	}

	@Override
	public Monitor getMonitor () {
		return getPrimaryMonitor();
	}

	@Override
	public Monitor[] getMonitors () {
		return new Monitor[] { getPrimaryMonitor() };
	}

	@Override
	public DisplayMode[] getDisplayModes (Monitor monitor) {
		return getDisplayModes();
	}

	@Override
	public DisplayMode getDisplayMode (Monitor monitor) {
		return getDisplayMode();
	}

	@Override
	public boolean setFullscreenMode (DisplayMode displayMode) {
		org.lwjgl.opengl.DisplayMode mode = ((LwjglDisplayMode)displayMode).mode;
		try {
			if (!mode.isFullscreenCapable()) {
				Display.setDisplayMode(mode);
			} else {
				Display.setDisplayModeAndFullscreen(mode);
			}
			float scaleFactor = PixelScaleCompat.factor();
			config.width = (int)(mode.getWidth() * scaleFactor);
			config.height = (int)(mode.getHeight() * scaleFactor);
			if (Gdx.gl != null) Gdx.gl.glViewport(0, 0, config.width, config.height);
			resize = true;
			return true;
		} catch (LWJGLException e) {
			return false;
		}
	}

	/** Kindly stolen from http://lwjgl.org/wiki/index.php?title=LWJGL_Basics_5_(Fullscreen), not perfect but will do. */
	@Override
	public boolean setWindowedMode (int width, int height) {
		if (getWidth() == width && getHeight() == height && !Display.isFullscreen()) {
			return true;
		}

		try {
			org.lwjgl.opengl.DisplayMode targetDisplayMode = null;
			boolean fullscreen = false;

			if (fullscreen) {
				org.lwjgl.opengl.DisplayMode[] modes = Display.getAvailableDisplayModes();
				int freq = 0;

				for (int i = 0; i < modes.length; i++) {
					org.lwjgl.opengl.DisplayMode current = modes[i];

					if ((current.getWidth() == width) && (current.getHeight() == height)) {
						if ((targetDisplayMode == null) || (current.getFrequency() >= freq)) {
							if ((targetDisplayMode == null) || (current.getBitsPerPixel() > targetDisplayMode.getBitsPerPixel())) {
								targetDisplayMode = current;
								freq = targetDisplayMode.getFrequency();
							}
						}

						// if we've found a match for bpp and frequence against the
						// original display mode then it's probably best to go for this one
						// since it's most likely compatible with the monitor
						if ((current.getBitsPerPixel() == Display.getDesktopDisplayMode().getBitsPerPixel())
							&& (current.getFrequency() == Display.getDesktopDisplayMode().getFrequency())) {
							targetDisplayMode = current;
							break;
						}
					}
				}
			} else {
				targetDisplayMode = new org.lwjgl.opengl.DisplayMode(width, height);
			}

			if (targetDisplayMode == null) {
				return false;
			}

			boolean resizable = !fullscreen && config.resizable;

			Display.setDisplayMode(targetDisplayMode);
			Display.setFullscreen(fullscreen);
			// Workaround for bug in LWJGL whereby resizable state is lost on DisplayMode change
			if (resizable == Display.isResizable()) {
				Display.setResizable(!resizable);
			}
			Display.setResizable(resizable);

			float scaleFactor = PixelScaleCompat.factor();
			config.width = (int)(targetDisplayMode.getWidth() * scaleFactor);
			config.height = (int)(targetDisplayMode.getHeight() * scaleFactor);
			if (Gdx.gl != null) Gdx.gl.glViewport(0, 0, config.width, config.height);
			resize = true;
			return true;
		} catch (LWJGLException e) {
			return false;
		}
	}

	@Override
	public DisplayMode[] getDisplayModes () {
		try {
			org.lwjgl.opengl.DisplayMode[] availableDisplayModes = Display.getAvailableDisplayModes();
			DisplayMode[] modes = new DisplayMode[availableDisplayModes.length];

			int idx = 0;
			for (org.lwjgl.opengl.DisplayMode mode : availableDisplayModes) {
				if (mode.isFullscreenCapable()) {
					modes[idx++] = new LwjglDisplayMode(mode.getWidth(), mode.getHeight(), mode.getFrequency(),
						mode.getBitsPerPixel(), mode);
				}
			}

			return modes;
		} catch (LWJGLException e) {
			throw new GdxRuntimeException("Couldn't fetch available display modes", e);
		}
	}

	@Override
	public DisplayMode getDisplayMode () {
		org.lwjgl.opengl.DisplayMode mode = Display.getDesktopDisplayMode();
		return new LwjglDisplayMode(mode.getWidth(), mode.getHeight(), mode.getFrequency(), mode.getBitsPerPixel(), mode);
	}

	@Override
	public void setTitle (String title) {
		Display.setTitle(title);
	}

	/**
	 * Display must be reconfigured via {@link #setWindowedMode(int, int)} for the changes to take
	 * effect.
	 */
	@Override
	public void setUndecorated (boolean undecorated) {
		System.setProperty("org.lwjgl.opengl.Window.undecorated", undecorated ? "true" : "false");
	}

	/**
	 * Display must be reconfigured via {@link #setWindowedMode(int, int)} for the changes to take
	 * effect.
	 */
	@Override
	public void setResizable (boolean resizable) {
		this.config.resizable = resizable;
		Display.setResizable(resizable);
	}

	@Override
	public BufferFormat getBufferFormat () {
		return bufferFormat;
	}

	@Override
	public void setVSync (boolean vsync) {
		// The Android bridge build keeps swap-interval pacing disabled and relies on explicit FPS pacing instead.
		this.vsync = false;
		Display.setVSyncEnabled(false);
	}

	@Override
	public boolean supportsExtension (String extension) {
		return extensions.contains(extension, false);
	}

	@Override
	public void setContinuousRendering (boolean isContinuous) {
		this.isContinuous = isContinuous;
	}

	@Override
	public boolean isContinuousRendering () {
		return isContinuous;
	}

	@Override
	public void requestRendering () {
		synchronized (this) {
			requestRendering = true;
		}
	}

	public boolean shouldRender () {
		synchronized (this) {
			boolean rq = requestRendering;
			requestRendering = false;
			return rq || isContinuous || Display.isDirty();
		}
	}

	@Override
	public boolean isFullscreen () {
		return Display.isFullscreen();
	}

	public boolean isSoftwareMode () {
		return softwareMode;
	}

	@Override
	public boolean isGL30Available () {
		return gl30 != null;
	}

	@Override
	public GL30 getGL30 () {
		return gl30;
	}

	/** A callback used by LwjglApplication when trying to create the display */
	public interface SetDisplayModeCallback {
		/** If the display creation fails, this method will be called. Suggested usage is to modify the passed configuration to use a
		 * common width and height, and set fullscreen to false.
		 * @return the configuration to be used for a second attempt at creating a display. A null value results in NOT attempting
		 *         to create the display a second time */
		public LwjglApplicationConfiguration onFailure (LwjglApplicationConfiguration initialConfig);
	}

	@Override
	public com.badlogic.gdx.graphics.Cursor newCursor (Pixmap pixmap, int xHotspot, int yHotspot) {
		return new LwjglCursor(pixmap, xHotspot, yHotspot);
	}

	@Override
	public void setCursor (com.badlogic.gdx.graphics.Cursor cursor) {
		if (canvas != null && SharedLibraryLoader.isMac) {
			return;
		}
		try {
			Mouse.setNativeCursor(((LwjglCursor)cursor).lwjglCursor);
		} catch (LWJGLException e) {
			throw new GdxRuntimeException("Could not set cursor image.", e);
		}
	}

	@Override
	public void setSystemCursor (SystemCursor systemCursor) {
		if (canvas != null && SharedLibraryLoader.isMac) {
			return;
		}
		try {
			Mouse.setNativeCursor(null);
		} catch (LWJGLException e) {
			throw new GdxRuntimeException("Couldn't set system cursor");
		}
	}

	static boolean isGLESContextActive () {
		return isGLESContext;
	}

	private class LwjglDisplayMode extends DisplayMode {
		org.lwjgl.opengl.DisplayMode mode;

		public LwjglDisplayMode (int width, int height, int refreshRate, int bitsPerPixel, org.lwjgl.opengl.DisplayMode mode) {
			super(width, height, refreshRate, bitsPerPixel);
			this.mode = mode;
		}
	}

	private class LwjglMonitor extends Monitor {
		protected LwjglMonitor (int virtualX, int virtualY, String name) {
			super(virtualX, virtualY, name);
		}
	}
}
