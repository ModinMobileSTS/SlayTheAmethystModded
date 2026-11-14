# 刷新率诊断

入口：**设置 → 开发者设置 → 状态与日志 → 刷新率诊断**。设置搜索也可搜索“刷新率诊断”或“查看上次刷新率报告”。遵循现有开发者设置警告与加载禁用规则。

点击后自动打开同进程、非导出的独立诊断 Activity，依次执行十个阶段。每阶段等待测试 View/Surface/Compose 首次绘制就绪（最多 5 秒），请求后进行 700 ms 动画预热、2500 ms 动画采样，正常约 35 秒完成。测试画面覆盖诊断窗口，不使用对话框窗口，以避免额外窗口偏好以及小面积预览干扰测试。阶段编号与进度使用实际阶段总数。

1. 默认窗口：清除本诊断窗口的刷新率/模式偏好，观察有动画时的系统选择；120 Hz 只是比较基准，并非此阶段提交的请求。
2. 模式请求：选择当前分辨率下刷新率最高的显示模式，设置 `preferredDisplayModeId`；报告记录实际选中的模式和目标刷新率，可能不是 120 Hz。
3. 窗口请求：清除模式 ID，设置 `preferredRefreshRate=120`。
4. SurfaceView：窗口请求 120 Hz，并对实际显示 Surface 请求 `setFrameRate(120, DEFAULT, ONLY_IF_SEAMLESS)`；API 30 使用双参数重载，低于 API 30 则如实记录未能提交。使用硬件 Canvas 绘制。
5. TextureView：窗口请求 120 Hz，使用 Canvas 绘制；此对照阶段不请求内部 Surface 帧率。
6. View High：仅对绘制动画的 View 使用生产 `ViewFrameRateVote` 请求 High，不叠加窗口或 Surface 请求；API 35 以下记录请求失败。
7. Compose 基线：同样的图案由 Compose Canvas 连续绘制，不安装 High modifier。
8. Compose High：同样的 Compose Canvas 使用生产 `rememberHighRefreshRateModifier(enabled = true)`，保留 API 35+/RESUMED/窗口焦点门槛；API 35 以下记录请求失败。
9. SurfaceView 生产组合：同分辨率最高窗口模式、生产 Surface 请求以及 API 35+ 渲染 View High。
10. TextureView 生产组合：与上一阶段相同的请求组合，使用实际 TextureView 和独立 `Surface(surfaceTexture)` 包装；包装只代表生产者端请求路径，不代表独立显示输出层。

原五阶段的 key、顺序和请求语义不变，新增阶段追加在后，便于与旧报告对照。模式选择与生产窗口共用 `DisplayRefreshRatePolicy.highestModeAtCurrentResolution`：仅匹配当前物理分辨率，过滤无效模式，同速优先保留当前 mode ID，缺少有效匹配时不回退到其它分辨率。每阶段重新读取当前显示器和模式。

生产组合复用不写偏好的窗口/Surface helper 以及实际 View 投票实现。Surface 参数仍为 `DEFAULT/ALWAYS`（API 30 为双参数，低于 API 30 跳过），目标仍与生产一样取显示器公开的全模式最高刷新率；窗口 mode ID 则严格限定当前分辨率。低于 API 35 时生产组合跳过 View High，与生产一致。High 是类别，不是固定 120 Hz；新增 High/Compose 基线以当前分辨率最高模式为比较基准，生产组合以生产 Surface 目标为基准。其它原对照仍使用 120 Hz，模式阶段使用选中模式的刷新率。

## 报告

- 启动/结束时间、是否中断、清理结果。
- 应用版本/包名/PID/UID、设备/Android/固件/指纹、全部显示模式及当前分辨率、电池/充电/省电/温控、可公开读取的亮度和刷新率设置、游戏持久化 FPS/帧同步/渲染后端配置、API 36+ ARR 支持值及 API 35+ 窗口省电平衡标志（不可用时如实标记）。
- 每阶段的请求参数、调用异常、请求前后状态、采样期每约 250 ms 状态、结束状态，以及显示变化事件。
- 应用读取刷新率、显示模式 ID/刷新率、分辨率、窗口偏好、焦点、温控/省电状态；API 35+ 实际内容 View 的原始 `requestedFrameRate`（字符串保留类别值或 NaN），以及 Compose 阶段 High modifier 是否安装。modifier 安装不等于系统接受，Compose 内部绘制层也不等于宿主 View 的读回值。
- Choreographer **回调到达时间**间隔数、平均回调频率、中位数/P95/最长间隔、超过目标周期 1.5 倍的间隔比例、测试绘制计数/错误数。
- 分别判断目标是否在大多数稳定采样中出现、回调是否接近目标、是否变化、请求失败、采样不足或中断。回调不足/绘制失败/采样无焦点时不作成功判断。

120 Hz 判定容许 1 Hz 模式误差（例如 119.88 Hz）。至少需要 30 个有效回调间隔、30 次绘制、5 个有效刷新率观测，且采样观测均有焦点、无绘制错误。稳定采样中至少 80% 达标、回调频率至少为目标 90% 时才显示同时观测到目标应用刷新率及接近目标的回调；这仍不是物理屏幕测量。默认阶段的判定同样只是与基准比较。结构化 JSON 保存原始字段及每阶段 `outcomeCode`，不依赖本地化文本解析。

报告正文可滚动、选择文字，底部重测/导出/关闭动作常驻。导出使用系统文件选择器生成 ZIP（`report.txt` + `report.json`，新报告 schemaVersion=2）。新增观测字段 `viewRequestedFrameRate` 和 `composeHighVoteInstalled` 可缺省，仍可读取 schema 1。最近一次报告在应用内部原子保存为 `files/refresh-rate-diagnostics/latest-report.zip`，可稍后重新打开。普通反馈/崩溃/分享日志包也附带 `sts/refresh_rate/latest-report.zip`；此文件有独立时间戳，不应假定它与日志最后一局来自同一次复现。只保留最近一次报告。

切换阶段时移除上一阶段的 View/Compose 投票，销毁 composition、释放自建 TextureView Surface 包装并移除测试 View；不手动释放借用的 SurfaceView holder Surface 或 TextureView 的 SurfaceTexture。取消、返回、切到后台或 Activity 重建会停止当前测试，恢复窗口原刷新率/模式和常亮标志、移除显示监听、释放测试 View/Surface，并保存已完成/当前采样阶段的部分结果。进程被系统直接杀死不能保证生成新报告，原有报告保持不变。旋转后保留 ViewModel 中结果，不将中断数据继续拼成完整测试。

## 能力边界与安全

- 这是诊断 Activity，不是首页 Activity 或游戏 Activity。View/Compose Canvas 不等价于游戏 JVM/EGL/Swappy；配置值不是运行时游戏 FPS 或实际软件限帧值。
- 首页和游戏另有 [View/Compose 显式高刷投票](view-frame-rate-voting.md)。新增诊断阶段复用投票实现和请求参数，但不代表真实首页交互负载或游戏 EGL 渲染；不能将旧基线与新请求混为同一阶段。
- `Display.refreshRate` 是应用可见值，可能被系统覆盖；显示模式数值也不是可变刷新面板的实时物理扫描测量。回调频率和绘制数不代表实际呈现 FPS。
- `setFrameRate` 返回不代表被系统批准，诊断不能假造 ColorOS 拒绝原因、白名单限制或电源/温控归因。
- 不运行 ADB、Root、私有 Binder 事务或 shell dumpsys；不请求特权 DUMP/READ_LOGS，不修改全局/单应用系统刷新率设置或游戏配置，不安装或启动游戏，不上传报告。
- 目标 FPS 使用 `LauncherConfig.readTargetFpsWithoutMigration`，复用现有解析/归一化规则，但不因观察配置而迁移写回旧设置。
- 原 Surface 对照保持 `ONLY_IF_SEAMLESS`，追加的生产组合使用 `ALWAYS`；生产窗口与诊断均限定当前分辨率。这些路径差异在报告环境字段中标明，不能将诊断成功直接等同于游戏请求路径成功。诊断不写生产请求刷新率的 SharedPreferences。
- 温控数值采用 Android 等级 0–6；电池温度不是屏幕温控限制的证明。公开 API 未提供 ColorOS 字符串时，以固件显示 ID 和指纹辅助识别。
- 分享前检查固件标识和配置。报告不收集 IMEI、账号、密钥或原始 SharedPreferences。
- 若这里仍无法解释，需用户授权后在指定设备上补充 active launcher/game 的只读系统显示策略快照，必要时短时 Perfetto；不能仅凭这一测试宣称 Reno15 120 Hz 已修复。

## 验证

默认只做本地 JVM 测试与 Android 测试代码编译，不运行 connected/managed-device/macrobenchmark，不自动安装卸载。测试类：

- `DisplayRefreshRatePolicyTest`：OPPO 双分辨率模式顺序回归、更高异分辨率、无匹配/无当前模式、无效值、同速保留当前模式。
- `RefreshRatePhaseTest`：保留原五阶段、Compose A/B、View 独立投票、两种生产后端请求计划。
- `RefreshRateReportTest`：采样统计、判定边界、部分报告、JSON 往返、ZIP 内容、schema 1 兼容和 schema 2 投票观测字段。
- `RefreshRateDiagnosticsInstrumentedTest`：长报告固定动作、读取/保存期间禁用、原子报告 ZIP（写入测试包目录，不覆盖用户报告）、动态阶段编号、Compose 基线/High/切换清理以及 View High 在下一基线前恢复。仅编译不代表设备上已通过。

真机待验：实际十阶段结果、View/Compose 投票移除、SurfaceView/TextureView 生命周期与包装释放、后台/取消/旋转、文件选择器取消/失败、横屏大字体、最近报告重新打开、普通日志附带报告。需要明确设备及相应操作授权；编译通过不等于这些真机验收已通过。
