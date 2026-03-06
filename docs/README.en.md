<p align="center">
  <img src="./assets/readme-icon-rounded.svg" alt="SlayTheAmethyst icon" width="96" height="96" />
</p>

<p align="center">
  <a href="../README.md">简体中文</a> | English
</p>

<h1 align="center">SlayTheAmethyst</h1>

<p align="center">
  An Android launcher for <strong>modded Slay the Spire</strong>, built to run most desktop mods on mobile.
</p>

<p align="center">
  <a href="https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases">
    <img alt="Download Latest APK" src="https://img.shields.io/badge/Download-Latest%20APK-3fb950?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  <a href="#quick-start">
    <img alt="Quick Start" src="https://img.shields.io/badge/Build-Quick%20Start-6f42c1?style=for-the-badge&logo=gradle&logoColor=white" />
  </a>
  <a href="./debug-automation/README.md">
    <img alt="Debug Automation" src="https://img.shields.io/badge/Docs-Debug%20Automation-0969da?style=for-the-badge&logo=androidstudio&logoColor=white" />
  </a>
</p>

<p align="center">
  <img alt="Android API 26+" src="https://img.shields.io/badge/Android-API%2026%2B-34A853?style=flat-square&logo=android&logoColor=white" />
  <img alt="Java 8 bridge" src="https://img.shields.io/badge/Runtime-Java%208%20bridge-5b4638?style=flat-square&logo=openjdk&logoColor=white" />
  <img alt="ABI arm64-v8a and armeabi-v7a" src="https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20armeabi--v7a-f97316?style=flat-square" />
  <img alt="GitHub release workflow" src="https://img.shields.io/badge/CI-GitHub%20Release%20workflow-24292f?style=flat-square&logo=githubactions&logoColor=white" />
</p>

<p align="center">
  <a href="#highlights">Highlights</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#build-from-source">Build</a> •
  <a href="#automation-and-docs">Docs</a> •
  <a href="#repository-layout">Layout</a> •
  <a href="#credits">Credits</a>
</p>

> [!IMPORTANT]
> This repository does **not** ship the Slay the Spire desktop jar. You must provide the game files from your own Steam installation before building.

<a id="highlights"></a>

## Highlights

| Area | What this project focuses on |
| --- | --- |
| Mod compatibility | Targets real `ModTheSpire` / `BaseMod` / `StSLib` style stacks instead of a vanilla-only Android wrapper. |
| Runtime | Uses JavaSE instead of ART to avoid compatibility problems caused by implementation differences. |
| Mobile interaction | Adds touch-oriented control and UI adaptation for Android devices. |
| Device coverage | Builds for both `arm64-v8a` and `armeabi-v7a`. |

> [!NOTE]
> Although Slay the Spire is written with LibGDX, some mods still rely on desktop-specific behavior. This project uses an Android-adapted JavaSE runtime plus matching native libraries to load those mods and preserve their behavior as much as possible.

<a id="quick-start"></a>

## Quick Start

### 1. Provide build-time dependencies

Set `STEAM_PATH` as an environment variable, or set `steam.path` in `gradle.properties`, pointing to a Steam root or `steamapps` directory that contains Slay the Spire.

Required files:
- `${STEAM_PATH}/common/SlayTheSpire/desktop-1.0.jar`
- `runtime-pack/jre8-pojav.zip`
- `runtime-pack/gdx_video_natives/libgdx-video-desktoparm64.so`
- `runtime-pack/gdx_video_natives/libgdx-video-desktoparm.so`

Dependency download sources:
- Runtime pack: [ModinMobileSTS/SlayTheAmethystModdedDependence](https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/tag/pojav-jre8)
- `gdx-video` native bridge libs: [ModinMobileSTS/GdxVideoDesktopAndroidNative](https://github.com/ModinMobileSTS/GdxVideoDesktopAndroidNative/releases)

> [!NOTE]
> Core mod jars such as `ModTheSpire.jar`, `BaseMod.jar`, and `StSLib.jar` are bundled from app assets. They are not fetched from external mod sources at build time.

### 2. Build a debug APK

```powershell
.\gradlew.bat :app:assembleDebug
```

### 3. Build a signed release APK

> [!IMPORTANT]
> For `Release` builds, prefer the workflow documented in the [Release Automation Guide](./release-automation/README.md) to avoid signing mismatches.

Use the standard Android signing environment variables:

```powershell
$env:RELEASE_STORE_FILE="C:\path\to\release-signing.jks"
$env:RELEASE_STORE_PASSWORD="..."
$env:RELEASE_KEY_ALIAS="..."
$env:RELEASE_KEY_PASSWORD="..."
.\gradlew.bat :app:assembleRelease
```

APK output:
- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

<a id="build-from-source"></a>

## Build from Source

| Module | Responsibility |
| --- | --- |
| `:app` | Android launcher UI, packaging, runtime asset assembly, and adb-backed debug tasks |
| `:boot-bridge` | Java boot bridge packaged into the runtime asset set |
| `:patches:gdx-patch` | Desktop compatibility patch jar included during build |

Platform targets:
- `minSdk 26`
- `targetSdk 33`
- `compileSdk 36`
- Java / Kotlin bytecode target: `1.8`

<a id="automation-and-docs"></a>

## Automation & Docs

The repo already includes the pieces needed for day-to-day device work and CI releases.

Debug and device automation:

```powershell
.\gradlew.bat :app:stsStart
.\gradlew.bat :app:stsStop
.\gradlew.bat :app:stsPullLogs
```

Extra options:
- `-PlaunchMode=mts_basemod` or `-PlaunchMode=vanilla`
- `-PdeviceSerial=<adb-serial>`
- `-PlogsDir=<path>`

Further reading:
- [Simplified Chinese README](../README.md)
- [Debug Automation Guide](./debug-automation/README.md)
- [Release Automation Guide](./release-automation/README.md)
- [Backend Startup Chain](./backend-startup-chain.md)

<a id="repository-layout"></a>

## Repository Layout

| Path | Purpose |
| --- | --- |
| `app/` | Main Android app and launcher |
| `boot-bridge/` | Bootstrapping bridge jar |
| `patches/gdx-patch/` | Runtime compatibility patch project |
| `runtime-pack/` | Local runtime pack and native bridge inputs |
| `docs/` | Developer-facing guides, multilingual READMEs, and architecture notes |

<a id="credits"></a>

## Credits

This repository reuses and adapts parts of the `Amethyst-Android` / `PojavLauncher` native and Java bridge work. See [NOTICE](../NOTICE) and [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md) for attribution and licensing details.

Special thanks:
- [AngelAuraMC/Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) for foundational Android JavaSE launcher bridging ideas and prior engineering work.
- [bwwq/ModTheSpire](https://github.com/bwwq/ModTheSpire) for the ModTheSpire variant currently used by this project.
- The wider Slay the Spire modding and launcher community for issue reports, experiments, and tooling that helped make this practical.
