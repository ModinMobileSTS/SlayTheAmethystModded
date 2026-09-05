package com.badlogic.gdx.backends.lwjgl;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the software frame pacer schedule.
 *
 * <p>These exist because of a v1.5.4 regression: the pacer advanced its deadline by exactly one
 * period without clamping to the current time, so an over-budget frame left timing debt behind. The
 * debt made the pacer stop waiting until it crossed a resynchronization threshold, and the
 * resynchronization dropped a frame. Players saw the display periodically rescan the previous frame.
 */
public class LwjglFramePacerScheduleTest {

	/** The reference implementation this schedule has to match: LWJGL2 {@code Sync.sync()} computes
	 * {@code nextFrame = Math.max(nextFrame + period, getTime())}. */
	private static long lwjglNextFrame (long nextFrameNanos, long nowNanos, long periodNanos) {
		return Math.max(nextFrameNanos + periodNanos, nowNanos);
	}

	@Test
	public void frameNanos_matchesTheTargetPeriod () {
		assertEquals(16666666L, LwjglFramePacerSchedule.frameNanos(60));
		assertEquals(11111111L, LwjglFramePacerSchedule.frameNanos(90));
		assertEquals(8333333L, LwjglFramePacerSchedule.frameNanos(120));
	}

	@Test
	public void frameNanos_supportsFractionalRatesForExactDisplayDivisors () {
		assertEquals(12121212L, LwjglFramePacerSchedule.frameNanos(82.5));
	}

	@Test
	public void frameNanos_neverReturnsZero () {
		// A zero period would make every deadline equal to "now" and disable pacing entirely.
		assertTrue(LwjglFramePacerSchedule.frameNanos(Integer.MAX_VALUE) >= 1L);
	}

	@Test
	public void advance_absorbsAnOverrunInOneStepInsteadOfAccumulatingDebt () {
		long period = LwjglFramePacerSchedule.frameNanos(90);
		long deadline = 1000L * period;
		// The frame finished a full period past its deadline.
		long afterWait = deadline + period;

		long next = LwjglFramePacerSchedule.advance(deadline, afterWait, period);

		// The overrun is absorbed: the next deadline is in the future, not already elapsed.
		assertTrue("next deadline must not be in the past", next >= afterWait);
		assertEquals(lwjglNextFrame(deadline, afterWait, period), next);
	}

	@Test
	public void advance_keepsASteadyCadenceWhenFramesFinishEarly () {
		long period = LwjglFramePacerSchedule.frameNanos(90);
		long deadline = 500L * period;
		// Woke up essentially on time, as it does when the pacer actually slept.
		long afterWait = deadline + 1000L;

		assertEquals(deadline + period, LwjglFramePacerSchedule.advance(deadline, afterWait, period));
	}

	@Test
	public void advance_agreesWithLwjglSyncAcrossOverrunSizes () {
		long period = LwjglFramePacerSchedule.frameNanos(90);
		long deadline = 42L * period;
		for (long overrun : new long[] {0L, 1L, period / 2, period, period * 2, period * 10}) {
			long afterWait = deadline + overrun;
			assertEquals("overrun=" + overrun,
				lwjglNextFrame(deadline, afterWait, period),
				LwjglFramePacerSchedule.advance(deadline, afterWait, period));
		}
	}

	@Test
	public void capToRefreshRate_limitsTheTargetToThePanel () {
		// The launcher's default target is 90 FPS; a 60Hz panel must pace on the 60Hz period.
		assertEquals(60, LwjglFramePacerSchedule.capToRefreshRate(90, 60));
	}

	@Test
	public void capToRefreshRate_leavesTheTargetAloneWhenThePanelCanKeepUp () {
		assertEquals(90, LwjglFramePacerSchedule.capToRefreshRate(90, 90));
		assertEquals(90, LwjglFramePacerSchedule.capToRefreshRate(90, 120));
	}

	@Test
	public void capToRefreshRate_doesNotGuessWhenTheRefreshRateIsUnknown () {
		// resolveActiveRefreshRate() returns -1 when nothing trustworthy is known.
		assertEquals(90, LwjglFramePacerSchedule.capToRefreshRate(90, -1));
		assertEquals(90, LwjglFramePacerSchedule.capToRefreshRate(90, 0));
	}

	@Test
	public void shouldSeed_onlyWhenUnseededOrTheTargetChanged () {
		assertTrue("no schedule yet", LwjglFramePacerSchedule.shouldSeed(0, 90, 0L));
		assertTrue("target changed", LwjglFramePacerSchedule.shouldSeed(60, 90, 12345L));
		assertFalse("steady state", LwjglFramePacerSchedule.shouldSeed(90, 90, 12345L));
	}

	@Test
	public void shouldSeed_doesNotResynchronizeMerelyBecauseAFrameRanLong () {
		long period = LwjglFramePacerSchedule.frameNanos(90);
		// A deadline many periods in the past used to trip a drift resynchronization, which threw away
		// the catch-up that advance() performs and cost throughput on already-slow frames.
		long staleDeadline = 100L * period;
		assertFalse(LwjglFramePacerSchedule.shouldSeed(90, 90, staleDeadline));
	}

	/**
	 * The pacer loop as it shipped in v1.5.4, kept here as an executable description of the bug.
	 *
	 * <p>Two defects had to combine to produce the reported symptom, which is why neither one alone is
	 * enough to reproduce it: the deadline advanced without clamping to the current time, so an
	 * over-budget frame left timing debt behind, and a drift check re-seeded the schedule once that debt
	 * exceeded two periods, which skipped a frame. This method reproduces both, and the assertion below
	 * pins the resulting beat so the current implementation can be compared against it.
	 */
	private static List<Long> shippedV154PresentIntervals (int frameRate, long workNanos, int frames) {
		long period = LwjglFramePacerSchedule.frameNanos(frameRate);
		long now = 0L;
		long deadline = 0L;
		int lastFrameRate = 0;
		List<Long> presents = new ArrayList<Long>();
		for (int i = 0; i < frames; i++) {
			now += workNanos;
			presents.add(now);
			if (lastFrameRate != frameRate || deadline <= 0L || now - deadline > period * 2L) {
				lastFrameRate = frameRate;
				deadline = now + period;
			}
			long target = deadline;
			if (now < target) now = target;
			deadline = target + period;
			if (now - deadline > period * 2L) deadline = now + period;
		}
		List<Long> intervals = new ArrayList<Long>();
		for (int i = 1; i < presents.size(); i++) {
			intervals.add(presents.get(i) - presents.get(i - 1));
		}
		return intervals.subList(10, intervals.size());
	}

	@Test
	public void shippedV154Schedule_reproducesTheReportedMissedVblankBeat () {
		// Guards the reproduction itself. If this ever stops showing the beat, the tests below are no
		// longer demonstrating anything and the comparison has silently become vacuous.
		//
		// A present interval longer than one vblank means the frame missed its vblank, so the display
		// scans out the previous frame again. The beat here peaks at 27.8ms against a 16.7ms vblank.
		long vblank = 16700000L;
		List<Long> intervals = shippedV154PresentIntervals(90, vblank, 400);
		long worst = 0L;
		int missed = 0;
		for (Long interval : intervals) {
			worst = Math.max(worst, interval.longValue());
			if (interval.longValue() > vblank) missed++;
		}
		assertTrue("the v1.5.4 reproduction should overshoot a vblank, worst interval was " + worst + "ns",
			worst > vblank);
		assertTrue("the overshoot should recur, not happen once; saw " + missed + " occurrences", missed > 10);
	}

	@Test
	public void schedule_removesTheBeatThatShippedInV154 () {
		// Same input, same uncapped 90 FPS target, current implementation: every frame now lands on a
		// vblank instead of periodically slipping past one.
		long vblank = 16700000L;
		for (Long interval : presentIntervals(90, -1, vblank, 400)) {
			assertEquals("frame missed its vblank, so the display rescans the previous frame",
				vblank, interval.longValue());
		}
	}

	/**
	 * Drives the real schedule with a fixed per-frame cost and returns the intervals between
	 * successive presents, which is what the display and therefore the player actually sees.
	 *
	 * <p>This mirrors {@code LwjglApplication#syncSoftwareFrame} step for step, including the
	 * top-of-call seed check, because the reported beat only emerges from the interaction between the
	 * seed check and the deadline advance.
	 */
	private static List<Long> presentIntervals (int targetFps, int refreshRate, long workNanos, int frames) {
		int frameRate = LwjglFramePacerSchedule.capToRefreshRate(targetFps, refreshRate);
		long period = LwjglFramePacerSchedule.frameNanos(frameRate);
		long now = 0L;
		long deadline = 0L;
		int lastFrameRate = 0;
		List<Long> presents = new ArrayList<Long>();
		for (int i = 0; i < frames; i++) {
			now += workNanos; // render + eglSwapBuffers
			presents.add(now); // the frame becomes visible here

			if (LwjglFramePacerSchedule.shouldSeed(lastFrameRate, frameRate, deadline)) {
				lastFrameRate = frameRate;
				deadline = LwjglFramePacerSchedule.seed(now, period);
			}
			long target = deadline;
			if (now < target) now = target; // sleepUntilFrameDeadline
			deadline = LwjglFramePacerSchedule.advance(target, now, period);
		}
		List<Long> intervals = new ArrayList<Long>();
		for (int i = 1; i < presents.size(); i++) {
			intervals.add(presents.get(i) - presents.get(i - 1));
		}
		return intervals.subList(10, intervals.size());
	}

	@Test
	public void schedule_landsEveryFrameOnAVblankOn60HzWithA90FpsTarget () {
		// The exact reported configuration: launcher default target of 90 FPS on a panel that presents
		// every 16.7ms. Any interval above one vblank means a missed vblank and a rescanned frame.
		long vblank = 16700000L;
		for (Long interval : presentIntervals(90, 60, vblank, 400)) {
			assertEquals("frame missed its vblank, so the display rescans the previous frame",
				vblank, interval.longValue());
		}
	}

	@Test
	public void schedule_landsEveryFrameOnAVblankWhenTheRefreshRateIsUnknown () {
		// Same 60Hz panel, but the launcher could not report a refresh rate, so the cap is inactive and
		// the pacer still targets 90 FPS. The clamp in advance() is then the only thing preventing the
		// beat, which is what makes this the tightest guard on the v1.5.4 regression.
		long vblank = 16700000L;
		for (Long interval : presentIntervals(90, -1, vblank, 400)) {
			assertEquals("frame missed its vblank, so the display rescans the previous frame",
				vblank, interval.longValue());
		}
	}

	@Test
	public void schedule_recoversWithoutDroppingAFrameAfterALongStall () {
		// A single 200ms stall (for example a stop-the-world collection) must be followed by frames
		// paced from the work itself. The shipped pacer resynchronized here and inserted an extra idle
		// period on top of the stall.
		long period = LwjglFramePacerSchedule.frameNanos(90);
		long work = 8000000L;
		long now = 0L;
		long deadline = 0L;
		int lastFrameRate = 0;
		long previousPresent = 0L;
		long worstAfterStall = 0L;
		for (int i = 0; i < 200; i++) {
			now += (i == 100) ? 200000000L : work;
			long present = now;
			if (i > 101) worstAfterStall = Math.max(worstAfterStall, present - previousPresent);
			previousPresent = present;
			if (LwjglFramePacerSchedule.shouldSeed(lastFrameRate, 90, deadline)) {
				lastFrameRate = 90;
				deadline = LwjglFramePacerSchedule.seed(now, period);
			}
			long target = deadline;
			if (now < target) now = target;
			deadline = LwjglFramePacerSchedule.advance(target, now, period);
		}
		assertTrue("frames after the stall must resume at the target period, saw " + worstAfterStall + "ns",
			worstAfterStall <= period);
	}

	@Test
	public void schedule_holdsAConstantCadenceWhenWorkExceedsTheTargetPeriod () {
		long vblank = 16700000L;
		for (Long interval : presentIntervals(90, 60, vblank, 400)) {
			// Work alone sets the cadence once it exceeds the period; the pacer must add nothing.
			assertEquals(vblank, interval.longValue());
		}
	}

	@Test
	public void schedule_pacesToTheTargetWhenWorkFitsInTheBudget () {
		long period = LwjglFramePacerSchedule.frameNanos(90);
		for (Long interval : presentIntervals(90, 90, 4000000L, 400)) {
			assertEquals(period, interval.longValue());
		}
	}
}
