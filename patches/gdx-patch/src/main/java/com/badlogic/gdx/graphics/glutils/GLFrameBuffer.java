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

package com.badlogic.gdx.graphics.glutils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/** <p>
 * Encapsulates OpenGL ES 2.0 frame buffer objects. This is a simple helper class which should cover most FBO uses. It will
 * automatically create a gltexture for the color attachment and a renderbuffer for the depth buffer. You can get a hold of the
 * gltexture by {@link GLFrameBuffer#getColorBufferTexture()}. This class will only work with OpenGL ES 2.0.
 * </p>
 *
 * <p>
 * FrameBuffers are managed. In case of an OpenGL context loss, which only happens on Android when a user switches to another
 * application or receives an incoming call, the framebuffer will be automatically recreated.
 * </p>
 *
 * <p>
 * A FrameBuffer must be disposed if it is no longer needed
 * </p>
 *
 * @author mzechner, realitix */
public abstract class GLFrameBuffer<T extends GLTexture> implements Disposable {
	/** the frame buffers **/
	private final static Map<Application, Array<GLFrameBuffer>> buffers = new HashMap<Application, Array<GLFrameBuffer>>();

	private final static int GL_DEPTH24_STENCIL8_OES = 0x88F0;
	private final static String NON_RENDERABLE_FBO_FORMAT_COMPAT_PROP =
		"amethyst.gdx.non_renderable_fbo_format_compat";
	private final static String GPU_RESOURCE_DIAG_ENABLED_PROP = "amethyst.gdx.gpu_resource_diag";
	private final static boolean GPU_RESOURCE_DIAG_ENABLED = readBooleanSystemProperty(GPU_RESOURCE_DIAG_ENABLED_PROP, false);
	private final static String GPU_RESOURCE_DIAG_FBO_STACKS_PROP =
		"amethyst.gdx.gpu_resource_diag.fbo_stacks";
	private final static boolean GPU_RESOURCE_DIAG_FBO_STACKS_ENABLED =
		readBooleanSystemProperty(GPU_RESOURCE_DIAG_FBO_STACKS_PROP, true);
	private final static String GPU_RESOURCE_DIAG_FBO_STACK_LIMIT_PROP =
		"amethyst.gdx.gpu_resource_diag.fbo_stack_limit";
	private final static int GPU_RESOURCE_DIAG_FBO_STACK_LIMIT =
		readIntSystemProperty(GPU_RESOURCE_DIAG_FBO_STACK_LIMIT_PROP, 8, 1, 128);
	private final static String GPU_RESOURCE_DIAG_FBO_STACK_DEPTH_PROP =
		"amethyst.gdx.gpu_resource_diag.fbo_stack_depth";
	private final static int GPU_RESOURCE_DIAG_FBO_STACK_DEPTH =
		readIntSystemProperty(GPU_RESOURCE_DIAG_FBO_STACK_DEPTH_PROP, 8, 1, 32);
	private final static String GPU_RESOURCE_DIAG_FBO_STACK_REPEAT_INTERVAL_PROP =
		"amethyst.gdx.gpu_resource_diag.fbo_stack_repeat_interval";
	private final static int GPU_RESOURCE_DIAG_FBO_STACK_REPEAT_INTERVAL =
		readIntSystemProperty(GPU_RESOURCE_DIAG_FBO_STACK_REPEAT_INTERVAL_PROP, 25, 1, 10000);
	private final static String GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_PROP =
		"amethyst.gdx.fbo_idle_reclaim";
	private final static boolean GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_ENABLED =
		readBooleanSystemProperty(GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_PROP, true);
	private final static String GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_FRAMES_PROP =
		"amethyst.gdx.fbo_idle_reclaim_frames";
	private final static int GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_FRAMES =
		readIntSystemProperty(GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_FRAMES_PROP, 180, 1, 36000);
	private final static String GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_SWEEP_INTERVAL_PROP =
		"amethyst.gdx.fbo_idle_reclaim_sweep_interval_frames";
	private final static int GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_SWEEP_INTERVAL =
		readIntSystemProperty(GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_SWEEP_INTERVAL_PROP, 60, 1, 3600);
	private final static String GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_MIN_BYTES_PROP =
		"amethyst.gdx.fbo_idle_reclaim_min_bytes";
	private final static long GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_MIN_BYTES =
		readLongSystemProperty(GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_MIN_BYTES_PROP, 6L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private final static String GPU_RESOURCE_DIAG_FBO_PRESSURE_DOWNSCALE_PROP =
		"amethyst.gdx.fbo_pressure_downscale";
	private final static boolean GPU_RESOURCE_DIAG_FBO_PRESSURE_DOWNSCALE_ENABLED =
		readBooleanSystemProperty(GPU_RESOURCE_DIAG_FBO_PRESSURE_DOWNSCALE_PROP, true);
	private final static int PRESSURE_DOWNSCALE_NONE = 0;
	private final static int PRESSURE_DOWNSCALE_ALLOW = 1;
	private final static int PRESSURE_DOWNSCALE_PROTECT = 2;
	private final static String GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_PROP =
		"amethyst.gdx.fbo_pressure_soft_budget_bytes";
	private final static long GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_BYTES =
		readLongSystemProperty(GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_PROP, 48L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private final static String GPU_RESOURCE_DIAG_FBO_PRESSURE_HARD_BUDGET_PROP =
		"amethyst.gdx.fbo_pressure_hard_budget_bytes";
	private final static long GPU_RESOURCE_DIAG_FBO_PRESSURE_HARD_BUDGET_BYTES =
		readLongSystemProperty(
			GPU_RESOURCE_DIAG_FBO_PRESSURE_HARD_BUDGET_PROP,
			96L * 1024L * 1024L,
			GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_BYTES,
			Long.MAX_VALUE
		);
	private final static String GPU_RESOURCE_DIAG_FBO_PRESSURE_MIN_BYTES_PROP =
		"amethyst.gdx.fbo_pressure_downscale_min_bytes";
	private final static long GPU_RESOURCE_DIAG_FBO_PRESSURE_MIN_BYTES =
		readLongSystemProperty(
			GPU_RESOURCE_DIAG_FBO_PRESSURE_MIN_BYTES_PROP,
			8L * 1024L * 1024L,
			0L,
			Long.MAX_VALUE
		);
	private final static AtomicLong NEXT_DEBUG_FRAMEBUFFER_ID = new AtomicLong(1L);
	private final static AtomicInteger FRAMEBUFFERS_BUILT = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFERS_DISPOSED = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFERS_LIVE = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFERS_NATIVE_LIVE = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFERS_IDLE_RECLAIMED = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFERS_REBUILT = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFER_IDLE_SWEEPS = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFER_BUILD_STACK_UNIQUES = new AtomicInteger();
	private final static AtomicInteger FRAMEBUFFER_BUILD_STACK_SUPPRESSED = new AtomicInteger();
	private final static AtomicLong FRAMEBUFFERS_NATIVE_BYTES = new AtomicLong();
	private final static ConcurrentHashMap<String, AtomicInteger> FRAMEBUFFER_BUILD_STACK_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private final static ConcurrentHashMap<GLTexture, GLFrameBuffer<?>> FRAMEBUFFER_TEXTURE_OWNERS =
		new ConcurrentHashMap<GLTexture, GLFrameBuffer<?>>();
	private static boolean fboFallbackLogged = false;
	private static boolean nonRenderableFormatFallbackLogged = false;
	private static volatile int frameBufferLivePeak;
	private static volatile long frameBufferNativeBytesPeak;
	private static volatile long currentFrameId;
	private static volatile long lastIdleSweepFrame = -1L;

	private final static class PressureDownscaleDecision {
		private final int mode;
		private final String reason;

		private PressureDownscaleDecision (int mode, String reason) {
			this.mode = mode;
			this.reason = reason == null ? "unspecified" : reason;
		}
	}

	private final long debugFrameBufferId = allocateDebugFrameBufferId();
	private int debugBuildGeneration = 0;
	private boolean nativeResourcesAllocated;
	private boolean buildInProgress;
	private boolean disposed;
	private long lastUsedFrame;
	private long estimatedNativeBytes;
	private int allocationWidth;
	private int allocationHeight;
	private boolean pressureDownscaled;

	/** the color buffer texture **/
	protected T colorTexture;

	/** the default framebuffer handle, a.k.a screen. */
	private static int defaultFramebufferHandle;
	/** true if we have polled for the default handle already. */
	private static boolean defaultFramebufferHandleInitialized = false;

	/** the framebuffer handle **/
	private int framebufferHandle;

	/** the depthbuffer render object handle **/
	private int depthbufferHandle;

	/** the stencilbuffer render object handle **/
	private int stencilbufferHandle;

	/** the depth stencil packed render buffer object handle **/
	private int depthStencilPackedBufferHandle;

	/** width **/
	protected final int width;

	/** height **/
	protected final int height;

	/** depth **/
	protected final boolean hasDepth;

	/** stencil **/
	protected final boolean hasStencil;

	/** if has depth stencil packed buffer **/
	private boolean hasDepthStencilPackedBuffer;

	/** format **/
	protected final Pixmap.Format format;

	/** Creates a new FrameBuffer having the given dimensions and potentially a depth buffer attached.
	 *
	 * @param format
	 * @param width
	 * @param height
	 * @param hasDepth */
	public GLFrameBuffer (Pixmap.Format format, int width, int height, boolean hasDepth) {
		this(format, width, height, hasDepth, false);
	}

	/** Creates a new FrameBuffer having the given dimensions and potentially a depth and a stencil buffer attached.
	 *
	 * @param format the format of the color buffer; according to the OpenGL ES 2.0 spec, only RGB565, RGBA4444 and RGB5_A1 are
	 *           color-renderable
	 * @param width the width of the framebuffer in pixels
	 * @param height the height of the framebuffer in pixels
	 * @param hasDepth whether to attach a depth buffer
	 * @throws com.badlogic.gdx.utils.GdxRuntimeException in case the FrameBuffer could not be created */
	public GLFrameBuffer (Pixmap.Format format, int width, int height, boolean hasDepth, boolean hasStencil) {
		this.width = width;
		this.height = height;
		this.allocationWidth = width;
		this.allocationHeight = height;
		this.format = toCompatibleColorFormat(format);
		this.hasDepth = hasDepth;
		this.hasStencil = hasStencil;
		build();

		addManagedFrameBuffer(Gdx.app, this);
	}

	/** Override this method in a derived class to set up the backing texture as you like. */
	protected abstract T createColorTexture ();
	
	/** Override this method in a derived class to dispose the backing texture as you like. */
	protected abstract void disposeColorTexture (T colorTexture);

	/** Override this method in a derived class to attach the backing texture to the GL framebuffer object. */
	protected abstract void attachFrameBufferColorTexture ();

	protected final int getColorTextureWidth () {
		return allocationWidth > 0 ? allocationWidth : width;
	}

	protected final int getColorTextureHeight () {
		return allocationHeight > 0 ? allocationHeight : height;
	}

	private static Pixmap.Format toCompatibleColorFormat (Pixmap.Format requestedFormat) {
		Pixmap.Format safeFormat = requestedFormat == null ? Pixmap.Format.RGBA8888 : requestedFormat;
		if (!isNonRenderableFboFormatCompatEnabled()) {
			return safeFormat;
		}
		if (safeFormat == Pixmap.Format.Alpha ||
			safeFormat == Pixmap.Format.Intensity ||
			safeFormat == Pixmap.Format.LuminanceAlpha) {
			if (!nonRenderableFormatFallbackLogged) {
				nonRenderableFormatFallbackLogged = true;
				System.out.println("[gdx-patch] GLFrameBuffer fallback: non-color-renderable format "
					+ safeFormat + " -> RGBA8888");
			}
			return Pixmap.Format.RGBA8888;
		}
		return safeFormat;
	}

	private static boolean isNonRenderableFboFormatCompatEnabled () {
		return readBooleanSystemProperty(NON_RENDERABLE_FBO_FORMAT_COMPAT_PROP, true);
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

	private static int readIntSystemProperty (String key, int defaultValue, int minValue, int maxValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		try {
			int parsed = Integer.parseInt(configured);
			if (parsed < minValue) return minValue;
			if (parsed > maxValue) return maxValue;
			return parsed;
		} catch (Throwable ignored) {
			return defaultValue;
		}
	}

	private static long readLongSystemProperty (String key, long defaultValue, long minValue, long maxValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		try {
			long parsed = Long.parseLong(configured);
			if (parsed < minValue) return minValue;
			if (parsed > maxValue) return maxValue;
			return parsed;
		} catch (Throwable ignored) {
			return defaultValue;
		}
	}

	private static int getCurrentFramebufferBinding (GL20 gl) {
		IntBuffer intbuf = ByteBuffer.allocateDirect(Integer.SIZE / 8).order(ByteOrder.nativeOrder()).asIntBuffer();
		gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, intbuf);
		return intbuf.get(0);
	}

	private void build () {
		if (disposed) {
			throw new IllegalStateException("frame buffer has been disposed");
		}
		if (nativeResourcesAllocated || buildInProgress) {
			return;
		}

		buildInProgress = true;
		updateAllocationPlan();
		GL20 gl = Gdx.gl20;
		int previousFramebufferHandle = getCurrentFramebufferBinding(gl);
		boolean createdColorTexture = false;

		// iOS uses a different framebuffer handle! (not necessarily 0)
		if (!defaultFramebufferHandleInitialized) {
			defaultFramebufferHandleInitialized = true;
			if (Gdx.app.getType() == ApplicationType.iOS) {
				IntBuffer intbuf = ByteBuffer.allocateDirect(16 * Integer.SIZE / 8).order(ByteOrder.nativeOrder()).asIntBuffer();
				gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, intbuf);
				defaultFramebufferHandle = intbuf.get(0);
			} else {
				defaultFramebufferHandle = 0;
			}
		}

		try {
			createdColorTexture = ensureColorTextureAvailable("framebuffer_build");

			framebufferHandle = gl.glGenFramebuffer();

			if (hasDepth) {
				depthbufferHandle = gl.glGenRenderbuffer();
			}

			if (hasStencil) {
				stencilbufferHandle = gl.glGenRenderbuffer();
			}

			int colorTextureWidth = getColorTextureWidth();
			int colorTextureHeight = getColorTextureHeight();
			gl.glBindTexture(colorTexture.glTarget, colorTexture.getTextureObjectHandle());

			if (hasDepth) {
				gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, depthbufferHandle);
				gl.glRenderbufferStorage(GL20.GL_RENDERBUFFER, GL20.GL_DEPTH_COMPONENT16, colorTextureWidth, colorTextureHeight);
			}

			if (hasStencil) {
				gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, stencilbufferHandle);
				gl.glRenderbufferStorage(GL20.GL_RENDERBUFFER, GL20.GL_STENCIL_INDEX8, colorTextureWidth, colorTextureHeight);
			}

			gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebufferHandle);

			attachFrameBufferColorTexture();

			if (hasDepth) {
				gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, depthbufferHandle);
			}

			if (hasStencil) {
				gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_STENCIL_ATTACHMENT, GL20.GL_RENDERBUFFER, stencilbufferHandle);
			}

			gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, 0);
			gl.glBindTexture(colorTexture.glTarget, 0);

			int result = gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);

			if (result == GL20.GL_FRAMEBUFFER_UNSUPPORTED && hasDepth && hasStencil
				&& (Gdx.graphics.supportsExtension("GL_OES_packed_depth_stencil")
					|| Gdx.graphics.supportsExtension("GL_EXT_packed_depth_stencil"))) {
				if (hasDepth) {
					gl.glDeleteRenderbuffer(depthbufferHandle);
					depthbufferHandle = 0;
				}
				if (hasStencil) {
					gl.glDeleteRenderbuffer(stencilbufferHandle);
					stencilbufferHandle = 0;
				}

				depthStencilPackedBufferHandle = gl.glGenRenderbuffer();
				hasDepthStencilPackedBuffer = true;
				gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, depthStencilPackedBufferHandle);
				gl.glRenderbufferStorage(GL20.GL_RENDERBUFFER, GL_DEPTH24_STENCIL8_OES, colorTextureWidth, colorTextureHeight);
				gl.glBindRenderbuffer(GL20.GL_RENDERBUFFER, 0);

				gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, depthStencilPackedBufferHandle);
				gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_STENCIL_ATTACHMENT, GL20.GL_RENDERBUFFER, depthStencilPackedBufferHandle);
				result = gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);
			}

			if (result != GL20.GL_FRAMEBUFFER_COMPLETE) {
				if (isUnknownFramebufferStatus(result) && isFboFallbackEnabled()) {
					int originalStatus = result;
					int originalError = gl.glGetError();
					result = tryRecoverUnknownFramebufferStatus(gl);
					int recoveredError = gl.glGetError();
					if (result == GL20.GL_FRAMEBUFFER_COMPLETE) {
						if (!fboFallbackLogged) {
							fboFallbackLogged = true;
							System.out.println("[gdx-patch] GLFrameBuffer fallback recovered unknown status="
								+ originalStatus + " (0x" + Integer.toHexString(originalStatus) + "), glError=0x"
								+ Integer.toHexString(originalError) + " -> complete"
								+ " (recovery glError=0x" + Integer.toHexString(recoveredError) + ")");
						}
						recordFrameBufferBuild(colorTextureWidth, colorTextureHeight, "unknown_status_recovered");
						gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previousFramebufferHandle);
						return;
					}
					if (shouldBypassUnknownFramebufferStatus()) {
						if (!fboFallbackLogged) {
							fboFallbackLogged = true;
							System.out.println("[gdx-patch] GLFrameBuffer fallback active: ignore unknown status="
								+ originalStatus + " (0x" + Integer.toHexString(originalStatus) + "), glError=0x"
								+ Integer.toHexString(originalError) + ", recoveredStatus=" + result + " (0x"
								+ Integer.toHexString(result) + "), recovery glError=0x"
								+ Integer.toHexString(recoveredError));
						}
						recordFrameBufferBuild(colorTextureWidth, colorTextureHeight, "unknown_status_bypass");
						gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previousFramebufferHandle);
						return;
					}
					if (!fboFallbackLogged) {
						fboFallbackLogged = true;
						System.out.println("[gdx-patch] GLFrameBuffer fallback recovery failed: originalStatus="
							+ originalStatus + " (0x" + Integer.toHexString(originalStatus) + "), original glError=0x"
							+ Integer.toHexString(originalError) + ", recoveredStatus=" + result + " (0x"
							+ Integer.toHexString(result) + "), recovery glError=0x"
							+ Integer.toHexString(recoveredError));
					}
				}

				gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previousFramebufferHandle);
				releaseNativeResources("build_failed", false, true);
				if (createdColorTexture && colorTexture != null && !nativeResourcesAllocated) {
					FRAMEBUFFER_TEXTURE_OWNERS.remove(colorTexture);
					disposeColorTexture(colorTexture);
					colorTexture = null;
				}

				if (result == GL20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT)
					throw new IllegalStateException("frame buffer couldn't be constructed: incomplete attachment");
				if (result == GL20.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS)
					throw new IllegalStateException("frame buffer couldn't be constructed: incomplete dimensions");
				if (result == GL20.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT)
					throw new IllegalStateException("frame buffer couldn't be constructed: missing attachment");
				if (result == GL20.GL_FRAMEBUFFER_UNSUPPORTED)
					throw new IllegalStateException("frame buffer couldn't be constructed: unsupported combination of formats");
				throw new IllegalStateException("frame buffer couldn't be constructed: unknown error " + result);
			}
			recordFrameBufferBuild(colorTextureWidth, colorTextureHeight, "build");
		} finally {
			buildInProgress = false;
			gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previousFramebufferHandle);
		}
	}

	private int tryRecoverUnknownFramebufferStatus (GL20 gl) {
		gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebufferHandle);
		if (depthbufferHandle != 0) {
			gl.glDeleteRenderbuffer(depthbufferHandle);
			depthbufferHandle = 0;
		}
		if (stencilbufferHandle != 0) {
			gl.glDeleteRenderbuffer(stencilbufferHandle);
			stencilbufferHandle = 0;
		}
		if (depthStencilPackedBufferHandle != 0) {
			gl.glDeleteRenderbuffer(depthStencilPackedBufferHandle);
			depthStencilPackedBufferHandle = 0;
		}
		hasDepthStencilPackedBuffer = false;
		gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_RENDERBUFFER, 0);
		gl.glFramebufferRenderbuffer(GL20.GL_FRAMEBUFFER, GL20.GL_STENCIL_ATTACHMENT, GL20.GL_RENDERBUFFER, 0);
		return gl.glCheckFramebufferStatus(GL20.GL_FRAMEBUFFER);
	}

	private void updateAllocationPlan () {
		allocationWidth = width;
		allocationHeight = height;
		pressureDownscaled = false;
		if (!GPU_RESOURCE_DIAG_FBO_PRESSURE_DOWNSCALE_ENABLED) return;
		if (!isLargePressureDownscaleCandidate()) return;

		long requestedBytes = estimateFrameBufferBytes(width, height, String.valueOf(format), hasDepth, hasStencil);
		if (requestedBytes < GPU_RESOURCE_DIAG_FBO_PRESSURE_MIN_BYTES) return;
		long projectedFullBytes = FRAMEBUFFERS_NATIVE_BYTES.get() + requestedBytes;
		if (projectedFullBytes <= GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_BYTES) return;
		String stackKey = captureRelevantFrameBufferBuildStack();
		PressureDownscaleDecision decision =
			classifyPressureDownscaleDecision(stackKey, projectedFullBytes, requestedBytes);
		if (decision.mode == PRESSURE_DOWNSCALE_PROTECT) {
			System.out.println("[gdx-diag] GLFrameBuffer pressure_downscale_skip id=" + debugFrameBufferId
				+ " requested=" + width + "x" + height
				+ " requestedBytes=" + requestedBytes
				+ " projectedFullBytes=" + projectedFullBytes
				+ " budgetBytes=" + GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_BYTES
				+ " protected=" + decision.reason);
			return;
		}
		if (decision.mode != PRESSURE_DOWNSCALE_ALLOW) return;

		int downscaledWidth = Math.max(1, (width + 1) / 2);
		int downscaledHeight = Math.max(1, (height + 1) / 2);
		allocationWidth = downscaledWidth;
		allocationHeight = downscaledHeight;
		pressureDownscaled = true;
		System.out.println("[gdx-diag] GLFrameBuffer pressure_downscale id=" + debugFrameBufferId
			+ " requested=" + width + "x" + height
			+ " allocated=" + allocationWidth + "x" + allocationHeight
			+ " requestedBytes=" + requestedBytes
			+ " projectedFullBytes=" + projectedFullBytes
			+ " budgetBytes=" + GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_BYTES
			+ " reason=" + decision.reason);
	}

	private boolean isLargePressureDownscaleCandidate () {
		if (width < 2 || height < 2) return false;
		if (Gdx.graphics != null) {
			int backBufferWidth = Gdx.graphics.getBackBufferWidth();
			int backBufferHeight = Gdx.graphics.getBackBufferHeight();
			if (backBufferWidth > 0 && backBufferHeight > 0) {
				long requestedPixels = (long)width * (long)height;
				long backBufferPixels = (long)backBufferWidth * (long)backBufferHeight;
				boolean largeArea = requestedPixels * 2L >= backBufferPixels;
				boolean largeWidth = width * 3 >= backBufferWidth * 2;
				boolean largeHeight = height * 3 >= backBufferHeight * 2;
				if (largeArea && largeWidth && largeHeight) {
					return true;
				}
			}
		}
		return (long)width * (long)height >= 1280L * 720L;
	}

	private PressureDownscaleDecision classifyPressureDownscaleDecision (
		String stackKey,
		long projectedFullBytes,
		long requestedBytes
	) {
		String protectedReason = resolvePressureDownscaleProtectReason(stackKey);
		if (protectedReason != null) {
			return new PressureDownscaleDecision(PRESSURE_DOWNSCALE_PROTECT, protectedReason);
		}
		if (projectedFullBytes >= GPU_RESOURCE_DIAG_FBO_PRESSURE_HARD_BUDGET_BYTES) {
			return new PressureDownscaleDecision(PRESSURE_DOWNSCALE_ALLOW, "hard_budget");
		}
		if (containsExternalModNamespace(stackKey)) {
			return new PressureDownscaleDecision(PRESSURE_DOWNSCALE_ALLOW, "external_stack");
		}
		if (containsAnyStackFragment(
			stackKey,
			".vfx.",
			".effect.",
			".effects.",
			".postfx",
			".postprocess",
			".shader",
			".mask",
			".glow",
			".blur",
			".outline",
			".particles.",
			".cutscene.",
			".cutscenes."
		)) {
			return new PressureDownscaleDecision(PRESSURE_DOWNSCALE_ALLOW, "effect_stack");
		}
		if (requestedBytes >= GPU_RESOURCE_DIAG_FBO_PRESSURE_SOFT_BUDGET_BYTES / 2L) {
			return new PressureDownscaleDecision(PRESSURE_DOWNSCALE_ALLOW, "large_budget_share");
		}
		return new PressureDownscaleDecision(PRESSURE_DOWNSCALE_NONE, "below_policy");
	}

	private String resolvePressureDownscaleProtectReason (String stackKey) {
		if (containsAnyStackFragment(
			stackKey,
			"com.badlogic.gdx.backends.lwjgl.LwjglApplication$ScaledRenderPipeline",
			"basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.ApplyScreenPostProcessor"
		)) {
			if (containsStackFragment(
				stackKey,
				"com.badlogic.gdx.backends.lwjgl.LwjglApplication$ScaledRenderPipeline"
			)) {
				return "scaled_render_pipeline";
			}
			return "ApplyScreenPostProcessor";
		}
		return null;
	}

	private boolean ensureColorTextureAvailable (String reason) {
		if (colorTexture == null) {
			colorTexture = createColorTexture();
			FRAMEBUFFER_TEXTURE_OWNERS.put(colorTexture, this);
			ensureTextureAllocationMatchesPlan(colorTexture, reason + "_created");
			return true;
		}
		FRAMEBUFFER_TEXTURE_OWNERS.put(colorTexture, this);
		if (colorTexture.hasTextureObjectHandle()) {
			return false;
		}
		if (colorTexture instanceof Texture) {
			Texture texture = (Texture)colorTexture;
			prepareTextureAllocationData(texture, reason + "_restore_prepare");
			colorTexture.restoreHandleForReuse(Gdx.gl.glGenTexture(), reason + "_texture_restore");
			try {
				texture.load(texture.getTextureData());
				restoreTextureLogicalSize(texture, reason + "_restore_logical");
			} catch (Throwable t) {
				colorTexture.releaseHandleForReuse(reason + "_texture_restore_failed");
				throw t;
			}
			return false;
		}

		T previousTexture = colorTexture;
		FRAMEBUFFER_TEXTURE_OWNERS.remove(previousTexture);
		T rebuiltTexture = createColorTexture();
		colorTexture = rebuiltTexture;
		FRAMEBUFFER_TEXTURE_OWNERS.put(rebuiltTexture, this);
		disposeColorTexture(previousTexture);
		return true;
	}

	private void ensureTextureAllocationMatchesPlan (T texture, String reason) {
		if (!(texture instanceof Texture)) return;
		Texture colorBufferTexture = (Texture)texture;
		if (!(colorBufferTexture.getTextureData() instanceof GLOnlyTextureData)) return;

		GLOnlyTextureData data = (GLOnlyTextureData)colorBufferTexture.getTextureData();
		int plannedWidth = getColorTextureWidth();
		int plannedHeight = getColorTextureHeight();
		boolean changed = prepareTextureAllocationData(colorBufferTexture, reason);
		if (changed && colorBufferTexture.hasTextureObjectHandle()) {
			colorBufferTexture.load(colorBufferTexture.getTextureData());
			System.out.println("[gdx-diag] GLFrameBuffer allocation_applied id=" + debugFrameBufferId
				+ " reason=" + reason
				+ " allocated=" + plannedWidth + "x" + plannedHeight
				+ " requested=" + width + "x" + height
				+ " handle=" + colorBufferTexture.peekTextureObjectHandle());
		}
		restoreTextureLogicalSize(colorBufferTexture, reason + "_logical");
		if (!changed && (data.width != width || data.height != height)) {
			restoreTextureLogicalSize(colorBufferTexture, reason + "_logical");
		}
	}

	private boolean prepareTextureAllocationData (Texture colorBufferTexture, String reason) {
		if (!(colorBufferTexture.getTextureData() instanceof GLOnlyTextureData)) return false;
		GLOnlyTextureData data = (GLOnlyTextureData)colorBufferTexture.getTextureData();
		int plannedWidth = getColorTextureWidth();
		int plannedHeight = getColorTextureHeight();
		if (data.width == plannedWidth && data.height == plannedHeight) {
			return false;
		}
		int previousWidth = data.width;
		int previousHeight = data.height;
		data.width = plannedWidth;
		data.height = plannedHeight;
		System.out.println("[gdx-diag] GLFrameBuffer allocation_fixup id=" + debugFrameBufferId
			+ " reason=" + reason
			+ " requested=" + width + "x" + height
			+ " allocated=" + plannedWidth + "x" + plannedHeight
			+ " previous=" + previousWidth + "x" + previousHeight
			+ " textureClass=" + colorBufferTexture.getClass().getName());
		return true;
	}

	private void restoreTextureLogicalSize (Texture colorBufferTexture, String reason) {
		if (!(colorBufferTexture.getTextureData() instanceof GLOnlyTextureData)) return;
		GLOnlyTextureData data = (GLOnlyTextureData)colorBufferTexture.getTextureData();
		if (data.width == width && data.height == height) {
			return;
		}
		int previousWidth = data.width;
		int previousHeight = data.height;
		data.width = width;
		data.height = height;
		System.out.println("[gdx-diag] GLFrameBuffer logical_size_restore id=" + debugFrameBufferId
			+ " reason=" + reason
			+ " logical=" + width + "x" + height
			+ " previous=" + previousWidth + "x" + previousHeight);
	}

	private boolean releaseNativeResources (String reason, boolean permanentDispose, boolean deleteGlHandles) {
		boolean released = false;
		GL20 gl = Gdx.gl20;
		int releasedFramebufferHandle = framebufferHandle;
		int releasedColorTextureHandle = colorTexture == null ? 0 : colorTexture.peekTextureObjectHandle();
		long releasedBytes = estimatedNativeBytes;

		if (hasDepthStencilPackedBuffer && depthStencilPackedBufferHandle != 0) {
			if (deleteGlHandles) {
				gl.glDeleteRenderbuffer(depthStencilPackedBufferHandle);
			}
			depthStencilPackedBufferHandle = 0;
			released = true;
		} else {
			if (depthbufferHandle != 0) {
				if (deleteGlHandles) {
					gl.glDeleteRenderbuffer(depthbufferHandle);
				}
				depthbufferHandle = 0;
				released = true;
			}
			if (stencilbufferHandle != 0) {
				if (deleteGlHandles) {
					gl.glDeleteRenderbuffer(stencilbufferHandle);
				}
				stencilbufferHandle = 0;
				released = true;
			}
		}
		hasDepthStencilPackedBuffer = false;

		if (framebufferHandle != 0) {
			if (deleteGlHandles) {
				gl.glDeleteFramebuffer(framebufferHandle);
			}
			framebufferHandle = 0;
			released = true;
		}

		if (colorTexture != null) {
			if (permanentDispose) {
				FRAMEBUFFER_TEXTURE_OWNERS.remove(colorTexture);
				disposeColorTexture(colorTexture);
				colorTexture = null;
				released = released || releasedColorTextureHandle != 0;
			} else if (colorTexture.hasTextureObjectHandle()) {
				if (deleteGlHandles) {
					colorTexture.releaseHandleForReuse("fbo_" + reason);
				} else {
					colorTexture.invalidateHandleForReuse("fbo_" + reason);
				}
				released = true;
			}
		}

		boolean hadNativeResources = nativeResourcesAllocated;
		nativeResourcesAllocated = false;
		estimatedNativeBytes = 0L;
		if (hadNativeResources) {
			onFrameBufferNativeReleased(debugFrameBufferId, releasedFramebufferHandle, releasedColorTextureHandle, releasedBytes, reason);
		}
		return released;
	}

	private void onColorTextureAccess (String reason) {
		if (disposed || buildInProgress) return;
		if (!nativeResourcesAllocated) {
			build();
		}
		markUsed(reason);
	}

	private void markUsed (String reason) {
		long frameId = currentFrameId;
		if (frameId < 0L) {
			frameId = 0L;
		}
		lastUsedFrame = frameId;
	}

	private boolean isEligibleForIdleReclaim () {
		return nativeResourcesAllocated && estimatedNativeBytes >= GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_MIN_BYTES;
	}

	private boolean reclaimIfIdle (long frameId) {
		if (!GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_ENABLED) return false;
		if (disposed || buildInProgress || !isEligibleForIdleReclaim()) return false;
		if (framebufferHandle == 0) return false;
		long idleFrames = frameId - lastUsedFrame;
		if (idleFrames < GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_FRAMES) return false;
		long reclaimedBytes = estimatedNativeBytes;
		if (!releaseNativeResources("idle_reclaim", false, true)) return false;
		int reclaimed = FRAMEBUFFERS_IDLE_RECLAIMED.incrementAndGet();
		System.out.println("[gdx-diag] GLFrameBuffer idle_reclaim id=" + debugFrameBufferId
			+ " idleFrames=" + idleFrames
			+ " bytes=" + reclaimedBytes
			+ " reclaimed=" + reclaimed);
		return true;
	}

	private static boolean isUnknownFramebufferStatus (int status) {
		return status != GL20.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT
			&& status != GL20.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS
			&& status != GL20.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT
			&& status != GL20.GL_FRAMEBUFFER_UNSUPPORTED;
	}

	private static boolean shouldBypassUnknownFramebufferStatus () {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		for (int i = 0; i < stack.length; i++) {
			String className = stack[i].getClassName();
			if (className == null) {
				continue;
			}
			if (className.indexOf("basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.ApplyScreenPostProcessor") >= 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean isFboFallbackEnabled () {
		return false;
	}

	/** Releases all resources associated with the FrameBuffer. */
	@Override
	public void dispose () {
		if (disposed) return;
		disposed = true;
		int disposedFramebufferHandle = framebufferHandle;
		int disposedColorTextureHandle = colorTexture == null ? 0 : colorTexture.peekTextureObjectHandle();
		releaseNativeResources("dispose", true, true);
		onFrameBufferDisposed(debugFrameBufferId, disposedFramebufferHandle, disposedColorTextureHandle, "dispose");
		if (buffers.get(Gdx.app) != null) buffers.get(Gdx.app).removeValue(this, true);
	}

	/** Makes the frame buffer current so everything gets drawn to it. */
	public void bind () {
		build();
		markUsed("bind");
		Gdx.gl20.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebufferHandle);
	}

	/** Unbinds the framebuffer, all drawing will be performed to the normal framebuffer from here on. */
	public static void unbind () {
		Gdx.gl20.glBindFramebuffer(GL20.GL_FRAMEBUFFER, defaultFramebufferHandle);
	}

	/** Binds the frame buffer and sets the viewport accordingly, so everything gets drawn to it. */
	public void begin () {
		// Some render paths leave alpha color writes disabled and never restore state.
		// Ensure offscreen buffers can clear/write alpha before rendering translucent overlays.
		Gdx.gl20.glColorMask(true, true, true, true);
		bind();
		setFrameBufferViewport();
	}

	/** Sets viewport to the dimensions of framebuffer. Called by {@link #begin()}. */
	protected void setFrameBufferViewport () {
		Gdx.gl20.glViewport(0, 0, getColorTextureWidth(), getColorTextureHeight());
	}

	/** Unbinds the framebuffer, all drawing will be performed to the normal framebuffer from here on. */
	public void end () {
		int width = LwjglApplication.getScaledRenderBackBufferWidthOverride();
		int height = LwjglApplication.getScaledRenderBackBufferHeightOverride();
		if (width <= 0 || height <= 0) {
			width = Gdx.graphics.getBackBufferWidth();
			height = Gdx.graphics.getBackBufferHeight();
		}
		end(0, 0, width, height);
	}

	/** Unbinds the framebuffer and sets viewport sizes, all drawing will be performed to the normal framebuffer from here on.
	 *
	 * @param x the x-axis position of the viewport in pixels
	 * @param y the y-asis position of the viewport in pixels
	 * @param width the width of the viewport in pixels
	 * @param height the height of the viewport in pixels */
	public void end (int x, int y, int width, int height) {
		unbind();
		Gdx.gl20.glViewport(x, y, width, height);
	}

	/** @return the gl texture */
	public T getColorBufferTexture () {
		markUsed("get_color_texture");
		return colorTexture;
	}

	/** @return The OpenGL handle of the framebuffer (see {@link GL20#glGenFramebuffer()}) */
	public int getFramebufferHandle () {
		build();
		markUsed("get_framebuffer_handle");
		return framebufferHandle;
	}

	/** @return The OpenGL handle of the (optional) depth buffer (see {@link GL20#glGenRenderbuffer()}). May return 0 even if depth buffer enabled */
	public int getDepthBufferHandle () {
		return depthbufferHandle;
	}

	/** @return The OpenGL handle of the (optional) stencil buffer (see {@link GL20#glGenRenderbuffer()}). May return 0 even if stencil buffer enabled */
	public int getStencilBufferHandle () {
		return stencilbufferHandle;
	}
	
	/** @return The OpenGL handle of the packed depth & stencil buffer (GL_DEPTH24_STENCIL8_OES) or 0 if not used. **/
	protected int getDepthStencilPackedBuffer () {
		return depthStencilPackedBufferHandle;
	}

	/** @return the height of the framebuffer in pixels */
	public int getHeight () {
		return height;
	}

	/** @return the width of the framebuffer in pixels */
	public int getWidth () {
		return width;
	}

	/** @return the depth of the framebuffer in pixels (if applicable) */
	public int getDepth () {
		return colorTexture == null ? 0 : colorTexture.getDepth();
	}

	private static void addManagedFrameBuffer (Application app, GLFrameBuffer frameBuffer) {
		Array<GLFrameBuffer> managedResources = buffers.get(app);
		if (managedResources == null) managedResources = new Array<GLFrameBuffer>();
		managedResources.add(frameBuffer);
		buffers.put(app, managedResources);
	}

	/** Invalidates all frame buffers. This can be used when the OpenGL context is lost to rebuild all managed frame buffers. This
	 * assumes that the texture attached to this buffer has already been rebuild! Use with care. */
	public static void invalidateAllFrameBuffers (Application app) {
		if (Gdx.gl20 == null) return;

		Array<GLFrameBuffer> bufferArray = buffers.get(app);
		if (bufferArray == null) return;
		for (int i = 0; i < bufferArray.size; i++) {
			GLFrameBuffer buffer = bufferArray.get(i);
			boolean shouldRebuild = buffer.nativeResourcesAllocated;
			buffer.releaseNativeResources("context_lost", false, false);
			if (shouldRebuild) {
				buffer.build();
			}
		}
	}

	public static void clearAllFrameBuffers (Application app) {
		buffers.remove(app);
	}

	public static void noteFrameRendered (long frameId) {
		if (frameId >= 0L) {
			currentFrameId = frameId;
		}
	}

	public static void onExternalTextureAccess (GLTexture texture, String reason) {
		if (texture == null) return;
		GLFrameBuffer<?> frameBuffer = FRAMEBUFFER_TEXTURE_OWNERS.get(texture);
		if (frameBuffer != null) {
			frameBuffer.onColorTextureAccess(reason);
		}
	}

	public static void reclaimIdleFrameBuffers (Application app, long frameId) {
		if (!GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_ENABLED || Gdx.gl20 == null) return;
		if (app == null || frameId < 0L) return;
		if (lastIdleSweepFrame >= 0L
			&& frameId - lastIdleSweepFrame < GPU_RESOURCE_DIAG_FBO_IDLE_RECLAIM_SWEEP_INTERVAL) {
			return;
		}
		lastIdleSweepFrame = frameId;

		Array<GLFrameBuffer> bufferArray = buffers.get(app);
		if (bufferArray == null || bufferArray.size == 0) return;

		int reclaimed = 0;
		for (int i = 0; i < bufferArray.size; i++) {
			if (bufferArray.get(i).reclaimIfIdle(frameId)) {
				reclaimed++;
			}
		}
		if (reclaimed > 0) {
			int sweeps = FRAMEBUFFER_IDLE_SWEEPS.incrementAndGet();
			System.out.println("[gdx-diag] GLFrameBuffer idle_sweep frame=" + frameId
				+ " reclaimed=" + reclaimed
				+ " sweeps=" + sweeps
				+ " nativeLive=" + FRAMEBUFFERS_NATIVE_LIVE.get()
				+ " nativeBytes=" + FRAMEBUFFERS_NATIVE_BYTES.get());
		}
	}

	public static StringBuilder getManagedStatus (final StringBuilder builder) {
		builder.append("Managed buffers/app: { ");
		for (Application app : buffers.keySet()) {
			builder.append(buffers.get(app).size);
			builder.append(" ");
		}
		builder.append("}");
		return builder;
	}

	public static String getManagedStatus () {
		return getManagedStatus(new StringBuilder()).toString();
	}

	public static String getDebugStatusSummary () {
		if (!GPU_RESOURCE_DIAG_ENABLED) return "frameBuffersDiag=disabled";
		return "frameBuffersLive=" + FRAMEBUFFERS_LIVE.get()
			+ " frameBuffersPeak=" + frameBufferLivePeak
			+ " frameBuffersNative=" + FRAMEBUFFERS_NATIVE_LIVE.get()
			+ " frameBufferBytes=" + FRAMEBUFFERS_NATIVE_BYTES.get()
			+ " frameBufferBytesPeak=" + frameBufferNativeBytesPeak
			+ " frameBuffersBuilt=" + FRAMEBUFFERS_BUILT.get()
			+ " frameBuffersRebuilt=" + FRAMEBUFFERS_REBUILT.get()
			+ " frameBuffersReclaimed=" + FRAMEBUFFERS_IDLE_RECLAIMED.get()
			+ " frameBuffersDisposed=" + FRAMEBUFFERS_DISPOSED.get();
	}

	public static long getEstimatedNativeBytes () {
		return FRAMEBUFFERS_NATIVE_BYTES.get();
	}

	private void recordFrameBufferBuild (int colorTextureWidth, int colorTextureHeight, String reason) {
		nativeResourcesAllocated = true;
		estimatedNativeBytes = estimateFrameBufferBytes(colorTextureWidth, colorTextureHeight, String.valueOf(format), hasDepth, hasStencil);
		markUsed(reason);
		debugBuildGeneration++;
		onFrameBufferBuilt(
			debugFrameBufferId,
			getClass().getName(),
			debugBuildGeneration,
			framebufferHandle,
			colorTexture == null ? 0 : colorTexture.peekTextureObjectHandle(),
			colorTextureWidth,
			colorTextureHeight,
			hasDepth,
			hasStencil,
			String.valueOf(format),
			reason
		);
	}

	private static long allocateDebugFrameBufferId () {
		return GPU_RESOURCE_DIAG_ENABLED ? NEXT_DEBUG_FRAMEBUFFER_ID.getAndIncrement() : 0L;
	}

	private static void onFrameBufferBuilt (
		long id,
		String className,
		int buildGeneration,
		int framebufferHandle,
		int colorTextureHandle,
		int width,
		int height,
		boolean hasDepth,
		boolean hasStencil,
		String format,
		String reason
	) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		int built = FRAMEBUFFERS_BUILT.incrementAndGet();
		int live = FRAMEBUFFERS_LIVE.get();
		if (buildGeneration == 1) {
			live = FRAMEBUFFERS_LIVE.incrementAndGet();
		}
		int nativeLive = FRAMEBUFFERS_NATIVE_LIVE.incrementAndGet();
		if (buildGeneration > 1) {
			FRAMEBUFFERS_REBUILT.incrementAndGet();
		}
		long estimatedBytes = estimateFrameBufferBytes(width, height, format, hasDepth, hasStencil);
		long nativeBytes = FRAMEBUFFERS_NATIVE_BYTES.addAndGet(estimatedBytes);
		if (live > frameBufferLivePeak) {
			frameBufferLivePeak = live;
		}
		if (nativeBytes > frameBufferNativeBytesPeak) {
			frameBufferNativeBytesPeak = nativeBytes;
		}
		System.out.println("[gdx-diag] GLFrameBuffer build id=" + id
			+ " generation=" + buildGeneration
			+ " reason=" + reason
			+ " class=" + className
			+ " fb=" + framebufferHandle
			+ " colorTex=" + colorTextureHandle
			+ " size=" + width + "x" + height
			+ " format=" + format
			+ " depth=" + hasDepth
			+ " stencil=" + hasStencil
			+ " built=" + built
			+ " liveFrameBuffers=" + live
			+ " nativeFrameBuffers=" + nativeLive
			+ " nativeBytes=" + nativeBytes);
		logFrameBufferBuildStack(id, buildGeneration, width, height, format, hasDepth, hasStencil);
	}

	private static void onFrameBufferNativeReleased (
		long id,
		int framebufferHandle,
		int colorTextureHandle,
		long releasedBytes,
		String reason
	) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		int nativeLive = FRAMEBUFFERS_NATIVE_LIVE.decrementAndGet();
		if (nativeLive < 0) {
			FRAMEBUFFERS_NATIVE_LIVE.set(0);
			nativeLive = 0;
		}
		long nativeBytes = FRAMEBUFFERS_NATIVE_BYTES.addAndGet(-releasedBytes);
		if (nativeBytes < 0L) {
			FRAMEBUFFERS_NATIVE_BYTES.set(0L);
			nativeBytes = 0L;
		}
		System.out.println("[gdx-diag] GLFrameBuffer native_release id=" + id
			+ " fb=" + framebufferHandle
			+ " colorTex=" + colorTextureHandle
			+ " bytes=" + releasedBytes
			+ " reason=" + reason
			+ " nativeFrameBuffers=" + nativeLive
			+ " nativeBytes=" + nativeBytes);
	}

	private static void onFrameBufferDisposed (long id, int framebufferHandle, int colorTextureHandle, String reason) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		int disposed = FRAMEBUFFERS_DISPOSED.incrementAndGet();
		int live = FRAMEBUFFERS_LIVE.decrementAndGet();
		if (live < 0) {
			FRAMEBUFFERS_LIVE.set(0);
			live = 0;
		}
		System.out.println("[gdx-diag] GLFrameBuffer dispose id=" + id
			+ " fb=" + framebufferHandle
			+ " colorTex=" + colorTextureHandle
			+ " reason=" + reason
			+ " disposed=" + disposed
			+ " liveFrameBuffers=" + live);
	}

	private static long estimateFrameBufferBytes (int width, int height, String format, boolean hasDepth, boolean hasStencil) {
		long pixels = Math.max(0L, (long)width * (long)height);
		long colorBytes = pixels * estimateBytesPerPixel(format);
		long extraBytes = 0L;
		if (hasDepth && hasStencil) {
			extraBytes = pixels * 4L;
		} else if (hasDepth) {
			extraBytes = pixels * 2L;
		} else if (hasStencil) {
			extraBytes = pixels;
		}
		return colorBytes + extraBytes;
	}

	private static long estimateBytesPerPixel (String format) {
		if (format == null) return 4L;
		if ("RGBA8888".equals(format)) return 4L;
		if ("RGB888".equals(format)) return 3L;
		if ("RGB565".equals(format) || "RGBA4444".equals(format) || "RGB5_A1".equals(format)
			|| "LuminanceAlpha".equals(format)) {
			return 2L;
		}
		if ("Alpha".equals(format) || "Intensity".equals(format) || "Luminance".equals(format)) {
			return 1L;
		}
		return 4L;
	}

	private static void logFrameBufferBuildStack (
		long id,
		int buildGeneration,
		int width,
		int height,
		String format,
		boolean hasDepth,
		boolean hasStencil
	) {
		if (!GPU_RESOURCE_DIAG_ENABLED || !GPU_RESOURCE_DIAG_FBO_STACKS_ENABLED || id == 0L || buildGeneration != 1) return;
		String stackKey = captureRelevantFrameBufferBuildStack();
		if (stackKey == null) return;
		AtomicInteger existing = FRAMEBUFFER_BUILD_STACK_COUNTS.putIfAbsent(stackKey, new AtomicInteger(1));
		if (existing == null) {
			int uniqueCount = FRAMEBUFFER_BUILD_STACK_UNIQUES.incrementAndGet();
			if (uniqueCount <= GPU_RESOURCE_DIAG_FBO_STACK_LIMIT) {
				System.out.println("[gdx-diag] GLFrameBuffer stack_sample id=" + id
					+ " unique=" + uniqueCount
					+ " size=" + width + "x" + height
					+ " format=" + format
					+ " depth=" + hasDepth
					+ " stencil=" + hasStencil
					+ " stack=" + stackKey);
			} else {
				int suppressed = FRAMEBUFFER_BUILD_STACK_SUPPRESSED.incrementAndGet();
				if (suppressed == 1 || suppressed % GPU_RESOURCE_DIAG_FBO_STACK_REPEAT_INTERVAL == 0) {
					System.out.println("[gdx-diag] GLFrameBuffer stack_sample_suppressed suppressed=" + suppressed
						+ " limit=" + GPU_RESOURCE_DIAG_FBO_STACK_LIMIT
						+ " latestSize=" + width + "x" + height
						+ " format=" + format);
				}
			}
			return;
		}

		int repeatCount = existing.incrementAndGet();
		if (repeatCount == 2 || repeatCount % GPU_RESOURCE_DIAG_FBO_STACK_REPEAT_INTERVAL == 0) {
			System.out.println("[gdx-diag] GLFrameBuffer stack_repeat id=" + id
				+ " repeats=" + repeatCount
				+ " size=" + width + "x" + height
				+ " format=" + format
				+ " stack=" + stackKey);
		}
	}

	private static String captureRelevantFrameBufferBuildStack () {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		StringBuilder builder = new StringBuilder(256);
		int appended = 0;
		for (int i = 0; i < stack.length; i++) {
			StackTraceElement element = stack[i];
			if (!isRelevantFrameBufferBuildFrame(element)) continue;
			if (appended > 0) {
				builder.append(" <- ");
			}
			builder.append(element.getClassName());
			builder.append("#");
			builder.append(element.getMethodName());
			builder.append(":");
			builder.append(element.getLineNumber());
			appended++;
			if (appended >= GPU_RESOURCE_DIAG_FBO_STACK_DEPTH) {
				break;
			}
		}
		return appended == 0 ? null : builder.toString();
	}

	private static boolean isRelevantFrameBufferBuildFrame (StackTraceElement element) {
		if (element == null) return false;
		String className = element.getClassName();
		if (className == null) return false;
		if (className.equals(Thread.class.getName())) return false;
		if (className.equals(GLFrameBuffer.class.getName())) return false;
		if (className.startsWith("java.lang.reflect.")) return false;
		if (className.startsWith("sun.reflect.")) return false;
		if (className.startsWith("jdk.internal.reflect.")) return false;
		return true;
	}

	private static boolean containsStackFragment (String stackKey, String fragment) {
		return stackKey != null && fragment != null && stackKey.indexOf(fragment) >= 0;
	}

	private static boolean containsAnyStackFragment (String stackKey, String... fragments) {
		if (stackKey == null || stackKey.length() == 0 || fragments == null) return false;
		for (int i = 0; i < fragments.length; i++) {
			if (containsStackFragment(stackKey, fragments[i])) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsExternalModNamespace (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return false;
		String[] frames = stackKey.split(" <- ");
		for (int i = 0; i < frames.length; i++) {
			String frame = frames[i];
			int hashIndex = frame.indexOf('#');
			if (hashIndex <= 0) continue;
			String className = frame.substring(0, hashIndex);
			if (className.startsWith("com.megacrit.cardcrawl.")) continue;
			if (className.startsWith("basemod.")) continue;
			if (className.startsWith("com.badlogic.gdx.")) continue;
			if (className.startsWith("java.")) continue;
			if (className.startsWith("javax.")) continue;
			if (className.startsWith("sun.")) continue;
			if (className.startsWith("jdk.")) continue;
			if (className.startsWith("kotlin.")) continue;
			if (className.startsWith("org.lwjgl.")) continue;
			if (className.startsWith("org.apache.")) continue;
			if (className.startsWith("de.robojumper.")) continue;
			if (className.startsWith("com.esotericsoftware.")) continue;
			if (className.startsWith("io.stamethyst.")) continue;
			if (className.startsWith("com.evacipated.cardcrawl.modthespire.")) continue;
			return true;
		}
		return false;
	}
}
