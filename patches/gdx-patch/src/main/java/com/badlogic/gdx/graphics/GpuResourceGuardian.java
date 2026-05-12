package com.badlogic.gdx.graphics;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;

/**
 * Low-overhead tracked GPU resource guardian.
 *
 * Normal frames only read aggregate counters. Reclaim work is budgeted, cursor based, and limited to
 * resources that libGDX can track and safely recreate.
 */
public final class GpuResourceGuardian {
	private static final String MODE_PROP = "amethyst.gdx.gpu_resource_guardian";
	private static final String SOFT_BUDGET_PROP = "amethyst.gdx.gpu_guardian_soft_budget_bytes";
	private static final String HARD_BUDGET_PROP = "amethyst.gdx.gpu_guardian_hard_budget_bytes";
	private static final String WATCH_GROWTH_PROP = "amethyst.gdx.gpu_guardian_watch_growth_bytes";
	private static final String PRESSURE_GROWTH_PROP = "amethyst.gdx.gpu_guardian_pressure_growth_bytes";
	private static final String SWEEP_INTERVAL_PROP = "amethyst.gdx.gpu_guardian_sweep_interval_frames";
	private static final String COOLDOWN_PROP = "amethyst.gdx.gpu_guardian_cooldown_frames";
	private static final String TEXTURE_MIN_IDLE_PROP = "amethyst.gdx.gpu_guardian_texture_min_idle_frames";
	private static final String TEXTURE_MIN_BYTES_PROP = "amethyst.gdx.gpu_guardian_texture_min_bytes";
	private static final String TEXTURE_MAX_CHECKS_PROP = "amethyst.gdx.gpu_guardian_texture_max_checks_per_sweep";
	private static final String TEXTURE_MAX_RECLAIMS_PROP = "amethyst.gdx.gpu_guardian_texture_max_reclaims_per_sweep";
	private static final String TEXTURE_MAX_BYTES_PROP = "amethyst.gdx.gpu_guardian_texture_max_bytes_per_sweep";
	private static final String FBO_MIN_IDLE_PROP = "amethyst.gdx.gpu_guardian_fbo_min_idle_frames";
	private static final String FBO_MIN_BYTES_PROP = "amethyst.gdx.gpu_guardian_fbo_min_bytes";
	private static final String FBO_MAX_CHECKS_PROP = "amethyst.gdx.gpu_guardian_fbo_max_checks_per_sweep";
	private static final String FBO_MAX_RECLAIMS_PROP = "amethyst.gdx.gpu_guardian_fbo_max_reclaims_per_sweep";
	private static final String FBO_MAX_BYTES_PROP = "amethyst.gdx.gpu_guardian_fbo_max_bytes_per_sweep";
	private static final String DIAG_PROP = "amethyst.gdx.gpu_resource_diag";

	private static final Mode MODE = Mode.fromProperty(System.getProperty(MODE_PROP));
	private static final boolean DIAGNOSTIC_LOGS = MODE == Mode.DIAGNOSTIC || readBooleanSystemProperty(DIAG_PROP, false);
	private static final long SOFT_BUDGET_BYTES = readLongSystemProperty(SOFT_BUDGET_PROP, defaultSoftBudgetBytes(), 64L * 1024L * 1024L, Long.MAX_VALUE);
	private static final long HARD_BUDGET_BYTES = readLongSystemProperty(HARD_BUDGET_PROP, defaultHardBudgetBytes(), SOFT_BUDGET_BYTES, Long.MAX_VALUE);
	private static final long WATCH_GROWTH_BYTES = readLongSystemProperty(WATCH_GROWTH_PROP, defaultWatchGrowthBytes(), 0L, Long.MAX_VALUE);
	private static final long PRESSURE_GROWTH_BYTES = readLongSystemProperty(PRESSURE_GROWTH_PROP, defaultPressureGrowthBytes(), 0L, Long.MAX_VALUE);
	private static final int SWEEP_INTERVAL_FRAMES = readIntSystemProperty(SWEEP_INTERVAL_PROP, defaultSweepIntervalFrames(), 1, 36000);
	private static final int COOLDOWN_FRAMES = readIntSystemProperty(COOLDOWN_PROP, defaultCooldownFrames(), 0, 360000);
	private static final int TEXTURE_MIN_IDLE_FRAMES = readIntSystemProperty(TEXTURE_MIN_IDLE_PROP, defaultTextureMinIdleFrames(), 1, 360000);
	private static final long TEXTURE_MIN_BYTES = readLongSystemProperty(TEXTURE_MIN_BYTES_PROP, 4L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final int TEXTURE_MAX_CHECKS = readIntSystemProperty(TEXTURE_MAX_CHECKS_PROP, defaultTextureMaxChecks(), 1, 2048);
	private static final int TEXTURE_MAX_RECLAIMS = readIntSystemProperty(TEXTURE_MAX_RECLAIMS_PROP, defaultTextureMaxReclaims(), 1, 64);
	private static final long TEXTURE_MAX_BYTES = readLongSystemProperty(TEXTURE_MAX_BYTES_PROP, defaultTextureMaxBytes(), 0L, Long.MAX_VALUE);
	private static final int FBO_MIN_IDLE_FRAMES = readIntSystemProperty(FBO_MIN_IDLE_PROP, defaultFboMinIdleFrames(), 1, 360000);
	private static final long FBO_MIN_BYTES = readLongSystemProperty(FBO_MIN_BYTES_PROP, 4L * 1024L * 1024L, 0L, Long.MAX_VALUE);
	private static final int FBO_MAX_CHECKS = readIntSystemProperty(FBO_MAX_CHECKS_PROP, defaultFboMaxChecks(), 1, 2048);
	private static final int FBO_MAX_RECLAIMS = readIntSystemProperty(FBO_MAX_RECLAIMS_PROP, defaultFboMaxReclaims(), 1, 64);
	private static final long FBO_MAX_BYTES = readLongSystemProperty(FBO_MAX_BYTES_PROP, defaultFboMaxBytes(), 0L, Long.MAX_VALUE);

	private static volatile State state = State.OFF;
	private static volatile long lowWaterBytes = -1L;
	private static volatile long lastSweepFrame = -1L;
	private static volatile long cooldownUntilFrame = -1L;
	private static volatile long transitions;
	private static volatile long sweeps;
	private static volatile long textureReclaims;
	private static volatile long textureReclaimedBytes;
	private static volatile long fboReclaims;
	private static volatile long fboReclaimedBytes;
	private static volatile long lastCombinedBytes;
	private static volatile String lastReason = "init";
	private static boolean enabledLogged;

	private GpuResourceGuardian () {
	}

	public static void afterRender (Application app, long frameId) {
		if (MODE == Mode.OFF || app == null || frameId < 0L || Gdx.gl == null) {
			transitionTo(State.OFF, frameId, "disabled");
			return;
		}
		logEnabledOnce();
		long textureBytes = GLTexture.getEstimatedNativeBytes();
		long fboBytes = GLFrameBuffer.getEstimatedNativeBytes();
		long combinedBytes = safeAdd(textureBytes, fboBytes);
		lastCombinedBytes = combinedBytes;
		if (lowWaterBytes < 0L || combinedBytes < lowWaterBytes) {
			lowWaterBytes = combinedBytes;
		}
		State nextState = resolveState(combinedBytes, lowWaterBytes);
		transitionTo(nextState, frameId, "trackedBytes=" + combinedBytes + ",lowWater=" + lowWaterBytes);
		if (nextState == State.NORMAL || nextState == State.WATCH || nextState == State.OFF) return;
		if (cooldownUntilFrame >= 0L && frameId < cooldownUntilFrame) return;
		if (lastSweepFrame >= 0L && frameId - lastSweepFrame < SWEEP_INTERVAL_FRAMES) return;

		lastSweepFrame = frameId;
		sweeps++;
		long[] fboResult = GLFrameBuffer.reclaimGuardianFrameBuffers(
			app,
			frameId,
			FBO_MIN_IDLE_FRAMES,
			FBO_MIN_BYTES,
			FBO_MAX_CHECKS,
			FBO_MAX_RECLAIMS,
			FBO_MAX_BYTES
		);
		long[] textureResult = GLTexture.reclaimGuardianTextures(
			app,
			frameId,
			TEXTURE_MIN_IDLE_FRAMES,
			TEXTURE_MIN_BYTES,
			TEXTURE_MAX_CHECKS,
			TEXTURE_MAX_RECLAIMS,
			TEXTURE_MAX_BYTES
		);
		long fboChecked = sweepChecked(fboResult);
		long fboCandidates = sweepCandidates(fboResult);
		long fboReclaimed = sweepReclaimed(fboResult);
		long fboBytesReclaimed = sweepReclaimedBytes(fboResult);
		long textureChecked = sweepChecked(textureResult);
		long textureCandidates = sweepCandidates(textureResult);
		long textureReclaimed = sweepReclaimed(textureResult);
		long textureBytesReclaimed = sweepReclaimedBytes(textureResult);
		fboReclaims += fboReclaimed;
		fboReclaimedBytes += fboBytesReclaimed;
		textureReclaims += textureReclaimed;
		textureReclaimedBytes += textureBytesReclaimed;
		if (fboReclaimed > 0 || textureReclaimed > 0) {
			lowWaterBytes = Math.min(lowWaterBytes, safeAdd(GLTexture.getEstimatedNativeBytes(), GLFrameBuffer.getEstimatedNativeBytes()));
			cooldownUntilFrame = frameId + COOLDOWN_FRAMES;
		} else if (nextState == State.EMERGENCY) {
			cooldownUntilFrame = frameId + Math.max(1, COOLDOWN_FRAMES / 2);
		}
		if (DIAGNOSTIC_LOGS || fboReclaimed > 0 || textureReclaimed > 0) {
			System.out.println("[gdx-diag] GpuResourceGuardian sweep frame=" + frameId
				+ " mode=" + MODE.propertyValue
				+ " state=" + state.name().toLowerCase()
				+ " trackedBytesBefore=" + combinedBytes
				+ " trackedBytesAfter=" + safeAdd(GLTexture.getEstimatedNativeBytes(), GLFrameBuffer.getEstimatedNativeBytes())
				+ " textureChecked=" + textureChecked
				+ " textureCandidates=" + textureCandidates
				+ " textureReclaimed=" + textureReclaimed
				+ " textureReclaimedBytes=" + textureBytesReclaimed
				+ " fboChecked=" + fboChecked
				+ " fboCandidates=" + fboCandidates
				+ " fboReclaimed=" + fboReclaimed
				+ " fboReclaimedBytes=" + fboBytesReclaimed);
		}
	}

	public static String getDebugStatusSummary () {
		return "guardianMode=" + MODE.propertyValue
			+ " guardianState=" + state.name().toLowerCase()
			+ " guardianTrackedBytes=" + lastCombinedBytes
			+ " guardianLowWaterBytes=" + Math.max(0L, lowWaterBytes)
			+ " guardianSoftBudgetBytes=" + SOFT_BUDGET_BYTES
			+ " guardianHardBudgetBytes=" + HARD_BUDGET_BYTES
			+ " guardianSweeps=" + sweeps
			+ " guardianTextureReclaims=" + textureReclaims
			+ " guardianTextureBytes=" + textureReclaimedBytes
			+ " guardianFboReclaims=" + fboReclaims
			+ " guardianFboBytes=" + fboReclaimedBytes
			+ " guardianTransitions=" + transitions
			+ " guardianLastReason=" + sanitizeSummaryValue(lastReason);
	}

	private static State resolveState (long combinedBytes, long lowWater) {
		if (combinedBytes >= HARD_BUDGET_BYTES) return State.EMERGENCY;
		if (combinedBytes >= SOFT_BUDGET_BYTES) return State.PRESSURE;
		if (lowWater >= 0L && PRESSURE_GROWTH_BYTES > 0L && combinedBytes >= safeAdd(lowWater, PRESSURE_GROWTH_BYTES)) {
			return State.PRESSURE;
		}
		if (lowWater >= 0L && WATCH_GROWTH_BYTES > 0L && combinedBytes >= safeAdd(lowWater, WATCH_GROWTH_BYTES)) {
			return State.WATCH;
		}
		return State.NORMAL;
	}

	private static void transitionTo (State next, long frameId, String reason) {
		if (state == next) return;
		State previous = state;
		state = next;
		transitions++;
		lastReason = reason == null ? "unknown" : reason;
		if (DIAGNOSTIC_LOGS || next == State.PRESSURE || next == State.EMERGENCY) {
			System.out.println("[gdx-diag] GpuResourceGuardian state_change frame=" + frameId
				+ " mode=" + MODE.propertyValue
				+ " from=" + previous.name().toLowerCase()
				+ " to=" + next.name().toLowerCase()
				+ " " + getDebugStatusSummary());
		}
	}

	private static void logEnabledOnce () {
		if (enabledLogged) return;
		enabledLogged = true;
		if (DIAGNOSTIC_LOGS) {
			System.out.println("[gdx-diag] GpuResourceGuardian enabled mode=" + MODE.propertyValue
				+ " softBudgetBytes=" + SOFT_BUDGET_BYTES
				+ " hardBudgetBytes=" + HARD_BUDGET_BYTES
				+ " note=tracked_resources_only");
		}
	}

	private static String sanitizeSummaryValue (String value) {
		if (value == null || value.length() == 0) return "none";
		return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
	}

	private static long sweepChecked (long[] result) {
		return sweepValue(result, 0);
	}

	private static long sweepCandidates (long[] result) {
		return sweepValue(result, 1);
	}

	private static long sweepReclaimed (long[] result) {
		return sweepValue(result, 2);
	}

	private static long sweepReclaimedBytes (long[] result) {
		return sweepValue(result, 3);
	}

	private static long sweepValue (long[] result, int index) {
		if (result == null || index < 0 || index >= result.length) return 0L;
		return Math.max(0L, result[index]);
	}

	private static boolean isAggressiveMode () {
		return MODE == Mode.AGGRESSIVE;
	}

	private static long defaultSoftBudgetBytes () {
		return isAggressiveMode() ? 640L * 1024L * 1024L : 768L * 1024L * 1024L;
	}

	private static long defaultHardBudgetBytes () {
		return isAggressiveMode() ? 896L * 1024L * 1024L : 1024L * 1024L * 1024L;
	}

	private static long defaultWatchGrowthBytes () {
		return isAggressiveMode() ? 96L * 1024L * 1024L : 128L * 1024L * 1024L;
	}

	private static long defaultPressureGrowthBytes () {
		return isAggressiveMode() ? 160L * 1024L * 1024L : 256L * 1024L * 1024L;
	}

	private static int defaultSweepIntervalFrames () {
		return isAggressiveMode() ? 60 : 120;
	}

	private static int defaultCooldownFrames () {
		return isAggressiveMode() ? 180 : 300;
	}

	private static int defaultTextureMinIdleFrames () {
		return isAggressiveMode() ? 600 : 900;
	}

	private static int defaultTextureMaxChecks () {
		return isAggressiveMode() ? 48 : 24;
	}

	private static int defaultTextureMaxReclaims () {
		return isAggressiveMode() ? 4 : 2;
	}

	private static long defaultTextureMaxBytes () {
		return isAggressiveMode() ? 128L * 1024L * 1024L : 64L * 1024L * 1024L;
	}

	private static int defaultFboMinIdleFrames () {
		return isAggressiveMode() ? 180 : 300;
	}

	private static int defaultFboMaxChecks () {
		return isAggressiveMode() ? 32 : 16;
	}

	private static int defaultFboMaxReclaims () {
		return isAggressiveMode() ? 2 : 1;
	}

	private static long defaultFboMaxBytes () {
		return isAggressiveMode() ? 64L * 1024L * 1024L : 32L * 1024L * 1024L;
	}

	private static long safeAdd (long left, long right) {
		if (left >= Long.MAX_VALUE - right) return Long.MAX_VALUE;
		return left + right;
	}

	private static boolean readBooleanSystemProperty (String key, boolean defaultValue) {
		String configured = System.getProperty(key);
		if (configured == null) return defaultValue;
		configured = configured.trim();
		if (configured.length() == 0) return defaultValue;
		if ("false".equalsIgnoreCase(configured) || "0".equals(configured) || "off".equalsIgnoreCase(configured)) return false;
		if ("true".equalsIgnoreCase(configured) || "1".equals(configured) || "on".equalsIgnoreCase(configured)) return true;
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

	private enum Mode {
		OFF("off"),
		SAFE("safe"),
		AGGRESSIVE("aggressive"),
		DIAGNOSTIC("diagnostic");

		final String propertyValue;

		Mode (String propertyValue) {
			this.propertyValue = propertyValue;
		}

		static Mode fromProperty (String value) {
			if (value == null || value.trim().length() == 0) return SAFE;
			String normalized = value.trim().toLowerCase();
			for (Mode mode : values()) {
				if (mode.propertyValue.equals(normalized)) return mode;
			}
			return SAFE;
		}
	}

	private enum State {
		OFF,
		NORMAL,
		WATCH,
		PRESSURE,
		EMERGENCY
	}
}
