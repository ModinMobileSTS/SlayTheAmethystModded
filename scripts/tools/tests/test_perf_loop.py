"""Exercise the shell entrypoint with fake Gradle/connector and real port resolution."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]


class PerfLoopTests(unittest.TestCase):
    def setUp(self):
        (ROOT / "agent-tmp").mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / "agent-tmp")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        shutil.copyfile(ROOT / "scripts/perf-loop.sh", self.root / "scripts/perf-loop.sh")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls.jsonl"
        self.gradle = self.executable("gradle", "record('gradle', sys.argv[1:])\n")
        self.executable("adb", "raise AssertionError('No live adb allowed')\n")
        self.executable("python3", """
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0, os.environ['SOURCE_ROOT'])
args = sys.argv[1:]
if args[:3] == ['-m', 'scripts.tools.connector', 'start']:
    record('connector-start', args[3:])
    sys.exit(int(os.environ.get('FAIL_START', '0')))
assert args[:2] == ['scripts/tools/main.py', 'sts-harness'], args
record('harness', args[2:])
from scripts.tools.lib.sts_harness import Harness
port = int(args[args.index('-ConnectorPort') + 1]) if '-ConnectorPort' in args else None
serial = args[args.index('-DeviceSerial') + 1] if '-DeviceSerial' in args else ''
harness = Harness(SimpleNamespace(connector_port=port, device_serial=serial))
harness.repo_root = Path.cwd()
def ensure():
    record('ensure', [])
    if os.environ.get('FAIL_ENSURE'):
        raise RuntimeError('mock managed connection failed')
    return {'STS_CONNECTOR_PORT': '23456', 'STS_ADB_DEVICE_ENDPOINT': '192.0.2.1:45678'}
with patch('scripts.tools.local_adb.ensure', side_effect=ensure), \\
     patch('scripts.tools.connector.client.ConnectorClient') as client, \\
     patch('socket.socket', side_effect=AssertionError('No network allowed')), \\
     patch('subprocess.run', side_effect=AssertionError('No external commands allowed')), \\
     patch('subprocess.Popen', side_effect=AssertionError('No external commands allowed')):
    client.return_value.status.return_value = {'adb': 'mock-adb'}
    try:
        harness.connect_connector()
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(61)
    record('connected', client.call_args.kwargs)
""")
        self.env = {
            "PATH": str(self.bin) + os.pathsep + os.defpath,
            "SOURCE_ROOT": str(ROOT), "CALLS": str(self.calls), "GRADLE_BIN": str(self.gradle),
            "PYTHONDONTWRITEBYTECODE": "1",
        }

    def executable(self, name, source):
        path = self.bin / name
        path.write_text(f"#!{sys.executable}\n" + """
import sys, os, json
def record(action, args):
    with open(os.environ['CALLS'], 'a') as out:
        out.write(json.dumps({'action': action, 'args': args}) + '\\n')
""" + source)
        path.chmod(0o755)
        return path

    def run_script(self, *args, **environment):
        return subprocess.run(
            ["bash", str(self.root / "scripts/perf-loop.sh"), *args], cwd=self.root,
            env={**self.env, **environment}, capture_output=True, text=True, timeout=10,
        )

    def recorded(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_managed_default_uses_dynamic_port_and_builds_without_installing(self):
        (self.root / ".env.local").touch()
        result = self.run_script("192.0.2.1:45678", STS_CONNECTOR_PORT="19876")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.recorded()
        self.assertEqual([call["action"] for call in calls], ["gradle", "harness", "ensure", "connected"])
        self.assertEqual(calls[0]["args"], [":mods:amethyst-runtime-compat:jar", ":app:assembleDebug"])
        self.assertNotIn("-ConnectorPort", calls[1]["args"])
        self.assertEqual(calls[-1]["args"], {"port": 23456, "auto_start": False})

    def test_environment_managed_mode_also_uses_dynamic_port(self):
        result = self.run_script("--skip-build", STS_ADB_DEVICE_ENDPOINT="192.0.2.1:45678")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["action"] for call in self.recorded()], ["harness", "ensure", "connected"])
        self.assertEqual(self.recorded()[-1]["args"]["port"], 23456)

    def test_managed_mode_rejects_explicit_legacy_port(self):
        (self.root / ".env.local").touch()
        result = self.run_script("--skip-build", "--connector-port", "19876")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("remove --connector-port", result.stderr)
        self.assertEqual(self.recorded(), [])

    def test_managed_ensure_failure_is_not_swallowed(self):
        (self.root / ".env.local").touch()
        result = self.run_script("--skip-build", FAIL_ENSURE="1")
        self.assertEqual(result.returncode, 61)
        self.assertIn("mock managed connection failed", result.stderr)
        self.assertNotIn("Done.", result.stdout)

    def test_explicit_legacy_port_overrides_environment_and_is_preserved(self):
        result = self.run_script("--skip-build", "--connector-port", "34567", STS_CONNECTOR_PORT="19876")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.recorded()
        self.assertEqual([call["action"] for call in calls], ["connector-start", "harness", "connected"])
        self.assertEqual(calls[0]["args"], ["--port", "34567"])
        self.assertEqual(calls[-1]["args"], {"port": 34567, "auto_start": False})

    def test_legacy_environment_port_is_preserved(self):
        result = self.run_script("--skip-build", STS_CONNECTOR_PORT="34567")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.recorded()[-1]["args"]["port"], 34567)

    def test_legacy_start_failure_stops_before_harness(self):
        result = self.run_script("--skip-build", "--connector-port", "34567", FAIL_START="62")
        self.assertEqual(result.returncode, 62)
        self.assertEqual([call["action"] for call in self.recorded()], ["connector-start"])

    def test_legacy_without_port_fails_instead_of_guessing(self):
        result = self.run_script("--skip-build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("specify --connector-port", result.stderr)
        self.assertEqual(self.recorded(), [])
