# SlingBreak legacy WebView native audio

This document records the retired web minigame's audio protocol, not the current
Godot integration. Current game entry points do not create a WebView audio host.
The Gecko engine, loopback audio/launcher bridge and their dedicated tests have
been removed; no Gecko transport is supported. The retained mixer/protocol code
and host-side tests below do not establish current Godot audio behavior.

## Why

The affected device reports 9,620 PCM frames as its minimum output buffer at
48 kHz (200.4167 ms). This matches the Web Audio `baseLatency` in the supplied
logs; changing `latencyHint` to `interactive` did not change it. This is evidence
about that WebView path, not a hardware latency floor or a speaker measurement.

## Integration

The former WebView host used `SlingNativeAudioBridge` to expose a v2 capability.
The web code checked the capability, not the UA, model, or manufacturer. Without
it, the existing Web Audio graph was used.

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

The former host entry points paused/flushed on backgrounding and closed on destruction.
Audio focus loss mutes and clears pending sounds. Page reloads reset the sample
bank. Disconnections are closed on the control thread; the next web request falls
back to Web Audio. Underruns increase the application buffer by one burst, capped
below 50 ms; if no further bounded increase is possible, playback falls back.
Native initialization/upload/start failures also fall back. A failed native
session is not retried until the page is reloaded.

## v2 compatibility fixes

- The web recipe catalogue owns sound names; native playback validates uploaded
  sample IDs instead of the obsolete 13-name list. Boss cues no longer invalidate
  the session. Banks support 512 samples / 32 MiB and 16 voices per request.
- `setState` applies continuous gain at every nonzero game volume. Separate
  effects/UI/music buses preserve dialog cues while paused, discarding gameplay
  voices, echoes and stale queued commands.
- Music supports bounded PCM caching, seven instrument buses, delayed notes,
  filtered mix, echo, fade and stop. Evicted buffers remain alive while queued or
  playing; final PCM reclamation stays on the control thread. Bus limits are
  64 effects, 16 UI and 48 music voices.
- `playBatch` accepts up to 96 independent requests and returns one result each.
- The former build-time web audio adapter and web minigame packaging have been
  removed. The retained host-side code is legacy protocol support, not a playable
  compatibility engine.

## Packaging status

Builds no longer fetch or package the SlingBreak web runtime, including from a local
checkout. The `slingBreak.sourceDir` override is no longer supported. The diagnostics
below describe the legacy web integration, not a bundled runtime in current APKs.
The Godot engine/PCK download path is independent and unchanged.

## Diagnostics and verification status

When the legacy host was active with audio-debug enabled, `webview_diagnostics.log` contained
`native_audio_opened`, `native_audio_ready`, lifecycle/failure events, and a final
snapshot at close. Web JSON contains `native-ready`, `native-sound-enqueued`,
`native-fallback`, and `backend: native-aaudio` snapshots including xruns and drops.
Enqueued means accepted for rendering, not confirmed speaker output.

Host-side regression commands:

```sh
python3 -m unittest scripts.tools.tests.test_sling_audio
# Historical protocol fixtures required; current builds do not generate web game scripts.
node --test scripts/tools/tests/sling_audio_protocol.test.cjs
```

The C++ tests exercise the production mixer with AddressSanitizer/UBSan: gain,
pause/UI isolation, mute/flush, music/stop and voice bounds. JS tests cover v2
preparation, Boss cues, partial gain, UI, music and fallback when the historical
web script fixtures are available. They do not require or test a Gecko bridge.
Past Kotlin/Java and Android native compilation checks do not replace device
verification of audible latency, focus/pause/resume, fidelity or route changes;
host tests do not prove speaker output.
