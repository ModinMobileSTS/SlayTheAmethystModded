---
name: sts-debug-automation
description: Automate SlayTheAmethyst Android debug operations by starting the game, force-stopping the app, exporting runtime logs, and capturing device screenshots. Use this skill when asked for one-command debug workflows, test automation setup, CI-friendly run control, log extraction to a specified local directory, or screenshot capture during test runs.
---

# STS Debug Automation

## Overview

Use project Gradle tasks as the primary interface for debug automation and use direct adb commands as fallback.

## Workflow

1. Verify prerequisites.
- Ensure Android SDK is configured (`sdk.dir`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`).
- Ensure at least one Android device/emulator is connected (`adb devices`).
- Ensure app package name is `io.stamethyst`.

2. Prefer cross-platform Gradle tasks.
- On Windows, use `.\gradlew.bat`.
- On macOS/Linux, use `./gradlew`.
- Use these tasks:
  - `:app:stsStart`
  - `:app:stsStop`
  - `:app:stsPullLogs`

3. Pass optional parameters when needed.
- `-PlaunchMode=mts_basemod` or `-PlaunchMode=vanilla`
- `-PdeviceSerial=<adb-serial>` (for `:app:stsStart` and `:app:stsPullLogs`)
- `-PlogsDir=<local-output-dir>`
- Note: current `:app:stsStop` task uses default adb target. For multi-device cases, use adb fallback with `-s`.

4. Use adb fallback when Gradle tasks are unavailable or intentionally bypassed.
- Start:
  - `adb shell am start -n io.stamethyst/.LauncherActivity --es io.stamethyst.debug_launch_mode mts_basemod`
- Stop:
  - `adb shell am force-stop io.stamethyst`
- Pull one log:
  - `adb exec-out run-as io.stamethyst sh -c "cat files/sts/latest.log 2>/dev/null" > latest.log`
- Capture screenshot:
  - `adb shell screencap -p /sdcard/sts_screen.png`
  - `adb pull /sdcard/sts_screen.png ./sts_screen.png`
  - `adb shell rm /sdcard/sts_screen.png`

5. Export expected logs for test artifacts.
- Preferred artifact from `:app:stsPullLogs`:
  - `sts-jvm-logs-export-<timestamp>.zip`
  - Zip entries:
    - `sts/jvm_logs/latest.log` (if present)
    - `sts/jvm_logs/boot_bridge_events.log` (if present)
    - up to 4 archived `sts/jvm_logs/jvm_log_*.log` files (or up to 5 if `latest.log` is absent)
    - `sts/jvm_logs/README.txt` when no JVM logs are found
- Optional manual extras (adb fallback):
  - `enabled_mods.txt` via `files/sts/enabled_mods.txt`
  - `sts_screen.png` (when screenshot capture is requested)

## Response Rules

- Recommend the Gradle-task path first.
- Include both Windows and macOS/Linux command forms when giving copy-paste commands.
- Include `-PdeviceSerial` whenever multi-device ambiguity is possible.
- Report missing logs explicitly instead of silently succeeding.
- When taking screenshots, use the `screencap -> pull -> rm` sequence for shell compatibility.
