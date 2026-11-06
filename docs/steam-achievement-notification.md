# Steam 桌面版成就通知复刻

## 参照版本与资料

本实现对齐 **Steam 桌面版默认右下角成就通知**，不是 Steam Deck / Big Picture，也不是旧版 VGUI 通知。

2026-11-04 调研：

- [Steam Achievement Notifier](https://github.com/SteamAchievementNotifier/SteamAchievementNotifier)：检查了 `notify/presets/steamdeck/styles.css`。该项目允许自行定制布局、音效和过渡，不能当成桌面 Steam 的逐像素标准；没有拷贝它的实现或默认音频。
- [SteamTracking 原版客户端代码](https://github.com/SteamDatabase/SteamTracking/blob/9ef4b7907e0c6f9f4bcdb24d32c43c02afd7a381/ClientExtracted/steamui/sp.js) 和同版本 `css/sp.css`：以客户端实际的布局、运动算法、音效路由和稀有成就特效为依据。
- 同时检查了 [Valve 稳定版更新清单](https://client-update.akamai.steamstatic.com/steam_client_ubuntu12)，版本 `1792618285`，并对下载包校验清单中的 SHA-256。调研文件、完整客户端包均在 `agent-tmp/steam-achievement-reference/`，不提交。

关键模块（数字是上述 SteamTracking webpack module id）：

- `7474`：默认停留 `5 * 1000ms`，成就使用 `ToastAchievement` 音效。
- `15148`：桌面窗口宽 283px，进入 300ms、停留 5000ms、退出 300ms；右下角逐帧布局。
- `84572`：283 × 70px 容器、椭圆径向背景渐变、5% 透明度 Steam 水印。
- `59906` / `75883` / `48248`：10px 内边距、44px 图标、12 + 4px 文字间距、12px / 16px 行高，标题与说明共用三行预算。没有额外的“成就已解锁”蓝色标题。
- `71293` / `3270` / `33175`：全球完成率 **小于 10%** 才显示稀有金色光晕；两张不同的原始 PNG 遮罩、18 秒反向旋转、6 秒正向旋转、原始锥形渐变色标和阴影。
- `5757` / `7289`：桌面版使用 `/sounds/desktop_toast_default.wav`；Steam Deck 使用另一段 `deck_ui_achievement_toast.wav`，不得混用。

## 实现

- `InGameAchievementOverlayController` 保留事件去重和排队，并按原版最多三条叠放，不修改 Steam 成就同步、游戏存储或成就解锁逻辑。
- `achievement/SteamAchievementNotificationView` 使用 **283 × 210dp 的本地 Chromium WebView** 承载最多三张 283 × 70px 通知，而非占满屏幕的浏览器；沿用原版 CSS 排版、Motiva Sans regular/light、双遮罩与 conic-gradient。这样不必用 Compose 弹簧、Canvas 光圈等近似替代 Steam 特效。
- `notification.js` 移植右下角窗口栈算法，包括 `ceil` / `floor` 像素取整；`SteamAchievementNotificationSpec` 保存单条参考公式供 JVM 测试。原版单条窗口实际移动两倍窗口高度，不能简单改成一次高度的 slideIn；没有额外淡入、缩放或回弹。多条通知分别计时，前一条退出时其余通知按栈高度移动，前三条满时后续排队；水印区分顶部、中间、底部位置。
- 字体与图片解码完成后才启动时间线及原始 WAV。SoundPool 提前异步加载，在 `onDestroy` 才释放，不会像旧版 `ToneGenerator.startTone(); release()` 那样截断。同原版 `GamepadUIAudio` 使用 50ms 音频去重窗口，避免同时解锁三条时将同一音频叠加放大。
- WebView 只接受固定虚拟 HTTPS 源和明确白名单的打包资源；禁用网络、文件和 content 访问，使用严格 CSP。文本通过 `textContent` 写入，成就图片作为本地 data URI；没有远程 HTML 或页面跳转。
- `dispatchTouchEvent` 返回 false，游戏的触摸和手势能从通知区域穿透。方向和渲染宿主变化时，未完成的成就重新入队。

### 稀有度快照

`assets/steam-achievement/global-percentages.json` 是 2026-11-04 从 Valve 的免登录 `GetGlobalAchievementPercentagesForApp`（appid 646570）取得的全量 46 项快照。它只决定是否显示光晕，不决定成就是否解锁。全套资源离线可用，不在战斗中发网络请求。

当前稀有成就：`donut`、`ascend_20`、`speed_climber`、`neon`、`focused`、`minimalist`、`common_sense`、`one_relic`、`eternal_one`。以后若全球完成率跨过 10% 阈值，更新此快照；不要凭名称或个人完成率判定稀有度。

## 原始资源来源与校验

音频来自 Valve 官方包：

`https://client-update.akamai.steamstatic.com/steamui_websrc_sounds_all.zip.74727ca40ef88c5012bb631488fd285418dce825`

包内 `steamui/sounds/desktop_toast_default.wav` → `res/raw/steam_desktop_toast.wav`，不转码；44.1kHz、双声道、16-bit PCM，时长约 0.6342 秒。

字体来自 Valve 官方静态资源：

- `https://community.fastly.steamstatic.com/public/shared/fonts/MotivaSans-Regular.ttf?v=4.0`
- `https://community.fastly.steamstatic.com/public/shared/fonts/MotivaSans-Light.ttf?v=4.0`

遮罩来自上述 `sp.css` 中 `33175` 对应规则的 PNG data URI；SVG 是 `69385` 的 `SteamLogo` 原始路径。它们不是重新绘制或合成的素材。

SHA-256（单元测试固定校验）：

```text
steam_desktop_toast.wav  c03fc4796f78001b8d9e279c053d859b6e68ca97bdcb4bc657e45af3d17a3ef7
motiva-sans-regular.ttf  19b8db163bcc51732457efa40911b4a422f297ff3cd566467d87eab93cef0c14
motiva-sans-light.ttf    6b7a3177485c193a2e80be6269b6b12880e695a8b4349f49fccf87f9205badcc
glow-mask-outer.png      fdceb73b3e83404318667bd87a6f1a8f3c900fadf4ff8c3178a84bf9e59ecf40
glow-mask-inner.png      f26d07d9b1d86c46a7185943d927eaf239040ab0d343c8b0165f065da59a8560
```

Steam 标识、客户端素材及 Motiva Sans 的权利属于各自权利人；公开可下载不等于获得再分发许可，也不因放在本仓库就适用本仓库代码许可证。发布方须确认这些素材的分发授权；本文记录来源，不作授权声明。

## 验证与不能声称的保证

- JVM 测试：默认时长、入退场公式、不同密度高度下的单调性/无回弹、稀有度阈值、46 项快照覆盖、原始二进制 SHA-256。
- Android instrumentation：离线 HTML/字体加载、283 × 70 CSS 像素、稀有遮罩节点、图片解码、触摸穿透。
- Chromium 可重复测试：安装 Playwright 及 Chromium 后运行 `python3 scripts/tests/steam_achievement/main.py`。通过可控帧时钟检查单条 0/150/300/5300/5450/5600ms 位置、三条叠放、普通/稀有光晕、长中文三行预算、独立计时及页面错误；截图写入 `agent-tmp/steam-achievement-reference/three-toasts.png`。
- 对照须同时检查普通和稀有成就、中文长文本、连续解锁、横竖屏宿主重建、声音关闭及不同 Android WebView 版本。

2026-11-04 本地验证结果：7 项 JVM 测试通过，`:app:compileDebugAndroidTestKotlin` 通过，Chromium 156 的可控帧回归通过。另在同一 Chromium、同一原始字体和图片下，将普通/稀有通知的固定帧与 SteamTracking 完整原版 CSS 规则及原始类名 DOM 对照：各有 2 个像素的单通道最大差值 1（8-bit）；该结果只是浏览器内样式移植的对照，不是 Android 真机对 Steam 客户端的验收。当时 ADB 设备数为 0，Android instrumentation、真实设备录屏和录音未执行。

**不能把它描述成所有设备上已经逐像素/逐采样证明 100% 一致**：Android 和桌面 Chromium 的版本、DPR、中文 fallback 字体、音量混音和调度可能不同。默认 dp 对应 CSS px，不代表手机物理尺寸等于桌面物理尺寸。手机游戏中保持通知触摸穿透，不移植桌面鼠标悬停和点击进入 Steam 社区。没有可用 WebView 时不影响游戏启动，但无法显示该复刻通知。真机录屏/音频仍需与指定 Steam 版本对照验收。
