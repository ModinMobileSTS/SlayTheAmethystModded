# OpenCode V2 harness tools

`.opencode/plugins/sts-harness.ts` exposes the existing Python Android harness as scoped OpenCode tools. It **does not** reimplement the harness: each invocation runs `python3 -m scripts.tools.main sts-harness --command <command>` with argv (no shell) and returns the parsed `result.json`, status, and artifact paths. Default artifacts go to `agent-tmp/harness-tools/<timestamp>/`.

## Setup

- Use OpenCode V2; project plugins under `.opencode/plugins/` load automatically.
- Run `npm ci --prefix .opencode` after cloning to install the plugin dependency and test tools.
- Ensure Python 3, Android SDK/ADB and the Gradle wrapper are available. Override the Python executable with `STS_HARNESS_PYTHON` when needed.
- Specify the exact user-approved `deviceSerial` for installation and commands using raw adb/shell. Automatic discovery, historical examples, or a sole online device do not authorize package operations.
- Test tools only use the already installed app: smoke, single-room, performance, startup-cache and Steam Cloud tests never install/uninstall APKs. `skipInstall` is compatibility-only and cannot enable installation. Only the explicit lifecycle `install` action installs, with separate user authorization and a concrete `deviceSerial` (missing/`auto` values are rejected before spawning Python). Gradle automatic deployment tasks are blocked; see `AGENTS.md`.
- Copy `.env.local.example` to `.env.local` and configure the absolute SDK adb binary, independent local server port and wireless device endpoint. See `scripts/tools/connector/README.md` for isolation and lifecycle details. The local file is ignored by Git; never put a real device endpoint in tracked configuration.
- Tools automatically ensure the workspace-owned ADB and connector, and select only the configured wireless device. Old inherited `STS_CONNECTOR_PORT` values are ignored in managed mode; omit `connectorPort` and `deviceSerial`. Explicit values must match the managed instance.
- Without local configuration, legacy externally managed connectors require an explicit port; the previous fixed-port auto-start fallback has been removed.

## Direct slash command

Use **`/install-apk`** in this project to build the current working tree's Debug APK
(including uncommitted source changes) and install it on the device configured in
the ignored `.env.local`. This is a plugin command executor, not a Markdown prompt
template: it submits the existing Python harness `install` to the native session
shell endpoint (`POST /api/session/:sessionID/shell`), just like composer **`!`**,
never calls
an agent/model, never switches agents, and does not start the game.

The command accepts no arguments or attachments. Invoking it explicitly authorizes
the fixed build/install operation: unlike agent tool calls, plugin command executors
do **not** pass through `sts_control_game_lifecycle` tool permission checks.
OpenChamber renders native shell start/output/end through its existing `!` UI,
instead of synthetic user messages or heartbeat spam. The native shell owns job
lifetime; reloading this plugin no longer cancels the build. Outputs and result.json
live under `agent-tmp/harness-tools/`. Harness subprocess output can be buffered.
The command uses workspace-owned ADB but no longer holds the plugin's in-process
tool device lock: do not run concurrent device tools/install commands.

OpenCode plugin SDK 2.0.18 omits `session.shell` from the plugin context. The executor
targets the hosting process's `--hostname`/`--port` (or plugin option `serverUrl`)
and verifies `/api/info` returns its own PID before submitting any shell work.
Authentication uses the hosting server's environment, never tracked credentials.
It deliberately does **not** use CLI background-service discovery: OpenChamber's
private OpenCode and a global service may share session storage but have separate
live event buses, causing commands sent to the wrong server to be invisible.
Missing/incorrect server identity fails closed. All harness argv and the checkout
working directory are POSIX-shell quoted; this adapter requires a POSIX shell.

Installation uses `adb install -r`; the default package ID is `io.stamethyst`, not
a separate debug app. No uninstall/data wipe or signature-conflict recovery is
performed. A failed build is not followed by installation. The command passes a
720-second harness timeout; native shell execution limits/lifecycle also apply.
The existing harness imposes a separate 180-second ADB installation timeout.

OpenCode loads the command from this plugin automatically. OpenChamber must discover
and invoke server-registered commands; actual UI discovery is a separate integration
check from the offline tests below.

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

For example, request `sts.get_game_status({deviceSerial: "USER_APPROVED_SERIAL"})` to inspect the device, or `sts.run_gameplay_scenario({action: "single-room", deviceSerial: "USER_APPROVED_SERIAL", singleRoomMonster: "..."})` to start a test room. Replace the placeholder with the device approved for the current operation. Tool results include `resultJson` for full details; the model-facing result omits potentially large operation logs. Output/input paths must stay inside the repository.

All harness calls are serialized in the plugin runtime because device selection and Gradle operations share connector/ADB state. Cancellation stops the Python process group (not the separate connector daemon); a cancelled or timed-out run is never reported as successful, even if a partial `result.json` exists. The lock is local to this OpenCode process, not a cross-process lock for standalone harness runs.

## Workshop mod tools

`.opencode/plugins/sts-workshop.ts` registers **`sts_workshop`** tools for the local
agent. They use a desktop JVM CLI backed by the same `workshop-core` download engine
as the launcher, with anonymous Steam sessions and both direct URL and SteamPipe/UGC
downloads. No Steam account, API key, ADB or Android device is required.

- `search_mods`: keyword search or empty-query browsing, pagination, language,
  category tags, popularity/recent/update/subscriber sorting.
- `get_mod_details`: title, description, creator Steam ID, size, update time,
  tags, API-reported children and available download source.
- `download_mod`: download one exact `publishedFileId` into a fresh local directory;
  return paths, byte sizes, SHA-256, metadata, log and `result.json`.

Build the CLI once after cloning, and rebuild after changing the CLI or its shared
libraries (JDK 21 and the repository's existing Gradle dependencies are required):

```sh
mkdir -p agent-tmp
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :tools:workshop-agent:installDist --console=plain --max-workers=2
```

OpenCode loads the plugin automatically. Missing CLI binaries produce the build
command above. Builds are explicit, so searches do not launch Gradle or download
build dependencies. The optional plugin `executable` setting selects a prebuilt CLI.

Example Code Mode calls (item IDs must remain strings):

```js
await tools.sts_workshop.search_mods({ query: "BaseMod", pageSize: 10 })
await tools.sts_workshop.get_mod_details({ publishedFileId: "1605833019" })
await tools.sts_workshop.download_mod({ publishedFileId: "1605833019" })
```

Downloads default to `agent-tmp/workshop-mods/<id>-<unique>/`. `outDir` changes the
base directory inside this checkout; existing downloads are never overwritten.
The CLI downloads original Workshop files, without launcher-specific import,
patching, dependency installation, subscription changes or Android deployment.
API children are not guaranteed to contain every dependency. Collections are
rejected. Search uses the launcher's public HTML/SSR formats, without its
account-authenticated fallback. Steam restrictions and rate limits are surfaced
as errors. Timeouts default to 90 seconds for reads and 900 seconds for downloads;
session cancellation and plugin unload terminate the child process group.

Full results remain in `result.json`; only the first 100 downloaded files are
shown to the model. Failed/cancelled downloads retain partial artifacts and logs
with their directory in the error, but do not produce a success result.
See [`tools/workshop-agent/README.md`](../tools/workshop-agent/README.md) for the
CLI protocol, desktop Zstd adapter and offline verification.

## Verification

```sh
npm run typecheck --prefix .opencode
npm test --prefix .opencode
```

These tests use a fake Python CLI and do not need an Android device. Integration runs still require a connected device and a working connector.

## Implementation verification

For changes needing systematic post-implementation tests, use the lightweight
[verification skill](skills/post-implementation-verification/SKILL.md). It keeps acceptance criteria
before implementation, selects risk-based tests afterward and reports gaps without automatic delegation.
Routine changes still need appropriate verification even when the skill is not loaded.

## Single-slice TDD workflow (opt-in)

The six project agents under `.opencode/agents/` adapt SpireForge's single-slice workflow:
`tdd-slice` (read-only dispatcher), `tdd-red`, `tdd-green`, `tdd-refactor`, `test-runner`
(read-only verification), and `code-reviewer` (read-only review). No model/provider is pinned.
Native V2 configuration enables only two delegation levels: parent -> dispatcher -> leaf.

Only when the user explicitly requests this workflow or its skill, start with [the skill](skills/tdd-development/SKILL.md),
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
