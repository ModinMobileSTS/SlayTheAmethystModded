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
