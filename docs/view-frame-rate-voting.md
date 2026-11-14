# View / Compose 显式高刷投票

在已有窗口/Surface 请求之上接入 Android 15（API 35）起的 View/Compose 帧率提示；不改变用户目标 FPS 或软件 pacing 配置。窗口选模另外修正为仅选择当前分辨率下的最高刷新率，Surface 参数保持原有 `DEFAULT/ALWAYS`。

## 启动器 Compose

`LauncherContent` 的实际内容 Surface 使用 `rememberHighRefreshRateModifier()`。仅当 API 35+、Activity 为 RESUMED 且当前窗口有焦点时，应用公开的 `Modifier.preferredFrameRate(FrameRateCategory.High)`。

这是 Compose 绘制层 modifier，覆盖其内容中的动画、滚动、主页横向 Pager 和导航过渡；Compose 1.10.3 自身负责将其传递给相应子绘制层，移除时还原继承偏好。它不是给普通 Android ViewGroup 设值并假定所有子 View 自动继承。失焦/暂停时移除 modifier，内容离开 composition 时由 Compose 释放；独立 Dialog/其它 Activity 不自动继承该请求。

静态页面没有人为增加的动画、定时任务、空绘制或 `invalidate()` 保活。High 是系统定义的类别，不硬编码为 120 Hz，更不是保证物理面板常驻最高刷新率。旧系统不增加这个绘制层 modifier，也不调用新的 View API。

## 游戏 View

`RenderSurfaceManager` 对实际 SurfaceView 或 TextureView 创建 `ViewFrameRateVote`。当游戏前台、有窗口焦点且 Surface 生命周期有效时，向 **该渲染 View** 调用 `setRequestedFrameRate(REQUESTED_FRAME_RATE_CATEGORY_HIGH)`。

- Surface available、销毁、前后台和焦点变化触发状态评估；View 在 attach 期间晚于 Surface callback 初始化的情况，在 init 结束再同步一次。
- 只有值变化才调用 setter，不添加逐帧采样、周期重试或 UI 空绘制；系统读回刷新率暂时较低不会改变 High 偏好。
- 失焦、后台、Surface 销毁、manager 销毁时，恢复接管前的 View 偏好，而不是一律写 0。
- 如果另一个所有者（例如 TextureView 的平台 SurfaceTexture 转发）之后改写了偏好，释放时不覆盖它。重新接管时记录新的基线。
- API 35 以下不调用；厂商 RuntimeException/LinkageError 被记录，不让渲染生命周期崩溃。Java API 无异常不代表系统接受。
- SurfaceView 的独立 EGL 帧仍以原 Surface 请求为基础；View 提示是补充层，不通过人工重绘将 EGL 输出伪装成 View 动画。

`DisplayRefreshRatePolicy` 窗口选模只匹配当前物理分辨率、过滤无效模式，同速时优先当前 mode ID；无有效匹配时不回退到其它分辨率。例如 OPPO 列表同时有 `1216x2640@120` 与 `1080x2344@120`，当前为后者分辨率时不会因前者排列更早而跨分辨率选模。Surface ALWAYS 请求参数与原来的全模式最高刷新率目标保持不变。窗口/Surface 请求拆出不写偏好的 helper，供诊断复用；仅生产入口保存请求元数据。没有默认关闭 ARR，没有修改全局显示设置、制造触摸事件、安装卸载或清理应用数据。

## 验证与边界

- JVM：`ViewFrameRateVoteStateTest` 覆盖 API/前台/焦点/Surface 条件、请求去重、暂停和销毁的恢复、NaN/已有 High 基线、其它所有者、重新恢复，以及 setter 前后失败/恢复失败的重试。
- Android 编译覆盖：`HighRefreshRateModifierInstrumentedTest` 核对生命周期/窗口焦点以及诊断 enabled 开关对实际公开 modifier 的增删；`ViewFrameRateVoteInstrumentedTest` 用记录 View 核对 API 门槛、High/恢复调用及异常隔离。它们不证明物理刷新率变化，默认不执行设备测试。
- [刷新率诊断](refresh-rate-diagnostics.md) 保留原五阶段请求语义，另加 View High、Compose 基线/High、SurfaceView/TextureView 生产组合，共十阶段。复用生产投票实现与窗口/Surface helper，记录原始 View 请求值和 Compose modifier 安装状态，仍不代表真实首页或游戏负载、系统批准或物理面板刷新率。
- 真机仍需分别检查首页连续滚动/动画、SurfaceView/TextureView 游戏、无触摸、焦点切换、前后台、Surface 重建和功耗。API 35/36 也不能保证 ROM 的 ARR flag/HAL 支持；不能据编译或单测宣称 OPPO 高刷回归已修复。

本地验证（仓库根目录，不部署设备）：

```bash
./gradlew --offline :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.render.ViewFrameRateVoteStateTest' \
  --tests 'io.stamethyst.backend.render.DisplayRefreshRatePolicyTest' \
  --tests 'io.stamethyst.backend.render.RenderSurfaceStateTest' \
  --tests 'io.stamethyst.backend.render.SurfaceFrameRateVoteStateTest' \
  --tests 'io.stamethyst.backend.diag.refresh.RefreshRatePhaseTest' \
  --tests 'io.stamethyst.backend.diag.refresh.RefreshRateReportTest' \
  :app:compileDebugAndroidTestKotlin
```

参考：[Android ARR 文档](https://developer.android.google.cn/develop/ui/views/animations/adaptive-refresh-rate?hl=en)、[Android 帧率请求](https://developer.android.google.cn/media/optimize/performance/frame-rate?hl=en)。
