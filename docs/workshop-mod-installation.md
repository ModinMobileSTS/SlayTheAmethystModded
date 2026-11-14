# 市场模组的继续安装与下载状态

- 已下载但尚未导入修补（`ImportedUnpatched`，包括修补失败），以及更新待安装
  （`UpdatePendingInstall`）的模组，卡片右侧用「安装」
  替换启用开关，不在卡片下方重复显示安装按钮；安装成功后该位置恢复启用开关。
  右侧控件槽只限制最小宽度，给不同语言的按钮文字留足空间，避免沿用开关固定宽度裁切按钮。
- 「工具」面板保留 `ModWorkshopActions` 的安装入口。两处均沿用现有市场导入流程；
  多个 jar 时仍先选择文件，不重新下载已经取得的 jar。
- 安装入口不依赖 `mod.installed` 或已安装文件的管理权限，批量选择模式下不可操作。
  暂停、失败、文件缺失分别显示继续下载、重试、重新下载，启动器管理的资源仍禁止重复下载。
- 模组页与市场页使用同一下载任务存储、`WorkshopModStateResolver` 和 500 ms 活跃下载刷新间隔，
  同步排队、下载、暂停、取消、失败、进度及状态说明，不把排队或取消显示为正在传输。
- Dock 页面保持组合时，从市场返回模组页也会立即同步任务并重新扫描，不受普通刷新冷却影响；
  下载在市场页完成后不会因为模组页已无活跃任务而漏掉安装入口。
- `updateWorkshopModCards` 检测每个任务退出轻量任务列表，立即要求重新扫描安装结果，
  不再等待整个下载队列结束。扫描前保留卡片，扫描后显示「安装」或已安装模组，避免卡片消失。
- 市场页同样在单个下载结束及重新进入页面时刷新安装元数据，其他任务继续下载；
  不再等到整个队列空闲才更新。

离线验证：`WorkshopModCardUpdatesTest` 覆盖市场状态一致性、进度、单任务结束和保留本地配置；
`ModDetailsUiHelpersTest` 覆盖安装与开关的互斥状态；`ModWorkshopActionsInstrumentedTest`
覆盖卡片右侧安装位置、按钮不重复、安装后恢复开关、批量模式禁用和工具面板安装入口，
可离线编译，运行需单独设备授权。

## 更新待安装：旧版安装与新版下载分开保存

下载与更新仍共用 `WorkshopDownloadProcessService` 和 `WorkshopAutoImporter`。
旧版的主 jar 路径、远端版本时间和安装记录不被新下载覆盖；完整下载后在元数据中持久化
`pendingInstall`，包含新版 jar 路径、远端版本时间和依赖。旧版 JSON 缺少该字段时按无待安装版本读取。

- 修补正在运行（含排队、暂停收尾和取消收尾）时仍显示任务状态，禁止重复安装。
- 修补失败、异常或进程中断后，由 `WorkshopModStateResolver` 显示独立的
  `UpdatePendingInstall`，卡片右侧替换开关为「安装」；市场显示「更新待安装」，不重新下载。
  普通更新筛选也保留这些卡片。
- `WorkshopInterruptedDownloadRecovery` 优先恢复完整待安装候选，不再只恢复旧版本。
  下载中心每次本地刷新还会检查已持久化候选对应的停止任务，避免一次性恢复和旧心跳宽限期漏掉它。
  正在运行的下载服务及显式登记的手动导入任务不参与恢复。
- 手动重试及多 jar 选择读取 `pendingInstall` 的新版文件，而非旧版已安装文件；即使下载历史已清理，
  仍能使用持久化的新版时间和依赖。失败继续保留候选，成功后才更新安装版本、清空候选并清理下载目录。
- `canCleanDownloadedJars` 同时检查旧状态与待安装候选，防止恢复旧 `ImportedPatched` 记录后误删新版文件。
  下载尚未完成时不能只凭目录中存在 jar 就认定可安装；候选文件丢失时保留旧版并退回重新更新。

验证包含元数据往返/兼容、更新失败保留、修补进度不足 100% 时冷恢复、部分下载拒绝、同版本修补升级、
本地重试成功提升版本、成功提交后尚未清理时的恢复、市场/卡片状态一致性及右侧控件互斥。
原更新中断回归测试在旧代码实际失败（`UpdateAvailable` 而非 `UpdatePendingInstall`）；
Android UI 测试仅离线编译，真机杀进程/重启与实际显示仍需单独验收，不能用单元测试替代。

本轮 85 项 JVM 回归全部通过，`:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`
均通过；生成 `app/build/outputs/apk/debug/app-debug.apk`，并核对 DEX 中包含
`UpdatePendingInstall`、`pendingInstall` 和卡片安装控件。未安装或卸载设备应用。
