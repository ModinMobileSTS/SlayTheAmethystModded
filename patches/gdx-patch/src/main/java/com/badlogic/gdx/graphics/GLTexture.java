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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.TextureData.TextureDataType;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
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
	private static final String GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACKS_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_construct_stacks";
	private static final boolean GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACKS_ENABLED =
		readBooleanSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACKS_PROP, true);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIMIT_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_construct_stack_limit";
	private static final int GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIMIT =
		readIntSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIMIT_PROP, 16, 1, 256);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_REPEAT_INTERVAL_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_construct_stack_repeat_interval";
	private static final int GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_REPEAT_INTERVAL =
		readIntSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_REPEAT_INTERVAL_PROP, 25, 1, 10000);
	private static final String GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIVE_THRESHOLD_PROP =
		"amethyst.gdx.gpu_resource_diag.texture_construct_stack_live_threshold";
	private static final int GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIVE_THRESHOLD =
		readIntSystemProperty(GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIVE_THRESHOLD_PROP, 3000, 1, 1000000);
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
	private static final String TEXTURE_PRESSURE_DOWNSCALE_EXTERNAL_MIN_BYTES_PROP =
		"amethyst.gdx.texture_pressure_downscale_external_min_bytes";
	private static final long TEXTURE_PRESSURE_DOWNSCALE_EXTERNAL_MIN_BYTES =
		readLongSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_EXTERNAL_MIN_BYTES_PROP, 8L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final String TEXTURE_PRESSURE_DOWNSCALE_ART_MIN_BYTES_PROP =
		"amethyst.gdx.texture_pressure_downscale_art_min_bytes";
	private static final long TEXTURE_PRESSURE_DOWNSCALE_ART_MIN_BYTES =
		readLongSystemProperty(TEXTURE_PRESSURE_DOWNSCALE_ART_MIN_BYTES_PROP, 4L * 1024L * 1024L, 0L, Long.MAX_VALUE);
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
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_FILE = 1;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ART = 2;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_ART_PRESSURE = 3;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE = 4;
	private static final int TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE = 5;
	private static final int TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT = 4;
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
	private static final AtomicInteger TEXTURE_CONSTRUCT_STACK_UNIQUES = new AtomicInteger();
	private static final AtomicInteger TEXTURE_CONSTRUCT_STACK_SUPPRESSED = new AtomicInteger();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_BUILD_STACK_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_CONSTRUCT_STACK_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<Integer, Long> TEXTURE_HANDLE_ESTIMATED_BYTES =
		new ConcurrentHashMap<Integer, Long>();
	private static final ConcurrentHashMap<Integer, String> TEXTURE_HANDLE_LIVE_GROUPS =
		new ConcurrentHashMap<Integer, String>();
	private static final ConcurrentHashMap<Integer, String> TEXTURE_HANDLE_LIVE_SAMPLES =
		new ConcurrentHashMap<Integer, String>();
	private static final ConcurrentHashMap<Integer, String> TEXTURE_HANDLE_LIVE_OWNER_KEYS =
		new ConcurrentHashMap<Integer, String>();
	private static final ConcurrentHashMap<String, AtomicLong> TEXTURE_LIVE_SOURCE_BYTES =
		new ConcurrentHashMap<String, AtomicLong>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_LIVE_SOURCE_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, String> TEXTURE_LIVE_SOURCE_SAMPLES =
		new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, AtomicLong> TEXTURE_LIVE_OWNER_BYTES =
		new ConcurrentHashMap<String, AtomicLong>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_LIVE_OWNER_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, String> TEXTURE_LIVE_OWNER_SAMPLES =
		new ConcurrentHashMap<String, String>();
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
		return "texturesDiag=" + (GPU_RESOURCE_DIAG_ENABLED ? "enabled" : "disabled")
			+ " texturesLive=" + TEXTURES_LIVE.get()
			+ " texturesPeak=" + textureLivePeak
			+ " texturesCreated=" + TEXTURES_CREATED.get()
			+ " texturesDisposed=" + TEXTURES_DISPOSED.get()
			+ " textureHandleUpdates=" + TEXTURE_HANDLE_UPDATES.get()
			+ " textureBytes=" + TEXTURE_NATIVE_ESTIMATED_BYTES.get()
			+ " textureBytesPeak=" + textureNativeEstimatedBytesPeak;
	}

	public static String getLiveSourceSummary () {
		return buildLiveTextureSummary(
			"textureLiveTop=",
			TEXTURE_LIVE_SOURCE_BYTES,
			TEXTURE_LIVE_SOURCE_COUNTS,
			TEXTURE_LIVE_SOURCE_SAMPLES
		);
	}

	public static String getLiveOwnerSummary () {
		return buildLiveTextureSummary(
			"textureOwnerTop=",
			TEXTURE_LIVE_OWNER_BYTES,
			TEXTURE_LIVE_OWNER_COUNTS,
			TEXTURE_LIVE_OWNER_SAMPLES
		);
	}

	private static String buildLiveTextureSummary (
		String label,
		ConcurrentHashMap<String, AtomicLong> bytesByKey,
		ConcurrentHashMap<String, AtomicInteger> countsByKey,
		ConcurrentHashMap<String, String> samplesByKey
	) {
		String[] topGroups = new String[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		String[] topSamples = new String[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		long[] topBytes = new long[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		int[] topCounts = new int[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		int topSize = 0;
		int totalGroups = 0;
		for (Map.Entry<String, AtomicLong> entry : bytesByKey.entrySet()) {
			AtomicLong bytesRef = entry.getValue();
			if (bytesRef == null) continue;
			long bytes = bytesRef.get();
			if (bytes <= 0L) continue;
			String groupKey = entry.getKey();
			AtomicInteger countRef = countsByKey.get(groupKey);
			int count = countRef == null ? 0 : countRef.get();
			if (count <= 0) continue;
			totalGroups++;
			int insertAt = -1;
			for (int i = 0; i < topSize; i++) {
				if (ranksBeforeLiveTextureSummary(bytes, count, groupKey, topBytes[i], topCounts[i], topGroups[i])) {
					insertAt = i;
					break;
				}
			}
			if (insertAt < 0 && topSize < TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT) {
				insertAt = topSize;
			}
			if (insertAt < 0) continue;
			int moveEnd = topSize < TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT ? topSize : TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT - 1;
			for (int i = moveEnd; i > insertAt; i--) {
				topGroups[i] = topGroups[i - 1];
				topSamples[i] = topSamples[i - 1];
				topBytes[i] = topBytes[i - 1];
				topCounts[i] = topCounts[i - 1];
			}
			topGroups[insertAt] = groupKey;
			topSamples[insertAt] = samplesByKey.get(groupKey);
			topBytes[insertAt] = bytes;
			topCounts[insertAt] = count;
			if (topSize < TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT) {
				topSize++;
			}
		}
		if (topSize == 0) return label + "none";
		StringBuilder builder = new StringBuilder(256);
		builder.append(label);
		for (int i = 0; i < topSize; i++) {
			if (i > 0) builder.append("|");
			builder.append(topGroups[i])
				.append(":")
				.append(toSummaryMegabytes(topBytes[i]))
				.append("m/")
				.append(topCounts[i]);
			if (topSamples[i] != null && topSamples[i].length() > 0) {
				builder.append("@").append(topSamples[i]);
			}
		}
		if (totalGroups > TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT) {
			builder.append("|...");
		}
		return builder.toString();
	}

	public static long getEstimatedNativeBytes () {
		return TEXTURE_NATIVE_ESTIMATED_BYTES.get();
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
		String sourcePath = resolveTextureSourcePath(data);
		logLargeTextureUpload(data, miplevel, stackKey, sourcePath);
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
		Pixmap downscaledPixmap = maybePressureDownscalePixmap(pixmap, data, miplevel, stackKey, sourcePath);
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
		recordTextureNativeBytes(
			target,
			pixmap.getWidth(),
			pixmap.getHeight(),
			pixmap.getFormat(),
			data.useMipMaps(),
			sourcePath,
			stackKey
		);
		if (disposePixmap) {
			pixmap.dispose();
		}
	}

	private static String captureTextureUploadStackIfNeeded () {
		if (!GPU_RESOURCE_DIAG_TEXTURE_STACKS_ENABLED && !TEXTURE_PRESSURE_DOWNSCALE_ENABLED) return null;
		return captureRelevantTextureUploadStack();
	}

	private static Pixmap maybePressureDownscalePixmap (
		Pixmap pixmap,
		TextureData data,
		int miplevel,
		String stackKey,
		String sourcePath
	) {
		if (!TEXTURE_PRESSURE_DOWNSCALE_ENABLED || pixmap == null || miplevel != 0) return pixmap;
		int width = pixmap.getWidth();
		int height = pixmap.getHeight();
		if (width <= 0 || height <= 0) return pixmap;
		Pixmap.Format format = data == null ? pixmap.getFormat() : data.getFormat();
		long estimatedBytes = estimateTextureBytes(width, height, format);
		long liveTextureBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.get();
		long liveFrameBufferBytes = GLFrameBuffer.getEstimatedNativeBytes();
		long projectedTotalBytes = safeAdd(safeAdd(liveTextureBytes, liveFrameBufferBytes), estimatedBytes);
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		int mode = classifyTexturePressureDownscaleMode(
			stackKey,
			normalizedSourcePath,
			width,
			height,
			estimatedBytes,
			projectedTotalBytes
		);
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE) return pixmap;
		long minimumBytes = texturePressureDownscaleMinimumBytes(mode);
		if (estimatedBytes < minimumBytes) return pixmap;
		String reason = resolveTexturePressureDownscaleReason(mode, stackKey, normalizedSourcePath);

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
			+ " minimumBytes=" + minimumBytes
			+ " liveTextureBytes=" + liveTextureBytes
			+ " liveFrameBufferBytes=" + liveFrameBufferBytes
			+ " projectedTotalBytes=" + projectedTotalBytes
			+ " reason=" + reason
			+ " path=" + (sourcePath == null ? "unknown" : sourcePath)
			+ " stack=" + (stackKey == null ? "unknown" : stackKey));
		return scaledPixmap;
	}

	private static long allocateDebugTextureId () {
		return GPU_RESOURCE_DIAG_ENABLED ? NEXT_DEBUG_TEXTURE_ID.getAndIncrement() : 0L;
	}

	private static void onTextureConstructed (long id, String className, int target, int handle) {
		int created = TEXTURES_CREATED.incrementAndGet();
		int live = TEXTURES_LIVE.incrementAndGet();
		boolean newPeak = false;
		if (live > textureLivePeak) {
			textureLivePeak = live;
			newPeak = true;
		}
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		if (newPeak) {
			System.out.println("[gdx-diag] GLTexture peak_live=" + live
				+ " created=" + created
				+ " disposed=" + TEXTURES_DISPOSED.get()
				+ " class=" + className
				+ " id=" + id
				+ " handle=" + handle
				+ " target=" + target);
		}
		logTextureConstructStack(id, className, target, handle, live);
	}

	private static void onTextureHandleUpdated (long id, int oldHandle, int newHandle, String reason) {
		if (oldHandle == newHandle) return;
		TEXTURE_HANDLE_UPDATES.incrementAndGet();
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		System.out.println("[gdx-diag] GLTexture handle_update id=" + id
			+ " old=" + oldHandle
			+ " new=" + newHandle
			+ " reason=" + reason
			+ " liveTextures=" + TEXTURES_LIVE.get());
	}

	private static void onTextureDeleted (long id, int handle, String reason) {
		TEXTURES_DISPOSED.incrementAndGet();
		int live = TEXTURES_LIVE.decrementAndGet();
		if (live < 0) {
			TEXTURES_LIVE.set(0);
			live = 0;
		}
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		System.out.println("[gdx-diag] GLTexture dispose id=" + id
			+ " handle=" + handle
			+ " reason=" + reason
			+ " liveTextures=" + live);
	}

	private static void onTextureHandleRestored (long id, int handle, String reason) {
		int live = TEXTURES_LIVE.incrementAndGet();
		if (live > textureLivePeak) {
			textureLivePeak = live;
		}
		if (!GPU_RESOURCE_DIAG_ENABLED || id == 0L) return;
		System.out.println("[gdx-diag] GLTexture restore id=" + id
			+ " handle=" + handle
			+ " reason=" + reason
			+ " liveTextures=" + live);
	}

	private static void logLargeTextureUpload (TextureData data, int miplevel, String stackKey, String sourcePath) {
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
					+ " path=" + (sourcePath == null ? "unknown" : sourcePath)
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
				+ " path=" + (sourcePath == null ? "unknown" : sourcePath)
				+ " stack=" + stackKey);
		}
	}

	private static void logTextureConstructStack (long id, String className, int target, int handle, int live) {
		if (!GPU_RESOURCE_DIAG_ENABLED || !GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACKS_ENABLED) return;
		if (live < GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIVE_THRESHOLD) return;
		String stackKey = captureRelevantTextureConstructStack();
		if (stackKey == null) return;

		AtomicInteger existing = TEXTURE_CONSTRUCT_STACK_COUNTS.putIfAbsent(stackKey, new AtomicInteger(1));
		if (existing == null) {
			int uniqueCount = TEXTURE_CONSTRUCT_STACK_UNIQUES.incrementAndGet();
			if (uniqueCount <= GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIMIT) {
				System.out.println("[gdx-diag] GLTexture construct_sample unique=" + uniqueCount
					+ " live=" + live
					+ " created=" + TEXTURES_CREATED.get()
					+ " disposed=" + TEXTURES_DISPOSED.get()
					+ " class=" + className
					+ " id=" + id
					+ " handle=" + handle
					+ " target=" + target
					+ " stack=" + stackKey);
			} else {
				int suppressed = TEXTURE_CONSTRUCT_STACK_SUPPRESSED.incrementAndGet();
				if (suppressed == 1 || suppressed % GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_REPEAT_INTERVAL == 0) {
					System.out.println("[gdx-diag] GLTexture construct_sample_suppressed suppressed=" + suppressed
						+ " limit=" + GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_LIMIT
						+ " live=" + live
						+ " class=" + className
						+ " target=" + target);
				}
			}
			return;
		}

		int repeatCount = existing.incrementAndGet();
		if (repeatCount == 2 || repeatCount % GPU_RESOURCE_DIAG_TEXTURE_CONSTRUCT_STACK_REPEAT_INTERVAL == 0) {
			System.out.println("[gdx-diag] GLTexture construct_repeat repeats=" + repeatCount
				+ " live=" + live
				+ " class=" + className
				+ " id=" + id
				+ " handle=" + handle
				+ " target=" + target
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

	private static String captureRelevantTextureConstructStack () {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		StringBuilder builder = new StringBuilder(256);
		int appended = 0;
		for (int i = 0; i < stack.length; i++) {
			StackTraceElement element = stack[i];
			if (!isRelevantTextureConstructFrame(element)) continue;
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

	private static boolean isRelevantTextureConstructFrame (StackTraceElement element) {
		if (element == null) return false;
		String className = element.getClassName();
		if (className == null) return false;
		if (className.equals(Thread.class.getName())) return false;
		if (className.equals(GLTexture.class.getName())) return false;
		if (className.equals(Texture.class.getName())) return false;
		if (className.startsWith("java.lang.reflect.")) return false;
		if (className.startsWith("sun.reflect.")) return false;
		if (className.startsWith("jdk.internal.reflect.")) return false;
		if (className.startsWith("dalvik.system.")) return false;
		if (className.startsWith("java.lang.Thread")) return false;
		return true;
	}

	private static int classifyTexturePressureDownscaleMode (
		String stackKey,
		String sourcePath,
		int width,
		int height,
		long estimatedBytes,
		long projectedTotalBytes
	) {
		if (estimatedBytes <= 0L) return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;
		if (isTexturePressureDownscaleExemptStack(stackKey)) return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;

		boolean fileBacked = sourcePath != null && sourcePath.length() > 0;
		boolean externalMod = containsExternalModNamespace(stackKey);
		boolean modStoragePath = isModStorageTexturePath(sourcePath);
		boolean modOwnedSource = externalMod || modStoragePath;
		boolean atlasLike = isAtlasTextureSource(stackKey);
		boolean portraitLike = isPortraitLikeTextureSource(stackKey, sourcePath);
		boolean cardArtLike = isCardArtLikeTextureSource(stackKey, sourcePath);
		boolean artLike = portraitLike || cardArtLike;
		boolean largeTexture = width >= 2048 || height >= 2048;
		boolean mediumTexture = width >= 1024 || height >= 1024;
		boolean hugeTexture = estimatedBytes >= TEXTURE_PRESSURE_DOWNSCALE_HUGE_MIN_BYTES;
		boolean pressureExceeded = projectedTotalBytes >= TEXTURE_PRESSURE_DOWNSCALE_SOFT_BYTES;

		if (atlasLike) return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;
		if (modOwnedSource && fileBacked && artLike && mediumTexture) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ART;
		}
		if (modOwnedSource && fileBacked && largeTexture) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_FILE;
		}
		if (pressureExceeded && fileBacked && artLike && mediumTexture) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_ART_PRESSURE;
		}
		if (pressureExceeded && modOwnedSource && fileBacked && (largeTexture || mediumTexture)) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE;
		}
		if (pressureExceeded && largeTexture && hugeTexture) {
			return TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE;
		}
		return TEXTURE_PRESSURE_DOWNSCALE_MODE_NONE;
	}

	private static long texturePressureDownscaleMinimumBytes (int mode) {
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ART
			|| mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_ART_PRESSURE) {
			return TEXTURE_PRESSURE_DOWNSCALE_ART_MIN_BYTES;
		}
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_FILE
			|| mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE) {
			return TEXTURE_PRESSURE_DOWNSCALE_EXTERNAL_MIN_BYTES;
		}
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE) {
			return TEXTURE_PRESSURE_DOWNSCALE_HUGE_MIN_BYTES;
		}
		return Long.MAX_VALUE;
	}

	private static String resolveTexturePressureDownscaleReason (int mode, String stackKey, String sourcePath) {
		boolean portraitLike = isPortraitLikeTextureSource(stackKey, sourcePath);
		boolean modStoragePath = isModStorageTexturePath(sourcePath);
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ART) {
			return portraitLike ? "mod_runtime_portrait" : "mod_runtime_card_art";
		}
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_FILE) {
			return modStoragePath ? "mod_storage_large_file" : "external_stack_large_file";
		}
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_ART_PRESSURE) {
			return portraitLike ? "portrait_pressure" : "card_art_pressure";
		}
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE) {
			return modStoragePath ? "mod_runtime_file_pressure" : "external_stack_pressure";
		}
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE) {
			return "generic_pressure";
		}
		return "unspecified";
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
			"com.badlogic.gdx.graphics.g2d.TextureAtlas",
			"com.badlogic.gdx.graphics.g2d.PixmapPacker",
			"com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator",
			"com.megacrit.cardcrawl.helpers.FontHelper"
		);
	}

	private static boolean isAtlasTextureSource (String stackKey) {
		return containsAnyStackFragment(
			stackKey,
			"com.badlogic.gdx.graphics.g2d.TextureAtlas",
			"com.esotericsoftware.spine",
			".spine."
		);
	}

	private static String resolveTextureSourcePath (TextureData data) {
		if (!(data instanceof FileTextureData)) return null;
		FileHandle fileHandle = ((FileTextureData)data).getFileHandle();
		if (fileHandle == null) return null;
		String path = fileHandle.path();
		if (path == null) return null;
		path = path.trim();
		if (path.length() == 0) return null;
		return path.replace('\\', '/');
	}

	private static String normalizeTextureSourcePath (String sourcePath) {
		if (sourcePath == null) return null;
		String normalized = sourcePath.replace('\\', '/').trim().toLowerCase();
		return normalized.length() == 0 ? null : normalized;
	}

	private static String classifyLiveTextureSourceGroup (String sourcePath, String stackKey) {
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		if (normalizedSourcePath != null) {
			String archiveGroup = extractLiveTextureArchiveGroup(normalizedSourcePath);
			if (archiveGroup != null) return archiveGroup;
			String pathGroup = extractLiveTexturePathGroup(normalizedSourcePath);
			if (pathGroup != null) return pathGroup;
		}
		String namespaceGroup = extractExternalNamespaceGroup(stackKey);
		return namespaceGroup == null ? "unknown" : namespaceGroup;
	}

	private static String extractLiveTextureArchiveGroup (String normalizedSourcePath) {
		int bangIndex = normalizedSourcePath.indexOf("!/");
		if (bangIndex < 0) return null;
		String archivePath = normalizedSourcePath.substring(0, bangIndex);
		int slashIndex = archivePath.lastIndexOf('/');
		String archiveName = slashIndex >= 0 ? archivePath.substring(slashIndex + 1) : archivePath;
		return archiveName.length() == 0 ? null : archiveName;
	}

	private static String extractLiveTexturePathGroup (String normalizedSourcePath) {
		int start = 0;
		if (normalizedSourcePath.length() >= 3
			&& normalizedSourcePath.charAt(1) == ':'
			&& normalizedSourcePath.charAt(2) == '/') {
			start = 3;
		}
		while (start < normalizedSourcePath.length() && normalizedSourcePath.charAt(start) == '/') {
			start++;
		}
		if (start >= normalizedSourcePath.length()) return null;
		int end = normalizedSourcePath.indexOf('/', start);
		String firstSegment =
			end >= 0 ? normalizedSourcePath.substring(start, end) : normalizedSourcePath.substring(start);
		if (firstSegment.length() == 0) return null;
		if ("android_asset".equals(firstSegment) && end >= 0 && end + 1 < normalizedSourcePath.length()) {
			int secondEnd = normalizedSourcePath.indexOf('/', end + 1);
			String secondSegment =
				secondEnd >= 0
					? normalizedSourcePath.substring(end + 1, secondEnd)
					: normalizedSourcePath.substring(end + 1);
			if (secondSegment.length() > 0) return secondSegment;
		}
		return firstSegment;
	}

	private static String extractExternalNamespaceGroup (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return null;
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
			int packageSeparator = className.indexOf('.');
			if (packageSeparator > 0) {
				return className.substring(0, packageSeparator).toLowerCase();
			}
			return className.toLowerCase();
		}
		return null;
	}

	private static String summarizeLiveTextureSampleSource (String sourcePath, String stackKey, String groupKey) {
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		if (normalizedSourcePath != null) {
			return abbreviateLiveTextureSource(normalizedSourcePath);
		}
		String namespaceGroup = extractExternalNamespaceGroup(stackKey);
		return namespaceGroup == null ? groupKey : namespaceGroup;
	}

	private static String abbreviateLiveTextureSource (String normalizedSourcePath) {
		if (normalizedSourcePath == null || normalizedSourcePath.length() == 0) return null;
		if (normalizedSourcePath.length() <= 72) return normalizedSourcePath;
		int lastSlash = normalizedSourcePath.lastIndexOf('/');
		if (lastSlash >= 0 && lastSlash + 1 < normalizedSourcePath.length()) {
			String fileName = normalizedSourcePath.substring(lastSlash + 1);
			int parentSlash = normalizedSourcePath.lastIndexOf('/', lastSlash - 1);
			if (parentSlash >= 0 && parentSlash + 1 < lastSlash) {
				String parentName = normalizedSourcePath.substring(parentSlash + 1, lastSlash);
				String condensed = parentName + "/" + fileName;
				if (condensed.length() <= 72) return condensed;
			}
			if (fileName.length() <= 72) return fileName;
		}
		return normalizedSourcePath.substring(normalizedSourcePath.length() - 72);
	}

	private static String classifyLiveTextureOwnerKey (String groupKey, String sourcePath, String stackKey) {
		String safeGroupKey = groupKey == null || groupKey.length() == 0 ? "unknown" : groupKey;
		if (isDownfallTextureAttribution(safeGroupKey, sourcePath, stackKey)) {
			return "downfall<-" + safeGroupKey;
		}
		if (containsAnyStackFragment(
			stackKey,
			"com.evacipated.cardcrawl.mod.stslib.",
			"stslib."
		)) {
			return "stslib<-" + safeGroupKey;
		}
		if (containsAnyStackFragment(
			stackKey,
			"com.evacipated.cardcrawl.modthespire."
		)) {
			return "modthespire<-" + safeGroupKey;
		}
		if (extractExternalNamespaceGroup(stackKey) != null) {
			return "othermod<-" + safeGroupKey;
		}
		if (containsAnyStackFragment(stackKey, "basemod.")) {
			return "basemod<-" + safeGroupKey;
		}
		return "core<-" + safeGroupKey;
	}

	private static boolean isDownfallTextureAttribution (String groupKey, String sourcePath, String stackKey) {
		if ("downfallresources".equals(groupKey)) {
			return true;
		}
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		if (containsAnyPathFragment(normalizedSourcePath, "downfallresources/")) {
			return true;
		}
		return containsAnyStackFragment(stackKey, "downfall.", "downfall/");
	}

	private static boolean containsPathFragment (String sourcePath, String fragment) {
		return sourcePath != null && fragment != null && sourcePath.indexOf(fragment) >= 0;
	}

	private static boolean containsAnyPathFragment (String sourcePath, String... fragments) {
		if (sourcePath == null || sourcePath.length() == 0 || fragments == null) return false;
		for (int i = 0; i < fragments.length; i++) {
			if (containsPathFragment(sourcePath, fragments[i])) {
				return true;
			}
		}
		return false;
	}

	private static boolean isModStorageTexturePath (String sourcePath) {
		return containsAnyPathFragment(
			sourcePath,
			"/files/sts/mods/",
			"/files/sts/mods_library/",
			"/mods/",
			"/mods_library/"
		);
	}

	private static boolean isPortraitLikeTextureSource (String stackKey, String sourcePath) {
		if (containsAnyPathFragment(
			sourcePath,
			"/1024portraits/",
			"/1024portraitsbeta/",
			"/512portraits/",
			"/512portraitsbeta/",
			"/portraits/",
			"/portrait/"
		)) {
			return true;
		}
		return containsAnyStackFragment(
			stackKey,
			"com.megacrit.cardcrawl.screens.SingleCardViewPopup",
			"#loadPortraitImg",
			"#getPortraitImage"
		);
	}

	private static boolean isCardArtLikeTextureSource (String stackKey, String sourcePath) {
		if (containsAnyPathFragment(
			sourcePath,
			"/cards/",
			"/cardsbeta/",
			"/cards_beta/",
			"/cardimages/",
			"/cardimage/",
			"/cardimg/",
			"/card_art/"
		)) {
			return true;
		}
		return containsAnyStackFragment(
			stackKey,
			"#loadCardImage",
			"basemod.abstracts.CustomCard",
			"com.megacrit.cardcrawl.cards."
		);
	}

	private static boolean containsExternalModNamespace (String stackKey) {
		return extractExternalNamespaceGroup(stackKey) != null;
	}

	private static boolean ranksBeforeLiveTextureSummary (
		long candidateBytes,
		int candidateCount,
		String candidateGroup,
		long existingBytes,
		int existingCount,
		String existingGroup
	) {
		if (candidateBytes != existingBytes) return candidateBytes > existingBytes;
		if (candidateCount != existingCount) return candidateCount > existingCount;
		if (existingGroup == null) return true;
		if (candidateGroup == null) return false;
		return candidateGroup.compareTo(existingGroup) < 0;
	}

	private static String texturePressureDownscaleModeName (int mode) {
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_FILE) return "external_file";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_ART) return "external_art";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_ART_PRESSURE) return "art_pressure";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_EXTERNAL_PRESSURE) return "external_pressure";
		if (mode == TEXTURE_PRESSURE_DOWNSCALE_MODE_GENERIC_PRESSURE) return "generic_pressure";
		return "none";
	}

	private static void recordTextureNativeBytes (
		int target,
		int width,
		int height,
		Pixmap.Format format,
		boolean useMipMaps,
		String sourcePath,
		String stackKey
	) {
		int handle = getCurrentTextureBinding(target);
		if (handle == 0) return;
		long estimatedBytes = estimateTextureBytes(width, height, format);
		if (useMipMaps) {
			estimatedBytes = Math.max(estimatedBytes, (estimatedBytes * 4L) / 3L);
		}
		updateLiveTextureSourceAttribution(handle, estimatedBytes, sourcePath, stackKey);
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
		String previousGroup = TEXTURE_HANDLE_LIVE_GROUPS.remove(handle);
		String previousSample = TEXTURE_HANDLE_LIVE_SAMPLES.remove(handle);
		String previousOwnerKey = TEXTURE_HANDLE_LIVE_OWNER_KEYS.remove(handle);
		if (previousGroup != null) {
			adjustLiveTextureAggregate(
				previousGroup,
				previousSample,
				-(previousBytes == null ? 0L : previousBytes.longValue()),
				-1
			);
		}
		if (previousOwnerKey != null) {
			adjustLiveTextureOwnerAggregate(
				previousOwnerKey,
				previousSample,
				-(previousBytes == null ? 0L : previousBytes.longValue()),
				-1
			);
		}
		if (previousBytes == null) return;
		long liveBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.addAndGet(-previousBytes.longValue());
		if (liveBytes < 0L) {
			TEXTURE_NATIVE_ESTIMATED_BYTES.set(0L);
		}
	}

	private static void updateLiveTextureSourceAttribution (
		int handle,
		long estimatedBytes,
		String sourcePath,
		String stackKey
	) {
		String groupKey = classifyLiveTextureSourceGroup(sourcePath, stackKey);
		String sampleSource = summarizeLiveTextureSampleSource(sourcePath, stackKey, groupKey);
		String ownerKey = classifyLiveTextureOwnerKey(groupKey, sourcePath, stackKey);
		Long previousBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.get(handle);
		String previousGroup = TEXTURE_HANDLE_LIVE_GROUPS.put(handle, groupKey);
		String previousSample = TEXTURE_HANDLE_LIVE_SAMPLES.put(handle, sampleSource);
		String previousOwnerKey = TEXTURE_HANDLE_LIVE_OWNER_KEYS.put(handle, ownerKey);
		long safePreviousBytes = previousBytes == null ? 0L : previousBytes.longValue();
		if (previousGroup != null && groupKey.equals(previousGroup)) {
			adjustLiveTextureAggregate(
				groupKey,
				sampleSource,
				estimatedBytes - safePreviousBytes,
				0
			);
		} else {
			if (previousGroup != null) {
				adjustLiveTextureAggregate(
					previousGroup,
					previousSample,
					-safePreviousBytes,
					-1
				);
			}
			adjustLiveTextureAggregate(
				groupKey,
				sampleSource,
				estimatedBytes,
				1
			);
		}
		if (previousOwnerKey != null && ownerKey.equals(previousOwnerKey)) {
			adjustLiveTextureOwnerAggregate(
				ownerKey,
				sampleSource,
				estimatedBytes - safePreviousBytes,
				0
			);
			return;
		}
		if (previousOwnerKey != null) {
			adjustLiveTextureOwnerAggregate(
				previousOwnerKey,
				previousSample,
				-safePreviousBytes,
				-1
			);
		}
		adjustLiveTextureOwnerAggregate(ownerKey, sampleSource, estimatedBytes, 1);
	}

	private static void adjustLiveTextureAggregate (
		String groupKey,
		String sampleSource,
		long byteDelta,
		int countDelta
	) {
		String safeGroupKey = groupKey == null || groupKey.length() == 0 ? "unknown" : groupKey;
		AtomicLong bytesRef = TEXTURE_LIVE_SOURCE_BYTES.get(safeGroupKey);
		if (bytesRef == null) {
			if (byteDelta <= 0L && countDelta <= 0) return;
			AtomicLong created = new AtomicLong();
			AtomicLong existing = TEXTURE_LIVE_SOURCE_BYTES.putIfAbsent(safeGroupKey, created);
			bytesRef = existing == null ? created : existing;
		}
		AtomicInteger countRef = TEXTURE_LIVE_SOURCE_COUNTS.get(safeGroupKey);
		if (countRef == null) {
			if (byteDelta <= 0L && countDelta <= 0) return;
			AtomicInteger created = new AtomicInteger();
			AtomicInteger existing = TEXTURE_LIVE_SOURCE_COUNTS.putIfAbsent(safeGroupKey, created);
			countRef = existing == null ? created : existing;
		}
		if (sampleSource != null && sampleSource.length() > 0) {
			TEXTURE_LIVE_SOURCE_SAMPLES.put(safeGroupKey, sampleSource);
		}
		long bytes = bytesRef.get();
		if (byteDelta != 0L) {
			bytes = bytesRef.addAndGet(byteDelta);
			if (bytes < 0L) {
				bytesRef.set(0L);
				bytes = 0L;
			}
		}
		int count = countRef.get();
		if (countDelta != 0) {
			count = countRef.addAndGet(countDelta);
			if (count < 0) {
				countRef.set(0);
				count = 0;
			}
		}
		if (bytes == 0L || count == 0) {
			TEXTURE_LIVE_SOURCE_BYTES.remove(safeGroupKey, bytesRef);
			TEXTURE_LIVE_SOURCE_COUNTS.remove(safeGroupKey, countRef);
			TEXTURE_LIVE_SOURCE_SAMPLES.remove(safeGroupKey);
		}
	}

	private static void adjustLiveTextureOwnerAggregate (
		String ownerKey,
		String sampleSource,
		long byteDelta,
		int countDelta
	) {
		String safeOwnerKey = ownerKey == null || ownerKey.length() == 0 ? "core<-unknown" : ownerKey;
		AtomicLong bytesRef = TEXTURE_LIVE_OWNER_BYTES.get(safeOwnerKey);
		if (bytesRef == null) {
			if (byteDelta <= 0L && countDelta <= 0) return;
			AtomicLong created = new AtomicLong();
			AtomicLong existing = TEXTURE_LIVE_OWNER_BYTES.putIfAbsent(safeOwnerKey, created);
			bytesRef = existing == null ? created : existing;
		}
		AtomicInteger countRef = TEXTURE_LIVE_OWNER_COUNTS.get(safeOwnerKey);
		if (countRef == null) {
			if (byteDelta <= 0L && countDelta <= 0) return;
			AtomicInteger created = new AtomicInteger();
			AtomicInteger existing = TEXTURE_LIVE_OWNER_COUNTS.putIfAbsent(safeOwnerKey, created);
			countRef = existing == null ? created : existing;
		}
		if (sampleSource != null && sampleSource.length() > 0) {
			TEXTURE_LIVE_OWNER_SAMPLES.put(safeOwnerKey, sampleSource);
		}
		long bytes = bytesRef.get();
		if (byteDelta != 0L) {
			bytes = bytesRef.addAndGet(byteDelta);
			if (bytes < 0L) {
				bytesRef.set(0L);
				bytes = 0L;
			}
		}
		int count = countRef.get();
		if (countDelta != 0) {
			count = countRef.addAndGet(countDelta);
			if (count < 0) {
				countRef.set(0);
				count = 0;
			}
		}
		if (bytes == 0L || count == 0) {
			TEXTURE_LIVE_OWNER_BYTES.remove(safeOwnerKey, bytesRef);
			TEXTURE_LIVE_OWNER_COUNTS.remove(safeOwnerKey, countRef);
			TEXTURE_LIVE_OWNER_SAMPLES.remove(safeOwnerKey);
		}
	}

	private static long toSummaryMegabytes (long bytes) {
		if (bytes <= 0L) return 0L;
		return (bytes + (1024L * 1024L) - 1L) / (1024L * 1024L);
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
