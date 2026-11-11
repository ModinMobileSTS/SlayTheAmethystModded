import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts.tools.lib.sts_harness import HarnessOptions
from scripts.tools.harness._context import HarnessContext
from scripts.tools.harness.install import run_install


class RunInstallTest(unittest.TestCase):
    def _make_ctx(self):
        return HarnessContext(
            options=HarnessOptions(
                command="install",
                launch_mode="mts_basemod",
                device_serial="",
                out_dir="",
                timeout_seconds=300,
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
            ),
            repo_root=Path("/fake/repo"),
            result={"artifacts": {}},
        )

    @patch("scripts.tools.harness.install.adb")
    @patch("scripts.tools.harness.install.gradle")
    @patch.object(Path, "glob")
    @patch.object(Path, "exists")
    def test_install_builds_and_installs(self, mock_exists, mock_glob, mock_gradle, mock_adb):
        mock_exists.return_value = True
        mock_apk = MagicMock()
        mock_apk.__str__ = MagicMock(return_value="/fake/repo/app-debug.apk")
        mock_apk.stat.return_value.st_mtime = 1000
        mock_glob.return_value = [mock_apk]
        mock_gradle.return_value = MagicMock(exit_code=0)
        mock_adb.return_value = MagicMock(exit_code=0)
        ctx = self._make_ctx()
        run_install(ctx)
        mock_gradle.assert_called_once_with(ctx, [":app:assembleDebug"])
        mock_adb.assert_called_once_with(
            ctx, ["install", "--fastdeploy", "-r", "/fake/repo/app-debug.apk"],
            timeout_seconds=180,
        )
        self.assertTrue(ctx.result["success"])
        self.assertEqual(ctx.result["status"], "INSTALLED")
