import json
import tempfile
import unittest
from pathlib import Path

from scripts.tools.device_spike_diag import (
    analyze,
    parse_atrace,
    parse_gc_log,
    parse_gdx_summaries,
    parse_gfxinfo,
    parse_samples,
    perfetto_metadata,
    perfetto_config_for_duration,
    run_perfetto_sql,
)


class DeviceSpikeDiagTest(unittest.TestCase):
    def test_analyze_keeps_long_frame_context_and_writes_report(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "frame-probe-incidents.jsonl").write_text(
                json.dumps({"t": 1000, "frame": 7, "totalMs": 80, "renderMs": 75, "room": "MonsterRoom", "action": "DamageAction"}) + "\n"
                + json.dumps({"t": 1100, "frame": 8, "totalMs": 10, "renderMs": 8}) + "\n",
                encoding="utf-8",
            )
            (root / "process-samples.tsv").write_text(
                "epoch_ms\ttotal_pss_kb\tnative_pss_kb\tdalvik_pss_kb\ttemperature_tenth_c\n1000\t200\t30\t40\t350\n",
                encoding="utf-8",
            )
            (root / "atrace.txt").write_text("x sched_switch x\n", encoding="utf-8")
            self.assertEqual(0, analyze(type("Args", (), {"input": str(root), "long_frame_ms": 33.0, "top": 30, "trace_processor": None})()))
            report = json.loads((root / "spike-analysis.json").read_text(encoding="utf-8"))
            self.assertEqual(1, report["longFrameCount"])
            self.assertEqual("DamageAction", report["longFrames"][0]["action"])
            self.assertEqual(1, report["atrace"]["schedSwitchEvents"])

    def test_parse_samples_ignores_non_numeric_power_fields(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "samples.tsv"
            path.write_text("epoch_ms\ttotal_pss_kb\tcurrent_now\n1\t20\tpermission denied\n", encoding="utf-8")
            rows = parse_samples(path)
            self.assertEqual(20.0, rows[0]["total_pss_kb"])
            self.assertNotIn("current_now", rows[0])

    def test_parse_atrace_counts_scheduler_events(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "atrace.txt"
            path.write_text("sched_switch\nactual_frame_timeline_slice\n", encoding="utf-8")
            result = parse_atrace(path)
            self.assertEqual(1, result["schedSwitchEvents"])
            self.assertEqual(1, result["gfxTimelineLines"])

    def test_failed_sample_output_is_not_numeric(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "samples.tsv"
            path.write_text(
                "epoch_ms\tpid\ttotal_pss_kb\n1\t[command failed]\t[command failed]\n",
                encoding="utf-8",
            )
            rows = parse_samples(path)
            self.assertEqual(1.0, rows[0]["epoch_ms"])
            self.assertNotIn("total_pss_kb", rows[0])

    def test_perfetto_metadata_retains_raw_trace_without_processor(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "trace.pftrace"
            path.write_bytes(b"trace")
            result = perfetto_metadata(path)
            self.assertTrue(result["available"])
            self.assertEqual(5, result["bytes"])

    def test_perfetto_sql_preserves_query_when_processor_is_missing(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "trace.pftrace"
            path.write_bytes(b"trace")
            result = run_perfetto_sql(path, None)
            self.assertFalse(result["available"])
            self.assertIn("thread_state", result["sql"])
            self.assertIn("FROM sched", result["sql"])

    def test_perfetto_config_embeds_duration(self):
        self.assertIn("duration_ms: 15000", perfetto_config_for_duration(15))

    def test_parses_gfx_gc_and_gdx_summaries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            gfx = root / "gfx.txt"
            fields = ["0"] * 24
            fields[2] = "1000000"
            fields[17] = "41000000"
            gfx.write_text("---PROFILEDATA---\nFlags,...\n" + ",".join(fields) + "\n---PROFILEDATA---\n")
            self.assertEqual(40.0, parse_gfxinfo(gfx)["durationMaxMs"])
            gc = root / "gc.txt"
            gc.write_text("1: [GC pause (System.gc()) (young), 0.012 secs]\n")
            self.assertEqual(12.0, parse_gc_log(gc)["pauseMaxMs"])
            log = root / "game.txt"
            log.write_text("[gdx-diag] GpuResources summary reason=frame_1 heapUsedMb=2 texturesLive=3 textureUploads=4 textureReleases=5 textureBytes=6 textureBytesPeak=7 frameBuffersLive=8 frameBufferBytes=9\n")
            self.assertEqual(7, parse_gdx_summaries(log)["maxTextureBytesPeak"])


if __name__ == "__main__":
    unittest.main()
