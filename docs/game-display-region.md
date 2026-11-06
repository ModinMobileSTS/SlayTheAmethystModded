# Game display region

The Game settings and first-run setup replace the camera-cutout and bottom-edge switches with a four-corner display-region editor. Dragging a corner changes two boundaries while keeping the opposite corner fixed. Width and height are independent; the minimum is 50% on each axis. The editor keeps the device preview, selected and screen pixel dimensions, and action buttons; instructional paragraphs, margin readouts, cutout-source descriptions and the next-launch hint are not displayed. Changes remain a local draft until Save; Cancel/back/outside dismiss do not persist them. Saved regions apply on the next game launch.

`DeviceDisplayGeometry` uses the activity's maximum `WindowMetrics` (API 30+) or real display metrics (older releases), not the popup dimensions. It rotates the full panel and `DisplayCutout.boundingRects` into canonical landscape coordinates, renders the exact `DisplayCutout.cutoutPath` and reported rounded corners on API 31+, and falls back to the system's cutout bounding boxes on API 28–30. No opening is invented when none is reported. The game scene is illustrative, not a screenshot; panel aspect ratio, cutout placement, selected bounds and virtual-resolution letterboxing are derived from real geometry. The preview is proportionally fitted into the dialog, not a claim of physical 1:1 millimeter calibration.

`GameDisplayRegion` stores normalized left/top/right/bottom coordinates in `LauncherConfig` under `game_display_region`. Untouched installations retain legacy cropping until a region is saved. The editor derives its initial legacy selection from reported openings rather than substituting a hard-coded 90% scale. Saving clears both legacy switches, so no invisible second crop is added. Reset-to-default settings writes a fullscreen region.

`GameSessionConfig` passes the region to `RenderSurfaceManager`. The region determines both the physical surface bounds and the startup virtual canvas; fixed-ratio modes retain their existing letterboxing. Reverse landscape rotates the region by 180 degrees to keep the chosen area attached to the physical panel. Input continues to use the actual render view's coordinate transform.

Pure geometry, serialization and rotation tests are in `GameDisplayRegionTest`; preview gesture and cancel/save coverage are in `GameDisplayRegionInstrumentedTest`.

## Verification

```sh
./gradlew :app:testDebugUnitTest \
  --tests io.stamethyst.backend.render.GameDisplayRegionTest \
  --tests io.stamethyst.RenderSurfaceManagerPolicyTest \
  --tests io.stamethyst.GameSessionConfigTest
./gradlew :app:compileDebugAndroidTestKotlin
```

On a connected test device, run `GameDisplayRegionInstrumentedTest` with the Android instrumentation runner. Also visually verify a portrait-to-landscape preview against the physical camera, reverse-landscape rendering, and an asymmetric selection in both surface backends. API 28–30 can only approximate cutout contours; manufacturer-hidden or unreported openings cannot be reconstructed accurately. No camera permission is needed because this reads display geometry, not camera images.
