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
import com.badlogic.gdx.backends.lwjgl.audio.OpenALAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
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
	private static final int CONTEXT_GENERATION_QUERY_ACTION = 2999;
	private static final String NO_CONTEXT_LOG_MARKER =
		"No context is current or a function that is not available in the current context was called.";
	private static final String ZERO_MISSING_FUNCTION_PTR_PROP = "amethyst.lwjgl.diag.zero_missing_function_ptr";
	private static final String FORCE_DEFAULT_FBO_PROP = "amethyst.lwjgl.force_default_framebuffer";
	private static volatile boolean noContextDiagnosticsInstalled;
	private boolean contextRecoveryLogged;
	private boolean contextGenerationUnavailableLogged;
	private boolean missingFunctionPointerPatchLogged;
	private boolean missingFunctionPointerPatched;
	private boolean inactiveRenderSuppressedLogged;
	private boolean firstRenderFrameLogged;
	private boolean defaultFramebufferRebindLogged;
	private int nativeContextGeneration = Integer.MIN_VALUE;
	private boolean pendingNativeContextRebind;
	private Boolean lastActiveState;
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
		config.vSyncEnabled = true;
		return config;
	}

	private void initialize () {
		mainLoopThread = new Thread("LWJGL Application") {
			@Override
			public void run () {
				graphics.setVSync(graphics.config.vSyncEnabled);
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

	private int queryNativeContextGeneration () {
		try {
			String value = CallbackBridge.nativeClipboard(CONTEXT_GENERATION_QUERY_ACTION, null);
			if (value == null) return Integer.MIN_VALUE;
			return Integer.parseInt(value.trim());
		} catch (Throwable t) {
			if (!contextGenerationUnavailableLogged) {
				System.out.println("[gdx-patch] Native context generation query unavailable: " + t);
				contextGenerationUnavailableLogged = true;
			}
			return Integer.MIN_VALUE;
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
				return true;
			}
		} catch (Throwable ignored) {
		}

		try {
			GL.createCapabilities();
			zeroMissingFunctionPointersIfRequested(phase);
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
		try {
			org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
		} catch (Throwable ignored) {
		}
		try {
			org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0);
		} catch (Throwable ignored) {
		}
		try {
			org.lwjgl.opengl.GL11.glViewport(0, 0, Display.getWidth(), Display.getHeight());
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

	void mainLoop () {
		SnapshotArray<LifecycleListener> lifecycleListeners = this.lifecycleListeners;

		try {
			graphics.setupDisplay();
		} catch (LWJGLException e) {
			throw new GdxRuntimeException(e);
		}

		ensureDisplayContextCurrent("create");
		System.out.println("[gdx-patch][diag] listener.create begin");
		listener.create();
		System.out.println("[gdx-patch][diag] listener.create end");
		graphics.resize = true;

		int lastWidth = graphics.getWidth();
		int lastHeight = graphics.getHeight();

		graphics.lastTime = System.nanoTime();
		boolean wasActive = true;
		while (running) {
			Display.processMessages();
			if (Display.isCloseRequested()) exit();

			boolean isActive = Display.isActive();
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
				System.out.println("[gdx-patch][diag] activity state changed: active=" + isActive + ", visible=" + isVisible
					+ ", current=" + isCurrent + ", bgFPS=" + graphics.config.backgroundFPS + ", fgFPS="
					+ graphics.config.foregroundFPS);
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

			if (!isActive && graphics.config.backgroundFPS == -1) {
				if (!inactiveRenderSuppressedLogged) {
					System.out.println("[gdx-patch][diag] suppressing render because active=false and backgroundFPS=-1");
					inactiveRenderSuppressedLogged = true;
				}
				shouldRender = false;
			}
			int frameRate = isActive ? graphics.config.foregroundFPS : graphics.config.backgroundFPS;
			if (shouldRender) {
				if (!firstRenderFrameLogged) {
					boolean isCurrent = false;
					try {
						isCurrent = Display.isCurrent();
					} catch (Throwable ignored) {
					}
					System.out.println("[gdx-patch][diag] first render frame, active=" + isActive + ", current=" + isCurrent);
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
					System.out.println("[gdx-patch][diag] render heartbeat frameId=" + graphics.frameId + ", active="
						+ isActive + ", current=" + isCurrent + ", size=" + Display.getWidth() + "x" + Display.getHeight());
				}
				listener.render();
				boolean forceDefaultFbo = shouldForceDefaultFramebuffer();
				if (forceDefaultFbo || Boolean.getBoolean("amethyst.lwjgl.diag.post_render_clear")) {
					if (forceDefaultFbo && !defaultFramebufferRebindLogged) {
						System.out.println("[gdx-patch] Enabling default framebuffer rebind before swap");
						defaultFramebufferRebindLogged = true;
					}
					bindDefaultFramebufferForSwap();
				}
				if (Boolean.getBoolean("amethyst.lwjgl.diag.post_render_clear")) {
					org.lwjgl.opengl.GL11.glClearColor(1f, 0f, 0f, 1f);
					org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
				}
				Display.update(false);
			} else {
				// Sleeps to avoid wasting CPU in an empty loop.
				if (frameRate == -1) frameRate = 10;
				if (frameRate == 0) frameRate = graphics.config.backgroundFPS;
				if (frameRate == 0) frameRate = 30;
			}
			if (frameRate > 0) Display.sync(frameRate);
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
