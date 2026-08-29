# amethyst-frame-probe

Bundled mod that provides per-frame timing, an optional in-game bar-chart HUD, and structured JSONL incident output.

## Activation

Enable **Deep performance diagnostics** in developer settings. Collection does not depend on **Performance overlay**; that separate switch only controls HUD visibility. `StsLaunchSpec` adds:

```
-Damethyst.gdx.frame_ring=true
-Damethyst.gdx.frame_ring.budget_ms=<1000/targetFps>
-Damethyst.gdx.frame_hud=<true when Performance overlay is enabled>
```

Nothing runs when `amethyst.gdx.frame_ring` is absent.

## Default-enabled fix (2026-08-22)

**Symptom addressed**: The mod was added to the MTS launch list unconditionally, so on every normal launch it was *enabled* — its `SpireInitializer` ran, the three `@SpirePatch2` hooks in `FrameProbePatches` were installed, and it subscribed to `PostUpdateSubscriber`/`PostRenderSubscriber` — even when deep performance diagnostics was off. The internal `FrameRingBuffer.ENABLED` check only made the probe a no-op; the mod was still loaded, instrumented, and wired into the render/update loop.

**Fix**: The launcher (`ModManager.isFrameProbeEnabled`) now gates the mod behind the same effective condition that `StsLaunchSpec.buildArgs` uses to set `-Damethyst.gdx.frame_ring=true`: deep performance diagnostics enabled **and** the arthas resource pack installed. The mod is no longer added to the launch list (`buildLaunchModSnapshot`, `listMtsLaunchModFiles`) nor reported as enabled in the installed-mod list unless both hold. The internal `FrameRingBuffer.ENABLED` guard in `AmethystFrameProbe` is retained as defense-in-depth.

**Patch class**: `io.stamethyst.backend.mods.ModManager.isFrameProbeEnabled` (launcher-side launch gating).

## Included fixes / components

### 1. `FrameRingBuffer` (gdx-patch layer)
**What it does**: Zero-allocation ring buffer (1800 slots ≈ 20 s at 90 fps) that records every rendered frame unconditionally: `totalNs`, `renderNs`, `guardianNs`, `reclaimNs`, `swapNs`, heap bytes, SpriteBatch flush count, texture-switch count.
**Symptom addressed**: The old `FrameProfiler` was gated behind a 33 ms threshold; frames taking 12–32 ms (perfectly visible to players on a 90 fps target) were silently dropped.
**Patch class**: `com.badlogic.gdx.backends.lwjgl.FrameRingBuffer` (new class in gdx-patch, registered in `ModRuntimeJarConstants` and `StsDesktopJarPatcher`).

### 2. `AmethystFrameProbe` (mod entry point)
**What it does**: Subscribes to BaseMod `PostUpdateSubscriber` / `PostRenderSubscriber`. On each update tick it drains `FrameRingBuffer` and feeds data to `FrameHud` and `IncidentWriter`.
**Symptom addressed**: No integrated drain point existed; data had to be pulled manually via harness scripts.
**Patch class**: `io.stamethyst.frameprobe.AmethystFrameProbe` (SpireInitializer).

### 3. `FrameHud` (in-game bar chart)
**What it does**: When `amethyst.gdx.frame_hud=true`, renders a 180-bar scrolling chart (bottom-left corner) coloured green/yellow/red relative to the configured budget. Collection and incident output continue when the HUD is hidden.
**Symptom addressed**: The old 1 Hz overlay could not show individual frame spikes, while requiring a visible overlay prevented headless diagnostics collection.
**Patch class**: `io.stamethyst.frameprobe.FrameHud` (no SpirePatch, rendered via PostRenderSubscriber).

### 4. `IncidentWriter` (JSONL output)
**What it does**: Off-render-thread writer. Every frame that exceeds the budget threshold is serialised as one JSONL line to `<stsRoot>/frame-probe-incidents.jsonl`. Previous session file is rotated to `frame-probe-incidents.prev.jsonl`. Fields: `t` (wall clock ms), `frame`, `totalMs`, `renderMs`, `guardianMs`, `reclaimMs`, `swapMs`, `heapMb`, `flushes`, `switches`, plus game context fields from `GameContext` (`room`, `floor`, `act`, `tag`, `action`).
**Symptom addressed**: Old diagnostics required manually parsing `[gdx-frame]` log lines; this produces machine-readable output usable for baseline comparison.
**Patch class**: `io.stamethyst.frameprobe.IncidentWriter` (background daemon thread).

### 5. `FrameProbePatches` (game-event context hooks)
**What it does**: Three SpirePatch2 hooks write game state into `GameContext.INSTANCE`:
- `CardUsePatch` — records the card ID and frame when a card is played (`tag: card:<id>`).
- `RoomTransitionPatch` — records room class, floor, and act on each `nextRoomTransition`.
- `ActionUpdatePatch` — records the last `AbstractGameAction` subclass that started updating.

**Symptom addressed**: Without game context, a slow frame at ms 4200 tells you nothing; with it you see `"tag":"card:Whirlwind","action":"DamageAllEnemiesAction"` and the root cause is obvious.
**Patch class**: `io.stamethyst.frameprobe.FrameProbePatches` (three independent inner patch classes, each addressing one domain).

## Output format

```jsonl
{"t":1723621842000,"frame":9134,"totalMs":18.342,"renderMs":15.210,"guardianMs":0.012,"reclaimMs":0.188,"swapMs":2.844,"heapMb":398,"flushes":312,"switches":87,"room":"MonsterRoom","floor":3,"act":1,"tag":"card:Whirlwind","action":"DamageAllEnemiesAction"}
```

Parse with `jq` or the `tools/perf-harness` schema at `tools/perf-harness/testdata/baseline-run/metrics.json`.

## Design constraints

- **No threshold in the ring**: every frame is written unconditionally; thresholding happens in `FrameHud`/`IncidentWriter` at read time, not write time.
- **Independent collection and display**: `amethyst.gdx.frame_ring=true` controls collection and incident output; `amethyst.gdx.frame_hud=true` only controls HUD rendering. The old five-property profiler scheme is removed.
- **Render-thread only**: `FrameRingBuffer` has no locks; single writer and reader on the same thread.
- **No patch in a single file per this repo's AGENTS.md rules**: `CardUsePatch`, `RoomTransitionPatch`, and `ActionUpdatePatch` are separate inner classes in `FrameProbePatches.java`, each addressing one distinct domain.
