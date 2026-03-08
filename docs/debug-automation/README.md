# Debug Automation Guide

Use Gradle tasks so the workflow is the same on Windows/macOS/Linux.

## Commands

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

## Options

- Choose launch mode: `-PlaunchMode=mts_basemod` (default) or `-PlaunchMode=vanilla`
- Choose device for `stsStart` / `stsPullLogs`: `-PdeviceSerial=<adb-serial>`
- Export logs to a specific directory: `-PlogsDir=<path>`
- Note: current `stsStop` task does not read `-PdeviceSerial` and always uses default adb target.

## `stsPullLogs` Output

- One zip bundle named `sts-jvm-logs-export-<timestamp>.zip`
- The task resolves the same runtime root as the app: external app files `.../Android/data/<package>/files/sts` when available, otherwise the legacy internal `files/sts`
- Bundle contents (Gradle task output):
  - `sts/jvm_logs/latest.log` (if present)
  - `sts/jvm_logs/boot_bridge_events.log` (if present)
  - Up to 4 archived `sts/jvm_logs/jvm_log_*.log` files (or up to 5 if `latest.log` is absent)
  - `sts/jvm_logs/README.txt` when no JVM logs are found
- Note: Settings -> Share Logs additionally includes `sts/jvm_logs/device_info.txt`.
