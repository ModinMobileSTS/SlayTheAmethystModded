from __future__ import annotations

import unittest

from scripts.tools.lib.sts_harness_cli import create_parser


class DecompilCliTest(unittest.TestCase):
    def test_decompil_command_in_choices(self):
        parser = create_parser()
        command_actions = [action for action in parser._actions if action.dest == "command"]
        self.assertEqual(len(command_actions), 1)
        self.assertIn("decompil", command_actions[0].choices)

    def test_decompil_targets_single(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "decompil",
            "-Target", "com.megacrit.cardcrawl.cards.AbstractCard",
        ])
        self.assertEqual(args.command, "decompil")
        self.assertEqual(args.decompil_targets, ["com.megacrit.cardcrawl.cards.AbstractCard"])

    def test_decompil_targets_multiple(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "decompil",
            "-Target", "com.megacrit.cardcrawl.cards.AbstractCard",
            "-Target", "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers",
        ])
        self.assertEqual(len(args.decompil_targets), 2)
        self.assertIn("com.megacrit.cardcrawl.cards.AbstractCard", args.decompil_targets)
        self.assertIn("com.megacrit.cardcrawl.cards.AbstractCard#applyPowers", args.decompil_targets)

    def test_decompil_accepts_empty_targets_at_cli_level(self):
        parser = create_parser()
        args = parser.parse_args(["-Command", "decompil"])
        self.assertEqual(args.command, "decompil")
        self.assertEqual(args.decompil_targets, [])


class SingleRoomCliTest(unittest.TestCase):
    def test_single_room_boss_dungeon_options(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "single-room",
            "-SingleRoomEntry", "boss",
            "-SingleRoomDungeon", "ruina:Asiyah",
            "-SingleRoomSeed", "123456789",
        ])
        self.assertEqual(args.single_room_entry, "boss")
        self.assertEqual(args.single_room_dungeon, "ruina:Asiyah")
        self.assertEqual(args.single_room_seed, "123456789")

class StartupCacheProfileCliTest(unittest.TestCase):

    def test_startup_cache_profile_command_in_choices(self):
        parser = create_parser()
        command_actions = [action for action in parser._actions if action.dest == "command"]
        self.assertEqual(len(command_actions), 1)
        self.assertIn("startup-cache-profile", command_actions[0].choices)

    def test_startup_cache_profile_options(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "startup-cache-profile",
            "-CacheHitRuns", "3",
            "-NoClearStartupCache",
        ])
        self.assertEqual(args.command, "startup-cache-profile")
        self.assertEqual(args.cache_hit_runs, 3)
        self.assertTrue(args.no_clear_startup_cache)


class ConsoleCliTest(unittest.TestCase):
    def test_console_command_in_choices(self):
        parser = create_parser()
        command_actions = [action for action in parser._actions if action.dest == "command"]
        self.assertEqual(len(command_actions), 1)
        self.assertIn("console", command_actions[0].choices)

    def test_console_command_one_shot(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "console",
            "-ConsoleCommand", "gold 999",
        ])
        self.assertEqual(args.command, "console")
        self.assertEqual(args.console_command, "gold 999")

    def test_console_command_empty_defaults(self):
        parser = create_parser()
        args = parser.parse_args(["-Command", "console"])
        self.assertEqual(args.command, "console")
        self.assertEqual(args.console_command, "")
        self.assertEqual(args.agent_port, 9099)

    def test_console_command_agent_port_override(self):
        parser = create_parser()
        for option in ("-AgentPort", "--agent-port"):
            with self.subTest(option=option):
                args = parser.parse_args(["-Command", "console", option, "19099"])
                self.assertEqual(args.agent_port, 19099)

    def test_exit_command_in_choices(self):
        parser = create_parser()
        command_actions = [action for action in parser._actions if action.dest == "command"]
        self.assertIn("exit", command_actions[0].choices)

    def test_console_command_with_device_serial(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "console",
            "-ConsoleCommand", "unlock Ironclad",
            "-DeviceSerial", "localhost:15555",
        ])
        self.assertEqual(args.command, "console")
        self.assertEqual(args.console_command, "unlock Ironclad")
        self.assertEqual(args.device_serial, "localhost:15555")


class SteamCloudSyncCliTest(unittest.TestCase):
    def test_steam_cloud_sync_command_in_choices(self):
        parser = create_parser()
        command_actions = [action for action in parser._actions if action.dest == "command"]
        self.assertEqual(len(command_actions), 1)
        self.assertIn("steam-cloud-sync", command_actions[0].choices)

    def test_steam_cloud_sync_options(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "steam-cloud-sync",
            "-CloudSyncRelativePath", "saves/test-sync.txt",
            "-CloudSyncPayload", "hello",
            "-CloudSyncSourceFile", ".\\agent-tmp\\sync-payload.txt",
            "-CloudSyncPullIntervalSeconds", "15",
        ])
        self.assertEqual(args.command, "steam-cloud-sync")
        self.assertEqual(args.cloud_sync_relative_path, "saves/test-sync.txt")
        self.assertEqual(args.cloud_sync_payload, "hello")
        self.assertEqual(args.cloud_sync_source_file, ".\\agent-tmp\\sync-payload.txt")
        self.assertEqual(args.cloud_sync_pull_interval_seconds, 15)


if __name__ == "__main__":
    unittest.main()
