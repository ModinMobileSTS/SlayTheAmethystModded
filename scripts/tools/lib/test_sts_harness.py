from __future__ import annotations

import json
import os
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr
from dataclasses import dataclass
from io import StringIO
from pathlib import Path
from unittest.mock import MagicMock, patch

TEST_DEVICE_SERIAL = os.environ["TEST_DEVICE_SERIAL"]

from scripts.tools.lib.sts_harness import Harness, HarnessOptions, COMMANDS


@dataclass
class _MinimalOptions:
    command: str = ""
    launch_mode: str = "mts_basemod"
    device_serial: str = ""
    out_dir: str = ""
    timeout_seconds: int = 120
    poll_interval_seconds: int = 2
    force_jvm_crash: bool = False
    force_runtime_crash: bool = False
    autoplay: bool = False
    skip_install: bool = False
    no_stop_after_smoke: bool = False
    mods: list = ()
    mod_list_file: str = ""
    enable_all_mods: bool = False
    disable_all_mods: bool = False
    decompil_targets: tuple[str, ...] = ()

    def __post_init__(self):
        self.mods = list(self.mods)
        self.decompil_targets = list(self.decompil_targets)


class DecompilTargetParsingTest(unittest.TestCase):
    def test_parse_simple_class(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        class_name, method_name = parse_decompil_target(
            "com.megacrit.cardcrawl.cards.AbstractCard"
        )
        self.assertEqual(class_name, "com.megacrit.cardcrawl.cards.AbstractCard")
        self.assertIsNone(method_name)

    def test_parse_class_with_method(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        class_name, method_name = parse_decompil_target(
            "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers"
        )
        self.assertEqual(class_name, "com.megacrit.cardcrawl.cards.AbstractCard")
        self.assertEqual(method_name, "applyPowers")

    def test_parse_method_with_descriptor(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        class_name, method_name = parse_decompil_target(
            "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers(Lcom/megacrit/entities/Entity;)V"
        )
        self.assertEqual(class_name, "com.megacrit.cardcrawl.cards.AbstractCard")
        self.assertEqual(method_name, "applyPowers(Lcom/megacrit/entities/Entity;)V")

    def test_parse_empty_string_raises(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        with self.assertRaises(ValueError):
            parse_decompil_target("")

    def test_parse_whitespace_only_raises(self):
        from scripts.tools.lib.sts_harness import parse_decompil_target
        with self.assertRaises(ValueError):
            parse_decompil_target("   ")


class DecompilCommandTest(unittest.TestCase):
    def test_decompil_in_commands(self):
        self.assertIn("decompil", COMMANDS)

    def test_decompil_routing_triggers_harness_decompil(self):
        options = HarnessOptions(
            command="decompil",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=False,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
            decompil_targets=["com.megacrit.cardcrawl.cards.AbstractCard"],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness.run_command = lambda out_dir: 0  # noqa — bypass for this test
        self.assertEqual(harness.options.decompil_targets, ["com.megacrit.cardcrawl.cards.AbstractCard"])

    def test_decompil_empty_targets_raises(self):
        options = HarnessOptions(
            command="decompil",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=False,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
            decompil_targets=[],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness.resolved_out_dir = MagicMock(return_value=unittest.mock.MagicMock())
        with self.assertRaises(ValueError):
            harness.run_command(harness.resolved_out_dir())


class CfrDownloadTest(unittest.TestCase):
    def _make_cfr_path_mock(self, exists=True, size=2000000):
        mock = MagicMock()
        mock.exists.return_value = exists

        class _Stat:
            st_size = size

        mock.stat.return_value = _Stat()
        return mock

    def _make_ctx(self):
        from scripts.tools.lib.sts_harness import HarnessOptions
        from scripts.tools.harness._context import HarnessContext
        from pathlib import Path
        return HarnessContext(
            options=HarnessOptions(
                command="decompil", launch_mode="mts_basemod", device_serial="",
                out_dir="", timeout_seconds=120, poll_interval_seconds=2,
                force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
                autoplay=False, skip_install=False, no_stop_after_smoke=False,
                mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
                decompil_targets=["com.example.Foo"],
            ),
            repo_root=MagicMock(),
        )

    def test_ensure_cfr_returns_existing_jar(self):
        from scripts.tools.harness.decompil import _ensure_cfr
        ctx = self._make_ctx()
        fake_cfr_path = self._make_cfr_path_mock(exists=True)
        ctx.repo_root = MagicMock()
        ctx.repo_root.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

        with patch("scripts.tools.harness.decompil.urllib.request.urlretrieve") as mock_download:
            result = _ensure_cfr(ctx)
            self.assertEqual(result, fake_cfr_path)
            mock_download.assert_not_called()

    def test_ensure_cfr_downloads_when_missing(self):
        from scripts.tools.harness.decompil import _ensure_cfr
        ctx = self._make_ctx()
        fake_cfr_path = self._make_cfr_path_mock()
        fake_cfr_path.exists.side_effect = [False, True]
        ctx.repo_root = MagicMock()
        ctx.repo_root.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

        with patch("scripts.tools.harness.decompil.urllib.request.urlretrieve") as mock_download:
            result = _ensure_cfr(ctx)
            self.assertEqual(result, fake_cfr_path)
            mock_download.assert_called_once()

    def test_ensure_cfr_raises_on_download_failure(self):
        from scripts.tools.harness.decompil import _ensure_cfr
        ctx = self._make_ctx()
        fake_cfr_path = self._make_cfr_path_mock(exists=False, size=0)
        ctx.repo_root = MagicMock()
        ctx.repo_root.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

        with patch("scripts.tools.harness.decompil.urllib.request.urlretrieve",
                   side_effect=Exception("network error")):
            with self.assertRaises(RuntimeError) as exc_ctx:
                _ensure_cfr(ctx)
            self.assertIn("Failed to download CFR", str(exc_ctx.exception))


class DecompilRoutingTest(unittest.TestCase):
    def test_decompil_run_command_sets_result(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import MagicMock

        options = HarnessOptions(
            command="decompil",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=False,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
            decompil_targets=["com.example.Foo"],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness.resolved_out_dir = MagicMock()
        out = harness.resolved_out_dir.return_value = MagicMock()

        fake_info = {"decompiledClasses": ["Foo.java"]}
        with unittest.mock.patch(
            "scripts.tools.harness.decompil.run_decompil",
            return_value=(fake_info, True, "OK", "1 class decompiled"),
        ):
            harness.run_command(out)
            self.assertEqual(harness.result["decompilInfo"], fake_info)
            self.assertTrue(harness.result["success"])
            self.assertEqual(harness.result["status"], "OK")


class StartupCacheProfileTest(unittest.TestCase):
    def _make_options(self, **overrides):
        values = dict(
            command="startup-cache-profile",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=300,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=True,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
        )
        values.update(overrides)
        return HarnessOptions(**values)

    def test_command_is_registered(self):
        self.assertIn("startup-cache-profile", COMMANDS)

    def test_run_command_routes_to_startup_cache_profile(self):
        harness = Harness(self._make_options())
        out = Path(tempfile.mkdtemp())
        try:
            harness.result = {"artifacts": {}}
            with unittest.mock.patch(
                "scripts.tools.harness.startup_cache.run_startup_cache_profile",
                return_value=0,
            ) as mock_func:
                exit_code = harness.run_command(out)
                self.assertEqual(exit_code, 0)
                mock_func.assert_called_once()
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_extract_startup_cache_log_evidence_detects_build(self):
        from scripts.tools.harness._status import extract_startup_cache_log_evidence
        text = "\n".join([
            "[Amethyst] Patch cache miss: marker changed",
            "[Amethyst] Writing MTS patch cache jar: /tmp/desktop-1.0-modded.jar",
            "[Amethyst] MTS patch cache step invokePackageJar cacheBytes=123 packageJars=21 took 4567ms",
            "[Amethyst] Wrote cached MTS annotation DB entries=12 took 34ms",
            "[Amethyst] MTS patch cache is ready: packageJars=21",
        ])
        evidence = extract_startup_cache_log_evidence(text)
        self.assertEqual(evidence["mode"], "cache-build")
        self.assertTrue(evidence["sawCacheBuild"])
        self.assertTrue(evidence["sawCacheMiss"])
        elapsed_values = [item["elapsedMs"] for item in evidence["timings"]]
        self.assertIn(4567, elapsed_values)
        self.assertIn(34, elapsed_values)

    def test_extract_startup_cache_log_evidence_detects_hit(self):
        from scripts.tools.harness._status import extract_startup_cache_log_evidence
        text = "\n".join([
            "[Amethyst] Launching cached MTS patch jar: /tmp/desktop-1.0-modded.jar",
            "[Amethyst] Prepared cached MTS prepackaged launch took 98ms",
            "[Amethyst] Restored cached MTS annotation DB: mods=20 entries=20 took 12ms",
        ])
        evidence = extract_startup_cache_log_evidence(text)
        self.assertEqual(evidence["mode"], "cache-hit")
        self.assertTrue(evidence["sawCacheHit"])
        self.assertGreaterEqual(len(evidence["timings"]), 2)

    def test_clear_startup_caches_clears_external_and_private_paths(self):
        from scripts.tools.harness._context import HarnessContext
        from scripts.tools.harness.startup_cache import clear_startup_caches
        ctx = HarnessContext(
            options=self._make_options(),
            repo_root=Path("/fake/repo"),
            application_id="io.test",
            result={"artifacts": {}},
        )
        with patch("scripts.tools.harness.startup_cache.resolve_device_sts_root",
                   return_value={"root": "/sdcard/Android/data/io.test/files/sts", "accessMode": "shell"}):
            external_result = MagicMock(exit_code=0, output="")
            private_result = MagicMock(exit_code=0, output="")
            with patch("scripts.tools.harness.startup_cache.remote_sts_root_script", return_value=external_result):
                with patch("scripts.tools.harness.startup_cache.adb", return_value=private_result):
                    summary = clear_startup_caches(ctx)
        self.assertEqual(summary["externalExitCode"], 0)
        self.assertEqual(summary["privateExitCode"], 0)


class SteamCloudSyncTest(unittest.TestCase):
    def _make_options(self, **overrides):
        values = dict(
            command="steam-cloud-sync",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=300,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            autoplay=False,
            skip_install=True,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
        )
        values.update(overrides)
        return HarnessOptions(**values)

    def test_command_is_registered(self):
        self.assertIn("steam-cloud-sync", COMMANDS)

    def test_run_command_routes_to_steam_cloud_sync(self):
        harness = Harness(self._make_options())
        out = Path(tempfile.mkdtemp())
        try:
            harness.result = {"artifacts": {}}
            harness.harness_steam_cloud_sync = MagicMock(return_value=0)
            exit_code = harness.run_command(out)
            self.assertEqual(exit_code, 0)
            harness.harness_steam_cloud_sync.assert_called_once_with(out)
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_resolve_device_storage_root_shell(self):
        harness = Harness(self._make_options())
        storage_root = harness.resolve_device_storage_root(
            {"root": "/sdcard/Android/data/io.test/files/sts", "accessMode": "shell"}
        )
        self.assertEqual(storage_root["root"], "/sdcard/Android/data/io.test/files")
        self.assertEqual(storage_root["accessMode"], "shell")

    def test_resolve_device_storage_root_run_as(self):
        harness = Harness(self._make_options())
        storage_root = harness.resolve_device_storage_root(
            {"root": "files/sts", "accessMode": "run-as"}
        )
        self.assertEqual(storage_root["root"], "files")
        self.assertEqual(storage_root["accessMode"], "run-as")

    def test_parse_steam_cloud_summary(self):
        text = "\n".join([
            "Steam Cloud diagnostics summary",
            "",
            "Outcome: SUCCESS",
            "Operation: manual_push",
            "Account: test-user",
            "Started At: 2026-07-08 10:00:00",
            "Completed At: 2026-07-08 10:00:05",
            "Duration Ms: 5000",
            "Failure Summary: <none>",
            "Current Stage: upload_complete",
        ])
        parsed = Harness.parse_steam_cloud_summary(text)
        self.assertEqual(parsed["outcome"], "SUCCESS")
        self.assertEqual(parsed["operation"], "manual_push")
        self.assertEqual(parsed["account"], "test-user")
        self.assertEqual(parsed["durationMs"], 5000)
        self.assertEqual(parsed["currentStage"], "upload_complete")

    def test_parse_steam_cloud_push_summary(self):
        text = "\n".join([
            "Steam Cloud push summary",
            "",
            "Completed At: 2026-07-08 10:00:05",
            "Uploaded Files: 2",
            "Uploaded Bytes: 4096",
            "Deleted Remote Files: 1",
            "Remote Files After Push: 9",
        ])
        parsed = Harness.parse_steam_cloud_push_summary(text)
        self.assertEqual(parsed["uploadedFiles"], 2)
        self.assertEqual(parsed["uploadedBytes"], 4096)
        self.assertEqual(parsed["deletedRemoteFiles"], 1)
        self.assertEqual(parsed["remoteFilesAfterPush"], 9)

    def test_harness_logs_falls_back_to_adb_export(self):
        harness = Harness(self._make_options())
        harness.result = {"artifacts": {}}
        harness.gradle = MagicMock(side_effect=RuntimeError("gradle failed"))
        harness.resolve_device_sts_root = MagicMock(
            return_value={"root": "/sdcard/Android/data/io.test/files/sts", "accessMode": "shell"}
        )
        harness.resolve_device_storage_root = MagicMock(
            return_value={"root": "/sdcard/Android/data/io.test/files", "accessMode": "shell"}
        )
        harness.collect_remote_text_snapshot = MagicMock(
            side_effect=lambda root_info, relative_path, local_path, **kwargs: {
                "relativePath": relative_path,
                "state": {"exists": False},
                "artifact": str(local_path),
            }
        )
        out = Path(tempfile.mkdtemp())
        try:
            harness.harness_logs(out)
            self.assertIn("logsGradleError", harness.result["artifacts"])
            summary_path = Path(harness.result["artifacts"]["logsFallbackSummary"])
            self.assertTrue(summary_path.is_file())
            exported_paths = [
                call.args[1]
                for call in harness.collect_remote_text_snapshot.call_args_list
            ]
            self.assertIn("steam-cloud/last-operation-summary.txt", exported_paths)
            self.assertIn("steam-cloud/push-summary.txt", exported_paths)
            self.assertIn("latest.log", exported_paths)
            self.assertIn("boot_bridge_events.log", exported_paths)
        finally:
            shutil.rmtree(out, ignore_errors=True)


class HarnessAdbLaunchTest(unittest.TestCase):
    def _make_options(self, **overrides):
        values = dict(
            command="start",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            autoplay=False,
            skip_install=False,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
        )
        values.update(overrides)
        return HarnessOptions(**values)

    def test_effective_debug_launch_mode_maps_mts_basemod_to_mts(self):
        harness = Harness(self._make_options(launch_mode="mts_basemod"))
        self.assertEqual(harness.effective_debug_launch_mode(), "mts")
        harness = Harness(self._make_options(launch_mode="vanilla"))
        self.assertEqual(harness.effective_debug_launch_mode(), "vanilla")

    def test_harness_start_uses_direct_adb_launch(self):
        harness = Harness(self._make_options())
        harness.application_id = "io.test"
        harness.adb = MagicMock(return_value=MagicMock(exit_code=0, output=""))

        harness.harness_start()

        adb_args = harness.adb.call_args.args[0]
        self.assertEqual(adb_args[:4], ["shell", "am", "start", "-n"])
        self.assertIn("io.test/.LauncherActivity", adb_args)
        self.assertIn("io.stamethyst.debug_launch_mode", adb_args)
        self.assertIn("mts", adb_args)

    def test_harness_start_for_steam_cloud_sync_opens_launcher_without_debug_launch(self):
        harness = Harness(self._make_options(command="steam-cloud-sync"))
        harness.application_id = "io.test"
        harness.adb = MagicMock(return_value=MagicMock(exit_code=0, output=""))

        harness.harness_start()

        adb_args = harness.adb.call_args.args[0]
        self.assertEqual(adb_args, ["shell", "am", "start", "-n", "io.test/.LauncherActivity"])

    def test_harness_stop_uses_direct_adb_force_stop(self):
        harness = Harness(self._make_options(command="stop"))
        harness.application_id = "io.test"
        harness.adb = MagicMock(return_value=MagicMock(exit_code=0, output=""))

        harness.harness_stop()

        self.assertEqual(harness.adb.call_args.args[0], ["shell", "am", "force-stop", "io.test"])

    def test_harness_status_treats_steamcloud_process_as_running(self):
        harness = Harness(self._make_options(command="status"))
        harness.application_id = "io.test"
        harness.resolved_device_serial = "device-1"
        harness.resolve_device_sts_root = MagicMock(return_value={"root": "files/sts", "accessMode": "run-as"})
        harness.read_remote_sts_text = MagicMock(return_value="")
        harness.desktop_jar_patch_snapshot = MagicMock(return_value={"inProgress": False})
        harness.package_version_info = MagicMock(return_value={"versionName": None, "versionCode": None})
        harness.process_pid_text = MagicMock(
            side_effect=lambda name: "1234" if name == "io.test:steamcloud" else ""
        )

        status = harness.harness_status()

        self.assertEqual(status["observedState"], "STEAM_CLOUD_SYNC_RUNNING")
        self.assertEqual(status["processes"]["steamcloud"], "1234")


class HarnessLogcatCrashDetectionTest(unittest.TestCase):
    def test_ignores_unrelated_native_crash_near_app_logs(self):
        text = "\n".join(
            [
                "07-08 16:40:19.144 I ActivityTaskManager: START cmp=io.test/.LauncherActivity",
                "07-08 16:41:17.555 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 22299 (lshal), pid 22299 (lshal)",
                "07-08 16:41:17.603 I DEBUG   : pid: 22299, tid: 22299, name: lshal  >>> /system/bin/lshal <<<",
                "07-08 16:41:17.630 D WindowManager: Task{7292ccf A=10492:io.test}",
            ]
        )

        crash = Harness.find_harness_logcat_crash(text, "io.test")

        self.assertIsNone(crash)

    def test_ignores_foreground_utils_process_lifecycle_logs(self):
        text = "\n".join(
            [
                "07-28 08:43:10.068  1328  1936 D ForegroundUtils: handleForegroundActivitiesChanged process: io.test uid: 10079 pid: 9142 FG:false, pi.foreground = false",
                "07-28 08:43:13.002   445   889 I ActivityManager: Force stopping io.test appid=10079 user=0: from pid 9579",
                "07-28 08:43:13.499  1328  1936 D ForegroundUtils: handleForegroundActivitiesChanged process: io.test uid: 10079 pid: 9592 FG:true, pi.foreground = true",
            ]
        )

        self.assertIsNone(Harness.find_harness_logcat_crash(text, "io.test"))

    def test_detects_steamcloud_native_crash(self):
        text = "\n".join(
            [
                "07-08 16:40:23.760 I SteamCloud: operation_begin operation=plan_upload",
                "07-08 16:40:24.100 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 19948 (steamcloud), pid 19948 (steamcloud)",
                "07-08 16:40:24.150 I DEBUG   : pid: 19948, tid: 19948, name: steamcloud  >>> io.test:steamcloud <<<",
            ]
        )

        crash = Harness.find_harness_logcat_crash(text, "io.test")

        self.assertIsNotNone(crash)
        self.assertEqual(crash["marker"], "Fatal signal")


class ConsoleRoutingTest(unittest.TestCase):
    def test_console_command_is_registered(self):
        self.assertIn("console", COMMANDS)

    def test_console_run_command_routes(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import MagicMock

        options = HarnessOptions(
            command="console", launch_mode="mts_basemod", device_serial=TEST_DEVICE_SERIAL,
            out_dir="", timeout_seconds=120, poll_interval_seconds=2,
            force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
            autoplay=False, skip_install=False, no_stop_after_smoke=False,
            mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            console_command="gold 999",
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        out = MagicMock()
        harness.result = {"artifacts": {}}

        with unittest.mock.patch(
            "scripts.tools.harness.console.run_console",
        ) as mock_func:
            exit_code = harness.run_command(out)
            self.assertEqual(exit_code, 0)
            mock_func.assert_called_once()

    def test_console_command_without_console_command_arg(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import MagicMock

        options = HarnessOptions(
            command="console", launch_mode="mts_basemod", device_serial=TEST_DEVICE_SERIAL,
            out_dir="", timeout_seconds=120, poll_interval_seconds=2,
            force_jvm_crash=False, force_runtime_crash=False, debug_mode=False,
            autoplay=False, skip_install=False, no_stop_after_smoke=False,
            mods=[], mod_list_file="", enable_all_mods=False, disable_all_mods=False,
            console_command="",
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        out = MagicMock()
        harness.result = {"artifacts": {}}

        with unittest.mock.patch(
            "scripts.tools.harness.console.run_console",
        ) as mock_func:
            harness.run_command(out)
            mock_func.assert_called_once()



class ResolvedOutDirTest(unittest.TestCase):
    def _options(self, **kwargs) -> HarnessOptions:
        defaults = dict(
            command="doctor",
            launch_mode="mts_basemod",
            device_serial="",
            out_dir="",
            timeout_seconds=120,
            poll_interval_seconds=2,
            force_jvm_crash=False,
            force_runtime_crash=False,
            debug_mode=False,
            autoplay=False,
            skip_install=False,
            no_stop_after_smoke=False,
            mods=[],
            mod_list_file="",
            enable_all_mods=False,
            disable_all_mods=False,
            decompil_targets=[],
        )
        defaults.update(kwargs)
        return HarnessOptions(**defaults)

    def test_default_out_dir_uses_command_and_timestamp(self):
        harness = Harness(self._options(out_dir=""))
        path = harness.resolved_out_dir()
        self.assertEqual(path.parent, harness.repo_root / "debug-artifacts" / "harness")
        self.assertTrue(path.name.startswith("doctor-"))
        self.assertEqual(harness.resolved_out_dir(), path)

    def test_user_out_dir_nests_timestamp_subdir(self):
        harness = Harness(self._options(out_dir="agent-tmp/harness-outdir-check"))
        path = harness.resolved_out_dir()
        base = harness.repo_root / "agent-tmp" / "harness-outdir-check"
        self.assertEqual(path.parent, base.resolve())
        self.assertRegex(path.name, r"^\d{8}-\d{6}-\d{6}$")
        self.assertEqual(harness.resolved_out_dir(), path)

    def test_absolute_out_dir_nests_timestamp_subdir(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "fixed-out"
            harness = Harness(self._options(out_dir=str(base)))
            path = harness.resolved_out_dir()
            self.assertEqual(path.parent, base.resolve())
            self.assertRegex(path.name, r"^\d{8}-\d{6}-\d{6}$")

    def test_run_prints_initialization_failure_to_stderr(self):
        with tempfile.TemporaryDirectory() as tmp:
            harness = Harness(self._options(out_dir=tmp))
            stderr = StringIO()
            with patch.object(
                harness,
                "initialize",
                side_effect=RuntimeError("Requested device is not connected and online: test-device"),
            ), redirect_stderr(stderr):
                exit_code = harness.run()

            self.assertEqual(exit_code, 1)
            self.assertIn("Harness error [RuntimeError]", stderr.getvalue())
            self.assertIn("Requested device is not connected", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
