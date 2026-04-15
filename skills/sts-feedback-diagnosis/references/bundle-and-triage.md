# Bundle And Triage

## Source Of Truth

- `../../app/src/main/java/io/stamethyst/backend/feedback/FeedbackSubmissionService.kt`
  - Wraps the base diagnostics archive and adds `sts/feedback/*`, screenshots, and the final `sts-feedback-report-YYYYMMDD-HHMMSS.zip`.
- `../../app/src/main/java/io/stamethyst/backend/diag/DiagnosticsArchiveBuilder.kt`
  - Builds the base diagnostics bundle.
- `../../app/src/main/java/io/stamethyst/backend/launch/JvmLogRotationManager.kt`
  - Keeps `latest.log` plus up to 4 archived `jvm_log_*.log` files.
- `../../app/src/main/java/io/stamethyst/backend/feedback/FeedbackLogAnalyzer.kt`
  - Generates `sts/feedback/latest_log_summary.txt` from only the last 256 KiB and a narrow keyword list.
- `../../app/src/main/java/io/stamethyst/backend/diag/LauncherSettingsDiagnosticsFormatter.kt`
  - Generates `launcher_settings.txt` as a resolved settings snapshot.
- `../../docs/feedback.md`
  - High-level feedback system design.
- `../../docs/backend-startup-chain.md`
  - Startup/runtime ownership and process boundaries.

## Bundle Layout

### Feedback Layer

- `sts/feedback/issue_title.txt`
- `sts/feedback/issue_body.md`
- `sts/feedback/request.json`
- `sts/feedback/enabled_mods.txt`
- `sts/feedback/latest_log_summary.txt`
- `sts/feedback/screenshots/*`

Read these first. They capture what the user thought happened and how the launcher interpreted the report at submission time.

### JVM Logs

- `sts/jvm_logs/latest.log`
  - Last run only. This is the primary log for diagnosis.
- `sts/jvm_logs/jvm_log_*.log`
  - Older runs. Newest archive sorts first by file name and timestamp.
- `sts/jvm_logs/latest_log_summary.txt`
  - Crash marker summary plus the last non-blank line.
- `sts/jvm_logs/process_exit_info.txt`
  - Android process-exit metadata for interesting `:game` process exits.
- `sts/jvm_logs/process_exit_trace.txt`
  - Optional trace text from `ApplicationExitInfo`.
- `sts/jvm_logs/launcher_settings.txt`
  - Resolved launcher settings at submission time.
- `sts/jvm_logs/device_info.txt`
  - Launcher version plus Android `Build.*` values.
- `sts/jvm_logs/boot_bridge_events.log`
  - Startup phases and heap samples.
- Optional:
  - `sts/jvm_logs/jvm_gc.log`
  - `sts/jvm_logs/jvm_heap_snapshot.txt`
  - `sts/jvm_logs/last_signal_dump.txt`

### Logcat

- `sts/logcat/logcat_app_capture.log`
- `sts/logcat/logcat_system_capture.log`
- `sts/logcat/launcher_logcat_app_capture.log`
- `sts/logcat/launcher_logcat_app_capture.log.1`
- `sts/logcat/launcher_logcat_system_capture.log`

The `launcher_` prefixed files are launcher-side capture files. Use them when the fault looks UI-side, settings-side, or occurs before the game process fully takes over.

### Histograms And Crash Context

- `sts/jvm_histograms/summary.txt`
- `sts/jvm_histograms/*.txt`
- `sts/crash/summary.txt`

`sts/crash/summary.txt` is only added when a crash-category feedback report had an interesting `ProcessExitInfo`.

## Important Semantics

- `latest.log` is authoritative for the last run. Do not confuse it with the archived logs.
- `latest_log_summary.txt` is intentionally narrow. Missing keywords there do not rule out a performance or rendering problem.
- `boot_bridge_events.log` focuses on startup:
  - `READY 100` means the boot pipeline reached game ready.
  - `FAIL` or missing `READY` keeps suspicion on launcher/startup chain.
- `process_exit_info.txt` only covers Android exit reasons considered interesting:
  - `REASON_CRASH`
  - `REASON_CRASH_NATIVE`
  - `REASON_ANR`
  - `REASON_SIGNALED`
- `launcher_settings.txt` exposes the settings that actually resolved at runtime:
  - renderer backend
  - render scale
  - MobileGlues values
  - JVM heap
  - compatibility toggles

## Attribution Hints

### Likely Launcher

- The issue happens before startup reaches `READY`.
- `launcher_logcat_*` shows launcher exceptions or lifecycle failure.
- Renderer, compatibility, or heap settings clearly conflict with the symptom.
- The same symptom reproduces with no mod-specific evidence in the logs.
- The problem is in export/packaging itself, not gameplay.

### Likely Mod

- Stack traces, patch failures, or repeated warnings name a mod package or class.
- The user says the issue only happens with a specific character, content pack, or mod combination.
- `request.json.feedback.suspectedMods` or enabled mod diff lines up with the failure.
- Patching/init errors cluster around the same mod and the symptom starts after modded content loads.

### Likely Device Or Driver

- Logcat shows Adreno, Mali, ANGLE, EGL, or framebuffer warnings without strong mod evidence.
- `process_exit_info.txt` shows `REASON_SIGNALED` or `REASON_CRASH_NATIVE`.
- The issue depends on renderer/backend choice or MobileGlues settings.
- The device is under memory or GPU pressure and compatibility mitigations are in play.

### Likely Mixed

- Mod-specific evidence exists, but the crash or lag becomes much worse only under a certain renderer, low memory ceiling, or compatibility profile.
- The symptom is driven by a mod, but the launcher can likely mitigate it with compatibility or settings changes.

## Known Project Clues

- Project strings explicitly warn that `mobileHudEnabled=true` can make some mod UIs render incorrectly, including Loadout console issues.
- Compatibility descriptions already map toggles to symptom families:
  - `runtimeTextureCompat`: black textures or missing textures
  - `largeTextureDownscaleCompat`: texture/GPU pressure mitigation
  - `nonRenderableFboFormatCompat`: incomplete attachment / FBO startup crash
  - `fboIdleReclaimCompat`: lower GPU pressure, possible rebuild hitch
  - `fboPressureDownscaleCompat`: lower GPU pressure, possible blur
- Release notes mention previous attempts to fix Loadout issues. Treat Loadout patch errors as meaningful mod-side signals.

## Reporting Standard

When writing the final diagnosis, prefer this structure:

1. User claim and reproduction.
2. Strongest evidence from `latest.log` and process-exit data.
3. Supporting context from settings, device info, and logcat.
4. Verdict: `launcher`, `mod`, `device/driver`, `mixed`, or `undetermined`.
5. One or two next actions that would raise confidence.
