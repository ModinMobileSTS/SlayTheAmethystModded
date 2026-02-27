# SlayTheAmethyst

Android launcher for running Slay the Spire (`desktop-1.0.jar`) on Android with:
- LibGDX desktop runtime bridge
- Amethyst/Pojav JavaSE launch chain
- arm64-v8a + armeabi-v7a support

## Current Scope (Verified 2026-02-26)
- Main-screen launch defaults to `mts_basemod` mode.
- Bundled mod components include `ModTheSpire.jar`, `BaseMod.jar`, and `StSLib.jar`.
- Vanilla mode (`com.megacrit.cardcrawl.desktop.DesktopLauncher`) still exists for debug launch entry.

## Build Prerequisites (Developers)
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

## Build
```bash
./gradlew :app:assembleDebug
```

## Debug Automation (Cross-Platform)
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
- `logcat.txt` (last 2000 lines)

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
