---
name: stamethyst-launcher
description: Reusable design system imported from the SlayTheAmethyst Android Jetpack Compose launcher. Use for launcher settings, workshop, feedback, loading, and game-adjacent Android surfaces.
---

# STS Amethyst Launcher Design System

## Source of truth

This system is imported from the Android Compose implementation in the SlayTheAmethyst launcher. The implementation uses Material 3 as its component foundation and computes a themed light or dark `ColorScheme` from a persisted seed color.

The canonical source files and their responsibilities are listed in `README.md`. Do not invent new visual primitives when an equivalent Material 3 or documented launcher primitive exists.

## Visual direction

- Functional Android launcher UI with a calm, technical, game-adjacent tone.
- Material 3 surfaces with restrained elevation and rounded geometry.
- Light and dark modes are first-class. `FOLLOW_SYSTEM` is the default mode behavior.
- Theme color is user-selectable. The selected seed subtly tints primary, secondary, tertiary, surfaces, and outlines.
- Use frosted glass only for persistent chrome such as the floating header. It uses Haze blur, translucent surface color, and a low-contrast outline.
- Keep content dense enough for settings and operational workflows. Use clear labels, supporting text, and predictable list actions.

## Color behavior

The five persisted theme seeds are:

- Zhanshige: `#C24A4A`
- Liebao: `#3F8A4B`
- Jibao: `#D2A72E`
- Guanjie: `#6F4C93`
- Colorless: `#2196F3`

For the colorless theme, use the exact Material 3 tonal roles in `tokens/colors_and_type.css`. For the four named themes, derive roles from the selected seed as implemented in `LauncherTheme.kt`:

- Light primary: HSL seed, saturation x `1.06`, lightness x `0.92`.
- Light secondary: hue `-18`, saturation x `0.42`, lightness delta `+0.04`.
- Light tertiary: hue `+24`, saturation x `0.58`, lightness delta `+0.03`.
- Dark primary: blend seed toward white by `0.42`.
- Dark secondary: hue `-18`, saturation x `0.52`, lightness delta `+0.02`, then blend toward white by `0.46`.
- Dark tertiary: hue `+24`, saturation x `0.60`, lightness delta `+0.03`, then blend toward white by `0.40`.

Surface roles are blended with warm-neutral light or dark bases. Content color is chosen from luminance rather than hard-coded per seed.

## Typography

Use the platform Material 3 typography scale with no custom font family. The Compose code consistently uses:

- `headlineMedium` and `headlineSmall` for screen and overlay titles.
- `titleLarge`, `titleMedium`, and `titleSmall` for prominent values, card headings, and compact headings.
- `bodyMedium` for primary supporting content and `bodySmall` for descriptions, metadata, and status text.
- `labelLarge`, `labelMedium`, and `labelSmall` for controls, chips, previews, and compact metadata.
- `FontFamily.Monospace` only for JVM/log output.

Use `FontWeight.SemiBold` for selected or emphasized control labels and `FontWeight.Bold` for boot-overlay titles and high-emphasis headings. Preserve Material 3 default sizes, line heights, and letter spacing unless a source component explicitly overrides weight or alignment.

## Layout and shape

- Page content uses `16.dp` horizontal gutters.
- Settings list content begins below an approximately `104.dp` header reserve and uses `18.dp` top padding.
- Standard vertical list rhythm is `12.dp`; compact component rhythm is `8.dp`.
- Common inner padding is `10.dp` to `14.dp`; large overlay content uses `24.dp` to `28.dp`.
- Category cards use `22.dp` corners; header and icon surfaces use `18.dp` corners.
- Action rows use `12.dp` corners; image previews and selection controls use `6.dp` to `8.dp` corners.
- Pills and progress tracks use a fully rounded `999.dp` radius.
- Icon surfaces are commonly `50.dp` or `52.dp` square with `28.dp` icons.
- Touchable icon buttons are at least `40.dp`; use Android Material minimum touch target behavior for smaller visual icons.

## Components

Use these documented source patterns:

- `SettingsRouteScaffold`: full-screen background, lazy content, pinned floating glass header, status-bar-aware header, and bottom content inset.
- `SettingsCategoryCard`: full-width elevated surface with icon tile, title, subtitle, and trailing navigation affordance.
- `SettingsSectionCard`: surface container with title, divider, and vertically spaced settings content.
- `SettingsActionListItem`: high container list row with title, optional supporting value, trailing navigation marker, 2 dp shadow, and 12 dp clipping.
- `SettingsDangerActionListItem`: error container variant with error-colored title and trailing marker.
- `SwitchSettingRow`: switch first, title beside it, then body-small description below.
- `SettingsRadioOptionRow`: full-width toggleable row with radio control and label.
- `SettingsMetadataChip`: compact secondary-container pill using label-small text.
- `FrostedGlassChrome` and `FloatingGlassHeader`: persistent Haze-backed chrome with 12 dp blur, optional 1 dp outline, and 6 dp shadow for floating headers.
- `AppSearchBar`: docked Material 3 search bar with high surface container, 2 dp shadow, history suggestions, and a trailing text action.
- Boot overlays: progress-driven states, restrained black scrims over imagery, large white status text for image modes, and surface-based Material/log modes.
- Loading skeletons: low-alpha on-surface shimmer with an approximately 1180 ms default sweep and a 900 ms pulse.

Do not turn every section into a card. Cards are for repeated settings groups and category items; page-level structure remains an unframed full-width layout.

## Interaction and motion

- Click, toggle, and icon actions use launcher haptic feedback through `LauncherHaptics`.
- Disabled content follows Material 3 enabled-state treatment and must not trigger haptics.
- Progress changes animate with a `360 ms` tween in settings.
- Crossfades use `260 ms` tweens for boot preview changes.
- Collapsible floating headers use fade plus vertical expand/shrink.
- Log auto-scroll uses a `240 ms` cubic-bezier easing `(0.22, 1, 0.36, 1)`.
- Loading uses a 900 ms pulse and a 1180 ms shimmer loop; workshop skeletons soften their first pass with a 1400 ms sweep.

## Content and iconography

- Use concise, descriptive labels with supporting descriptions for settings.
- Preserve the source project's localized string resources; do not hard-code user-facing copy in new surfaces.
- Use sentence-style Android resource copy and explicit action labels such as acknowledge, close, open issue, and confirm.
- Avoid emoji as interface icons.
- Use the existing drawable and Compose icon resources. Do not hand-draw replacement SVGs.
- Use monospace only when content itself is technical output, such as logs.
