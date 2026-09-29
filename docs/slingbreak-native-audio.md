# SlingBreak optional native audio

## Why

The affected device reports 9,620 PCM frames as its minimum output buffer at
48 kHz (200.4167 ms). This matches the Web Audio `baseLatency` in the supplied
logs; changing `latencyHint` to `interactive` did not change it. This is evidence
about that WebView path, not a hardware latency floor or a speaker measurement.

## Integration

`SlingNativeAudioBridge` exposes a versioned capability to the bundled game in
both the standalone activity and boot overlay. The web code checks the capability,
not the UA, model, or manufacturer. Without it, the existing Web Audio graph is used.

`sling_audio.cpp` opens a float-stereo AAudio callback stream with low-latency
preference, first requesting exclusive sharing and then shared if opening fails.
It lets the device select the sample rate, requests two bursts, and rejects a
reported application buffer of 50 ms or more. The negotiated settings are logged;
buffer duration is **not** end-to-end output latency.

The first gesture starts asynchronous preparation. `audio-recipes.js` is the
single source of sound recipes for both backends. `audio-native.js` renders each
unique mono voice with `OfflineAudioContext`, at the native stream's rate, and
uploads bounded float PCM samples once. Existing Web Audio remains available
during preparation. Native playback begins only after the entire bank is ready.
The one-time switch stops existing Web Audio voices, so a sound already playing
can be cut short at that transition.

The callback mixes cached voices, equal-power stereo positioning, delayed notes,
the 105 ms / 18% feedback / 16% wet echo, and the existing limiter curve. Noise is
randomly generated once per session, as in the web path. DSP interpolation and
buffer boundaries differ; waveform-identical output is not guaranteed.

Sound triggers transfer only small commands. The callback performs no file I/O,
JNI, allocations or locking. A bounded queue, voice limits, and priority stealing
prevent unbounded backlog. Request throttling still uses `performance.now()`.

Both host entry points pause/flush on backgrounding and close on destruction.
Audio focus loss mutes and clears pending sounds. Page reloads reset the sample
bank. Disconnections are closed on the control thread; the next web request falls
back to Web Audio. Underruns increase the application buffer by one burst, capped
below 50 ms; if no further bounded increase is possible, playback falls back.
Native initialization/upload/start failures also fall back. A failed native
session is not retried until the page is reloaded.

## Packaging local changes

The app now fetches web sources by default. To include uncommitted sibling web
changes, add this argument to the usual build command:

```text
-PslingBreak.sourceDir=/home/apricityx/workspaces/slingbreak
```

The web source must include `audio-recipes.js`, `audio-native.js`, the updated
`audio.js`, and their script entries in `index.html`. No manual asset copy is needed.

## Diagnostics and verification status

With the existing audio-debug option enabled, `webview_diagnostics.log` contains
`native_audio_opened`, `native_audio_ready`, lifecycle/failure events, and a final
snapshot at close. Web JSON contains `native-ready`, `native-sound-enqueued`,
`native-fallback`, and `backend: native-aaudio` snapshots including xruns and drops.
Enqueued means accepted for rendering, not confirmed speaker output.

This implementation has been statically reviewed only. No builds, automated
tests, game runs, or device latency measurements were performed for this change.
Device verification still needs to establish actual audible latency, focus and
pause/resume behavior, sound fidelity, and safe fallback on route changes.
