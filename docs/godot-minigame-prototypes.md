# Godot 双包原型（debug 专用）

## 范围与边界

此原型不替换正式小游戏，不删除旧 Web 资源或存档，不安装第二个应用。仅用于回答两个问题：

1. 启动器不含 Godot AAR/DEX/SO/PCK 时，能否下载固定 URL 的引擎容器和 Android PCK，在 `:minigame` 中原生运行并退出重进？
2. 小游戏 Activity 覆盖主游戏时，能否继续准备主游戏 JVM/Surface，通过 Binder 接收真实进度，在保存后返回可操作的主游戏首帧？

**编译和桌面 PCK 检查通过不代表以上 Android 真机验收通过。** 必须按下面的设备矩阵验证。

### 2026-10-30 真机发现与核心链路验证

在 Xiaomi 2206122SC / Android 16 上，双包安装和 hash 校验成功，动态加载进入 Godot 原生初始化，但官方 4.7.2 release Android AAR 明确拒绝 `--main-pack` 指向外部私有目录：引擎编译时禁用了路径覆盖。日志要求使用 `disable_path_overrides=no` 编译。该直接文件路径方案未通过。

源码 `main/main.cpp` 的 Android 分支明确允许 `--main-pack` 从 assets 读取。因此后续原型改为：仍下载两个原始文件，校验 PCK 后本地生成只含 `assets/game.pck` 的 STORED ZIP，将它与引擎资源一起加入专用 AssetManager，再使用 `--main-pack res://game.pck`。派生 ZIP 不含代码，每次启动校验条目数量、类型和 PCK hash，缺失或不匹配时从已校验 PCK 重新原子生成。它不是第三个下载，也不把游戏内容放回引擎包或启动器 APK。额外占用约一个 PCK 大小的设备存储。**该路径已在上述设备运行成功。** 构建脚本仍使用官方 AAR，没有自编译引擎步骤。

随后同一设备已通过该 assets 路径启动完整游戏：日志显示 Adreno 730 / OpenGL ES 3.2、GDScript `ready` 和 `main_loop_started`，截图见 `agent-tmp/godot-spike/device-asset-first.png`。但该轮发现存档写入失败：GodotIO 使用 Activity 的 `getFilesDir()`，存储访问器使用 EngineContext 的 `getFilesDir()`，两者不一致导致存储范围识别失败。已改为由宿主 Activity 统一提供 `files/godot-prototype-user/`，EngineContext 直接委托，不再重复追加目录。

修复版 APK `08084fef52d959c82ec8e0342a8928f0c0402779896ee0ab01bb261c18143731` 已覆盖安装，引擎为 `179689620ab555854bdd932f7156aae5ab74d7a000db19b38ab05beeedb1ff9d`，PCK 不变。以下为本轮实际观察，不代表完整设备矩阵或正式替换验收通过：

- 自动存档生成有效 `slingbreak-progress.json`；选择技能、拖拽发射后，存档含金币 7、`decay` 技能和已击破 2 块的状态，重进截图与之相符。
- 手动返回收到 `gdscript=saved` / `saved`；退出后 `:minigame` 消失。三次重进分别出现新 PID 29391、2904、4736，并收到 ready，未发生引擎单例重初始化崩溃。
- Home→返回、睡眠→唤醒后仍能保存返回；第三次重进前已关闭设备下载 relay，仍可启动。**这是无下载服务依赖验证，不是关闭 Wi-Fi/VPN 的完全断网测试**，以免断开远程设备连接。
- 主游戏加载时小游戏保持前台，日志显示 `jvmStarted=true bridgeReady=true view=2400x1080 attached=true focus=false`，进度从 8% 到 99% / `ready=true`，小游戏不会被自动关闭。
- 点击“保存并进入主游戏”后依次记录 `gdscript=saved`、`saved`、`enter_main_game`，主游戏恢复横屏战斗画面，可触控打开设置。用户另确认“触控一切正常”。未出牌或结束回合。
- 共存时单次快照：小游戏 PSS 575194 KiB，主游戏 PSS 1405248 KiB（合计约 1.89 GiB，未含启动器等进程）；**不是峰值测量**，低内存设备风险仍需专门验证。
- 音频仅有系统播放会话证据；设备有音量静音状态，没有听感确认，不能标记音频验收通过。未覆盖其他 Surface/渲染后端、低内存回收或全部原版/Mod 组合。
- 测试后通过测试页关闭 boot 开关，并读取 preferences 确认 `boot=false`。未卸载、清理应用数据或迁移旧存档；正式 Web 小游戏未替换。

主要证据位于 `agent-tmp/godot-spike/`：`device-save-fixed-first.log`、`device-save-before-reentry.json`、`device-save-reentry-live.log`、`device-reentry1.png`、`device-reentry3-no-relay.xml`、`device-overlay-live.log`、`device-overlay-loading.png`、`device-main-after-godot.png`、`device-main-touch-settings.png` 和 `device-overlay-memory.txt`。测试包仍绑定回环地址，未恢复公网托管；新设备首次下载需要本地服务/转发。

## 模块

- `godot-runtime/`：独立构建下载用、无需安装的容器 APK。只包含引擎 Java 代码、Android 资源、arm64 SO 和桥接适配器，不包含游戏内容。使用本地 Godot **4.7.2 stable** 导出模板中的 release AAR，不依赖 Maven 的可变版本。
- `app/src/debug/java/io/stamethyst/godot/`：下载/校验、测试入口、动态加载 Activity 和 Binder 进度桥接。Activity 只在 debug Manifest 中声明。
- `GodotBootPrototype.kt`：正式代码中的轻量 debug 探针接口。默认关闭，未安装双包时也不会启用。release 中没有动态适配器实现。
- `scripts/godot/build_prototype.py`：读取上游工程，在 `agent-tmp/godot-spike/` 中创建干净 staging，不修改上游工作树；导出 Android PCK。上游存在 `src/core/launcher.gd` 时直接使用其主场景与原生桥接，只有旧上游/最小测试场景才注入原型启动/保存桥接。
- `scripts/godot/check_payload.py`：检查真实 APK 的 DEX/原生库/内容边界和两个引擎 SO 的 ELF 16 KiB LOAD 对齐。

引擎容器可视为一种已 dex 的资源 ZIP；不是 Android PackageManager 安装的 split APK。不可直接将 AAR 放进 DexClassLoader。

## 两个固定下载地址

原型使用四个构建参数，不在运行时从远程获取 hash：

```properties
godot.prototype.engine_url=https://your-host/path/engine.apk
godot.prototype.engine_sha256=<64 hex>
godot.prototype.pck_url=https://your-host/path/game.pck
godot.prototype.pck_sha256=<64 hex>
```

固定 URL 的文件内容变化后，必须重新固定 hash 并构建测试启动器。既有缓存以内容 hash 为目录/文件名，不覆盖正在运行的版本。固定地址独立更新的正式协议、签名清单和回滚管理不在本原型范围内。

只允许 HTTPS；为 USB 测试额外允许 `127.0.0.1`/`localhost` 的 HTTP。原型不自动跟随 HTTP 重定向，生产地址应指向直接返回 200 的文件。下载大小和 SO 解压上限分别为 256 MiB，下载完成后校验 hash，再原子发布。动态 APK 在加载前设为只读；每次启动重新验证 APK、PCK 和提取的 SO。完整校验在工作线程执行。

### 本地构建

```sh
SOURCE=/absolute/path/to/slingbreak
python3 scripts/godot/build_prototype.py --source "$SOURCE" --minimal
python3 scripts/godot/build_prototype.py --source "$SOURCE"
./gradlew :godot-runtime:assembleRelease
python3 scripts/godot/package_prototype.py
```

`godot.androidSourceZip` 可覆盖默认的
`~/.local/share/godot/export_templates/4.7.2.stable/android_source.zip`。

`package_prototype.py` 将引擎与完整 PCK 复制到 `agent-tmp/godot-spike/serve/{engine.apk,game.pck}`，生成 `prototype.properties`，并打印四个 Gradle `-P` 参数。加 `--minimal` 可改用小型触控场景；加 `--base-url https://...` 可改用你自己的固定地址。两个组件需要单独上传，脚本不会自动发布。

将输出的四个参数传给：

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest --tests 'io.stamethyst.godot.PrototypeFilesTest' <四个 -P 参数>
python3 scripts/godot/check_payload.py \
  --engine godot-runtime/build/outputs/apk/release/godot-runtime-release-unsigned.apk \
  --launcher app/build/outputs/apk/debug/app-debug.apk
```

可用 `scripts/godot/build_test_apk.py` 在 PCK 已导出后按顺序构建引擎、固定 hash、构建测试 APK 并执行上述检查。
成功后另存测试包到 `agent-tmp/godot-spike/slaytheamethyst-godot-prototype.apk`，并生成含组件与 APK hash 的 `build-report.json`，避免后续其他构建覆盖交付包。

若引擎和 PCK 位于不同的临时直链，可直接指定：

```sh
python3 scripts/godot/build_test_apk.py --engine-url https://host/engine-file.apk --pck-url https://host/content-file.pck
```

临时托管会过期，仅用于设备验证；过期后需重新上传并重新固定 URL 构建。上传之后应完整回下载并核对 SHA-256，而不是只检查链接能打开。

如果匿名托管拒绝 APK 或只提供短时令牌，也可将下面的专用文件服务器通过临时 HTTPS 通道公开，再以 `--base-url https://临时域名` 构建。仅公开 `serve/`，不要公开仓库或用户目录。这是开发机临时提供下载，并非文件已存入托管平台：下载依赖开发机、文件服务器和通道持续在线，没有固定有效期或可用性保证。两个组件下载并校验成功后仍可离线运行。交付包实际使用的地址以 `build-report.json` 为准。

### USB 下载验证

不指定 URL 时，构建脚本默认绑定本机回环的两个地址，无需公网发布；若交付包已绑定公网地址，则无需此节的服务器和 USB 转发：

```sh
python3 -m http.server 18765 --bind 127.0.0.1 --directory agent-tmp/godot-spike/serve
```

另一个终端执行（ADB 必须能看到设备）：

```sh
adb reverse tcp:18765 tcp:18765
adb install -r agent-tmp/godot-spike/slaytheamethyst-godot-prototype.apk
adb shell am start -n io.stamethyst/io.stamethyst.godot.GodotPrototypeSetupActivity
```

如果现有应用证书与测试包不同，**不要卸载现有应用或清理数据**，先确认签名/另建包名。启动器原有主游戏资源下载配置不受双包回环地址影响。

## 真机操作与通过标准

### 全屏宿主与游戏内启动控件

`GodotMinigameActivity` 的引擎容器始终占满窗口，不再用原生纵向布局为顶部状态文字、底部返回按钮保留空间，也不再使用 `fitsSystemWindows` 缩小游戏区域。系统状态栏与导航栏采用沉浸式隐藏，窗口恢复焦点、恢复前台及配置变化时重新应用；Android 9+ 允许内容延伸至屏幕缺口区域。窗口仍保留 translucent 属性，避免改变下层主游戏准备所需的生命周期。

启动校验和错误提示使用不改变引擎尺寸的浮层。带有原生桥接的 PCK 在报告 `launcher_ui_ready` 后由游戏自己的进度和“启动游戏”按钮接管，独立游玩在报告 `ready` 后隐藏原生浮层。旧版没有该握手的 PCK 保留浮层返回入口；返回键也始终走保存确认流程。

主游戏通过 Binder 发布的进度、文案和 ready 状态会先缓存在 Activity，在下载组件校验及引擎创建完成后重新发送给 `PrototypeHost.state`，避免初始化之前收到的最终就绪状态丢失。游戏通过 `get_progress` / `get_message` / `is_ready` 读取真实状态；百分比不作为就绪判据。按钮的 `enter_requested` 仅在主游戏 ready 时受理，经 `leave` → GDScript 保存 → `saved` 才切回主游戏。

额外验收：在带手势导航/屏幕缺口的设备检查上下无原生白条，Home 或系统弹窗返回后仍全屏；检查主游戏先于小游戏校验完成时，内置按钮仍能变为可点击；确认点击后保存完成再返回。本节描述代码行为，不代表已完成上述真机验收。

#### 与上游 LauncherDock 的对接约定

- `BootOverlayController.updateProgress` 发布实际启动进度，`markManualEnterGameReady` 独立发布首帧门禁就绪；99% 可以 ready，100% 本身不能代替 ready。
- `GodotMinigameBoot` 通过会话 Binder 发送 `progress` / `message` / `ready`。`GodotLauncherState` 缓存它们，在引擎创建及 `launcher_ui_ready` 握手时补发；Binder 死亡后拒绝迟到的进度，防止重新开放进入按钮。
- 下载引擎的 `PrototypeHost` 保持原有 `SlingPrototype` API 不变。上游 `godot/src/core/launcher.gd` 轮询状态，`godot/src/ui/launcher_dock.gd` 显示进度与按钮，仅启动器模式挂载。此次对接无需修改引擎容器、固定 hash 或上游 PCK 协议。
- 只有已完成 UI 握手、主游戏 ready 且引擎未失败时，才受理 Godot 的 `enter_requested`。Android 返回键和原生回退入口仍允许提前离开，不将提前返回伪装为就绪。
- 进入顺序固定为 `enter_requested` → Android `leave()` → Godot `should_leave()` → 保存 → `saved` → Binder 返回主游戏；重复保存确认/进程死亡通知只触发一次主游戏进入回调。
- Godot `save_failed` 时保持小游戏，不进入主游戏；`save_retry_requested` 只在保存失败后受理，并重置原生保存超时。未完成 UI 握手的旧 PCK、独立模式或主游戏进程死亡时，显示原生恢复控件，避免丢失退出入口。

本地回归：启动器运行 `GodotLauncherStateTest` 与 `GodotFilesTest`；上游运行 `godot --headless --path godot --script res://tests/launcher_test.gd`，覆盖真实 Godot Controls 的进度/ready/点击/保存失败重试及独立模式。Headless 测试不替代 Android 真机或 GUI 触控验收。

应用内入口：**设置 → 开发者设置 → 实验功能 → Godot 双包原型测试**（仅 debug 显示）。也可以使用上面的 ADB 命令直接打开。

### 原型 1：独立原生小游戏

1. 打开 debug 测试页，点击“下载 / 校验两个组件”。成功后点击“单独运行 Godot PCK”。
2. 应看到真实 Godot 游戏，顶部出现 `外部 PCK ready · PID ...`；能触控、选择技能并游玩。
3. 点击“保存并返回”，必须收到 GDScript 保存确认。重新打开后，进度保留；重复三次，不能出现原生单例重初始化崩溃。
4. 下载后断网再打开，必须可用；取消 `adb reverse` 不应影响已安装组件。
5. Home→返回、锁屏→解锁、前后台切换后检查触控和音频。退出后 `:minigame` 应结束，`:game`/启动器不应被杀。

小游戏存档位于启动器私有 `files/godot-prototype-user/` 下，由 Godot 自身处理 `user://`。目录不随组件 hash 变化。当前正式宿主在引擎启动前支持经用户确认迁移旧 Web 存档，原始浏览器存档始终保留，详见 [旧存档迁移](slingbreak-save-migration.md)。完整 PCK 中的保存调用复用上游 `session.save()`；最小 PCK 保存测试时间戳。

### 原型 2：主游戏加载期间游玩

1. 完成双包下载，打开“下一次主游戏启动时使用 Godot 原型”，返回启动器，正常启动主游戏。
2. Godot 应覆盖主游戏并显示真实加载进度；主游戏实际 JVM 日志继续增长，最终显示“保存并进入主游戏”。
3. 继续玩一会儿，确认主游戏就绪不会强行关闭小游戏；点击进入，保存完成后切回主游戏，方向、首帧、触控、声音应正常。
4. 同时测试 SurfaceView/TextureView（若设备/后端提供）、原版/Mod 启动、Home/锁屏、返回键、系统低内存回收。主游戏不能在进入 Godot 后永久卡住。
5. 返回键或“排查加载停滞”允许提前切回，不表示已完成加载；用于确认 Activity 覆盖是否造成门禁停滞。随后主游戏应继续正常完成启动。
6. 测试后关闭开关。默认正式 Web 小游戏路径保持不变。

### 为什么采用 translucent Window

完全不透明的 Activity 会导致下层主游戏 `onStop`，现有后台保护会暂停运行时，并可能失去可用 Surface。此原型将窗口设为 translucent，但绘制区域本身不透明，尝试保留下层主游戏可见性。仅在小游戏通过会话 Binder 报告 resumed、且主游戏尚未 stopped 时，恢复主游戏加载所需的前台标记并保持其音频静音。

这不是取消应用后台保护：小游戏暂停时撤销标记，主游戏 `onStop` 仍会进入后台。进程死亡使用 Binder death 检测；小游戏进程死亡可回退主游戏，主游戏进程死亡在小游戏中提示。Activity 的方向、厂商 WindowManager/Surface 行为和生命周期时序必须真机验证，若失败应重新选择窗口/宿主架构，不能宣称已经可正式替换。

## 建议反馈与日志

记录设备型号、Android 版本、GPU、渲染后端、Surface 类型、是否启用 Mod，以及在哪一步出错。

```sh
adb logcat -v threadtime -d > agent-tmp/godot-spike/device-logcat.txt
adb shell dumpsys activity activities > agent-tmp/godot-spike/device-activities.txt
adb shell dumpsys meminfo io.stamethyst:minigame > agent-tmp/godot-spike/device-minigame-memory.txt
adb shell dumpsys meminfo io.stamethyst:game > agent-tmp/godot-spike/device-game-memory.txt
```

关注 `GodotPrototype`、`godot`、`AndroidRuntime`、`BOOT_OVERLAY_DISMISSED`、Surface/JVM 相关事件。**不要先清空 logcat**，以免丢失启动时错误；没有必要向外公开带个人信息的完整日志。

## 原型尚未覆盖

- 正式启动器入口/下载 UI、生产镜像、断点续传、坏缓存一键修复、旧版本回收、远程签名版本协议。
- Web 存档确认迁移的完整真机矩阵（实现见 [旧存档迁移](slingbreak-save-migration.md)）、Godot 预览独立应用存档迁移；现有浏览器存档均保持不动。
- 正式包移除旧小游戏、精确净体积收益。
- Android 文件选择器、Godot FileProvider/ProcessPhoenix 等非原型入口（不声明或不调用这些组件）。
- 远程代码下载的商店政策适配。
