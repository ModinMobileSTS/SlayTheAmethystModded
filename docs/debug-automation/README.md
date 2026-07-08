# Debug Automation and Harness Guide

This repository exposes device automation through two layers:

1. Gradle adb tasks owned by `:app`.
2. A Python harness at `scripts/tools/main.py sts-harness` that records machine-readable results and artifacts.

The harness is the preferred entrypoint for repeatable local debugging, CI smoke checks, and Codex-driven device work. It uses the existing Gradle tasks for launcher start/stop/log export instead of bypassing the app's launch chain.

When adding or changing harness commands or options, update both `scripts/README.md` and this guide in the same change.

## Contract

Harness commands must write `result.json` under the selected output directory. The JSON schema version is `1` and includes:

- `success`: boolean command result.
- `status`: command-specific status or observed runtime state.
- `message`: human-readable summary.
- `applicationId`: Gradle `application.id`, normally `io.stamethyst`.
- `deviceSerial`: resolved adb serial.
- `launchMode`: requested launch mode.
- `artifacts`: output paths such as `resultJson`, `logsZip`, `screenshot`, `debugApk`, and `harnessLogcat`.
- `statusSnapshot`: for `doctor`, `status`, `logs`, and `smoke`; includes `observedState`, optional `runtimeSignalState`, process pids, runtime storage root, desktop jar patch artifact state, boot bridge event summary, latest log tail summary, harness logcat crash summary, and package version.
- `deviceMods`: for `mods` and `set-mods`; includes required mods, optional mods in `sts/mods_library`, legacy runtime mods in `sts/mods`, raw `enabled_mods.txt` tokens, enabled optional mods, and `.mts_mod_file_list`.
- `modSelection`: for `set-mods`; includes requested tokens and the exact optional mod storage paths written to `enabled_mods.txt`.
- `steamCloudSync`: for `steam-cloud-sync`; includes trigger-file metadata, periodic poll snapshots, final Steam Cloud summaries, and the completion decision.
- `operations`: every native command invoked, with exit code, timestamps, duration, command line, and output tail.

Observed runtime states are intentionally limited to signals the project actually emits:

- `READY`: `boot_bridge_events.log` contains a terminal `READY` event and the `:game` process is still visible.
- `FAIL`: `boot_bridge_events.log` contains a terminal `FAIL` event.
- `CRASH_MARKER`: `latest.log` contains a known runtime crash marker.
- `LOGCAT_CRASH`: harness-captured logcat contains an Android fatal exception, native fatal signal, or mirrored game crash marker for the app before project runtime files reported a terminal state.
- `PROCESS_EXITED`: the `:game` process was observed and then disappeared before a terminal boot bridge or crash marker was captured.
- `RUNNING_WITHOUT_TERMINAL_EVENT`: the `:game` process is alive but no terminal boot event has been observed.
- `STEAM_CLOUD_SYNC_RUNNING`: the `:steamcloud` background process is alive while no launcher/game terminal state is visible.
- `PATCHING_DESKTOP_JAR`: the launcher process is alive and `desktop-1.0.jar.patching.tmp` or `.patching.backup` is present, so the app is still repairing the game jar before starting `:game`.
- `LAUNCHER_RUNNING`: the launcher process is alive but no game process or terminal event is visible.
- `NOT_RUNNING`: no tracked launcher/game/steamcloud process is visible and no terminal event was found.
- `ERROR`: harness execution failed before a valid state could be produced.

The boot bridge event format is:

```text
TYPE<TAB>PROGRESS<TAB>MESSAGE
```

`READY` and `FAIL` are terminal. The harness does not claim that the game reached a specific menu unless the boot bridge reported `READY`.

## Harness Commands

Windows:

```bat
python .\scripts\tools\main.py sts-harness -Command doctor
python .\scripts\tools\main.py sts-harness -Command install
python .\scripts\tools\main.py sts-harness -Command start -LaunchMode mts_basemod
python .\scripts\tools\main.py sts-harness -Command status
python .\scripts\tools\main.py sts-harness -Command mods
python .\scripts\tools\main.py sts-harness -Command set-mods -Mods "Downfall.jar,ReplayTheSpire"
python .\scripts\tools\main.py sts-harness -Command set-mods -ModListFile .\agent-tmp\enabled-mods.txt
python .\scripts\tools\main.py sts-harness -Command set-mods -EnableAllMods
python .\scripts\tools\main.py sts-harness -Command set-mods -DisableAllMods
python .\scripts\tools\main.py sts-harness -Command screenshot
python .\scripts\tools\main.py sts-harness -Command logs
python .\scripts\tools\main.py sts-harness -Command stop
python .\scripts\tools\main.py sts-harness -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
python .\scripts\tools\main.py sts-harness -Command smoke -Autoplay
python .\scripts\tools\main.py sts-harness -Command smoke -Autoplay -AutoplaySaveMode continue
python .\scripts\tools\main.py sts-harness -Command single-room -SingleRoomCharacter IRONCLAD -SingleRoomMonster Cultist -SingleRoomCards "Strike_R,Defend_R,Bash"
python .\scripts\tools\main.py sts-harness -Command steam-cloud-sync -CloudSyncPullIntervalSeconds 15 -SkipInstall
```

macOS/Linux:

```bash
python3 ./scripts/tools/main.py sts-harness -Command doctor
python3 ./scripts/tools/main.py sts-harness -Command mods
python3 ./scripts/tools/main.py sts-harness -Command set-mods -Mods "Downfall.jar,ReplayTheSpire"
python3 ./scripts/tools/main.py sts-harness -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
```

Common options:

- `-DeviceSerial <adb-serial>`: required when more than one device is online.
- `-OutDir <path>`: output directory for `result.json` and artifacts. Defaults to `debug-artifacts/harness/<command>-<timestamp>`.
- `-LaunchMode mts_basemod|mts|vanilla`: defaults to `mts_basemod`.
- `-TimeoutSeconds <seconds>`: smoke/status wait timeout, default `120`; direct `-Autoplay` smoke defaults to `300` unless this option is explicitly set.
- `-Autoplay`: enable the bundled autoplay driver. Requires `mts` or `mts_basemod` launch mode.
- `-AutoplaySaveMode fresh|continue`: controls autoplay save handling. `fresh` is the default and clears stale saves before starting a new run; `continue` leaves saves intact and presses Resume/Continue when the main menu exposes it, falling back to a new run without clearing if no previous save is visible.
- `-AutoplayMode normal|single_room`: selects normal long-run autoplay or a one-room combat test.
- `-DisableCardObtainEffectOwnershipCompat`: disables the bundled `ShowCardAndAddToHandEffect` ownership compatibility patch for repro runs.
- `-SingleRoomCharacter <id>` / `-SingleRoomMonster <id>` / `-SingleRoomCards <ids>`: configure the `single-room` command. Character ids may be vanilla or modded player class enum names; monster ids may be BaseMod custom encounter ids or vanilla `MonsterHelper` encounter ids; card ids may include modded cards. Card ids are comma- or newline-separated.
- `-SingleRoomSpec <path>`: local UTF-8 properties file for `single-room`, with `character=`, `monster=`, and `cards=`. Put ad hoc spec files under `agent-tmp/`.
- `-ForceJvmCrash`: expects a boot bridge `FAIL` during `smoke`.
- `-ForceRuntimeCrash`: expects a runtime crash marker during `smoke`.
- `-SkipInstall`: skip APK build/install during `smoke`.
- `-NoStopAfterSmoke`: leave the app running after `smoke`.
- `-CacheHitRuns <count>`: for `startup-cache-profile`, number of cache-hit launches after the cache-build launch. Defaults to `1`.
- `-NoClearStartupCache`: for `startup-cache-profile`, reuse the existing startup cache instead of clearing it before the first run.
- `-CloudSyncRelativePath <path>`: for `steam-cloud-sync`, device-relative path under `sts/` to modify before opening the launcher. Defaults to `saves/.amethyst-cloud-sync-harness.txt`.
- `-CloudSyncPayload <text>`: for `steam-cloud-sync`, inline UTF-8 payload to write to the target device file before launch.
- `-CloudSyncSourceFile <path>`: for `steam-cloud-sync`, local UTF-8 file to copy to the target device path before launch. Put ad hoc payload files under `agent-tmp/`.
- `-CloudSyncPullIntervalSeconds <seconds>`: for `steam-cloud-sync`, interval between pulling Steam Cloud summaries and runtime logs into `polls/<n>/`. Defaults to `10`.
- `-Mods <tokens>`: comma- or newline-separated optional mod ids, jar names, display names, launch ids, or storage paths for `set-mods`; repeatable.
- `-ModListFile <path>`: local UTF-8 file with one optional mod token per line for `set-mods`; blank lines and `#` comments are ignored.
- `-EnableAllMods`: enable every optional mod currently found in `sts/mods_library`.
- `-DisableAllMods`: disable every optional mod.

`mods` reads device state only. `set-mods` replaces the enabled optional mod selection by writing `sts/enabled_mods.txt` and deleting the stale `.mts_mod_file_list` so the next MTS launch rebuilds it. Required mods such as BaseMod, StSLib, Amethyst Runtime Compat, Amethyst Floating Tools, and Ram Saver are not controlled by `enabled_mods.txt`.

`smoke` starts a harness-owned `adb logcat` capture before launching the app. On crashes that happen before `latest.log` or `boot_bridge_events.log` can be written, `result.json` reports `LOGCAT_CRASH`, stores a short crash excerpt under `statusSnapshot.harnessLogcat.crash`, and writes the full capture to `artifacts.harnessLogcat`. Main-process desktop jar patch failures are also written as a boot bridge `FAIL` event so autoplay smoke can return a concrete failure instead of timing out in the launcher.

Autoplay randomly handles `CardRewardScreen` discovery/card reward choice pages and logs `[amethyst-autoplay] choice: ...` markers with the selected card id, source, group size, and current action. This makes scripted repros for discovery-card pages deterministic enough to reach the interaction; visual twitch detection still requires reviewing the captured screen/log evidence.

`single-room` is a harness-owned autoplay run mode. The harness writes or forwards a properties spec, pushes it to the device when needed, starts MTS autoplay with `autoplayMode=single_room`, waits until the runtime logs `[amethyst-autoplay] single_room result ...`, copies that parsed line into `statusSnapshot.latestLog.singleRoomResult`, exports logs, and stops the app. Success is reported as `SINGLE_ROOM_COMPLETE`; crashes and boot failures still use the normal `LOGCAT_CRASH`, `CRASH_MARKER`, or `FAIL` paths.

`startup-cache-profile` is a harness-owned startup timing run. By default it clears launcher/MTS startup caches, runs one MTS launch that rebuilds the cache, force-stops the app, then runs one cache-hit launch. Pass `-CacheHitRuns` to collect more hit samples or `-NoClearStartupCache` to profile an existing cache. The top-level `result.json` stores `startupCacheProfile`; each phase has its own subdirectory with `result.json`, logs, logcat, cache state before/after, detected cache mode, and extracted timing evidence from `latest.log`.

`steam-cloud-sync` is a harness-owned Steam Cloud upload validation flow. By default it writes a safe marker file under `sts/saves/` instead of touching real character saves, starts a harness-owned `adb logcat` capture, and opens `LauncherActivity` without `io.stamethyst.debug_launch_mode` so the normal Steam Cloud refresh/sync route runs before game launch. It periodically pulls `steam-cloud/last-operation-summary.txt`, `steam-cloud/push-summary.txt`, `steam-cloud/pull-summary.txt`, `steam-cloud/manifest.json`, `steam-cloud/sync-baseline.json`, `sts/latest.log`, and `sts/boot_bridge_events.log` into `polls/<n>/snapshot.json`. Success is reported only after a new `last-operation-summary.txt` records `Outcome: SUCCESS` with `Operation: manual_push` or `force_push`; any new `Outcome: FAILED` summary, launcher crash, or timeout fails the run.

## Gradle Harness Tasks

Gradle wrapper tasks call the same Python harness:

Windows:

```powershell
.\gradlew.bat :app:stsHarnessDoctor
.\gradlew.bat :app:stsHarnessInstall
.\gradlew.bat :app:stsHarnessStart
.\gradlew.bat :app:stsHarnessStatus
.\gradlew.bat :app:stsHarnessScreenshot
.\gradlew.bat :app:stsHarnessLogs
.\gradlew.bat :app:stsHarnessStop
.\gradlew.bat :app:stsHarnessSmoke
.\gradlew.bat :app:stsHarnessAutoplaySmoke
.\gradlew.bat :app:stsHarnessSingleRoom
.\gradlew.bat :app:stsHarnessStartupCacheProfile
.\gradlew.bat :app:stsHarnessSteamCloudSync
```

macOS/Linux:

```bash
./gradlew :app:stsHarnessDoctor
./gradlew :app:stsHarnessSmoke
```

Gradle properties:

- `-PdeviceSerial=<adb-serial>`
- `-PlaunchMode=mts_basemod|mts|vanilla`
- `-PharnessOutDir=<path>`
- `-PharnessTimeoutSeconds=<seconds>`
- `-PharnessPollIntervalSeconds=<seconds>`
- `-PharnessSkipInstall=true`
- `-PpythonExecutable=<python-command>`
- `-Pautoplay=true`
- `-PautoplaySaveMode=fresh|continue`
- `-PautoplayMode=normal|single_room`
- `-PdisableCardObtainEffectOwnershipCompat=true`
- `-PsingleRoomCharacter=<id>`
- `-PsingleRoomMonster=<id>`
- `-PsingleRoomCards=<comma-separated-card-ids>`
- `-PsingleRoomSpecFile=<local-properties-path>`
- `-PstartupCacheHitRuns=<count>`
- `-PstartupCacheNoClear=true`
- `-PcloudSyncRelativePath=<sts-relative-path>`
- `-PcloudSyncPayload=<inline-text>`
- `-PcloudSyncSourceFile=<local-text-path>`
- `-PcloudSyncPullIntervalSeconds=<seconds>`
- `-PforceJvmCrash=true`
- `-PforceRuntimeCrash=true`
- `-PnoStopAfterSmoke=true`

Example:

```powershell
.\gradlew.bat :app:stsHarnessSmoke -PdeviceSerial=emulator-5554 -PlaunchMode=vanilla -PharnessOutDir=debug-artifacts\harness\vanilla-smoke
.\gradlew.bat :app:stsHarnessAutoplaySmoke -PdeviceSerial=emulator-5554 -PharnessOutDir=debug-artifacts\harness\autoplay-smoke
.\gradlew.bat :app:stsHarnessAutoplaySmoke -PdeviceSerial=emulator-5554 -PautoplaySaveMode=continue -PharnessOutDir=debug-artifacts\harness\autoplay-continue
.\gradlew.bat :app:stsHarnessSingleRoom -PdeviceSerial=emulator-5554 -PsingleRoomCharacter=IRONCLAD -PsingleRoomMonster=Cultist -PsingleRoomCards=Strike_R,Defend_R,Bash -PharnessOutDir=debug-artifacts\harness\single-room
.\gradlew.bat :app:stsHarnessSingleRoom -PdeviceSerial=emulator-5554 -PsingleRoomCharacter=IRONCLAD -PsingleRoomMonster=Looter -PsingleRoomCards=Strike_R -PdisableCardObtainEffectOwnershipCompat=true -PharnessOutDir=debug-artifacts\harness\card-obtain-ownership-unpatched
.\gradlew.bat :app:stsHarnessStartupCacheProfile -PdeviceSerial=emulator-5554 -PstartupCacheHitRuns=2 -PharnessSkipInstall=true -PharnessOutDir=debug-artifacts\harness\startup-cache-profile
.\gradlew.bat :app:stsHarnessSteamCloudSync -PdeviceSerial=emulator-5554 -PcloudSyncPullIntervalSeconds=15 -PharnessOutDir=debug-artifacts\harness\steam-cloud-sync
```

`:app:stsHarnessAutoplaySmoke` and `:app:stsHarnessSmoke -Pautoplay=true` default to a 300-second harness timeout so first-run desktop jar patching can finish; pass `-PharnessTimeoutSeconds=<seconds>` to override it.

`:app:stsHarnessSteamCloudSync` always forwards `-SkipInstall` and assumes the target build is already installed on the device. Use the Python harness directly if the sync test also needs to rebuild/reinstall first.

## Low-Level Gradle Tasks

The original adb-backed Gradle tasks remain available for direct use. The Python harness now drives start/stop through direct `adb` calls so command flows do not depend on Gradle task availability; log export still tries `:app:stsPullLogs` first and then falls back to direct `adb` collection of the key Steam Cloud/runtime files if that task fails.

Unix/macOS:

```bash
./gradlew :app:stsStart
./gradlew :app:stsStop
./gradlew :app:stsPullLogs
```

Windows:

```powershell
.\gradlew.bat :app:stsStart
.\gradlew.bat :app:stsStop
.\gradlew.bat :app:stsPullLogs
```

Options:

- `-PlaunchMode=mts_basemod`, `-PlaunchMode=mts`, or `-PlaunchMode=vanilla`.
- `-PdeviceSerial=<adb-serial>`.
- `-PlogsDir=<path>`.
- `-PforceJvmCrash=true`.
- `-PforceRuntimeCrash=true`.
- `-Pautoplay=true` and `-PautoplaySaveMode=fresh|continue` for debug autoplay starts.

`stsStart` sends `io.stamethyst.debug_launch_mode` to `LauncherActivity`. The launcher then follows the normal debug route through `MainScreenViewModel`, main-process desktop jar patching when MTS needs it, `StsGameActivity`, launch preparation, and `JvmLaunchController`. `steam-cloud-sync` intentionally does not use this extra, because it must let the launcher run the pre-game Steam Cloud check/sync path.

## `stsPullLogs` Output

`stsPullLogs` writes one zip bundle named `sts-jvm-logs-export-<timestamp>.zip`. If this Gradle task fails during a Python harness run, the harness writes `artifacts.logsGradleError` and collects a fallback bundle under `logs-fallback/summary.json`.

The task resolves the same runtime root as the app:

1. external app files under `Android/data/<package>/files/sts` when readable;
2. legacy internal `files/sts` through `run-as` as fallback.

Bundle contents:

- `sts/jvm_logs/device_info.txt`
- `sts/jvm_logs/latest.log` when present
- `sts/jvm_logs/boot_bridge_events.log` when present
- `sts/jvm_logs/jvm_gc.log` when present
- `sts/jvm_logs/jvm_heap_snapshot.txt` when present
- `sts/jvm_logs/last_signal_dump.txt` when present
- up to 4 archived `sts/jvm_logs/jvm_log_*.log` files, or up to 5 if `latest.log` is absent
- memory diagnostics logs under `sts/jvm_logs/`
- up to 6 histogram files under `sts/jvm_histograms/`
- `sts/jvm_histograms/summary.txt`
- `sts/logcat/*.log*` when present
- `sts/launcher_crash_reports/sts-launcher-crash-*.txt` when present
- `sts/README.txt` when no diagnostic logs are found

## Prerequisites

- Android SDK configured through `local.properties` `sdk.dir`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or PATH.
- At least one online adb device or emulator.
- Build dependencies required by the app, including `desktop-1.0.jar` and `runtime-pack/jre8-pojav.zip`, before running install/smoke.
- Python 3.10 or newer for `scripts/tools/main.py`.
