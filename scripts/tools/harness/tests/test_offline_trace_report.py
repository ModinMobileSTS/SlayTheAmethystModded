import tempfile
import unittest
from pathlib import Path

from scripts.tools.harness.offline_trace_report import parse_sched


class OfflineTraceReportTest(unittest.TestCase):
    def test_scheduler_does_not_duplicate_process_events(self):
        raw = (
            " game-100 ( 100) [001] d..2 10.000: sched_switch: "
            "prev_comm=game prev_pid=100 prev_prio=110 prev_state=S ==> next_comm=swapper/1 next_pid=0\n"
            "<idle>-0 ( 0) [001] d..2 10.050: sched_switch: "
            "prev_comm=swapper/1 prev_pid=0 prev_prio=120 prev_state=R ==> next_comm=game next_pid=100\n"
            " RenderThread-101 ( 100) [001] d..2 10.100: sched_switch: "
            "prev_comm=RenderThread prev_pid=101 prev_prio=110 prev_state=S ==> next_comm=swapper/1 next_pid=0\n"
            "<idle>-0 ( 0) [001] d..2 10.120: sched_switch: "
            "prev_comm=swapper/1 prev_pid=0 prev_prio=120 prev_state=R ==> next_comm=RenderThread next_pid=101\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "atrace.txt"
            path.write_text(raw, encoding="utf-8")
            report = parse_sched(path, 100)

        self.assertEqual(4, report["matchedEvents"])
        self.assertEqual(1, report["mainThread"]["count"])
        self.assertEqual(2, report["targetThreadCount"])
        self.assertEqual(2, report["allProcessThreads"]["count"])
        self.assertEqual(50.0, report["mainThread"]["maxMs"])


if __name__ == "__main__":
    unittest.main()
