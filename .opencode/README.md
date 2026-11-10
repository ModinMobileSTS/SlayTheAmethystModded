# OpenCode V2 harness tools

`.opencode/plugins/sts-harness.ts` exposes the existing Python Android harness as scoped OpenCode tools. It **does not** reimplement the harness: each invocation runs `python3 -m scripts.tools.main sts-harness --command <command>` with argv (no shell) and returns the parsed `result.json`, status, and artifact paths. Default artifacts go to `agent-tmp/harness-tools/<timestamp>/`.

## Setup

- Use OpenCode V2; project plugins under `.opencode/plugins/` load automatically.
- Run `npm ci --prefix .opencode` after cloning to install the plugin dependency and test tools.
- Ensure Python 3, Android SDK/ADB and the Gradle wrapper are available. Override the Python executable with `STS_HARNESS_PYTHON` when needed.
- Copy `.env.local.example` to `.env.local` and configure the absolute SDK adb binary, independent local server port and wireless device endpoint. See `scripts/tools/connector/README.md` for isolation and lifecycle details. The local file is ignored by Git; never put a real device endpoint in tracked configuration.
- Tools automatically ensure the workspace-owned ADB and connector, and select only the configured wireless device. Old inherited `STS_CONNECTOR_PORT` values are ignored in managed mode; omit `connectorPort` and `deviceSerial`. Explicit values must match the managed instance.
- Without local configuration, legacy externally managed connectors require an explicit port; the previous fixed-port auto-start fallback has been removed.

## Tools

Tools in the `sts` namespace (effective OpenCode ID: `sts_<name>`):

| Tool name | Purpose |
| --- | --- |
| `local_adb` | Ensure, inspect or stop this workspace's local wireless ADB and connector. |
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

## Single-slice TDD workflow

The six project agents under `.opencode/agents/` adapt SpireForge's single-slice workflow:
`tdd-slice` (read-only dispatcher), `tdd-red`, `tdd-green`, `tdd-refactor`, `test-runner`
(read-only verification), and `code-reviewer` (read-only review). No model/provider is pinned.
Native V2 configuration enables only two delegation levels: parent -> dispatcher -> leaf.

Start with [the skill](skills/tdd-development/SKILL.md),
[project context](skills/tdd-development/project/slay-the-amethyst.md) and
[handoff template](skills/tdd-development/references/slice-task-template.md).
The parent confirms one behavior, accepted criteria, allowed/frozen paths and real focused commands.
Red establishes expected failure; Green freezes tests; Refactor may legitimately do nothing;
Review is mandatory. Missing infrastructure blocks the slice rather than becoming invented Red.
Initial adoption is limited to existing deterministic offline tests, not mandatory unit-test claims
for Android UI, native/driver or live-game behavior. Documentation and workflow bootstrapping are
outside behavior-slice dispatch; the parent integrates required docs before Review.

All six roles explicitly deny `sts_*` tools. Writers and runner have broad shell permissions for
multiple toolchains: scope restrictions are procedural, not a security sandbox. Audit actual diffs
and commands; do not invoke devices, live services, installation, deployment or shell bypasses.
Existing parent device approvals and MCP/plugin configuration remain unchanged.

Focused offline configuration regression:

```sh
node --test .opencode/tests/tdd-workflow.test.mjs
```

It checks checked-in V2 rule declarations, role boundaries, handoff gates and context references.
It does **not** emulate OpenCode's permission evaluator, prove runtime isolation, discover agents
or exercise nested dispatch. The full existing `npm test --prefix .opencode` also checks the fake
harness integration; type checking remains separate. No dependency installation or device is needed
when the repository's existing Node tools/dependencies are available.

An explicit maintenance self-check can dispatch static `code-reviewer`, then `test-runner` for
the command above. It is not product TDD and cannot edit files or probe denied commands.
Runtime agent discovery/nested dispatch must be reported separately if exercised; registration
alone is not permission-isolation proof. Do not launch an unrelated product slice just to demo TDD.
