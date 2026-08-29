# Ram Saver

## Low-overhead GPU diagnostics

`RamSaverDiag` is enabled only by the dedicated `ramsaver.diag.enabled=true` property. Enabling the launcher's Deep performance diagnostics no longer activates Ram Saver duration tracking, render-thread stack capture, slow-event logging, or per-resource lifecycle logging. This addresses the symptom where drawing or shuffling cards becomes visibly slower only while diagnostics are enabled, and prevents that diagnostic overhead from being recorded by the frame probe as a false gameplay stall. GPU texture/FBO counters, byte estimates, source attribution, and periodic summaries remain available; intrusive texture/FBO stack sampling, resource-stall timing, and lifecycle logs require their dedicated properties. The behavior is implemented by `optispire.RamSaverDiag`, with low-overhead defaults in `GLTexture` and `GLFrameBuffer`.

This bundled mod carries Ram Saver's texture-lifetime changes as a launcher-managed ModTheSpire component, so the Android runtime can ship the same RAM-saving behavior and Amethyst-specific diagnostics without requiring a user-imported workshop jar.

## Included fixes

1. `com.badlogic.gdx.graphics.Texture`, `com.badlogic.gdx.graphics.RealTexture`, and `optispire.RamSaver`
Replace normal file-backed texture construction with fake/lazy textures that keep file identity and dimensions but defer real `RealTexture` creation until rendering or API calls need an actual GL handle. This addresses the symptom where heavily modded runs keep too many image assets loaded at once and exhaust Java/native/GPU memory. Type: memory-management workaround implemented by the Ram Saver texture replacement and asset manager.

2. `optispire.RamSaver`
Ages managed texture and atlas-region holders in small rotating buckets, disposes stale assets, and processes weak-reference notifications so logical image assets can be released after they are no longer strongly referenced. This addresses long-session memory growth from cached card, UI, atlas, and region assets. Type: memory-management workaround implemented by `RamSaver.update`, `ManagedAsset`, and the `FileTextureSupplier` path.

3. `optispire.patches.HandleRenderingFakes` and `optispire.patches.G3dBindRealTextures`
Materialize fake textures only at SpriteBatch, PolygonSpriteBatch, and g3d bind sites, then restore the fake texture object after drawing where needed. This addresses rendering paths that require real GL texture handles while preserving lazy texture residency elsewhere. Type: rendering compatibility workaround implemented by `HandleRenderingFakes` and `G3dBindRealTextures`.

4. `optispire.patches.ChangeSpriterLoader`
Disables Spriter pixmap packing and routes Spriter sprite creation through lazy texture-backed sprites. This addresses Spriter animation loading paths that would otherwise force eager pixmap/texture allocation. Type: memory-management workaround implemented by `ChangeSpriterLoader`.

5. `optispire.patches.PixmapLessAngry`
Prevents double-disposed pixmaps from throwing when the disposed flag is already set, printing a diagnostic message instead. This addresses cleanup paths made more common by aggressive image lifetime management. Type: compatibility workaround implemented by `PixmapLessAngry`.

6. `optispire.patches.LessColors`
Reuses a shared white `Color` instance for repeated card-image color-copy calls during `AbstractCard.createCardImage`. This addresses avoidable temporary object churn in card image creation paths that are exercised more often when images are lazily reloaded. Type: memory-management workaround implemented by `LessColors`.

7. `optispire.patches.AggressiveGC`
Requests Java GC from selected lifecycle points to accelerate cleanup after Ram Saver has made assets collectible. This addresses the symptom where released fake/real texture wrappers can otherwise remain in heap until a later GC cycle. Type: memory-pressure workaround implemented by `AggressiveGC`.

This patch requires the launcher to leave explicit GC enabled. `RamSaver.update` drains a `ReferenceQueue` and only disposes a backing texture for entries the collector has already cleared and enqueued, so `System.gc()` here is what converts "collectible" into "native memory actually freed". The launcher therefore omits `-XX:+DisableExplicitGC` whenever Ram Saver is active (`StsLaunchSpec.resolveDisableExplicitGcEnabled`); with that flag applied these calls become no-ops and native texture release is deferred to the next incidental collection.

Because those calls are reachable, the launcher also adds `-XX:+ExplicitGCInvokesConcurrent` (`StsLaunchSpec.resolveExplicitGcInvokesConcurrentEnabled`, applied only when explicit GC was not suppressed, inside the 64-bit branch that selects G1). `System.gc()` otherwise defaults to a full stop-the-world collection, which lands on the render thread as a frame hitch; the G1 concurrent cycle still clears and enqueues the weak references this patch depends on, so the release path above is unaffected.

**Rate limiting (2026 performance fix)**: `AggressiveGC` now throttles `System.gc()` calls to at most one per 45 seconds (configurable via `ramsaver.gc.throttle_seconds`, range 10-300). This prevents multiple stop-the-world GC pauses during startup when BaseMod publishes cards/relics/characters/strings/keywords in quick succession. Suppressed GC requests are logged when diagnostics are enabled. This preserves Ram Saver's memory reclamation behavior while eliminating the 10-80ms frame drops that caused severe 1% low framerate degradation and user-visible stutter.

8. `optispire.RamSaverDiag`
Adds `[ram-saver]` diagnostic logging gated by the launcher's `amethyst.gdx.gpu_resource_diag` property or the explicit `ramsaver.diag.enabled` property, with full verbose traces requiring `ramsaver.diag.verbose=true`. This addresses the need to attribute render-thread materialization stalls and repeated texture creation without flooding normal gameplay logs. Type: diagnostic hook implemented by `RamSaverDiag` and instrumentation in the texture/materialization paths.

9. `optispire.RamSaver` and `com.badlogic.gdx.graphics.Texture`
Caches missing/rejected texture paths and detects repeated fake texture creation from render-like stacks, logging `repeated_render_texture_create` milestones while suppressing repeated full fake-texture creation stacks after the first hot threshold. This addresses long-session stutter caused by mods repeatedly constructing missing or duplicate textures during render, such as repeated `TimeEaterImg/img/clock/alarm.png` lookup attempts from `Clock.AbstractClock.render`. Type: performance diagnostic and mitigation implemented by `RamSaver.isTextureRejected`, `RamSaver.markTextureRejected`, `RamSaver.recordFakeTextureCreate`, and the file-backed `Texture` constructor.

10. `optispire.RamSaver` and `com.badlogic.gdx.graphics.Texture`
Keeps shared fake-texture state per file path, including cached dimensions, supplier identity, rejected status, and repeated-render construction state. This addresses the symptom where generic render-path `new Texture(path)` loops repeatedly redo registration, header-size probing, and state setup even though the path is identical. Type: performance mitigation implemented by `RamSaver.FakeTextureState`, `RamSaver.cacheTextureSize`, `RamSaver.getCachedTextureSize`, `RamSaver.registerTexture`, and the file-backed `Texture` constructor.

11. `optispire.RamSaverDiag` and `optispire.RamSaver`
Stops treating RAM Saver fake wrappers as live GPU textures in Amethyst's GDX diagnostic counters and samples render-path creation stacks only at low-frequency milestones once a path is known to be repeated. This addresses the symptom where GPU diagnostics amplify bad render-path texture construction by logging thousands of fake-wrapper `GLTexture construct_repeat` stacks and by repeatedly calling `Thread.getStackTrace` on the render thread. Type: diagnostic/performance mitigation implemented by `RamSaverDiag.markFakeTextureWrapperConstructed`, `RamSaver.recordFakeTextureCreate`, and `RamSaver.findRenderTextureCreationSignature`.

12. `optispire.RamSaver`
Extends the default rotating-bucket aging window from the original aggressive 5 seconds to a configurable 15 seconds (`ramsaver.age.tick_seconds`) so ordinary cached assets are not released and reloaded as quickly. This addresses heat and stutter caused by short-term UI, map, reward, and scene textures being repeatedly disposed and synchronously decoded/uploaded again during normal play. Type: performance/thermal mitigation implemented by `RamSaver.update` and the configurable `TICK` value.

13. `optispire.RamSaver`
Pins hot real textures for a bounded time when they are slow to materialize or repeatedly materialized within a short window, then enforces a configurable total hot-texture budget (`ramsaver.hot.budget_mb`). This preserves correctness while reducing repeated render-thread `RealTexture` decode/upload work for frequently reused card, UI, map, reward, and effect textures. Type: performance/thermal mitigation implemented by `RamSaver.markTextureMaterialized`, `RamSaver.isHotTexturePinned`, `RamSaver.enforceHotPinBudget`, and `ManagedAsset.isHotPinned`.

14. `optispire.RamSaver`
Catches runtime failures while materializing a lazy file-backed texture, logs the failing path and exception, and substitutes a pinned 1x1 transparent fallback texture instead of letting render-thread PNG decode failures crash the game. This addresses crashes such as `Couldn't load file: HakureiReimuResources/images/ui/PowerShadow.png` / `decoder init failed for stream` when Ram Saver restores a mod texture during rendering. Type: crash fix implemented by `RamSaver.FileTextureSupplier` and the materialization fallback path in `RamSaver`.

15. `com.badlogic.gdx.graphics.Texture` and `optispire.RamSaver`
Materializes fake file-backed textures when mods call `Texture.bind()` or `Texture.bind(int)` directly, but uses a non-refreshing cache lookup before loading so ordinary asset aging is not touched on every bind. This addresses missing or incorrect shader effect textures, such as foil/noise/spectrum textures used by card visual-effect mods, while avoiding the per-frame residency refresh and budget bookkeeping that made the direct-bind compatibility path too heavy. Type: rendering compatibility/performance workaround implemented by the fake-texture `bind` overrides in `Texture` and `RamSaver.getTextureForBindFallback`.

16. `optispire.patches.SpriteCacheFakeTextures`
Temporarily swaps fake textures stored in `SpriteCache` private cache entries to real textures only for `SpriteCache.draw(int)` and `SpriteCache.draw(int, int, int)`, using the same non-refreshing bind fallback as direct `Texture.bind()`, then restores the fake objects after drawing. This addresses cached mesh render paths that bind textures directly from `SpriteCache.Cache.textures` and would otherwise bind fake GL handle `0` or refresh texture residency every frame. Type: rendering compatibility/performance workaround implemented by `SpriteCacheFakeTextures`.

17. `optispire.patches.DecalMaterialFakeTexture`
Temporarily swaps the texture inside `DecalMaterial.textureRegion` to a real texture for `DecalMaterial.set()` through the non-refreshing bind fallback, then restores the fake texture after the material has bound texture unit `0`. This addresses decal/g3d render paths that call `Texture.bind(0)` directly and avoids repeatedly refreshing texture residency from the material bind path. Type: rendering compatibility/performance workaround implemented by `DecalMaterialFakeTexture`.

18. `optispire.patches.TextureDescriptorFakeTexture`
Makes `TextureDescriptor.hashCode()` and `TextureDescriptor.compareTo(TextureDescriptor)` fake-texture-safe by using the fake texture's file identity as a stable sort/hash key instead of asking the fake texture for a real GL handle. This addresses g3d material sorting and texture-binder bookkeeping paths that could otherwise decode/upload real textures just to hash or compare descriptors. Type: memory-management/performance workaround implemented by `TextureDescriptorFakeTexture`.

19. `optispire.patches.TextureDescriptorFakeTexture`
Reads the `TextureDescriptor.compareTo(TextureDescriptor)` argument through ModTheSpire's `__args` array instead of a named `other` patch parameter. This addresses startup failures with `PatchingException: Illegal patch parameter: No matching parameter with name "other"` when the target libGDX method parameter name is unavailable or differs from the patch method name. Type: startup crash fix implemented by `TextureDescriptorFakeTexture`.

20. `com.badlogic.gdx.graphics.Texture`
Reads JPEG dimensions from the file header for fake file-backed textures before falling back to real texture materialization. This addresses render-thread stalls where atlas or region setup calls `getWidth()` / `getHeight()` on a fake JPG, forcing a full `RealTexture` decode/upload only to discover its dimensions, such as `title/title.jpg` during menu construction. Type: performance/memory-management workaround implemented by the fake-texture `Texture.getSize` header-size path.

21. `optispire.RamSaverDiag`, `com.badlogic.gdx.graphics.Texture`, `com.badlogic.gdx.graphics.RealTexture`, `optispire.RamSaver`, `optispire.patches.HandleRenderingFakes`, `optispire.patches.G3dBindRealTextures`, `optispire.patches.SpriteCacheFakeTextures`, `optispire.patches.DecalMaterialFakeTexture`, `optispire.patches.ChangeSpriterLoader`, and `optispire.patches.AggressiveGC`
Reads Ram Saver diagnostic flags once at startup and skips diagnostic timing, stack logging, and texture/region detail string construction unless diagnostics are enabled. This addresses normal-gameplay allocation churn and young-GC pressure from disabled diagnostics in fake-texture draw, cache-hit, direct-bind, SpriteCache, decal, Spriter, texture load, and asset-aging paths while preserving the same lazy texture loading and memory-reduction behavior. Type: performance/thermal diagnostic-overhead mitigation implemented by the guarded Ram Saver diagnostic call sites.

22. `optispire.patches.CombatTexturePrewarm`
Actively registers and pre-materializes a fixed set of core combat card UI, card atlas, power atlas, and Exordium scene textures starting from splash/black-screen loading, then continuing through character-select and non-combat gameplay `CardCrawlGame.update` ticks, with a fallback that finishes any remaining textures during `AbstractDungeon.nextRoomTransition(SaveFile)` before `AbstractPlayer.preBattlePrep`. This addresses the first-battle hitch where the battle-start banner and opening hand frames stall while Ram Saver lazily decodes/uploads `cardui`, `cards`, `powers`, and bottom-scene textures on the render thread. Type: performance mitigation implemented by `CombatTexturePrewarm`.

23. `optispire.patches.BattleStartResourcePrewarm`
Preloads the battle-start sound effects, first-combat `BUFF_*` / `DEBUFF_*` power-apply sound effects, battle-start sword atlas region, and the glyphs used by `BattleStartEffect`'s banner and turn text during splash/black-screen loading, continuing through non-combat screens with an `AbstractDungeon.nextRoomTransition(SaveFile)` fallback for the first monster room. This addresses the remaining first-battle banner hitch where `BattleStartEffect.render` creates FreeType glyphs, `BattleStartEffect` may lazily decode battle-start OGG sounds, and early monster powers such as Louse strength/defense can lazily decode `AbstractPower.playApplyPowerSfx` sounds on the render thread before the banner is published. Type: performance mitigation implemented by `BattleStartResourcePrewarm`.

24. `optispire.patches.FirstCombatUiPrewarm`
Preloads BaseMod's first card-glow FBO/ShapeRenderer setup and Chinese dynamic-variable card-description render patch, initializes the monster intent switch map, pre-materializes first-room Exordium monster Spine page textures, combat health/block bar textures, and monster intent icon textures, prewarms combat health/block/power amount and monster-name glyphs, primes StSLib's red-health-bar reflection lookup, and performs one offscreen player Spine mesh draw after the player is created, with a first-monster-room fallback. This addresses first-combat UI hitches where the opening hand compiles BaseMod glow shaders, first loads BaseMod's `RenderCustomDynamicVariableCN` patch while rendering hand text, the first monster render lazily decodes/uploads `images/monsters/theBottom/.../skeleton.png`, the first player or monster health bar render uploads `images/ui/combat/body7.png`, `left7.png`, `right7.png`, and related block/bar textures, `BattleStartEffect.update` stalls while `MonsterGroup.showIntent` initializes intent routing and uploads `images/ui/intent/...` textures, the first health bar and monster-name text renders upload FreeType glyph pages, StSLib's first red-health-bar render pays a reflection lookup on `targetHealthBarWidth`, and the first visible player Spine render spends a slow frame in `SkeletonMeshRenderer.draw` on the render thread. Type: performance mitigation implemented by `FirstCombatUiPrewarm`.

25. `optispire.patches.FirstCombatLogConsolePrewarm`
Pre-initializes Swing text-document insertion, the AWT event queue lookup, and ModTheSpire's redirected stdout/stderr console output path during splash/black-screen loading, with a first-monster-room fallback. This addresses first-combat entry hitches where early combat logging such as monster power application can stall the render thread inside `MessageConsole$ConsoleOutputStream.clearBuffer`, `DefaultCaret`, and `Toolkit.getEventQueue` before the battle-start banner is published. Type: performance mitigation implemented by `FirstCombatLogConsolePrewarm`.

26. `optispire.patches.CombatTexturePrewarm`
Keeps the shared `vfx/vfx.png` atlas resident with the existing combat prewarm set so card animations and other combat effects do not repeatedly materialize the lazy atlas during draw actions. This addresses SpriteBatch flush spikes and render hitches caused by effect-region draws switching between fake and real textures. Type: rendering performance mitigation implemented by `CombatTexturePrewarm`.

## Maintenance rule

If you add another runtime/gameplay fix through this mod, update this README in the same change and describe:

- what symptom the fix addresses
- which patch class implements it
- whether it is a memory-management workaround, compatibility workaround, crash fix, or diagnostic hook
