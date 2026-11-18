# “更大的 UI”与桌面模组的兼容性

**优先检查自定义模式的多角色换行、地图选点和战斗选敌。** 这三处不只是图片大小不同：第一处能算出 BaseMod 的布局偏移；后两处改变实际可点击区域。只添加卡牌、沿用原版绘制和命中框的模组，风险比自行定位按钮、叠画图标或替换原版界面的模组低。

检查对象：仓库 `build-deps/desktop.jar`（V2.3.4，2022-12-18）、随包 BaseMod 5.56.0、StSLib 2.12.0，以及当前启动器的 MTS 兼容补丁。以下区分离线核实的布局变化和需要实机复现的玩家症状。

## 先区分两种“移动端 UI”

[启动配置](../app/src/main/java/io/stamethyst/config/LauncherConfig.kt)默认关闭“更大的 UI”。打开后，[启动参数](../app/src/main/java/io/stamethyst/backend/launch/StsLaunchSpec.kt)把 `amethyst.ui_scale` 从 `1.00` 改为 `1.05`；这个值用来**选择移动布局分支**，不会把 `Settings.scale` 统一乘以 1.05。旧的原生 HUD 读取函数固定返回 `false`。字体比例、`Settings.isTouchScreen` 和出牌手势都有各自的设置。

[布局补丁](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/ui/UiScaleCompatPatches.java)扫描原版和已加载模组，把符合条件的 `Settings.isMobile` 直接读取改成 `原值 || 开启大 UI`。有两个影响：

- **同一个类里会同时存在桌面和移动尺寸。** [改写器](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/ui/MobileUiLayoutClassPatcher.java)遍历构造函数和方法，未遍历静态初始化器 `<clinit>`。例如地图节点的构造和绘制走移动分支，列距和节点随机偏移量仍在类初始化时取桌面值。这与把原版全局 `Settings.isMobile` 置为 `true` 的结果不同。
- **模组没读标志也可能受影响；读了也不代表适配。** 前者可能调用已改变布局的原版方法，再用桌面常量添加按钮；后者可能把 `isMobile` 当平台标志，跳过桌面控件，不只是放大它。反射读原字段以及静态初始化时缓存的值也可能与方法中的直接读取相反。旧 HUD 设置文案提到的 Loadout 控制台消失，不能据此认定在当前开关下必现。

## 优先排查的界面

| 场景 | 核实的差异 | 对桌面模组的影响 |
| --- | --- | --- |
| **自定义模式，角色数超过 10 个** | `CustomModeScreen.updateCharacterButtons` 的列距从 `100 × xScale` 变成 `130 × xScale`，按钮命中框从 80 方形变成 120 方形（乘 `scale`）。BaseMod 的 `PositionCharacterButtons` 仍每 10 个向左回退 `1000 × scale`、向下移 `100 × scale`。 | **已离线核实换行偏移。** 以 `xScale = scale = 1` 计算，大 UI 第 1、11、21 个按钮的中心 X 分别是 **370、670、970**，桌面三者都是 **430**。大 UI 每行起点多向右移 300；行数多时末列可能出屏，邻行点击框也可能重叠。这是“自定义模式”，不是普通开局的角色翻页。 |
| **地图、额外房间和路线标记** | `MapRoomNode` 普通节点图、轮廓和已走圆圈的绘制倍率翻倍；方形命中框边长从 `64` 变为 `114 × scale`（两值均随 `scale` 缩放）。当前开关下节点中心仍按桌面列距和随机偏移排列，`MapEdge` 也保留桌面间距；图例字图放大，图例行距仍为桌面值。 | 靠得近的可达节点，命中区域可能相交；模组的小标记、文字或额外图例可能被大图盖住。**并非所有连线都会错位**：原版节点和边仍共用桌面几何。用节点实际 `hb`（命中框）定位，别按整张地图放大坐标。带火精英火焰已有[专门补丁](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/ui/BurningEliteFlameCompatPatches.java)，不会自动放大第三方特效。 |
| **战斗选敌、意图和能力图标** | `AbstractPlayer.initializeClass`、`AbstractMonster` 构造时把本体命中框宽度乘 **1.17**，高度不变，初始血条命中框也变宽。怪物意图绘制乘 **1.2**，`intentHb` 本身没有同比扩大。`AbstractMonster.updateHitbox` 重建本体框时又不乘 1.17；原版异鸟、守护者的 `changeState` 就调用它。 | 密集站位或多部件 Boss 可能悬浮/选到邻敌；模组缓存的目标区域可能在变身后失准。重画意图的附加数字也可能压到图标。能力列表另有混排：图集 `region48` 绘制乘 1.17、独立 `img` 路径不变，层数文字下移，而静态横向间距仍是桌面的 48。 |
| **普通选角、进阶和初始遗物** | 原版头像行 Y 从 `190` 移到 `230 × yScale`，进阶框和箭头 Y 从 `70` 移到 `100 × scale`（各组均使用对应缩放）。安装自定义角色时，BaseMod 换成 `CustomCharacterSelectScreen`，初始化和翻页会把头像、翻页箭头放回自己的桌面行；进阶和角色说明仍用原版移动布局。 | 模组加的皮肤、难度、配置按钮可能挡住原版控件。`CharacterOption.renderRelics` 的**单遗物**移动分支另画名称和说明，绕过 BaseMod 原先只插在桌面说明上的关键词处理；当前已有[关键词兼容补丁](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/ui/CharacterOptionRelicKeywordCompatPatches.java)。多遗物走另一条循环，不应套用单遗物坐标。 |
| **顶栏、药水和遗物行** | `TopPanel` 把角色称号移到玩家名下方，重算生命、金币、药水等横坐标；`Settings.initializeDisplay` 把药水槽宽度从 56 改为 64、中心从屏顶下 30 移到 42（乘 `scale`）。但顶栏背景高度仍是桌面 `128 × scale`，遗物/灾厄的 `START_Y` 也保留桌面值。 | 额外药水槽、货币、长角色名、遗物行附加按钮等，若沿用固定坐标，可能与原版字图或其他点击区域重叠。沿用当前药水/遗物实例位置的叠加图标，不应再整体缩放一次。 |
| **确认弹窗、设置和字体** | `ConfirmPopup` 背景和按钮图乘 1.5，“是/否”命中框从 `160×70` 变为 `240×110` 并向两侧和下方移动；`OptionsPanel.render` 移动语言下拉框。`FontHelper.initialize` 请求的生命/格挡/按钮/能力层数字号分别从 `22/24/32/16` 变为 `29/30/37/20`，即使启动器字体比例没变也有差异。 | 模组只替换按钮外观、沿用桌面点击区时，会出现“字在这里、要点旁边”；反过来只继承原版绘制和更新则两者一起移动。固定宽度的弹窗、长译名和层数文字优先检查裁切。 |

**边界：** 原版全局移动 HUD 会让 `MainMenuScreen.setMainMenuButtons` 不创建退出和补丁说明按钮。但当前大 UI 改写明确排除 `MainMenuScreen`、`MenuButton` 和六个对话气泡/文字特效类；主菜单另有受触屏模式控制的[局部补丁](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/touch/MainMenuTouchLayoutCompatPatches.java)。事件正文的 `GenericEventDialog.DIALOG_MSG_Y_TEXT` 也只在 `<clinit>` 取值。商店购买、营火确认、奖励选牌改为二次确认，不是本开关直接引入的行为。

## 怎么验证和处理

- **玩家排查：** 保持同一模组列表、存档、分辨率、字体比例和输入模式，只切换“更大的 UI”，每次重启游戏。优先比较自定义模式第 11/21 个角色、地图相邻可达节点、密集敌人及变身前后；顶栏和弹窗同时检查图像位置与实际能点中的位置。
- **启动器维护：** 建议保留默认关闭。最明确的修复点是 BaseMod 自定义模式换行：让换行量和行高使用实际列距、命中框尺寸。若继续收窄兼容风险，优先按界面成组核对绘制、命中框和静态尺寸，再决定哪些原版读取走移动分支；不要把所有模组的平台判断或所有静态初始化一并切到移动端。
- **证据口径：** 筛出 `desktop.jar` 中 `com/megacrit/cardcrawl` 下引用 `Settings.isMobile` 的类，逐方法用 `javap -p -c` 核对，共 **30 类、97 读、1 写**。将原始候选类交给当前 `MobileUiLayoutClassPatcher.patchJar` 离线运行得到 **18 类、64 处读取被包装、0 类失败**；另 18 读在未排除类的静态初始化器中，15 读在显式排除类中。这个数是干净原版的基线，不是每个整合包的实际日志。随包 BaseMod、StSLib 未发现直接读这个字段；上面的换行结果由实际 BaseMod `alterX/alterY` 结合原版入参离线验证。未做真机整包 A/B，遮挡、误选与否取决于具体模组和场景。
