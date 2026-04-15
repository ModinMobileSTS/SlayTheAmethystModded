# Amethyst Runtime Compat

This mod carries runtime-side compatibility fixes that are safer to ship as ModTheSpire patches than as direct game-jar edits.

## Included fixes

1. `FontScaleCompatPatches`
Adjusts `FontHelper.prepFont` so launcher-configured text scaling is applied consistently during MTS launches.

2. `ResolutionDropdownCompatPatches`
Guards the in-game settings resolution dropdown on Android-compatible runtimes. When no desktop-compatible resolution list can be built, the dropdown is replaced with a single `N/A` entry and resolution changes become a no-op instead of crashing.

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

## Maintenance rule

If you add another fix through this mod, update this README in the same change and describe:

- what symptom the fix addresses
- which patch class implements it
- whether it is a crash fix, compatibility workaround, or diagnostic hook
