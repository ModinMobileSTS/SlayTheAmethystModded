# Backend Startup Chain (Current)

This document maps the backend launch path for SlayTheAmethyst from runtime bridge initialization to game entrypoint.

Verified against implementation on 2026-03-08:
- `MainScreenViewModel.onLaunch(...)`
- `StsGameActivity`
- `GameSessionCoordinator`
- `JvmLaunchController`
- `LaunchPreparationService`
- `StsLaunchSpec`

## 1. Backend Layers

1. Native runtime bridge (`app/src/main/jni`)
- Loads and initializes embedded Java runtime (`libjli.so`, `libjvm.so`)
- Bridges Android surface/input/audio/lifecycle into LWJGL callbacks
- Captures JVM exit/signal path and returns crash metadata to launcher

2. Java runtime bridge (`net.kdt.pojavlaunch`, `com.oracle.dalvik`, `org.lwjgl.glfw`)
- Builds runtime env vars and native library path
- Calls native `VMLauncher.launchJVM(String[] args)`
- Streams JVM logs and boot events back to Android-side UI/diagnostics

3. Launch orchestration (`io.stamethyst`)
- Installs runtime/components/mod prerequisites
- Validates jars and prepares classpath
- Builds final JVM args and starts BootBridge/STS delegate

4. UI trigger (`io.stamethyst.ui.main`)
- Main-screen launch action dispatches launch mode + settings into backend chain

## 2. Real Startup Flow

1. Android entry
- `AndroidManifest.xml` launcher activity is `io.stamethyst.LauncherActivity` (no launcher alias)
- `StsApplication.onCreate()` initializes shared bridge context via `MainActivity.init(...)`
- `LauncherActivity` stays in the default app process; `StsGameActivity` runs in the dedicated `:game` process

2. User action -> backend launch
- `MainScreenViewModel.onLaunch(...)` calls `prepareAndLaunch(...)`
- Default launch mode from main screen is `mts_basemod`
- Flow starts `StsGameActivity` directly

3. Surface ready gate
- `StsGameActivity` owns the render surface and input dispatch
- `GameSessionCoordinator` waits for valid render surface size/orientation
- Calls `startJvmOnce()` only after readiness checks pass

4. Preflight in game process
- `LaunchPreparationService.prepare(...)`
- Internally runs:
  - `ComponentInstaller.ensureInstalled(...)`
  - `RuntimePackInstaller.ensureInstalled(...)`
  - `RuntimePaths.ensureBaseDirs(...)`
  - `StsJarValidator.validate(...)`
  - MTS mode: `ModJarSupport.validate*` + `ModJarSupport.prepareMtsClasspath(...)` + `ModManager.resolveLaunchModIds(...)`

5. Runtime bootstrap
- Resolve `javaHome` under `files/runtimes/Internal`
- Resolve runtime content root via `RuntimePaths.stsRoot(context)`; this currently prefers `externalFilesDir(...)/sts` and falls back to internal `files/sts`
- Rotate/archive old log slots and redirect stdio to `RuntimePaths.latestLog(context)`
- Start boot bridge event monitor for `RuntimePaths.bootBridgeEventsLog(context)`
- Boot bridge events are now startup-only: the Android-side monitor stops after the first terminal event (`READY` or `FAIL`)
- Sync surface/display resolution to runtime config

6. Native/JVM init
- `JREUtils.relocateLibPath(...)`
- `JREUtils.setJavaEnvironment(...)`
- `JREUtils.initJavaRuntime(...)`
- `JREUtils.setupExitMethod(...)`, `JREUtils.initializeHooks(...)`, `JREUtils.chdir(...)`

7. Build and execute launch args
- `StsLaunchSpec.buildArgs(...)`
- `VMLauncher.launchJVM(...)` (JNI -> `jre_launcher.c` -> `JLI_Launch`)

8. Exit/crash return path
- `JvmLaunchController` returns exit code to `StsGameActivity`
- `StsGameActivity` forwards crash metadata via extras back to `LauncherActivity`
- Native fatal/signal path can return through `ExitActivity.showExitMessage(...)`

## 3. Launch Modes and Entrypoint

1. MTS/BaseMod mode (`mts_basemod`, default from main screen)
- JVM main class: `io.stamethyst.bridge.BootBridgeLauncher`
- Boot bridge delegate: `com.evacipated.cardcrawl.modthespire.Loader`
- Passes `--jre51`, `--skip-launcher`, and `--mods <resolved list>`
- Boot bridge emits phase/splash/ready/fail/memory events only during startup; once `READY` or `FAIL` is sent, subsequent boot events are suppressed

2. Vanilla mode (`vanilla`, debug launch entry)
- JVM main class is still `io.stamethyst.bridge.BootBridgeLauncher`
- Boot bridge delegate: `com.megacrit.cardcrawl.desktop.DesktopLauncher`
- Classpath includes patched `desktop-1.0.jar` and `gdx-patch.jar`

## 4. Key Backend Files

1. Launch orchestration
- `app/src/main/java/io/stamethyst/StsGameActivity.kt`
- `app/src/main/java/io/stamethyst/GameSessionCoordinator.kt`
- `app/src/main/java/io/stamethyst/GameSessionConfig.kt`
- `app/src/main/java/io/stamethyst/backend/launch/JvmLaunchController.kt`
- `app/src/main/java/io/stamethyst/backend/launch/LaunchPreparationService.kt`
- `app/src/main/java/io/stamethyst/backend/launch/StsLaunchSpec.kt`
- `app/src/main/java/io/stamethyst/backend/launch/ComponentInstaller.kt`
- `app/src/main/java/io/stamethyst/backend/runtime/RuntimePackInstaller.kt`
- `app/src/main/java/io/stamethyst/backend/render/DisplayConfigSync.kt`
- `app/src/main/java/io/stamethyst/backend/mods/ModJarSupport.kt`
- `app/src/main/java/io/stamethyst/backend/mods/ModManager.kt`

2. Java/native bridge
- `app/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java`
- `app/src/main/java/com/oracle/dalvik/VMLauncher.java`
- `app/src/main/java/org/lwjgl/glfw/CallbackBridge.java`
- `app/src/main/java/net/kdt/pojavlaunch/ExitActivity.java`

3. Native layer
- `app/src/main/jni/jre_launcher.c`
- `app/src/main/jni/input_bridge_v3.c`
- `app/src/main/jni/egl_bridge.c`
- `app/src/main/jni/stdio_is.c`
- `app/src/main/jni/native_hooks/exit_hook.c`

4. MTS boot bridge
- `boot-bridge/src/main/java/io/stamethyst/bridge/BootBridgeLauncher.java`

## 5. Runtime Artifacts (`RuntimePaths.stsRoot(context)`)

- `desktop-1.0.jar`
- `ModTheSpire.jar`
- `mods/*.jar`
- `enabled_mods.txt`
- `latest.log`
- `jvm_logs/jvm_log_*.log`
- `boot_bridge_events.log`
- `files/runtimes/Internal/...`
- `files/lwjgl3/lwjgl-glfw-classes.jar`

## 6. Current Maintainability Risks

1. High coupling in `StsGameActivity`
- It now focuses on lifecycle, surface hosting, and input dispatch, but still owns direct wiring for render/input/session controller creation

2. Split startup progress channels
- Startup state is distributed across preparation progress callbacks, `latest.log` parsing, and boot bridge events

3. Cross-process launcher/runtime boundary
- Launcher UI and game runtime are now intentionally split across the default process and `:game`, so shared state must continue to flow through intents, preferences, or files instead of in-memory singletons

4. Log export behavior drift risk
- Gradle `stsPullLogs` bundle and Settings "Share Logs" bundle are intentionally close but currently not identical

5. Mixed concerns in `ModJarSupport`
- Validation, compatibility patching, diagnostics, and classpath prep live in one large utility

## 7. Suggested Refactor Order

1. Freeze startup phase contract
- Define one phase schema consumed by preparation, boot bridge monitor, and boot overlay

2. Extract launch-session service
- Keep `StsGameActivity` focused on surface/input/lifecycle, continue shrinking its remaining wiring around `GameSessionCoordinator`

3. Unify log bundle assembly
- Reuse one JVM log bundle builder for both Settings export and Gradle automation task

4. Split `ModJarSupport`
- Separate manifest parsing, patching, diagnostics, and classpath building

5. Add JNI contract document
- Add `docs/backend-jni-contract.md` with callback ownership/threading/failure behavior
