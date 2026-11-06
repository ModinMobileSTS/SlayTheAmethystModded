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

选择小游戏且依赖已安装时，`StsGameActivity.launch()` 改走同一 `:game` 进程的 `GodotBootGameActivity` 竖屏入口。Manifest 单独声明竖屏并禁用主游戏启动预览，不改其它启动样式的横屏入口。`BootOverlayController.init()` 先取得并初始化会话，再同步调用 `GodotBootSession.start()` 打开小游戏；不再等 `decor.post` 后先绘制一帧主游戏加载页。目标可见流程为「启动器主页 → 竖屏小游戏」，主游戏仍在背后准备。

准备期间 Android 窗口保持竖屏，但 `RenderSurfaceManager` 仍使用物理屏幕推导的固定**横屏逻辑画布/缓冲区**，不会拿小游戏的竖屏尺寸初始化游戏。JVM 启动门禁对此不再等待横屏旋转；窗口在保存并进入或故障恢复时才交还 `GameOrientationPolicy` 切到横屏。多窗口继续由宿主决定方向。Godot 用独立竖屏、WindowManager translucent Activity 覆盖主游戏，使底层 Surface 保持可用；Binder 传递进度、真实 ready 和保存确认。

进度 UI 属于上游 PCK：棋盘左下方 46×46 圆环与右侧市场按钮镜像，使用同一底色；只在真实主游戏 ready 后显示「启动」。上游 `launcher.gd` 探测 Android Godot 4.7.2 插件时同时支持 `has_method()` 和 `has_java_method()`，因为 JNI 插件方法不在普通 ClassDB 方法表中。小游戏 Activity 不再创建原生状态文字、百分比、启动/保存返回按钮；Godot 从第一帧起独占正常界面。`launcher_ui_ready` 仍是协议和进入门禁，不能因为没有原生 UI 就省略握手。异常时只保留引擎故障、保存未确认的恢复对话框，不把异常当作已保存或主游戏就绪。

正常进入等待 `saved`；独立模式与启动遮罩模式均只接受已请求且未失败的保存确认。Android BACK 在 Godot 渲染视图之前转到宿主保存请求，不能让 SceneTree 直接退出而跳过握手。保存失败可在 Godot 启动栏重试。未收到保存确认时，放弃本次进度必须单独确认；不删除旧存档。小游戏进程失败时恢复主游戏（用户已请求返回启动器则按其退出目标处理），而非退回 Web。缺依赖的旧设置也只回退到普通原生加载界面，不创建 WebView。

### 返回键与退出目标

- 小游戏内第一次 BACK 只提示「2 秒内再次按返回键，保存并返回启动器」，不保存、不离开；两秒内第二次 BACK 才开始退出。返回手势和实体键都走相同门禁，按住或取消的按键不会重复触发；切到后台后清除首次返回的计时。
- 双击 BACK 的目标始终是**启动器 app**，与小游戏「启动」按钮的**进入主游戏**严格区分。独立游玩也返回启动器，不停在 debug 配置页或把启动器一起退出。
- `GodotExitState` 记录目标及保存状态：返回启动器可覆盖尚未完成的主游戏进入，但延迟的进入事件不能反向覆盖；未请求、保存失败后未重试、已经完成的 `saved` 一律无效。等待保存和失败重试不会重新选择退出目标。
- 启动遮罩模式先向私有 Binder 发 `CANCEL_MAIN_GAME`，禁止延迟进入和 Binder death 恢复误入主游戏；收到真实 `saved`（或用户显式确认放弃）后，才发不同的 `RETURN_TO_LAUNCHER`。`GodotBootCompletion` 只允许一个最终去向。小游戏覆盖层保持到启动器打开，避免退出时露出主游戏。
- `StsGameActivity.returnToLauncherFromGodot()` 复用主游戏的预期返回路径，中断准备、静音/停止运行时、更新会话标记并打开启动器，然后终止**自己的 `:game` 进程**，防止已进入 native 的后台 JVM 仅凭线程 interrupt 仍驻留。小游戏在销毁时只结束自己的 `:minigame`；不结束启动器 app、不清数据/存档。
- 保存失败/10 秒未确认时仍要求用户等待、在 Godot 重试或单独确认放弃；不会因为双击 BACK 自动放弃。正常「启动」仍要求真实 ready + UI 握手，并在保存确认后进入主游戏，不采用取消路径。

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

### 2026-11-03 桥接修复真机回归

Xiaomi 2206122SC / Android 16，同签名 `adb install -r`，保留应用数据及原存档。公网 `v0.0.3` 的基线失败报告保留在 `agent-tmp/godot-device-validation/RESULTS.md`。修复后使用上游本地导出的 Android PCK（标签明确为 `local-0.0.3-bridge-fix`，SHA-256 `a80eb33def2a651b62384713c48ab3749bddfba737ec2b1c65ea5f5c444d70bf`），**未发布 GitHub 资源、未替换固定引擎**。

已实测 `ready`/`launcher_ui_ready`、真实进度圆环、深浅色就绪按钮、减少动画模式、独立 BACK 保存返回，以及 `enter_requested → save_requested → saved → save_acknowledged → enter_main_game`。小游戏暂停/恢复后也完成保存并切入主菜单，但 Home 图标返回打开了启动器而非直接恢复小游戏，不能据此声称完整 Home/Recents 恢复通过。恢复主题为跟随系统、关闭性能模式后，小游戏存档 `state` 与备份完全相同，仅 `exportedAt` 更新。

构建及 `io.stamethyst.godot.*` 20 项单测通过；上游 Godot `launcher_test`（headless/GUI）及 `interface_input_test`（GUI）通过。原始事件、截图、包 hash 和备份位于 `agent-tmp/godot-bridge-fix/`。本节结果验证桥接/保存修复，**不包含随后新增的主页直达小游戏启动顺序**；直达入口需独立进行真机首帧、后台真实就绪、保存进入及故障回退验收。

随后直达入口的 debug 构建、72 项定向测试、release Kotlin/Java 编译通过，同签名覆盖安装后存档 JSON 未变。用户手动反馈「没问题」并要求继续移除原生 UI、改双击返回启动器。该反馈属于用户确认：第一轮采集设备息屏，第二轮 ADB 已离线，代理没有取得直达过程的完整录屏证据。原始记录 `agent-tmp/godot-direct-start/RESULTS.md`。新增的纯 Godot 界面/双击返回逻辑还需使用其新 APK 单独验证，不能继承旧 APK 的通过结论。

### 2026-11-03 移除原生 UI / 双击返回

最终 debug 构建和 88 项定向回归通过（0 失败/错误/跳过），覆盖 Godot 文件/迁移/真实就绪门禁、双击窗口/保存去向、取消优先、启动顺序/进程边界、launch guard 保护、方向/Surface/会话配置/窗口可见性。日志和明细 `agent-tmp/godot-clean-exit/final-debug-build-tests.log` / `test-summary.json`。不是全项目单测通过结论。

同一最终代码的 release Kotlin/Java 独立非增量编译也通过，日志 `agent-tmp/godot-clean-exit/final-release-compile.log`；未构建或安装 release APK。

待安装 APK 固定在 `agent-tmp/godot-clean-exit/app-debug.apk`，SHA-256 `66f1011e5e8bc835a5db2ec4ef4dda63f26dccf5f2abea78c1d3d4e56f095bcf`，已验证与上一版同签名。ADB 当前无设备，**此 APK 尚未安装或真机验收**；不继承前一 APK 的设备结果。具体验收清单和后续编译结果见 `agent-tmp/godot-clean-exit/RESULTS.md`。未改固定引擎或本地 PCK，未清数据、提交、推送或发布。

### 2026-11-03 v0.0.4 PCK 发布

用户随后授权发布 [v0.0.4](https://github.com/Apricityx/slingbreak/releases/tag/v0.0.4)，已作为正式 Latest Release 上线。PCK 基于之前真机验证的桥接修复快照，只更新内部版本为 `0.0.4`；重新导出的所有游戏脚本/素材与基线逐字节一致，主场景仅有无语义差异的序列化 ID 更新。资源/每条目 MD5、版本读取及 JNI 桥接冒烟检查通过，新字节包尚未单独真机验收。

- `game.pck`：99,809,832 bytes，SHA-256 `4227169cd400aa1ee8fb4d8900d9bdb9177c5f85a39052e712318faf6132cab3`。
- Release ID `432995134`，PCK asset ID `706419448`；另附 `game.pck.sha256`、`release-manifest.json`。
- 草稿上传后验证 GitHub digest/大小，正式发布后无鉴权公网完整下载重新计算 SHA-256，一致。证据位于 `agent-tmp/godot-v0.0.4/`。
- 固定引擎/旧版本资源未替换，启动器 APK 未发布，用户数据未修改。新版双击返回/删除原生 UI 仍需要上节的启动器 APK。
- 只创建资源版本 tag/release，tag 指向现有远端 master `14fae178c4b17fe93d470bf7398682c9f791ecdd`；未提交或推送本地未提交源码。发布说明已公开此源码/资源边界，不能把 tag 的 Source code ZIP 当作本地修复源码快照。
