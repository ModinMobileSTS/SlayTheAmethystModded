# STS Amethyst Launcher

Reusable OpenDesign system imported from the SlayTheAmethyst Android Jetpack Compose launcher.

## Understanding

The launcher is a Material 3 Android application for configuring and operating a modded Slay the Spire runtime. Its UI is operational rather than promotional: settings are organized into dense, scannable sections; workshop and feedback flows use status-rich content; boot overlays communicate progress and logs. The visual language is adaptive and user-controlled, with light, dark, or system-following mode and a selectable theme seed.

The system combines Material 3 defaults with a small set of launcher-specific primitives: rounded surface containers, low elevation, haptic actions, pinned frosted-glass headers, seeded color schemes, progress overlays, log panes, and low-alpha loading skeletons.

## Sources consulted

Theme and configuration:

- `app/src/main/java/io/stamethyst/ui/theme/LauncherTheme.kt`
- `app/src/main/java/io/stamethyst/config/LauncherThemeColor.kt`
- `app/src/main/java/io/stamethyst/config/LauncherThemeMode.kt`
- `app/src/main/java/io/stamethyst/config/LauncherThemeController.kt`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/values-v31/themes.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values-night/colors.xml`

Settings and shared Compose components:

- `app/src/main/java/io/stamethyst/ui/settings/common/SettingsScaffold.kt`
- `app/src/main/java/io/stamethyst/ui/settings/common/SettingsCommonUi.kt`
- `app/src/main/java/io/stamethyst/ui/settings/core/SettingsScreen.kt`
- `app/src/main/java/io/stamethyst/ui/settings/sections/SettingsAppearanceSection.kt`
- `app/src/main/java/io/stamethyst/ui/FrostedGlassChrome.kt`
- `app/src/main/java/io/stamethyst/ui/FloatingGlassHeader.kt`
- `app/src/main/java/io/stamethyst/ui/AppSearchBar.kt`
- `app/src/main/java/io/stamethyst/ui/LoadingSkeleton.kt`

Runtime and overlay surfaces:

- `app/src/main/java/io/stamethyst/BootOverlayController.kt`
- `app/src/main/java/io/stamethyst/ui/GameAndroidUiPalette.kt`

The source uses platform Material 3 typography and does not define a custom Compose `Typography` or `Shapes` object. The documented type and shape guidance therefore records observed Material 3 usage and local component overrides rather than inventing a replacement theme object.

## Folder index

- `SKILL.md`: portable usage guidance for agents and design work.
- `tokens/colors_and_type.css`: canonical raw and semantic color, type, spacing, shape, elevation, and motion tokens.

## Token usage

The default CSS semantic roles represent the exact colorless light scheme from `LauncherTheme.kt`. Apply `[data-theme-mode="dark"]` or the equivalent dark-mode class for the exact colorless dark roles. The `--seed-*` variables preserve the five persisted theme color inputs; named theme role derivation remains HSL/blend-based as documented in `SKILL.md` and implemented by the Kotlin source.

Use semantic tokens in artifacts. Use raw seed and tonal tokens only when a component explicitly needs a theme seed, source color preview, or exact Material role.

## Canonical decisions

- Material 3 is the component baseline.
- System, light, and dark modes must all remain legible.
- Primary, secondary, and tertiary accents are generated from one selected seed rather than used as unrelated brand colors.
- Surfaces are layered through Material container roles, not decorative gradients.
- Glass treatment is reserved for persistent chrome and uses real blur behavior where available.
- User-facing copy comes from localized resources and should remain concise and descriptive.

## Open questions for future iterations

- The source does not provide a standalone typography or shape theme override, so those remain Material 3 defaults plus observed per-component values.
- The source contains both launcher Compose surfaces and lower-level game overlay palettes; future artifacts should explicitly choose which surface family they target.
- Exact localized copy and drawable assets were not copied into this system because this import requested only the portable skill, README, and token files.
