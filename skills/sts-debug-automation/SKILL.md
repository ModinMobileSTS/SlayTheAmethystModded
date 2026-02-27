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
- `-PdeviceSerial=<adb-serial>`
- `-PlogsDir=<local-output-dir>`

4. Use adb fallback when Gradle tasks are unavailable or intentionally bypassed.
- Start:
  - `adb shell am start -n io.stamethyst/.LauncherActivity --es io.stamethyst.debug_launch_mode mts_basemod`
- Stop:
  - `adb shell am force-stop io.stamethyst`
- Pull one log:
  - `adb exec-out run-as io.stamethyst sh -c "cat files/sts/latestlog.txt 2>/dev/null" > latestlog.txt`
- Capture screenshot:
  - `adb shell screencap -p /sdcard/sts_screen.png`
  - `adb pull /sdcard/sts_screen.png ./sts_screen.png`
  - `adb shell rm /sdcard/sts_screen.png`

5. Export expected logs for test artifacts.
- `latestlog.txt`
- `jvm_output.log`
- `boot_bridge_events.log`
- `enabled_mods.txt`
- `last_crash_report.txt`
- `logcat.txt` (captured by `stsPullLogs`)
- `sts_screen.png` (when screenshot capture is requested)

## Response Rules

- Recommend the Gradle-task path first.
- Include both Windows and macOS/Linux command forms when giving copy-paste commands.
- Include `-PdeviceSerial` whenever multi-device ambiguity is possible.
- Report missing logs explicitly instead of silently succeeding.
- When taking screenshots, use the `screencap -> pull -> rm` sequence for shell compatibility.
