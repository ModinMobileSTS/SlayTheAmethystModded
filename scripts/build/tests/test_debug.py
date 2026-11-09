import unittest
from unittest.mock import patch

from scripts.build.lib import cli, commands


class DebugBuildTest(unittest.TestCase):
    def test_cli_default_defers_to_gradle_configuration(self):
        with patch.object(cli, "build_debug") as build:
            self.assertEqual(0, cli.main(["debug"]))
        build.assert_called_once_with(None)

    def test_default_does_not_override_gradle_application_id(self):
        with patch.object(commands, "run_gradle") as run, patch("builtins.print"):
            commands.build_debug()
        run.assert_called_once_with(
            [":app:assembleDebug"], failure_message="assembleDebug failed."
        )

    def test_cli_preserves_explicit_override_and_alias(self):
        for option in ("--application-id", "-ApplicationId"):
            with self.subTest(option=option), patch.object(cli, "build_debug") as build:
                self.assertEqual(0, cli.main(["debug", option, "io.stamethyst.debug"]))
                build.assert_called_once_with("io.stamethyst.debug")

    def test_explicit_override_is_trimmed_and_forwarded(self):
        with patch.object(commands, "run_gradle") as run, patch("builtins.print"):
            commands.build_debug(" io.stamethyst.debug ")
        run.assert_called_once_with(
            [":app:assembleDebug", "-Papplication.id=io.stamethyst.debug"],
            failure_message="assembleDebug failed.",
        )

    def test_empty_explicit_override_fails_before_build(self):
        for value in ("", "  "):
            with self.subTest(value=value), patch.object(commands, "run_gradle") as run:
                with self.assertRaisesRegex(RuntimeError, "ApplicationId cannot be empty"):
                    commands.build_debug(value)
                run.assert_not_called()
