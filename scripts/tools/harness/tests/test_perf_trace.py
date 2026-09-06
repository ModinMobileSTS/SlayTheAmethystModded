import unittest

from scripts.tools.harness.perf_trace import (
    TracerResult,
    aggregate_trace_hotspots,
    correlate_trace_incidents,
    format_report,
    parse_trace_output,
)


class PerfTraceTest(unittest.TestCase):
    def test_aggregate_trace_hotspots_ranks_nested_functions(self):
        entries = parse_trace_output(
            "`---[20.0ms] com.example.Card:render()\n"
            "+---[12.0ms] com.example.Card:renderGlow()\n"
            "+---[8.0ms] com.example.SpriteBatch:flush()\n"
            "`---[10.0ms] com.example.Card:render()\n"
            "+---[9.0ms] com.example.Card:renderGlow()\n"
        )

        hotspots = aggregate_trace_hotspots(entries)

        self.assertEqual("Card.render", hotspots[0].frame)
        self.assertEqual(2, hotspots[0].count)
        self.assertEqual(30.0, hotspots[0].total_ms)
        self.assertEqual("Card.renderGlow", hotspots[1].frame)
        self.assertEqual(21.0, hotspots[1].total_ms)

    def test_report_exposes_function_level_diagnosis(self):
        result = TracerResult(
            raw_trace_output=(
                "`---[20.0ms] com.example.Card:render()\n"
                "+---[12.0ms] com.example.Card:renderGlow()\n"
            ),
            duration_s=4.0,
        )

        report = format_report(result)

        self.assertIn("Functions contributing most trace time", report)
        self.assertIn("Card.renderGlow", report)
        self.assertEqual("Card.render", result.function_hotspots[0].frame)

    def test_correlation_reports_same_second_without_fabricating_frame_match(self):
        entries = parse_trace_output(
            "`---ts=2026-09-06 10:00:01;thread_name=LWJGL Application;id=1;\n"
            "    `---[120.0ms] com.example.Game:render()\n"
        )
        correlate_trace_incidents(entries, [
            {"t": 1788660001100, "totalMs": 80.0, "frame": 10},
            {"t": 1788660001900, "totalMs": 12.0, "frame": 11},
        ])

        self.assertEqual(2, entries[0].same_second_incident_count)
        self.assertEqual(1, entries[0].same_second_long_incident_count)
        self.assertEqual(80.0, entries[0].same_second_max_ms)
        self.assertIsNone(entries[0].matched_incident)


if __name__ == "__main__":
    unittest.main()
