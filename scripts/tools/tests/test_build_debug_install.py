"""Run the shell entrypoint in a fake checkout; no live ADB, Gradle or installation."""
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


class BuildDebugInstallTests(unittest.TestCase):
    def setUp(self):
        (ROOT / "agent-tmp").mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT / "agent-tmp")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "scripts/tools").mkdir(parents=True)
        for source in ("scripts/build-debug-install.sh", "scripts/tools/local_adb.py"):
            shutil.copyfile(ROOT / source, self.root / source)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls.jsonl"
        self.endpoint = "192.0.2.13:47685"
        self.adb = self.executable("adb", "raise SystemExit('Global adb must not be invoked')\n")
        self.gradle = self.executable("gradle", """
import json, os
from pathlib import Path
with open(os.environ['CALLS'], 'a') as output:
    output.write(json.dumps({'action': 'gradle', 'args': sys.argv[1:]}) + '\\n')
if os.environ.get('FAIL_BUILD'):
    sys.exit(71)
if os.environ.get('CHANGE_CONFIG'):
    config = Path('.env.local')
    config.write_text(config.read_text().replace('192.0.2.13:47685', '192.0.2.99:5555'))
out = Path('app/build/outputs/apk/debug')
out.mkdir(parents=True)
for index in range(int(os.environ.get('APK_COUNT', '1'))):
    (out / f'app-{index}.apk').write_bytes(b'offline test APK')
""")
        # Execute the real config resolver, but replace managed lifecycle/ADB execution
        # at the module CLI boundary. Even a regression cannot contact a real server.
        self.executable("python3", """
import json, os, subprocess
if sys.argv[1:3] != ['-m', 'scripts.tools.local_adb']:
    os.execv(sys.executable, [sys.executable, *sys.argv[1:]])
sys.path.insert(0, os.getcwd())
from scripts.tools import local_adb
config = local_adb.config()
env = local_adb.environment(config)
action = sys.argv[3]
args = sys.argv[5:]
assert sys.argv[4] == '--'
entry = {'action': action, 'args': args, 'endpoint': env['STS_ADB_DEVICE_ENDPOINT'],
         'server': env['ADB_SERVER_SOCKET']}
if action == 'adb':
    entry['command'] = local_adb.command(config, '-s', env['STS_ADB_DEVICE_ENDPOINT'], *args)
with open(os.environ['CALLS'], 'a') as output:
    output.write(json.dumps(entry) + '\\n')
if os.environ.get('FAIL_CONNECT'):
    sys.exit(70)
if action == 'run':
    sys.exit(subprocess.call(args, env=env))
assert action == 'adb'
sys.exit(72 if os.environ.get('FAIL_INSTALL') else 0)
""")
        (self.root / ".env.local").write_text(
            f"STS_ADB_BINARY={self.adb}\nSTS_ADB_SERVER_PORT=16037\n"
            f"STS_ADB_DEVICE_ENDPOINT={self.endpoint}\n"
        )
        self.env = {key: value for key, value in os.environ.items()
                    if key not in {"STS_ADB_BINARY", "STS_ADB_SERVER_PORT", "STS_ADB_DEVICE_ENDPOINT"}}
        self.env.update(PATH=str(self.bin) + os.pathsep + os.defpath,
                        GRADLE_BIN=str(self.gradle), ADB_BIN=str(self.adb), CALLS=str(self.calls))

    def executable(self, name, source):
        path = self.bin / name
        path.write_text(f"#!{sys.executable}\nimport sys\n" + source)
        path.chmod(0o755)
        return path

    def run_script(self, *args, **environment):
        return subprocess.run(
            ["bash", str(self.root / "scripts/build-debug-install.sh"), *args],
            cwd=ROOT, env={**self.env, **environment}, capture_output=True, text=True, timeout=10,
        )

    def recorded(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def assert_managed_install(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.recorded()
        self.assertEqual([call['action'] for call in calls], ['run', 'gradle', 'adb'])
        self.assertEqual(calls[0]['args'], [str(self.gradle), ':app:assembleDebug'])
        self.assertEqual(calls[1]['args'], [':app:assembleDebug'])
        for call in (calls[0], calls[2]):
            self.assertEqual(call['endpoint'], self.endpoint)
            self.assertEqual(call['server'], 'tcp:127.0.0.1:16037')
        self.assertEqual(calls[2]['command'], [str(self.adb), '-H', '127.0.0.1', '-P', '16037',
                         '-s', self.endpoint, 'install', '--fastdeploy', '-r',
                         str(self.root / 'app/build/outputs/apk/debug/app-0.apk')])
        self.assertIn(f'installed successfully on {self.endpoint}', result.stdout)

    def test_bare_ip_uses_configured_wireless_port_and_project_server(self):
        self.assert_managed_install(self.run_script('192.0.2.13'))

    def test_full_endpoint_is_supported_from_another_working_directory(self):
        self.assert_managed_install(self.run_script(self.endpoint))

    def test_endpoint_is_pinned_when_configuration_changes_during_build(self):
        self.assert_managed_install(self.run_script('192.0.2.13', CHANGE_CONFIG='1'))

    def test_missing_automatic_mismatched_and_extra_targets_never_connect_or_build(self):
        for args in ([], ['auto'], ['*'], ['-d'], ['192.0.2.99'], ['192.0.2.13:5555'],
                     [self.endpoint, 'unexpected']):
            with self.subTest(args=args):
                result = self.run_script(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.recorded(), [])

    def test_connection_failure_stops_before_build(self):
        result = self.run_script(self.endpoint, FAIL_CONNECT='1')
        self.assertEqual(result.returncode, 70)
        self.assertEqual([call['action'] for call in self.recorded()], ['run'])

    def test_build_failure_never_installs(self):
        result = self.run_script(self.endpoint, FAIL_BUILD='1')
        self.assertEqual(result.returncode, 71)
        self.assertEqual([call['action'] for call in self.recorded()], ['run', 'gradle'])

    def test_ambiguous_or_missing_apk_never_installs(self):
        for count in ('0', '2'):
            with self.subTest(count=count):
                self.calls.unlink(missing_ok=True)
                shutil.rmtree(self.root / 'app', ignore_errors=True)
                result = self.run_script(self.endpoint, APK_COUNT=count)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Expected exactly one debug APK', result.stderr)
                self.assertEqual([call['action'] for call in self.recorded()], ['run', 'gradle'])

    def test_install_failure_is_reported_without_retry(self):
        result = self.run_script(self.endpoint, FAIL_INSTALL='1')
        self.assertEqual(result.returncode, 72)
        self.assertEqual([call['action'] for call in self.recorded()], ['run', 'gradle', 'adb'])
        self.assertNotIn('installed successfully', result.stdout)


if __name__ == '__main__':
    unittest.main()
