# Bundle And Triage

## Source Of Truth

- `../../app/src/main/java/io/stamethyst/backend/diag/DiagnosticsArchiveBuilder.kt`
  - Builds every bundle: base diagnostics (JVM-log export / crash), performance bundle, and the per-domain writers.
  - Owns the archive entry names, so this file defines the layout below.
- `../../app/src/main/java/io/stamethyst/config/RuntimePaths.kt`
  - The single source for on-device paths. Archive entries generally mirror the path relative to `<stsRoot>`.
- `../../app/src/main/java/io/stamethyst/backend/feedback/FeedbackSubmissionService.kt`
  - Wraps a base diagnostics archive and adds `sts/feedback/*`, screenshots, and the final `sts-feedback-report-YYYYMMDD-HHMMSS.zip`.
- `../../app/src/main/java/io/stamethyst/backend/launch/JvmLogRotationManager.kt`
  - Keeps `latest.log` plus up to 4 archived `jvm_log_*.log` files (5 slots total).
- `../../app/src/main/java/io/stamethyst/backend/feedback/FeedbackLogAnalyzer.kt`
  - Generates `sts/feedback/latest_log_summary.txt` from only the last 256 KiB and a narrow keyword list.
- `../../app/src/main/java/io/stamethyst/backend/diag/DiagnosticsSummaryFormatter.kt`
  - Generates `sts/logs/latest_log_summary.txt` (machine summary) and the `processExit.*` / `signalDump.*` blocks in `sts/crash/summary.txt`.
- `../../app/src/main/java/io/stamethyst/backend/crash/`
  - `LatestLogCrashDetector`, `ProcessExitInfoCapture`, `ProcessExitSummary`, `SignalCrashDumpReader`, `LauncherCrashReporter` produce the crash evidence.
- `../../app/src/main/java/io/stamethyst/backend/diag/LauncherSettingsDiagnosticsFormatter.kt`
  - Renders `sts/info/launcher_settings.txt` (English machine keys) and `sts/info/launcher_settings.zh.txt` (Chinese labels/values) from one captured snapshot.
- `../../app/src/main/java/io/stamethyst/backend/diag/LauncherSettingsCatalog.kt`
  - Resolves every user-facing setting once, in both languages.
- `../../app/src/main/java/io/stamethyst/backend/diag/RawPreferencesDiagnostics.kt`
  - Appends a full raw `SharedPreferences` dump (`[Raw preferences / <file>]`) so no stored preference is missing.
- `../../app/src/main/java/io/stamethyst/ui/settings/files/SettingsFileService.kt`
  - A separate, narrower "export JVM logs" feature that still writes `sts/jvm_logs/*`. Do not confuse it with the share/crash bundles.
- `../../docs/architecture/diagnostics-log-consolidation.md`
  - Device-path to archive-path mapping and the layout rationale.
- `../../docs/backend-startup-chain.md`
  - Startup/runtime ownership and process boundaries.

## Bundle Kinds

### Feedback report (`sts-feedback-report-*.zip`)

Base diagnostics plus a `sts/feedback/` layer. The only kind that carries the user's own report.

- `sts/feedback/issue_title.txt`
- `sts/feedback/issue_body.md`
- `sts/feedback/request.json`
- `sts/feedback/enabled_mods.txt`
- `sts/feedback/latest_log_summary.txt`
- `sts/feedback/screenshots/*`

The base archive underneath is a crash bundle when `category=GAME_BUG` and `gameIssueType=CRASH`; otherwise a plain base diagnostics bundle. So a feedback report may also contain `sts/crash/summary.txt`.

### Crash report (`sts-crash-report-*.zip`)

Base diagnostics plus `sts/crash/summary.txt`. No `sts/feedback/` layer.

### JVM-log export / Share logs (`sts-jvm-logs-export-*.zip`)

Base diagnostics only. This is what "Share logs" writes.

### Performance (`sts-performance-logs-*.zip`)

A different tree, everything under `sts/performance/`:

- `sts/performance/readme.txt`
- `sts/performance/device_info.txt`
- `sts/performance/launcher_settings.txt`
- `sts/performance/frame-probe-incidents.jsonl`, `frame-probe-incidents.prev.jsonl`
- `sts/performance/latest.log`
- `sts/performance/jvm_gc.log`, `jvm_heap_snapshot.txt`
- `sts/performance/launcher_perf_snapshot.txt`
- `sts/performance/performance_launch_audit.log`
- `sts/performance/arthas-bridge.log`, `sts/performance/arthas/*`
- `sts/performance/memory_diagnostics/*`
- `sts/performance/window/window_diagnostics.log`
- `sts/performance/jvm_histograms/*`

## Base Diagnostics Layout

### Feedback Layer

Only in `sts-feedback-report-*.zip`. Read first; it captures what the user thought happened.

### Logs (`sts/logs/`)

- `latest.log` - last run only; the primary log.
- `jvm_log_*.log` - older runs; newest sorts first by file name/timestamp; up to 4 archives.
- `latest_log_summary.txt` - machine summary: `latestLog.detectedCrashMarker`, `latestLog.lastNonBlankLine`.
- `process_exit_trace.txt` - optional Android `ApplicationExitInfo` trace for an interesting exit.
- `boot_bridge_events.log` - tab-separated startup phases and heap samples.
- `startup_trace.log` - tab-separated launcher/prepare step timings.
- `jvm_gc.log`, `jvm_heap_snapshot.txt`, `last_signal_dump.txt` - optional.

### Info (`sts/info/`)

- `device_info.txt` - launcher version plus Android `Build.*` values.
- `launcher_settings.txt` - resolved settings, English keys.
- `launcher_settings.zh.txt` - same snapshot, Chinese labels.

### Crash (`sts/crash/`)

- `summary.txt` - present only in crash bundles: `crash.code`, `crash.isSignal`, `crash.detail`, then `processExit.*` and `signalDump.*` blocks.

### Domains

- `sts/resource_pack/state.txt` - resource-pack generation, checksum, migration and error state.
- `sts/achievement_sync/achievement_sync.log` - up to 3 slots.
- `sts/window/window_diagnostics.log` - up to 3 slots.
- `sts/memory_diagnostics/memory_diagnostics.log*` - up to 5 slots.
- `sts/easytier/` - `config_snapshot.txt`, `connection-state.json`, `last-session-summary.txt`, `event-*.txt`.
- `sts/steam_cloud/phase1/` - `last-operation-summary.txt`, `manifest.json`, `pull-summary.txt`, `last-websocket-cm-endpoint.txt`, `failures/*`, `login-history/*`.
- `sts/steam_login/` - `login-success-*` / `login-failed-*`, up to 5.
- `sts/steam-game-presence/` - `last-operation-summary.txt`, `events.log`.
- `sts/workshop/download_tasks/` - `index.txt` plus up to 10 task logs; raw logs under `sts/workshop/raw_download_logs/`.
- `sts/workshop/market_failed/` - `index.txt` plus up to 5 browse failures.
- `sts/workshop/auto_import_patch_logs/` - `index.txt` plus up to 10 patch logs.
- `sts/workshop/sts_jar_import_logs/` - import logs.
- `sts/launcher_crash_reports/` - `index.txt` plus up to 5 reports.
- `sts/logcat/app/` and `sts/logcat/system/` - up to 5 each; launcher logcat uses the `launcher_` prefix.

### Removed

`process_exit_info.txt`, `performance_launch_audit.log`, and `jvm_histograms/` are no longer in the base/crash/feedback bundles. The Android exit reason now lives inside `sts/crash/summary.txt`; histograms and the launch audit moved to the performance bundle.

## Important Semantics

- `sts/logs/latest.log` is authoritative for the last run. Do not confuse it with archived logs.
- `latest_log_summary.txt` is intentionally narrow. Missing keywords there do not rule out a performance or rendering problem.
- `sts/feedback/latest_log_summary.txt` and `sts/logs/latest_log_summary.txt` share a name but are different artifacts (human text vs `key=value`).
- `boot_bridge_events.log` focuses on startup:
  - a `READY` line means the boot pipeline reached game ready.
  - `FAIL` or a missing `READY` keeps suspicion on the launcher/startup chain.
  - `MEM` lines carry `heapUsed`/`heapMax`; sustained heap near `heapMax` implies JVM heap pressure.
- `sts/crash/summary.txt` only exists in crash bundles. `crash.isSignal=true` plus a `signal.*` block means a native signal, not a Java exception.
- `launcher_settings.txt` exposes the settings that actually resolved at runtime: renderer backend, render scale, MobileGlues values, JVM heap, and compatibility toggles.
- `launcher_settings.zh.txt` is the same snapshot with Chinese labels and values.
- Both settings files end with `[Raw preferences / ...]` sections holding a raw dump of every stored preference. The curated sections are resolved values; the raw sections are persisted values.

## Attribution Hints

### Likely Launcher

- The issue happens before startup reaches a `READY` event.
- Launcher logcat shows launcher exceptions or lifecycle failure.
- `sts/launcher_crash_reports/` shows a launcher process exit around the symptom time.
- Renderer, compatibility, or heap settings clearly conflict with the symptom.
- The same symptom reproduces with no mod-specific evidence in the logs.
- The problem is in export/packaging itself, not gameplay.

### Likely Mod

- Stack traces, patch failures, or repeated warnings name a mod package or class.
- The user says the issue only happens with a specific character, content pack, or mod combination.
- `request.json.feedback.suspectedMods` or the enabled-mod list lines up with the failure.
- Patching/init errors cluster around the same mod and the symptom starts after modded content loads.
- `sts/workshop/auto_import_patch_logs/*` ends in an error for the mod in question.

### Likely Device Or Driver

- Logcat or `last_signal_dump.txt` shows Adreno, Mali, ANGLE, EGL (`libEGL.so`), or framebuffer frames without strong mod evidence.
- `processExit.reason` is `REASON_SIGNALED` or `REASON_CRASH_NATIVE` with no named mod frame.
- The issue depends on renderer/backend choice or MobileGlues settings.
- The device is under memory or GPU pressure and compatibility mitigations are in play.

### Likely Mixed

- Mod-specific evidence exists, but the crash or lag becomes much worse only under a certain renderer, low memory ceiling, or compatibility profile.
- The symptom is driven by a mod, but the launcher can likely mitigate it with compatibility or settings changes.

## Known Project Clues

- Project strings explicitly warn that `mobileHudEnabled` / touchscreen UI modes can make some mod UIs render incorrectly, including Loadout console issues.
- Compatibility descriptions already map toggles to symptom families:
  - `runtimeTextureCompat`: black textures or missing textures
  - `largeTextureDownscaleCompat` / `texturePressureDownscaleDivisor`: texture/GPU pressure mitigation
  - `nonRenderableFboFormatCompat`: incomplete attachment / FBO startup crash
  - `fboIdleReclaimCompat`: lower GPU pressure, possible rebuild hitch
  - `fboPressureDownscaleCompat`: lower GPU pressure, possible blur
  - `fboManagerCompat`: alternate FBO lifetime management
  - `modManifestRootCompat`, `downfallImportCompat`, `frierenModCompat`, `vupShionModCompat`, `jacketNoAnoKoModCompat`: mod-specific import/render fixes
- Release notes mention previous attempts to fix Loadout issues. Treat Loadout patch errors as meaningful mod-side signals.
- `sts/memory_diagnostics/memory_diagnostics.log` is JSONL. Growth of `systemLowMemory`, declining `systemAvailMemBytes`, or a shrinking `javaFreeBytes` across events is device-side pressure evidence.

## Reporting Standard

When writing the final diagnosis, prefer this structure:

1. User claim and reproduction.
2. Strongest evidence from `latest.log`, `latest_log_summary.txt`, and crash data.
3. Supporting context from settings, device info, domains, and logcat.
4. Verdict: `launcher`, `mod`, `device/driver`, `mixed`, or `undetermined`.
5. One or two next actions that would raise confidence.
