# GPU Resource Guardian Plan

This note captures the staged plan for replacing the separate texture/FBO reclaim knobs with a low-overhead GPU resource guardian. It is intentionally implementation-oriented so the plan is not lost between sessions.

## Goal

Prevent long-play GPU memory growth from leaking mods without reintroducing the current performance risks from independent texture residency, FBO manager, FBO idle reclaim, and FBO pressure downscale strategies.

The default behavior should prioritize stable frame time:

- No full resource scans every frame.
- No unbudgeted texture restore from `bind()` / `getTextureObjectHandle()`.
- No normal-mode downscale.
- No frequent diagnostic stack capture or verbose logging.
- Only low-frequency, pressure-driven, tiny-batch reclaim.

## Final shape

Expose one user-facing mode instead of four independent default knobs:

```text
GPU resource protection:
- Off
- Safe mode (default)
- Aggressive mode
- Diagnostic mode
```

Recommended default internal behavior:

```text
gpu_resource_guardian = safe
texture_residency_restore = budgeted
fbo_reclaim = safe_idle_pressure_only
texture_reclaim = safe_idle_pressure_only
pressure_downscale = emergency_only
diagnostic_stacks = off
diagnostic_logs = state_changes_only
```

## Unified state machine

Create a single guardian that owns global GPU resource pressure decisions using:

```text
gpuEstimatedBytes = GLTexture.estimatedNativeBytes + GLFrameBuffer.estimatedNativeBytes
```

Suggested states:

```text
NORMAL
WATCH
PRESSURE
EMERGENCY
COOLDOWN
```

### NORMAL

- Sample only cheap counters every few seconds.
- Do not scan resource arrays.
- Do not reclaim.

### WATCH

Enter when bytes approach soft budget or growth is sustained.

- Low-frequency small-slice candidate scoring.
- No reclaim yet unless growth persists.

### PRESSURE

Enter when over soft budget for multiple samples or growth continues.

- Reclaim tiny batches only.
- Suggested maximum per sweep: 1 FBO or 1-2 textures or 8-16MB.
- Enter cooldown after reclaim.

### EMERGENCY

Enter when over hard budget or close to freeze/OOM.

- Permit more aggressive reclaim.
- Permit emergency-only downscale.
- Still avoid large unbudgeted restores on render hot paths.

### COOLDOWN

- Prevent reclaim/restore oscillation.
- Suppress candidates that quickly rebuild or restore.

## Candidate scoring

Use a shared scoring model instead of independent texture/FBO policies:

```text
score = bytesScore
      + idleScore
      + ownerRiskScore
      - restorePenalty
      - recentUsePenalty
      - coreAssetPenalty
```

Prefer reclaiming:

- Large resources.
- Very long idle resources.
- External/mod-owned resources.
- Resources with no recent restore/rebuild history.
- Resources not currently bound.

Protect:

- Recently accessed resources.
- Core/base-game UI, fonts, common atlas resources.
- Current GL bindings.
- Non-managed or non-reloadable resources.
- Resources that were quickly rebuilt/restored after prior reclaim.

## Stage 1: Guardian skeleton and monitoring

Implement the guardian but make it monitoring-only first.

Tasks:

1. Add a `GpuResourceGuardian` owned by the render loop or libGDX patch layer.
2. Read existing counters only:
   - `GLTexture` estimated native bytes.
   - `GLFrameBuffer` estimated native bytes.
3. Add dynamic soft/hard budgets based on device memory class and resolution.
4. Add state transitions: `NORMAL -> WATCH -> PRESSURE -> EMERGENCY -> COOLDOWN`.
5. Log only state changes by default.
6. Keep stack traces and owner summaries diagnostic-only.
7. Do not reclaim in this stage.

Success criteria:

- Near-zero frame-time overhead in normal gameplay.
- No per-frame array scans.
- No recurring verbose logs.
- State changes make sense during long sessions with resource growth.
- The guardian reports both tracked bytes and an explicit `untracked/unknown` caveat so diagnostics do not overclaim coverage.

## Stage 2: Safe FBO reclaim

FBO reclaim should be implemented before texture reclaim because the existing FBO metadata is already closer to what the guardian needs.

Default safe FBO reclaim conditions:

```text
estimatedNativeBytes >= 8-16MB
idleFrames >= 3600-7200
not currently bound
not externally accessed recently
not rebuilt recently
not reclaimed recently
not quick-rebuilt after previous reclaim
owner is preferably external/mod-owned
```

Suggested safe-mode limits:

```text
sweepIntervalFrames = 300-600
maxChecksPerSweep = 8
maxFboReclaimsPerSweep = 1
maxReclaimedBytesPerSweep = 16MB
cooldownAfterReclaimFrames = 600-1800
quickRebuildBlacklistFrames = 7200+
```

Tasks:

1. Move global pressure decisions out of `GLFrameBuffer` into the guardian.
2. Make `GLFrameBuffer` expose candidate checks and reclaim execution only.
3. Use existing bind tracking where possible; fall back to GL query only if unknown.
4. Suppress/blacklist candidates that rebuild shortly after reclaim.
5. Keep pressure downscale disabled in safe mode.

Success criteria:

- Tracked, idle, reclaim-safe long-play FBO memory growth is capped or slowed.
- Reclaim never happens every frame.
- Reclaim does not cause repeated rebuild/reclaim oscillation.
- Normal sessions below budget do not scan or reclaim.
- Safe-mode FBO scanning has a fixed per-sweep upper bound independent of total FBO count.

## Stage 3: Safe texture reclaim with budgeted restore

Texture reclaim is riskier because restoring a texture can force synchronous upload. Do not keep the current behavior where an expensive restore can happen directly in `bind()` without a budget.

Default safe texture reclaim conditions:

```text
is managed
TextureData is FileTextureData
data is managed
glTarget == GL_TEXTURE_2D
estimatedNativeBytes >= 16-32MB
idleFrames >= 7200-18000
not current texture binding
restoreCount is low
not recently restored
owner is preferably external/mod-owned
```

Required restore redesign:

1. Small textures may restore synchronously under a strict byte threshold.
2. Large textures must enqueue a restore request.
3. Process pending restores at a safe point with limits:

```text
maxRestoreCountPerFrame = 1
maxRestoreBytesPerFrame = 4MB or less in safe mode
maxRestoreNanosPerFrame = about 1ms
```

4. If a reclaimed large texture is accessed before restoration, use a fallback/placeholder texture for that frame or defer restoration to a loading/menu safe point.
5. Blacklist textures that are quickly accessed after reclaim.

Tasks:

1. Convert texture residency manager into a guardian-controlled candidate executor.
2. Replace full-list candidate building with cursor/slice scanning in safe mode. The current full `managedTextures` scan/list/sort approach is acceptable only for diagnostic or emergency mode.
3. Remove unbudgeted large restore from `bind()` / `getTextureObjectHandle()`.
4. Add pending restore queue and frame-budgeted processing.
5. Add quick-access-after-reclaim suppression so textures that are touched soon after reclaim are protected for a long window.
6. Keep stack classification and verbose restore logs diagnostic-only.
7. Keep normal-mode texture pressure downscale disabled.

Success criteria:

- Tracked, managed, reloadable, idle leaking mod textures can eventually be reclaimed.
- Reclaim does not introduce large random upload stalls in gameplay.
- Frequently used textures are learned and protected.
- Restore behavior is bounded and observable.
- Safe-mode texture scanning has a fixed per-sweep upper bound independent of total managed texture count.

## Proof boundaries and required assumptions

This plan is a bounded-risk mitigation, not a mathematical guarantee that every GPU leak will be fixed.

It can only guarantee low overhead if the implementation preserves these constraints:

- Normal mode reads counters only; it must not scan managed texture/FBO arrays every frame.
- Safe-mode candidate discovery must be cursor/slice based. Do not use the current full `managedTextures` candidate list construction in the render loop.
- Every sweep must have hard limits for checked objects, reclaimed objects, reclaimed bytes, and cooldown.
- Large texture restore must be queued and budgeted; it must not run unbounded from `bind()` or `getTextureObjectHandle()`.
- Stack traces, owner summaries, and verbose logs must remain diagnostic-only.
- Emergency downscale must not run in safe mode.

It can only reduce memory growth for resources that meet these assumptions:

- The allocation is visible to the libGDX patch counters (`GLTexture` or `GLFrameBuffer`).
- The leaked object eventually becomes idle enough to be a safe candidate.
- The object is managed/reloadable or otherwise safe to release.
- The resource is not continuously rebound/used every frame.
- The budget is reached before the driver/device is already unrecoverably stalled.

It cannot guarantee recovery for these cases:

- Raw GL allocations made by mods outside `GLTexture` / `GLFrameBuffer` tracking.
- Native/driver allocations that are not reflected in estimated counters.
- Non-managed textures or custom `TextureData` that cannot be reloaded safely.
- Resources that are technically leaking but still touched every frame.
- Driver memory fragmentation where freeing some objects does not return usable memory.
- Devices where the OS or driver kills/freezes the process before guardian thresholds trigger.

Therefore the success claim should be:

```text
The guardian bounds its own overhead and reduces/caps tracked idle GPU-resource growth under the stated assumptions.
```

It should not be documented as:

```text
The guardian always fixes all GPU memory leaks.
```

## Emergency-only downscale

Downscale should not be part of default safe mode. It should only activate in `EMERGENCY` when reclaim cannot lower memory enough.

Rules:

- Downscale new uploads or newly created large FBOs only when possible.
- Avoid resizing active FBOs during normal render flow.
- State-change log only by default.
- Detailed per-resource downscale logs only in diagnostic mode.

## Validation plan

The plan is considered valid only if both memory-control and frame-time constraints pass. A result that lowers memory but introduces new p99 spikes is a failure for safe mode.

Measure before and after with:

- Long-session tracked GPU estimated bytes.
- Growth slope in MB/minute after warmup.
- Time until soft/hard budget crossing.
- p95/p99 frame time.
- longest frame.
- reclaim count and reclaimed bytes.
- quick rebuild/restore count after reclaim.
- restore queue backlog.
- safe-mode sweep cost: checked object count, elapsed nanos, and allocations.
- number of GL queries per sweep.
- state transitions and time spent in each state.

Safe-mode acceptance criteria:

```text
normal/no-leak session:
  p99 frame time regression <= 1ms or <= 5%, whichever is larger
  no recurring per-frame allocations from guardian
  no reclaim below budget

known tracked leak session:
  post-warmup tracked byte growth slope is reduced substantially or capped
  guardian reaches PRESSURE before hard freeze/OOM
  quick rebuild/restore ratio stays low enough to show candidates are truly idle

stress/emergency session:
  process avoids the previous full freeze/OOM longer than baseline
  any downscale or aggressive reclaim is reported as emergency behavior, not safe-mode behavior
```

Key scenarios:

1. Normal base-game session with no leaking mods.
2. Long modded session with known GPU memory growth.
3. Texture-heavy mod scene changes.
4. FBO-heavy effects/UI.
5. Low-memory device or forced low budget.

## Validation result

Synthetic tracked texture validation was run on device `2206122SC` with `launchMode=vanilla` and a hidden debug-only injector that creates `downfallresources/guardian_leak_1024.png` textures. The injector and extra low budgets are not exposed in normal UI and are gated by debug/test prefs.

Relevant logs:

- OFF baseline: `app/build/sts-logs/sts-jvm-logs-export-20260512-020656.zip`
- Old SAFE before attribution fix: `app/build/sts-logs/sts-jvm-logs-export-20260512-020919.zip`
- SAFE after attribution fix: `app/build/sts-logs/sts-jvm-logs-export-20260512-022110.zip`
- SAFE after restore-budget guard: `app/build/sts-logs/sts-jvm-logs-export-20260512-022758.zip`
- Exact final build after restore-size protect filter: `app/build/sts-logs/sts-jvm-logs-export-20260512-023202.zip`

Observed result:

```text
OFF baseline:
  synthetic injected textures: 80
  synthetic injected bytes: 335,544,320
  tracked bytes kept growing to about 1.12GB in the run

SAFE before fix:
  Guardian entered emergency and swept, but textureCandidates=0
  root cause: live textures did not refresh handle owner attribution before Guardian owner eligibility

SAFE after fix / final validation:
  Guardian entered emergency and performed bounded cursor sweeps
  reclaimed synthetic textures: 80
  reclaimed synthetic bytes: 335,544,320
  tracked texture bytes stabilized around 781,613,320 after the injector capped
  textureBytesPeak stayed below 870MB in the validation run
```

The result supports the bounded claim: Guardian can cap tracked, managed, reloadable, idle, Downfall-like texture growth under pressure. It does not prove recovery for raw GL, non-managed/custom texture data, active resources, or driver-only leaks.

Compatibility hardening after player startup reports:

- `LwjglApplication` must not directly link to newly added optional classes such as `GpuLeakInjector` or `GpuResourceGuardian`; it resolves them through cached reflection so older or partial runtime patch deployments do not crash with `NoClassDefFoundError`.
- Existing core classes (`GLTexture`, `GLFrameBuffer`) must not expose newly added helper classes in public Guardian method signatures. Guardian sweep stats use a primitive `long[]` shape to avoid hard-linking those core classes to `GpuResourceSweepResult`.

## Important existing code locations

- `patches/gdx-patch/src/main/java/com/badlogic/gdx/graphics/GpuResourceGuardian.java`
  - Unified pressure state machine and bounded sweep orchestration.
- `patches/gdx-patch/src/main/java/com/badlogic/gdx/graphics/GLTexture.java`
  - Guardian texture reclaim path: `reclaimGuardianTextures`.
  - Budgeted Guardian restore path: `ensureHandleAvailableForUse`, `tryAcquireGuardianRestoreBudget`.
  - Current upload/downscale path: `uploadImageData`, `maybePressureDownscalePixmap`.
- `patches/gdx-patch/src/main/java/com/badlogic/gdx/graphics/glutils/GLFrameBuffer.java`
  - Guardian FBO reclaim path: `reclaimGuardianFrameBuffers`.
- `patches/gdx-patch/src/main/java/com/badlogic/gdx/graphics/GpuLeakInjector.java`
  - Debug-only synthetic validation injector; defaults off.
- `patches/gdx-patch/src/main/java/com/badlogic/gdx/backends/lwjgl/LwjglApplication.java`
  - Render-loop integration point for injector, Guardian, and GPU summaries.
  - Guardian/injector calls are optional reflective calls so older or partial runtime patch deployments do not crash with `NoClassDefFoundError`.
