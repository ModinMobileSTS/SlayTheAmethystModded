# Native source pins / configurations

Sources are explicitly prepared with `prepare_native.py`, never fetched by Gradle.
Each checkout is unmodified and pinned; `pins.json` is packaged in APK assets and
recorded in measured rows. The source trees remain ignored under `build/native-vendor`.
Generated source checkouts can be recreated from the recorded upstream commits.

| Engine | Repository | Commit | License |
| --- | --- | --- | --- |
| mendsley BSDiff | https://github.com/mendsley/bsdiff | b817e9491cf7b8699c8462ef9e2657ca4ccd7667 | BSD-2-Clause |
| HDiffPatch | https://github.com/sisong/HDiffPatch | 06961361340085ddb2a551775dc402172b8ca67c | MIT; embedded divsufsort notices retained |
| xdelta (CMake version 3.2.2) | https://github.com/jmacd/xdelta | 5d936d2c30f78729375c3ae070248a05bef27bd2 | Apache-2.0 in this revision |

Do not extrapolate GPL terms from historical xdelta3 releases to this pinned source:
its LICENSE and compiled source headers state Apache-2.0. This is a source-level
inventory for an experiment, not a blanket legal audit of future configurations.
BSDiff LICENSE, HDiffPatch LICENSE and original embedded sources/notices, xdelta
LICENSE, and the pin manifest are packaged in APK assets under `native/`.

## Build / encoding choices

- Android NDK 27.0.12077973, CMake 3.22.1, arm64-v8a only, API26 minimum.
  CMake RelWithDebInfo with final target flag `-O3`, NDEBUG; no sanitizer, no LTO.
  All native adapters in one shared library; fresh instrumentation process per engine.
- Native BSDiff uses mendsley's in-memory suffix-sort generator and callback bspatch.
  Adapter adds ENDSLEY/BSDIFF43 signature and target length, with the raw body then
  compressed by Java DEFLATE. It is not the upstream CLI's bzip2 file envelope.
- HDiffPatch uses `create_single_compressed_diff`, no internal compression plugin,
  256 KiB patch-step memory, minimum score 4, big-cache false, one thread.
  Application uses `patch_single_compressed_diff`; multithreading disabled at build.
- xdelta uses `xd3_encode_memory` / `xd3_decode_memory`, compression level 6;
  DJW/FGK/LZMA secondary compression disabled, 64-bit window sizes, no CLI/armor.
- All five candidates use the same outer **raw DEFLATE level 6**. No tuned native
  compressors are included. This is the shared-envelope pilot, not a best-config
  delivery benchmark and not an uncompressed algorithm-only benchmark.
- Native generation/apply materializes arrays and copies through JNI. Timing includes
  these costs and file sync. JVM BSDiff application streams while native application
  buffers complete output: memory/access modes are intentionally reported, not equal.
- Inputs restricted to trusted generated smoke data. JNI length checks are not a
  sandbox; pinned bspatch has not been hardened/fuzzed for arbitrary hostile patches.
  No production decoder API, interruption recovery or random resume is promised.

Prepare/build:

```
python3 tools/delta-benchmark/prepare_native.py
./gradlew --offline :tools:delta-benchmark:testBenchmarkUnitTest :tools:delta-benchmark:assembleBenchmark
```

`--local-cache agent-tmp/delta-native` can reuse existing Git checkouts. No global
dependencies are installed. If offline lint is unavailable, see README for the exact
explicit task exclusions used in this environment.

Native engine names:

- `native-bsdiff43-deflate6`
- `native-hdiff-single-deflate6`
- `native-xdelta3-deflate6`

Use the README instrumentation command, substituting the engine. With `-e safety true`,
the run instead checks truncated patches, output-length mismatch, and wrong-base
detection (decoder rejection **or** harness byte-oracle rejection). Safety mode is not
timed; it does not imply that all codecs authenticate their base themselves.
