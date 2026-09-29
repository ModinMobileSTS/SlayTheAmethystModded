# OpenCode V2 harness tools

`.opencode/plugins/sts-harness.ts` exposes the existing Python Android harness as scoped OpenCode tools. It **does not** reimplement the harness: each invocation runs `python3 -m scripts.tools.main sts-harness --command <command>` with argv (no shell) and returns the parsed `result.json`, status, and artifact paths. Default artifacts go to `agent-tmp/harness-tools/<timestamp>/`.

## Setup

- Use OpenCode V2; project plugins under `.opencode/plugins/` load automatically.
- Run `npm ci --prefix .opencode` after cloning to install the plugin dependency and test tools.
- Ensure Python 3, Android SDK/ADB and the Gradle wrapper are available. Override the Python executable with `STS_HARNESS_PYTHON` when needed.
- The connector daemon port is provided by `STS_CONNECTOR_PORT` or the tool's `connectorPort` argument. With either set, the harness requires an existing connector daemon. Start it separately with `python3 -m scripts.tools.connector start --port <port>`. When neither is set, the current Python harness uses port 19876 and may auto-start the daemon.
- Specify `deviceSerial` if more than one Android device is online.

## Tools

Tools in the `sts` namespace (effective OpenCode ID: `sts_<name>`):

| Tool name | Purpose |
| --- | --- |
| `get_game_status` | Inspect process, runtime, installed package, and device. |
| `control_game_lifecycle` | Install, start, stop, or gracefully exit the game. |
| `collect_game_diagnostics` | Run doctor, collect logs, or take a screenshot. |
| `run_gameplay_scenario` | Run smoke, single-room, or probe-driven play scenarios. |
| `manage_game_probe_agent` | Attach, detach, list, or check a game-probe agent. |
| `execute_game_console_command` | Send one BaseMod DevConsole command. |
| `inspect_or_reload_game_class` | Inspect a live class or redefine it from a `.class` file. |
| `decompile_installed_game_classes` | Decompile classes from the installed game. |
| `manage_enabled_mods` | List or change enabled mods. |
| `run_game_performance_tests` | Collect metrics, profile startup cache, or run a benchmark. |
| `test_steam_cloud_sync` | Exercise Steam Cloud sync and capture snapshots. |

Multi-action tools accept an `action` enum, never an arbitrary harness command. Only the one-shot console operation is exposed; no interactive console/ADB/Gradle command tool is provided. OpenCode project permissions ask before modifying device state or the perf baseline. The names follow [Anthropic's tool guidance](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools) on meaningful namespacing and informative descriptions; `sts` already identifies the service, so the tool name focuses on the action and object.

For example, request `sts.get_game_status({})` to inspect the device, or `sts.run_gameplay_scenario({action: "single-room", singleRoomMonster: "..."})` to start a test room. Tool results include `resultJson` for full details; the model-facing result omits potentially large operation logs. Output/input paths must stay inside the repository.

All harness calls are serialized in the plugin runtime because device selection and Gradle operations share connector/ADB state. Cancellation stops the Python process group (not the separate connector daemon); a cancelled or timed-out run is never reported as successful, even if a partial `result.json` exists. The lock is local to this OpenCode process, not a cross-process lock for standalone harness runs.

## Verification

```sh
npm run typecheck --prefix .opencode
npm test --prefix .opencode
```

These tests use a fake Python CLI and do not need an Android device. Integration runs still require a connected device and a working connector.
