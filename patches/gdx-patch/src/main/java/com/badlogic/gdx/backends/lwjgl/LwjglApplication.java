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
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import com.badlogic.gdx.ApplicationLogger;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.CallbackBridge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.Display;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.backends.lwjgl.audio.OpenALAudio;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.ObjectSet;
import com.badlogic.gdx.utils.SnapshotArray;

/** An OpenGL surface fullscreen or in a lightweight window. */
public class LwjglApplication implements Application {
	protected final LwjglGraphics graphics;
	protected OpenALAudio audio;
	protected final LwjglFiles files;
	protected final LwjglInput input;
	protected final LwjglNet net;
	protected final ApplicationListener listener;
	protected Thread mainLoopThread;
	protected boolean running = true;
	private static final String NO_CONTEXT_LOG_MARKER =
		"No context is current or a function that is not available in the current context was called.";
	private static final String ZERO_MISSING_FUNCTION_PTR_PROP = "amethyst.lwjgl.diag.zero_missing_function_ptr";
	private static final String FORCE_DEFAULT_FBO_PROP = "amethyst.lwjgl.force_default_framebuffer";
	private static final String RENDER_SCALE_PROP = "amethyst.gdx.render_scale";
	private static final String MOBILE_HUD_ENABLED_PROP = "amethyst.mobile_hud_enabled";
	private static final String GLOBAL_ATLAS_FILTER_COMPAT_PROP = "amethyst.gdx.global_atlas_filter_compat";
	private static final String RUNTIME_TEXTURE_COMPAT_PROP = "amethyst.gdx.runtime_texture_compat";
	private static final String GLOBAL_TEXTURE_COMPAT_VERBOSE_PROP = "amethyst.gdx.global_texture_compat_verbose";
	private static final String STS_CARD_CRAWL_GAME_CLASS = "com.megacrit.cardcrawl.core.CardCrawlGame";
	private static final int VSYNC_SOFTWARE_SYNC_MARGIN_FPS = 1;
	private static final String[][] EXT_FRAMEBUFFER_FUNCTION_ALIASES = {
		{"glBindFramebufferEXT", "glBindFramebuffer"},
		{"glDeleteFramebuffersEXT", "glDeleteFramebuffers"},
		{"glGenFramebuffersEXT", "glGenFramebuffers"},
		{"glCheckFramebufferStatusEXT", "glCheckFramebufferStatus"},
		{"glFramebufferTexture1DEXT", "glFramebufferTexture1D"},
		{"glFramebufferTexture2DEXT", "glFramebufferTexture2D"},
		{"glFramebufferTexture3DEXT", "glFramebufferTexture3D"},
		{"glFramebufferRenderbufferEXT", "glFramebufferRenderbuffer"},
		{"glGetFramebufferAttachmentParameterivEXT", "glGetFramebufferAttachmentParameteriv"},
		{"glBindRenderbufferEXT", "glBindRenderbuffer"},
		{"glDeleteRenderbuffersEXT", "glDeleteRenderbuffers"},
		{"glGenRenderbuffersEXT", "glGenRenderbuffers"},
		{"glRenderbufferStorageEXT", "glRenderbufferStorage"},
		{"glGetRenderbufferParameterivEXT", "glGetRenderbufferParameteriv"},
		{"glIsFramebufferEXT", "glIsFramebuffer"},
		{"glIsRenderbufferEXT", "glIsRenderbuffer"},
		{"glGenerateMipmapEXT", "glGenerateMipmap"}
	};
	private static final String[] FUNCTION_ALIAS_SUFFIXES = {"EXT", "OES", "ARB"};
	private static volatile boolean noContextDiagnosticsInstalled;
	private static boolean scaledRenderPresentLogged;
	private static volatile int scaledRenderBackBufferHandle;
	private static volatile int scaledRenderBackBufferWidth;
	private static volatile int scaledRenderBackBufferHeight;
	private boolean contextRecoveryLogged;
	private boolean contextGenerationUnavailableLogged;
	private boolean missingFunctionPointerPatchLogged;
	private boolean missingFunctionPointerPatched;
	private boolean framebufferExtAliasLogged;
	private boolean framebufferExtAliasFailedLogged;
	private boolean genericFunctionAliasLogged;
	private boolean genericFunctionAliasFailedLogged;
	private boolean inactiveRenderSuppressedLogged;
	private boolean firstRenderFrameLogged;
	private boolean defaultFramebufferRebindLogged;
	private int nativeContextGeneration = Integer.MIN_VALUE;
	private boolean pendingNativeContextRebind;
	private Boolean lastActiveState;
	private Boolean coreFramebufferBindUsable;
	private Boolean extFramebufferBindUsable;
	private boolean missingAbortPointerResolved;
	private long missingAbortPointer;
	private boolean globalTextureCompatErrorLogged;
	private boolean globalTextureCompatFailureLogged;
	private int globalTextureCompatRepairedTotal;
	private int globalTextureCompatFallbackTotal;
	private int globalTextureCompatFailedTotal;
	private int globalTextureCompatScanTotal;
	private int globalTextureCompatKnownManagedCount = -1;
	private final float configuredRenderScale = readConfiguredRenderScale();
	private final ObjectSet<Texture> globalTextureCompatSeen = new ObjectSet<Texture>();
	private final ObjectMap<Texture, Integer> globalTextureCompatFailureCounts = new ObjectMap<Texture, Integer>();
	private final ObjectMap<Texture, String> globalTextureCompatSourceCache = new ObjectMap<Texture, String>();
	private ScaledRenderPipeline scaledRenderPipeline;
	private boolean scaledRenderPipelineDisabled;
	private boolean scaledRenderPipelineLogged;
	protected final Array<Runnable> runnables = new Array<Runnable>();
	protected final Array<Runnable> executedRunnables = new Array<Runnable>();
	protected final SnapshotArray<LifecycleListener> lifecycleListeners = new SnapshotArray<LifecycleListener>(LifecycleListener.class);
	protected int logLevel = LOG_INFO;
	protected ApplicationLogger applicationLogger;
	protected String preferencesdir;
	protected Files.FileType preferencesFileType;

	public LwjglApplication (ApplicationListener listener, String title, int width, int height) {
		this(listener, createConfig(title, width, height));
	}

	public LwjglApplication (ApplicationListener listener) {
		this(listener, null, 640, 480);
	}

	public LwjglApplication (ApplicationListener listener, LwjglApplicationConfiguration config) {
		this(listener, config, new LwjglGraphics(config));
	}

	public LwjglApplication (ApplicationListener listener, Canvas canvas) {
		this(listener, new LwjglApplicationConfiguration(), new LwjglGraphics(canvas));
	}

	public LwjglApplication (ApplicationListener listener, LwjglApplicationConfiguration config, Canvas canvas) {
		this(listener, config, new LwjglGraphics(canvas, config));
	}

	public LwjglApplication (ApplicationListener listener, LwjglApplicationConfiguration config, LwjglGraphics graphics) {
		LwjglNativesLoader.load();
		setApplicationLogger(new LwjglApplicationLogger());
		installNoContextDiagnostics();

		if (config.title == null) config.title = listener.getClass().getSimpleName();
		this.graphics = graphics;
		if (!LwjglApplicationConfiguration.disableAudio) {
			try {
				audio = new OpenALAudio(config.audioDeviceSimultaneousSources, config.audioDeviceBufferCount,
					config.audioDeviceBufferSize);
			} catch (Throwable t) {
				log("LwjglApplication", "Couldn't initialize audio, disabling audio", t);
				LwjglApplicationConfiguration.disableAudio = true;
			}
		}
		files = new LwjglFiles();
		input = new LwjglInput();
		net = new LwjglNet();
		this.listener = listener;
		this.preferencesdir = config.preferencesDirectory;
		this.preferencesFileType = config.preferencesFileType;

		Gdx.app = this;
		Gdx.graphics = graphics;
		Gdx.audio = audio;
		Gdx.files = files;
		Gdx.input = input;
		Gdx.net = net;
		initialize();
	}

	private static void installNoContextDiagnostics () {
		if (noContextDiagnosticsInstalled) return;
		synchronized (LwjglApplication.class) {
			if (noContextDiagnosticsInstalled) return;
			System.setOut(new NoContextDiagnosticPrintStream(System.out, "stdout"));
			System.setErr(new NoContextDiagnosticPrintStream(System.err, "stderr"));
			noContextDiagnosticsInstalled = true;
		}
	}

	private static void dumpNoContextStack (PrintStream base, String streamName, String value) {
		if (value == null || !value.contains(NO_CONTEXT_LOG_MARKER)) return;
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		base.println("[gdx-patch][diag] no-context marker captured on " + streamName + ", thread="
			+ Thread.currentThread().getName());
		for (int i = 0; i < stack.length; i++) {
			base.println("[gdx-patch][diag]   at " + stack[i]);
		}
	}

	private static final class NoContextDiagnosticPrintStream extends PrintStream {
		private final PrintStream base;
		private final String streamName;

		private NoContextDiagnosticPrintStream (PrintStream base, String streamName) {
			super(base, true);
			this.base = base;
			this.streamName = streamName;
		}

		@Override
		public void println (String value) {
			super.println(value);
			dumpNoContextStack(base, streamName, value);
		}

		@Override
		public void println (Object value) {
			super.println(value);
			dumpNoContextStack(base, streamName, String.valueOf(value));
		}
	}

	private static LwjglApplicationConfiguration createConfig (String title, int width, int height) {
		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
		config.title = title;
		config.width = width;
		config.height = height;
		config.vSyncEnabled = false;
		return config;
	}

	private static Boolean parseBooleanLike (String rawValue) {
		if (rawValue == null) return null;
		String normalized = rawValue.trim();
		if (normalized.length() == 0) return null;

		if ("true".equalsIgnoreCase(normalized) || "1".equals(normalized) || "yes".equalsIgnoreCase(normalized)
			|| "on".equalsIgnoreCase(normalized)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(normalized) || "0".equals(normalized) || "no".equalsIgnoreCase(normalized)
			|| "off".equalsIgnoreCase(normalized)) {
			return Boolean.FALSE;
		}
		return null;
	}

	private void applyMobileHudModeFromProperty () {
		Boolean parsed = parseBooleanLike(System.getProperty(MOBILE_HUD_ENABLED_PROP));
		if (parsed == null) return;

		try {
			Class<?> settingsClass = Class.forName("com.megacrit.cardcrawl.core.Settings");
			Field isMobileField = settingsClass.getDeclaredField("isMobile");
			isMobileField.setAccessible(true);
			boolean enabled = parsed.booleanValue();
			if (isMobileField.getBoolean(null) != enabled) {
				isMobileField.setBoolean(null, enabled);
				System.out.println("[gdx-patch] Applied mobile HUD mode: " + enabled);
			}
		} catch (Throwable t) {
			System.out.println("[gdx-patch] Failed to apply mobile HUD mode: " + t);
		}
	}

	private void initialize () {
		mainLoopThread = new Thread("LWJGL Application") {
			@Override
			public void run () {
				graphics.config.vSyncEnabled = false;
				graphics.setVSync(false);
				try {
					LwjglApplication.this.mainLoop();
				} catch (Throwable t) {
					if (audio != null) audio.dispose();
					Gdx.input.setCursorCatched(false);
					if (t instanceof RuntimeException)
						throw (RuntimeException)t;
					else
						throw new GdxRuntimeException(t);
				}
			}
		};
		mainLoopThread.start();
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

	private boolean shouldUseScaledRenderPipeline () {
		return graphics.canvas == null && configuredRenderScale < 0.999f && !scaledRenderPipelineDisabled;
	}

	private ScaledRenderPipeline beginScaledRenderFrame (int screenWidth, int screenHeight) {
		if (!shouldUseScaledRenderPipeline()) return null;
		try {
			if (scaledRenderPipeline == null) {
				scaledRenderPipeline = new ScaledRenderPipeline(configuredRenderScale);
			}
			scaledRenderPipeline.ensureReady(screenWidth, screenHeight, nativeContextGeneration);
			scaledRenderPipeline.beginFrame();
			if (!scaledRenderPipelineLogged) {
				scaledRenderPipelineLogged = true;
				System.out.println("[gdx-patch] Internal render scale active: factor=" + configuredRenderScale);
			}
			return scaledRenderPipeline;
		} catch (Throwable t) {
			scaledRenderPipelineDisabled = true;
			disposeScaledRenderPipeline();
			System.out.println("[gdx-patch] Disabling scaled render pipeline after initialization failure: " + t);
			return null;
		}
	}

	private void finishScaledRenderFrame (ScaledRenderPipeline pipeline, int screenWidth, int screenHeight) {
		if (pipeline == null) return;
		try {
			bindDefaultFramebufferForSwap();
			pipeline.finishFrame(screenWidth, screenHeight);
		} catch (Throwable t) {
			scaledRenderPipelineDisabled = true;
			try {
				pipeline.abortFrame();
			} catch (Throwable ignored) {
			}
			disposeScaledRenderPipeline();
			System.out.println("[gdx-patch] Disabling scaled render pipeline after present failure: " + t);
		}
	}

	private void disposeScaledRenderPipeline () {
		if (scaledRenderPipeline == null) return;
		try {
			scaledRenderPipeline.dispose();
		} catch (Throwable ignored) {
		}
		scaledRenderPipeline = null;
	}

	private static int getManagedDefaultFramebufferHandle () {
		try {
			return ((Integer)findField(GLFrameBuffer.class, "defaultFramebufferHandle").get(null)).intValue();
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private static void setManagedDefaultFramebufferHandle (int handle) {
		try {
			findField(GLFrameBuffer.class, "defaultFramebufferHandle").set(null, Integer.valueOf(handle));
		} catch (Throwable ignored) {
		}
	}

	public static int getScaledRenderBackBufferWidthOverride () {
		return scaledRenderBackBufferWidth;
	}

	public static int getScaledRenderBackBufferHeightOverride () {
		return scaledRenderBackBufferHeight;
	}

	static int remapRequestedFramebufferHandle (int framebuffer) {
		if (framebuffer != 0) return framebuffer;
		int overrideHandle = scaledRenderBackBufferHandle;
		return overrideHandle != 0 ? overrideHandle : framebuffer;
	}

	private static void setScaledRenderBackBufferOverride (int handle, int width, int height) {
		scaledRenderBackBufferHandle = handle;
		scaledRenderBackBufferWidth = width;
		scaledRenderBackBufferHeight = height;
	}

	private static void clearScaledRenderBackBufferOverride () {
		scaledRenderBackBufferHandle = 0;
		scaledRenderBackBufferWidth = 0;
		scaledRenderBackBufferHeight = 0;
	}

	private static final class ScaledRenderPipeline {
		private final float scale;
		private final Matrix4 projection = new Matrix4();
		private FrameBuffer frameBuffer;
		private SpriteBatch batch;
		private int frameBufferWidth;
		private int frameBufferHeight;
		private int contextGeneration = Integer.MIN_VALUE;
		private int previousDefaultFramebufferHandle;
		private boolean frameActive;

		ScaledRenderPipeline (float scale) {
			this.scale = scale;
		}

		void ensureReady (int screenWidth, int screenHeight, int currentContextGeneration) {
			int targetWidth = Math.max(1, Math.round(screenWidth * scale));
			int targetHeight = Math.max(1, Math.round(screenHeight * scale));
			boolean sizeChanged = frameBufferWidth != targetWidth || frameBufferHeight != targetHeight;
			boolean contextChanged = contextGeneration != currentContextGeneration;
			if (frameBuffer != null && !sizeChanged && !contextChanged) return;
			dispose();
			frameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, targetWidth, targetHeight, true);
			frameBuffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
			batch = new SpriteBatch();
			frameBufferWidth = targetWidth;
			frameBufferHeight = targetHeight;
			contextGeneration = currentContextGeneration;
		}

		void beginFrame () {
			if (frameBuffer == null) {
				throw new IllegalStateException("Scaled framebuffer is not initialized");
			}
			previousDefaultFramebufferHandle = getManagedDefaultFramebufferHandle();
			setManagedDefaultFramebufferHandle(frameBuffer.getFramebufferHandle());
			frameBuffer.begin();
			setScaledRenderBackBufferOverride(
				frameBuffer.getFramebufferHandle(),
				frameBufferWidth,
				frameBufferHeight
			);
			frameActive = true;
		}

		void finishFrame (int screenWidth, int screenHeight) {
			if (!frameActive) return;
			frameActive = false;
			clearScaledRenderBackBufferOverride();
			setManagedDefaultFramebufferHandle(previousDefaultFramebufferHandle);
			FrameBuffer.unbind();
			Gdx.gl20.glColorMask(true, true, true, true);
			Gdx.gl20.glDisable(GL20.GL_DEPTH_TEST);
			Gdx.gl20.glDisable(GL20.GL_CULL_FACE);
			Gdx.gl20.glDisable(GL20.GL_SCISSOR_TEST);
			Gdx.gl20.glDisable(GL20.GL_STENCIL_TEST);
			projection.setToOrtho2D(0, 0, screenWidth, screenHeight);
			batch.setProjectionMatrix(projection);
			batch.disableBlending();
			batch.begin();
			if (!scaledRenderPresentLogged) {
				scaledRenderPresentLogged = true;
				System.out.println(
					"[gdx-patch] Scaled present config: uv=0.0,0.0,1.0,1.0");
			}
			// Present the offscreen texture as-is. On this MobileGlues path the prior X flip
			// correction was overcompensating and left the final fullscreen image mirrored.
			batch.draw(frameBuffer.getColorBufferTexture(), 0, 0, screenWidth, screenHeight, 0f, 0f, 1f, 1f);
			batch.end();
		}

		void abortFrame () {
			if (!frameActive) return;
			frameActive = false;
			clearScaledRenderBackBufferOverride();
			setManagedDefaultFramebufferHandle(previousDefaultFramebufferHandle);
			FrameBuffer.unbind();
		}

		void dispose () {
			abortFrame();
			if (batch != null) {
				batch.dispose();
				batch = null;
			}
			if (frameBuffer != null) {
				frameBuffer.dispose();
				frameBuffer = null;
			}
			frameBufferWidth = 0;
			frameBufferHeight = 0;
			contextGeneration = Integer.MIN_VALUE;
		}
	}

	private boolean shouldUseSoftwareSync (boolean renderedFrame, boolean isActive, int frameRate) {
		if (frameRate <= 0) return false;
		if (!renderedFrame) return true;
		if (!isActive) return true;
		if (!graphics.vsync) return true;

		int refreshRate = resolveActiveRefreshRate();
		if (refreshRate <= 0) return true;

		// Avoid double-throttling when swap interval is already pacing us near the active refresh rate.
		return frameRate + VSYNC_SOFTWARE_SYNC_MARGIN_FPS < refreshRate;
	}

	private int resolveActiveRefreshRate () {
		try {
			org.lwjgl.opengl.DisplayMode currentMode = Display.getDisplayMode();
			if (currentMode != null && currentMode.getFrequency() > 0) {
				return currentMode.getFrequency();
			}
		} catch (Throwable ignored) {
		}

		try {
			com.badlogic.gdx.Graphics.DisplayMode desktopMode = graphics.getDisplayMode();
			if (desktopMode != null && desktopMode.refreshRate > 0) {
				return desktopMode.refreshRate;
			}
		} catch (Throwable ignored) {
		}
		return -1;
	}

	private int queryNativeContextGeneration () {
		try {
			return CallbackBridge.nativeGetGlContextGeneration();
		} catch (Throwable t) {
			if (!contextGenerationUnavailableLogged) {
				System.out.println("[gdx-patch] Native context generation query unavailable: " + t);
				contextGenerationUnavailableLogged = true;
			}
			return Integer.MIN_VALUE;
		}
	}

	private boolean isRuntimeForeground () {
		try {
			return CallbackBridge.nativeIsRuntimeForeground();
		} catch (Throwable ignored) {
			return true;
		}
	}

	private void syncNativeContextGeneration (String phase) {
		int generation = queryNativeContextGeneration();
		if (generation == Integer.MIN_VALUE) return;
		if (nativeContextGeneration == Integer.MIN_VALUE) {
			nativeContextGeneration = generation;
			return;
		}
		if (generation != nativeContextGeneration) {
			nativeContextGeneration = generation;
			pendingNativeContextRebind = true;
			invalidateFramebufferBindCapabilities();
			globalTextureCompatSeen.clear();
			globalTextureCompatFailureCounts.clear();
			globalTextureCompatSourceCache.clear();
			globalTextureCompatFailureLogged = false;
			globalTextureCompatKnownManagedCount = -1;
			System.out.println("[gdx-patch] Native GL context generation changed to " + generation + " (" + phase + ")");
		}
	}

	private long resolveDisplayWindowHandle () {
		try {
			Object value = Display.class.getMethod("getWindow").invoke(null);
			if (value instanceof Long) return (Long)value;
		} catch (Throwable ignored) {
		}

		try {
			Class<?> windowClass = Class.forName("org.lwjgl.opengl.Display$Window");
			java.lang.reflect.Field handle = windowClass.getDeclaredField("handle");
			handle.setAccessible(true);
			Object value = handle.get(null);
			if (value instanceof Long) return (Long)value;
		} catch (Throwable ignored) {
		}

		try {
			long current = GLFW.glfwGetCurrentContext();
			if (current != 0L) return current;
		} catch (Throwable ignored) {
		}
		return 0L;
	}

	private boolean makeDisplayContextCurrent (String phase) throws LWJGLException {
		long window = resolveDisplayWindowHandle();
		if (window == 0L) return false;

		GLFW.glfwMakeContextCurrent(window);
		if (Display.isCurrent()) return true;

		// Keep legacy path as fallback for compatibility with non-GLFW backends.
		Display.makeCurrent();
		return Display.isCurrent();
	}

	private boolean ensureGlCapabilities (String phase, boolean forceRecreate) {
		try {
			if (!forceRecreate) {
				GL.getCapabilities();
				zeroMissingFunctionPointersIfRequested(phase);
				ensureFramebufferExtFunctionAliases(phase);
				ensureGenericFunctionAliases(phase);
				return true;
			}
		} catch (Throwable ignored) {
		}

		try {
			GL.createCapabilities();
			zeroMissingFunctionPointersIfRequested(phase);
			ensureFramebufferExtFunctionAliases(phase);
			ensureGenericFunctionAliases(phase);
			invalidateFramebufferBindCapabilities();
			return true;
		} catch (Throwable t) {
			if (!contextRecoveryLogged) {
				System.out.println("[gdx-patch] Failed to rebuild GL capabilities (" + phase + "): " + t);
			}
			return false;
		}
	}

	private void zeroMissingFunctionPointersIfRequested (String phase) {
		if (!Boolean.getBoolean(ZERO_MISSING_FUNCTION_PTR_PROP)) return;
		if (missingFunctionPointerPatched) return;

		try {
			Class<?> threadLocalUtil = Class.forName("org.lwjgl.system.ThreadLocalUtil");
			Method getMissingAbort = threadLocalUtil.getDeclaredMethod("getFunctionMissingAbort");
			getMissingAbort.setAccessible(true);
			long missingAbortPointer = ((Long)getMissingAbort.invoke(null)).longValue();
			if (missingAbortPointer == 0L) return;

			Object capabilities = GL.getCapabilities();
			Field addressesField = capabilities.getClass().getDeclaredField("addresses");
			addressesField.setAccessible(true);
			Object pointerBuffer = addressesField.get(capabilities);
			Class<?> pointerBufferClass = pointerBuffer.getClass();
			Method limit = pointerBufferClass.getMethod("limit");
			Method get = pointerBufferClass.getMethod("get", int.class);
			Method put = pointerBufferClass.getMethod("put", int.class, long.class);

			int pointerCount = ((Integer)limit.invoke(pointerBuffer)).intValue();
			int replaced = 0;
			for (int i = 0; i < pointerCount; i++) {
				long value = ((Long)get.invoke(pointerBuffer, i)).longValue();
				if (value == missingAbortPointer) {
					put.invoke(pointerBuffer, i, 0L);
					replaced++;
				}
			}

			if (replaced > 0) {
				missingFunctionPointerPatched = true;
				System.out.println("[gdx-patch] Zeroed " + replaced + " missing GL function pointers for diagnostics (" + phase + ")");
			} else if (!missingFunctionPointerPatchLogged) {
				System.out.println("[gdx-patch] No missing GL function pointers found to zero (" + phase + ")");
				missingFunctionPointerPatchLogged = true;
			}
		} catch (Throwable t) {
			if (!missingFunctionPointerPatchLogged) {
				System.out.println("[gdx-patch] Failed to zero missing GL function pointers (" + phase + "): " + t);
				missingFunctionPointerPatchLogged = true;
			}
		}
	}

	private void invalidateFramebufferBindCapabilities () {
		coreFramebufferBindUsable = null;
		extFramebufferBindUsable = null;
	}

	private long getMissingAbortPointer () {
		if (missingAbortPointerResolved) return missingAbortPointer;
		missingAbortPointerResolved = true;
		missingAbortPointer = Long.MIN_VALUE;
		try {
			Class<?> threadLocalUtil = Class.forName("org.lwjgl.system.ThreadLocalUtil");
			Method getMissingAbort = threadLocalUtil.getDeclaredMethod("getFunctionMissingAbort");
			getMissingAbort.setAccessible(true);
			Object value = getMissingAbort.invoke(null);
			if (value instanceof Long) missingAbortPointer = ((Long)value).longValue();
		} catch (Throwable ignored) {
		}
		return missingAbortPointer;
	}

	private boolean isFunctionPointerUsable (String fieldName) {
		try {
			Object capabilities = GL.getCapabilities();
			Field field = capabilities.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object value = field.get(capabilities);
			if (!(value instanceof Long)) return false;
			long address = ((Long)value).longValue();
			if (address == 0L) return false;
			long missingPointer = getMissingAbortPointer();
			return missingPointer == Long.MIN_VALUE || address != missingPointer;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean canUseCoreFramebufferBind () {
		if (coreFramebufferBindUsable != null) return coreFramebufferBindUsable.booleanValue();
		coreFramebufferBindUsable = Boolean.valueOf(isFunctionPointerUsable("glBindFramebuffer"));
		return coreFramebufferBindUsable.booleanValue();
	}

	private boolean canUseExtFramebufferBind () {
		if (extFramebufferBindUsable != null) return extFramebufferBindUsable.booleanValue();
		extFramebufferBindUsable = Boolean.valueOf(isFunctionPointerUsable("glBindFramebufferEXT"));
		return extFramebufferBindUsable.booleanValue();
	}

	private boolean isFunctionPointerAddressUsable (long address) {
		if (address == 0L) return false;
		long missingPointer = getMissingAbortPointer();
		return missingPointer == Long.MIN_VALUE || address != missingPointer;
	}

	private boolean aliasFunctionPointerIfMissing (Object capabilities, String extFieldName, String coreFieldName) {
		try {
			Class<?> capabilitiesClass = capabilities.getClass();
			Field extField = capabilitiesClass.getDeclaredField(extFieldName);
			Field coreField = capabilitiesClass.getDeclaredField(coreFieldName);
			extField.setAccessible(true);
			coreField.setAccessible(true);

			Object extValue = extField.get(capabilities);
			Object coreValue = coreField.get(capabilities);
			if (!(extValue instanceof Long) || !(coreValue instanceof Long)) return false;

			long extAddress = ((Long)extValue).longValue();
			if (isFunctionPointerAddressUsable(extAddress)) return false;

			long coreAddress = ((Long)coreValue).longValue();
			if (!isFunctionPointerAddressUsable(coreAddress)) return false;

			extField.setLong(capabilities, coreAddress);
			return true;
		} catch (NoSuchFieldException ignored) {
			return false;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void ensureFramebufferExtFunctionAliases (String phase) {
		try {
			Object capabilities = GL.getCapabilities();
			int aliased = 0;
			for (int i = 0; i < EXT_FRAMEBUFFER_FUNCTION_ALIASES.length; i++) {
				String[] mapping = EXT_FRAMEBUFFER_FUNCTION_ALIASES[i];
				if (aliasFunctionPointerIfMissing(capabilities, mapping[0], mapping[1])) {
					aliased++;
				}
			}
			framebufferExtAliasFailedLogged = false;
			if (aliased > 0 && !framebufferExtAliasLogged) {
				framebufferExtAliasLogged = true;
				System.out.println("[gdx-patch] Aliased " + aliased + " EXT framebuffer function pointer(s) to core GL (" + phase + ")");
			}
		} catch (Throwable t) {
			if (!framebufferExtAliasFailedLogged) {
				framebufferExtAliasFailedLogged = true;
				System.out.println("[gdx-patch] Unable to alias EXT framebuffer function pointers (" + phase + "): " + t);
			}
		}
	}

	private static boolean isFunctionPointerField (Field field) {
		if (field == null) return false;
		if (!"long".equals(field.getType().getName())) return false;
		String name = field.getName();
		return name != null && name.startsWith("gl") && name.length() > 2;
	}

	private long readFunctionPointerAddress (Object capabilities, Field field) throws IllegalAccessException {
		field.setAccessible(true);
		return field.getLong(capabilities);
	}

	private boolean aliasFunctionPointerFieldIfMissing (Object capabilities, Field targetField, Field sourceField) throws IllegalAccessException {
		if (targetField == null || sourceField == null) return false;
		if (targetField == sourceField) return false;
		if (!isFunctionPointerField(targetField) || !isFunctionPointerField(sourceField)) return false;

		long targetAddress = readFunctionPointerAddress(capabilities, targetField);
		if (isFunctionPointerAddressUsable(targetAddress)) return false;

		long sourceAddress = readFunctionPointerAddress(capabilities, sourceField);
		if (!isFunctionPointerAddressUsable(sourceAddress)) return false;

		targetField.setAccessible(true);
		targetField.setLong(capabilities, sourceAddress);
		return true;
	}

	private void ensureGenericFunctionAliases (String phase) {
		try {
			Object capabilities = GL.getCapabilities();
			Class<?> capabilitiesClass = capabilities.getClass();
			Field[] declaredFields = capabilitiesClass.getDeclaredFields();
			ObjectMap<String, Field> fieldsByName = new ObjectMap<String, Field>();
			for (int i = 0; i < declaredFields.length; i++) {
				Field field = declaredFields[i];
				if (!isFunctionPointerField(field)) continue;
				fieldsByName.put(field.getName(), field);
			}
			if (fieldsByName.size == 0) return;

			int aliased = 0;
			for (ObjectMap.Entry<String, Field> entry : fieldsByName.entries()) {
				String fieldName = entry.key;
				Field targetField = entry.value;

				boolean aliasedThisField = false;
				for (int i = 0; i < FUNCTION_ALIAS_SUFFIXES.length && !aliasedThisField; i++) {
					String suffix = FUNCTION_ALIAS_SUFFIXES[i];
					if (fieldName.endsWith(suffix)) {
						String coreName = fieldName.substring(0, fieldName.length() - suffix.length());
						Field coreField = fieldsByName.get(coreName);
						if (aliasFunctionPointerFieldIfMissing(capabilities, targetField, coreField)) {
							aliased++;
							aliasedThisField = true;
						}
					}
				}
				if (aliasedThisField) continue;

				for (int i = 0; i < FUNCTION_ALIAS_SUFFIXES.length; i++) {
					String suffix = FUNCTION_ALIAS_SUFFIXES[i];
					Field suffixField = fieldsByName.get(fieldName + suffix);
					if (aliasFunctionPointerFieldIfMissing(capabilities, targetField, suffixField)) {
						aliased++;
						break;
					}
				}
			}

			genericFunctionAliasFailedLogged = false;
			if (aliased > 0 && !genericFunctionAliasLogged) {
				genericFunctionAliasLogged = true;
				System.out.println("[gdx-patch] Aliased " + aliased
					+ " generic GL function pointer(s) across core/EXT/OES/ARB names (" + phase + ")");
			}
		} catch (Throwable t) {
			if (!genericFunctionAliasFailedLogged) {
				genericFunctionAliasFailedLogged = true;
				System.out.println("[gdx-patch] Unable to alias generic GL function pointers (" + phase + "): " + t);
			}
		}
	}

	private void ensureDisplayContextCurrent (String phase) {
		if (!Display.isCreated()) return;

		syncNativeContextGeneration(phase);

		try {
			boolean needsRebind = pendingNativeContextRebind || !Display.isCurrent();
			if (needsRebind) {
				if (!makeDisplayContextCurrent(phase)) {
					if (!contextRecoveryLogged) {
						System.out.println("[gdx-patch] GL context is not current after recovery attempt (" + phase + ")");
						contextRecoveryLogged = true;
					}
					return;
				}
				pendingNativeContextRebind = false;
				if (!ensureGlCapabilities(phase, true)) {
					contextRecoveryLogged = true;
					return;
				}
			} else if (!ensureGlCapabilities(phase, false)) {
				contextRecoveryLogged = true;
				return;
			}

			if (contextRecoveryLogged) {
				System.out.println("[gdx-patch] GL context recovered (" + phase + ")");
				contextRecoveryLogged = false;
			}
		} catch (Throwable t) {
			if (!contextRecoveryLogged) {
				System.out.println("[gdx-patch] Failed to ensure current GL context (" + phase + "): " + t);
				contextRecoveryLogged = true;
			}
		}
	}

	private void bindDefaultFramebufferForSwap () {
		ensureDisplayContextCurrent("pre-swap-fbo-rebind");
		boolean bound = false;
		try {
			if (canUseCoreFramebufferBind()) {
				org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
				bound = true;
			}
		} catch (Throwable ignored) {
		}
		try {
			if (!bound && canUseExtFramebufferBind()) {
				org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
				bound = true;
			}
		} catch (Throwable ignored) {
		}
		try {
			if (!bound && Gdx.gl20 != null) {
				Gdx.gl20.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0);
			}
		} catch (Throwable ignored) {
		}
		try {
			org.lwjgl.opengl.GL11.glViewport(0, 0, Display.getWidth(), Display.getHeight());
		} catch (Throwable ignored) {
		}
	}

	private void ensureColorMaskWritable () {
		try {
			if (Gdx.gl20 != null) {
				Gdx.gl20.glColorMask(true, true, true, true);
			}
		} catch (Throwable ignored) {
		}
	}

	private boolean shouldForceDefaultFramebuffer () {
		String configured = System.getProperty(FORCE_DEFAULT_FBO_PROP);
		if (configured != null) {
			configured = configured.trim();
			return !"0".equals(configured)
				&& !"false".equalsIgnoreCase(configured)
				&& !"off".equalsIgnoreCase(configured);
		}
		// Default to enabled on GLES-backed contexts to avoid swapping a stale black backbuffer
		// when third-party code leaves an offscreen FBO bound at end of frame.
		return LwjglGraphics.isGLESContextActive();
	}

	private static Field findField (Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(name);
	}

	private static Object readStaticField (Class<?> owner, String fieldName) throws ReflectiveOperationException {
		return findField(owner, fieldName).get(null);
	}

	private static Object readField (Object target, String fieldName) throws ReflectiveOperationException {
		return findField(target.getClass(), fieldName).get(target);
	}

	private static Object invokeNoArgMethod (Object target, String methodName) {
		if (target == null || methodName == null || methodName.length() == 0) return null;
		try {
			Method method = target.getClass().getMethod(methodName);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Object readFieldQuietly (Object target, String fieldName) {
		if (target == null || fieldName == null || fieldName.length() == 0) return null;
		try {
			return readField(target, fieldName);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String sanitizeForLog (String value, int maxLength) {
		if (value == null) return "null";
		String sanitized = value.replace('\n', ' ').replace('\r', ' ');
		if (sanitized.length() <= maxLength) return sanitized;
		return sanitized.substring(0, maxLength) + "...";
	}

	private static String resolveFileHandlePathHint (Object fileHandle) {
		if (fileHandle == null) return null;
		Object absolutePath = invokeNoArgMethod(fileHandle, "path");
		if (absolutePath != null) return sanitizeForLog(String.valueOf(absolutePath), 240);
		Object name = invokeNoArgMethod(fileHandle, "name");
		if (name != null) return sanitizeForLog(String.valueOf(name), 240);
		return sanitizeForLog(String.valueOf(fileHandle), 240);
	}

	private String resolveTextureSourceTag (Texture texture) {
		if (texture == null) return "source=texture-null";
		String cached = globalTextureCompatSourceCache.get(texture);
		if (cached != null) return cached;

		String sourceTag = "source=unresolved";
		try {
			Object textureData = Texture.class.getMethod("getTextureData").invoke(texture);
			if (textureData == null) {
				sourceTag = "source=textureData-null";
			} else {
				String dataClass = textureData.getClass().getName();
				Object type = invokeNoArgMethod(textureData, "getType");
				Object useMipMaps = invokeNoArgMethod(textureData, "useMipMaps");
				Object managed = invokeNoArgMethod(textureData, "isManaged");
				Object fileHandle = invokeNoArgMethod(textureData, "getFileHandle");
				if (fileHandle == null) fileHandle = readFieldQuietly(textureData, "file");
				if (fileHandle == null) fileHandle = readFieldQuietly(textureData, "fileHandle");
				String fileHint = resolveFileHandlePathHint(fileHandle);

				StringBuilder builder = new StringBuilder(192);
				builder.append("dataClass=").append(dataClass);
				if (type != null) builder.append(", dataType=").append(type);
				if (managed != null) builder.append(", dataManaged=").append(managed);
				if (useMipMaps != null) builder.append(", dataUseMipMaps=").append(useMipMaps);
				if (fileHint != null) {
					builder.append(", file=").append(fileHint);
				} else {
					Object descriptor = readFieldQuietly(textureData, "desc");
					if (descriptor != null) {
						builder.append(", desc=").append(sanitizeForLog(String.valueOf(descriptor), 240));
					}
				}
				sourceTag = builder.toString();
			}
		} catch (Throwable t) {
			sourceTag = "source=resolve-failed:" + t.getClass().getSimpleName();
		}

		globalTextureCompatSourceCache.put(texture, sourceTag);
		return sourceTag;
	}

	private boolean shouldEnableGlobalTextureCompat () {
		return readBooleanSystemProperty(RUNTIME_TEXTURE_COMPAT_PROP, false) && LwjglGraphics.isGLESContextActive();
	}

	private static boolean readBooleanSystemProperty (String key, boolean defaultValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		if ("false".equalsIgnoreCase(configured) || "0".equals(configured) || "off".equalsIgnoreCase(configured)) {
			return false;
		}
		if ("true".equalsIgnoreCase(configured) || "1".equals(configured) || "on".equalsIgnoreCase(configured)) {
			return true;
		}
		return defaultValue;
	}

	private boolean shouldRunGlobalTextureCompatScan () {
		long frame = graphics.frameId;
		// Startup phase is most sensitive to transient black blocks: scan every frame first.
		if (frame < 3600) return true;
		if (frame < 7200) return (frame % 10) == 0;
		if (frame < 21600) return (frame % 60) == 0;
		return (frame % 600) == 0;
	}

	private boolean shouldEnableGlobalAtlasFilterCompatFallback () {
		return readBooleanSystemProperty(GLOBAL_ATLAS_FILTER_COMPAT_PROP, true);
	}

	private boolean shouldEnableGlobalTextureCompatVerboseLog () {
		return readBooleanSystemProperty(GLOBAL_TEXTURE_COMPAT_VERBOSE_PROP, false);
	}

	private static void drainGlErrors () {
		if (Gdx.gl == null) return;
		for (int i = 0; i < 8; i++) {
			int error = Gdx.gl.glGetError();
			if (error == GL20.GL_NO_ERROR) return;
		}
	}

	private static final class MipRepairResult {
		private final boolean repaired;
		private final int glError;
		private final String failureReason;

		private MipRepairResult (boolean repaired, int glError, String failureReason) {
			this.repaired = repaired;
			this.glError = glError;
			this.failureReason = failureReason;
		}
	}

	private static String toGlErrorString (int glError) {
		switch (glError) {
		case GL20.GL_NO_ERROR:
			return "GL_NO_ERROR";
		case GL20.GL_INVALID_ENUM:
			return "GL_INVALID_ENUM";
		case GL20.GL_INVALID_VALUE:
			return "GL_INVALID_VALUE";
		case GL20.GL_INVALID_OPERATION:
			return "GL_INVALID_OPERATION";
		case GL20.GL_OUT_OF_MEMORY:
			return "GL_OUT_OF_MEMORY";
		default:
			return "0x" + Integer.toHexString(glError);
		}
	}

	private static String textureDebugInfo (Texture texture) {
		if (texture == null) return "texture=null";
		return "handle=" + texture.getTextureObjectHandle()
			+ ", size=" + texture.getWidth() + "x" + texture.getHeight()
			+ ", min=" + texture.getMinFilter()
			+ ", mag=" + texture.getMagFilter()
			+ ", wrap=" + texture.getUWrap() + "/" + texture.getVWrap();
	}

	private static boolean textureDataUseMipMaps (Texture texture) {
		if (texture == null) return false;
		try {
			Object textureData = Texture.class.getMethod("getTextureData").invoke(texture);
			Object value = invokeNoArgMethod(textureData, "useMipMaps");
			return value instanceof Boolean && ((Boolean)value).booleanValue();
		} catch (Throwable ignored) {
			return false;
		}
	}

	private MipRepairResult tryRepairTextureMipChain (Texture texture) {
		if (Gdx.gl == null || texture == null) {
			return new MipRepairResult(false, GL20.GL_INVALID_OPERATION, "missing gl or texture");
		}
		try {
			texture.bind();
			drainGlErrors();
			Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
			int error = Gdx.gl.glGetError();
			return new MipRepairResult(error == GL20.GL_NO_ERROR, error, null);
		} catch (Throwable t) {
			String reason = t.getClass().getSimpleName() + ": " + t.getMessage();
			return new MipRepairResult(false, GL20.GL_INVALID_OPERATION, reason);
		}
	}

	private static String tryApplyLinearFilterFallback (Texture texture) {
		if (texture == null) return "texture is null";
		try {
			texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
			texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
			return null;
		} catch (Throwable t) {
			return t.getClass().getSimpleName() + ": " + t.getMessage();
		}
	}

	private static boolean isPowerOfTwo (int value) {
		return value > 0 && (value & (value - 1)) == 0;
	}

	private static boolean isNpotTexture (Texture texture) {
		if (texture == null) return false;
		return !isPowerOfTwo(texture.getWidth()) || !isPowerOfTwo(texture.getHeight());
	}

	private int getManagedTextureCount () {
		try {
			Object managedObj = readStaticField(Texture.class, "managedTextures");
			if (!(managedObj instanceof Map<?, ?>)) return -1;

			Object texturesObj = ((Map<?, ?>)managedObj).get(this);
			if (!(texturesObj instanceof Array<?>)) return -1;
			return ((Array<?>)texturesObj).size;
		} catch (Throwable ignored) {
			return -1;
		}
	}

	private boolean runGlobalTextureCompatOnManagedGrowth (String phase) {
		if (!shouldEnableGlobalTextureCompat()) return false;
		int managedCount = getManagedTextureCount();
		if (managedCount < 0) return false;

		if (globalTextureCompatKnownManagedCount < 0) {
			globalTextureCompatKnownManagedCount = managedCount;
			return false;
		}

		if (managedCount == globalTextureCompatKnownManagedCount) return false;

		if (managedCount > globalTextureCompatKnownManagedCount) {
			int added = managedCount - globalTextureCompatKnownManagedCount;
			globalTextureCompatKnownManagedCount = managedCount;
			if (shouldEnableGlobalTextureCompatVerboseLog()) {
				// Reduced log mode: growth-triggered debug log disabled.
				// System.out.println("[gdx-patch] Global texture compat growth-triggered scan (" + phase + "): +"
				//	+ added + " managed texture(s), total=" + managedCount);
			}
			ensureGlobalTextureCompat();
			return true;
		}

		// Texture dispose/unload path: track new baseline, no immediate scan needed.
		globalTextureCompatKnownManagedCount = managedCount;
		return false;
	}

	private void ensureGlobalTextureCompat () {
		if (!shouldEnableGlobalTextureCompat()) return;

		try {
			Object managedObj = readStaticField(Texture.class, "managedTextures");
			if (!(managedObj instanceof Map<?, ?>)) return;

			Object texturesObj = ((Map<?, ?>)managedObj).get(this);
			if (!(texturesObj instanceof Array<?>)) return;

			Array<?> textures = (Array<?>)texturesObj;
			globalTextureCompatKnownManagedCount = textures.size;
			boolean verbose = shouldEnableGlobalTextureCompatVerboseLog();
			boolean atlasFallbackEnabled = shouldEnableGlobalAtlasFilterCompatFallback();
			globalTextureCompatScanTotal++;

			int mipmapCandidatesThisScan = 0;
			int npotCandidatesThisScan = 0;
			int skippedSeenThisScan = 0;
			int repairedThisScan = 0;
			int fallbackThisScan = 0;
			int failedThisScan = 0;
			for (int i = 0, n = textures.size; i < n; i++) {
				Object value = textures.get(i);
				if (!(value instanceof Texture)) continue;

				Texture texture = (Texture)value;
				if (globalTextureCompatSeen.contains(texture)) {
					skippedSeenThisScan++;
					continue;
				}

				Texture.TextureFilter minFilter = texture.getMinFilter();
				boolean textureDataMipMaps = textureDataUseMipMaps(texture);
				if (!minFilter.isMipMap() && !textureDataMipMaps) continue;
				mipmapCandidatesThisScan++;
				boolean npot = isNpotTexture(texture);
				if (npot) npotCandidatesThisScan++;
				String sourceTag = resolveTextureSourceTag(texture);

				MipRepairResult repair = tryRepairTextureMipChain(texture);
				if (repair.repaired) {
					boolean forcedLinearClamp = false;
					if (atlasFallbackEnabled) {
						String forceError = tryApplyLinearFilterFallback(texture);
						if (forceError == null) {
							fallbackThisScan++;
							forcedLinearClamp = true;
						} else if (verbose) {
							// Reduced log mode: per-texture force-linear failure log disabled.
							// System.out.println("[gdx-patch][texture-compat] force-linear-clamp-failed frame=" + graphics.frameId
							//	+ ", scan=" + globalTextureCompatScanTotal
							//	+ ", index=" + i
							//	+ ", npot=" + npot
							//	+ ", textureDataUseMipMaps=" + textureDataMipMaps
							//	+ ", reason=" + forceError
							//	+ ", " + textureDebugInfo(texture)
							//	+ ", " + sourceTag);
						}
					}
					globalTextureCompatSeen.add(texture);
					globalTextureCompatFailureCounts.remove(texture);
					repairedThisScan++;
					// Reduced log mode: per-texture repaired log disabled.
					// if (verbose) {
					//	System.out.println("[gdx-patch][texture-compat] repaired frame=" + graphics.frameId
					//		+ ", scan=" + globalTextureCompatScanTotal
					//		+ ", index=" + i
					//		+ ", npot=" + npot
					//		+ ", glError=" + toGlErrorString(repair.glError)
					//		+ ", textureDataUseMipMaps=" + textureDataMipMaps
					//		+ ", forcedLinearClamp=" + forcedLinearClamp
					//		+ ", " + textureDebugInfo(texture)
					//		+ ", " + sourceTag);
					// }
					continue;
				}

				if (atlasFallbackEnabled) {
					String fallbackError = tryApplyLinearFilterFallback(texture);
					if (fallbackError == null) {
						globalTextureCompatSeen.add(texture);
						globalTextureCompatFailureCounts.remove(texture);
						fallbackThisScan++;
						// Reduced log mode: per-texture fallback-linear log disabled.
						// if (verbose) {
						//	System.out.println("[gdx-patch][texture-compat] fallback-linear frame=" + graphics.frameId
						//		+ ", scan=" + globalTextureCompatScanTotal
						//		+ ", index=" + i
						//		+ ", npot=" + npot
						//		+ ", repairGlError=" + toGlErrorString(repair.glError)
						//		+ ", textureDataUseMipMaps=" + textureDataMipMaps
						//		+ ", " + textureDebugInfo(texture)
						//		+ ", " + sourceTag);
						// }
						continue;
					}
					if (verbose) {
						// Reduced log mode: per-texture fallback-failed log disabled.
						// System.out.println("[gdx-patch][texture-compat] fallback-failed frame=" + graphics.frameId
						//	+ ", scan=" + globalTextureCompatScanTotal
						//	+ ", index=" + i
						//	+ ", npot=" + npot
						//	+ ", repairGlError=" + toGlErrorString(repair.glError)
						//	+ ", textureDataUseMipMaps=" + textureDataMipMaps
						//	+ ", fallbackReason=" + fallbackError
						//	+ ", " + textureDebugInfo(texture)
						//	+ ", " + sourceTag);
					}
				}

				failedThisScan++;
				int failureCount = globalTextureCompatFailureCounts.get(texture, 0) + 1;
				globalTextureCompatFailureCounts.put(texture, failureCount);
				// Reduced log mode: per-texture repair-failed log disabled.
				// if (verbose || failureCount <= 3 || (failureCount % 10) == 0) {
				//	System.out.println("[gdx-patch][texture-compat] repair-failed frame=" + graphics.frameId
				//		+ ", scan=" + globalTextureCompatScanTotal
				//		+ ", index=" + i
				//		+ ", failureCount=" + failureCount
				//		+ ", npot=" + npot
				//		+ ", glError=" + toGlErrorString(repair.glError)
				//		+ ", textureDataUseMipMaps=" + textureDataMipMaps
				//		+ ", atlasFallbackEnabled=" + atlasFallbackEnabled
				//		+ (repair.failureReason == null ? "" : ", reason=" + repair.failureReason)
				//		+ ", " + textureDebugInfo(texture)
				//		+ ", " + sourceTag);
				// }
			}

			if (repairedThisScan > 0 || fallbackThisScan > 0) {
				globalTextureCompatFailureLogged = false;
			}
			globalTextureCompatRepairedTotal += repairedThisScan;
			globalTextureCompatFallbackTotal += fallbackThisScan;
			globalTextureCompatFailedTotal += failedThisScan;
			if (repairedThisScan > 0 || fallbackThisScan > 0 || failedThisScan > 0) {
				System.out.println("[gdx-patch] Global texture compat scan#" + globalTextureCompatScanTotal
					+ ": managed=" + textures.size
					+ ", mipmapCandidates=" + mipmapCandidatesThisScan
					+ ", npotCandidates=" + npotCandidatesThisScan
					+ ", skippedSeen=" + skippedSeenThisScan
					+ ", repaired=" + repairedThisScan
					+ ", fallback=" + fallbackThisScan
					+ ", failed=" + failedThisScan
					+ ", totalRepaired=" + globalTextureCompatRepairedTotal
					+ ", totalFallback=" + globalTextureCompatFallbackTotal
					+ ", totalFailed=" + globalTextureCompatFailedTotal
					+ ", strictRepair=true, atlasFallbackEnabled=" + atlasFallbackEnabled);
			}
			if (failedThisScan > 0 && !globalTextureCompatFailureLogged) {
				globalTextureCompatFailureLogged = true;
				System.out.println("[gdx-patch] Global texture compat could not repair " + failedThisScan
					+ " texture(s) this scan (strict mip-repair mode, atlasFallbackEnabled=" + atlasFallbackEnabled + ")");
			}
		} catch (Throwable t) {
			if (!globalTextureCompatErrorLogged) {
				globalTextureCompatErrorLogged = true;
				System.out.println("[gdx-patch] Global texture compat unavailable: " + t);
			}
		}
	}

	void mainLoop () {
		SnapshotArray<LifecycleListener> lifecycleListeners = this.lifecycleListeners;

		try {
			graphics.setupDisplay();
		} catch (LWJGLException e) {
			throw new GdxRuntimeException(e);
		}

		ensureDisplayContextCurrent("create");
		applyMobileHudModeFromProperty();
		// Reduced log mode: listener.create begin log disabled.
		// System.out.println("[gdx-patch][diag] listener.create begin");
		listener.create();
		// Reduced log mode: listener.create end log disabled.
		// System.out.println("[gdx-patch][diag] listener.create end");
		graphics.resize = true;

		int lastWidth = graphics.getWidth();
		int lastHeight = graphics.getHeight();

		graphics.lastTime = System.nanoTime();
		boolean wasActive = true;
		while (running) {
			Display.processMessages();
			if (Display.isCloseRequested()) exit();

			boolean runtimeForeground = isRuntimeForeground();
			boolean isActive = runtimeForeground && Display.isActive();
			if (lastActiveState == null || lastActiveState.booleanValue() != isActive) {
				boolean isVisible = false;
				boolean isCurrent = false;
				try {
					isVisible = Display.isVisible();
				} catch (Throwable ignored) {
				}
				try {
					isCurrent = Display.isCurrent();
				} catch (Throwable ignored) {
				}
				// Reduced log mode: activity-state diagnostic log disabled.
				// System.out.println("[gdx-patch][diag] activity state changed: active=" + isActive + ", visible=" + isVisible
				//	+ ", current=" + isCurrent + ", bgFPS=" + graphics.config.backgroundFPS + ", fgFPS="
				//	+ graphics.config.foregroundFPS);
				lastActiveState = isActive;
			}
			if (wasActive && !isActive) { // if it's just recently minimized from active state
				wasActive = false;
				synchronized (lifecycleListeners) {
					LifecycleListener[] listeners = lifecycleListeners.begin();
					for (int i = 0, n = lifecycleListeners.size; i < n; ++i)
						 listeners[i].pause();
					lifecycleListeners.end();
				}
				ensureDisplayContextCurrent("pause");
				listener.pause();
			}
			if (!wasActive && isActive) { // if it's just recently focused from minimized state
				wasActive = true;
				synchronized (lifecycleListeners) {
					LifecycleListener[] listeners = lifecycleListeners.begin();
					for (int i = 0, n = lifecycleListeners.size; i < n; ++i)
						listeners[i].resume();
					lifecycleListeners.end();
				}
				ensureDisplayContextCurrent("resume");
				listener.resume();
			}

			boolean shouldRender = false;

			if (graphics.canvas != null) {
				int width = graphics.canvas.getWidth();
				int height = graphics.canvas.getHeight();
				if (lastWidth != width || lastHeight != height) {
					lastWidth = width;
					lastHeight = height;
					ensureDisplayContextCurrent("canvas-resize");
					Gdx.gl.glViewport(0, 0, lastWidth, lastHeight);
					ensureDisplayContextCurrent("listener-resize-canvas");
					listener.resize(lastWidth, lastHeight);
					shouldRender = true;
				}
			} else {
				graphics.config.x = Display.getX();
				graphics.config.y = Display.getY();
				if (graphics.resize || Display.wasResized()
					|| (int)(Display.getWidth() * PixelScaleCompat.factor()) != graphics.config.width
					|| (int)(Display.getHeight() * PixelScaleCompat.factor()) != graphics.config.height) {
					graphics.resize = false;
					graphics.config.width = (int)(Display.getWidth() * PixelScaleCompat.factor());
					graphics.config.height = (int)(Display.getHeight() * PixelScaleCompat.factor());
					ensureDisplayContextCurrent("window-resize");
					Gdx.gl.glViewport(0, 0, graphics.config.width, graphics.config.height);
					ensureDisplayContextCurrent("listener-resize-window");
					if (listener != null) listener.resize(graphics.config.width, graphics.config.height);
					graphics.requestRendering();
				}
			}

			if (executeRunnables()) shouldRender = true;

			// If one of the runnables set running to false, for example after an exit().
			if (!running) break;

			input.update();
			shouldRender |= graphics.shouldRender();
			input.processEvents();
			if (audio != null) audio.update();

			if (!runtimeForeground) {
				shouldRender = false;
			}
			if (!isActive && graphics.config.backgroundFPS == -1) {
				if (!inactiveRenderSuppressedLogged) {
					// Reduced log mode: inactive-render diagnostic log disabled.
					// System.out.println("[gdx-patch][diag] suppressing render because active=false and backgroundFPS=-1");
					inactiveRenderSuppressedLogged = true;
				}
				shouldRender = false;
			}
			int frameRate = isActive ? graphics.config.foregroundFPS : graphics.config.backgroundFPS;
			if (!runtimeForeground) frameRate = -1;
			boolean renderedFrame = false;
			if (shouldRender) {
				if (!firstRenderFrameLogged) {
					boolean isCurrent = false;
					try {
						isCurrent = Display.isCurrent();
					} catch (Throwable ignored) {
					}
					// Reduced log mode: first-render diagnostic log disabled.
					// System.out.println("[gdx-patch][diag] first render frame, active=" + isActive + ", current=" + isCurrent);
					firstRenderFrameLogged = true;
				}
				ensureDisplayContextCurrent("render");
				graphics.updateTime();
				graphics.frameId++;
				if ((graphics.frameId % 600) == 0) {
					boolean isCurrent = false;
					try {
						isCurrent = Display.isCurrent();
					} catch (Throwable ignored) {
					}
					// Reduced log mode: render-heartbeat diagnostic log disabled.
					// System.out.println("[gdx-patch][diag] render heartbeat frameId=" + graphics.frameId + ", active="
					//	+ isActive + ", current=" + isCurrent + ", size=" + Display.getWidth() + "x" + Display.getHeight());
				}
				runGlobalTextureCompatOnManagedGrowth("pre-render");
				if (shouldRunGlobalTextureCompatScan()) ensureGlobalTextureCompat();
				ensureColorMaskWritable();
				ScaledRenderPipeline scaledRender = beginScaledRenderFrame(Display.getWidth(), Display.getHeight());
				try {
					listener.render();
					// Catch textures created during listener.render in the same frame.
					runGlobalTextureCompatOnManagedGrowth("post-render");
				} finally {
					finishScaledRenderFrame(scaledRender, Display.getWidth(), Display.getHeight());
				}
				boolean forceDefaultFbo = shouldForceDefaultFramebuffer() || scaledRender != null;
				if (forceDefaultFbo || Boolean.getBoolean("amethyst.lwjgl.diag.post_render_clear")) {
					if (forceDefaultFbo && !defaultFramebufferRebindLogged) {
						// Reduced log mode: default-fbo one-time info log disabled.
						// System.out.println("[gdx-patch] Enabling default framebuffer rebind before swap");
						defaultFramebufferRebindLogged = true;
					}
					bindDefaultFramebufferForSwap();
				}
				if (Boolean.getBoolean("amethyst.lwjgl.diag.post_render_clear")) {
					org.lwjgl.opengl.GL11.glClearColor(1f, 0f, 0f, 1f);
					org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
				}
				Display.update(false);
				renderedFrame = true;
			} else {
				// Sleeps to avoid wasting CPU in an empty loop.
				if (frameRate == -1) frameRate = 10;
				if (frameRate == 0) frameRate = graphics.config.backgroundFPS;
				if (frameRate == 0) frameRate = 30;
			}
			if (shouldUseSoftwareSync(renderedFrame, isActive, frameRate)) Display.sync(frameRate);
		}

		synchronized (lifecycleListeners) {
			LifecycleListener[] listeners = lifecycleListeners.begin();
			for (int i = 0, n = lifecycleListeners.size; i < n; ++i) {
				listeners[i].pause();
				listeners[i].dispose();
			}
			lifecycleListeners.end();
		}
		listener.pause();
		listener.dispose();
		disposeScaledRenderPipeline();
		Display.destroy();
		if (audio != null) audio.dispose();
		if (graphics.config.forceExit) System.exit(-1);
	}

	public boolean executeRunnables () {
		synchronized (runnables) {
			for (int i = runnables.size - 1; i >= 0; i--)
				executedRunnables.add(runnables.get(i));
			runnables.clear();
		}
		if (executedRunnables.size == 0) return false;
		do
			executedRunnables.pop().run();
		while (executedRunnables.size > 0);
		return true;
	}

	@Override
	public ApplicationListener getApplicationListener () {
		return listener;
	}

	@Override
	public Audio getAudio () {
		return audio;
	}

	@Override
	public Files getFiles () {
		return files;
	}

	@Override
	public LwjglGraphics getGraphics () {
		return graphics;
	}

	@Override
	public Input getInput () {
		return input;
	}

	@Override
	public Net getNet () {
		return net;
	}

	@Override
	public ApplicationType getType () {
		return ApplicationType.Desktop;
	}

	@Override
	public int getVersion () {
		return 0;
	}

	public void stop () {
		running = false;
		try {
			mainLoopThread.join();
		} catch (Exception ex) {
		}
	}

	@Override
	public long getJavaHeap () {
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}

	@Override
	public long getNativeHeap () {
		return getJavaHeap();
	}

	ObjectMap<String, Preferences> preferences = new ObjectMap<String, Preferences>();

	@Override
	public Preferences getPreferences (String name) {
		if (preferences.containsKey(name)) {
			return preferences.get(name);
		} else {
			Preferences prefs = new LwjglPreferences(new LwjglFileHandle(new File(preferencesdir, name), preferencesFileType));
			preferences.put(name, prefs);
			return prefs;
		}
	}

	@Override
	public Clipboard getClipboard () {
		return new LwjglClipboard();
	}

	@Override
	public void postRunnable (Runnable runnable) {
		synchronized (runnables) {
			runnables.add(runnable);
			Gdx.graphics.requestRendering();
		}
	}

	@Override
	public void debug (String tag, String message) {
		if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message);
	}

	@Override
	public void debug (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, exception);
	}

	@Override
	public void log (String tag, String message) {
		if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message);
	}

	@Override
	public void log (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, exception);
	}

	@Override
	public void error (String tag, String message) {
		if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message);
	}

	@Override
	public void error (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, exception);
	}

	@Override
	public void setLogLevel (int logLevel) {
		this.logLevel = logLevel;
	}

	@Override
	public int getLogLevel () {
		return logLevel;
	}

	@Override
	public void setApplicationLogger (ApplicationLogger applicationLogger) {
		this.applicationLogger = applicationLogger;
	}

	@Override
	public ApplicationLogger getApplicationLogger () {
		return applicationLogger;
	}


	@Override
	public void exit () {
		postRunnable(new Runnable() {
			@Override
			public void run () {
				running = false;
			}
		});
	}

	@Override
	public void addLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.add(listener);
		}
	}

	@Override
	public void removeLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.removeValue(listener, true);
		}
	}
}
