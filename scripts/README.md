# Scripts

This directory contains repository automation entrypoints. New automation must use Python for repo-owned logic, keep implementation code under the entrypoint's `lib/` directory, and update this README in the same change when a script command or harness feature is added or changed.

PowerShell script support has been removed from `scripts/`. Use the Python entrypoints below, or the small `prepare-release.bat` / `prepare-release.sh` compatibility wrappers where they are still needed by external workflows.

## Build Entrypoint

Use `scripts/build/main.py` for build, release, and packaging tasks.

Commands:

- `debug`: build a debug APK. Option: `--application-id` / `-ApplicationId`, default `io.stamethyst.debug`.
- `release`: build the slim release APK. It downloads the resource pack on first launch.
- `release-fast`: alias of `release` kept for compatibility.
- `release-full`: build the full release APK with the resource-pack archive embedded in the APK; it uses the same runtime installation path as slim.
- `fast-release`: build the fast slim release APK and skip slow cleanup by default.
- `fast-release-slim`: alias of `fast-release`.
- `fast-release-full`: build the fast full release APK with the embedded resource-pack archive and skip slow cleanup by default.
- `prepare-release`: run local release preparation checks and signing setup validation.
- `package-cloud-function`: package `cloud-function/` into a zip.

Common release options:

- `--store-file` / `-StoreFile`: signing keystore path.
- `--key-alias` / `-KeyAlias`: signing key alias, default `upload`.
- `--skip-lint-check` / `-SkipLintCheck`: available on non-fast release commands.
- `--run-lint-check` / `-RunLintCheck`: available on fast release commands.
- `--skip-local-check` / `-SkipLocalCheck`: available on `prepare-release`.
- `--source-dir` / `-SourceDir` and `--output-zip` / `-OutputZip`: available on `package-cloud-function`.

Examples:

```bash
python scripts/build/main.py debug
python scripts/build/main.py release
python scripts/build/main.py release-full
python scripts/build/main.py fast-release
python scripts/build/main.py package-cloud-function
python scripts/build/main.py prepare-release
```

Implementation files:

- `scripts/build/main.py`: thin entrypoint.
- `scripts/build/lib/cli.py`: parses build command arguments and dispatches subcommands.
- `scripts/build/lib/commands.py`: implements Gradle builds, release preparation, and cloud function packaging.

## Tools Entrypoint

Use `scripts/tools/main.py` for debugging and device automation tools. The `harness` alias is equivalent to `sts-harness`.

**Prerequisite:** start the connector daemon and set its port before any harness command:

```bash
export STS_CONNECTOR_PORT=39999
export STS_TEST_DEVICE=localhost:15555   # optional; or pass -DeviceSerial
python -m scripts.tools.connector start --port 39999
```

Alternatively pass `-ConnectorPort 39999` / `--connector-port 39999` on each harness invocation. Harness does **not** auto-start the daemon.

Harness commands:

- `doctor`: validate connector, Gradle wrapper, package id, device selection, storage access, and runtime status signals.
- `install`: build and install the debug APK.
- `start`: start the app through `:app:stsStart`.
- `stop`: force-stop the app through `:app:stsStop`.
- `logs`: export runtime logs and a harness logcat dump.
- `screenshot`: capture a device screenshot.
- `status`: capture current process, boot bridge, crash marker, package, and storage state.
- `mods`: list device required mods, optional mods in `sts/mods_library`, legacy runtime mods in `sts/mods`, the current `enabled_mods.txt`, and the current `.mts_mod_file_list`.
- `set-mods`: replace the enabled optional mod selection by writing `enabled_mods.txt`.
- `smoke`: install when needed, clear runtime signals, start, wait for an expected state, capture screenshot/logs, and stop unless requested otherwise.
- `decompil`: pull device jars and decompile classes with CFR.
- `agent-attach` / `agent-detach` / `agent-list` / `agent-status`: game-probe monitor agents.
- `play`: interactive game-probe OBSERVE/EXEC session.
- `console`: BaseMod DevConsole via game-probe.
- `hotreload`: dump or redefine classes via game-probe.
- `perf`: attach a monitor and collect PERF stats.
- `single-room`: one-room combat autoplay test that stays running for inspection and sampling.
- `exit`: request a graceful GDX exit for the current game process. Use `stop` for force-stop fallback.
- `startup-cache-profile`: run one cache-build launch and then one or more cache-hit launches, exporting per-run logs and a startup timing summary.
- `steam-cloud-sync`: modify a device-side `sts/` file, open the launcher to trigger Steam Cloud sync, poll Steam Cloud diagnostics/runtime logs into per-interval snapshots, export the full log bundle, and stop the app.

Common harness options:

- `-ConnectorPort <port>` / `--connector-port`: connector daemon TCP port (or set `STS_CONNECTOR_PORT`).
- `-DeviceSerial <adb-serial>`: required when more than one device is online.
- `-OutDir <path>`: output base for `result.json` and artifacts. When set, each run writes under `<path>/<timestamp>/` (base is not cleared). When empty, defaults to `debug-artifacts/harness/<command>-<timestamp>`.
- `-LaunchMode mts_basemod|mts|vanilla`: defaults to `mts_basemod`.
- `-TimeoutSeconds <seconds>` and `-PollIntervalSeconds <seconds>`: runtime observation controls.
- `-Autoplay`: enable the bundled autoplay driver for MTS smoke runs.
- `-AutoplaySaveMode fresh|continue`: autoplay save handling. `fresh` clears stale saves and starts a new run; `continue` keeps saves and resumes the previous run when available.
- `-AutoplayMode normal|single_room`: selects normal long-run autoplay or a one-room combat test.
- `-DisableCardObtainEffectOwnershipCompat`: disables the bundled `ShowCardAndAddToHandEffect` ownership compatibility patch for repro runs.
- `-SingleRoomCharacter <id>` / `-SingleRoomMonster <id>` / `-SingleRoomCards <ids>`: configure the `single-room` command. Card ids are comma- or newline-separated and may include modded cards.
- `-SingleRoomSpec <path>`: local UTF-8 properties file with `character=`, `monster=`, and `cards=` for single-room tests. Put ad hoc spec files under `agent-tmp/`.
- `-ForceJvmCrash` and `-ForceRuntimeCrash`: smoke expectations for crash-path validation.
- `-DebugMode`: enable the game-probe Java agent (port 9099) for diagnostics without enabling autoplay. Required for Arthas, tracing, and OBSERVE/EXEC commands. When omitted, game-probe only starts if one of `-Autoplay`, `-ForceJvmCrash`, `-ForceRuntimeCrash`, or the launcher's performance deep diagnostics is active.
- `-SkipInstall`: skip APK build/install during `smoke`.
- `-NoStopAfterSmoke`: leave the app running after `smoke`.
- `-CacheHitRuns <count>`: for `startup-cache-profile`, number of cache-hit launches after the cache-build launch. Defaults to `1`.
- `-NoClearStartupCache`: for `startup-cache-profile`, reuse the existing startup cache instead of clearing it before the first run.
- `-CloudSyncRelativePath <path>`: for `steam-cloud-sync`, device-relative path under `sts/` to modify before opening the launcher. Defaults to `saves/.amethyst-cloud-sync-harness.txt`.
- `-CloudSyncPayload <text>`: for `steam-cloud-sync`, inline UTF-8 payload to write to the target file before launch.
- `-CloudSyncSourceFile <path>`: for `steam-cloud-sync`, local UTF-8 file to copy to the target device file before launch. Put ad hoc payload files under `agent-tmp/`.
- `-CloudSyncPullIntervalSeconds <seconds>`: for `steam-cloud-sync`, interval between pulling Steam Cloud summaries and runtime logs into `polls/<n>/`. Defaults to `10`.

Mod selection options for `set-mods`:

- `-Mods <tokens>`: comma- or newline-separated optional mod ids, jar names, display names, launch ids, or storage paths. Repeat the option to add more tokens.
- `-ModListFile <path>`: local UTF-8 text file with one token per line. Blank lines and `#` comments are ignored.
- `-EnableAllMods`: enable every optional mod currently found in `sts/mods_library`.
- `-DisableAllMods`: disable every optional mod.

`set-mods` controls optional mods only. Required mods such as BaseMod, StSLib, Amethyst Runtime Compat, Amethyst Floating Tools, and Ram Saver are controlled by app runtime/settings behavior, not by `enabled_mods.txt`.

Examples:

```bash
export STS_CONNECTOR_PORT=39999
python -m scripts.tools.connector start --port 39999

python scripts/tools/main.py sts-harness -Command doctor -DeviceSerial localhost:15555
python scripts/tools/main.py sts-harness -Command status -DeviceSerial localhost:15555
python scripts/tools/main.py sts-harness -Command mods -DeviceSerial localhost:15555
python scripts/tools/main.py sts-harness -Command set-mods -Mods "Downfall.jar,ReplayTheSpire"
python scripts/tools/main.py sts-harness -Command set-mods -ModListFile agent-tmp/enabled-mods.txt
python scripts/tools/main.py sts-harness -Command set-mods -EnableAllMods
python scripts/tools/main.py sts-harness -Command set-mods -DisableAllMods
python scripts/tools/main.py sts-harness -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
python scripts/tools/main.py sts-harness -Command smoke -Autoplay
python scripts/tools/main.py sts-harness -Command smoke -Autoplay -AutoplaySaveMode continue
python scripts/tools/main.py sts-harness -Command single-room -SingleRoomCharacter IRONCLAD -SingleRoomMonster Cultist -SingleRoomCards "Strike_R,Defend_R,Bash"
python scripts/tools/main.py sts-harness -Command exit
python scripts/tools/main.py sts-harness -Command startup-cache-profile -LaunchMode mts_basemod -CacheHitRuns 2 -SkipInstall
python scripts/tools/main.py sts-harness -Command steam-cloud-sync -CloudSyncPullIntervalSeconds 15 -SkipInstall
python scripts/tools/main.py sts-harness -Command smoke -Autoplay -DisableCardObtainEffectOwnershipCompat
python scripts/tools/main.py sts-harness -Command console -ConsoleCommand "gold 999" -DebugMode
```

Harness output is always written to `result.json`. The `mods` and `set-mods` commands add `deviceMods`; `set-mods` also adds `modSelection`. Autoplay now also logs and auto-resolves `CardRewardScreen` discovery/card reward pages. `single-room` writes the pushed spec to `artifacts.singleRoomSpec`, waits for a `[amethyst-autoplay] single_room result ...` line in `latest.log`, stores it at `statusSnapshot.latestLog.singleRoomResult`, exports logs, and then stops the app.
`startup-cache-profile` writes a top-level `startupCacheProfile` summary and a `startup-cache-profile-summary.json` artifact. Each phase also gets its own subdirectory with `result.json`, logs, logcat, cache state before/after, detected cache mode, and extracted timing evidence from `latest.log`.
`steam-cloud-sync` writes a safe marker file under `sts/saves/` by default instead of touching real character saves, starts a harness-owned `adb logcat` capture, opens `LauncherActivity` without the debug launch extra so the normal Steam Cloud refresh/sync path runs before game launch, and periodically stores `steam-cloud/last-operation-summary.txt`, `steam-cloud/push-summary.txt`, `steam-cloud/pull-summary.txt`, `steam-cloud/manifest.json`, `steam-cloud/sync-baseline.json`, `sts/latest.log`, and `sts/boot_bridge_events.log` under `polls/<n>/snapshot.json`. Success requires a new `last-operation-summary.txt` with `Outcome: SUCCESS` and `Operation: manual_push` or `force_push`. Final log export tries `:app:stsPullLogs` first and falls back to direct adb collection under `logs-fallback/summary.json` if Gradle log export is unavailable.

## Architecture

The tools directory is organized into modular components, each with single
responsibility:

| Module | Role |
|--------|------|
| `connector/` | 常驻守护进程，设备连接管理、TCP 透传代理、adb 命令代理 |
| `lib/agent_client.py` | game-probe 协议客户端（通过 connector.connect_stream 通信） |
| `lib/env_device.py` | 从 `STS_TEST_DEVICE` 环境变量读取设备序列号，所有测试和脚本共用 |
| `arthas/` | Arthas JVM 诊断工具集成（通过 connector + arthas-bridge） |
| `harness/` | 高层编排（build, install, smoke, single-room, startup-cache） |
| `autoplay/` | 游戏自动化控制 |
| `monitor/` | 日志采集、截图、文件拉取 |

各模块通过 `connector` daemon 访问设备，**不直接调用 adb**，**不直接开 TCP 连接到设备服务**。
Gradle 与本地 CFR 等主机侧工具仍本地执行。Connector 使用纯 Python TCP (127.0.0.1)，通过 `STS_CONNECTOR_PORT` 或 `-ConnectorPort` 发现服务。
详见各模块目录下的 `README.md`。

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `STS_CONNECTOR_PORT` | Connector daemon 的 TCP 端口 | 必填（harness 也可传 `-ConnectorPort`） |
| `STS_TEST_DEVICE` | 集成测试的默认设备序列号 | `auto` |

```bash
export STS_CONNECTOR_PORT=39999
export STS_TEST_DEVICE=localhost:15555
python -m scripts.tools.connector start --port 39999
```

```
harness / arthas / autoplay / monitor
         │
         ▼
    ┌─────────────┐
    │  connector  │  ← TCP 127.0.0.1:<port>
    └──────┬──────┘     STS_CONNECTOR_PORT / -ConnectorPort
           │ adb only here
           ▼
     Android Device
     ├── game-probe (:9099)   ← OBSERVE / EXEC / LOAD_AGENT (via connect_stream)
     └── arthas-bridge    (:8099)  ← ArthasBootstrapCompat (无 Netty)
```

Implementation files:

- `scripts/tools/main.py`: thin tools entrypoint.
- `scripts/tools/connector/`: 设备通信守护进程及其客户端库（shell/push/pull/install/adb/logcat/connect_stream）。
- `scripts/tools/lib/agent_client.py`: 统一 game-probe TCP 协议客户端（经 connector `connect_stream`）。
- `scripts/tools/lib/sts_harness_cli.py`: harness CLI 解析器。
- `scripts/tools/lib/sts_harness.py`: harness 编排与部分命令实现；设备 I/O 委托 `harness/_runner` → connector。
- `scripts/tools/harness/`: 按命令拆分的 harness 实现（doctor/install/smoke/agent/console/…）。
- `scripts/tools/lib/device_mods.py`: 列出设备 mod、解析可选 mod token、写入 `enabled_mods.txt`。
