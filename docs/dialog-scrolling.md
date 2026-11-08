# Launcher dialog scrolling

`LauncherAlertDialog` scrolls its icon, heading and ordinary body together by default.
Its confirm/dismiss actions are outside that weighted viewport, so a short landscape
window, large font or software keyboard cannot push them below an oversized body.
Short content still wraps to its natural height.

This covers settings choices/dropdowns, boot style and theme selectors, floating-tool
switches, status/log text, workshop dependencies and download details, mod priority and
suggestions, model-test results, input forms and variable-length error notices.

## Caller-owned scroll containers

Pass `scrollableText = false` if the body already owns a `LazyColumn` or
`verticalScroll` viewport, including one inside a helper composable. In that mode
the heading stays fixed and the body receives the finite height remaining above
the actions. Do not place an unbounded lazy list in the default scrolling body:
vertical scroll containers measure their content with infinite height.

Existing mod configuration, mod import wizard, cloud progress, release history,
workshop change notes, image/animation pickers and other bounded lists explicitly
use the caller-owned mode. `SaveImportConfirmationDialog` uses Material's own
`AlertDialog` and is independent of this wrapper.

For custom `LauncherDialog` cards, a scrolling list above footer buttons needs
`Modifier.weight(1f, fill = false)` in the surrounding `Column`; a fixed
`heightIn(max = ...)` alone does not reserve space for the footer. Mod actions and
local AI-patch chooser/actions follow this pattern. AI-patch installation/upload
use `LauncherAlertDialog`, with form/progress/result content in the scrolling body
and close/upload/back actions in the fixed action slots.

## Regression tests

`LauncherDialogScrollInstrumentedTest` constrains the dialog surface itself to
200–240 dp, rather than constraining the underlying activity (dialogs have their
own windows). It covers long bodies, large headings/fonts, caller-owned lazy and
ordinary lists, keyboard-sized input forms, both settings choice components, and
the boot-style preview grid. It verifies the last content is reachable and footer
actions remain visible/clickable.

Compile without a device:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

Run on a connected test device:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.stamethyst.ui.LauncherDialogScrollInstrumentedTest
```

Also manually check actual landscape rotation and the device IME, especially AI
patch upload/rename and room-password/kick-message forms. The small-height fixture
does not emulate a particular system keyboard or display inset configuration.
