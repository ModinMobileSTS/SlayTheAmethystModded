"""Behavioral regressions for native audio recovery, without a device/Gradle.

Tests assert the desired behavior, including the original resume-failure and
stale-success regressions, rather than asserting buggy behavior as correct.
"""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from scripts.tools.lib.native_audio_recovery_probe import (
    REPO_ROOT,
    build_probe,
    run_scenario,
)


class NativeAudioRecoveryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        artifact_root = REPO_ROOT / "agent-tmp/native-audio-recovery-tests"
        artifact_root.mkdir(parents=True, exist_ok=True)
        cls.out_dir = Path(tempfile.mkdtemp(prefix="run-", dir=artifact_root))
        cls.executable = build_probe(cls.out_dir)
        print(f"\nNative audio test artifacts: {cls.out_dir}")

    def scenario(self, name, count=1):
        states = run_scenario(self.executable, name)
        self.assertEqual(count, len(states), f"Missing native probe output: {name}")
        return states

    def test_healthy_output_reports_success(self):
        state, = self.scenario("healthy")
        self.assertEqual(1, state["reset_calls"])
        self.assertEqual(1, state["resume_calls"])
        self.assertEqual(1, state["connected"])
        self.assertEqual(1, state["running"])
        self.assertEqual(0, state["alc_error"])
        self.assertEqual(1, state["connection_reads"])
        self.assertEqual(0, state["queued"])
        self.assertEqual(1, state["reported_success"])

    def test_reset_failure_does_not_report_success(self):
        state, = self.scenario("reset_failed")
        self.assertEqual(1, state["reset_calls"])
        self.assertEqual(0, state["connected"])
        self.assertEqual(0, state["running"])
        self.assertEqual(0, state["reported_success"])

    def test_resume_failure_does_not_report_success(self):
        state, = self.scenario("resume_failed")
        self.assertEqual(1, state["reset_calls"])
        self.assertEqual(1, state["resume_calls"])
        self.assertEqual(0xA001, state["injected_error"])
        self.assertEqual(0, state["alc_error"])
        self.assertEqual(0, state["connected"])
        self.assertEqual(0, state["running"])
        self.assertEqual(
            0, state["reported_success"],
            f"Resume failed and output stopped, but JNI reported success: {state}",
        )

    def test_missing_context_keeps_request_until_context_returns(self):
        waiting, completed = self.scenario("context_delayed", count=2)
        self.assertEqual(1, waiting["queued"])
        self.assertEqual(0, waiting["reset_calls"])
        self.assertEqual(0, waiting["completed_generation"])
        self.assertEqual(0, waiting["reported_success"])
        self.assertEqual(0, completed["queued"])
        self.assertEqual(1, completed["completed_generation"])
        self.assertEqual(1, completed["running"])
        self.assertEqual(1, completed["reported_success"])

    def test_duplicate_pending_requests_are_coalesced(self):
        waiting, completed = self.scenario("duplicate_pending", count=2)
        self.assertEqual(1, waiting["queued"])
        self.assertEqual(1, waiting["requested_generation"])
        self.assertEqual(0, waiting["reported_success"])
        self.assertEqual(1, completed["reset_calls"])
        self.assertEqual(1, completed["reported_success"])

    def test_completed_result_is_consumed_once(self):
        first, second = self.scenario("result_consumed_once", count=2)
        self.assertEqual(1, first["reported_success"])
        self.assertEqual(0, second["reported_success"])
        self.assertEqual(1, second["running"])
        self.assertEqual(1, second["reset_calls"])

    def test_previous_success_cannot_complete_new_pending_request(self):
        waiting, failed = self.scenario("stale_success", count=2)
        self.assertEqual(2, waiting["requested_generation"])
        self.assertEqual(1, waiting["completed_generation"])
        self.assertEqual(1, waiting["queued"])
        self.assertEqual(0, waiting["running"])
        # Prove that the newer request actually fails, independently of the
        # already-confirmed resume bug: inject a reset failure this time.
        self.assertEqual(2, failed["completed_generation"])
        self.assertEqual(0, failed["running"])
        self.assertEqual(0, failed["reported_success"])
        self.assertEqual(
            0, waiting["reported_success"],
            f"Old success was returned while generation 2 was still pending: {waiting}",
        )

    def test_disconnected_resume_does_not_report_success(self):
        state, = self.scenario("resume_disconnected")
        self.assertEqual(0, state["injected_error"])
        self.assertEqual(1, state["connection_reads"])
        self.assertEqual(0, state["connected"])
        self.assertEqual(0, state["running"])
        self.assertEqual(0, state["reported_success"])

    def test_reset_error_does_not_report_success(self):
        state, = self.scenario("reset_error")
        self.assertEqual(1, state["reset_calls"])
        self.assertEqual(0xA001, state["injected_error"])
        self.assertEqual(0, state["resume_calls"])
        self.assertEqual(0, state["reported_success"])

    def test_pause_error_does_not_continue_recovery(self):
        state, = self.scenario("pause_failed")
        self.assertEqual(1, state["pause_calls"])
        self.assertEqual(0xA001, state["injected_error"])
        self.assertEqual(0, state["reset_calls"])
        self.assertEqual(0, state["resume_calls"])
        self.assertEqual(0, state["reported_success"])

    def test_connection_query_error_does_not_report_success(self):
        state, = self.scenario("query_failed")
        self.assertEqual(1, state["running"])
        self.assertEqual(0xA003, state["injected_error"])
        self.assertEqual(0, state["reported_success"])

    def test_prior_error_does_not_poison_healthy_recovery(self):
        state, = self.scenario("prior_error")
        self.assertEqual(0xA001, state["injected_error"])
        self.assertEqual(0, state["alc_error"])
        self.assertEqual(1, state["running"])
        self.assertEqual(1, state["reported_success"])

    def test_failed_resume_can_be_retried_successfully(self):
        failed, recovered = self.scenario("failure_then_retry", count=2)
        self.assertEqual(0, failed["running"])
        self.assertEqual(0, failed["reported_success"])
        self.assertEqual(2, recovered["requested_generation"])
        self.assertEqual(2, recovered["completed_generation"])
        self.assertEqual(1, recovered["running"])
        self.assertEqual(1, recovered["reported_success"])

    def test_missing_recovery_symbols_do_not_pause_or_report_success(self):
        for name in ("missing_reset", "missing_resume", "missing_error", "missing_query"):
            with self.subTest(symbol=name):
                state, = self.scenario(name)
                self.assertEqual(0, state["pause_calls"])
                self.assertEqual(0, state["reset_calls"])
                self.assertEqual(0, state["resume_calls"])
                self.assertEqual(0, state["reported_success"])

    def test_old_success_does_not_hide_latest_success(self):
        waiting, completed = self.scenario("latest_success", count=2)
        self.assertEqual(2, waiting["requested_generation"])
        self.assertEqual(1, waiting["completed_generation"])
        self.assertEqual(0, waiting["reported_success"])
        self.assertEqual(2, completed["completed_generation"])
        self.assertEqual(2, completed["reported_generation"])
        self.assertEqual(1, completed["reported_success"])

    def test_concurrent_pending_requests_are_coalesced(self):
        waiting, completed = self.scenario("concurrent_requests", count=2)
        self.assertEqual(1, waiting["requested_generation"])
        self.assertEqual(1, waiting["queued"])
        self.assertEqual(0, waiting["reported_success"])
        self.assertEqual(1, completed["reset_calls"])
        self.assertEqual(1, completed["reported_success"])

    def test_queue_full_failure_can_be_retried_without_deadlock(self):
        failed, completed = self.scenario("queue_full", count=2)
        self.assertEqual(1, failed["completed_generation"])
        self.assertEqual(0, failed["reset_calls"])
        self.assertEqual(0, failed["reported_success"])
        self.assertEqual(2, completed["completed_generation"])
        self.assertEqual(0, completed["queued"])
        self.assertEqual(1, completed["reported_success"])

    def test_focus_only_probe_recovers_without_an_activity_or_route_request(self):
        pending, recovered = self.scenario("focus_only_disconnect", count=2)
        self.assertEqual(0, pending["queued"])
        self.assertEqual(0, pending["requested_generation"])
        self.assertNotEqual(0, pending["health_pending"])
        self.assertEqual(1, recovered["reset_calls"])
        self.assertEqual(1, recovered["running"])
        self.assertEqual(1, recovered["reported_success"])
        self.assertEqual(0, recovered["muted"])
        self.assertAlmostEqual(0.65, recovered["listener_gain"])

    def test_healthy_popup_and_periodic_checks_do_not_rebuild_audio(self):
        state, = self.scenario("healthy_focus")
        self.assertEqual(101, state["connection_reads"])
        self.assertEqual(0, state["reset_calls"])
        self.assertEqual(0, state["requested_generation"])
        self.assertEqual(1, state["running"])

    def test_late_disconnect_is_detected_with_an_empty_command_ring(self):
        healthy, idle, recovered = self.scenario("late_disconnect", count=3)
        self.assertEqual(0, healthy["reset_calls"])
        self.assertEqual(0, idle["queued"])
        self.assertEqual(0, idle["running"])
        self.assertEqual(1, recovered["reset_calls"])
        self.assertEqual(1, recovered["requested_generation"])
        self.assertEqual(1, recovered["running"])

    def test_disconnect_after_a_success_starts_a_new_recovery_generation(self):
        previous, idle, recovered = self.scenario("disconnect_after_recovery", count=3)
        self.assertEqual(1, previous["reported_success"])
        self.assertEqual(1, idle["reset_calls"])
        self.assertEqual(2, recovered["reset_calls"])
        self.assertEqual(2, recovered["requested_generation"])
        self.assertEqual(2, recovered["completed_generation"])
        self.assertEqual(1, recovered["reported_success"])
        self.assertEqual(1, recovered["running"])

    def test_periodic_checks_cannot_renew_exhausted_automatic_retries(self):
        first, exhausted, periodic, renewed = self.scenario("health_retry_exhausted", count=4)
        self.assertEqual(1, first["reset_calls"])
        self.assertEqual(4, exhausted["reset_calls"])
        self.assertEqual(0, exhausted["reported_success"])
        self.assertEqual(4, periodic["reset_calls"])
        self.assertEqual(0, periodic["running"])
        self.assertEqual(5, renewed["reset_calls"])
        self.assertEqual(1, renewed["reported_success"])
        self.assertEqual(1, renewed["running"])
        log = (self.out_dir / "health_retry_exhausted.log").read_text(encoding="utf-8")
        self.assertEqual(1, log.count("event=audio_health_retry_exhausted"))

    def test_automatic_retry_backoff_uses_monotonic_time(self):
        states = self.scenario("health_backoff", count=7)
        self.assertEqual([1, 1, 2, 2, 3, 3, 4], [s["reset_calls"] for s in states])

    def test_health_probe_is_retained_until_the_context_is_available(self):
        pending, recovered = self.scenario("health_context_delayed", count=2)
        self.assertNotEqual(0, pending["health_pending"])
        self.assertEqual(0, pending["reset_calls"])
        self.assertEqual(0, recovered["health_pending"])
        self.assertEqual(1, recovered["running"])

    def test_background_drops_health_probes_and_stale_recovery_without_unmuting(self):
        background, foreground = self.scenario("health_background", count=2)
        self.assertEqual(0, background["reset_calls"])
        self.assertEqual(0, background["health_pending"])
        self.assertEqual(0, background["reported_success"])
        self.assertEqual(1, background["muted"])
        self.assertEqual(1, foreground["reset_calls"])
        self.assertEqual(1, foreground["running"])
        self.assertEqual(0, foreground["muted"])

    def test_health_probes_coalesce_without_filling_the_command_ring(self):
        pending, recovered = self.scenario("health_coalesced", count=2)
        self.assertEqual(0, pending["queued"])
        self.assertEqual(1, recovered["reset_calls"])
        self.assertEqual(1, recovered["requested_generation"])

    def test_connected_but_paused_failed_recovery_is_not_mistaken_for_health(self):
        paused, recovered = self.scenario("health_connected_but_paused", count=2)
        self.assertEqual(1, paused["connected"])
        self.assertEqual(0, paused["running"])
        self.assertEqual(2, recovered["reset_calls"])
        self.assertEqual(1, recovered["running"])

    def test_failed_health_query_does_not_pause_the_device(self):
        state, = self.scenario("health_query_failed")
        self.assertEqual(0, state["pause_calls"])
        self.assertEqual(0, state["requested_generation"])

    def test_simultaneous_health_and_explicit_request_do_not_reset_twice(self):
        state, = self.scenario("health_explicit_coalesced")
        self.assertEqual(1, state["requested_generation"])
        self.assertEqual(1, state["reset_calls"])
        self.assertEqual(1, state["reported_success"])


if __name__ == "__main__":
    unittest.main()
