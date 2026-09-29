# 游戏页图形渲染卡片

打开 `index.html` 即可使用。React、图标、启动器图片和样式均内嵌，支持离线打开。

## 设计依据

- 基于 `opendesign/design-systems/stamethyst-launcher/`，颜色采用 `LauncherTheme.kt` 的无色主题 Material 3 语义色。
- 对照 `MainScreen.kt` 的 `LauncherGamePage`、`SteamCloudOverviewCard` 和 `EasyTierOverviewCard`，沿用 16px 页边距及卡片间距、圆角图标块、低阴影和浅描边。新卡片位于游戏概览之后。
- 切换区是一个完整按钮，共享外边框与圆角，中间无间隙。约 12 度的斜切分界区分两种后端；选中区域与未选中区域平均宽度约为 1.65:1，确认弹窗后以 360ms 移动斜线并交换占比。固定内容高度 164px，卡片不会因切换而上下跳动。说明文字以 260ms 更新，减少动态效果设置会禁用动画。
- MobileGlues 的叠层图标与 GLES2 的芯片图标作为低对比背景置于按钮内，局部裁切并随选中状态轻微旋转放大。前景保留名称、说明和选中标记，未选中 MobileGlues 在单词边界分为两行，适配窄屏。原生选项沿用页面现有绿色语义色。
- 游戏概览和其他模块是用于比较视觉风格的精简上下文。模组名称、大小、云状态为演示数据；这些模块提供局部弹窗，不模拟完整导航。启动游戏按钮仅查看当前原型配置。
- 为避免启动操作遮住内容，预览中的启动按钮放在内容外的底部操作区。正式落地时可沿用现有悬浮按钮及内容避让逻辑。

## 状态和交互

- 初始为自动选择 MobileGlues，假设设备包含可用的 MobileGlues 库。
- 点击整体按钮任意位置，或聚焦后按 Enter / Space，弹出切换至另一后端的确认框。取消、Esc、点击遮罩均不保存，关闭后恢复整体按钮焦点。
- 确认才切换为手动模式，更新勾选、占比、描述和成功反馈。辅助技术将整体按钮读取为当前后端及切换目标，内部区域不产生额外焦点。
- 原生 GLES2 对应 `RendererBackend.OPENGL_ES2_NATIVE`，不是 GL4ES。
- 自动选择采用 `RendererBackendResolver` 的优先顺序。手动选择会关闭自动模式；恢复按钮可以重新开启自动选择。
- `GameSessionConfig` 在游戏会话启动时读取配置，因此文案为“下次启动游戏时生效”。这是选择状态，不声称当前运行中的游戏已经更换后端。
- 主题、渲染器和选择模式仅存入独立的浏览器 localStorage，不修改 Android 配置。原型未连接真实的设备可用性检测。
- 所有交互目标至少 48px，支持键盘、原生模态焦点约束、选中语义、状态播报和 320px 小屏幕。

## 修改与构建

源码为 `prototype.jsx` 与 `prototype.css`。第三方构建依赖放在仓库临时目录中。

```sh
npm install --prefix agent-tmp/renderer-card-tools --no-audit --no-fund react@19.1.1 react-dom@19.1.1 lucide-react@0.468.0 esbuild@0.25.9
node opendesign/mockups/renderer-switch/build.mjs
```

`build.mjs` 生成完整的单文件 HTML。图标由 Lucide 提供；启动器图片引用仓库现有资源，并在构建时内嵌。
