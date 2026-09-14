---
name: sts-feedback-diagnosis
description: Analyze Slay the Amethyst diagnostics bundles such as `sts-feedback-report-*.zip`, `sts-crash-report-*.zip`, `sts-jvm-logs-export-*.zip`, `sts-performance-logs-*.zip`, or extracted `sts/` directories. Prioritize `sts/feedback/issue_body.md` + `request.json`, then `sts/logs/latest.log`, `sts/logs/latest_log_summary.txt`, `sts/crash/summary.txt`, `sts/logs/last_signal_dump.txt`, `sts/info/launcher_settings.txt`, `sts/info/device_info.txt`, and the per-domain folders to decide whether a problem is caused by the launcher, a mod, or the device/driver. Use when inspecting a feedback package, interpreting the project's diagnostics layout, or writing a concrete evidence-backed diagnosis and next step.
---

# STS Feedback Diagnosis

## Quick Start

1. Treat the package as evidence for one report, not as a generic log dump.
2. Run `python scripts/inspect_feedback_report.py <path-to-zip-or-dir>` for a structural summary. It resolves both the current layout and the legacy `sts/jvm_logs/` layout.
3. Identify the bundle kind first:
   - `sts-feedback-report-*.zip` -> base diagnostics + `sts/feedback/*` (user report).
   - `sts-crash-report-*.zip` -> base diagnostics + `sts/crash/summary.txt`.
   - `sts-jvm-logs-export-*.zip` -> base diagnostics only ("Share logs").
   - `sts-performance-logs-*.zip` -> `sts/performance/*` only.
4. Read `sts/feedback/issue_body.md` first when present, and use `sts/feedback/request.json` for structured values.
5. Inspect `sts/logs/latest.log`. It is the most recent run. Archived `sts/logs/jvm_log_*.log` are older runs, newest first, up to 5 slots.
6. Use `sts/crash/summary.txt`, `sts/logs/last_signal_dump.txt`, and `sts/logs/process_exit_trace.txt` for crash/native-exit evidence.
7. Use `sts/info/launcher_settings.txt` and `sts/info/device_info.txt` before assigning blame; many problems are driven by renderer, compatibility, or device constraints.
8. End with a verdict: `launcher`, `mod`, `device/driver`, `mixed`, or `undetermined`, citing exact files and lines.

## Workflow

1. Extract the user claim before reading logs.
- Summarize the symptom, when it happens, the reproduction path, and whether the user says it reproduced on the last run.
- Prefer `sts/feedback/request.json` for structured values:
  - `feedback.category` / `feedback.categoryLabel`
  - `feedback.gameIssueType` / `feedback.gameIssueTypeLabel`
  - `feedback.reproducedOnLastRun`
  - `feedback.suspectUnknown`
  - `feedback.suspectedMods[]` (each has `name`, `manifestModId`, `modId`)
  - `environment.*`
  - `enabledMods[]`
- `sts/feedback/issue_body.md` repeats the same claim in Chinese with `## 概要` / `## 详细描述` / `## 复现步骤` / `## 环境信息` / `## 启用模组快照` sections.
- Treat `sts/feedback/latest_log_summary.txt` as a helper only. It is generated from the last 256 KiB and can miss performance problems.
- Crash reports have no `sts/feedback/` layer. The claim is whatever the user said out of band; rely on `sts/crash/summary.txt`.

2. Inspect the last run.
- Start with `sts/logs/latest_log_summary.txt`, `sts/logs/latest.log`, and `sts/logs/startup_trace.log`.
- For large logs, search with `rg -n "Exception|Error|Caused by|OutOfMemory|SIG|ANR|FATAL|FAIL|READY|WARN|GLFrameBuffer|loadout" <path> -S`.
- If the symptom happens after returning to menu or starting a second run, inspect the end of `sts/logs/latest.log` first and compare it with the newest archived `sts/logs/jvm_log_*.log`.

3. Inspect launcher and device context.
- Parse `sts/info/launcher_settings.txt` (English machine keys). Curated sections are titled like `[Game / Rendering]`, `[Developer / Advanced rendering]`, `[Developer / MobileGlues]`, `[Developer / Status and logs]`, and `[Developer / Compatibility settings]`. Each field is a `key=value` line under its section.
- Key fields:
  - `Game / Rendering` -> `targetFps`, `render.scale`
  - `Developer / Advanced rendering` -> `render.surfaceBackend`, `render.selectionMode`, `render.manualBackend`, `jvm.heapMaxMb`, `gpuResourceGuardianMode`
  - `Developer / MobileGlues` -> `anglePolicy`, `multidrawMode`, `customGlVersion`, `extComputeShaderEnabled`
  - `Developer / Status and logs` -> `diag.logcatCaptureEnabled`, `diag.launcherLogcatCaptureEnabled`
  - `Developer / Compatibility settings` -> all compat toggles
- The export ships two settings files rendered from one snapshot:
  - `sts/info/launcher_settings.txt`: stable English machine keys, consumed by tooling.
  - `sts/info/launcher_settings.zh.txt`: Simplified-Chinese labels and values, for human reading (may be absent in older bundles).
- Both files end with `[Raw preferences / <file>]` sections: a full raw dump of every stored `SharedPreferences` entry. The curated sections above them hold resolved/effective values; the raw sections hold persisted values. The inspector ignores the raw sections on purpose.
- Use `sts/info/device_info.txt` and `request.json.environment` to identify Android version, device family, ABI, memory ceiling, and build fingerprint.
- Read `sts/logcat/app/*` for launch and game-process context, and `sts/logcat/system/*` for system-side reasons (low-memory kills, vendor kills).
- Use `sts/logs/boot_bridge_events.log` mainly for startup phase and heap pressure. It is not the primary source for mid-run gameplay diagnosis.

4. Inspect domain evidence as the symptom demands.
- Resource pack: `sts/resource_pack/state.txt` (ready/state/version/source/error, generation, quarantine).
- Mod import/patching: `sts/workshop/download_tasks/`, `sts/workshop/auto_import_patch_logs/`, `sts/workshop/sts_jar_import_logs/`, `sts/workshop/market_failed/`.
- Networking/LAN: `sts/easytier/config_snapshot.txt`, `sts/easytier/last-session-summary.txt`, `sts/easytier/event-*.txt`.
- Steam: `sts/steam_cloud/phase1/`, `sts/steam_login/`, `sts/steam-game-presence/`.
- Achievements: `sts/achievement_sync/achievement_sync.log`.
- Window/viewport/touch mapping: `sts/window/window_diagnostics.log`.
- Memory pressure: `sts/memory_diagnostics/memory_diagnostics.log*`.
- Launcher failures: `sts/launcher_crash_reports/index.txt` plus the individual reports.

5. Attribute carefully.
- `launcher`: startup orchestration, settings, compatibility toggles, launcher process failures, packaging/export bugs, or reproducible issues without credible mod evidence.
- `mod`: stack traces or repeated warnings naming mod packages/classes, failures during patching or mod initialization, symptoms tied to modded characters/content, or suspected mods matching the symptom.
- `device/driver`: vendor GL or driver anomalies, signal/native exits without mod evidence, renderer/backend sensitivity, or memory pressure specific to the device.
- `mixed`: a mod triggers the issue but launcher settings or device constraints amplify it.
- `undetermined`: evidence is weak, stale, or contradictory.

6. Write the diagnosis in this order.
- User-reported symptom and reproduction.
- Hard evidence from the latest run.
- Supporting evidence from settings, device info, logcat, and older runs.
- Verdict with confidence.
- The most useful next debugging action or config change.

## Project-Specific Clues

- `sts/crash/summary.txt` (`crash.code`, `crash.isSignal`, `crash.detail`, then `processExit.*` and `signalDump.*`) replaces the removed `sts/jvm_logs/process_exit_info.txt`. A `crash.detail` containing "设备压力过大" / "减少模组" points at device memory pressure over a single culprit mod.
- `sts/logs/last_signal_dump.txt` holds one block per captured signal. `SIGSEGV`/`SIGABRT` with `module=/system/lib64/libEGL.so` or `libc.so` is device/driver-side until a named mod frame appears.
- `sts/logs/process_exit_trace.txt` only appears when Android returned an interesting exit trace. Missing it is not proof of a clean exit.
- `sts/feedback/latest_log_summary.txt` and `sts/logs/latest_log_summary.txt` are convenience summaries, not authoritative diagnostics.
- `sts/logs/boot_bridge_events.log` is tab-separated: `PHASE <n> @amethyst.startup/<step>`, `SPLASH <n> ...`, `READY <n> ...`, `FAIL <n> ...`, `MEM -1 heapUsed=...;heapMax=...`. A `READY` line means the boot path completed; later lag usually points elsewhere. Heap samples approaching `heapMax` indicate JVM heap pressure (`jvm.heapMaxMb`).
- `sts/logs/startup_trace.log` is tab-separated `TRACE -1 @amethyst.trace/<step>;step=...;tookMs=...` and is the best source for "slow to start" attributions.
- `Game / Input and interaction.mobileHudEnabled` and `touchscreenInputMode` can break some mod UIs. Project strings explicitly call out Loadout console display issues.
- Compatibility toggles already encode known symptom areas:
  - `runtimeTextureCompat`: runtime-created textures going black
  - `largeTextureDownscaleCompat` / `texturePressureDownscaleDivisor`: GPU pressure mitigation at the cost of sharpness
  - `nonRenderableFboFormatCompat`: incomplete-attachment or FBO startup crashes
  - `fboIdleReclaimCompat` and `fboPressureDownscaleCompat`: GPU-memory mitigation with possible blur or rebuild stutter
  - `fboManagerCompat`: alternate FBO lifetime management
  - `globalAtlasFilterCompat`, `fragmentShaderPrecisionCompat`: rendering fidelity workarounds
  - `modManifestRootCompat`, `downfallImportCompat`, `frierenModCompat`, `vupShionModCompat`, `jacketNoAnoKoModCompat`: mod-specific import/render fixes
  - `powerIconRenderRescueCompat`, `baseModCustomMonsterRenderRescueCompat`, `nonCombatPlayerRenderRescueCompat`, `cardTooltipKeywordRescueCompat`, `hinaCharacterRenderCompat`: targeted render rescues
- Treat repeated `loadout.LoadoutMod> Error patching ...` lines as mod-side evidence worth correlating with the symptom, especially when the problem only appears with modded characters or after starting another run.
- Treat repeated `GLFrameBuffer.NestedFrameBuffers` or other framebuffer warnings as render-pipeline evidence; correlate them with renderer settings, compatibility toggles, GPU pressure, and the affected mod content before choosing a final owner.
- `sts/performance/jvm_histograms/` and `performance_launch_audit.log` exist only in `sts-performance-logs-*.zip`, not in base/crash/feedback bundles.

## Resources

- Use `scripts/inspect_feedback_report.py` for a quick structural summary of a zip or extracted report.
- Read `references/bundle-and-triage.md` for bundle composition, per-domain file meanings, and attribution hints.
