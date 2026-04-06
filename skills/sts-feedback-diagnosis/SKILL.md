---
name: sts-feedback-diagnosis
description: Analyze SlayTheAmethyst feedback report bundles such as `sts-feedback-report-*.zip` or extracted `sts/` directories by prioritizing `sts/feedback/issue_body.md` and `request.json`, then `sts/jvm_logs/latest.log`, archived `jvm_log_*.log`, `launcher_settings.txt`, logcat, and optional crash artifacts to diagnose whether a problem is caused by the launcher, a mod, or the device/driver. Use when Codex needs to inspect a feedback package, interpret the project's diagnostics files, or write a concrete evidence-backed diagnosis and next step.
---

# STS Feedback Diagnosis

## Quick Start

1. Treat the package as evidence for one report, not as a generic log dump.
2. If the input is a zip, run `python scripts/inspect_feedback_report.py <path-to-zip>`.
3. Read `sts/feedback/issue_body.md` first. Use `sts/feedback/request.json` to confirm category, issue type, `reproducedOnLastRun`, suspected mods, screenshots, and environment.
4. Inspect `sts/jvm_logs/latest.log` next. It is always the most recent run. Archived `jvm_log_*.log` files are older runs, newest first, up to 4 retained archives.
5. Use `sts/jvm_logs/launcher_settings.txt` and `sts/jvm_logs/device_info.txt` before assigning blame; many problems are driven by renderer, compatibility, or device constraints.
6. Use `sts/logcat/*`, `process_exit_info.txt`, `process_exit_trace.txt`, `boot_bridge_events.log`, `jvm_histograms/summary.txt`, `jvm_gc.log`, `last_signal_dump.txt`, and `sts/crash/summary.txt` as supporting evidence.
7. End with a verdict: `launcher`, `mod`, `device/driver`, `mixed`, or `undetermined`, and cite the exact files and lines that support it.

## Workflow

1. Extract the user claim before reading logs.
- Summarize the symptom, when it happens, the reproduction path, and whether the user says it reproduced on the last run.
- Prefer `sts/feedback/request.json` for structured values:
  - `feedback.category`
  - `feedback.gameIssueType`
  - `feedback.reproducedOnLastRun`
  - `feedback.suspectUnknown`
  - `feedback.suspectedMods`
  - `environment.*`
  - `enabledMods[]`
- Treat `sts/feedback/latest_log_summary.txt` as a helper only. It is generated from the last 256 KiB and can miss performance problems.

2. Inspect the last run.
- Start with `sts/jvm_logs/latest_log_summary.txt`, `sts/jvm_logs/process_exit_info.txt`, and `sts/jvm_logs/latest.log`.
- For large logs, search with `rg -n "Exception|Error|Caused by|OutOfMemory|SIG|ANR|FATAL|FAIL|READY|WARN|GLFrameBuffer|loadout" <path> -S`.
- If the symptom happens after returning to menu or starting a second run, inspect the end of `latest.log` first and compare it with the newest archived `jvm_log_*.log`.

3. Inspect launcher and device context.
- Parse `launcher_settings.txt` for:
  - `render.scale`
  - `render.surfaceBackend`
  - `render.selectionMode`
  - `render.manualBackend`
  - the `[MobileGlues]` section
  - `jvm.heapMaxMb`
  - compatibility flags in `[Compatibility]`
- Use `device_info.txt` and `request.json.environment` to identify Android version, device family, ABI, memory ceiling, and build fingerprint.
- Read `sts/logcat/logcat_app_capture.log` for launch and game-process context.
- Read `sts/logcat/launcher_logcat_app_capture.log*` when the problem looks launcher-side, UI-side, or settings-related.
- Use `boot_bridge_events.log` mainly for startup phase and heap pressure. It is not the primary source for mid-run gameplay diagnosis.

4. Attribute carefully.
- `launcher`: startup orchestration, settings, compatibility toggles, launcher process failures, packaging/export bugs, or reproducible issues without credible mod evidence.
- `mod`: stack traces or repeated warnings naming mod packages/classes, failures during patching or mod initialization, symptoms tied to modded characters/content, or suspected mods matching the symptom.
- `device/driver`: vendor GL or driver anomalies, signal/native exits without mod evidence, renderer/backend sensitivity, or memory pressure specific to the device.
- `mixed`: a mod triggers the issue but launcher settings or device constraints amplify it.
- `undetermined`: evidence is weak, stale, or contradictory.

5. Write the diagnosis in this order.
- User-reported symptom and reproduction.
- Hard evidence from the latest run.
- Supporting evidence from settings, device info, logcat, and older runs.
- Verdict with confidence.
- The most useful next debugging action or config change.

## Project-Specific Clues

- `process_exit_info.txt` only reports interesting game-process exits on Android 11+ (`CRASH`, `CRASH_NATIVE`, `ANR`, `SIGNALED`). `none` is not proof of a clean exit.
- `sts/feedback/latest_log_summary.txt` and `sts/jvm_logs/latest_log_summary.txt` are convenience summaries, not authoritative diagnostics.
- `boot_bridge_events.log` is startup-focused. If you see `READY 100`, the boot path completed; later lag often points elsewhere.
- `launcher_settings.txt` is a resolved snapshot, not raw preferences. Use it to reason about actual runtime settings.
- `mobileHudEnabled=true` can break some mod UIs. Project strings explicitly call out Loadout console display issues.
- Compatibility toggles already encode known symptom areas:
  - `virtualFboPoc`: framebuffer-construction or render-crash workaround
  - `runtimeTextureCompat`: runtime-created textures going black
  - `largeTextureDownscaleCompat`: GPU pressure mitigation at the cost of sharpness
  - `nonRenderableFboFormatCompat`: incomplete-attachment or FBO startup crashes
  - `fboIdleReclaimCompat` and `fboPressureDownscaleCompat`: GPU-memory mitigation with possible blur or rebuild stutter
- Treat repeated `loadout.LoadoutMod> Error patching ...` lines as mod-side evidence worth correlating with the symptom, especially when the problem only appears with modded characters or after starting another run.
- Treat repeated `GLFrameBuffer.NestedFrameBuffers` or other framebuffer warnings as render-pipeline evidence; correlate them with renderer settings, compatibility toggles, GPU pressure, and the affected mod content before choosing a final owner.

## Resources

- Use `scripts/inspect_feedback_report.py` for a quick structural summary of a zip or extracted report.
- Read `references/bundle-and-triage.md` for bundle composition, source files, and attribution hints.
