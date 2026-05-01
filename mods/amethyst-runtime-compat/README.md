# Amethyst Runtime Compat

This mod carries runtime-side compatibility fixes that are safer to ship as ModTheSpire patches than as direct game-jar edits.

## Included fixes

1. `FontScaleCompatPatches`
Adjusts `FontHelper.prepFont` so launcher-configured text scaling is applied consistently during MTS launches while staying independent from the launcher UI-scale slider. This addresses the symptom where text sizing only followed the base game's `Bigger Text` behavior or got unintentionally magnified again when UI scale was increased. Type: compatibility workaround implemented by `FontScaleCompatPatches`.

2. `ResolutionDropdownCompatPatches`
Guards the in-game settings resolution dropdown on Android-compatible runtimes. When no desktop-compatible resolution list can be built, the dropdown is replaced with a single `N/A` entry and resolution changes become a no-op instead of crashing. The patch also normalizes stale dropdown selection and scroll-window state during settings rendering so toggling graphics options cannot leave the placeholder dropdown pointing past its only row and crash inside `DropdownMenu.layoutRowsBelow`. Type: crash fix implemented by `ResolutionDropdownCompatPatches`.

3. `DuelistCompatPatches`
Short-circuits a few Duelist dynamic/base-value lookups so they reuse current card state instead of going through slower or less stable reflection-heavy paths.

4. `CharacterPreviewReuseCompatPatches`
Adds hooks used by `RuntimeMemoryDiagnostics` to track main-menu and character-select preview lifecycle, including preview reuse for modded characters and per-selected-character recreate summaries when the menu has to rebuild a modded preview instead of reusing it.

5. `RuntimeMemoryDiagnosticsPatches`
Adds runtime hooks for update/create-character/start-over/reset/dispose so memory diagnostics can observe long-session behavior without modifying the base game directly, including menu-cycle texture/FBO summaries and owner/source hotspot attribution suitable for normal play sessions.

6. `FrierenTextureCacheCompatPatches`
Intercepts `ImageMaster.loadImage` for Frieren slot-library textures and reuses one `Texture` per resource path instead of letting `Slot`, `SlotBgLibrary`, and `refreshSlot()` repeatedly allocate duplicates. This targets the severe GPU-memory growth that happens when Frieren rebuilds the full slot background library on each return to the main menu. Type: compatibility workaround for a third-party texture leak.

7. `DownfallMainMenuAtlasCompatPatches`
Intercepts `downfall.patches.MainMenuColorPatch.setMainMenuBG(TitleBackground)` and reuses a singleton Downfall main-menu `TextureAtlas` instead of constructing a fresh atlas on every menu rebuild. This targets the repeated `title.atlas` / `title.jpg` GPU-memory leak seen when bouncing back to the main menu. Type: compatibility workaround for a third-party texture leak.

8. `HinaCharacterRenderCompatPatches`
Downsizes Blue Archive Hina's offscreen 3D character render target from its desktop-style supersampled framebuffer to a mobile-safe framebuffer, keeps the helper's original logical camera scale, and replaces the original mipmap generation path with linear filtering during the final blit. This addresses the symptom where Hina's in-run 3D model renders as a blank character or at the wrong size on Android-compatible runtimes after the oversized framebuffer is pressure-downscaled or the desktop mipmap path misbehaves. The fix is controlled by the launcher's runtime compatibility switch for Hina character rendering. Type: compatibility workaround for third-party mobile rendering assumptions implemented by `HinaCharacterRenderCompatPatches`.

9. `UiScaleCompatPatches`
Uses the launcher's UI-size setting to dynamically rewrite base-game and mod reads of `Settings.isMobile`, so enlarged mobile-style layout branches are reused without turning on the real mobile HUD mode. Main-menu classes and speech-bubble VFX classes are intentionally excluded so the title screen and dialogue bubbles keep their desktop behavior instead of picking up mobile-only spacing and text placement. This addresses the symptom where the only built-in way to enlarge the interface was the native mobile HUD toggle, which also changes global runtime state and can break modded screens. Type: compatibility workaround implemented by `UiScaleCompatPatches` together with `MobileUiLayoutClassPatcher`.

10. `BurningEliteFlameCompatPatches`
Enlarges the burning-elite emerald flame marker when the launcher's Larger UI mode reuses mobile map-node scaling. This addresses the symptom where the elite node grows with the larger mobile-style layout but its flame VFX stays at desktop size and becomes hard to notice. Type: compatibility workaround implemented by `BurningEliteFlameCompatPatches`.

11. `CharacterOptionRelicKeywordCompatPatches`
Reapplies BaseMod's starter-relic multiword-keyword normalization when mobile layout handling is active inside `CharacterOption.renderRelics`. This addresses the symptom where character-select relic descriptions leak raw keyword prefixes such as `spearandshield:` after the launcher's Larger UI mode reroutes the screen through the mobile single-relic branch. Type: compatibility workaround implemented by `CharacterOptionRelicKeywordCompatPatches`.

12. `JacketNoAnoKoElectrocardiogramCompatPatches`
Rewrites JacketNoAnoKo's `ElectrocardiogramLoeweEffect` shader sources to GLES 100 at runtime on Android-compatible runtimes, installs the compiled shader back into the original effect, and lets the original render method draw the visual effect. If the rewritten shader still fails, it falls back to preserving the effect timing and sound trigger while skipping only the render, so the `Inspiration`/tuning card can continue resolving its selected gameplay effect without crashing. This addresses the repeated `Shader compilation failed: Error: shader version mismatch` crash at `ElectrocardiogramLoeweEffect.update(ElectrocardiogramLoeweEffect.java:64)`. Type: crash fix implemented by `JacketNoAnoKoElectrocardiogramCompatPatches`.

13. `JacketNoAnoKoJesterFormCompatPatches`
Reimplements JacketNoAnoKo's `JesterForm.use` and `JesterFormPower.atStartOfTurnPostDraw` on Android-compatible runtimes so the gameplay actions still apply while creation of `CombinedFireworksSpotlightEffect` is guarded. If the rewritten shader compiles, the original fireworks/spotlight effect is preserved; if shader setup still fails, only that visual effect is skipped. This addresses the `Shader compilation failed: Error: shader version mismatch` crash at `CombinedFireworksSpotlightEffect.<init>(CombinedFireworksSpotlightEffect.java:40)` when playing `jacketnoanokomod:JesterForm` or when its power triggers on later turns. Type: crash fix implemented by `JacketNoAnoKoJesterFormCompatPatches`.

## Maintenance rule

If you add another fix through this mod, update this README in the same change and describe:

- what symptom the fix addresses
- which patch class implements it
- whether it is a crash fix, compatibility workaround, or diagnostic hook
