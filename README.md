# SlayTheAmethyst

Android launcher for running Slay the Spire (`desktop-1.0.jar`) on Android with:
- LibGDX desktop runtime bridge
- Amethyst/Pojav JavaSE launch chain
- arm64-v8a + armeabi-v7a support

## Current Scope
- Main-screen launch defaults to `mts_basemod` mode.
- Bundled mod components include `ModTheSpire.jar`, `BaseMod.jar`, and `StSLib.jar`.
- Vanilla mode (`com.megacrit.cardcrawl.desktop.DesktopLauncher`) still exists for debug launch entry.

## Build Prerequisites
1. Set environment variable `STEAM_PATH` (or Gradle property `steam.path`) to Steam root or `steamapps`.
2. Required build-time jars are resolved from Steam/Workshop:
   - `${STEAM_PATH}/common/SlayTheSpire/desktop-1.0.jar`
   - `${STEAM_PATH}/workshop/content/646570/1605833019/BaseMod.jar`
   - `${STEAM_PATH}/workshop/content/646570/1610056683/Downfall.jar`
3. Ensure runtime pack zip exists at `runtime-pack/jre8-pojav.zip` (auto-expanded into APK assets during `preBuild`).
   Required entries inside zip:
   - `universal.tar.xz`
   - `bin-arm64.tar.xz` (or legacy `bin-aarch64.tar.xz`)
   - `bin-arm.tar.xz`
   - `version`
4. When adding/updating runtime-critical native libs, keep both ABI folders in sync:
   - `app/src/main/jniLibs/arm64-v8a`
   - `app/src/main/jniLibs/armeabi-v7a`
5. For desktop `gdx-video` mod compatibility, include both native bridge libs in:
   - `runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so`
   - `runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so`
   You can download these files from:
   - `https://github.com/ModinMobileSTS/GdxVideoDesktopAndroidNative/releases`

## Build
```bash
./gradlew.bat :app:assembleRelease
```
`assembleRelease` will produce a signed APK directly (using debug signing config).

## Debug Automation
Use Gradle tasks so the workflow is the same on Windows/macOS/Linux.

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

Options:
- Choose launch mode: `-PlaunchMode=mts_basemod` (default) or `-PlaunchMode=vanilla`
- Choose device: `-PdeviceSerial=<adb-serial>`
- Export logs to a specific directory: `-PlogsDir=<path>`

`stsPullLogs` exports:
- `latestlog.txt`
- `jvm_output.log`
- `boot_bridge_events.log`
- `enabled_mods.txt`
- `last_crash_report.txt`
- `last_signal_stack.txt` (when signal handler captured a native crash stack)
- `hs_err_pid*.log` (if JVM fatal error logs were generated)
- `logcat.txt` (all buffers, last 12000 lines)
- `logcat_crash.txt` (crash buffer, last 12000 lines)

## Runtime Setup (Device)
1. Open app settings.
2. Import `desktop-1.0.jar`.
3. Optionally import extra mod jars and save archive.
4. Return to main screen and tap Launch.

## Runtime Paths (`filesDir`)
- `files/sts/desktop-1.0.jar`
- `files/sts/ModTheSpire.jar`
- `files/sts/mods/BaseMod.jar`
- `files/sts/mods/StSLib.jar`
- `files/runtimes/Internal/...`
- `files/lwjgl3/lwjgl-glfw-classes.jar`
- `files/sts/latestlog.txt`
- `files/sts/jvm_output.log`
- `files/sts/boot_bridge_events.log`

## Documentation Map
- Entry guide and prerequisites: `README.md`
- Backend launch chain: `docs/backend-startup-chain.md`
- Agent-only workflow constraints: `requirement.md`

## Credits
This repository reuses parts of Amethyst-Android/PojavLauncher native and Java bridge code.
See `NOTICE` and `THIRD_PARTY_LICENSES.md`.

### Special Thanks
Special thanks to the Amethyst-Android (AngelAuraMC) maintainers and contributors.
Their open-source work on Android JavaSE launcher bridging provided important reference implementations and practical foundations for this project.
Without that prior engineering effort and community sharing, this repository would have taken significantly longer to reach its current state.

Project: https://github.com/AngelAuraMC/Amethyst-Android

This project currently uses a ModTheSpire variant from:
https://github.com/bwwq/ModTheSpire

We also sincerely thank all other modding and launcher developers whose public discussions, issue reports, and tooling contributions helped this project move forward.
