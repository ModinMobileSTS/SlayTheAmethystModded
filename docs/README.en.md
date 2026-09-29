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
  <img alt="ABI arm64-v8a" src="https://img.shields.io/badge/ABI-arm64--v8a-f97316?style=flat-square" />
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
> This repository does **not** ship the Slay the Spire desktop jar. Place `desktop.jar` and `jre8-pojav.zip` under `build-deps/` before building.

<a id="highlights"></a>

## Highlights

| Area | What this project focuses on |
| --- | --- |
| Mod compatibility | Targets real `ModTheSpire` / `BaseMod` / `StSLib` style stacks instead of a vanilla-only Android wrapper. |
| Runtime | Uses JavaSE instead of ART to avoid compatibility problems caused by implementation differences. |
| Mobile interaction | Adds touch-oriented control and UI adaptation for Android devices. |
| Device coverage | Builds for `arm64-v8a` only. |

> [!NOTE]
> Although Slay the Spire is written with LibGDX, some mods still rely on desktop-specific behavior. This project uses an Android-adapted JavaSE runtime plus matching native libraries to load those mods and preserve their behavior as much as possible.

<a id="quick-start"></a>

## Quick Start

### 1. Provide build-time dependencies

The build reads only the repository-local `build-deps/` directory; no Steam installation or path environment variable is required. Required files:

| File | Source |
| --- | --- |
| `build-deps/desktop.jar` | Copy `steamapps/common/SlayTheSpire/desktop-1.0.jar` from your own Steam installation and rename it to `desktop.jar` |
| `build-deps/jre8-pojav.zip` | https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/download/pojav-jre8/jre8-pojav.zip |

Both files can be placed under `build-deps/` manually. **When a build finds one missing, the `ensureBuildDependencies` task downloads the dependency bundle and extracts it automatically** (default: `build-deps.tar.gz`, which contains both files):

```
https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/download/deps-20260305/build-deps.tar.gz
```

Override the URL with `-PbuildDeps.bundleUrl=<url>`; place the files manually for offline builds.

Optional signing directories (a shared debug signature, and release signing without environment variables):

- `build-deps/debug-signature/`: overrides the AGP default debug keystore.
- `build-deps/release-signature/`: release signing.

Both directories have the same shape: a keystore file plus a `signing.properties`:

```properties
# build-deps/release-signature/signing.properties
storeFile=keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

`storeFile` defaults to `keystore.jks`; `keyPassword` defaults to `storePassword`. Release builds still prefer `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` environment variables and `release.*` Gradle properties, and only fall back to `build-deps/release-signature/` when all of them are empty.

Dependency download sources:
- Build dependency bundle (contains `desktop.jar` and `jre8-pojav.zip`): [ModinMobileSTS/SlayTheAmethystModdedDependence](https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases)
- Native library market: [ModinMobileSTS/SlayTheAmethystResource](https://github.com/ModinMobileSTS/SlayTheAmethystResource)

> [!NOTE]
> Core mod jars such as `ModTheSpire.jar`, `BaseMod.jar`, and `StSLib.jar` are bundled from app assets. They are not fetched from external mod sources at build time.

### 2. Build a debug APK

```powershell
.\gradlew.bat :app:assembleDebug
```

On Linux/macOS or Git Bash, build and install the debug APK to a connected Android device:

```bash
./scripts/build-debug-install.sh
```

When multiple devices are connected, pass the target device serial:

```bash
./scripts/build-debug-install.sh <device-serial>
```

### 3. Build a signed release APK

> [!IMPORTANT]
> For `Release` builds, prefer the workflow documented in the [Release Automation Guide](./release-automation/README.md) to avoid signing mismatches.

Recommended: place the signing material under `build-deps/release-signature/` (see above), then build directly:

```bash
./gradlew :app:assembleRelease
```

Or use the standard Android signing environment variables (they take precedence over the signing directory):

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

Use the harness entrypoint for repeatable device automation. It writes machine-readable results to `debug-artifacts/harness/.../result.json`:

```bat
python .\scripts\tools\main.py sts-harness -Command doctor
python .\scripts\tools\main.py sts-harness -Command smoke -LaunchMode mts_basemod -TimeoutSeconds 120
python .\scripts\tools\main.py sts-harness -Command smoke -Autoplay
python .\scripts\tools\main.py sts-harness -Command smoke -Autoplay -AutoplaySaveMode continue
python .\scripts\tools\main.py sts-harness -Command single-room -SingleRoomCharacter IRONCLAD -SingleRoomMonster Cultist -SingleRoomCards "Strike_R,Defend_R,Bash"
```

The same harness is also exposed through Gradle:

```powershell
.\gradlew.bat :app:stsHarnessDoctor
.\gradlew.bat :app:stsHarnessSmoke
.\gradlew.bat :app:stsHarnessAutoplaySmoke
.\gradlew.bat :app:stsHarnessSingleRoom
```

Low-level debug tasks:

```powershell
.\gradlew.bat :app:stsStart
.\gradlew.bat :app:stsStop
.\gradlew.bat :app:stsPullLogs
```

Extra options:
- `-PlaunchMode=mts_basemod` or `-PlaunchMode=vanilla`
- `-PdeviceSerial=<adb-serial>`
- `-PlogsDir=<path>`
- `-PharnessOutDir=<path>`
- `-Pautoplay=true`
- `-PautoplaySaveMode=fresh|continue`
- `-PautoplayMode=normal|single_room`
- `-PdisableCardObtainEffectOwnershipCompat=true`
- `-PsingleRoomCharacter=<id>`
- `-PsingleRoomMonster=<id>`
- `-PsingleRoomCards=<comma-separated-card-ids>`
- `-PsingleRoomSpecFile=<local-properties-path>`
- `-PpythonExecutable=<python-command>`

`smoke` also saves a harness-side logcat capture. If the game crashes before JVM logs are written, `result.json` reports `LOGCAT_CRASH` and points `artifacts.harnessLogcat` at the full error log. Autoplay smoke defaults to a 300-second wait so first-run `desktop-1.0.jar` patching can finish; default `autoplaySaveMode=fresh` clears old saves and starts a new run, while `continue` keeps saves and resumes the previous run when available. Main-process patch failures are returned as `FAIL` in `result.json`.

Autoplay randomly handles `CardRewardScreen` discovery/card reward choice pages and writes `[amethyst-autoplay] choice: ...` log lines. Use `-PdisableCardObtainEffectOwnershipCompat=true` when you need a repro run with the bundled `ShowCardAndAddToHandEffect` ownership compatibility patch disabled.

`single-room` runs one configured autoplay combat room and keeps the game process running after the player dies or all configured monsters are defeated, so battle state and performance samples can be collected. Use the generic `exit` harness command for a graceful GDX shutdown; `stop` remains the force-stop fallback. The result marker from `latest.log` is parsed into `statusSnapshot.latestLog.singleRoomResult`.

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
| `build-deps/` | Local build dependency bundle and runtime inputs |
| `docs/` | Developer-facing guides, multilingual READMEs, and architecture notes |

<a id="credits"></a>

## Credits

This repository reuses and adapts parts of the `Amethyst-Android` / `PojavLauncher` native and Java bridge work. See [NOTICE](../NOTICE) and [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md) for attribution and licensing details.

Special thanks:
- [AngelAuraMC/Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) for foundational Android JavaSE launcher bridging ideas and prior engineering work.
- [Alchyr/sts-ram-saver](https://github.com/Alchyr/sts-ram-saver) for major guidance on launcher memory optimization, making more mods usable in the launcher.
- [bwwq/ModTheSpire](https://github.com/bwwq/ModTheSpire) for the ModTheSpire variant currently used by this project.
- The wider Slay the Spire modding and launcher community for issue reports, experiments, and tooling that helped make this practical.
