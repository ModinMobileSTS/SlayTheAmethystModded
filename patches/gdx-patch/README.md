# gdx-patch

启动器使用的 libGDX 兼容层补丁。

## Windows 风格模组资源路径兼容

- **症状：** 模组使用反斜杠拼接资源路径时，Linux/Android 上 atlas 本身可以从 classpath 读取，
  但 `FileHandle.parent()` 丢失目录；页图落到根目录，出现找不到图片，或误读同名资源导致白块。
  铃兰的 `wild.png` 报错和默认皮肤误读游戏字体 `default.png` 是同一类问题。
- **修复：** `ResourceFileHandleCompat` 在 `FileHandle(String, FileType)` 和
  `FileHandle(File, FileType)` 构造入口统一 Internal/Classpath 路径的 `\` 为 `/`，
  使读取、名称、父目录、子路径和 sibling 使用一致的底层 `File`。规则不依赖模组名或资源扩展名。
  Local/External/Absolute 保持原有文件系统语义；不按文件名搜索、不吞掉真正的缺失资源错误。
- **接线：** `StsResourceFileHandlePatcher` 对游戏原类的两个构造方法做幂等字节码注入，
  保留其余方法；`StsDesktopJarPatcher` 注入 helper、检查构造方法补丁状态，启动时更新旧缓存。
  `REQUIRED_STS_PATCH_CLASSES` 登记 helper，避免只有构造调用而缺少运行时依赖。
- **验证：** `ResourceFileHandleCompatTest` 验证类型范围、混合分隔符、Unicode/空格、空值和幂等；
  `StsResourceFileHandlePatcherTest` 加载真实游戏 FileHandle/LwjglFileHandle/atlas 解析器，
  对照原版的根目录误匹配，覆盖两种构造、嵌套页图、读/exists、parent/child/sibling 和缺失资源。
  JVM 验证不等同于 Android GPU 画面实测；直接绕过 libGDX 的自定义文件加载器不在此入口范围内。

## 资源创建/销毁可视事件

- **症状：** 原释放提示和时序图缺少图像创建事件，颜色不能区分创建与销毁，提示超时消失且无法滚动。
- **实现：** `RamSaverReleaseEvents` 增加创建/销毁动作与共享蓝/橙颜色；`GLTexture` 在成功上传后发出创建事件，补齐非受管纹理的真实销毁事件；受管纹理仍由 RamSaver 保留原销毁原因、避免重复。`GLFrameBuffer` 发出构建与释放事件，Region 注册/解绑由 RamSaver 发出，零 GPU 字节。
- **视图：** RamSaver 的独立事件窗常驻左上角，最新六条替换旧行，可滚动查看最近 512 条；`FrameHud` 以固定创建/销毁双色带对应实际完成帧，保留耗时柱。窗口输入遮罩由独立 `VisualResourceInputPatches` 实现。调试窗口自身的白色像素通过可嵌套内部资源作用域排除。
- **边界：** 沿用默认关闭的 `ramsaver.debug.visual`，不改变持久化耗时诊断、回收预算、手牌保护或 GPU 同步语义。失败上传/未物化假纹理不冒充图像创建。参见 `mods/ram-saver/README.md` 第 44 项和对应事件、生命周期、滚动输入、FrameHud 测试。

## 逐资源生命周期阻塞耗时

- **症状：** 原有慢事件/重复抑制日志无法逐条回答创建、上传、回收各自阻塞多久，诊断日志导出也不包含完整耗时明细。
- **实现：** `ResourceLifecycleTimings` 提供独立诊断门控、可复用固定容量队列和后台 JSONL 写盘；`GLTexture` 记录上传、GL 名称创建和真实释放/上下文失效，`GLFrameBuffer` 记录构建、回收、重建和销毁。RamSaver 自己的资源所有权/Region 入口补齐外层耗时及父子关系，详见其 README 第 43 项。不会为计时调用 GPU fence/`glFinish`，也不额外查询上传绑定。
- **取舍：** 用户确认逐条完整优先，4096 槽满时生产者等待，不采样/覆盖；排队等待单列。所有格式化与磁盘 I/O 在后台。关闭诊断不分配记录器或调用新计时点。磁盘错误、崩溃、强制退出的未落盘风险会显式报告，不宣称断电持久化或 GPU 执行时间。
- **接线：** `StsLaunchSpec` 随深度性能诊断启用开关并设置路径；`StsDesktopJarPatcher` 注入主类及全部嵌套类；`ResourceTimingArchive` 在普通/性能/反馈导出链路打包当前与上一会话 JSONL、汇总和导出状态。
- **验证：** `ResourceLifecycleTimingsTest`、RamSaver `ResourceLifecycleTimingIntegrationTest`、app `ResourceTimingArchiveTest`、`PerformanceDiagnosticsArchiveTest`、launch policy 和 desktop patcher 测试覆盖记录、阻塞、失败、生命周期及归档边界。Android 驱动延迟与帧率仍需实机验证。

## 纹理上传临时像素清理

- **症状：** 纹理上传失败时格式转换/缩放产生的 native Pixmap 未释放；libGDX 1.9.5 软件 mipmap 路径也会保留最后一级临时像素，削弱 RamSaver 后台解码 staging 的有界生命周期。
- **修复：** `GLTexture.uploadImageData` 在 `finally` 中释放其拥有的上传 Pixmap，转换/缩放异常恢复全局 blending 并释放临时对象。`MipMapGenerator` 保留原硬件选择/API/级别生成语义，释放最后一级及异常中的软件 mipmap；借入的源 Pixmap 仍由调用方负责。
- **接线：** `StsDesktopJarPatcher` 白名单及 `REQUIRED_STS_PATCH_CLASSES` 包含 `MipMapGenerator.class`，确保覆盖游戏 jar 的原实现。`StsDesktopJarPatcherTest` 校验此项。
- **验证：** RamSaver 的 `BackgroundTextureDecodeTest` 使用真实 native Pixmap、mock GL 验证上传失败、format/mipmap、managed reload 及 blending 恢复；没有宣称 Android 帧率或驱动延迟实测。完整后台解码覆盖范围和预算见 `mods/ram-saver/README.md` 第 37–38 项。

## Android 桌面窗口图标兼容

- **症状：** 启动时打印 `ArrayIndexOutOfBoundsException: 1`，堆栈指向
  `org.lwjgl.opengl.Display.setIcon(Display.java:945)`。
- **原因：** 游戏只提供一个图标，而 LWJGL 桥接层在窗口创建前访问 `icons[1]`；
  此外，该实现把 RGBA 像素缓冲区直接当作 GLFW 图像结构体缓冲区使用。
  桥接层内部捕获并打印异常，因此外层异常保护不能消除这条堆栈。
- **修复：** `WindowIconCompat` 通过启动器始终设置的
  `amethyst.renderer.effective_backend` 识别 Android 桥接路径，
  `LwjglGraphics.setupDisplay()` 在该路径跳过桌面窗口图标的加载和设置，
  不影响 Android Manifest 中的应用图标。未设置该平台标记的桌面路径保持原行为。
- **测试：** `WindowIconCompatTest` 覆盖单图标、多图标、不同 Android 后端、
  桌面路径和空图标列表。
- **运行时集成：** `StsDesktopJarPatcher` 的注入白名单及
  `REQUIRED_STS_PATCH_CLASSES` 必须包含 `WindowIconCompat.class`，否则仅更新
  `LwjglGraphics` 会导致 MTSClassLoader 在启动时抛出 `NoClassDefFoundError`。
  `StsDesktopJarPatcherTest` 校验该依赖的注入和必需类登记。

此修复不处理 LWJGL Java/native 版本不兼容警告，也不保证解决其他原因导致的黑屏或闪退。

## 通话中断后的音频断流检测

- **症状：** 电话或微信语音结束后返回游戏，只有窗口重新获焦，没有 Activity
  恢复或输出设备增删事件，游戏音频流断开后一直无声；恢复成功后迟发断流也会漏检。
- **修复：** `LwjglApplication.processQueuedAudioCommands()` 在持有 OpenAL context
  的游戏线程执行启动器请求。`nativeHasQueuedAudioCommands()` 同时检查命令队列和
  合并后的健康检查标记，空队列不再阻止前台每秒检查及获焦后的即时检查。
  `GameSessionCoordinator` / `ForegroundAudioHealthMonitor` 负责触发和生命周期约束，
  `input_bridge_v3.c` 检查 `ALC_CONNECTED`，断流时建立新批次并有界退避重试。
  路由变化通过单次原子标记同时合并预算续开与必要恢复，避免 health/explicit 两次 JNI
  调用之间游戏线程先完成一次恢复、随后又收到第二次重建。
- **边界：** 正常获焦不重建设备，不强抢系统音频焦点；后台、启动遮罩暂停及退出时
  不进行自动恢复。显式/健康恢复共享每次中断最多 4 次及 250/1000/2000/4000ms
  单调时钟退避；真实输出路由变化、观察到通信结束，或调度器实际停止后的稳定前台恢复
  可续开一次预算，重复获焦/start 回调及周期检查不续开，也不强制重建健康设备。
- **测试：** `scripts.tools.tests.test_native_audio_recovery` 验证空队列、迟发/重复断流、
  健康输出不重建、重试耗尽及后台取消；`ForegroundAudioHealthMonitorTest` 验证调度。
  宿主机 stub 通过不等于真实 Android 通话后的可听输出已验证。

## 通话恢复停滞与 96% 启动阶段诊断（#674）

- **症状：** 通话后无必要的设备 pause/reset 可能停住游戏线程；后续启动的
  `GL context detected` 与首次音频之间缺少日志，96% 不能判定为同一旧进程/旧音频锁。
- **恢复约束：** `GameAudioController` / `AudioOutputRouteBaseline` 将初始设备清单
  作为基线。`ForegroundAudioHealthMonitor` 在非正常音频模式及回到正常模式后的
  1 秒稳定期禁止恢复，代际隔离过期回调。`GameSessionCoordinator` 普通前台/获焦
  只恢复静音状态并探测，不轮询补发显式重建；仅 audio-ready 后的真实路由变化显式恢复。
  `LwjglApplication` 发布 initializing/ready/no-device/disabled-failed 状态，不能把
  Android bridge-ready 当成 OpenAL 已创建。真实断流与 connected-but-paused 的失败设备
  仍由 `input_bridge_v3.c` 共用预算恢复。
- **静音请求可靠性：** 游戏线程长时间停顿时，每秒解除静音曾会塞满 64 格命令队列，
  导致随后后台静音被丢弃。`input_bridge_v3.c` 将静音改为独立原子“最新目标状态”，
  保留未 ready/无 context 时的请求；恢复完成后遵守最新静音目标和后台约束，暂停在
  OpenAL 操作中途到达时，所有者返回后仍会应用它。恢复命令继续使用原有有界队列。
- **路由事件保留：** `AudioOutputRouteBaseline` / `GameAudioController` 保留被暂停取消的
  防抖事件；`ForegroundAudioHealthMonitor` 在遮罩、后台和 bridge 未 ready 时保留待办，
  由 `GameSessionCoordinator` 确认已交给 native 后才清除。`input_bridge_v3.c` 接收事件与
  执行门控分离，在连接查询前转交为游戏线程的恢复责任；暂停、查询错误或退避不会丢失
  路由，也不会因重试同一事件反复续开预算。
- **后台通话观察空窗：** 通话可能在调度器停止期间完整发生，返回时已是正常模式。
  `ForegroundAudioHealthMonitor` 对实际 stop/start 的观察空窗同样等待正常模式稳定 1 秒，
  然后仅续开一次预算；未读到模式或再次进入通信会重新计时。路由/已观察的通信结束与
  此次恢复合并处理，重复窗口获焦不绕过稳定期或制造新预算。
- **诊断：** `LwjglGraphics` 记录 GL version/extensions/instances，`LwjglApplication`
  记录 display/VSync/frame override/audio constructor/initial commands begin/end。
  `RuntimeStageDiagnostics` 独立守护线程在同一阶段持续 5 秒时仅报告一次，含 JVM PID
  和会话 UUID、执行线程及 Java 栈；native 的独立原子序号/阶段快照细分 gain、context、
  device、error、pause、reset、resume、process、connected 和健康查询。观察线程只读
  快照，不查询 `ALC_CONNECTED`、不进恢复锁、不取消/销毁/并发重置设备。Java JNI 栈
  不冒充 native 完整栈；整个首次 audio constructor 打点不冒充已细分原生 open/create。
- **注入：** `StsDesktopJarPatcher` 与必需类清单包含 `RuntimeStageDiagnostics` 及嵌套类，
  保证实际游戏 jar 路径携带诊断，而不只在源码/测试中存在。
- **退出：** `UserExitProcessFallback` 在用户明确返回启动器时承诺原有 1.5 秒终止兜底，
  包括未 ready 与启动遮罩分支；Handler 不依赖已分离的渲染 View，Activity 销毁不撤销。
  正常游戏仍先发关闭请求，保留原保存机会；不是音频停滞自动杀进程。
  `LauncherActivity` debug 直启在旧进程清理失败后停止，不继续覆盖启动。
- **验证边界：** Kotlin/JVM、实际 C helpers 宿主 stub 与本地 Android 编译不证明原华为
  电话/驱动故障已解决。本轮不换 `.so`、不删档/设置、不切换音频后端、不自动重启或安装。
