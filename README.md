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
2. Required build-time jar:
   - `${STEAM_PATH}/common/SlayTheSpire/desktop-1.0.jar` (used by `:patches:gdx-patch` compileOnly)
   - Core mod jars (`ModTheSpire.jar`, `BaseMod.jar`, `StSLib.jar`) are bundled from app assets, not resolved from Steam Workshop during build.
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

## CI Release Build (Private Dependency Bundle)
GitHub-hosted runners do not have your local Steam/runtime files. For release workflow, prepare one private dependency archive and host it in a private location.

Expected archive format (`.tar.gz`):
- `build-deps/steamapps/common/SlayTheSpire/desktop-1.0.jar`
- `build-deps/runtime-pack/jre8-pojav.zip`
- `build-deps/runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so`
- `build-deps/runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so`

Required `release-signing` environment settings:
- Variables:
  - `BUILD_DEPS_RELEASE_TAG` (tag that contains the dependency asset, example `deps-20260305`)
  - `BUILD_DEPS_ASSET_NAME` (optional, default `build-deps.tar.gz`)
  - `BUILD_DEPS_REPO` (optional, default current repository, format `owner/repo`)
  - `BUILD_DEPS_SHA256` (archive sha256 checksum)
- Secrets:
  - `BUILD_DEPS_GH_TOKEN` (optional; required when downloading from another private repository)
  - `ANDROID_KEYSTORE_BASE64`
  - `RELEASE_STORE_PASSWORD`
  - `RELEASE_KEY_ALIAS`
  - `RELEASE_KEY_PASSWORD`

The workflow downloads the archive from GitHub Release via `gh release download`, verifies `BUILD_DEPS_SHA256`, unpacks it, sets `STEAM_PATH` to the unpacked `steamapps`, and copies `runtime-pack` into workspace before `:app:assembleRelease`.

## Build
```bash
./gradlew.bat :app:assembleRelease
```
`assembleRelease` requires release signing environment variables (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).

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
- Choose device for `stsStart` / `stsPullLogs`: `-PdeviceSerial=<adb-serial>`
- Export logs to a specific directory: `-PlogsDir=<path>`
- Note: current `stsStop` task does not read `-PdeviceSerial` and always uses default adb target.

`stsPullLogs` exports:
- One zip bundle named `sts-jvm-logs-export-<timestamp>.zip`
- Bundle contents (Gradle task output):
  - `sts/jvm_logs/latest.log` (if present)
  - `sts/jvm_logs/boot_bridge_events.log` (if present)
  - Up to 4 archived `sts/jvm_logs/jvm_log_*.log` files (or up to 5 if `latest.log` is absent)
  - `sts/jvm_logs/README.txt` when no JVM logs are found
- Note: Settings -> Share Logs additionally includes `sts/jvm_logs/device_info.txt`.

## Runtime Setup (Device)
1. On first launch, if `desktop-1.0.jar` is missing, app enters Quick Start.
2. Import `desktop-1.0.jar` from Quick Start (or Settings).
3. Optionally import extra non-core mod jars and save archive.
   Core jars (`BaseMod`, `StSLib`, `ModTheSpire`) are launcher-managed and blocked from manual import.
4. Return to main screen and tap Launch.

## Runtime Paths (`filesDir`)
- `files/sts/desktop-1.0.jar`
- `files/sts/ModTheSpire.jar`
- `files/sts/mods/BaseMod.jar`
- `files/sts/mods/StSLib.jar`
- `files/sts/enabled_mods.txt`
- `files/sts/latest.log`
- `files/sts/jvm_logs/jvm_log_*.log`
- `files/sts/boot_bridge_events.log`
- `files/runtimes/Internal/...`
- `files/lwjgl3/lwjgl-glfw-classes.jar`

## Documentation Map
- Entry guide and prerequisites: `README.md`
- Backend launch chain: `docs/backend-startup-chain.md`
- Debug automation skill reference: `skills/sts-debug-automation/SKILL.md`

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
