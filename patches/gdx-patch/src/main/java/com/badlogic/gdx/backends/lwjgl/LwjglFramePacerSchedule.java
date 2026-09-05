package com.badlogic.gdx.backends.lwjgl;

/**
 * Pure frame-schedule arithmetic for the software frame pacer in {@link LwjglApplication}.
 *
 * <p>Extracted so the schedule can be exercised without a GL context or a real clock. The pacer's
 * only job is to decide the absolute nanotime at which the next frame should be presented; the
 * sleeping itself stays in {@code LwjglApplication#sleepUntilFrameDeadline}.
 *
 * <p>The contract deliberately mirrors LWJGL2's {@code Sync.sync()}, which computes
 * {@code nextFrame = Math.max(nextFrame + period, getTime())}. The {@code max} is load-bearing:
 * it is what stops an over-budget frame from leaving timing debt on the schedule.
 */
final class LwjglFramePacerSchedule {
	static final long NANOS_PER_SECOND = 1000000000L;

	private LwjglFramePacerSchedule () {
	}

	/** Nanoseconds budgeted per frame for {@code frameRate}, floored at 1ns. */
	static long frameNanos (int frameRate) {
		return Math.max(1L, NANOS_PER_SECOND / frameRate);
	}

	/** Nanoseconds budgeted per frame for a fractional rate, rounded to the nearest nanosecond. */
	static long frameNanos (double frameRate) {
		return Math.max(1L, Math.round(NANOS_PER_SECOND / frameRate));
	}

	/**
	 * Caps a target frame rate at the display's active refresh rate.
	 *
	 * <p>This backend runs with swap-interval pacing disabled, so the software pacer is the only frame
	 * limiter. Targeting above the panel's rate cannot produce extra presented frames; it only puts the
	 * pacer's period out of step with the display period, and the two then beat against each other.
	 *
	 * @param refreshRate the active refresh rate, or a non-positive value when it is unknown, in which
	 *     case {@code frameRate} is returned unchanged rather than guessed at.
	 */
	static int capToRefreshRate (int frameRate, int refreshRate) {
		if (refreshRate <= 0) return frameRate;
		return Math.min(frameRate, refreshRate);
	}

	/**
	 * Whether the schedule has to be seeded from the current time.
	 *
	 * <p>True only when no schedule exists yet or the target rate changed. Notably there is no
	 * "drifted too far, resynchronize" case: {@link #advance} can never leave the deadline more than
	 * one frame's overrun behind, so a drift trigger can only fire on a frame that legitimately ran
	 * long and would discard the catch-up that {@link #advance} exists to perform.
	 */
	static boolean shouldSeed (int previousFrameRate, int frameRate, long scheduledDeadlineNanos) {
		return previousFrameRate != frameRate || scheduledDeadlineNanos <= 0L;
	}

	/** The deadline for the first frame after seeding at {@code nowNanos}. */
	static long seed (long nowNanos, long frameNanos) {
		return nowNanos + frameNanos;
	}

	/**
	 * The deadline for the frame after the one that was paced to {@code deadlineNanos}.
	 *
	 * <p>Advances by exactly one period, then clamps to {@code afterWaitNanos} so that an overrun is
	 * absorbed in a single step instead of accumulating. Without the clamp the pacer keeps handing out
	 * deadlines that have already elapsed, so it stops waiting at all until the accumulated debt trips
	 * a resynchronization, and that resynchronization drops a frame. On a panel presenting every
	 * 16.7ms with a 90 FPS target this produced a fixed 16.7/16.7/16.7/27.8ms beat, where the 27.8ms
	 * interval spans two vblanks and the display rescans the previous frame.
	 */
	static long advance (long deadlineNanos, long afterWaitNanos, long frameNanos) {
		return Math.max(deadlineNanos + frameNanos, afterWaitNanos);
	}
}
