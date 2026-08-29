from __future__ import annotations

import argparse

from .sts_harness import (
    AGENT_COMMANDS,
    AUTOPLAY_MODES,
    AUTOPLAY_SAVE_MODES,
    COMMANDS,
    DEFAULT_CLOUD_SYNC_RELATIVE_PATH,
    LAUNCH_MODES,
    Harness,
    HarnessOptions,
)


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SlayTheAmethyst Android debug harness.")
    parser.add_argument("-Command", "--command", dest="command", choices=COMMANDS, default="doctor")
    parser.add_argument("-LaunchMode", "--launch-mode", dest="launch_mode", choices=LAUNCH_MODES, default="mts_basemod")
    parser.add_argument("-DeviceSerial", "--device-serial", dest="device_serial", default="")
    parser.add_argument(
        "-ConnectorPort",
        "--connector-port",
        dest="connector_port",
        type=int,
        default=None,
        help="Connector daemon TCP port. Required unless STS_CONNECTOR_PORT is set. Start with: python -m scripts.tools.connector start --port <port>",
    )
    parser.add_argument(
        "-OutDir",
        "--out-dir",
        dest="out_dir",
        default="",
        help=(
            "Output base directory for result.json and artifacts. "
            "When set, harness writes to <OutDir>/<timestamp>/ (does not clear the base). "
            "When empty, defaults to debug-artifacts/harness/<command>-<timestamp>."
        ),
    )
    parser.add_argument("-TimeoutSeconds", "--timeout-seconds", dest="timeout_seconds", type=int, default=None)
    parser.add_argument("-PollIntervalSeconds", "--poll-interval-seconds", dest="poll_interval_seconds", type=int, default=2)
    parser.add_argument("-ForceJvmCrash", "--force-jvm-crash", dest="force_jvm_crash", action="store_true")
    parser.add_argument("-ForceRuntimeCrash", "--force-runtime-crash", dest="force_runtime_crash", action="store_true")
    parser.add_argument("-DebugMode", "--debug-mode", dest="debug_mode", action="store_true",
                        help="Enable game-probe agent for diagnostics (Arthas, tracing, etc.) without autoplay.")
    parser.add_argument("-Autoplay", "--autoplay", dest="autoplay", action="store_true")
    parser.add_argument(
        "-AutoplaySaveMode",
        "--autoplay-save-mode",
        dest="autoplay_save_mode",
        choices=AUTOPLAY_SAVE_MODES,
        default="fresh",
        help="Autoplay save handling: fresh clears stale saves before starting; continue resumes the previous run when available.",
    )
    parser.add_argument(
        "-AutoplayMode",
        "--autoplay-mode",
        dest="autoplay_mode",
        choices=AUTOPLAY_MODES,
        default="normal",
         help="Autoplay run mode. single_room runs one configured combat and remains available until -Command exit.",
    )
    parser.add_argument(
        "-SingleRoomSpec",
        "--single-room-spec",
        dest="single_room_spec",
        default="",
        help="Local UTF-8 properties file for single-room mode. Supports character=, monster=, and cards=.",
    )
    parser.add_argument(
        "-SingleRoomDeviceSpec",
        "--single-room-device-spec",
        dest="single_room_device_spec",
        default="",
        help="Device-side properties path already visible to the game JVM for single-room mode.",
    )
    parser.add_argument(
        "-SingleRoomCharacter",
        "--single-room-character",
        dest="single_room_character",
        default="",
        help="Player class id/name for single-room mode, including modded character enum names.",
    )
    parser.add_argument(
        "-SingleRoomMonster",
        "--single-room-monster",
        dest="single_room_monster",
        default="",
        help="Encounter id for single-room mode. BaseMod custom encounters and vanilla MonsterHelper ids are supported.",
    )
    parser.add_argument(
        "-SingleRoomCards",
        "--single-room-cards",
        dest="single_room_cards",
        default="",
        help="Comma- or newline-separated card ids for the initial hand in single-room mode, including modded cards.",
    )
    parser.add_argument(
        "-DisableCardObtainEffectOwnershipCompat",
        "--disable-card-obtain-effect-ownership-compat",
        dest="disable_card_obtain_effect_ownership_compat",
        action="store_true",
        help=(
            "Disable the shared ShowCardAndAddToHandEffect ownership compatibility patch "
            "for repro runs."
        ),
    )
    parser.add_argument("-SkipInstall", "--skip-install", dest="skip_install", action="store_true")
    parser.add_argument("-NoStopAfterSmoke", "--no-stop-after-smoke", dest="no_stop_after_smoke", action="store_true")
    parser.add_argument(
        "-CacheHitRuns",
        "--cache-hit-runs",
        dest="cache_hit_runs",
        type=int,
        default=1,
        help="For startup-cache-profile, number of cache-hit launches after the cache-build launch.",
    )
    parser.add_argument(
        "-NoClearStartupCache",
        "--no-clear-startup-cache",
        dest="no_clear_startup_cache",
        action="store_true",
        help="For startup-cache-profile, reuse existing startup cache instead of clearing it before the build phase.",
    )
    parser.add_argument(
        "-CloudSyncRelativePath",
        "--cloud-sync-relative-path",
        dest="cloud_sync_relative_path",
        default=DEFAULT_CLOUD_SYNC_RELATIVE_PATH,
        help=(
            "For steam-cloud-sync, device-relative path under sts/ to modify before opening the launcher. "
            "Defaults to a harness-owned marker file under sts/saves/."
        ),
    )
    parser.add_argument(
        "-CloudSyncPayload",
        "--cloud-sync-payload",
        dest="cloud_sync_payload",
        default="",
        help="For steam-cloud-sync, inline UTF-8 payload to write to the target device file before launch.",
    )
    parser.add_argument(
        "-CloudSyncSourceFile",
        "--cloud-sync-source-file",
        dest="cloud_sync_source_file",
        default="",
        help="For steam-cloud-sync, local UTF-8 file to copy to the target device path before launch.",
    )
    parser.add_argument(
        "-CloudSyncPullIntervalSeconds",
        "--cloud-sync-pull-interval-seconds",
        dest="cloud_sync_pull_interval_seconds",
        type=int,
        default=10,
        help="For steam-cloud-sync, seconds between periodic Steam Cloud/log snapshots pulled from the device.",
    )
    parser.add_argument(
        "-Mods",
        "--mods",
        dest="mods",
        action="append",
        default=[],
        help="Comma- or newline-separated optional mod ids, jar names, display names, launch ids, or storage paths for set-mods. Repeatable.",
    )
    parser.add_argument(
        "-ModListFile",
        "--mod-list-file",
        dest="mod_list_file",
        default="",
        help="Local UTF-8 text file containing one optional mod token per line for set-mods. Lines starting with # are ignored.",
    )
    parser.add_argument("-EnableAllMods", "--enable-all-mods", dest="enable_all_mods", action="store_true")
    parser.add_argument("-DisableAllMods", "--disable-all-mods", dest="disable_all_mods", action="store_true")
    parser.add_argument(
        "-Target",
        "--target",
        dest="decompil_targets",
        action="append",
        default=[],
        help=(
            "Class name or Class#method to decompile. Repeatable. Example: "
            "com.megacrit.cardcrawl.cards.AbstractCard or "
            "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers"
        ),
    )
    parser.add_argument(
        "-AgentCommand",
        "--agent-command",
        dest="agent_command",
        choices=AGENT_COMMANDS,
        default="",
        help="Agent operation to run after smoke succeeds.",
    )
    parser.add_argument(
        "-AgentSpec",
        "--agent-spec",
        dest="agent_spec",
        default="",
        help="Monitor spec (e.g. tracing@classes=com.megacrit.*).",
    )
    parser.add_argument(
        "-AgentPort",
        "--agent-port",
        dest="agent_port",
        type=int,
        default=9099,
        help="TCP port for game-probe (default: 9099).",
    )
    parser.add_argument(
        "-AgentDuration",
        "--agent-duration",
        dest="agent_duration",
        type=float,
        default=0,
        help="Seconds to capture agent data before detach.",
    )
    parser.add_argument(
        "-RedefineClass",
        "--redefine-class",
        dest="redefine_class_file",
        default="",
        help="For -Command hotreload, provide a .class file to redefine in the JVM.",
    )
    parser.add_argument(
        "-ConsoleCommand",
        "--console-command",
        dest="console_command",
        default="",
        help="One-shot BaseMod DevConsole command (e.g. 'gold 999'). If omitted, enters interactive REPL mode.",
    )
    parser.add_argument(
        "-UpdateBaseline", "--update-baseline",
        dest="perf_bench_update_baseline",
        action="store_true",
        help="Overwrite the perf-bench baseline file with this run's metrics.",
    )
    parser.add_argument(
        "-PerfBenchBaseline", "--perf-bench-baseline",
        dest="perf_bench_baseline",
        default="",
        help="Path to the perf-bench baseline JSON file.",
    )
    parser.add_argument(
        "-PerfBenchEnableProfiler", "--perf-bench-enable-profiler",
        dest="perf_bench_enable_profiler",
        action="store_true",
        help="Enable async-profiler flamegraph when regressions are detected (opt-in).",
    )
    parser.add_argument(
        "-PerfBenchProfilerSeconds", "--perf-bench-profiler-seconds",
        dest="perf_bench_profiler_seconds",
        type=int,
        default=30,
        help="CPU profiling duration in seconds when regressions are found (default 30).",
    )
    parser.add_argument(
        "-PerfBenchCharacter", "--perf-bench-character",
        dest="perf_bench_character",
        default="",
        help="Autoplay character used by perf-bench (default IRONCLAD).",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = create_parser()
    args = parser.parse_args(argv)
    timeout_seconds = args.timeout_seconds
    if timeout_seconds is None:
        autoplay_like = (
            args.autoplay
            or args.command in {"single-room", "startup-cache-profile", "steam-cloud-sync"}
            or args.autoplay_mode == "single_room"
        )
        timeout_seconds = 300 if autoplay_like else 120
    options = HarnessOptions(
        command=args.command,
        launch_mode=args.launch_mode,
        device_serial=args.device_serial,
        out_dir=args.out_dir,
        timeout_seconds=timeout_seconds,
        poll_interval_seconds=args.poll_interval_seconds,
        force_jvm_crash=args.force_jvm_crash,
        force_runtime_crash=args.force_runtime_crash,
        debug_mode=args.debug_mode,
        autoplay=args.autoplay,
        autoplay_save_mode=args.autoplay_save_mode,
        autoplay_mode=args.autoplay_mode,
        single_room_spec=args.single_room_spec,
        single_room_device_spec=args.single_room_device_spec,
        single_room_character=args.single_room_character,
        single_room_monster=args.single_room_monster,
        single_room_cards=args.single_room_cards,
        disable_card_obtain_effect_ownership_compat=args.disable_card_obtain_effect_ownership_compat,
        skip_install=args.skip_install,
        no_stop_after_smoke=args.no_stop_after_smoke,
        mods=args.mods,
        mod_list_file=args.mod_list_file,
        enable_all_mods=args.enable_all_mods,
        disable_all_mods=args.disable_all_mods,
        decompil_targets=args.decompil_targets or [],
        agent_command=args.agent_command,
        agent_spec=args.agent_spec,
        agent_port=args.agent_port,
        agent_duration=args.agent_duration,
        redefine_class_file=args.redefine_class_file,
        console_command=args.console_command,
        cache_hit_runs=args.cache_hit_runs,
        no_clear_startup_cache=args.no_clear_startup_cache,
        cloud_sync_relative_path=args.cloud_sync_relative_path,
        cloud_sync_payload=args.cloud_sync_payload,
        cloud_sync_source_file=args.cloud_sync_source_file,
        cloud_sync_pull_interval_seconds=args.cloud_sync_pull_interval_seconds,
        connector_port=args.connector_port,
        perf_bench_update_baseline=getattr(args, "perf_bench_update_baseline", False),
        perf_bench_baseline=getattr(args, "perf_bench_baseline", ""),
        perf_bench_enable_profiler=getattr(args, "perf_bench_enable_profiler", False),
        perf_bench_profiler_seconds=getattr(args, "perf_bench_profiler_seconds", 30),
        perf_bench_character=getattr(args, "perf_bench_character", ""),
    )
    return Harness(options).run()
