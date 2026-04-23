# Launcher Steam Cloud 正式接入方案

基于 2026-04-22 的真实验证结果整理。目标不是继续做 spike，而是把 Steam 云存档正式接进 launcher，并且把接入边界、风险和分阶段落地顺序提前讲清楚。

## 1. 这次已经验证清楚的事实

- 已通过 `tools:steam-cloud-spike` 真实连通 Steam，并成功枚举 `AppID 646570` 的云文件。
- 真实清单文件在 [tools/steam-cloud-spike/.tmp/sts-steam-cloud-spike/cloud-list.tsv](/D:/Desktop/SlayTheAmethystModded/tools/steam-cloud-spike/.tmp/sts-steam-cloud-spike/cloud-list.tsv)。
- 当前真实远端文件总数是 126 个。
- 其中 `preferences/` 目录下 116 个，`saves/` 目录下 10 个。
- 远端路径前缀不是桌面端常见的绝对路径，而是 Steam Cloud 占位形式：
  - `%GameInstall%preferences/...`
  - `%GameInstall%saves/...`
- 这批文件不只包含原版档，还包含大量 Mod 角色和 Mod 偏好文件。
- 远端同时存在：
  - 常规偏好文件，例如 `STSPlayer`、`STSSeenCards`、`STSUnlocks`
  - 槽位前缀文件，例如 `1_STSPlayer`、`1_Tuner_CLASS`
  - 备份文件，例如 `STSPlayer.backUp`
  - 自动存档，例如 `WATCHER.autosave`
- 目前没有观察到 `runs/`、`metrics/`、`home/` 等目录出现在真实 Steam Cloud 清单中。

这几点直接决定了正式方案不能做“原版白名单同步”，而要做“远端清单驱动 + 本地路径映射”。

## 2. 对 launcher 设计的直接结论

- v1 同步范围应当只覆盖这次真实验证到的两个根目录：`preferences/` 和 `saves/`。
- 槽位前缀、`.backUp`、`.autosave`、Mod 文件名都应视为普通文件，不能特殊过滤。
- 正式实现必须先拉取远端清单，再决定下载、上传和冲突，不应写死固定文件表。
- 鉴权不能放在启动游戏的关键路径上交互式完成。首次登录和刷新凭据要单独做成设置页流程。
- 异常退出、崩溃、被强杀后的回到 launcher，不应自动回推本地存档到云端。
- 覆盖本地文件前必须先做本地备份；删除远端文件必须默认保守，不能在 v1 自动执行。

## 3. 当前项目里适合挂接的现有链路

### 本地存档根目录

- [app/src/main/java/io/stamethyst/config/RuntimePaths.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/config/RuntimePaths.kt)
- `RuntimePaths.stsRoot(context)` 是当前 launcher 管理的 STS 运行时根目录。
- `RuntimePaths.preferencesDir(context)` 已经存在。
- `stsRoot(context)` 下已经是当前 launcher 的真实存档落盘位置，因此云同步最终也应落到这里。

### 现有导入导出与备份能力

- [app/src/main/java/io/stamethyst/ui/settings/SettingsFileService.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/ui/settings/SettingsFileService.kt)
- 已经具备：
  - `exportSaveBundle(...)`
  - `importSaveArchive(...)`
  - `backupExistingSavesToDownloads(...)`
  - `clearExistingSaveTargets(...)`
- 这些逻辑说明 launcher 已经有成熟的“先备份，再替换本地存档”的处理模型，正式云同步应直接复用这套思路。

### 本地存档归档视角

- [app/src/main/java/io/stamethyst/ui/settings/SaveArchiveLayout.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/ui/settings/SaveArchiveLayout.kt)
- 当前归档支持的顶级目录比 Steam Cloud 实际观测范围更大，包括 `runs`、`metrics`、`home` 等。
- 这意味着：
  - 本地导入导出范围可以维持现状
  - Steam Cloud v1 不应直接沿用全部归档目录，而应只同步真实观测到的 `preferences/` 和 `saves/`

### 启动前与退出后时机

- 启动前主链路：
  - [app/src/main/java/io/stamethyst/ui/main/MainScreenViewModel.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/ui/main/MainScreenViewModel.kt)
  - `prepareAndLaunch(...)`
- 启动前准备服务：
  - [app/src/main/java/io/stamethyst/backend/launch/LaunchPreparationService.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/launch/LaunchPreparationService.kt)
  - [app/src/main/java/io/stamethyst/backend/launch/LaunchPreparationProcessService.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/backend/launch/LaunchPreparationProcessService.kt)
- 退出后分析链路：
  - [app/src/main/java/io/stamethyst/LauncherActivity.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/LauncherActivity.kt)
  - [app/src/main/java/io/stamethyst/ui/main/MainScreenViewModel.kt](/D:/Desktop/SlayTheAmethystModded/app/src/main/java/io/stamethyst/ui/main/MainScreenViewModel.kt)
  - `maybeScheduleGameReturnAnalysis()`
  - `handleGameProcessExitAnalysis(...)`

这几处已经给出很明确的挂点：

- 启动前拉取云端并下发本地：挂在 launch preparation 之前或之内
- 游戏正常退出后的回推：挂在 `ExpectedCleanShutdown` 分支之后
- `ExpectedBackExit`、显式崩溃、进程异常退出：默认不自动上传

## 4. 推荐目标方案

推荐把正式实现拆成三层，而不是把 `StsSteamCloudReadOnlySpike` 直接塞进 app。

### 4.1 协议层

职责：

- Steam 连接
- 凭据登录
- 云文件清单枚举
- 文件下载
- 文件上传

要求：

- 对 UI 无感知
- 不直接读写 `auth.env`
- 不依赖控制台输入
- 能接受外部传入的代理、协议、超时、凭据

### 4.2 同步规划层

职责：

- 把远端清单映射到本地 STS 路径
- 维护上次同步快照
- 判断本地变更、远端变更、双端冲突
- 产出“要拉哪些文件、要推哪些文件、哪些需要用户确认”的计划

### 4.3 Launcher 编排层

职责：

- 设置页登录与账号管理
- 手动同步按钮
- 启动前自动下拉
- 正常退出后自动上推
- 进度、错误提示、冲突确认 UI

## 5. 为什么不应该直接复用 spike 主类

当前 spike 是一个独立 JVM 命令行工具：

- [tools/steam-cloud-spike/src/main/java/io/stamethyst/tools/steamcloud/StsSteamCloudReadOnlySpike.java](/D:/Desktop/SlayTheAmethystModded/tools/steam-cloud-spike/src/main/java/io/stamethyst/tools/steamcloud/StsSteamCloudReadOnlySpike.java)
- [tools/steam-cloud-spike/build.gradle.kts](/D:/Desktop/SlayTheAmethystModded/tools/steam-cloud-spike/build.gradle.kts)

它当前是“协议验证脚手架”，不是 launcher 运行时代码，原因有三点：

- 它有命令行输入输出语义，例如控制台 2FA 输入和 `auth.env` 写出。
- 它当前模块是 Java 11 JVM CLI；而 `:app` 目前是 Android/JVM 1.8 编译目标。
- 它把连接、鉴权、清单输出、下载动作都耦在一个主类里，不适合做 UI 驱动和状态持久化。

正式接入时，应该保留 spike 作为回归验证工具，但把可复用逻辑抽出去。

## 6. 正式实现的两条可行路线

### 路线 A：Android 原生集成

思路：

- 把 Steam Cloud 客户端实现为 app 内的后台服务或后台协调器
- UI 通过设置页和启动前/退出后状态机驱动

优点：

- 用户体验最好
- 不需要额外拉起 JVM helper
- 状态同步、进度和错误提示最直接

风险：

- 需要先验证 JavaSteam 相关依赖在 Android 运行时是否稳定可用
- 需要把当前 Java 11 CLI 逻辑重写成 Android 友好的实现

### 路线 B：JVM helper 侧车

思路：

- 保留 Steam 协议实现为纯 JVM 模块
- launcher 通过现有运行时/JVM 基础设施拉起一个短生命周期 helper
- helper 输出结构化结果，再由 launcher 应用本地文件

优点：

- 可以最大化复用当前 spike 的协议实现经验
- 和 Android 运行时隔离，依赖兼容性风险更低

代价：

- 启动成本更高
- IPC、进度回传、凭据传递更复杂
- 正式产品体验不如原生集成直接

### 推荐结论

推荐按“先验证，再定稿”的方式推进：

1. 先做一轮 Android 兼容性 spike，只验证“使用 refresh token 枚举真实云清单”。
2. 如果 Android 侧稳定可用，正式方案走路线 A。
3. 如果 Android 侧出现依赖或运行时兼容性问题，再切路线 B。

这样可以避免一开始就把架构押错。

## 7. v1 的同步范围与路径映射规则

### 7.1 远端根目录白名单

v1 只认这两个远端前缀：

- `%GameInstall%preferences/`
- `%GameInstall%saves/`

其他前缀全部记录日志并忽略，不自动处理。

### 7.2 本地映射规则

映射目标根目录统一是 `RuntimePaths.stsRoot(context)`。

示例：

- `%GameInstall%preferences/STSPlayer`
  - -> `stsRoot/preferences/STSPlayer`
- `%GameInstall%preferences/1_STSPlayer.backUp`
  - -> `stsRoot/preferences/1_STSPlayer.backUp`
- `%GameInstall%saves/WATCHER.autosave`
  - -> `stsRoot/saves/WATCHER.autosave`

注意：

- 不做原版/Mod 文件名判断
- 不裁剪 `1_` 前缀
- 不裁剪 `.backUp`
- 不裁剪 `.autosave`

换句话说，正式实现要把远端文件名当成字节级普通文件名处理。

## 8. 推荐的数据模型

建议新增 `app/src/main/java/io/stamethyst/backend/steamcloud/` 包，至少包含以下对象。

### 8.1 鉴权与配置

- `SteamCloudAuthStore`
  - 持久化 `accountName`
  - 持久化 `refreshToken`
  - 持久化 `guardData`
  - 持久化最近一次成功协议和代理配置
- `SteamCloudNetworkConfig`
  - `protocol = auto | websocket | tcp`
  - `proxyUrl`
  - `connectTimeoutMs`

其中敏感信息不要继续落 `auth.env`，正式实现应放到 Android 私有存储，并优先使用加密存储。

### 8.2 远端清单

- `SteamCloudRemoteEntry`
  - `remotePath`
  - `relativeRoot`
  - `relativePath`
  - `rawSize`
  - `timestamp`
  - `machineName`
  - `persistState`

- `SteamCloudManifestSnapshot`
  - `appId`
  - `fetchedAt`
  - `entries`

### 8.3 本地快照

- `SteamCloudLocalEntrySnapshot`
  - `relativePath`
  - `exists`
  - `size`
  - `lastModified`
  - `sha256`

- `SteamCloudSyncBaseline`
  - 上次成功同步时的本地快照
  - 上次成功同步时的远端清单摘要

### 8.4 规划结果

- `SteamCloudSyncPlan`
  - `downloads`
  - `uploads`
  - `conflicts`
  - `ignoredEntries`
  - `warnings`

- `SteamCloudConflict`
  - `relativePath`
  - `lastSyncedState`
  - `currentLocalState`
  - `currentRemoteState`
  - `suggestedResolution`

## 9. 冲突检测与删除策略

### 9.1 冲突判断

不要只看当前本地和远端时间戳，而要基于“上次同步基线”做双端变更判断：

- `localChanged`
  - 当前本地哈希或存在性，相比上次同步基线发生变化
- `remoteChanged`
  - 当前远端 `rawSize/timestamp` 或存在性，相比上次同步基线发生变化

处理规则：

- 仅远端变更：自动或手动拉取
- 仅本地变更：可上传
- 双端都变更：进入冲突，不自动覆盖

### 9.2 删除策略

v1 推荐保守处理：

- 不自动删除远端文件
- 不因为本地缺文件就默认删云端
- 不因为远端缺文件就默认删本地

如果用户未来明确需要“镜像推送”或“镜像拉取”，那应做成显式高风险操作，不放进 v1 自动同步。

## 10. 同步时机建议

### 10.1 设置页手动操作

在现有设置页导入导出区域附近新增 `Steam Cloud` 分区：

- 登录 Steam
- 刷新云清单
- 立即拉取
- 立即推送
- 查看冲突
- 清除账号凭据

这是 v1 最先要交付的交互入口。

### 10.2 启动前自动下拉

推荐逻辑：

- 用户启用“启动前检查云存档”
- 启动游戏前先拉远端 manifest
- 如仅远端有变化：
  - 先做本地备份
  - 再下载并覆盖到 `stsRoot`
- 如检测到冲突：
  - 弹出确认
  - 默认不自动合并
  - 用户可选择“本次跳过同步继续启动”

挂点建议：

- 在 `prepareAndLaunch(...)` 前增加快速云同步检查
- 如果未来同步动作较重，再并入 `LaunchPreparationProcessService` 一并做进度展示

### 10.3 正常退出后自动上推

推荐逻辑：

- 只有在 `ExpectedCleanShutdown` 之后，才考虑自动上传
- 上传前重新拉一次远端 manifest 做基线核对
- 如果是“仅本地变更”，允许自动上传
- 如果远端也变更，转为冲突，不自动推

挂点建议：

- 在 `handleGameProcessExitAnalysis(...)` 的 `ExpectedCleanShutdown` 分支后触发

### 10.4 异常退出后的策略

以下情况默认不自动上传：

- `ExpectedBackExit`
- 显式崩溃
- 进程退出异常
- 无法确认是否干净落盘

这条是正式接入里最重要的保守策略之一。

## 11. 登录与凭据管理建议

### 11.1 首次登录流程

正式产品里不要在启动游戏时临时要求用户输入 2FA。推荐流程是：

- 在设置页完成首次账号登录
- 成功后保存 refresh token 和 guard data
- 之后所有自动同步都只走 refresh token

### 11.2 针对这次踩到的问题的设计结论

这次实际调试已经说明两个问题：

- 交互式 2FA 输入不适合放在长链路里
- websocket 链路在长时间等待手动输入时更容易暴露 watchdog 风险

因此正式产品设计必须满足：

- 自动同步不依赖控制台输入
- 2FA 只出现在显式登录流程里
- 登录成功后持久化 refresh token
- 代理和协议作为可配置的高级项保存下来

## 12. 代码落点建议

建议新增以下实现单元。

### 后端

- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudAuthStore.kt`
- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudClient.kt`
- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudPathMapper.kt`
- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudSnapshotStore.kt`
- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudDiffPlanner.kt`
- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudApplyService.kt`
- `app/src/main/java/io/stamethyst/backend/steamcloud/SteamCloudSyncCoordinator.kt`

### UI

- `SettingsScreen.kt` 增加 Steam Cloud 分区
- `SettingsScreenViewModel.kt` 增加登录、刷新、拉取、推送、冲突查看入口
- 新增 Steam Cloud 状态卡片和冲突确认弹窗

### 现有工具模块

- 保留 `:tools:steam-cloud-spike`
- 后续把协议相关公共逻辑逐步抽成可复用组件
- 让 spike 继续承担桌面端回归验证和协议排障职责

## 13. 分阶段落地顺序

### Phase 0：Android 兼容性验证

目标：

- 在 app 环境里完成一次“用 refresh token 枚举真实 manifest”
- 优先验证直连场景；如果测试网络依赖代理，允许通过开发者设置显式填写代理地址继续验证 Android 侧接入可行性

通过标准：

- 能连通 Steam
- 能枚举出真实 126 个文件
- 能稳定处理代理/协议配置
- 没有 Android 运行时兼容性问题

### Phase 1：只读接入

目标：

- 设置页可登录
- 可刷新 manifest
- 可显示远端文件计数、最近同步时间、账号信息
- 可手动拉取选定文件或全量文件

### Phase 2：可写接入

目标：

- 建立本地/远端基线快照
- 支持手动上传新增或修改文件
- 支持冲突检测
- 拉取前自动备份本地

### Phase 3：自动同步

目标：

- 启动前自动下拉
- 正常退出后自动上推
- 冲突时中断自动覆盖并交给用户确认

### Phase 4：高级能力

目标：

- 代理高级设置
- 协议记忆与重试策略
- 详细冲突明细页
- 可能的镜像模式和显式删除操作

## 14. 测试矩阵

- 原版无 Mod 档
- 含大量 Mod 偏好文件
- 含槽位前缀文件
- 含 `.backUp` 文件
- 含 `.autosave` 文件
- 本地为空，远端有档
- 本地有档，远端为空
- 本地和远端同时变更
- 干净退出
- Back Exit
- 崩溃退出
- refresh token 过期
- 需要重新登录
- 无代理直连
- 走 HTTP 代理的 websocket

## 15. 最终推荐

基于这次拿到的真实云文件结构，launcher 的正式接入不应再围绕“几个原版文件名”设计，而应围绕“`preferences/` + `saves/` 的完整远端清单”设计。

推荐的正式落地策略是：

1. 先完成 Android 侧兼容性验证。
2. v1 只同步真实观测到的 `preferences/` 和 `saves/`。
3. 启动前只做安全下拉，退出后只在 `ExpectedCleanShutdown` 后做安全上推。
4. 任何双端同时变更的情况都先进入冲突，不做自动覆盖。
5. 保留 `steam-cloud-spike` 作为协议验证工具，但不要把 CLI 主类直接作为产品实现。

这样可以把功能做成真正可上线的 launcher 能力，而不是停留在一次性脚本层面。
