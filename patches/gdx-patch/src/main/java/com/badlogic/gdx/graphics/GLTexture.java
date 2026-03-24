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

package com.badlogic.gdx.graphics;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.TextureData.TextureDataType;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.MipMapGenerator;
import com.badlogic.gdx.utils.Disposable;

/** Class representing an OpenGL texture by it's target and handle. Keeps track of its state like the TextureFilter and
 * TextureWrap. Also provides some (protected) static methods to create Texture instances.
 * @author mzechner
 * @author badlogic */
public abstract class GLTexture implements Disposable {
	private static final String FORCE_LINEAR_MIPMAP_FILTER_PROP = "amethyst.gdx.force_linear_mipmap_filter";
	private static final String FORCE_LINEAR_MIPMAP_FILTER_ENV = "AMETHYST_GDX_FORCE_LINEAR_MIPMAP_FILTER";
	private static final String GPU_RESOURCE_DIAG_ENABLED_PROP = "amethyst.gdx.gpu_resource_diag";
	private static final boolean GPU_RESOURCE_DIAG_ENABLED = readBooleanSystemProperty(GPU_RESOURCE_DIAG_ENABLED_PROP, false);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_STACKS_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_stacks";
	private static final boolean GPU_RESOURCE_DIAG_TEXTURE_STACKS_ENABLED =
		readBooleanSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_STACKS_PROP, true);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_STACK_LIMIT_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_stack_limit";
	private static final int GPU_RESOURCE_DIAG_TEXTURE_STACK_LIMIT =
		readIntSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_STACK_LIMIT_PROP, 12, 1, 128);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_STACK_DEPTH_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_stack_depth";
	private static final int GPU_RESOURCE_DIAG_TEXTURE_STACK_DEPTH =
		readIntSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_STACK_DEPTH_PROP, 8, 1, 32);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_STACK_REPEAT_INTERVAL_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_stack_repeat_interval";
	private static final int GPU_RESOURCE_DIAG_TEXTURE_STACK_REPEAT_INTERVAL =
		readIntSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_STACK_REPEAT_INTERVAL_PROP, 25, 1, 10000);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_STACK_MIN_BYTES_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_stack_min_bytes";
	private static final long GPU_RESOURCE_DIAG_TEXTURE_STACK_MIN_BYTES =
		readLongSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_STACK_MIN_BYTES_PROP, 4L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_PROP =
		"amethyst.gdx.texture_pressure_downscale";
	private static final boolean TEXTURE_PRESSURE_DOWNSCALE_ENABLED =
		readBooleanSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_PROP, true);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_MIN_BYTES_PROP =
		"amethyst.gdx.texture_pressure_downscale_min_bytes";
	private static final long TEXTURE_PRESSURE_DOWNSCALE_MIN_BYTES =
		readLongSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_MIN_BYTES_PROP, 16L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_SCENE_MIN_BYTES_PROP =
		"amethyst.gdx.texture_pressure_downscale_scene_min_bytes";
	private static final long TEXTURE_PRESSURE_DOWNSCALE_SCENE_MIN_BYTES =
		readLongSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_SCENE_MIN_BYTES_PROP, 8L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_SOFT_BYTES_PROP =
		"amethyst.gdx.texture_pressure_downscale_soft_bytes";
	private static final long TEXTURE_PRESSURE_DOWNSCALE_SOFT_BYTES =
		readLongSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_SOFT_BYTES_PROP, 192L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_HUGE_MIN_BYTES_PROP =
		"amethyst.gdx.texture_pressure_downscale_huge_min_bytes";
	private static final long TEXTURE_PRESSURE_DOWNSCALE_HUGE_MIN_BYTES =
		readLongSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_HUGE_MIN_BYTES_PROP, 32L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_DIVISOR_PROP =
		"amethyst.gdx.texture_pressure_downscale_divisor";
	private static final int TEXTURE_PRESSURE_DOWNSCALE_DIVISOR =
		readIntSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_DIVISOR_PROP, 2, 2, 4);
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE = 0;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ANIMATION = 1;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_SCENE = 2;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE = 3;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE = 4;
	private static final int GL_TEXTURE_2D_ENUM = 0x0DE1;
	private static final int GL_TEXTURE_BINDING_2D_ENUM = 0x8069;
	private static final int GL_TEXTURE_CUBE_MAP_ENUM = 0x8513;
	private static final int GL_TEXTURE_BINDING_CUBE_MAP_ENUM = 0x8514;
	private static final AtomicLong NEXT_DEBUG_TEXTURE_ID = new AtomicLong(1L);
	private static final AtomicInteger TEXTURES_CREATED = new AtomicInteger();
	private static final AtomicInteger TEXTURES_DISPOSED = new AtomicInteger();
	private static final AtomicInteger TEXTURE_HANDLE_UPDATES = new AtomicInteger();
	private static final AtomicInteger TEXTURES_LIVE = new AtomicInteger();
	private static final AtomicInteger TEXTURE_BUILD_STACK_UNIQUES = new AtomicInteger();
	private static final AtomicInteger TEXTURE_BUILD_STACK_SUPPRESSED = new AtomicInteger();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_BUILD_STACK_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<Integer, Long> TEXTURE_HANDLE_ESTIMATED_BYTES =
		new ConcurrentHashMap<Integer, Long>();
	private static final AtomicLong TEXTURE_NATIVE_ESTIMATED_BYTES = new AtomicLong();
	private static boolean forceLinearMipmapFilterLogPrinted;
	private static volatile int textureLivePeak;
	private static volatile long textureNativeEstimatedBytesPeak;
	public final int glTarget;
	protected int glHandle;
	private final long debugTextureId;
	private int debugTrackedHandle;
	protected TextureFilter minFilter = TextureFilter.Nearest;
	protected TextureFilter magFilter = TextureFilter.Nearest;
	protected TextureWrap uWrap = TextureWrap.ClampToEdge;
	protected TextureWrap vWrap = TextureWrap.ClampToEdge;

	public abstract int getWidth ();

	public abstract int getHeight ();

	public abstract int getDepth ();

	public GLTexture (int glTarget) {
		this(glTarget, Gdx.gl.glGenTexture());
	}

	public GLTexture (int glTarget, int glHandle) {
		this.glTarget = glTarget;
		this.glHandle = glHandle;
		this.debugTextureId = allocateDebugTextureId();
		this.debugTrackedHandle = glHandle;
		onTextureConstructed(debugTextureId, getClass().getName(), glTarget, glHandle);
	}

	public abstract boolean isManaged ();

	protected abstract void reload ();

	public void bind () {
		notifyBeforeTextureAccess("bind");
		syncDebugHandle("bind");
		Gdx.gl.glBindTexture(glTarget, glHandle);
	}

	public void bind (int unit) {
		notifyBeforeTextureAccess("bind_unit");
		syncDebugHandle("bind_unit");
		Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
		Gdx.gl.glBindTexture(glTarget, glHandle);
	}

	public TextureFilter getMinFilter () {
		return minFilter;
	}

	public TextureFilter getMagFilter () {
		return magFilter;
	}

	public TextureWrap getUWrap () {
		return uWrap;
	}

	public TextureWrap getVWrap () {
		return vWrap;
	}

	public int getTextureObjectHandle () {
		notifyBeforeTextureAccess("get_handle");
		syncDebugHandle("get_handle");
		return glHandle;
	}

	public final int peekTextureObjectHandle () {
		return glHandle;
	}

	public final boolean hasTextureObjectHandle () {
		return glHandle != 0;
	}

	public void unsafeSetWrap (TextureWrap u, TextureWrap v) {
		unsafeSetWrap(u, v, false);
	}

	public void unsafeSetWrap (TextureWrap u, TextureWrap v, boolean force) {
		notifyBeforeTextureAccess("unsafe_set_wrap");
		if (u != null && (force || uWrap != u)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_S, u.getGLEnum());
			uWrap = u;
		}
		if (v != null && (force || vWrap != v)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_T, v.getGLEnum());
			vWrap = v;
		}
	}

	public void setWrap (TextureWrap u, TextureWrap v) {
		notifyBeforeTextureAccess("set_wrap");
		uWrap = u;
		vWrap = v;
		bind();
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_S, u.getGLEnum());
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_WRAP_T, v.getGLEnum());
	}

	public void unsafeSetFilter (TextureFilter minFilter, TextureFilter magFilter) {
		unsafeSetFilter(minFilter, magFilter, false);
	}

	public void unsafeSetFilter (TextureFilter minFilter, TextureFilter magFilter, boolean force) {
		notifyBeforeTextureAccess("unsafe_set_filter");
		TextureFilter safeMinFilter = coerceMinFilter(minFilter);
		if (safeMinFilter != null && (force || this.minFilter != safeMinFilter)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MIN_FILTER, safeMinFilter.getGLEnum());
			this.minFilter = safeMinFilter;
		}
		if (magFilter != null && (force || this.magFilter != magFilter)) {
			Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MAG_FILTER, magFilter.getGLEnum());
			this.magFilter = magFilter;
		}
	}

	public void setFilter (TextureFilter minFilter, TextureFilter magFilter) {
		notifyBeforeTextureAccess("set_filter");
		TextureFilter safeMinFilter = coerceMinFilter(minFilter);
		this.minFilter = safeMinFilter;
		this.magFilter = magFilter;
		bind();
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MIN_FILTER, safeMinFilter.getGLEnum());
		Gdx.gl.glTexParameterf(glTarget, GL20.GL_TEXTURE_MAG_FILTER, magFilter.getGLEnum());
	}

	private static TextureFilter coerceMinFilter (TextureFilter minFilter) {
		if (minFilter == null || !minFilter.isMipMap() || !isForceLinearMipmapFilterEnabled()) {
			return minFilter;
		}
		if (!forceLinearMipmapFilterLogPrinted) {
			forceLinearMipmapFilterLogPrinted = true;
			System.out.println(
				"[gdx-patch] GLTexture mipmap-min-filter fallback enabled; coercing mipmap min filters to Linear"
			);
		}
		return TextureFilter.Linear;
	}

	private static boolean isForceLinearMipmapFilterEnabled () {
		String value = System.getProperty(FORCE_LINEAR_MIPMAP_FILTER_PROP);
		if (value == null) {
			value = System.getenv(FORCE_LINEAR_MIPMAP_FILTER_ENV);
		}
		if (value == null) {
			return true;
		}
		value = value.trim();
		return !"0".equals(value) && !"false".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value);
	}

	protected void delete () {
		releaseHandle("delete", true);
	}

	public final int releaseHandleForReuse (String reason) {
		return releaseHandle(reason, true);
	}

	public final int invalidateHandleForReuse (String reason) {
		return releaseHandle(reason, false);
	}

	public final void restoreHandleForReuse (int newHandle, String reason) {
		if (newHandle == 0) {
			throw new IllegalArgumentException("newHandle must be non-zero");
		}
		int oldHandle = glHandle;
		glHandle = newHandle;
		debugTrackedHandle = newHandle;
		if (oldHandle == 0) {
			onTextureHandleRestored(debugTextureId, newHandle, reason);
		} else {
			onTextureHandleUpdated(debugTextureId, oldHandle, newHandle, reason);
		}
	}

	private void syncDebugHandle (String reason) {
		if (debugTrackedHandle == glHandle) return;
		onTextureHandleUpdated(debugTextureId, debugTrackedHandle, glHandle, reason);
		debugTrackedHandle = glHandle;
	}

	public static String getDebugStatusSummary () {
		if (!GPU_RESOURCE_DIAG_ENABLED) return "texturesDiag=disabled";
		return "texturesLive=" + TEXTURES_LIVE.get()
			+ " texturesPeak=" + textureLivePeak
			+ " texturesCreated=" + TEXTURES_CREATED.get()
			+ " texturesDisposed=" + TEXTURES_DISPOSED.get()
			+ " textureHandleUpdates=" + TEXTURE_HANDLE_UPDATES.get()
			+ " textureBytes=" + TEXTURE_NATIVE_ESTIMATED_BYTES.get()
			+ " textureBytesPeak=" + textureNativeEstimatedBytesPeak;
	}

	@Override
	public void dispose () {
		delete();
	}

	private void notifyBeforeTextureAccess (String reason) {
		GLFrameBuffer.onExternalTextureAccess(this, reason);
	}

	private int releaseHandle (String reason, boolean deleteGlHandle) {
		if (glHandle == 0) return 0;
		int deletedHandle = glHandle;
		forgetTrackedTextureBytes(deletedHandle);
		if (deleteGlHandle) {
			Gdx.gl.glDeleteTexture(glHandle);
		}
		glHandle = 0;
		debugTrackedHandle = 0;
		onTextureDeleted(debugTextureId, deletedHandle, reason);
		return deletedHandle;
	}

	protected static void uploadImageData (int target, TextureData data) {
		uploadImageData(target, data, 0);
	}

	/** This method can be used to upload TextureData to a texture. The call must be preceded by calls to {@link GL20#glBindTexture(int, int)}
	 * and perhaps {@link GL20#glPixelStorei(int, int)} to configure how the pixel data should be interpreted. */
	public static void uploadImageData (int target, TextureData data, int miplevel) {
		if (data == null) {
			return;
		}
		if (!data.isPrepared()) {
			data.prepare();
		}
		final TextureDataType type = data.getType();
		if (type == TextureDataType.Custom) {
			data.consumeCustomData(target);
			return;
		}
		String stackKey = captureTextureUploadStackIfNeeded();
		logLargeTextureUpload(data, miplevel, stackKey);
		Pixmap pixmap = data.consumePixmap();
		boolean disposePixmap = data.disposePixmap();
		if (data.getFormat() != pixmap.getFormat()) {
			Pixmap tmp = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), data.getFormat());
			Blending blend = Pixmap.getBlending();
			Pixmap.setBlending(Blending.None);
			tmp.drawPixmap(pixmap, 0, 0, 0, 0, pixmap.getWidth(), pixmap.getHeight());
			Pixmap.setBlending(blend);
			if (data.disposePixmap()) {
				pixmap.dispose();
			}
			pixmap = tmp;
			disposePixmap = true;
		}
		Pixmap downscaledPixmap = maybePressureDownscalePixmap(pixmap, data, miplevel, stackKey);
		if (downscaledPixmap != pixmap) {
			if (disposePixmap) {
				pixmap.dispose();
			}
			pixmap = downscaledPixmap;
			disposePixmap = true;
		}
		Gdx.gl.glPixelStorei(GL20.GL_UNPACK_ALIGNMENT, 1);
		if (data.useMipMaps()) {
			MipMapGenerator.generateMipMap(target, pixmap, pixmap.getWidth(), pixmap.getHeight());
		} else {
			Gdx.gl.glTexImage2D(
				target,
				miplevel,
				pixmap.getGLInternalFormat(),
				pixmap.getWidth(),
				pixmap.getHeight(),
				0,
				pixmap.getGLFormat(),
				pixmap.getGLType(),
				pixmap.getPixels()
			);
		}
		recordTextureNativeBytes(target, pixmap.getWidth(), pixmap.getHeight(), pixmap.getFormat(), data.useMipMaps());
		if (disposePixmap) {
			pixmap.dispose();
		}
	}

	private static String captureTextureUploadStackIfNeeded () {
		if (!GPU_RESOURCE_DIAG_TEXTURE_STACKS_ENABLED && !TEXTURE_PRESSURE_DOWNSCALE_ENABLED) return null;
		return captureRelevantTextureUploadStack();
	}

	private static Pixmap maybePressureDownscalePixmap (Pixmap pixmap, TextureData data, int miplevel, String stackKey) {
		if (!TEXTURE_PRESSURE_DOWNSCALE_ENABLED || pixmap == null || miplevel != 0) return pixmap;
		int width = pixmap.getWidth();
		int height = pixmap.getHeight();
		if (width <= 0 || height <= 0) return pixmap;
		Pixmap.Format format = data == null ? pixmap.getFormat() : data.getFormat();
		long estimatedBytes = estimateTextureBytes(width, height, format);
		long liveTextureBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.get();
		long liveFrameBufferBytes = GLFrameBuffer.getEstimatedNativeBytes();
		long projectedTotalBytes = safeAdd(safeAdd(liveTextureBytes, liveFrameBufferBytes), estimatedBytes);
		int mode = classifyTexturePressureDownscaleMode(
			stackKey,
			width,
			height,
			estimatedBytes,
			projectedTotalBytes
		);
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE) return pixmap;

		long minimumBytes = mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_SCENE
			? TEXTURE_PRESSURE_DOWNSCALE_SCENE_MIN_BYTES
			: TEXTURE_PRESSURE_DOWNSCALE_MIN_BYTES;
		if (estimatedBytes < minimumBytes) return pixmap;

		int scaledWidth = Math.max(1, (width + TEXTURE_PRESSURE_DOWNSCALE_DIVISOR - 1) / TEXTURE_PRESSURE_DOWNSCALE_DIVISOR);
		int scaledHeight = Math.max(1, (height + TEXTURE_PRESSURE_DOWNSCALE_DIVISOR - 1) / TEXTURE_PRESSURE_DOWNSCALE_DIVISOR);
		if (scaledWidth == width && scaledHeight == height) return pixmap;

		Pixmap scaledPixmap = new Pixmap(scaledWidth, scaledHeight, pixmap.getFormat());
		Blending blend = Pixmap.getBlending();
		Pixmap.setBlending(Blending.None);
		scaledPixmap.drawPixmap(
			pixmap,
			0,
			0,
			width,
			height,
			0,
			0,
			scaledWidth,
			scaledHeight
		);
		Pixmap.setBlending(blend);

		System.out.println("[gdx-diag] GLTexture pressure_downscale mode=" + texturePressureDownscaleModeName(mode)
			+ " source=" + width + "x" + height
			+ " upload=" + scaledWidth + "x" + scaledHeight
			+ " format=" + String.valueOf(format)
			+ " bytes=" + estimatedBytes
			+ " liveTextureBytes=" + liveTextureBytes
			+ " liveFrameBufferBytes=" + liveFrameBufferBytes
			+ " projectedTotalBytes=" + projectedTotalBytes
			+ " stack=" + (stackKey == null ? "unknown" : stackKey));
		return scaledPixmap;
	}

	private static long allocateDebugTextureId () {
		return GPU_RESOURCE_DIAG_ENABLED ? NEXT_DEBUG_TEXTURE_ID.getAndIncrement() : 0L;
	}

	private static void onTextureConstructed (long id, String className, int target, int handle) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		TEXTURES_CREATED.incrementAndGet();
		int live = TEXTURES_LIVE.incrementAndGet();
		if (live > textureLivePeak) {
			textureLivePeak = live;
			System.out.println("[gdx-diag] GLTexture peak_live=" + live
				+ " created=" + TEXTURES_CREATED.get()
				+ " disposed=" + TEXTURES_DISPOSED.get()
				+ " class=" + className
				+ " id=" + id
				+ " handle=" + handle
				+ " target=" + target);
		}
	}

	private static void onTextureHandleUpdated (long id, int oldHandle, int newHandle, String reason) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L || oldHandle == newHandle) return;
		TEXTURE_HANDLE_UPDATES.incrementAndGet();
		System.out.println("[gdx-diag] GLTexture handle_update id=" + id
			+ " old=" + oldHandle
			+ " new=" + newHandle
			+ " reason=" + reason
			+ " liveTextures=" + TEXTURES_LIVE.get());
	}

	private static void onTextureDeleted (long id, int handle, String reason) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		TEXTURES_DISPOSED.incrementAndGet();
		int live = TEXTURES_LIVE.decrementAndGet();
		if (live < 0) {
			TEXTURES_LIVE.set(0);
			live = 0;
		}
		System.out.println("[gdx-diag] GLTexture dispose id=" + id
			+ " handle=" + handle
			+ " reason=" + reason
			+ " liveTextures=" + live);
	}

	private static void onTextureHandleRestored (long id, int handle, String reason) {
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		int live = TEXTURES_LIVE.incrementAndGet();
		if (live > textureLivePeak) {
			textureLivePeak = live;
		}
		System.out.println("[gdx-diag] GLTexture restore id=" + id
			+ " handle=" + handle
			+ " reason=" + reason
			+ " liveTextures=" + live);
	}

	private static void logLargeTextureUpload (TextureData data, int miplevel, String stackKey) {
		if (!GPU_RESOURCE_DIAG_ENABLED || !GPU_RESOURCE_DIAG_TEXTURE_STACKS_ENABLED || data == null) return;
		int width = data.getWidth();
		int height = data.getHeight();
		if (width <= 0 || height <= 0) return;
		long estimatedBytes = estimateTextureBytes(width, height, data.getFormat());
		if (estimatedBytes < GPU_RESOURCE_DIAG_TEXTURE_STACK_MIN_BYTES) return;
		if (stackKey == null) return;

		AtomicInteger existing = TEXTURE_BUILD_STACK_COUNTS.putIfAbsent(stackKey, new AtomicInteger(1));
		String format = String.valueOf(data.getFormat());
		if (existing == null) {
			int uniqueCount = TEXTURE_BUILD_STACK_UNIQUES.incrementAndGet();
			if (uniqueCount <= GPU_RESOURCE_DIAG_TEXTURE_STACK_LIMIT) {
				System.out.println("[gdx-diag] GLTexture large_upload_sample unique=" + uniqueCount
					+ " size=" + width + "x" + height
					+ " format=" + format
					+ " bytes=" + estimatedBytes
					+ " mipLevel=" + miplevel
					+ " stack=" + stackKey);
			} else {
				int suppressed = TEXTURE_BUILD_STACK_SUPPRESSED.incrementAndGet();
				if (suppressed == 1 || suppressed % GPU_RESOURCE_DIAG_TEXTURE_STACK_REPEAT_INTERVAL == 0) {
					System.out.println("[gdx-diag] GLTexture large_upload_sample_suppressed suppressed=" + suppressed
						+ " limit=" + GPU_RESOURCE_DIAG_TEXTURE_STACK_LIMIT
						+ " size=" + width + "x" + height
						+ " format=" + format
						+ " bytes=" + estimatedBytes);
				}
			}
			return;
		}

		int repeatCount = existing.incrementAndGet();
		if (repeatCount == 2 || repeatCount % GPU_RESOURCE_DIAG_TEXTURE_STACK_REPEAT_INTERVAL == 0) {
			System.out.println("[gdx-diag] GLTexture large_upload_repeat repeats=" + repeatCount
				+ " size=" + width + "x" + height
				+ " format=" + format
				+ " bytes=" + estimatedBytes
				+ " stack=" + stackKey);
		}
	}

	private static String captureRelevantTextureUploadStack () {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		StringBuilder builder = new StringBuilder(256);
		int appended = 0;
		for (int i = 0; i < stack.length; i++) {
			StackTraceElement element = stack[i];
			if (!isRelevantTextureUploadFrame(element)) continue;
			if (appended > 0) {
				builder.append(" <- ");
			}
			builder.append(element.getClassName());
			builder.append("#");
			builder.append(element.getMethodName());
			builder.append(":");
			builder.append(element.getLineNumber());
			appended++;
			if (appended >= GPU_RESOURCE_DIAG_TEXTURE_STACK_DEPTH) {
				break;
			}
		}
		return appended == 0 ? null : builder.toString();
	}

	private static boolean isRelevantTextureUploadFrame (StackTraceElement element) {
		if (element == null) return false;
		String className = element.getClassName();
		if (className == null) return false;
		if (className.equals(Thread.class.getName())) return false;
		if (className.equals(GLTexture.class.getName())) return false;
		if (className.equals(Texture.class.getName())) return false;
		if (className.startsWith("java.lang.reflect.")) return false;
		if (className.startsWith("sun.reflect.")) return false;
		if (className.startsWith("jdk.internal.reflect.")) return false;
		return true;
	}

	private static int classifyTexturePressureDownscaleMode (
		String stackKey,
		int width,
		int height,
		long estimatedBytes,
		long projectedTotalBytes
	) {
		if (estimatedBytes <= 0L) return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;
		if (isTexturePressureDownscaleExemptStack(stackKey)) return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;

		boolean externalMod = containsExternalModNamespace(stackKey);
		boolean animationLike = containsAnyStackFragment(
			stackKey,
			".skins.AbstractSkin#loadAnimation",
			".skins.AbstractSkinBase#loadAnimation",
			"#loadAnimation",
			"#reloadAnimation",
			"#preload",
			"#replace"
		);
		boolean sceneLike = containsAnyStackFragment(
			stackKey,
			".cutscenes.",
			".relics.",
			".events."
		);
		boolean largeTexture = width >= 2048 || height >= 2048;
		boolean hugeTexture = estimatedBytes >= TEXTURE_PRESSURE_DOWNSCALE_HUGE_MIN_BYTES;
		boolean pressureExceeded = projectedTotalBytes >= TEXTURE_PRESSURE_DOWNSCALE_SOFT_BYTES;

		if (externalMod && animationLike && largeTexture && estimatedBytes >= TEXTURE_PRESSURE_DOWNSCALE_MIN_BYTES) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ANIMATION;
		}
		if (externalMod && sceneLike && largeTexture && estimatedBytes >= TEXTURE_PRESSURE_DOWNSCALE_SCENE_MIN_BYTES) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_SCENE;
		}
		if (pressureExceeded && externalMod && largeTexture && estimatedBytes >= TEXTURE_PRESSURE_DOWNSCALE_MIN_BYTES) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE;
		}
		if (pressureExceeded && largeTexture && hugeTexture) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE;
		}
		return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;
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

	private static boolean isTexturePressureDownscaleExemptStack (String stackKey) {
		return containsAnyStackFragment(
			stackKey,
			"com.badlogic.gdx.graphics.g2d.PixmapPacker",
			"com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator",
			"com.megacrit.cardcrawl.helpers.FontHelper"
		);
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
			return true;
		}
		return false;
	}

	private static String texturePressureDownscaleModeName (int mode) {
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ANIMATION) return "external_animation";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_SCENE) return "external_scene";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE) return "external_pressure";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE) return "generic_pressure";
		return "none";
	}

	private static void recordTextureNativeBytes (
		int target,
		int width,
		int height,
		Pixmap.Format format,
		boolean useMipMaps
	) {
		int handle = getCurrentTextureBinding(target);
		if (handle == 0) return;
		long estimatedBytes = estimateTextureBytes(width, height, format);
		if (useMipMaps) {
			estimatedBytes = Math.max(estimatedBytes, (estimatedBytes * 4L) / 3L);
		}
		Long previousBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.put(handle, estimatedBytes);
		long delta = estimatedBytes - (previousBytes == null ? 0L : previousBytes.longValue());
		if (delta == 0L) return;
		long liveBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.addAndGet(delta);
		if (liveBytes < 0L) {
			TEXTURE_NATIVE_ESTIMATED_BYTES.set(0L);
			liveBytes = 0L;
		}
		if (liveBytes > textureNativeEstimatedBytesPeak) {
			textureNativeEstimatedBytesPeak = liveBytes;
		}
	}

	private static void forgetTrackedTextureBytes (int handle) {
		if (handle == 0) return;
		Long previousBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.remove(handle);
		if (previousBytes == null) return;
		long liveBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.addAndGet(-previousBytes.longValue());
		if (liveBytes < 0L) {
			TEXTURE_NATIVE_ESTIMATED_BYTES.set(0L);
		}
	}

	private static int getCurrentTextureBinding (int target) {
		int bindingQuery = getTextureBindingQueryEnum(target);
		if (bindingQuery == 0) return 0;
		IntBuffer intbuf = ByteBuffer.allocateDirect(Integer.SIZE / 8).order(ByteOrder.nativeOrder()).asIntBuffer();
		Gdx.gl.glGetIntegerv(bindingQuery, intbuf);
		return intbuf.get(0);
	}

	private static int getTextureBindingQueryEnum (int target) {
		if (target == GL_TEXTURE_2D_ENUM) return GL_TEXTURE_BINDING_2D_ENUM;
		if (target == GL_TEXTURE_CUBE_MAP_ENUM) return GL_TEXTURE_BINDING_CUBE_MAP_ENUM;
		return 0;
	}

	private static long safeAdd (long a, long b) {
		if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
		if (b < 0L && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
		return a + b;
	}

	private static long estimateTextureBytes (int width, int height, Pixmap.Format format) {
		long pixels = Math.max(0L, (long)width * (long)height);
		return pixels * estimateBytesPerPixel(format);
	}

	private static long estimateBytesPerPixel (Pixmap.Format format) {
		if (format == null) return 4L;
		if (format == Pixmap.Format.RGBA8888) return 4L;
		if (format == Pixmap.Format.RGB888) return 3L;
		if (format == Pixmap.Format.RGB565
			|| format == Pixmap.Format.RGBA4444
			|| format == Pixmap.Format.LuminanceAlpha) {
			return 2L;
		}
		if (format == Pixmap.Format.Alpha
			|| format == Pixmap.Format.Intensity) {
			return 1L;
		}
		return 4L;
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

}
