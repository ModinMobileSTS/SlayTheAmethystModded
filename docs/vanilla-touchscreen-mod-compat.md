# 桌面原版开启触屏后，哪些模组行为会变

检查对象是仓库的 `build-deps/desktop.jar`（`[V2.3.4] (12-18-2022)`），以及随启动器提供的 BaseMod 5.56.0、StSLib 2.12.0。下文的“全局触屏”指运行时 `Settings.isTouchScreen == true`，不是 `Settings.isMobile`、结束回合长按或 Android 的触摸转鼠标。结论来自字节码与启动器补丁的静态对照；没有把推测的玩家症状当作真机复现。

**对桌面模组，建议保留混合模式作为默认值。** 最大的风险是原版把“提交操作”从被点击的对象移到了另一个界面的确认按钮。模组即使一次都没读过触屏标志，也可能因此收不到回调，或等不到当前界面完成。只添加卡牌、遗物数据，并完整沿用原版界面和结算回调的模组，不会仅因为多一步确认就失效。

## 五条需要优先检查的调用路径

| 场景 | 桌面 → 全局触屏 | 对桌面模组的具体影响 |
| --- | --- | --- |
| 营火 | 桌面在 `AbstractCampfireOption.update` 内调用 `useOption()` 并写 `somethingSelected`。触屏第一次点选只设 `touchOption`；由 `CampfireUI.updateTouchscreen` 在确认后调用 `useOption()`，随后写 `somethingSelected = true`。 | **能从两份 jar 确认的回调缺口**：BaseMod 的 `PostCampfireHook` 只改写前一个方法里的字段写入，用 `publishPostCampfire()` 的返回值决定是否结束。触屏确认绕开这处补丁。需要返回 `false` 以继续使用营火、或在二级页面取消后返回的模组，会失去这次阻止营火隐藏的机会。 |
| 遗物、灾厄拾取 | 未取得的对象走 `AbstractRelic.update` / `AbstractBlight.update` 点击分支时，桌面调用 `bossObtainLogic()`；触屏改写全局 `bossRelicScreen.touchRelic` / `touchBlight`，由 Boss 奖励界面确认。写入前并不要求对象属于该界面的候选列表。 | 模组房间若直接展示可点击的遗物、却没有运行 Boss 奖励界面，就可能点中而领不到。影响的是走这条点击分支的对象；直接调用 `instantObtain()` 的奖励不经过它。 |
| 商店 | 原版卡、遗物、药水和删牌服务从直接购买变为先选中，再由 `ShopScreen.update` 确认。尤其是 `StoreRelic.update` / `StorePotion.update`：它们悬浮时操作商品自己的 `this.shopScreen`，触屏待购项和确认按钮却写进 **`AbstractDungeon.shopScreen`**。 | 模组若把原版商品放进独立商店实例，当前页可能有悬浮反馈，却没有可用的购买确认。另一个风险是模组在第一次点击就扣自定义货币或发购买回调；此时原版尚未成交，玩家还可以取消。 |
| 战斗奖励、发现、抉择选牌 | 桌面在 `CardRewardScreen.cardSelectUpdate` 当场结算；触屏只设 `touchCard`，由 `CardRewardScreen.update` 的确认分支写 `discoveryCard` / `codexCard`、调用 `onChoseThisOption()` 或领取卡牌。点空白松手可撤销待选。 | 只补丁桌面结算位置，或第一次点击就读取结果、关闭界面的模组，可能漏发选择回调或读到空结果。等待最终结果的模组仍可工作。此结论针对 `CardRewardScreen`，不应推广成所有网格选牌都要确认。 |
| 战斗出牌、自定义目标 | `AbstractPlayer.updateInput` 改变出牌区域判定；`clickAndDragCards` 把拖动中的牌画在触点上方，并改变松手分支。短拖在松手帧刚进入出牌区时，触屏分支可能以 `canUse(player, null)` 检查后直接 `playCard()`；对于指向敌人的牌，入队目标取自 `hoveredMonster`。`updateSingleTargetInput` 在某些无按压帧还会主动移动光标。 | 按桌面拖拽状态或牌面位置判断目标的模组可能漏选、误出，或在目标尚未设置时收到空目标。应分别测正常拖动、快速短拖、对空白松手。StSLib 的 `CustomTargeting` 本身也有读 `isTouchScreen` 并移动光标的代码，所以不能假设所有依赖库都忽略了触屏。 |

## 其他可见变化与排查边界

- **坐标与悬浮：** `InputHelper.updateFirst` 在桌面路径裁剪鼠标坐标，在触屏路径给 X/Y 分别加 `VERT_LETTERBOX_AMT` / `HORIZ_LETTERBOX_AMT`。模组用原始 `Gdx.input` 画控件、用 `InputHelper` 做命中测试时，有黑边才容易看出偏移。`moveCursorToNeutralPosition()` 在触屏且非手柄时会移走光标；手牌选择、姿态药水和禁用结束回合按钮等路径也会调用它。
- **提示和布局：** `AbstractCard.renderCardTip`、`TipHelper.render` 在拖牌、进入出牌区时采用不同的提示显隐条件；`EndTurnButton.render` 不画桌面的悬浮说明，`GameCursor.render` 从普通/检查指针变为渐隐触点。`MenuButton` 的按钮高度和行距都变为 `2 × SPACE_Y`；额外主菜单项按桌面间距排布时容易重叠或出屏。存档名和制作人员页面的操作提示也会变。
- **输入模式切换：** `CInputHelper`、`CInputListener`、`SteamInputDetect` 进入手柄模式时有写 `isTouchScreen = false` 的路径；桌面版 `InputHelper.leaveControllerMode` 按 `TOUCHSCREEN_ENABLED` 恢复。不要把某一帧的值当作设备的永久属性。
- **不要误归因：** 普通 `Hitbox.update`、地图、事件按钮、网格选牌没有直接读取这个标志；它们仍可能受共享坐标和模组补丁间接影响。`Settings.USE_LONG_PRESS` 单独控制结束回合长按；开启触屏也不会自动为模组补出右键、滚轮或键盘操作。

## 对当前启动器的意义

[启动参数](../app/src/main/java/io/stamethyst/backend/launch/StsLaunchSpec.kt)仅在 `MOBILE` 下暴露全局 `true`。默认 `HYBRID` 让普通模组代码读到 `false`，再通过[触屏白名单](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/touch/NativeTouchscreenAllowlistPatches.java)恢复原版商店、选牌和战斗等操作。遗物/灾厄另有[房间、界面和候选对象检查](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/core/CompatRuntimeState.java)；[营火确认](../mods/amethyst-runtime-compat/src/main/java/io/stamethyst/compatmod/touch/CampfireDesktopTouchConfirmPatches.java)在确认后重走桌面选项的 `update`，保留 BaseMod 的回调位置。

白名单按**方法**生效。模组代码即使读到 `false`，调用白名单里的原版方法仍会得到触屏行为。最值得补测的是第三方商店复用 `StoreRelic` / `StorePotion`、自定义奖励页复用 `CardRewardScreen`，以及 StSLib 的自定义目标牌。对同一模组、同一场景固定出牌优化和 HUD 设置，分桌面、混合、全局触屏三种输入模式比较。商店验证“取消不扣费、确认只成交一次”；奖励验证“取消不产出、确认回调一次”；营火验证“二级页面取消后仍能使用营火”。

**核查口径：** 在 `desktop.jar` 的 `com/megacrit/cardcrawl` 包下筛出含 `isTouchScreen` 的 class，再用 `javap -p -c` 核对实际字段访问：22 个类直接读写该字段（36 条 `getstatic`、12 条 `putstatic`，包括设置、初始化和手柄切换）。调用触屏辅助函数、共享界面状态或模组反射访问的影响不在这个计数内。
