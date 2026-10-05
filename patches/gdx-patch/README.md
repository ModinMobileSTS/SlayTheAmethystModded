# gdx-patch

启动器使用的 libGDX 兼容层补丁。

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
- **边界：** 正常获焦不重建设备，不强抢系统音频焦点；后台、启动遮罩暂停及退出时
  不进行自动恢复。健康检查最多自动尝试 4 次，后续前台/路由/音频模式事件可续开预算。
- **测试：** `scripts.tools.tests.test_native_audio_recovery` 验证空队列、迟发/重复断流、
  健康输出不重建、重试耗尽及后台取消；`ForegroundAudioHealthMonitorTest` 验证调度。
  宿主机 stub 通过不等于真实 Android 通话后的可听输出已验证。
