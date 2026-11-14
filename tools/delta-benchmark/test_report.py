import unittest
from report import parse, summarize
from run_phases import schedule, parse_row
from run_phases import summarize as summarize_phases
from run_phases import argument_parser
from prepare_corpus import unpacked
import io
import zipfile


class ReportTest(unittest.TestCase):
    def test_real_only_cli_accepts_empty_synthetic_lists(self):
        args=argument_parser().parse_args(["--model","test","--output","unused",
            "--sizes","--fixtures","--real-fixtures","real-one"])
        self.assertEqual([],args.sizes)
        self.assertEqual([],args.fixtures)
        self.assertEqual(["real-one"],args.real_fixtures)

    def test_unpacked_corpus_is_sorted_and_independent_of_zip_compression(self):
        def jar(order,compression):
            data=io.BytesIO()
            with zipfile.ZipFile(data,"w",compression=compression) as out:
                for name in order:
                    out.writestr(name,(name*10).encode())
            data.seek(0)
            return data
        self.assertEqual(unpacked(jar(["a","b"],zipfile.ZIP_STORED)),
                         unpacked(jar(["b","a"],zipfile.ZIP_DEFLATED)))

    def test_real_only_schedule_does_not_create_synthetic_jobs(self):
        jobs=schedule(["a"],[],[],3,19,["real-one","real-two"])
        self.assertEqual(6,len(jobs))
        self.assertEqual({"real-one","real-two"},{job[2] for job in jobs})

    def test_phase_schedule_is_reproducible_and_real_pair_not_duplicated_by_sizes(self):
        result = schedule(["a", "b"], [1024,2048], ["insert-middle"], 3, 19)
        self.assertEqual(result, schedule(["a", "b"], [1024,2048], ["insert-middle"], 3, 19))
        self.assertEqual(18,len(result))
        self.assertEqual(6,sum(f=="real-readme" for _,_,f,_ in result))

    def test_phase_parser_requires_successful_result(self):
        with self.assertRaises(ValueError):
            parse_row('INSTRUMENTATION_CODE: 0')

    def test_phase_summary_preserves_phase_and_rejects_configuration_mix(self):
        common = dict(engine="test", fixture="move", baseBytes=4096, phase="apply",
                      baseSha256="a", targetSha256="b", patchBytes=30,
                      fingerprint="device", nativePins="pins", harnessVersion="0.3",
                      elapsedNs=2000000, sampledRssPeakKb=200, baselineRssKb=150,
                      batteryTempAfterDeciC=320, thermalAfter=0)
        result = summarize_phases([common])[0]
        self.assertEqual("apply", result["phase"])
        self.assertEqual(50, result["maxRssIncreaseKb"])
        self.assertEqual(2, result["medianMs"])
        with self.assertRaises(ValueError):
            summarize_phases([common, dict(common, nativePins="changed")])

    def test_rejects_failed_or_missing_results(self):
        for text in ("", "INSTRUMENTATION_CODE: -1\nDelta smoke PASS"):
            with self.assertRaises(ValueError):
                parse(text)

    def test_excludes_warmup(self):
        common = dict(engine="test", fixture="insert", baseBytes=4096,
                      baseSha256="a", targetSha256="b", patchBytes=30,
                      fingerprint="device", abi="arm64", vmHwmKb=100)
        rows = [dict(common, warmup=warm, generateNs=n, applyAndSyncNs=n)
                for warm, n in ((True, 999999999), (False, 1000000), (False, 3000000))]
        result = summarize(rows)[0]
        self.assertEqual(2, result["samples"])
        self.assertEqual(2, result["applyMedianMs"])

    def test_rejects_mixed_sample_identity(self):
        common = dict(engine="test", fixture="insert", baseBytes=4096,
                      warmup=False, baseSha256="a", targetSha256="b")
        with self.assertRaises(ValueError):
            summarize([common, dict(common, baseSha256="different")])


if __name__ == "__main__":
    unittest.main()
