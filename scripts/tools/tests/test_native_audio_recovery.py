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


if __name__ == "__main__":
    unittest.main()
