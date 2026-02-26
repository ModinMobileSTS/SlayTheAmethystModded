# Backend Startup Chain (Current)

This document maps the backend launch path for SlayTheAmethyst, from native runtime bridge to game entrypoint.

Verified against implementation on 2026-02-26:
- `MainScreenViewModel.onLaunch(...)`
- `StsGameActivity`
- `StsLaunchSpec`
- `ComponentInstaller` and `RuntimePackInstaller`

## 1. Backend Layers

1. Native runtime bridge (`app/src/main/jni`)
- Loads and initializes embedded Java runtime (`libjli.so`, `libjvm.so`)
- Bridges Android surface/input/audio/lifecycle into LWJGL callbacks
- Captures JVM exit and returns crash metadata to Android launcher

2. Java runtime bridge (`net.kdt.pojavlaunch`, `com.oracle.dalvik`, `org.lwjgl.glfw`)
- Builds runtime env vars and native lib path
- Calls native `VMLauncher.launchJVM(String[] args)`
- Streams JVM logs back to Android UI and log files

3. Launch orchestration (`io.stamethyst`)
- Installs runtime/components/mod prerequisites
- Resolves renderer fallback and compatibility patches
- Builds final JVM args/classpath and starts STS or MTS loader

4. UI trigger (`io.stamethyst.ui.main`)
- Main-screen launch action dispatches launch mode + settings into backend chain

## 2. Real Startup Flow

1. Android entry
- `AndroidManifest.xml` launcher alias -> `io.stamethyst.LauncherActivity`
- `StsApplication.onCreate()` initializes shared bridge context via `MainActivity.init(...)`

2. User action -> backend launch
- `MainScreenViewModel.onLaunch(...)` calls `prepareAndLaunch(...)`
- Current implementation starts `StsGameActivity` directly (not `LaunchLoadingActivity`)
- Default launch mode from main screen is `mts_basemod`

3. Surface ready gate
- `StsGameActivity` waits for valid render surface size/orientation
- Calls `startJvmOnce()` only after surface readiness checks pass

4. Preflight in game process
- `ComponentInstaller.ensureInstalled(...)`
- `RuntimePackInstaller.ensureInstalled(...)`
- `StsJarValidator.validate(...)`
- MTS mode: validate bundled/imported mod jars + `ModJarSupport.prepareMtsClasspath(...)`

5. Runtime bootstrap
- Resolve `javaHome` under `files/runtimes/Internal`
- Initialize logger (`latestlog.txt`) and optional boot bridge reader
- Re-resolve renderer backend with fallback (`RendererConfig.resolveEffectiveBackend(...)`)

6. Native/JVM init
- `JREUtils.relocateLibPath(...)`
- `JREUtils.setJavaEnvironment(...)`
- `JREUtils.initJavaRuntime(...)`
- `JREUtils.setupExitMethod(...)`, `JREUtils.initializeHooks(...)`, `JREUtils.chdir(...)`

7. Build and execute launch args
- `StsLaunchSpec.buildArgs(...)`
- `VMLauncher.launchJVM(...)` (JNI -> `jre_launcher.c` -> `JLI_Launch`)

8. Exit/crash return path
- Exit hooks capture JVM exit/signal
- `ExitActivity.showExitMessage(...)` returns crash metadata to `LauncherActivity`
- Launcher shows crash dialog and optional crash report share

## 3. Launch Modes and Main Class

1. MTS/BaseMod mode (`mts_basemod`, default from main screen)
- Main class: `io.stamethyst.bridge.BootBridgeLauncher`
- Boot bridge delegates to `com.evacipated.cardcrawl.modthespire.Loader`
- Boot bridge emits phase/ready/fail events to `boot_bridge_events.log`
- `StsGameActivity` consumes events to control boot overlay and readiness

2. Vanilla mode (`vanilla`, debug launch entry)
- Classpath includes patched `desktop-1.0.jar`
- Main class: `com.megacrit.cardcrawl.desktop.DesktopLauncher`

## 4. Key Backend Files

1. Launch orchestration
- `app/src/main/java/io/stamethyst/StsGameActivity.java`
- `app/src/main/java/io/stamethyst/StsLaunchSpec.java`
- `app/src/main/java/io/stamethyst/ComponentInstaller.java`
- `app/src/main/java/io/stamethyst/RuntimePackInstaller.java`
- `app/src/main/java/io/stamethyst/RendererConfig.java`
- `app/src/main/java/io/stamethyst/ModJarSupport.java`
- `app/src/main/java/io/stamethyst/ModManager.java`

2. Java/native bridge
- `app/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java`
- `app/src/main/java/com/oracle/dalvik/VMLauncher.java`
- `app/src/main/java/org/lwjgl/glfw/CallbackBridge.java`

3. Native layer
- `app/src/main/jni/jre_launcher.c`
- `app/src/main/jni/input_bridge_v3.c`
- `app/src/main/jni/egl_bridge.c`
- `app/src/main/jni/stdio_is.c`
- `app/src/main/jni/native_hooks/exit_hook.c`

4. MTS boot bridge
- `boot-bridge/src/main/java/io/stamethyst/bridge/BootBridgeLauncher.java`

## 5. Runtime Artifacts (`filesDir`)

- `files/sts/desktop-1.0.jar`
- `files/sts/ModTheSpire.jar`
- `files/sts/mods/*.jar`
- `files/runtimes/Internal/...`
- `files/sts/latestlog.txt`
- `files/sts/jvm_output.log`
- `files/sts/last_crash_report.txt`
- `files/sts/boot_bridge_events.log`

## 6. Current Maintainability Risks

1. Split prelaunch logic
- Prelaunch checks exist in both `LaunchLoadingActivity` and `StsGameActivity`
- Current user flow bypasses `LaunchLoadingActivity`, increasing drift risk

2. High coupling in `StsGameActivity`
- Handles UI lifecycle, input bridge, boot overlay, mod readiness, and JVM orchestration in one class

3. Mixed concerns in `ModJarSupport`
- Validation, patching, diagnostics, and resource-jar extraction are all in one large class

4. Native/Java contract visibility
- JNI entrypoints are stable but scattered; no single contract doc for callbacks and ownership

## 7. Suggested Refactor Order

1. Freeze JNI contract
- Add `docs/backend-jni-contract.md` with each JNI method, caller, thread expectations, and failure behavior

2. Extract launch pipeline service
- Move preflight + runtime init + arg build from `StsGameActivity` into `GameLaunchPipeline`
- Keep `StsGameActivity` focused on surface/input/lifecycle

3. Unify prelaunch path
- Decide one canonical prelaunch path (recommended: always via `LaunchLoadingActivity`), remove duplicate checks

4. Split `ModJarSupport`
- Separate into `ModManifestReader`, `StsPatchApplier`, `CompatPatchApplier`, `MtsClasspathBuilder`

5. Add startup phase logging schema
- Keep one machine-readable startup phase log file for easier regression triage
