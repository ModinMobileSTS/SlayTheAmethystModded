<p align="center">
  <img src="./docs/assets/readme-icon-rounded.svg" alt="SlayTheAmethyst 图标" width="96" height="96" />
</p>

<p align="center">
  简体中文 ｜ <a href="./docs/README.en.md">English</a>
</p>

<h1 align="center">SlayTheAmethyst</h1>

<p align="center">
  一个面向 <strong>模组版 Slay the Spire</strong> 的 Android 启动器项目，在移动端运行大部分桌面版模组
</p>

<p align="center">
  <a href="https://github.com/ModinMobileSTS/SlayTheAmethystModded/releases">
    <img alt="Download Latest APK" src="https://img.shields.io/badge/Download-Latest%20APK-3fb950?style=for-the-badge&logo=android&logoColor=white" />
  </a>
  <a href="#quick-start">
    <img alt="Quick Start" src="https://img.shields.io/badge/Build-Quick%20Start-6f42c1?style=for-the-badge&logo=gradle&logoColor=white" />
  </a>
  <a href="./docs/debug-automation/README.md">
    <img alt="Debug Docs" src="https://img.shields.io/badge/Docs-Debug%20Automation-0969da?style=for-the-badge&logo=androidstudio&logoColor=white" />
  </a>
</p>

<p align="center">
  <img alt="Android API 26+" src="https://img.shields.io/badge/Android-API%2026%2B-34A853?style=flat-square&logo=android&logoColor=white" />
  <img alt="Runtime Java 8 Bridge" src="https://img.shields.io/badge/Runtime-Java%208%20Bridge-5b4638?style=flat-square&logo=openjdk&logoColor=white" />
  <img alt="ABI arm64-v8a" src="https://img.shields.io/badge/ABI-arm64--v8a-f97316?style=flat-square" />
  <img alt="CI GitHub Release Workflow" src="https://img.shields.io/badge/CI-GitHub%20Release%20Workflow-24292f?style=flat-square&logo=githubactions&logoColor=white" />
</p>

<p align="center">
  <a href="#highlights">核心特性</a> •
  <a href="#quick-start">快速开始</a> •
  <a href="#build-from-source">构建说明</a> •
  <a href="#automation-and-docs">文档入口</a> •
  <a href="#repository-layout">仓库结构</a> •
  <a href="#credits">致谢</a>
</p>

> [!IMPORTANT]
> 本仓库 **不包含** Slay the Spire 的桌面版 jar。构建前请先准备你自己的 Steam 游戏文件。

<a id="highlights"></a>

## 核心特性

| 方向    | 说明                                                            |
|-------|---------------------------------------------------------------|
| 模组兼容性 | 目标是支持真实的 `ModTheSpire` / `BaseMod` / `StSLib` 模组堆栈，而不是只跑原版内容。 |
| 运行时   | 运行 JavaSE 而不是 ART，避免因为实现不一致导致的兼容性问题。                          |
| 移动端交互 | 针对触屏补充了控制适配与界面适配。                                             |
| 设备覆盖  | 仅支持 `arm64-v8a`。                                             |

> [!Note]
> 虽然尖塔采用了 LibGDX 编写，但是部分模组仍采用一些桌面特性进行编写，本项目旨在使用适配安卓的 JavaSE 与配套 Native 库来加载这些模组，从而最大限度还原模组的表现。
<a id="quick-start"></a>

## 快速开始

### 1. 准备构建依赖

设置环境变量 `STEAM_PATH`，或者在 `gradle.properties` 中设置 `steam.path`，其值应指向包含 Slay the Spire 的 Steam 根目录或 `steamapps` 目录。

必需文件：
- `${STEAM_PATH}/common/SlayTheSpire/desktop-1.0.jar`
- `runtime-pack/jre8-pojav.zip`

依赖下载来源：
- Runtime Pack: [ModinMobileSTS/SlayTheAmethystModdedDependence](https://github.com/ModinMobileSTS/SlayTheAmethystModdedDependence/releases/tag/pojav-jre8)
- 原生库市场: [ModinMobileSTS/SlayTheAmethystResource](https://github.com/ModinMobileSTS/SlayTheAmethystResource)

> [!NOTE]
> `ModTheSpire.jar`、`BaseMod.jar`、`StSLib.jar` 等核心模组 jar 由应用资源打包提供，不会在构建时从外部模组源动态解析。

### 2. 构建调试版 APK

```powershell
.\gradlew.bat :app:assembleDebug
```

### 3. 构建签名发布版 APK

> [!IMPORTANT]
> 建议使用[发布自动化指南](./docs/release-automation/README.md)中的方法进行`Release`版本的构建，以避免签名不同的问题

使用标准 Android 签名环境变量：

```powershell
$env:RELEASE_STORE_FILE="C:\path\to\release-signing.jks"
$env:RELEASE_STORE_PASSWORD="..."
$env:RELEASE_KEY_ALIAS="..."
$env:RELEASE_KEY_PASSWORD="..."
.\gradlew.bat :app:assembleRelease
```

APK 输出目录：
- `app/build/outputs/apk/debug/`
- `app/build/outputs/apk/release/`

<a id="build-from-source"></a>
## 构建说明

| 模块                   | 职责                                      |
|----------------------|-----------------------------------------|
| `:app`               | Android 启动器 UI、打包逻辑、运行时资源组装，以及 adb 调试任务 |
| `:boot-bridge`       | 打包进运行时资源的 Java 启动桥接 jar                 |
| `:patches:gdx-patch` | 构建期包含的桌面兼容补丁 jar                        |

构建目标：
- `minSdk 26`
- `targetSdk 33`
- `compileSdk 36`
- Java / Kotlin 字节码目标：`1.8`

<a id="automation-and-docs"></a>

## 文档入口

仓库已经内置了日常联机调试与 CI 发布所需的关键流程。

设备调试命令：

```powershell
.\gradlew.bat :app:stsStart
.\gradlew.bat :app:stsStop
.\gradlew.bat :app:stsPullLogs
```

可选参数：
- `-PlaunchMode=mts_basemod` 或 `-PlaunchMode=vanilla`
- `-PdeviceSerial=<adb-serial>`
- `-PlogsDir=<path>`

更多文档：
- [英文版 README](./docs/README.en.md)
- [调试自动化指南](./docs/debug-automation/README.md)
- [发布自动化指南](./docs/release-automation/README.md)
- [后端启动链路说明](./docs/backend-startup-chain.md)

<a id="repository-layout"></a>

## 仓库结构

| 路径                   | 用途                    |
|----------------------|-----------------------|
| `app/`               | 主 Android 应用与启动器实现    |
| `boot-bridge/`       | 启动桥接 jar 模块           |
| `patches/gdx-patch/` | 运行时兼容补丁模块             |
| `runtime-pack/`      | 本地运行时包与原生桥接输入         |
| `docs/`              | 开发文档、多语言 README 与架构说明 |

<a id="credits"></a>

## 致谢

本仓库复用了并改造了 `Amethyst-Android` / `PojavLauncher` 的部分原生与 Java 桥接实现。详细归属与许可证请见 [NOTICE](./NOTICE) 和 [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md)。

特别感谢：
- [AngelAuraMC/Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) 提供了 Android JavaSE 启动器桥接方向上的重要工程基础和参考实现。
- [bwwq/ModTheSpire](https://github.com/bwwq/ModTheSpire) 提供了本项目当前使用的 ModTheSpire 变体。
- 所有为 Slay the Spire 模组生态、启动器工具链、问题排查和公开讨论做出贡献的开发者与社区成员。
