# Godot 小游戏：正式下载与更新集成

小游戏的生产入口已从 Web 切换为下载式 Godot，启动器本体不包含 Godot DEX、SO 或 PCK。旧 `docs/godot-minigame-prototypes.md` 中的 debug 开关、固定 PCK hash 和本地 HTTP 地址只描述历史原型，不是当前配置方式。

## 用户流程

- 设置 → 启动器 → 启动遮罩选择「小游戏」：没有完整依赖时先询问是否下载，取消不改变原选项；下载和安装成功后才保存小游戏选项。
- 独立小游戏入口先检查依赖，缺少时使用相同的确认与下载界面；游玩运行在私有 `:minigame` 进程。
- 设置 → 启动器 → 更新：显示已安装小游戏的 GitHub tag，提供「检查小游戏更新」和新版本下载确认。不使用启动器的 `BuildConfig.VERSION_NAME` 作为游戏版本。
- 下载复用 `ExternalResourcePackService.downloadValidatedArchive`，与 `resource.zip` 共用镜像候选、探测、加速、范围下载、慢速换源、校验失败换源与重试；GitHub 的重定向由共用 HTTP 客户端处理。
- 检查更新复用 `LauncherUpdateService` 的多源元数据竞速、总超时、取消、源健康记录和旧镜像等待机制，只替换目标仓库、资源选择和版本身份规则，不新增独立 HTTP 更新客户端。

## 公网约定

引擎固定地址：

```text
https://github.com/Apricityx/slingbreak/releases/download/Resource/engine.apk
```

引擎包含可执行代码，启动器固定 SHA-256：

```text
e51956f8f3b2be5d5fed6fd8e235ae6d4f93103ebab4bb23e018a36a2559e50d
```

替换此 URL 的文件不能绕过校验。将来升级引擎需同步升级启动器中的 pin 和兼容性约定；它不是通过未经固定的远程清单自动升级的代码包。APK 不签名、不由 PackageManager 安装，只作私有动态资源容器使用。当前要求 Android 9+、arm64。

游戏检查使用 `Apricityx/slingbreak/releases?per_page=100`，从最近 100 条发布中按发布时间选择含 PCK 的最新正式发布，排除 draft、prerelease 和 `Resource`。优先 `game.pck`；没有该名称时只接受唯一 PCK，避免误用其它平台的资源包。要求 GitHub asset 的 `digest` 为 `sha256:…`、大小有效、下载地址属于该仓库的 GitHub release。最新 PCK 缺少 hash 时检查失败，不偷偷安装旧版本。

2026-10-31 已确认公网 `v0.0.1`：

```text
game.pck  79820504 bytes
1587d3a28342093f4efd4c2afb1d26678b585ab8c9d1fa59722ca7998b923aca
```

## 安装、激活和存档

- `SlingBreakReleases`：选择发布，比较已安装 release/asset 的 ID、hash、tag 和发布时间。同一 tag 替换 PCK 可以被发现，旧镜像不能使当前游戏降级。
- `SlingBreakService`：后台下载并校验 hash/大小/PCK 4.7.2 头；校验引擎和提取出的 SO。进程内串行，加跨进程安装锁和组件独占锁。小游戏仍运行（含后台）时拒绝安装并提示先退出；安装期间也不允许新小游戏进程加载组件。每次安装使用独立 UUID 暂存目录。
- `GodotFiles`：已验证的引擎提取 SO、创建只读动态代码；从 PCK 派生仅含 STORED `assets/game.pck` 的无代码 ZIP，校验后供私有 AssetManager 挂载。仍然只有两个下载，离线准备需要额外约一份 PCK 空间。
- `SlingBreakInstallation`：所有准备完成后，用同文件系统的原子 rename 更新 `no_backup/godot-prototype/installed-release.json`。记录 GitHub tag、release/asset ID、发布时间、下载 URL、hash 和大小。下载、解压、资源挂载准备或激活失败时不清理旧组件；版本记录不会出现半条记录。
- 激活成功后由 `SlingBreakStorage` 定向清理旧 `game-<sha>.pck`、`assets-<sha>.zip`、`engine-<sha>/` 和修复过程中退役的 `engine-retired-<uuid>/`，**只保留当前游戏的一份 PCK、一份 ZIP 挂载包和当前固定引擎的一套 APK/SO**。重复安装当前版本也执行清理，回收旧启动器累积的历史版本。当前 PCK 和 ZIP 仍是加载流程必需的两种形式，本次不修改该格式。清理不跟随符号链接、不处理未知名称或存档目录。清理失败记录警告，不把已经激活成功的版本报成安装失败；下次成功安装重试。
- Godot Activity 在读取 release 快照前取得 `components.lock`，持有至 `:minigame` 进程退出，由系统释放锁；Home、暂停、停止不释放。安装从下载前到激活/清理结束持同一独占锁，避免旧进程延迟读取已删除的组件。进程异常终止不会留下永久占用的文件锁。
- 保留原型的 `files/godot-prototype-user` 存档位置，不改动 WebView/Gecko 的历史存档，不清理应用数据。**Web 存档未自动转换成 Godot 存档**。

## 运行边界

`GodotMinigameActivity`、`GodotMinigameBoot` 和窗口样式在 main source set，release 不再依赖 debug 类或 debug 开关。`:minigame` 的 Application 启动路径不加载启动器/JRE 的 native 库或 WebView。Godot 类型只在已校验的容器 ClassLoader 内出现。

主游戏保持横屏准备 Surface；Godot 用独立竖屏、WindowManager translucent Activity 在前面游玩，Binder 传递进度、真实 ready 和保存确认。顶部进度 UI 属于上游 PCK，保持 Web 版样式。正常进入等待 `saved`；保存失败可在 Godot 启动栏重试。未收到保存确认时，放弃本次进度必须单独确认；不删除旧存档。小游戏进程失败时恢复主游戏，而非退回 Web。缺依赖的旧设置也只回退到普通原生加载界面，不创建 WebView。

## 验证与限制

本地验证命令：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.minigame.*' \
  --tests 'io.stamethyst.godot.*' \
  --tests 'io.stamethyst.backend.update.*' \
  --tests 'io.stamethyst.backend.resources.*' \
  :app:assembleDebug :app:compileReleaseKotlin :app:compileReleaseJavaWithJavac
```

2026-10-31 的扩大回归共运行 103 项：90 通过、2 跳过、11 失败。小游戏 14 项、动态文件校验 6 项、共享更新检查 7 项、下载器 11 项均通过。11 个失败全部属于未修改的 `ResourcePackStoreTest`：它生成测试 ZIP，却用当前 `BuildConfig.RESOURCE_PACK_SHA256` 的生产资源包 pin 安装，导致 `Resource pack archive hash mismatch`。这不是小游戏下载失败，不能通过关闭生产 hash 校验掩盖。需要另行让这些资源仓库夹具显式使用测试校验配置。

新增 `SlingBreakSettingsInstrumentedTest` 验证取消依赖确认不启动下载、当前 GitHub tag 独立于启动器版本、更新必须确认；UI-only，不重置偏好、不删除存档、不实际下载。

后续独立构建已成功完成 `:app:assembleDebug`、`:app:compileReleaseKotlin`、`:app:compileReleaseJavaWithJavac`、`:app:compileDebugAndroidTestKotlin`。Debug APK 入口与依赖边界任务通过；额外 `scripts/godot/check_payload.py --engine … --launcher …` 也确认启动器没有 Godot 引擎类/SO/容器/PCK，公网引擎不含游戏资源且 SO 的 LOAD 段满足 16 KiB 对齐。日志 `agent-tmp/godot-production-build-ui.log`。

最终代码的定向回归已成功：84 项中 82 通过、2 跳过、0 失败（小游戏、动态文件、更新服务、资源下载器与链接选择）。日志 `agent-tmp/godot-production-targeted-retry.log`，汇总 `agent-tmp/godot-production-targeted-summary.json`。它不包含上述资源仓库夹具失败，不能把定向通过表述为全部测试通过。Debug APK 已 `adb install -r` 返回 Success；随后设备 ADB 离线，尚未执行新版界面/公网安装验收，也未清理数据。

`:app:assembleDebugAndroidTest` 也已成功，测试 APK 位于 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，日志 `agent-tmp/godot-production-ui-apk.log`。这是编译打包结果，尚未在设备上执行 UI 测试。

测试覆盖发布选择、固定资源排除、无效 digest/URL/资源大小/格式、替换 asset、旧镜像不能降级、共享更新竞速、版本记录重读、准备/激活失败不清旧组件、升级后仅保留当前游戏与一套引擎、重复安装回收历史版本、符号链接不越界删除、组件文件锁互斥、存档/未知文件保护、STORED 派生挂载及 PCK 格式拒绝。构建还有启动器不携带 Godot payload 的 APK 边界检查。

旧原型真机验证不等于本次公网安装/更新验收。正式版仍需实测：取消确认、首次双组件下载、镜像切换、失败重试、重启后当前版本、同 tag 替换/新 release 更新、离线启动和主游戏进入/保存返回。音频、完整断网、低内存和其它 renderer/Surface 组合也不应从单台旧原型结果外推。
