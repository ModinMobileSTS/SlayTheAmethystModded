# SlayTheAmethyst

SlayTheAmethyst is an Android launcher for running Slay the Spire on Android with:
- 🧩 compatibility with nearly all Steam Workshop mods, including large mod stacks
- 🖥️ full desktop gameplay features on Android through the JavaSE desktop runtime bridge
- 📱 Android-specific control and UI optimizations for touch input and mobile screens
- ⚙️ arm64-v8a + armeabi-v7a support for broader device coverage

## 🧰 Build Prerequisites
1. Set environment variable `STEAM_PATH` (or Gradle property `steam.path`) to Steam root or `steamapps` with sts installed.
2. Required build-time jar:
   - `${STEAM_PATH}/common/SlayTheSpire/desktop-1.0.jar` (used by `:patches:gdx-patch` compileOnly)
   - Core mod jars (`ModTheSpire.jar`, `BaseMod.jar`, `StSLib.jar`) are bundled from app assets, not resolved from Steam Workshop during build.
3. Ensure runtime pack zip exists at `runtime-pack/jre8-pojav.zip`
   You can download these files from:
   - `https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/tag/pojav-jre8`
4. For desktop `gdx-video` mod compatibility, include both native bridge libs in:
   - `runtime-pack/gdx_video_natives/`
   You can download these files from:
   - `https://github.com/ModinMobileSTS/GdxVideoDesktopAndroidNative/releases`

## 🛠️ Build

### 🐞 Debug build
Use this for daily development/debug builds without release signing.

```bash
./gradlew.bat :app:assembleDebug
```

### 🚀 Release build
Use this for signed release APK output.

CI release automation and onboarding:
- [docs/release-automation/README.md](docs/release-automation/README.md)

## 🤖 Debug Automation
Debug automation operations and onboarding are documented in:
- [docs/debug-automation/README.md](docs/debug-automation/README.md)

## 🙏 Credits
This repository reuses parts of Amethyst-Android/PojavLauncher native and Java bridge code.
See `NOTICE` and `THIRD_PARTY_LICENSES.md`.

### 🌟 Special Thanks
Special thanks to the Amethyst-Android (AngelAuraMC) maintainers and contributors.
Their open-source work on Android JavaSE launcher bridging provided important reference implementations and practical foundations for this project.
Without that prior engineering effort and community sharing, this repository would have taken significantly longer to reach its current state.

Project: https://github.com/AngelAuraMC/Amethyst-Android

This project currently uses a ModTheSpire variant from:
https://github.com/bwwq/ModTheSpire

We also sincerely thank all other modding and launcher developers whose public discussions, issue reports, and tooling contributions helped this project move forward.
