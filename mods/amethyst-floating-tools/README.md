# Amethyst Floating Tools

This built-in mod replaces the Android-side floating mouse window during ModTheSpire launches with an in-game right-side collapsible tool ring drawn by the game JVM.

## Included fixes and tools

1. `FloatingToolPanel.configureOptionalActions`

   Reads the launcher-provided `amethyst.floating_tools.buttons` list and renders Ctrl, Shift, Tab, Alt, input lock, and virtual wheel only when their individual setting is enabled. Online, left/right mouse mode, keyboard, and Add key remain visible by default. This addresses the default drawer exposing too many advanced controls to new players. Rendering is implemented by `FloatingToolPanel`, which is driven by the `AmethystFloatingTools` subscriber; its input/update entry patch is `FloatingToolInputConsumePatches.ConsumeAfterInputUpdate`.

2. `FloatingToolPanel`

   Draws a right-side tool ring with a breathing edge orb and two arcs of relic-style icon buttons. The inner arc holds core tools and the outer arc holds enabled optional actions. The orb is the only expand/collapse control and has no direction glyph. The left/right mouse mode, Add key, keyboard, and virtual-LAN buttons provide focused hover feedback through a small scale increase, green outline/icon tint, one hover sound, and directly rendered tooltips reading "切换鼠标左右键", "新增按键", "打开键盘", and "打开虚拟局域网菜单". The tooltip is drawn by `FloatingToolPanel.renderHoverTooltip` with `FontHelper`, rather than the disabled `TipHelper` queue. The remaining tools stay visually stable and do not show tooltips on hover. Pressing an icon smoothly enlarges it, and active toggle icons share one selected treatment. This addresses the Android overlay looking detached from the game, the previous drawer becoming visually noisy, and the four primary actions lacking pointer affordance. Rendering is implemented by `FloatingToolPanel` and registered by the `AmethystFloatingTools` subscriber; input interception is implemented by the `FloatingToolInputConsumePatches.ConsumeAfterInputUpdate` patch class.

3. `FloatingToolPanel.activate` and `FloatingToolInputBridge`

   The online icon opens the launcher's virtual-LAN room UI, the keyboard icon delegates to the existing Android keyboard selector, and Add key opens the Android custom floating-key picker. The keyboard selector respects the launcher's built-in soft-keyboard setting in both legacy floating-window and in-game tool modes. This addresses having to leave the game for LAN controls and avoids reimplementing launcher-owned Android UI in the game JVM. The request path is implemented by `FloatingToolPanel` and `FloatingToolInputBridge`, and the game-input interception entry patch is `FloatingToolInputConsumePatches.ConsumeAfterInputUpdate`.

4. `FloatingToolPanel.transformLeftClickToRightClick`

   Converts the touch-generated left-button stream into a held GLFW right-button stream until release. The launcher-provided `amethyst.floating_tools.auto_switch_left_after_right_click` setting now decides whether one completed right-click action returns the drawer to left-click mode or leaves right-click mode selected. This addresses right-button dragging for mods such as Slay the Perk Tree and the symptom where the launcher's auto-switch setting previously had no effect in the in-game drawer. The behavior is implemented by `FloatingToolPanel.transformLeftClickToRightClick` and `FloatingToolPanel.releaseRightSurface`, invoked after `InputHelper.updateFirst` by `FloatingToolInputConsumePatches.ConsumeAfterInputUpdate`.

5. `FloatingToolWheel`

   Implements the virtual scroll wheel. Holding the upper or lower half repeatedly dispatches scroll ticks with a dead zone around the center. This addresses the old overlay's repeated wheel gesture behavior. Tick dispatch is implemented by `FloatingToolWheel`; the input/update entry patch is `FloatingToolInputConsumePatches.ConsumeAfterInputUpdate`.

6. `FloatingToolInputConsumePatches`

   Consumes clicks that hit the floating tools UI before the base game sees them, implements lock mode by swallowing game clicks while still allowing cursor movement, and emulates right-click touch mode by replacing the Android left-click stream with right-button events. This addresses clicks passing through the tool ring to underlying cards, buttons, or screens. The patch class is `FloatingToolInputConsumePatches`.

## Icon assets

The ten drawer glyphs are original work for this mod and are generated from vector primitives by `tools/generate_icons.py`. The matching asset declaration is included in `amethystFloatingTools/images/tools/ATTRIBUTION.txt`.
