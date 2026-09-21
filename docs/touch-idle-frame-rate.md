# Frame-rate drop after touch inactivity

## Evidence and scope

Reported: roughly five seconds without touching the screen, rendering falls from
about 90 FPS to 55 FPS in Huawei Zhuoyitong; Xiaomi 12S Pro does not reproduce it.

**Latest verdict:** device/compatibility-environment refresh policy is the leading
explanation for the remaining symptom. The launcher Surface-vote cache bug is
fixed and reapplication is confirmed on-device, but the idle drop persists. The
precise policy owner (Android toolkit, container or Huawei host) remains unproven.
The tested window/Surface votes, View redraw, ARR opt-out and TextureView backend
are insufficient on the reported Huawei/Zhuoyitong environment;
this does not establish that every public API is ineffective or that only real
hardware input can restore high refresh. No device-verified fix is available yet.

### Host clarification and high-refresh test

The user confirms **native HarmonyOS (not Android/EMUI) + Zhuoyitong**, and that
setting the device to its high-refresh mode still reproduces the symptom. Do not
request that same settings test again. Exact HarmonyOS and Zhuoyitong versions
are still needed; the Android fingerprint in the bundle is not a substitute.

The APK sees Android 16 / API 36 and Android Display modes through the compatibility
environment. Those values describe the exposed API view, not proof that the host
uses AOSP SurfaceFlinger or the AOSP ARR implementation. Successful Android setter
calls/readback do not establish that a native HarmonyOS window received and kept
the corresponding frame-rate request. The exposed 90 -> 60 Hz transition remains
useful evidence, but is not a physical measurement of the panel itself.

The remaining investigation belongs at the Zhuoyitong-to-HarmonyOS presentation
boundary: does the host window continue requesting high-rate presentation without
input, and does the host honor that request? The current evidence cannot distinguish
missing/expired request forwarding, container throttling or host policy overrides.
No more APK-level keepalive or automatic backend experiment is justified by these
logs alone.

For context, OpenHarmony's public `native_vsync.h` documentation describes
`OH_NativeVSync_SetExpectedFrameRateRange` (API 20) for native VSync expected rates.
It also states actual rates can differ due to system constraints. This is a
host-side integration reference, not a demonstrated Zhuoyitong API or an Android
NDK drop-in fix. Its availability on this device and use by the container are
unknown; native host access/interop would need to be provided and verified first.

#### Vendor/debugging handoff

- Environment: Huawei PLR-AL00 / Kirin 9020, native HarmonyOS + Zhuoyitong; device
  high-refresh mode already tested and ineffective. Obtain exact host/container
  versions and power-saving state.
- Reproduction: actively animated game, target 90 FPS, stop touching for 10-20 s,
  then touch again. Both SurfaceView and TextureView reproduce. Several steady
  cycles switch the exposed display from mode 2 / 90 Hz to mode 4 / 60 Hz roughly
  three seconds after touch stops; renewed touch restores high refresh.
- Attach the 223248 (SurfaceView) and 001318 (TextureView) bundles. They show
  successful per-generation Surface votes, persistent window requests and ARR
  opt-out readback while the drop still occurs.
- Ask Zhuoyitong to correlate input/idle classification, its native window's
  expected frame rate, native VSync callback cadence and actual presentation
  cadence across the transition. Check whether guest frame-rate requests are
  forwarded and retained throughout continuous rendering.
- Capture host-side diagnostics using the tools supported by the actual
  HarmonyOS/container versions (for example DevEco/hdc/HiLog if available), or
  Zhuoyitong's own support export. Guest ADB/logcat alone may not expose this
  boundary; AOSP `dumpsys SurfaceFlinger` must not be prescribed as a HarmonyOS
  host command.

### Latest reproduction: 20260912-001318 (TextureView)

Source: `agent-tmp/sts-performance-logs-20260912-001318.zip`, extracted under
`agent-tmp/idle-fps-001318/`. The same Huawei PLR-AL00 / API 36 and
`1.6.0-dev7-surfacefix` build are reported. `launcher_settings.txt:57` and all
runtime samples confirm TextureView. Target remains 90 FPS, MobileGlues and
sustained performance disabled. The run includes gameplay/loading, whereas the
previous run sampled the main menu; compare the refresh transitions, not overall
render performance across the two scenes.

Paths below are relative to `sts/performance/`:

- `window/window_diagnostics.log:2-3`: generation 1 is available and the 90 Hz
  Surface call succeeds. It stays generation 1 through the run. The reproduction
  therefore does not require SurfaceView's post-boot surface recreation.
- Lines 90-94: at 00:11:41, touch idle 1687 ms / 90 Hz / 87 FPS; at 00:11:42,
  idle 2692 ms / 90 Hz / 74 FPS; at 00:11:43, idle 3693 ms / 60 Hz / 66 FPS;
  the next two samples are 57 and 60 FPS at 60 Hz.
- Lines 93-130 remain at 60 Hz through 42 seconds of touch inactivity. Window
  preference stays at 90 Hz / mode 2, surface generation stays 1, and
  `frameRatePowerSavingsBalanced=false` throughout.
- Earlier, lines 63-65 include a 1/0 FPS loading stall and a transition already
  visible at idle 2179 ms. Do not treat this loading interval as clean evidence
  for a universal three-second timer. The later steady transition brackets the
  threshold between 2.692 and 3.693 seconds for that cycle.

**Result:** TextureView does not bypass the symptom. Do not add an automatic
TextureView default based on this investigation. The remaining evidence points
to a refresh-policy behavior shared by both backends, without identifying whether
that policy is in Android, Zhuoyitong or the Huawei host. Both tested backends and
all preceding application-side experiments have failed to produce a confirmed fix.

**Follow-up completed:** the user tested the Huawei host's high-refresh setting
and confirmed native HarmonyOS + Zhuoyitong; the issue persists. The next evidence
is host/container versions and host-side presentation-policy diagnostics as
described above. App-only performance archives do not contain that decision.

### Earlier reproduction: 20260911-223248

Source: `agent-tmp/sts-performance-logs-20260911-223248.zip`, extracted under
`agent-tmp/idle-fps-223248/`. Device: Huawei PLR-AL00, API 36,
`1.6.0-dev7-surfacefix`, target 90 FPS, SurfaceView.
Paths below are relative to `sts/performance/`.

- `window/window_diagnostics.log:3,56`: generations 1 and 2 both successfully
  submitted 90 Hz with the same `surfaceIdentity=219236016`. The lifecycle cache
  fix works; a missing generation-2 API submission no longer explains the drop.
- Lines 72-74: generation 2 stays active while touch idle advances from 2276 ms
  to 3278 ms. The display changes from 90 Hz / mode 2 to 60 Hz / mode 4, then
  swap FPS settles at 56. Window preference stays at 90 Hz / mode 2 throughout.
- Lines 110-112 and 130-133 independently repeat the transition at 3475 ms and
  3278 ms of touch inactivity. Renewed touch restores 90 Hz (lines 125-126).
- `frameRatePowerSavingsBalanced=false` throughout those intervals confirms that
  combining ARR opt-out with the corrected Surface vote still does not help.
- The log also shows 90 Hz before this Activity has observed any touch (lines
  67-68, `touchIdleMs=-1`). Other windows/input routes can influence the policy;
  these samples must not be presented as proof that this Activity's touch is the
  only possible source of a refresh-rate change.

Keep the independent lifecycle cache fix and bounded diagnostic export. Remove
the unsuccessful `WindowFrameRatePowerPolicy` experiment and its tests; the UI
redraw experiment was already removed. Samples now say
`powerPolicy=platform_default` and still read the platform balanced-policy value.

**Comparison requested after this run:** on the same installed
`surfacefix` APK, change only the existing rendering-surface setting from
SurfaceView to TextureView and restart the game. This preserves the other policy
settings for a backend comparison. The later 001318 report above completes this
test and confirms failure; no automatic backend switch is shipped.

Cleanup verification: 92 tests passed across rendering, surface lifecycle policy,
session configuration and performance archive export. Debug APK
`1.6.0-dev7-idle-diag` builds successfully and `git diff --check` passes. This
build removes a failed experiment; it is not another claimed idle-FPS fix. The
TextureView comparison used the already-installed `surfacefix` build.

### Earlier reproduction: 20260911-204302

Source: `Desktop/sts-performance-logs-20260911-204302.zip`, extracted under
`agent-tmp/idle-fps-204302/`. The device reports `1.6.0-dev7`, Huawei PLR-AL00,
API 36, SurfaceView and target 90 FPS. Paths below are relative to
`sts/performance/`.

- `window/window_diagnostics.log:4`: ARR request reads `before=true after=false`.
  Subsequent samples keep `powerPolicy=opt_out_requested` and
  `frameRatePowerSavingsBalanced=false`; the API did not throw or read back true.
- Lines 58-61: touch idle 2316 ms -> 3316 ms changes display mode 2 / 90 Hz to
  mode 4 / 60 Hz, then swap FPS settles at 58-59. `windowHz=90 windowModeId=2`
  remains unchanged. Lines 64-65 show renewed touch restoring 90 Hz / 88 FPS.
  This independently confirms that the window ARR opt-out is insufficient.
- Lines 47-48 show the surface-related policy restore/reactivation at 20:38:32,
  matching `latest.log:2861-2862` (PBUFFER then a new WINDOW surface). The game
  deliberately hides/shows SurfaceView after boot to refresh its surface.
- There is only one positive Surface rate application in this log (line 2,
  before game initialization). The next Surface rate application is zero on
  pause (line 280). No 90 Hz reapplication appears after the boot-time recreation.

The lack of reapplication matches a concrete source bug: the cache used only
`System.identityHashCode(surface)` and FPS. AOSP `SurfaceView` owns a final Java
`Surface`, reuses it across native surface changes, and calls `surfaceDestroyed`
while `Surface.isValid()` can still be true. `SurfaceViewHost.currentSurface`
therefore need not return null during that callback, leaving the cache intact.
Recreation with the same Java wrapper and same FPS then skips `setFrameRate`.
The log cannot prove whether the host retained or discarded the old native rate
vote, but it confirms the missing submission and the source explains how it occurs.

### Earlier reproduction: 20260911-200648

`sts-performance-logs-20260911-200648.zip` identifies the same Huawei PLR-AL00,
reported API 36, running `1.6.0-dev6`. It contains the new window diagnostics.
Times below are device local time (+0800); paths are relative to `sts/performance/`.

| Time | Touch idle | Display | Swap FPS | Window / View request | Evidence |
| --- | --- | --- | --- | --- | --- |
| 19:59:55.338 | 2801 ms | 90 Hz, mode 2 | 87 | 90 Hz / 90 Hz | `window/window_diagnostics.log:63` |
| 19:59:56.339 | 3803 ms | 60 Hz, mode 4 | 65 (transition) | 90 Hz / 90 Hz | line 64 |
| 19:59:57.342 | 4805 ms | 60 Hz, mode 4 | 59 | 90 Hz / 90 Hz | line 65 |
| 20:00:08.353 | 2317 ms | 90 Hz, mode 2 | 88 | 90 Hz / 90 Hz | line 76 |
| 20:00:09.355 | 3319 ms | 60 Hz, mode 4 | 80 (transition) | 90 Hz / 90 Hz | line 77 |
| 20:01:12.439 | 49 ms | 90 Hz, mode 2 | 91 | 90 Hz / 90 Hz | line 140 |

The second transition brackets the timeout between 2.317 and 3.319 seconds after
the last delivered touch event. The 61 samples from 20:00:10 through 20:01:10 have
mean swap FPS 59.0 and all report 60 Hz. Six samples from 20:01:12 through 20:01:17
after renewed touch average 89.5 FPS at 90 Hz. Throughout both intervals,
`frameActivity=true` and `uiInvalidations` grows. Thus the failed attempt was
enabled and issuing redraw requests; the persistent mode vote was not withdrawn.

`performance_launch_audit.log:6-9,31-32` confirms target 90 throughout startup.
The exported settings have sustained performance disabled (line 54); the older
export below had it enabled, so this is not a controlled sustained-mode A/B test.

Slow-frame incidents during the long idle interval have median `renderMs=13.597`,
`swapMs=2.773`, `totalMs=16.487`; these are **only over-budget frames**, not all-frame
statistics. They do not establish that EGL swap alone blocks for 16.7 ms or prove
CPU/GPU downclocking. The refresh-mode and touch timeline is the stronger evidence.

`latest.log:2861` also reports a missing JVM `nativeIsSwappyEnabled()` method, so
`swappyEnabled=false` is a fallback result, not definitive native Swappy state.
This bridge-version issue does not explain why the Android display mode itself
switches with touch. Native Surface request results are now persisted too, so the
next export can confirm whether those calls succeed.

### Older export

The local `sts-performance-logs-20260911-171753.zip` identifies a Huawei PLR-AL00,
Kirin 9020, reporting Android 16 / API 36. It uses SurfaceView, a 90 FPS target,
sustained performance mode, and the performance overlay. The JVM log confirms
`targetFps=90` and `swappyEnabled=false`. Thus a startup target of 60 FPS and
Swappy's automatic pacing are not explanations for that recorded run.

The incident log contains only over-budget frames, with no touch timestamps or
actual display-mode history. It cannot establish the reported five-second
transition or distinguish host throttling from a display-policy change. The
export may also predate the most recent uncommitted keepalive attempt.

## Earlier uncommitted attempt

`DisplayRefreshRateController.resolveWindowRefreshPreference()` used to return a
mode ID only when the chosen mode differed from the current mode. After a
successful 60 -> 90 Hz switch, the next sync returned no ID and wrote zero into
`preferredDisplayModeId`. This field is a persistent preference, not a one-shot
switch command. The four-second keepalive could therefore withdraw the mode
preference just before the reported idle interval.

Keep the same-resolution mode vote for the entire foreground session, including
when that mode is already active. Clear it on pause/destroy as before. Preserve
the fallback to a refresh-rate hint when the environment only advertises 60 Hz
for a higher requested rate. Keep fractional-divisor mode preferences explicit.

Display change callbacks now also resynchronize refresh preferences when only
refresh rate changes; they do not resize or reconnect the rendering surface.
The former keepalive has been removed. Refresh preferences are synchronized only
on lifecycle, focus, surface and display-change events; there is no periodic
forced `Surface.setFrameRate()` call. This is an attempted fix for the device
symptom, not a confirmed Zhuoyitong fix.

## Failed dev6 attempt: View frame activity

The first follow-up tried to make native frame activity visible to ViewRoot.
The latest device reproduction above confirms that this did not stop idle
refresh-rate changes. The controller and its frame callback have been removed.

Android's adaptive refresh-rate documentation describes another control layer:
ordinary Views normally vote for about 60 Hz, touch temporarily boosts to High,
and a View only votes when it needs redrawing. Our native EGL thread renders to
the SurfaceView independently of ViewRoot. Keeping its Surface/window mode vote
does not provide UI frame activity. An analogous Huawei MatePad report also
describes high refresh returning only after touch; it does not establish which
mechanism Zhuoyitong implements.

The removed `SurfaceViewFrameActivityController` used this approach:

- Enable only for Huawei manufacturer/brand, reported API 35+, SurfaceView, and
  a finite target above 60 FPS. This is a device-family heuristic, not a claimed
  reliable Zhuoyitong detector. Xiaomi, older Huawei Android and TextureView do
  not run this frame callback.
- Set `View.setRequestedFrameRate(targetFps)` on the rendering View. A rate on
  its parent would not automatically propagate to child Views.
- On UI animation callbacks, read the existing native swap counter and invalidate
  the rendering View only when new native frames have been submitted. No input
  events are generated. The callback does not modify game rendering, reconnect
  the surface or submit repeated `Surface.setFrameRate()` calls.
- Cancel the callback and clear the View rate on focus loss, pause, surface
  destruction and teardown. A stopped renderer produces no extra invalidations.
  The polling callback remains scheduled while the surface is active, so resumed
  native output is detected without needing a touch event.

The native `gl_bridge.c` swap counter is now atomic because the UI thread reads
it concurrently with the EGL thread. Relaxed ordering suffices: only the count,
not other render state or buffer contents, is communicated through this value.

Keeping this failed workaround would add UI traversal/composition work without
fixing the reported device. No synthetic input workaround is included.

## Failed dev7 attempt: window-level ARR opt-out

Android's official ARR guide provides `Window.setFrameRatePowerSavingsBalanced(false)`
for applications with significant refresh-rate problems. The local SDK API database
confirms API 35 availability. In AOSP Android 16, the setter updates the window
attributes and dispatches the change; `ViewRootImpl.shouldEnableDvrr()` consults
that attribute. This is a policy opt-out, rather than another content frame-rate
vote. AOSP guards the API with a feature flag, so successful readback is not proof
that a compatibility host honored it.

`WindowFrameRatePowerPolicy` replaces the dev6 callback:

- Apply only on Huawei manufacturer/brand, reported API 35+, finite target above
  60 FPS, and a foreground, focused game with an available surface. Both surface
  backends use the window policy. This remains a heuristic, not a container detector.
- Capture the original balanced-policy value and request `false` once per active
  interval. Record before/after values and any exception. Do not retry every frame
  or every monitor sample if the host rejects the request.
- Restore the captured original value on focus loss, pause, surface destruction
  and teardown. Recapture the current policy after resume.
- Keep persistent window/Surface frame-rate votes. There is no recurring View
  invalidation, View frame-rate request or periodic refresh-rate sync.

The API changes only this game window. Higher-priority system policies, or a
Huawei/container policy outside Android's toolkit ARR, can still override it.
The 204302 reproduction confirms that this alone does not fix Zhuoyitong, and
223248 confirms failure with the independent Surface cache fix. The controller
has now been removed. The description above records the experiment, not current
application behavior.

## Current behavior

The launcher no longer derives or applies an FPS cap from the display refresh rate.
The FPS setting is a fixed launcher preference. The game runtime only receives the
selected cap and, when software pacing needs it, a read-only snapshot of Android's
currently reported refresh rate. No Window or Surface refresh-rate vote is submitted.

## Diagnostic persistence

The former refresh-rate vote diagnostics are no longer emitted. Performance exports
may still contain logs from older builds, but new builds do not create them.

## Device verification

1. Install the new build, retain the same renderer, mods, 90 FPS target, and
   device refresh-rate setting. Logcat capture is optional for these samples.
2. Use a repeatable animated scene, touch a harmless region, then leave the
   screen untouched for at least 20 seconds. Repeat three times. Avoid screen
   recording during the initial test because it can change composition policy.
3. Repeat on Xiaomi 12S Pro, and repeat after background/resume. Check that
   `windowModeId` stays at the requested mode while foregrounded and that there
   are no recurring mode-switch flashes or surface recreations.
4. If needed, inspect the normal runtime log for the fixed target FPS and the
   read-only active refresh-rate snapshot. New builds do not emit refresh-vote logs.
5. TextureView (001318) and the Huawei host's high-refresh setting have both been
   tested and fail. Collect native HarmonyOS/Zhuoyitong presentation-policy
   evidence instead of repeating either comparison or an app-only-log experiment.
6. Treat any old `surface ... requestHz=...` entries as evidence from a previous
   build, not as current application behavior.

- If the display changes after a lifecycle event while the latest window/Surface
  request remains high, the environment is overriding the application's preference.
- Correlate frame incidents with host-side display-policy diagnostics. Higher
  render time points to game work, scheduling or GPU calls; higher swap time points
  to presentation backpressure. Neither alone proves CPU/GPU downclocking.
- If TextureView sustains high refresh without touch while SurfaceView does not,
  investigate its composition path and only then consider a targeted default or
  compatibility setting; check latency, power and foreground/surface recovery too.
- If both backends fall to 60 Hz, compare the host's fixed-high-refresh setting
  with smart refresh, including any per-app control for the container. These
  settings may not exist or may be ignored. Collect host/container display-policy
  logs or a system trace if accessible; the current app-only bundle cannot name
  the policy implementation or prove CPU/GPU frequency behavior.

## References

- [OpenHarmony NativeVSync API](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkgraphics2d/capi-native-vsync-h.md):
  native expected-rate and VSync interfaces; API/version and host integration
  requirements apply. This is not evidence of Zhuoyitong's implementation.
- [AOSP SurfaceView.java, Android 16](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/view/SurfaceView.java):
  `final Surface mSurface`, `copySurface()` and `notifySurfaceDestroyed()` document
  wrapper reuse and a surface that remains valid during destruction callbacks.
- [Android frame-rate API](https://developer.android.google.cn/media/optimize/performance/frame-rate?hl=en):
  requests are not guaranteed, should remain stable when the platform chooses
   another rate, and should not be repeatedly resubmitted as a frame heartbeat.
- [Android adaptive refresh rate](https://developer.android.google.cn/develop/ui/views/animations/adaptive-refresh-rate?hl=en):
  View votes only occur on redraw; normal votes are usually 60 Hz; touch provides
  a temporary high-rate boost; the "Enable and disable ARR" section documents
  `setFrameRatePowerSavingsBalanced(false)`.
- [AOSP Window.java, Android 16](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/view/Window.java):
  the opt-out dispatches a window-attribute change, subject to feature flags.
- [AOSP ViewRootImpl.java, Android 16](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/view/ViewRootImpl.java):
  `shouldEnableDvrr()` checks the balanced-policy attribute. This describes AOSP,
  not a reverse-engineered Zhuoyitong implementation.
- [Huawei MatePad touch-dependent refresh report](https://github.com/ClassicOldSong/moonlight-android/issues/427#issuecomment-4072714522):
  a separate app/device has the same high-refresh-on-touch behavior. A later
  comment reports an accessibility-input workaround, which is not used here.
- [AOSP-derived RefreshRateSelector](https://github.com/LineageOS/android_frameworks_native/blob/lineage-22.2/services/surfaceflinger/Scheduler/RefreshRateSelector.cpp):
  separate touch-boost and idle-rate selection paths. This documents Android
  behavior, not Zhuoyitong's implementation.
- [SurfaceView device-specific report](https://github.com/TheWidlarzGroup/react-native-video/issues/4550):
  SurfaceView versus TextureView changes refresh behavior on some devices.
  This is an analogous report, not proof of the same Huawei bug.
