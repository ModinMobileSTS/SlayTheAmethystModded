import subprocess
import unittest
from unittest.mock import patch

from scripts.build.lib import cli, commands


class ReleaseBuildTest(unittest.TestCase):
    def test_release_reuses_native_cache_unless_explicitly_cleaned(self):
        for command in ("release", "release-fast", "release-full"):
            for clean in (False, True):
                with self.subTest(command=command, clean=clean):
                    args = [command] + (["--clean-native-cache"] if clean else [])
                    with patch.object(cli, "release_build") as build:
                        self.assertEqual(0, cli.main(args))
                    self.assertEqual(not clean, build.call_args.kwargs["skip_native_cache_cleanup"])

    def test_preflight_does_not_delete_shared_native_cache(self):
        with patch.object(commands, "release_build") as build:
            commands.local_release_check("", "")
        self.assertEqual(2, build.call_count)
        self.assertTrue(all(call.kwargs["skip_native_cache_cleanup"] for call in build.call_args_list))

    def test_gradle_uses_auto_console_without_extra_diagnostic_output(self):
        env = {"GRADLE_USER_HOME": "/test/gradle"}
        with patch.object(commands.subprocess, "run") as run, patch("builtins.print") as output:
            run.return_value = subprocess.CompletedProcess([], 0)
            commands.run_gradle([":app:assembleRelease"], env=env)
        run.assert_called_once()
        self.assertEqual(
            commands.gradle_command(
                commands.resolve_gradle_wrapper(),
                [":app:assembleRelease", "--stacktrace", "--console=auto"],
            ),
            run.call_args.args[0],
        )
        self.assertEqual(str(commands.repo_root()), run.call_args.kwargs["cwd"])
        self.assertIs(env, run.call_args.kwargs["env"])
        self.assertNotIn("timeout", run.call_args.kwargs)
        self.assertNotIn("stdout", run.call_args.kwargs)
        self.assertNotIn("stderr", run.call_args.kwargs)
        output.assert_not_called()

    def test_gradle_failure_is_not_swallowed(self):
        with patch.object(commands.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1)
            with self.assertRaisesRegex(RuntimeError, "release failed"):
                commands.run_gradle([":app:assembleRelease"], failure_message="release failed")
