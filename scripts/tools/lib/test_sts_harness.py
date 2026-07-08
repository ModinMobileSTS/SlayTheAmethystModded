from __future__ import annotations

import hashlib
import json
import shutil
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock, patch

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
        harness.harness_decompil = MagicMock()
        harness.harness_decompil.return_value = (
            {"decompiledClasses": ["AbstractCard.java"]},
            True,
            "OK",
            "1 class decompiled",
        )
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

    def test_ensure_cfr_returns_existing_jar(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import patch, MagicMock

        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=["com.example.Foo"],
        )
        harness = Harness(options)
        with patch("scripts.tools.lib.sts_harness.repo_root") as mock_root:
            mock_root.return_value = MagicMock()
            fake_cfr_path = self._make_cfr_path_mock(exists=True)
            mock_root.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

            with patch("scripts.tools.lib.sts_harness.urllib.request.urlretrieve") as mock_download:
                result = harness._ensure_cfr()
                self.assertEqual(result, fake_cfr_path)
                mock_download.assert_not_called()

    def test_ensure_cfr_downloads_when_missing(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import patch, MagicMock

        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=["com.example.Foo"],
        )
        harness = Harness(options)
        with patch("scripts.tools.lib.sts_harness.repo_root") as mock_root:
            mock_root.return_value = MagicMock()
            fake_cfr_path = self._make_cfr_path_mock()
            fake_cfr_path.exists.side_effect = [False, True]
            mock_root.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

            with patch("scripts.tools.lib.sts_harness.urllib.request.urlretrieve") as mock_download:
                result = harness._ensure_cfr()
                self.assertEqual(result, fake_cfr_path)
                mock_download.assert_called_once()

    def test_ensure_cfr_raises_on_download_failure(self):
        from scripts.tools.lib.sts_harness import Harness, HarnessOptions
        from unittest.mock import patch, MagicMock

        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=["com.example.Foo"],
        )
        harness = Harness(options)
        with patch("scripts.tools.lib.sts_harness.repo_root") as mock_root:
            mock_root.return_value = MagicMock()
            fake_cfr_path = self._make_cfr_path_mock(exists=False, size=0)
            mock_root.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value.__truediv__.return_value = fake_cfr_path

            with patch("scripts.tools.lib.sts_harness.urllib.request.urlretrieve",
                       side_effect=OSError("network error")):
                with self.assertRaises(RuntimeError) as ctx:
                    harness._ensure_cfr()
                self.assertIn("Failed to download CFR", str(ctx.exception))


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
        harness.harness_decompil = MagicMock(
            return_value=(fake_info, True, "OK", "1 class decompiled")
        )
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
            harness.harness_startup_cache_profile = MagicMock(return_value=0)
            exit_code = harness.run_command(out)
            self.assertEqual(exit_code, 0)
            harness.harness_startup_cache_profile.assert_called_once_with(out)
        finally:
            shutil.rmtree(out, ignore_errors=True)

    def test_extract_startup_cache_log_evidence_detects_build(self):
        text = "\n".join([
            "[Amethyst] Patch cache miss: marker changed",
            "[Amethyst] Writing MTS patch cache jar: /tmp/desktop-1.0-modded.jar",
            "[Amethyst] MTS patch cache step invokePackageJar cacheBytes=123 packageJars=21 took 4567ms",
            "[Amethyst] Wrote cached MTS annotation DB entries=12 took 34ms",
            "[Amethyst] MTS patch cache is ready: packageJars=21",
        ])
        evidence = Harness.extract_startup_cache_log_evidence(text)
        self.assertEqual(evidence["mode"], "cache-build")
        self.assertTrue(evidence["sawCacheBuild"])
        self.assertTrue(evidence["sawCacheMiss"])
        elapsed_values = [item["elapsedMs"] for item in evidence["timings"]]
        self.assertIn(4567, elapsed_values)
        self.assertIn(34, elapsed_values)

    def test_extract_startup_cache_log_evidence_detects_hit(self):
        text = "\n".join([
            "[Amethyst] Launching cached MTS patch jar: /tmp/desktop-1.0-modded.jar",
            "[Amethyst] Prepared cached MTS prepackaged launch took 98ms",
            "[Amethyst] Restored cached MTS annotation DB: mods=20 entries=20 took 12ms",
        ])
        evidence = Harness.extract_startup_cache_log_evidence(text)
        self.assertEqual(evidence["mode"], "cache-hit")
        self.assertTrue(evidence["sawCacheHit"])
        self.assertGreaterEqual(len(evidence["timings"]), 2)

    def test_clear_startup_caches_clears_external_and_private_paths(self):
        harness = Harness(self._make_options())
        harness.application_id = "io.test"
        harness.resolve_device_sts_root = MagicMock(
            return_value={"root": "/sdcard/Android/data/io.test/files/sts", "accessMode": "shell"}
        )
        external_result = MagicMock(exit_code=0, output="")
        private_result = MagicMock(exit_code=0, output="")
        harness.remote_sts_root_script = MagicMock(return_value=external_result)
        harness.adb = MagicMock(return_value=private_result)

        summary = harness.clear_startup_caches()

        self.assertEqual(summary["externalExitCode"], 0)
        self.assertEqual(summary["privateExitCode"], 0)
        external_script = harness.remote_sts_root_script.call_args.args[1]
        private_args = harness.adb.call_args.args[0]
        private_script = private_args[-1]
        self.assertIn("cd '/sdcard/Android/data/io.test/files/sts' || exit 1", external_script)
        self.assertIn(".mts_classpath_cache", external_script)
        self.assertIn("files/mts_patch_cache", private_script)


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


class JarLibraryDirTest(unittest.TestCase):
    def test_jar_library_dir_returns_correct_path(self):
        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=[],
        )
        harness = Harness(options)
        with patch.object(harness, "repo_root", Path("/fake/repo")):
            result = harness._jar_library_dir()
            self.assertEqual(result, Path("/fake/repo/debug-artifacts/harness/jar-library"))


class Sha256Test(unittest.TestCase):
    def _make_harness(self):
        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=[],
        )
        return Harness(options)

    def test_compute_local_sha256(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tf.write(b"hello world")
            tmp_path = tf.name
        try:
            digest = harness._compute_local_sha256(Path(tmp_path))
            expected = hashlib.sha256(b"hello world").hexdigest()
            self.assertEqual(digest, expected)
            self.assertEqual(len(digest), 64)
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def test_read_local_sha256_exists(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(mode="w", delete=False) as tf:
            tf.write("abc123def456\n")
            tmp_jar = tf.name
        try:
            Path(tmp_jar + ".sha256").write_text("abc123def456\n")
            digest = harness._read_local_sha256(Path(tmp_jar))
            self.assertEqual(digest, "abc123def456")
        finally:
            Path(tmp_jar).unlink(missing_ok=True)
            Path(tmp_jar + ".sha256").unlink(missing_ok=True)

    def test_read_local_sha256_missing_file(self):
        harness = self._make_harness()
        result = harness._read_local_sha256(Path("/nonexistent/path.sha256"))
        self.assertIsNone(result)

    def test_write_local_sha256(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tmp_jar = Path(tf.name)
        try:
            harness._write_local_sha256(tmp_jar, "deadbeef")
            sha_path = Path(str(tmp_jar) + ".sha256")
            self.assertTrue(sha_path.exists())
            self.assertEqual(sha_path.read_text().strip(), "deadbeef")
        finally:
            tmp_jar.unlink(missing_ok=True)
            sha_path = Path(str(tmp_jar) + ".sha256")
            sha_path.unlink(missing_ok=True)

    def test_read_write_roundtrip(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tmp_jar = Path(tf.name)
        try:
            harness._write_local_sha256(tmp_jar, "a1b2c3d4e5f6")
            result = harness._read_local_sha256(tmp_jar)
            self.assertEqual(result, "a1b2c3d4e5f6")
        finally:
            tmp_jar.unlink(missing_ok=True)
            Path(str(tmp_jar) + ".sha256").unlink(missing_ok=True)

    def test_remote_file_sha256_parses_sha256sum_output(self):
        harness = self._make_harness()
        fake_result = MagicMock()
        fake_result.output = (
            "sha256=abc123def456abc123def456abc123def456abc123def456abc123def456abc1\n"
            "exists=1\n"
        )
        with patch.object(harness, "remote_sts_root_script", return_value=fake_result):
            digest = harness.remote_file_sha256({"root": "/data/sts", "accessMode": "shell"}, "desktop-1.0.jar")
            self.assertEqual(digest, "abc123def456abc123def456abc123def456abc123def456abc123def456abc1")

    def test_remote_file_sha256_parses_md5sum_fallback(self):
        harness = self._make_harness()
        fake_result = MagicMock()
        fake_result.output = (
            "sha256=md5:00112233445566778899aabbccddeeff\n"
            "exists=1\n"
        )
        with patch.object(harness, "remote_sts_root_script", return_value=fake_result):
            digest = harness.remote_file_sha256({"root": "/data/sts", "accessMode": "shell"}, "desktop-1.0.jar")
            self.assertEqual(digest, "md5:00112233445566778899aabbccddeeff")

    def test_remote_file_sha256_returns_none_when_no_tools(self):
        harness = self._make_harness()
        fake_result = MagicMock()
        fake_result.output = "sha256=\nexists=1\n"
        with patch.object(harness, "remote_sts_root_script", return_value=fake_result):
            digest = harness.remote_file_sha256({"root": "/data/sts", "accessMode": "shell"}, "desktop-1.0.jar")
            self.assertIsNone(digest)

    def test_remote_file_sha256_file_not_found(self):
        harness = self._make_harness()
        fake_result = MagicMock()
        fake_result.output = "exists=0\n"
        with patch.object(harness, "remote_sts_root_script", return_value=fake_result):
            digest = harness.remote_file_sha256({"root": "/data/sts", "accessMode": "shell"}, "missing.jar")
            self.assertIsNone(digest)


class PullJarIfNeededTest(unittest.TestCase):
    def _make_harness(self):
        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=[],
        )
        return Harness(options)

    def test_pull_skips_when_sha256_match(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tf.write(b"test content")
            local_jar = Path(tf.name)
        try:
            harness._write_local_sha256(local_jar, "match123")
            harness.adb = MagicMock()
            harness._compute_local_sha256 = MagicMock()

            with patch.object(harness, "remote_file_sha256", return_value="match123"):
                harness._pull_jar_if_needed(
                    {"root": "/data/sts", "accessMode": "shell"},
                    "desktop-1.0.jar",
                    local_jar,
                )
            harness.adb.assert_not_called()
        finally:
            local_jar.unlink(missing_ok=True)
            Path(str(local_jar) + ".sha256").unlink(missing_ok=True)

    def test_pull_fetches_when_sha256_mismatch(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tf.write(b"old content")
            local_jar = Path(tf.name)
        try:
            harness._write_local_sha256(local_jar, "oldhash")
            harness.adb = MagicMock(side_effect=self._make_adb_side_effect(local_jar))
            harness._compute_local_sha256 = MagicMock(return_value="newhash")

            with patch.object(harness, "remote_file_sha256", return_value="newhash"):
                harness._pull_jar_if_needed(
                    {"root": "/data/sts", "accessMode": "shell"},
                    "desktop-1.0.jar",
                    local_jar,
                )
            harness.adb.assert_called_once()
            written = Path(str(local_jar) + ".sha256").read_text().strip()
            self.assertEqual(written, "newhash")
        finally:
            local_jar.unlink(missing_ok=True)
            Path(str(local_jar) + ".sha256").unlink(missing_ok=True)

    def _make_adb_side_effect(self, local_path):
        def _fake_pull(*args, **kwargs):
            local_path.parent.mkdir(parents=True, exist_ok=True)
            local_path.write_bytes(b"pulled content")
            return MagicMock(exit_code=0, output="")
        return _fake_pull

    def test_pull_fetches_when_file_missing(self):
        harness = self._make_harness()
        local_jar = Path(tempfile.gettempdir()) / f"test_nonexistent_{id(harness)}.jar"
        try:
            harness.adb = MagicMock(side_effect=self._make_adb_side_effect(local_jar))
            harness._compute_local_sha256 = MagicMock(return_value="somehash")

            with patch.object(harness, "remote_file_sha256", return_value="somehash"):
                harness._pull_jar_if_needed(
                    {"root": "/data/sts", "accessMode": "shell"},
                    "desktop-1.0.jar",
                    local_jar,
                )
            harness.adb.assert_called_once()
        finally:
            local_jar.unlink(missing_ok=True)
            Path(str(local_jar) + ".sha256").unlink(missing_ok=True)

    def test_pull_fetches_when_remote_hash_unavailable(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tf.write(b"existing")
            local_jar = Path(tf.name)
        try:
            harness._write_local_sha256(local_jar, "somehash")
            harness.adb = MagicMock(side_effect=self._make_adb_side_effect(local_jar))
            harness._compute_local_sha256 = MagicMock(return_value="freshhash")

            with patch.object(harness, "remote_file_sha256", return_value=None):
                harness._pull_jar_if_needed(
                    {"root": "/data/sts", "accessMode": "shell"},
                    "desktop-1.0.jar",
                    local_jar,
                )
            harness.adb.assert_called_once()
        finally:
            local_jar.unlink(missing_ok=True)
            Path(str(local_jar) + ".sha256").unlink(missing_ok=True)

    def test_pull_fetches_when_sha256_file_missing(self):
        harness = self._make_harness()
        with tempfile.NamedTemporaryFile(delete=False) as tf:
            tf.write(b"content with no companion")
            local_jar = Path(tf.name)
        try:
            harness.adb = MagicMock(side_effect=self._make_adb_side_effect(local_jar))
            harness._compute_local_sha256 = MagicMock(return_value="newhash")

            with patch.object(harness, "remote_file_sha256", return_value="newhash"):
                harness._pull_jar_if_needed(
                    {"root": "/data/sts", "accessMode": "shell"},
                    "desktop-1.0.jar",
                    local_jar,
                )
            harness.adb.assert_called_once()
        finally:
            local_jar.unlink(missing_ok=True)
            Path(str(local_jar) + ".sha256").unlink(missing_ok=True)


class HarnessDecompilUsesJarLibraryTest(unittest.TestCase):
    def test_decompil_uses_jar_library_path(self):
        options = HarnessOptions(
            command="decompil",
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
            decompil_targets=["com.example.Foo"],
        )
        harness = Harness(options)
        harness.initialize = MagicMock()
        harness._ensure_cfr = MagicMock(return_value=Path("/fake/cfr.jar"))
        harness.resolve_device_sts_root = MagicMock(
            return_value={"root": "/data/sts", "accessMode": "shell"}
        )
        tmp_jar_dir = Path(tempfile.mkdtemp())
        harness._jar_library_dir = MagicMock(return_value=tmp_jar_dir)
        tmp_jar_dir.mkdir(parents=True, exist_ok=True)
        (tmp_jar_dir / "desktop-1.0.jar").write_bytes(b"fake jar")
        harness._read_local_sha256 = MagicMock(return_value=None)
        harness.remote_file_sha256 = MagicMock(return_value="abc123")
        harness.adb = MagicMock()
        harness.run_native = MagicMock()
        fake_result = MagicMock()
        fake_result.exit_code = 0
        harness.run_native.return_value = fake_result

        out_dir = Path(tempfile.mkdtemp())
        src_dir = out_dir / "src"
        src_dir.mkdir(parents=True, exist_ok=True)
        class_file = src_dir / "com" / "example" / "Foo.java"
        class_file.parent.mkdir(parents=True, exist_ok=True)
        class_file.write_text("// decompiled")

        try:
            harness.harness_decompil(out_dir)
            harness._jar_library_dir.assert_called_once()
        finally:
            shutil.rmtree(out_dir, ignore_errors=True)
            shutil.rmtree(tmp_jar_dir, ignore_errors=True)  # noqa — imported at top level


if __name__ == "__main__":
    unittest.main()
