# Local ADB MCP

`sts.local_adb` uses the workspace-owned ADB server and the device configured in
ignored `.env.local`. It retains `ensure`, `status`, and `stop`, and supports the
following structured actions. It does not accept arbitrary shell commands.

| action | Parameters | Behavior |
| --- | --- | --- |
| `device-info` | — | Model, Android version, SDK, ABI, boot-completed state |
| `package-info` | `packageName` | Android package details |
| `install` | `apkPath`, **`deviceSerial`**, optional `fastDeploy` | Install an existing repository APK; no build |
| `list-instrumentation` | — | List installed test runners |
| `instrument` | `runner`, optional `testClasses`, `extras` | Run installed instrumentation; never installs |
| `screenshot` | — | Capture PNG to a local artifact |
| `tap` | `x`, `y` | Tap screen coordinates |
| `swipe` | `x`, `y`, `endX`, `endY`, optional `durationMs` | Swipe; default duration 300 ms |
| `keyevent` | `keyCode` | Send numeric Android key code |
| `start-activity` | `component` | Start `package/activity`, waiting for launch |
| `force-stop` | `packageName` | Force-stop an application |

Structured actions accept optional `timeoutSeconds` (1–3600; install defaults
to 180, others to 120) and `deviceSerial`. If supplied, the serial must exactly
match local configuration. Installation always requires a separately authorized,
explicit serial; configuration alone is not authorization. `fastDeploy` defaults
to true, but ADB can fall back to a full install. Set false only when deliberately
testing that path. APK paths must resolve to existing `.apk` files inside the
repository, including through symlinks.

`instrument` accepts runner names such as `io.stamethyst.test/androidx.test.runner.AndroidJUnitRunner`,
`testClasses: ["io.stamethyst.iotask.FileIoInstrumentedTest"]`, and string-valued
`extras`. Use `testClasses`, not an extra named `class`. Arguments are quoted for
the Android shell; metacharacters in extra values remain data. Instrumentation
can modify app data: request approval as with other state-changing operations.
The returned exit code is ADB's exit code; inspect the test output too, since
Android runners can report test failures while ADB exits zero.

MCP retains the `sts_local_adb` permission and the shared harness device lock.
Results retain the existing execution envelope (`exitCode`, `cancelled`,
`timedOut`, `stdout`, `stderr`). For structured actions, parse `stdout` as JSON
to obtain the operation exit code, bounded output preview, and artifact path.
Full stdout or screenshot artifacts are uniquely named under
`agent-tmp/local-adb/operations`; stderr is a bounded preview. Device information
artifacts contain only the selected fields. No uninstall, clear-data, reboot,
file transfer, or arbitrary shell action is exposed.

CLI uses the same validation and implementation:

```sh
python3 -m scripts.tools.local_adb device-info --request '{}'
python3 -m scripts.tools.local_adb instrument --request '{"runner":"io.stamethyst.test/androidx.test.runner.AndroidJUnitRunner","testClasses":["io.stamethyst.iotask.FileIoInstrumentedTest"]}'
```

Existing CLI `adb -- ...` and `run -- ...` entry points are unchanged. The game
lifecycle `install` remains separate: it builds and installs the main debug APK,
whereas this generic `install` accepts an already-built main, test, or benchmark APK.

Offline verification:

```sh
python3 -m unittest scripts.tools.tests.test_local_adb scripts.tools.tests.test_local_adb_actions
node --test .opencode/tests/sts-harness.test.mjs
npm run typecheck --prefix .opencode
```
