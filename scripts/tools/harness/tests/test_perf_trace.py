import unittest

from scripts.tools.harness.perf_trace import (
    TracerResult,
    aggregate_trace_hotspots,
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


if __name__ == "__main__":
    unittest.main()
