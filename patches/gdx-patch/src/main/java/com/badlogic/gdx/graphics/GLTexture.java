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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.TextureData.TextureDataType;
import com.badlogic.gdx.graphics.glutils.FileTextureData;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.MipMapGenerator;
import com.badlogic.gdx.utils.Array;
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
	private static final String TEXTURE_RESIDENCY_MANAGER_PROP =
		"amethyst.gdx.texture_residency_manager";
	private static final boolean TEXTURE_RESIDENCY_MANAGER_ENABLED =
		readBooleanSystemProperty(TEXTURE_RESIDENCY_MANAGER_PROP, false);
	private static final String TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER_PROP =
		"amethyst.gdx.texture_residency_skip_for_ramsaver";
	private static final boolean TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER =
		readBooleanSystemProperty(TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER_PROP, false);
	private static final String TEXTURE_RESIDENCY_SOFT_BUDGET_PROP =
		"amethyst.gdx.texture_residency_soft_budget_bytes";
	private static final long TEXTURE_RESIDENCY_SOFT_BUDGET_BYTES =
		Math.max(
			512L * 1024L * 1024L,
			readLongSystemProperty(TEXTURE_RESIDENCY_SOFT_BUDGET_PROP, 768L * 1024L * 1024L, 0L, Long.MAX_VALUE)
		);
	private static final long TEXTURE_RESIDENCY_STARTUP_GRACE_NANOS = secondsToNanos(120L);
	private static final long TEXTURE_RESIDENCY_SAMPLE_INTERVAL_NANOS = secondsToNanos(30L);
	private static final long TEXTURE_RESIDENCY_SWEEP_COOLDOWN_NANOS = secondsToNanos(300L);
	private static final long TEXTURE_RESIDENCY_WATCHDOG_GROWTH_BYTES = 128L * 1024L * 1024L;
	private static final long TEXTURE_RESIDENCY_EMERGENCY_GROWTH_BYTES = 256L * 1024L * 1024L;
	private static final long TEXTURE_RESIDENCY_WATCHDOG_MIN_IDLE_NANOS = secondsToNanos(180L);
	private static final long TEXTURE_RESIDENCY_EMERGENCY_MIN_IDLE_NANOS = secondsToNanos(60L);
	private static final long TEXTURE_RESIDENCY_WATCHDOG_MIN_BYTES = 4L * 1024L * 1024L;
	private static final long TEXTURE_RESIDENCY_EMERGENCY_MIN_BYTES = 8L * 1024L * 1024L;
	private static final int TEXTURE_RESIDENCY_WATCHDOG_MAX_RECLAIMS = 4;
	private static final int TEXTURE_RESIDENCY_EMERGENCY_MAX_RECLAIMS = 8;
	private static final String TEXTURE_RESIDENCY_RESTORE_GRACE_FRAMES_PROP =
		"amethyst.gdx.texture_residency_restore_grace_frames";
	private static final int TEXTURE_RESIDENCY_RESTORE_GRACE_FRAMES =
		readIntSystemProperty(TEXTURE_RESIDENCY_RESTORE_GRACE_FRAMES_PROP, 180, 1, 72000);
	// Use wall-clock floors so high target FPS does not collapse protection windows into sub-second churn.
	private static final long TEXTURE_RESIDENCY_IDLE_MIN_MILLIS_FLOOR = 5000L;
	private static final long TEXTURE_RESIDENCY_PRESSURE_IDLE_MIN_MILLIS_FLOOR = 3000L;
	private static final long TEXTURE_RESIDENCY_RESTORE_GRACE_MIN_MILLIS_FLOOR = 5000L;
	private static final long TEXTURE_RESIDENCY_RESTORE_GRACE_NANOS = minimumDurationNanos(
		TEXTURE_RESIDENCY_RESTORE_GRACE_FRAMES,
		TEXTURE_RESIDENCY_RESTORE_GRACE_MIN_MILLIS_FLOOR
	);
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
	private static final AtomicInteger TEXTURE_RESIDENCY_SAMPLES = new AtomicInteger();
	private static final AtomicInteger TEXTURE_RESIDENCY_WATCHDOG_SWEEPS = new AtomicInteger();
	private static final AtomicInteger TEXTURE_RESIDENCY_EMERGENCY_SWEEPS = new AtomicInteger();
	private static final AtomicInteger TEXTURE_RESIDENCY_RECLAIMS = new AtomicInteger();
	private static final AtomicInteger TEXTURE_RESIDENCY_RESTORES = new AtomicInteger();
	private static final AtomicInteger TEXTURE_RESIDENCY_RESTORE_FAILURES = new AtomicInteger();
	private static final AtomicLong TEXTURE_RESIDENCY_RECLAIMED_BYTES = new AtomicLong();
	private static final AtomicLong TEXTURE_RESIDENCY_RESTORED_BYTES = new AtomicLong();
	private static final AtomicBoolean TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER_LOGGED = new AtomicBoolean();
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
	private static final ConcurrentHashMap<Integer, String> TEXTURE_HANDLE_LIVE_OWNER_SAMPLES =
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
	private static final AtomicInteger TEXTURE_UPLOAD_EVENTS = new AtomicInteger();
	private static final AtomicInteger TEXTURE_RELEASE_EVENTS = new AtomicInteger();
	private static final AtomicLong TEXTURE_UPLOAD_TOTAL_BYTES = new AtomicLong();
	private static final AtomicLong TEXTURE_RELEASE_TOTAL_BYTES = new AtomicLong();
	private static final ConcurrentHashMap<String, AtomicLong> TEXTURE_UPLOAD_SOURCE_BYTES =
		new ConcurrentHashMap<String, AtomicLong>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_UPLOAD_SOURCE_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, String> TEXTURE_UPLOAD_SOURCE_SAMPLES =
		new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, AtomicLong> TEXTURE_UPLOAD_OWNER_BYTES =
		new ConcurrentHashMap<String, AtomicLong>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_UPLOAD_OWNER_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, String> TEXTURE_UPLOAD_OWNER_SAMPLES =
		new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, AtomicLong> TEXTURE_RELEASE_SOURCE_BYTES =
		new ConcurrentHashMap<String, AtomicLong>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_RELEASE_SOURCE_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, String> TEXTURE_RELEASE_SOURCE_SAMPLES =
		new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, AtomicLong> TEXTURE_RELEASE_OWNER_BYTES =
		new ConcurrentHashMap<String, AtomicLong>();
	private static final ConcurrentHashMap<String, AtomicInteger> TEXTURE_RELEASE_OWNER_COUNTS =
		new ConcurrentHashMap<String, AtomicInteger>();
	private static final ConcurrentHashMap<String, String> TEXTURE_RELEASE_OWNER_SAMPLES =
		new ConcurrentHashMap<String, String>();
	private static final ConcurrentHashMap<String, TextureDebugWindowSnapshot> TEXTURE_DEBUG_WINDOWS =
		new ConcurrentHashMap<String, TextureDebugWindowSnapshot>();
	private static final AtomicLong TEXTURE_NATIVE_ESTIMATED_BYTES = new AtomicLong();
	private static boolean forceLinearMipmapFilterLogPrinted;
	private static volatile int textureLivePeak;
	private static volatile long textureNativeEstimatedBytesPeak;
	private static volatile long currentFrameId = -1L;
	private static final long TEXTURE_RESIDENCY_INIT_TIME_NANOS = nowMonotonicNanos();
	private static volatile long lastTextureResidencySampleTimeNanos = -1L;
	private static volatile long textureResidencyCooldownUntilNanos = -1L;
	private static volatile long textureResidencyLowWaterBytes = -1L;
	private static volatile int textureResidencyGrowthStrikes;
	private static volatile int textureResidencyEmergencyStrikes;
	private static final ThreadLocal<IntBuffer> GL_INT_QUERY_BUFFER = new ThreadLocal<IntBuffer>() {
		@Override
		protected IntBuffer initialValue () {
			return ByteBuffer.allocateDirect(Integer.SIZE / 8).order(ByteOrder.nativeOrder()).asIntBuffer();
		}
	};

	public final int glTarget;
	protected int glHandle;
	private final long debugTextureId;
	private int debugTrackedHandle;
	protected TextureFilter minFilter = TextureFilter.Nearest;
	protected TextureFilter magFilter = TextureFilter.Nearest;
	protected TextureWrap uWrap = TextureWrap.ClampToEdge;
	protected TextureWrap vWrap = TextureWrap.ClampToEdge;
	private long lastAccessFrame;
	private long lastAccessTimeNanos;
	private long lastRestoreFrame = -1L;
	private long lastRestoreTimeNanos = -1L;
	private String lastUseReason = "construct";
	private String residencyLastKnownSourceSample;
	private String residencyLastKnownOwnerKey;
	private String residencyLastKnownOwnerSample;
	private long residencyLastKnownBytes;
	private int residencyReleaseCount;
	private int residencyRestoreCount;
	private int residencyRestoreFailureCount;
	private volatile GLFrameBuffer<?> frameBufferTextureOwner;
	private boolean handleReclaimPending;
	private boolean permanentlyDisposed;

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
		this.lastAccessFrame = currentFrameId >= 0L ? currentFrameId : 0L;
		this.lastAccessTimeNanos = nowMonotonicNanos();
		onTextureConstructed(debugTextureId, getClass().getName(), glTarget, glHandle);
	}

	public abstract boolean isManaged ();

	protected abstract void reload ();

	public void bind () {
		notifyBeforeTextureAccess("bind");
		ensureHandleAvailableForUse("bind");
		syncDebugHandle("bind");
		Gdx.gl.glBindTexture(glTarget, glHandle);
	}

	public void bind (int unit) {
		notifyBeforeTextureAccess("bind_unit");
		ensureHandleAvailableForUse("bind_unit");
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
		ensureHandleAvailableForUse("get_handle");
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
		permanentlyDisposed = true;
		handleReclaimPending = false;
		releaseHandle("delete", true);
	}

	public final int releaseHandleForReuse (String reason) {
		handleReclaimPending = true;
		return releaseHandle(reason, true);
	}

	public final int invalidateHandleForReuse (String reason) {
		handleReclaimPending = true;
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
		handleReclaimPending = false;
		lastRestoreFrame = currentFrameId >= 0L ? currentFrameId : lastRestoreFrame;
		lastRestoreTimeNanos = nowMonotonicNanos();
	}

	private void syncDebugHandle (String reason) {
		if (debugTrackedHandle == glHandle) return;
		onTextureHandleUpdated(debugTextureId, debugTrackedHandle, glHandle, reason);
		debugTrackedHandle = glHandle;
	}

	public static String getDebugStatusSummary () {
		return "texturesDiag=" + (GPU_RESOURCE_DIAG_ENABLED ? "enabled" : "disabled")
			+ " textureAttribution=enabled"
			+ " textureWindows=enabled"
			+ " textureStackSamples="
			+ (GPU_RESOURCE_DIAG_ENABLED && GPU_RESOURCE_DIAG_TEXTURE_STACKS_ENABLED ? "enabled" : "disabled")
			+ " texturesLive=" + TEXTURES_LIVE.get()
			+ " texturesPeak=" + textureLivePeak
			+ " texturesCreated=" + TEXTURES_CREATED.get()
			+ " texturesDisposed=" + TEXTURES_DISPOSED.get()
			+ " textureUploads=" + TEXTURE_UPLOAD_EVENTS.get()
			+ " textureUploadBytes=" + TEXTURE_UPLOAD_TOTAL_BYTES.get()
			+ " textureReleases=" + TEXTURE_RELEASE_EVENTS.get()
			+ " textureReleaseBytes=" + TEXTURE_RELEASE_TOTAL_BYTES.get()
			+ " textureHandleUpdates=" + TEXTURE_HANDLE_UPDATES.get()
			+ " textureBytes=" + TEXTURE_NATIVE_ESTIMATED_BYTES.get()
			+ " textureBytesPeak=" + textureNativeEstimatedBytesPeak
			+ " textureResidencyManager=" + (TEXTURE_RESIDENCY_MANAGER_ENABLED ? "enabled" : "disabled")
			+ " textureResidencySamples=" + TEXTURE_RESIDENCY_SAMPLES.get()
			+ " textureResidencyWatchdogSweeps=" + TEXTURE_RESIDENCY_WATCHDOG_SWEEPS.get()
			+ " textureResidencyEmergencySweeps=" + TEXTURE_RESIDENCY_EMERGENCY_SWEEPS.get()
			+ " textureResidencyReclaims=" + TEXTURE_RESIDENCY_RECLAIMS.get()
			+ " textureResidencyRestores=" + TEXTURE_RESIDENCY_RESTORES.get()
			+ " textureResidencyRestoreFailures=" + TEXTURE_RESIDENCY_RESTORE_FAILURES.get()
			+ " textureResidencyReclaimedBytes=" + TEXTURE_RESIDENCY_RECLAIMED_BYTES.get()
			+ " textureResidencyRestoredBytes=" + TEXTURE_RESIDENCY_RESTORED_BYTES.get();
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

	public static String getUploadSourceSummary () {
		return buildLiveTextureSummary(
			"textureUploadTop=",
			TEXTURE_UPLOAD_SOURCE_BYTES,
			TEXTURE_UPLOAD_SOURCE_COUNTS,
			TEXTURE_UPLOAD_SOURCE_SAMPLES
		);
	}

	public static String getUploadOwnerSummary () {
		return buildLiveTextureSummary(
			"textureUploadOwnerTop=",
			TEXTURE_UPLOAD_OWNER_BYTES,
			TEXTURE_UPLOAD_OWNER_COUNTS,
			TEXTURE_UPLOAD_OWNER_SAMPLES
		);
	}

	public static String getReleaseSourceSummary () {
		return buildLiveTextureSummary(
			"textureReleaseTop=",
			TEXTURE_RELEASE_SOURCE_BYTES,
			TEXTURE_RELEASE_SOURCE_COUNTS,
			TEXTURE_RELEASE_SOURCE_SAMPLES
		);
	}

	public static String getReleaseOwnerSummary () {
		return buildLiveTextureSummary(
			"textureReleaseOwnerTop=",
			TEXTURE_RELEASE_OWNER_BYTES,
			TEXTURE_RELEASE_OWNER_COUNTS,
			TEXTURE_RELEASE_OWNER_SAMPLES
		);
	}

	public static void beginDebugWindow (String label) {
		String normalizedLabel = normalizeDebugWindowLabel(label);
		if (normalizedLabel == null) return;
		TEXTURE_DEBUG_WINDOWS.put(normalizedLabel, captureDebugWindowSnapshot());
	}

	public static String finishDebugWindow (String label) {
		String normalizedLabel = normalizeDebugWindowLabel(label);
		if (normalizedLabel == null) return "unavailable";
		TextureDebugWindowSnapshot startSnapshot = TEXTURE_DEBUG_WINDOWS.remove(normalizedLabel);
		if (startSnapshot == null) return "missing";
		return buildDebugWindowSummary(startSnapshot, captureDebugWindowSnapshot());
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

	public static void noteFrameRendered (long frameId) {
		if (frameId >= 0L) {
			currentFrameId = frameId;
		}
	}

	public static void reclaimIdleTextures (Application app, long frameId) {
		if (TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER) {
			logTextureResidencySkipForRamSaverIfNeeded();
			return;
		}
		if (!TEXTURE_RESIDENCY_MANAGER_ENABLED || Gdx.gl == null) return;
		if (app == null || frameId < 0L) return;
		noteFrameRendered(frameId);
		if (elapsedSince(TEXTURE_RESIDENCY_INIT_TIME_NANOS) < TEXTURE_RESIDENCY_STARTUP_GRACE_NANOS) {
			return;
		}
		long now = nowMonotonicNanos();
		if (lastTextureResidencySampleTimeNanos >= 0L
			&& now - lastTextureResidencySampleTimeNanos < TEXTURE_RESIDENCY_SAMPLE_INTERVAL_NANOS) {
			return;
		}
		lastTextureResidencySampleTimeNanos = now;
		TEXTURE_RESIDENCY_SAMPLES.incrementAndGet();

		long liveBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.get();
		if (textureResidencyLowWaterBytes < 0L) {
			textureResidencyLowWaterBytes = liveBytes;
			return;
		}
		if (liveBytes < textureResidencyLowWaterBytes) {
			textureResidencyLowWaterBytes = liveBytes;
		}

		long lowWaterBytes = textureResidencyLowWaterBytes;
		long watchdogTriggerBytes = safeAdd(lowWaterBytes, TEXTURE_RESIDENCY_WATCHDOG_GROWTH_BYTES);
		long emergencyTriggerBytes = Math.max(
			safeAdd(lowWaterBytes, TEXTURE_RESIDENCY_EMERGENCY_GROWTH_BYTES),
			TEXTURE_RESIDENCY_SOFT_BUDGET_BYTES
		);

		if (liveBytes > watchdogTriggerBytes) {
			textureResidencyGrowthStrikes++;
		} else {
			textureResidencyGrowthStrikes = 0;
		}
		if (liveBytes > emergencyTriggerBytes) {
			textureResidencyEmergencyStrikes++;
		} else {
			textureResidencyEmergencyStrikes = 0;
		}

		if (textureResidencyCooldownUntilNanos > now) return;

		Array<Texture> managedTextures = getManagedTextures(app);
		if (managedTextures == null || managedTextures.size == 0) return;
		int currentTexture2DBinding = getCurrentTextureBinding(GL_TEXTURE_2D_ENUM);

		TextureResidencySweepResult sweepResult = null;
		if (textureResidencyEmergencyStrikes >= 2) {
			sweepResult = reclaimResidencyTextures(
				managedTextures,
				frameId,
				TEXTURE_RESIDENCY_EMERGENCY_MIN_IDLE_NANOS,
				TEXTURE_RESIDENCY_EMERGENCY_MIN_BYTES,
				TEXTURE_RESIDENCY_EMERGENCY_MAX_RECLAIMS,
				true,
				lowWaterBytes,
				emergencyTriggerBytes,
				currentTexture2DBinding
			);
		} else if (textureResidencyGrowthStrikes >= 2) {
			sweepResult = reclaimResidencyTextures(
				managedTextures,
				frameId,
				TEXTURE_RESIDENCY_WATCHDOG_MIN_IDLE_NANOS,
				TEXTURE_RESIDENCY_WATCHDOG_MIN_BYTES,
				TEXTURE_RESIDENCY_WATCHDOG_MAX_RECLAIMS,
				false,
				lowWaterBytes,
				watchdogTriggerBytes,
				currentTexture2DBinding
			);
		}

		if (sweepResult != null) {
			textureResidencyCooldownUntilNanos = now + TEXTURE_RESIDENCY_SWEEP_COOLDOWN_NANOS;
			if (sweepResult.reclaimedCount > 0) {
				textureResidencyGrowthStrikes = 0;
				textureResidencyEmergencyStrikes = 0;
				textureResidencyLowWaterBytes = sweepResult.bytesAfter;
			}
			System.out.println("[gdx-diag] GLTexture texture_residency_" + sweepResult.kind + "_sweep"
				+ " frame=" + frameId
				+ " sweep=" + sweepResult.sweepIndex
				+ " candidates=" + sweepResult.candidateCount
				+ " reclaimed=" + sweepResult.reclaimedCount
				+ " reclaimedBytes=" + sweepResult.reclaimedBytes
				+ " beforeBytes=" + sweepResult.bytesBefore
				+ " afterBytes=" + sweepResult.bytesAfter
				+ " lowWaterBytes=" + sweepResult.lowWaterBytes
				+ " triggerBytes=" + sweepResult.triggerBytes
				+ " topOwnersBefore=" + sweepResult.topOwnersBefore
				+ " topOwnersAfter=" + sweepResult.topOwnersAfter);
		}
	}

	@Override
	public void dispose () {
		delete();
	}

	private void notifyBeforeTextureAccess (String reason) {
		GLFrameBuffer<?> frameBufferOwner = frameBufferTextureOwner;
		if (frameBufferOwner != null) {
			frameBufferOwner.onExternalColorTextureAccess(reason);
		}
		lastAccessFrame = currentFrameId >= 0L ? currentFrameId : lastAccessFrame;
		lastAccessTimeNanos = nowMonotonicNanos();
		lastUseReason = reason == null || reason.length() == 0 ? "unknown" : reason;
	}

	public final void setFrameBufferTextureOwner (GLFrameBuffer<?> owner) {
		frameBufferTextureOwner = owner;
	}

	public final void clearFrameBufferTextureOwner (GLFrameBuffer<?> owner) {
		GLFrameBuffer<?> currentOwner = frameBufferTextureOwner;
		if (currentOwner == owner || currentOwner == null) {
			frameBufferTextureOwner = null;
		}
	}

	public final GLFrameBuffer<?> getFrameBufferTextureOwner () {
		return frameBufferTextureOwner;
	}

	private int releaseHandle (String reason, boolean deleteGlHandle) {
		if (glHandle == 0) return 0;
		int deletedHandle = glHandle;
		captureResidencySnapshot(deletedHandle);
		forgetTrackedTextureBytes(deletedHandle);
		if (deleteGlHandle) {
			Gdx.gl.glDeleteTexture(glHandle);
		}
		glHandle = 0;
		debugTrackedHandle = 0;
		onTextureDeleted(debugTextureId, deletedHandle, reason);
		return deletedHandle;
	}

	private void ensureHandleAvailableForUse (String reason) {
		if (glHandle != 0 || !handleReclaimPending || permanentlyDisposed) return;
		if (!(this instanceof Texture)) return;
		Texture texture = (Texture)this;
		TextureData data = safeGetTextureData(texture);
		if (!isResidencyReloadableTexture(texture, data)) return;

		int newHandle = 0;
		try {
			newHandle = Gdx.gl.glGenTexture();
			if (newHandle == 0) return;
			restoreHandleForReuse(newHandle, "texture_residency_restore_" + reason);
			texture.load(data);
			lastAccessFrame = currentFrameId >= 0L ? currentFrameId : lastAccessFrame;
			lastAccessTimeNanos = nowMonotonicNanos();
			lastUseReason = reason == null || reason.length() == 0 ? "restore" : reason;
			long restoredBytes = refreshResidencyTrackedBytesFromHandle();
			if (restoredBytes <= 0L) {
				restoredBytes = estimateTextureBytes(data.getWidth(), data.getHeight(), data.getFormat());
				if (data.useMipMaps()) {
					restoredBytes = Math.max(restoredBytes, (restoredBytes * 4L) / 3L);
				}
			}
			residencyRestoreCount++;
			TEXTURE_RESIDENCY_RESTORES.incrementAndGet();
			TEXTURE_RESIDENCY_RESTORED_BYTES.addAndGet(Math.max(0L, restoredBytes));
			System.out.println("[gdx-diag] GLTexture residency_restore id=" + debugTextureId
				+ " handle=" + glHandle
				+ " reason=" + (reason == null ? "unknown" : reason)
				+ " owner=" + getResidencyOwnerKeyForLog()
				+ " source=" + getResidencySourceSampleForLog()
				+ " bytes=" + Math.max(0L, restoredBytes)
				+ " restores=" + residencyRestoreCount
				+ " releases=" + residencyReleaseCount);
		} catch (Throwable t) {
			residencyRestoreFailureCount++;
			TEXTURE_RESIDENCY_RESTORE_FAILURES.incrementAndGet();
			if (glHandle != 0) {
				releaseHandle("texture_residency_restore_failed", true);
			} else if (newHandle != 0) {
				try {
					Gdx.gl.glDeleteTexture(newHandle);
				} catch (Throwable ignored) {
				}
			}
			handleReclaimPending = true;
			if (residencyRestoreFailureCount <= 3 || (residencyRestoreFailureCount % 10) == 0) {
				System.out.println("[gdx-diag] GLTexture residency_restore_failed id=" + debugTextureId
					+ " reason=" + (reason == null ? "unknown" : reason)
					+ " owner=" + getResidencyOwnerKeyForLog()
					+ " source=" + getResidencySourceSampleForLog()
					+ " failures=" + residencyRestoreFailureCount
					+ " error=" + t);
			}
		}
	}

	private boolean reclaimForResidencySweep (
		long minIdleNanos,
		long minBytes,
		String reason,
		int currentTexture2DBinding
	) {
		String protectReason = resolveTextureResidencyProtectReason(minIdleNanos, minBytes, currentTexture2DBinding);
		if (protectReason != null) return false;
		long reclaimedBytes = captureTrackedHandleBytes();
		if (releaseHandleForReuse(reason) == 0) return false;
		residencyReleaseCount++;
		TEXTURE_RESIDENCY_RECLAIMS.incrementAndGet();
		TEXTURE_RESIDENCY_RECLAIMED_BYTES.addAndGet(reclaimedBytes);
		return true;
	}

	private String resolveTextureResidencyProtectReason (long minIdleNanos, long minBytes, int currentTexture2DBinding) {
		if (TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER) {
			logTextureResidencySkipForRamSaverIfNeeded();
			return "disabled_for_ramsaver";
		}
		if (!TEXTURE_RESIDENCY_MANAGER_ENABLED) return "disabled";
		if (permanentlyDisposed) return "disposed";
		if (!(this instanceof Texture)) return "not_texture";
		if (!isManaged()) return "not_managed";
		if (glHandle == 0) return "missing_handle";
		if (glTarget != GL_TEXTURE_2D_ENUM) return "unsupported_target";
		Texture texture = (Texture)this;
		TextureData data = safeGetTextureData(texture);
		if (!isResidencyReloadableTexture(texture, data)) return "not_reloadable";
		if (GLFrameBuffer.isFrameBufferTexture(this)) return "framebuffer_owner";
		if (!isResidencyWatchdogOwnerEligible()) return "owner_not_eligible";
		if (isResidencyWatchdogSourceExcluded(data)) return "excluded_hot_path";
		if (captureTrackedHandleBytes() < minBytes) return "below_min_bytes";
		if (currentTexture2DBinding == glHandle) return "currently_bound";
		if (getIdleDurationNanos() < minIdleNanos) return "recently_used";
		if (lastRestoreFrame >= 0L && getRestoreAgeNanos() < TEXTURE_RESIDENCY_RESTORE_GRACE_NANOS) {
			return "recently_restored";
		}
		return null;
	}

	private long getIdleFrames (long frameId) {
		long idleFrames = frameId - lastAccessFrame;
		return idleFrames < 0L ? 0L : idleFrames;
	}

	private long getIdleDurationNanos () {
		if (lastAccessTimeNanos <= 0L) return Long.MAX_VALUE;
		return elapsedSince(lastAccessTimeNanos);
	}

	private long getRestoreAgeNanos () {
		if (lastRestoreTimeNanos <= 0L) return Long.MAX_VALUE;
		return elapsedSince(lastRestoreTimeNanos);
	}

	private boolean isResidencyWatchdogOwnerEligible () {
		String ownerKey = getResidencyOwnerKeyForLog();
		return ownerKey.startsWith("external<-")
			|| ownerKey.startsWith("downfall<-")
			|| ownerKey.startsWith("basemod<-")
			|| ownerKey.startsWith("modthespire<-")
			|| ownerKey.startsWith("stslib<-");
	}

	private boolean isResidencyWatchdogSourceExcluded (TextureData data) {
		String sourcePath = resolveTextureSourcePath(data);
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		return containsAnyPathSegment(
			normalizedSourcePath,
			"ui",
			"cardui",
			"cards",
			"oldcards",
			"map",
			"title",
			"scene",
			"scenes",
			"charselect",
			"powers",
			"vfx"
		);
	}

	private long captureTrackedHandleBytes () {
		if (glHandle == 0) return Math.max(0L, residencyLastKnownBytes);
		Long trackedBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.get(glHandle);
		long bytes = trackedBytes == null ? 0L : trackedBytes.longValue();
		if (bytes > 0L) {
			residencyLastKnownBytes = bytes;
		}
		return bytes > 0L ? bytes : Math.max(0L, residencyLastKnownBytes);
	}

	private long refreshResidencyTrackedBytesFromHandle () {
		long trackedBytes = captureTrackedHandleBytes();
		if (trackedBytes > 0L) {
			return trackedBytes;
		}
		return Math.max(0L, residencyLastKnownBytes);
	}

	private void captureResidencySnapshot (int handle) {
		if (handle == 0) return;
		Long trackedBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.get(handle);
		if (trackedBytes != null && trackedBytes.longValue() > 0L) {
			residencyLastKnownBytes = trackedBytes.longValue();
		}
		String sourceSample = TEXTURE_HANDLE_LIVE_SAMPLES.get(handle);
		if (sourceSample != null && sourceSample.length() > 0) {
			residencyLastKnownSourceSample = sourceSample;
		}
		String ownerKey = TEXTURE_HANDLE_LIVE_OWNER_KEYS.get(handle);
		if (ownerKey != null && ownerKey.length() > 0) {
			residencyLastKnownOwnerKey = ownerKey;
		}
		String ownerSample = TEXTURE_HANDLE_LIVE_OWNER_SAMPLES.get(handle);
		if (ownerSample != null && ownerSample.length() > 0) {
			residencyLastKnownOwnerSample = ownerSample;
		}
	}

	private String getLastUseReasonForLog () {
		return lastUseReason == null || lastUseReason.length() == 0 ? "unknown" : lastUseReason;
	}

	private String getResidencySourceSampleForLog () {
		return residencyLastKnownSourceSample == null || residencyLastKnownSourceSample.length() == 0
			? "unknown"
			: residencyLastKnownSourceSample;
	}

	private String getResidencyOwnerKeyForLog () {
		return residencyLastKnownOwnerKey == null || residencyLastKnownOwnerKey.length() == 0
			? "core<-unknown"
			: residencyLastKnownOwnerKey;
	}

	@SuppressWarnings("unchecked")
	private static Array<Texture> getManagedTextures (Application app) {
		if (app == null) return null;
		try {
			Field managedField = Texture.class.getDeclaredField("managedTextures");
			managedField.setAccessible(true);
			Object managedObj = managedField.get(null);
			if (!(managedObj instanceof Map<?, ?>)) return null;
			Object textures = ((Map<?, ?>)managedObj).get(app);
			if (textures instanceof Array<?>) {
				return (Array<Texture>)textures;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	private static TextureResidencySweepResult reclaimResidencyTextures (
		Array<Texture> managedTextures,
		long frameId,
		long minIdleNanos,
		long minBytes,
		int maxReclaims,
		boolean emergency,
		long lowWaterBytes,
		long triggerBytes,
		int currentTexture2DBinding
	) {
		long bytesBefore = TEXTURE_NATIVE_ESTIMATED_BYTES.get();
		int sweepIndex = emergency
			? TEXTURE_RESIDENCY_EMERGENCY_SWEEPS.incrementAndGet()
			: TEXTURE_RESIDENCY_WATCHDOG_SWEEPS.incrementAndGet();
		String topOwnersBefore = stripLiveTextureSummaryLabel(getLiveOwnerSummary(), "textureOwnerTop=");
		List<GLTexture> candidates = new ArrayList<GLTexture>(managedTextures.size);
		for (int i = 0; i < managedTextures.size; i++) {
			GLTexture texture = managedTextures.get(i);
			if (texture != null && texture.resolveTextureResidencyProtectReason(
				minIdleNanos,
				minBytes,
				currentTexture2DBinding
			) == null) {
				candidates.add(texture);
			}
		}
		Collections.sort(candidates, new Comparator<GLTexture>() {
			@Override
			public int compare (GLTexture left, GLTexture right) {
				return compareResidencyCandidates(left, right, frameId);
			}
		});

		int reclaimedCount = 0;
		long reclaimedBytes = 0L;
		for (int i = 0; i < candidates.size(); i++) {
			if (reclaimedCount >= maxReclaims) break;
			GLTexture candidate = candidates.get(i);
			if (!candidate.reclaimForResidencySweep(
				minIdleNanos,
				minBytes,
				emergency ? "texture_residency_emergency" : "texture_residency_watchdog",
				currentTexture2DBinding
			)) continue;
			reclaimedCount++;
			reclaimedBytes += Math.max(0L, candidate.residencyLastKnownBytes);
		}
		long bytesAfter = TEXTURE_NATIVE_ESTIMATED_BYTES.get();
		String topOwnersAfter = stripLiveTextureSummaryLabel(getLiveOwnerSummary(), "textureOwnerTop=");
		return new TextureResidencySweepResult(
			emergency ? "emergency" : "watchdog",
			sweepIndex,
			bytesBefore,
			bytesAfter,
			reclaimedCount,
			reclaimedBytes,
			candidates.size(),
			topOwnersBefore,
			topOwnersAfter,
			lowWaterBytes,
			triggerBytes
		);
	}

	private static int compareResidencyCandidates (GLTexture left, GLTexture right, long frameId) {
		int leftTier = left.getResidencyOwnerTier();
		int rightTier = right.getResidencyOwnerTier();
		if (leftTier != rightTier) return leftTier < rightTier ? -1 : 1;
		long leftIdleNanos = left.getIdleDurationNanos();
		long rightIdleNanos = right.getIdleDurationNanos();
		if (leftIdleNanos != rightIdleNanos) return leftIdleNanos > rightIdleNanos ? -1 : 1;
		long leftBytes = left.captureTrackedHandleBytes();
		long rightBytes = right.captureTrackedHandleBytes();
		if (leftBytes != rightBytes) return leftBytes > rightBytes ? -1 : 1;
		long leftIdleFrames = left.getIdleFrames(frameId);
		long rightIdleFrames = right.getIdleFrames(frameId);
		if (leftIdleFrames != rightIdleFrames) return leftIdleFrames > rightIdleFrames ? -1 : 1;
		if (left.residencyRestoreCount != right.residencyRestoreCount) {
			return left.residencyRestoreCount < right.residencyRestoreCount ? -1 : 1;
		}
		return left.getResidencyOwnerKeyForLog().compareTo(right.getResidencyOwnerKeyForLog());
	}

	private int getResidencyOwnerTier () {
		String ownerKey = getResidencyOwnerKeyForLog();
		if (ownerKey.startsWith("external<-") || ownerKey.startsWith("downfall<-")) {
			return 0;
		}
		if (ownerKey.startsWith("basemod<-")
			|| ownerKey.startsWith("modthespire<-")
			|| ownerKey.startsWith("stslib<-")) {
			return 1;
		}
		return 2;
	}

	private static TextureData safeGetTextureData (Texture texture) {
		if (texture == null) return null;
		try {
			return texture.getTextureData();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void logTextureResidencySkipForRamSaverIfNeeded () {
		if (!TEXTURE_RESIDENCY_MANAGER_ENABLED || !TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER) return;
		if (!TEXTURE_RESIDENCY_SKIP_FOR_RAM_SAVER_LOGGED.compareAndSet(false, true)) return;
		System.out.println("[gdx-diag] GLTexture texture_residency_disabled reason=ram_saver_mod");
	}

	private static boolean isResidencyReloadableTexture (Texture texture, TextureData data) {
		if (texture == null || data == null) return false;
		if (!texture.isManaged()) return false;
		if (!data.isManaged()) return false;
		if (!(data instanceof FileTextureData)) return false;
		TextureDataType dataType = data.getType();
		return dataType != null && dataType != TextureDataType.Custom;
	}

	private static String stripLiveTextureSummaryLabel (String summary, String label) {
		if (summary == null || summary.length() == 0) return "none";
		if (label != null && summary.startsWith(label)) {
			return summary.substring(label.length());
		}
		return summary;
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
			String sourcePath = resolveTextureSourcePath(data);
			String stackKey = captureTextureUploadStackIfNeeded(data, sourcePath, data.getWidth(), data.getHeight(), data.getFormat());
			data.consumeCustomData(target);
			recordTextureNativeBytes(
				target,
				data.getWidth(),
				data.getHeight(),
				data.getFormat(),
				data.useMipMaps(),
				sourcePath,
				stackKey
			);
			return;
		}
		String sourcePath = resolveTextureSourcePath(data);
		String stackKey = null;
		if (shouldCaptureTextureUploadStackForDiagnostics()) {
			stackKey = captureRelevantTextureUploadStack();
		}
		logLargeTextureUpload(data, miplevel, stackKey, sourcePath);
		Pixmap pixmap = data.consumePixmap();
		if (stackKey == null) {
			stackKey = captureTextureUploadStackIfNeeded(data, sourcePath, pixmap.getWidth(), pixmap.getHeight(), pixmap.getFormat());
		}
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

	private static boolean shouldCaptureTextureUploadStackForDiagnostics () {
		return GPU_RESOURCE_DIAG_ENABLED && GPU_RESOURCE_DIAG_TEXTURE_STACKS_ENABLED;
	}

	private static String captureTextureUploadStackIfNeeded (
		TextureData data,
		String sourcePath,
		int width,
		int height,
		Pixmap.Format format
	) {
		if (shouldCaptureTextureUploadStackForDiagnostics()) return captureRelevantTextureUploadStack();
		if (!TEXTURE_PRESSURE_DOWNSCALE_ENABLED) return null;
		if (!needsTexturePressureStackClassification(data, sourcePath, width, height, format)) return null;
		return captureRelevantTextureUploadStack();
	}

	private static boolean needsTexturePressureStackClassification (
		TextureData data,
		String sourcePath,
		int width,
		int height,
		Pixmap.Format format
	) {
		if (width <= 0 || height <= 0) return false;
		long estimatedBytes = estimateTextureBytes(width, height, format);
		if (estimatedBytes < TEXTURE_PRESSURE_DOWNSCALE_ART_MIN_BYTES) return false;
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		if (normalizedSourcePath != null) return false;
		long projectedTotalBytes = safeAdd(
			safeAdd(TEXTURE_NATIVE_ESTIMATED_BYTES.get(), GLFrameBuffer.getEstimatedNativeBytes()),
			estimatedBytes
		);
		if (projectedTotalBytes >= TEXTURE_PRESSURE_DOWNSCALE_SOFT_BYTES) return true;
		return width >= 2048 || height >= 2048 || (data != null && data.useMipMaps());
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
		String namespaceGroup = TextureOwnerSummary.extractExternalNamespaceGroup(stackKey);
		if (normalizedSourcePath != null) {
			String archiveGroup = extractLiveTextureArchiveGroup(normalizedSourcePath);
			if (archiveGroup != null) return archiveGroup;
			String pathGroup = extractLiveTexturePathGroup(normalizedSourcePath);
			if (pathGroup != null) {
				if (isGenericTexturePathGroup(pathGroup) && namespaceGroup != null) {
					return namespaceGroup;
				}
				return pathGroup;
			}
		}
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

	private static boolean isGenericTexturePathGroup (String pathGroup) {
		if (pathGroup == null || pathGroup.length() == 0) return false;
		return "images".equals(pathGroup)
			|| "image".equals(pathGroup)
			|| "img".equals(pathGroup)
			|| "textures".equals(pathGroup)
			|| "texture".equals(pathGroup)
			|| "characters".equals(pathGroup)
			|| "character".equals(pathGroup)
			|| "cards".equals(pathGroup)
			|| "card".equals(pathGroup)
			|| "powers".equals(pathGroup)
			|| "power".equals(pathGroup)
			|| "ui".equals(pathGroup)
			|| "vfx".equals(pathGroup)
			|| "effects".equals(pathGroup)
			|| "scenes".equals(pathGroup)
			|| "scene".equals(pathGroup)
			|| "charselect".equals(pathGroup);
	}

	private static long minimumIdleDurationNanos (boolean managerPressure, long minIdleFrames) {
		long floorMillis = managerPressure
			? TEXTURE_RESIDENCY_PRESSURE_IDLE_MIN_MILLIS_FLOOR
			: TEXTURE_RESIDENCY_IDLE_MIN_MILLIS_FLOOR;
		return minimumDurationNanos(minIdleFrames, floorMillis);
	}

	private static long minimumDurationNanos (long frameCount, long floorMillis) {
		long frameMillis = framesToMillisAtSixtyFps(frameCount);
		long requiredMillis = Math.max(floorMillis, frameMillis);
		if (requiredMillis <= 0L) return 0L;
		if (requiredMillis >= Long.MAX_VALUE / 1000000L) return Long.MAX_VALUE;
		return requiredMillis * 1000000L;
	}

	private static long framesToMillisAtSixtyFps (long frameCount) {
		if (frameCount <= 0L) return 0L;
		if (frameCount >= Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
		return (frameCount * 1000L + 59L) / 60L;
	}

	private static long nowMonotonicNanos () {
		return System.nanoTime();
	}

	private static long secondsToNanos (long seconds) {
		if (seconds <= 0L) return 0L;
		if (seconds >= Long.MAX_VALUE / 1000000000L) return Long.MAX_VALUE;
		return seconds * 1000000000L;
	}

	private static long elapsedSince (long startTimeNanos) {
		long elapsed = nowMonotonicNanos() - startTimeNanos;
		return elapsed < 0L ? 0L : elapsed;
	}

	private static String summarizeLiveTextureSampleSource (String sourcePath, String stackKey, String groupKey) {
		String normalizedSourcePath = normalizeTextureSourcePath(sourcePath);
		if (normalizedSourcePath != null) {
			return abbreviateLiveTextureSource(normalizedSourcePath);
		}
		String namespaceGroup = TextureOwnerSummary.extractExternalNamespaceGroup(stackKey);
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
		return TextureOwnerSummary.classifyOwnerKey(groupKey, sourcePath, stackKey);
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

	private static boolean containsPathSegment (String sourcePath, String segment) {
		if (sourcePath == null || sourcePath.length() == 0 || segment == null || segment.length() == 0) {
			return false;
		}
		return sourcePath.equals(segment)
			|| sourcePath.startsWith(segment + "/")
			|| sourcePath.endsWith("/" + segment)
			|| sourcePath.indexOf("/" + segment + "/") >= 0;
	}

	private static boolean containsAnyPathSegment (String sourcePath, String... segments) {
		if (sourcePath == null || sourcePath.length() == 0 || segments == null) return false;
		for (int i = 0; i < segments.length; i++) {
			if (containsPathSegment(sourcePath, segments[i])) {
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
		return TextureOwnerSummary.extractExternalNamespaceGroup(stackKey) != null;
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
		TextureAttribution attribution = resolveTextureAttribution(sourcePath, stackKey);
		recordTextureUploadActivity(attribution, estimatedBytes);
		updateLiveTextureSourceAttribution(handle, estimatedBytes, attribution);
		Long previousBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.put(handle, estimatedBytes);
		TEXTURE_UPLOAD_EVENTS.incrementAndGet();
		TEXTURE_UPLOAD_TOTAL_BYTES.addAndGet(estimatedBytes);
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
		String previousOwnerSample = TEXTURE_HANDLE_LIVE_OWNER_SAMPLES.remove(handle);
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
				previousOwnerSample,
				-(previousBytes == null ? 0L : previousBytes.longValue()),
				-1
			);
		}
		if (previousBytes == null) return;
		TEXTURE_RELEASE_EVENTS.incrementAndGet();
		TEXTURE_RELEASE_TOTAL_BYTES.addAndGet(previousBytes.longValue());
		recordTextureReleaseActivity(
			previousGroup,
			previousSample,
			previousOwnerKey,
			previousOwnerSample,
			previousBytes.longValue()
		);
		long liveBytes = TEXTURE_NATIVE_ESTIMATED_BYTES.addAndGet(-previousBytes.longValue());
		if (liveBytes < 0L) {
			TEXTURE_NATIVE_ESTIMATED_BYTES.set(0L);
		}
	}

	private static void updateLiveTextureSourceAttribution (
		int handle,
		long estimatedBytes,
		TextureAttribution attribution
	) {
		if (attribution == null) return;
		String groupKey = attribution.groupKey;
		String sampleSource = attribution.sourceSample;
		String ownerKey = attribution.ownerKey;
		String ownerSample = attribution.ownerSample;
		Long previousBytes = TEXTURE_HANDLE_ESTIMATED_BYTES.get(handle);
		String previousGroup = TEXTURE_HANDLE_LIVE_GROUPS.put(handle, groupKey);
		String previousSample = TEXTURE_HANDLE_LIVE_SAMPLES.put(handle, sampleSource);
		String previousOwnerKey = TEXTURE_HANDLE_LIVE_OWNER_KEYS.put(handle, ownerKey);
		String previousOwnerSample = TEXTURE_HANDLE_LIVE_OWNER_SAMPLES.put(handle, ownerSample);
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
				ownerSample,
				estimatedBytes - safePreviousBytes,
				0
			);
			return;
		}
		if (previousOwnerKey != null) {
			adjustLiveTextureOwnerAggregate(
				previousOwnerKey,
				previousOwnerSample,
				-safePreviousBytes,
				-1
			);
		}
		adjustLiveTextureOwnerAggregate(ownerKey, ownerSample, estimatedBytes, 1);
	}

	private static void adjustLiveTextureAggregate (
		String groupKey,
		String sampleSource,
		long byteDelta,
		int countDelta
	) {
		adjustTextureAggregate(
			TEXTURE_LIVE_SOURCE_BYTES,
			TEXTURE_LIVE_SOURCE_COUNTS,
			TEXTURE_LIVE_SOURCE_SAMPLES,
			"unknown",
			groupKey,
			sampleSource,
			byteDelta,
			countDelta
		);
	}

	private static void adjustLiveTextureOwnerAggregate (
		String ownerKey,
		String ownerSample,
		long byteDelta,
		int countDelta
	) {
		adjustTextureAggregate(
			TEXTURE_LIVE_OWNER_BYTES,
			TEXTURE_LIVE_OWNER_COUNTS,
			TEXTURE_LIVE_OWNER_SAMPLES,
			"core<-unknown",
			ownerKey,
			ownerSample,
			byteDelta,
			countDelta
		);
	}

	private static void recordTextureUploadActivity (TextureAttribution attribution, long estimatedBytes) {
		if (attribution == null || estimatedBytes <= 0L) return;
		adjustTextureAggregate(
			TEXTURE_UPLOAD_SOURCE_BYTES,
			TEXTURE_UPLOAD_SOURCE_COUNTS,
			TEXTURE_UPLOAD_SOURCE_SAMPLES,
			"unknown",
			attribution.groupKey,
			attribution.sourceSample,
			estimatedBytes,
			1
		);
		adjustTextureAggregate(
			TEXTURE_UPLOAD_OWNER_BYTES,
			TEXTURE_UPLOAD_OWNER_COUNTS,
			TEXTURE_UPLOAD_OWNER_SAMPLES,
			"core<-unknown",
			attribution.ownerKey,
			attribution.ownerSample,
			estimatedBytes,
			1
		);
	}

	private static void recordTextureReleaseActivity (
		String groupKey,
		String sourceSample,
		String ownerKey,
		String ownerSample,
		long releasedBytes
	) {
		if (releasedBytes <= 0L) return;
		adjustTextureAggregate(
			TEXTURE_RELEASE_SOURCE_BYTES,
			TEXTURE_RELEASE_SOURCE_COUNTS,
			TEXTURE_RELEASE_SOURCE_SAMPLES,
			"unknown",
			groupKey,
			sourceSample,
			releasedBytes,
			1
		);
		adjustTextureAggregate(
			TEXTURE_RELEASE_OWNER_BYTES,
			TEXTURE_RELEASE_OWNER_COUNTS,
			TEXTURE_RELEASE_OWNER_SAMPLES,
			"core<-unknown",
			ownerKey,
			ownerSample,
			releasedBytes,
			1
		);
	}

	private static void adjustTextureAggregate (
		ConcurrentHashMap<String, AtomicLong> bytesByKey,
		ConcurrentHashMap<String, AtomicInteger> countsByKey,
		ConcurrentHashMap<String, String> samplesByKey,
		String fallbackKey,
		String key,
		String sample,
		long byteDelta,
		int countDelta
	) {
		String safeKey = key == null || key.length() == 0 ? fallbackKey : key;
		AtomicLong bytesRef = bytesByKey.get(safeKey);
		if (bytesRef == null) {
			if (byteDelta <= 0L && countDelta <= 0) return;
			AtomicLong created = new AtomicLong();
			AtomicLong existing = bytesByKey.putIfAbsent(safeKey, created);
			bytesRef = existing == null ? created : existing;
		}
		AtomicInteger countRef = countsByKey.get(safeKey);
		if (countRef == null) {
			if (byteDelta <= 0L && countDelta <= 0) return;
			AtomicInteger created = new AtomicInteger();
			AtomicInteger existing = countsByKey.putIfAbsent(safeKey, created);
			countRef = existing == null ? created : existing;
		}
		if (sample != null && sample.length() > 0) {
			samplesByKey.put(safeKey, sample);
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
			bytesByKey.remove(safeKey, bytesRef);
			countsByKey.remove(safeKey, countRef);
			samplesByKey.remove(safeKey);
		}
	}

	private static TextureAttribution resolveTextureAttribution (String sourcePath, String stackKey) {
		String groupKey = classifyLiveTextureSourceGroup(sourcePath, stackKey);
		String sourceSample = summarizeLiveTextureSampleSource(sourcePath, stackKey, groupKey);
		String ownerKey = classifyLiveTextureOwnerKey(groupKey, sourcePath, stackKey);
		String ownerSample = TextureOwnerSummary.summarizeOwnerSample(stackKey, sourceSample);
		return new TextureAttribution(groupKey, sourceSample, ownerKey, ownerSample);
	}

	private static String normalizeDebugWindowLabel (String label) {
		if (label == null) return null;
		String normalized = label.trim();
		return normalized.length() == 0 ? null : normalized;
	}

	private static TextureDebugWindowSnapshot captureDebugWindowSnapshot () {
		return new TextureDebugWindowSnapshot(
			TEXTURES_LIVE.get(),
			TEXTURES_CREATED.get(),
			TEXTURES_DISPOSED.get(),
			TEXTURE_UPLOAD_EVENTS.get(),
			TEXTURE_RELEASE_EVENTS.get(),
			TEXTURE_HANDLE_UPDATES.get(),
			TEXTURE_NATIVE_ESTIMATED_BYTES.get(),
			TEXTURE_UPLOAD_TOTAL_BYTES.get(),
			TEXTURE_RELEASE_TOTAL_BYTES.get(),
			snapshotLongMap(TEXTURE_LIVE_SOURCE_BYTES),
			snapshotIntMap(TEXTURE_LIVE_SOURCE_COUNTS),
			snapshotStringMap(TEXTURE_LIVE_SOURCE_SAMPLES),
			snapshotLongMap(TEXTURE_LIVE_OWNER_BYTES),
			snapshotIntMap(TEXTURE_LIVE_OWNER_COUNTS),
			snapshotStringMap(TEXTURE_LIVE_OWNER_SAMPLES),
			snapshotLongMap(TEXTURE_UPLOAD_SOURCE_BYTES),
			snapshotIntMap(TEXTURE_UPLOAD_SOURCE_COUNTS),
			snapshotStringMap(TEXTURE_UPLOAD_SOURCE_SAMPLES),
			snapshotLongMap(TEXTURE_UPLOAD_OWNER_BYTES),
			snapshotIntMap(TEXTURE_UPLOAD_OWNER_COUNTS),
			snapshotStringMap(TEXTURE_UPLOAD_OWNER_SAMPLES),
			snapshotLongMap(TEXTURE_RELEASE_SOURCE_BYTES),
			snapshotIntMap(TEXTURE_RELEASE_SOURCE_COUNTS),
			snapshotStringMap(TEXTURE_RELEASE_SOURCE_SAMPLES),
			snapshotLongMap(TEXTURE_RELEASE_OWNER_BYTES),
			snapshotIntMap(TEXTURE_RELEASE_OWNER_COUNTS),
			snapshotStringMap(TEXTURE_RELEASE_OWNER_SAMPLES)
		);
	}

	private static Map<String, Long> snapshotLongMap (ConcurrentHashMap<String, AtomicLong> source) {
		HashMap<String, Long> snapshot = new HashMap<String, Long>();
		for (Map.Entry<String, AtomicLong> entry : source.entrySet()) {
			AtomicLong value = entry.getValue();
			if (value == null) continue;
			long bytes = value.get();
			if (bytes <= 0L) continue;
			snapshot.put(entry.getKey(), Long.valueOf(bytes));
		}
		return snapshot;
	}

	private static Map<String, Integer> snapshotIntMap (ConcurrentHashMap<String, AtomicInteger> source) {
		HashMap<String, Integer> snapshot = new HashMap<String, Integer>();
		for (Map.Entry<String, AtomicInteger> entry : source.entrySet()) {
			AtomicInteger value = entry.getValue();
			if (value == null) continue;
			int count = value.get();
			if (count <= 0) continue;
			snapshot.put(entry.getKey(), Integer.valueOf(count));
		}
		return snapshot;
	}

	private static Map<String, String> snapshotStringMap (ConcurrentHashMap<String, String> source) {
		return new HashMap<String, String>(source);
	}

	private static String buildDebugWindowSummary (
		TextureDebugWindowSnapshot startSnapshot,
		TextureDebugWindowSnapshot endSnapshot
	) {
		if (startSnapshot == null || endSnapshot == null) return "unavailable";
		StringBuilder builder = new StringBuilder(512);
		builder.append("live=").append(formatSignedInt(endSnapshot.liveTextures - startSnapshot.liveTextures));
		builder.append(",bytes=").append(formatSignedMegabytes(endSnapshot.liveBytes - startSnapshot.liveBytes)).append("m");
		builder.append(",created=").append(formatSignedInt(endSnapshot.createdTextures - startSnapshot.createdTextures));
		builder.append(",disposed=").append(formatSignedInt(endSnapshot.disposedTextures - startSnapshot.disposedTextures));
		builder.append(",uploads=").append(formatSignedInt(endSnapshot.uploadEvents - startSnapshot.uploadEvents));
		builder.append(",uploadBytes=").append(formatSignedMegabytes(endSnapshot.uploadBytes - startSnapshot.uploadBytes)).append("m");
		builder.append(",releases=").append(formatSignedInt(endSnapshot.releaseEvents - startSnapshot.releaseEvents));
		builder.append(",releaseBytes=").append(formatSignedMegabytes(endSnapshot.releaseBytes - startSnapshot.releaseBytes)).append("m");
		builder.append(",handleUpdates=").append(formatSignedInt(endSnapshot.handleUpdates - startSnapshot.handleUpdates));

		String liveSources = buildDeltaAggregateSummary(
			startSnapshot.liveSourceBytes,
			startSnapshot.liveSourceCounts,
			endSnapshot.liveSourceBytes,
			endSnapshot.liveSourceCounts,
			endSnapshot.liveSourceSamples
		);
		String liveOwners = buildDeltaAggregateSummary(
			startSnapshot.liveOwnerBytes,
			startSnapshot.liveOwnerCounts,
			endSnapshot.liveOwnerBytes,
			endSnapshot.liveOwnerCounts,
			endSnapshot.liveOwnerSamples
		);
		String uploadSources = buildDeltaAggregateSummary(
			startSnapshot.uploadSourceBytes,
			startSnapshot.uploadSourceCounts,
			endSnapshot.uploadSourceBytes,
			endSnapshot.uploadSourceCounts,
			endSnapshot.uploadSourceSamples
		);
		String uploadOwners = buildDeltaAggregateSummary(
			startSnapshot.uploadOwnerBytes,
			startSnapshot.uploadOwnerCounts,
			endSnapshot.uploadOwnerBytes,
			endSnapshot.uploadOwnerCounts,
			endSnapshot.uploadOwnerSamples
		);
		String releaseSources = buildDeltaAggregateSummary(
			startSnapshot.releaseSourceBytes,
			startSnapshot.releaseSourceCounts,
			endSnapshot.releaseSourceBytes,
			endSnapshot.releaseSourceCounts,
			endSnapshot.releaseSourceSamples
		);
		String releaseOwners = buildDeltaAggregateSummary(
			startSnapshot.releaseOwnerBytes,
			startSnapshot.releaseOwnerCounts,
			endSnapshot.releaseOwnerBytes,
			endSnapshot.releaseOwnerCounts,
			endSnapshot.releaseOwnerSamples
		);

		if (!"none".equals(liveSources)) {
			builder.append(",liveSources=").append(liveSources);
		}
		if (!"none".equals(liveOwners)) {
			builder.append(",liveOwners=").append(liveOwners);
		}
		if (!"none".equals(uploadSources)) {
			builder.append(",uploadSources=").append(uploadSources);
		}
		if (!"none".equals(uploadOwners)) {
			builder.append(",uploadOwners=").append(uploadOwners);
		}
		if (!"none".equals(releaseSources)) {
			builder.append(",releaseSources=").append(releaseSources);
		}
		if (!"none".equals(releaseOwners)) {
			builder.append(",releaseOwners=").append(releaseOwners);
		}
		return builder.toString();
	}

	private static String buildDeltaAggregateSummary (
		Map<String, Long> startBytes,
		Map<String, Integer> startCounts,
		Map<String, Long> endBytes,
		Map<String, Integer> endCounts,
		Map<String, String> endSamples
	) {
		String[] topKeys = new String[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		String[] topSamples = new String[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		long[] topBytes = new long[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		int[] topCounts = new int[TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT];
		int topSize = 0;
		int totalGroups = 0;
		for (Map.Entry<String, Long> entry : endBytes.entrySet()) {
			String key = entry.getKey();
			long endByteValue = entry.getValue() == null ? 0L : entry.getValue().longValue();
			long startByteValue = 0L;
			if (startBytes != null) {
				Long startValue = startBytes.get(key);
				startByteValue = startValue == null ? 0L : startValue.longValue();
			}
			long byteDelta = endByteValue - startByteValue;
			int endCountValue = 0;
			Integer endCountRef = endCounts == null ? null : endCounts.get(key);
			if (endCountRef != null) {
				endCountValue = endCountRef.intValue();
			}
			int startCountValue = 0;
			Integer startCountRef = startCounts == null ? null : startCounts.get(key);
			if (startCountRef != null) {
				startCountValue = startCountRef.intValue();
			}
			int countDelta = endCountValue - startCountValue;
			if (byteDelta <= 0L && countDelta <= 0) continue;
			totalGroups++;
			int insertAt = -1;
			for (int i = 0; i < topSize; i++) {
				if (ranksBeforeLiveTextureSummary(byteDelta, countDelta, key, topBytes[i], topCounts[i], topKeys[i])) {
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
				topKeys[i] = topKeys[i - 1];
				topSamples[i] = topSamples[i - 1];
				topBytes[i] = topBytes[i - 1];
				topCounts[i] = topCounts[i - 1];
			}
			topKeys[insertAt] = key;
			topSamples[insertAt] = endSamples == null ? null : endSamples.get(key);
			topBytes[insertAt] = byteDelta;
			topCounts[insertAt] = countDelta;
			if (topSize < TEXTURE_LIVE_SOURCE_SUMMARY_LIMIT) {
				topSize++;
			}
		}
		if (topSize == 0) return "none";
		StringBuilder builder = new StringBuilder(192);
		for (int i = 0; i < topSize; i++) {
			if (i > 0) builder.append("|");
			builder.append(topKeys[i])
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

	private static String formatSignedInt (int value) {
		return value > 0 ? "+" + value : String.valueOf(value);
	}

	private static long toSignedSummaryMegabytes (long bytes) {
		if (bytes == 0L) return 0L;
		long absoluteBytes = bytes < 0L ? -bytes : bytes;
		long summaryMegabytes = toSummaryMegabytes(absoluteBytes);
		return bytes < 0L ? -summaryMegabytes : summaryMegabytes;
	}

	private static String formatSignedMegabytes (long bytes) {
		long summaryMegabytes = toSignedSummaryMegabytes(bytes);
		return summaryMegabytes > 0L ? "+" + summaryMegabytes : String.valueOf(summaryMegabytes);
	}

	private static long toSummaryMegabytes (long bytes) {
		if (bytes <= 0L) return 0L;
		return (bytes + (1024L * 1024L) - 1L) / (1024L * 1024L);
	}

	private static int getCurrentTextureBinding (int target) {
		int bindingQuery = getTextureBindingQueryEnum(target);
		if (bindingQuery == 0) return 0;
		IntBuffer intbuf = GL_INT_QUERY_BUFFER.get();
		intbuf.clear();
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

	private static final class TextureAttribution {
		private final String groupKey;
		private final String sourceSample;
		private final String ownerKey;
		private final String ownerSample;

		private TextureAttribution (String groupKey, String sourceSample, String ownerKey, String ownerSample) {
			this.groupKey = groupKey;
			this.sourceSample = sourceSample;
			this.ownerKey = ownerKey;
			this.ownerSample = ownerSample;
		}
	}

	private static final class TextureDebugWindowSnapshot {
		private final int liveTextures;
		private final int createdTextures;
		private final int disposedTextures;
		private final int uploadEvents;
		private final int releaseEvents;
		private final int handleUpdates;
		private final long liveBytes;
		private final long uploadBytes;
		private final long releaseBytes;
		private final Map<String, Long> liveSourceBytes;
		private final Map<String, Integer> liveSourceCounts;
		private final Map<String, String> liveSourceSamples;
		private final Map<String, Long> liveOwnerBytes;
		private final Map<String, Integer> liveOwnerCounts;
		private final Map<String, String> liveOwnerSamples;
		private final Map<String, Long> uploadSourceBytes;
		private final Map<String, Integer> uploadSourceCounts;
		private final Map<String, String> uploadSourceSamples;
		private final Map<String, Long> uploadOwnerBytes;
		private final Map<String, Integer> uploadOwnerCounts;
		private final Map<String, String> uploadOwnerSamples;
		private final Map<String, Long> releaseSourceBytes;
		private final Map<String, Integer> releaseSourceCounts;
		private final Map<String, String> releaseSourceSamples;
		private final Map<String, Long> releaseOwnerBytes;
		private final Map<String, Integer> releaseOwnerCounts;
		private final Map<String, String> releaseOwnerSamples;

		private TextureDebugWindowSnapshot (
			int liveTextures,
			int createdTextures,
			int disposedTextures,
			int uploadEvents,
			int releaseEvents,
			int handleUpdates,
			long liveBytes,
			long uploadBytes,
			long releaseBytes,
			Map<String, Long> liveSourceBytes,
			Map<String, Integer> liveSourceCounts,
			Map<String, String> liveSourceSamples,
			Map<String, Long> liveOwnerBytes,
			Map<String, Integer> liveOwnerCounts,
			Map<String, String> liveOwnerSamples,
			Map<String, Long> uploadSourceBytes,
			Map<String, Integer> uploadSourceCounts,
			Map<String, String> uploadSourceSamples,
			Map<String, Long> uploadOwnerBytes,
			Map<String, Integer> uploadOwnerCounts,
			Map<String, String> uploadOwnerSamples,
			Map<String, Long> releaseSourceBytes,
			Map<String, Integer> releaseSourceCounts,
			Map<String, String> releaseSourceSamples,
			Map<String, Long> releaseOwnerBytes,
			Map<String, Integer> releaseOwnerCounts,
			Map<String, String> releaseOwnerSamples
		) {
			this.liveTextures = liveTextures;
			this.createdTextures = createdTextures;
			this.disposedTextures = disposedTextures;
			this.uploadEvents = uploadEvents;
			this.releaseEvents = releaseEvents;
			this.handleUpdates = handleUpdates;
			this.liveBytes = liveBytes;
			this.uploadBytes = uploadBytes;
			this.releaseBytes = releaseBytes;
			this.liveSourceBytes = liveSourceBytes;
			this.liveSourceCounts = liveSourceCounts;
			this.liveSourceSamples = liveSourceSamples;
			this.liveOwnerBytes = liveOwnerBytes;
			this.liveOwnerCounts = liveOwnerCounts;
			this.liveOwnerSamples = liveOwnerSamples;
			this.uploadSourceBytes = uploadSourceBytes;
			this.uploadSourceCounts = uploadSourceCounts;
			this.uploadSourceSamples = uploadSourceSamples;
			this.uploadOwnerBytes = uploadOwnerBytes;
			this.uploadOwnerCounts = uploadOwnerCounts;
			this.uploadOwnerSamples = uploadOwnerSamples;
			this.releaseSourceBytes = releaseSourceBytes;
			this.releaseSourceCounts = releaseSourceCounts;
			this.releaseSourceSamples = releaseSourceSamples;
			this.releaseOwnerBytes = releaseOwnerBytes;
			this.releaseOwnerCounts = releaseOwnerCounts;
			this.releaseOwnerSamples = releaseOwnerSamples;
		}
	}

	private static final class TextureResidencySweepResult {
		private final String kind;
		private final int sweepIndex;
		private final long bytesBefore;
		private final long bytesAfter;
		private final int reclaimedCount;
		private final long reclaimedBytes;
		private final int candidateCount;
		private final String topOwnersBefore;
		private final String topOwnersAfter;
		private final long lowWaterBytes;
		private final long triggerBytes;

		private TextureResidencySweepResult (
			String kind,
			int sweepIndex,
			long bytesBefore,
			long bytesAfter,
			int reclaimedCount,
			long reclaimedBytes,
			int candidateCount,
			String topOwnersBefore,
			String topOwnersAfter,
			long lowWaterBytes,
			long triggerBytes
		) {
			this.kind = kind;
			this.sweepIndex = sweepIndex;
			this.bytesBefore = bytesBefore;
			this.bytesAfter = bytesAfter;
			this.reclaimedCount = reclaimedCount;
			this.reclaimedBytes = reclaimedBytes;
			this.candidateCount = candidateCount;
			this.topOwnersBefore = topOwnersBefore;
			this.topOwnersAfter = topOwnersAfter;
			this.lowWaterBytes = lowWaterBytes;
			this.triggerBytes = triggerBytes;
		}
	}

}
