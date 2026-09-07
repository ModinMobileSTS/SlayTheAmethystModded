# Offline Device Spike Diagnostics

The collector runs on a connected Android device using only `adb shell` tools. Network access, root, and the Perfetto UI are not required. Analysis runs locally after the raw files are pulled.

## Collect

Start the game or the existing harness first, then collect for a fixed window:

```bash
python3 scripts/tools/device_spike_diag.py collect \
  --device 10.126.126.2:5555 \
  --package io.stamethyst:game \
  --duration 180 \
  --output agent-tmp/ram-saver-ab/device-diag-run
```

The collector records:

- `device-trace.pftrace`: scheduler, CPU frequency/idle, gfx/view, Dalvik, binder, disk, memory, thermal and power Perfetto trace events, written on-device and pulled after the window.
- `gfxinfo-after.txt`: Android `framestats` after the window.
- `process-samples.tsv`: process PSS, native/Dalvik/graphics PSS, heap and battery temperature/level/voltage samples.
- `game-log.txt`: recent game log including GDX texture and RAM Saver diagnostics.
- `device-info.txt`, `cpuinfo.txt`, `meminfo-final.txt`, `battery-final.txt`.

Perfetto writes directly to `/data/misc/perfetto-traces/` and exits after the configured duration. The completed file is then pulled and removed from the device, so collection does not need an online service and does not block on `atrace --async_dump`.

## Analyze

```bash
python3 scripts/tools/device_spike_diag.py analyze \
  --input agent-tmp/ram-saver-ab/device-diag-run
```

This writes `spike-analysis.json` and `spike-analysis.md`. It preserves frame number, room, action, tag, heap, flush and switch context for the largest frame-probe spikes, and reports scheduler/gfx/GC/texture-upload evidence from the same collection window.

## Interpretation

- Large `renderMs` with texture-upload lines and `glTexImage2D` in Arthas/GDX logs indicates synchronous texture materialization/upload.
- Large `renderMs` with many `sched_switch` or CPU-frequency changes but little Java/GDX work indicates scheduling or thermal throttling.
- Long `gfxinfo` frame durations with low Java render time suggests SurfaceFlinger/VSync or GPU queue delay.
- `System.gc()` stack samples plus JVM GC log pauses identify explicit GC; do not infer a GC pause only from a heap drop.

The Perfetto processor is optional. If `trace_processor_shell` is available, pass it through `TRACE_PROCESSOR_SHELL` or `--trace-processor` to run SQL against the raw trace. Some vendor builds expose FrameTimeline but deny kernel scheduler ftrace; the report records that as a limitation instead of treating missing `sched_slice` rows as zero CPU scheduling cost.

The report does not claim exact frame-level causality between atrace and frame-probe because their clocks and event IDs differ. Current Android shell permissions also commonly prevent battery current sysfs reads; temperature/voltage are retained but are not a power measurement.
