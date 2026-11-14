# Experimental force high refresh rate

The refresh-rate mismatch dialog and **Settings → Performance → Rendering** (immediately below
mod caching) share a single, persisted, default-off **Force high refresh rate** switch.

The technique was inspired by [svtxvt/android-force-120hz](https://github.com/svtxvt/android-force-120hz)
(reviewed at `aba02dbdd016e18992bb079fb72d6a8c141d2e50`). That project's source is licensed
CC BY-NC-SA 4.0. No source code or assets from it are incorporated here; this is an independent
implementation of the continuously changing tiny-surface technique using Android public APIs.

`ForceHighRefreshRateController` creates a non-touchable, non-focusable application-panel window
with a one-physical-pixel translucent line along the foreground activity's bottom edge. The line
changes its very low opacity every VSync to discourage LTPO idle downscaling. Its own surface avoids
forcing the entire Compose UI or game renderer to redraw. The rate request uses the highest mode
at the current resolution, not a hardcoded 120 Hz or a mode at another resolution.

The controller handles only `LauncherActivity` and `StsGameActivity` (including its boot subclass);
refresh-rate diagnostics and utility activities are deliberately untouched to preserve probe baselines.
Enabling/disabling applies immediately to local
resumed activities; activities returning from the background reread the cross-process setting.
Pause, stop, destruction, detachment and hidden windows stop animation; pause also removes the
panel and cancels pending attachment. No accessibility service, overlay permission, global setting,
boot receiver or background service is added. Existing normal refresh-rate requests remain intact
when this workaround is off. The setting is included in settings search and exported diagnostics.

Restoring launcher defaults also persists `DEFAULT_FORCE_HIGH_REFRESH_RATE` (`false`). The reset
executor only writes the preference; its successful main-thread completion calls
`ForceHighRefreshRateController.refreshFromPreferences` to remove any current keep-active panel,
cancel its frame callback and notify both switch instances immediately. This refresh does not
rewrite the preference. Returning activities still reread the stored value as usual.

This uses extra power and can increase heat. OEM refresh-rate policy, battery saver, thermal limits
and hardware restrictions still apply; enabling it does not prove the physical panel runs at 120 Hz.
Actual LTPO behavior, visual impact and device compatibility require separately authorized device
verification. Offline session tests cover enable/disable, repeated updates, foreground transitions
and attachment failure/retry; compilation does not establish device behavior.
JVM reset regressions cover the persisted default, deferred listener notification and refresh
without duplicate writes; a source-level wiring check guards the main-thread reset completion.

The mismatch dialog uses `RefreshRateSettingsDialog` with ordinary, non-scrolling content inside
`LauncherAlertDialog`, which already owns the scroll viewport. Adding an unbounded inner vertical
scroller crashes Compose measurement when the card is tapped (reported on Huawei Android 16).
The dialog's UI regression tests cover constrained height, larger fonts, reachable controls and
actions, and a disabled switch; they require a separately authorized runtime to execute.
