# Android delta benchmark — experiment history

Historical milestone notes below retain the conclusions available at the time.
**Final decision: use HDiffPatch**, confirmed after the experiments. See
[DECISION.md](DECISION.md); earlier “no final selection” statements are historical,
not the current project decision. Current instructions are in [README.md](README.md).

Standalone APK `io.stamethyst.deltabench`; not a launcher dependency and no production
Delta entry point is changed. Only app-private `files/runs/<UUID>` is written. No network
permission, user mod access, shared-storage access, or production publication.

## Current milestone

The smoke harness compares existing **JVM ENDSLEY/BSDIFF43 + raw DEFLATE level 6**
against **full replacement + the same compression**. It compiles the existing Java
algorithm subset via build-generated sources, not the archive Kotlin APIs. Minimum
match length 16; heap suffix array. Existing upstream attribution is packaged in assets
from `archive-delta-generator/LICENSE.archive-patcher` and `UPSTREAM.md`.

Version 0.2 also integrates **native BSDiff, HDiffPatch and xdelta3**. Exact source
pins, licenses, build flags and adapter details are in [NATIVE_SOURCES.md](NATIVE_SOURCES.md).
Run `python3 tools/delta-benchmark/prepare_native.py` before building. All candidates
use outer raw DEFLATE level 6; native internal compressors are disabled.
This pilot cannot select an algorithm. APK version is a harness version, not an
upstream version pin. The APK is arm64-only.

## Offline verification/build

```
./gradlew --offline :tools:delta-benchmark:testBenchmarkUnitTest :tools:delta-benchmark:assembleBenchmark
python3 -m unittest discover -s tools/delta-benchmark -p 'test_*.py'
```

`benchmark` inherits release settings, is non-debuggable, unminified and signed with
the local debug key, deliberately separate from launcher release signing.
If lint artifacts are unavailable offline, APK packaging can explicitly exclude
`:tools:delta-benchmark:lintVitalAnalyzeBenchmark`, `:tools:delta-benchmark:lintVitalReportBenchmark`
and `:tools:delta-benchmark:lintVitalBenchmark`. Report that lint was **not run**;
do not claim a full lint-verified build.

## Authorized device smoke

Ask for the target device and independent APK install authorization first. Use only
workspace local_adb/sts; never global adb. Check model before installing. Commands
below are examples, not automatic device deployment tasks:

```
python3 -m scripts.tools.local_adb adb -- shell getprop ro.product.model
python3 -m scripts.tools.local_adb adb -- install --no-streaming -r tools/delta-benchmark/build/outputs/apk/benchmark/delta-benchmark-benchmark.apk
python3 -m scripts.tools.local_adb adb -- shell am instrument -w -r -e engine jvm-bsdiff43-deflate6 -e size 262144 -e repeats 3 io.stamethyst.deltabench/.BenchmarkInstrumentation
```

Run `replace-deflate6` in a separate invocation. Every invocation starts a fresh
instrumentation process, but fixtures/repetitions within it share that process.
Both generation and application run **on Android**; inputs are materialized from disk
inside generation timing. Each fixture has one recorded warmup (`iteration=-1`)
and three measured iterations by default. Size is restricted to 1 KiB..4 MiB;
repeats to 1..10. Fixed Java Random seed and five synthetic random-byte fixtures:
identical, 37-byte head/middle insertion, sparse mutation, unrelated bytes.

Save stdout to `agent-tmp/` (it contains all JSON rows), then:

```
python3 tools/delta-benchmark/report.py agent-tmp/delta-benchmark-jvm-device.log agent-tmp/delta-benchmark-replace-device.log --output agent-tmp/delta-benchmark-pilot
```

No `run-as` or debuggable build is necessary for export. Report JSONL is also retained
privately on-device at the final `report` path. Failures return a non-success
instrumentation code; always inspect that code, not only the host adb exit code.

## Measurement boundaries

- Generation includes compression, file I/O and patch fd.sync; application includes
  decompression, candidate writing and fd.sync. Verification is timed separately.
- Candidate and unchanged base are compared byte-for-byte after every iteration;
  their SHA-256 identities are recorded. Output length is bounded during application.
- `pssAfterKb` is a post-verification sample, **not peak memory**.
  `vmHwmKb` is cumulative process RSS high-water (including generation, warmup,
  fixture preparation and verification), **not application-only peak memory**.
- `workBytesAfter` is logical bytes in four files after execution, **not peak disk
  usage, physical allocated space or total I/O**.
- Current pilot uses fixed ordering, hot-cache repetitions, no thermal gate, no
  CPU/energy metrics, no randomization, no engine subprocess timeouts and no
  phase-specific memory sampling. Results are smoke observations, not rankings.
- Android filesystem/process behavior is genuinely exercised, but interruption,
  low-disk, malformed-input fuzzing and recovery have not been tested on-device.
  JVM tests cover round trips, insertion fixtures, truncated streams and output limits.

## Follow-up before selection

1. Extend the pinned native adapters with phase-isolated generation/application
   and review additional codecs/licenses if enabled.
2. Add parameter/codec configurations, fair algorithm-only and delivery tracks.
3. Expand seeded corpus (deletion, movement, repetition, sizes) and real old/new
   version pairs with source/license/hashes. Keep third-party corpus ignored.
4. Randomize batches, add temperature/thermal logging, process timeouts and
   phase-specific memory/temporary-space observations; separate apply and generation.
5. Run broader safety/admission cases and larger samples before any production decision.

Native integration is complete for the shared-envelope configuration;
tuned codecs and further parameter variants remain future work. Small on-device
safety probes are available with `-e safety true`, separately from timed runs.

## 2026-11-14 pilot

M2007J3SY, Android 10/API29, arm64-v8a. Independent benchmark APK installed with
explicit approval; launcher untouched. Five 256 KiB synthetic fixtures per adapter,
one warmup + three measured iterations. Raw evidence lives in
`agent-tmp/delta-benchmark-{jvm,replace}-device.log`; summary under
`agent-tmp/delta-benchmark-pilot/`. No claim about a winning algorithm.

## 2026-11-14 native pilot (final APK 0.2)

Same authorized M2007J3SY, API29/arm64. Reinstalled only this independent APK;
all five candidates rerun on the same five 256 KiB fixtures, warmup1 + measured3.
**100 round trips verified byte-for-byte**, including warmups; base unchanged.
Separate safety invocations passed 14 probes: each delta engine rejects/detects
truncation, output-size mismatch and wrong base; replacement checks only the first
two (it deliberately does not require a base).

An initial safety expectation incorrectly required full replacement to depend on
base bytes. Corrected that test expectation, then rebuilt/reinstalled and reran
all five safety + timed invocations. No timed failure was hidden.

Head insertion results (median of 3, application includes sync):

| Engine | Patch bytes | Generation ms | Application ms |
| --- | ---: | ---: | ---: |
| JVM BSDiff | 308 | 357.29 | 8.97 |
| Native BSDiff | 313 | 31.06 | 9.08 |
| HDiffPatch single | 32 | 14.54 | 7.96 |
| xdelta3 | 31 | 6.70 | 8.35 |
| Replacement | 262261 | 15.29 | 8.44 |

Middle insertion patches: JVM314 / nativeBSD322 / HDiff38 / xdelta33 bytes.
Unrelated random data: all delta packages slightly exceed compressed replacement;
there is no benefit in always forcing a delta. xdelta generates fastest among the
tested delta configurations in this pilot, but small fixed-order hot-cache synthetic
samples cannot determine the best production algorithm. Application times are close
and strongly include common file sync/JNI costs; no memory ranking is inferred.

Final evidence: `agent-tmp/delta-native-<engine>-{timed,safety}.log`,
`agent-tmp/delta-native-pilot/{observations.jsonl,summary.json}` and
`agent-tmp/delta-benchmark-native-build.log`. JVM3 and Python3 tests pass.
APK packaging explicitly excluded offline-unavailable lint tasks; lint not run.
Native malformed-input fuzzing, device process-kill/low-disk, thermal/randomized
measurement, real version pairs and larger sizes remain unverified.

## Phase-isolated scale experiment (0.3)

```
python3 tools/delta-benchmark/prepare_corpus.py
```

This explicitly stages **actual README versions** from local Git commits
`444ca4feb6c01266f4a829287d5b0f772e43402b` and
`ba538b93a853cb9adf529f3157f5031f9f581255`. Their source manifest and hashes are
included in assets and result rows. This is a real text update, **not a real JAR or
binary version pair**. No suitable local old/new binary pair was identified in this
iteration; such corpus is still required for production selection.

Build/install the standalone APK under the same explicit device authorization rules.
Then run the opt-in runner (it never installs anything):

```
python3 tools/delta-benchmark/run_phases.py --model M2007J3SY --output agent-tmp/delta-phase-batch --repeats 3
```

Defaults: all five engines, 1/4 MiB, middle insertion, middle deletion, quarter
rotation, sparse edits, plus the one real README pair. Fixed seeded randomized job
order, three fresh-process repeats, **no warmup**. Every pair runs prepare, generate
and apply in three separate instrumentation processes. Thus application memory no
longer includes generation. Failed jobs are logged in `failures.json`, not silently
dropped into successful aggregates. Summary contains only verified pairs; inspect
failure counts alongside it. 120-second host timeout per invocation aborts a batch,
but is not an on-device execution deadline/kill guarantee.

Temperature observations: battery deci-C and Android thermal status before/after
each phase. Host gate waits for battery <40 C, at most five minutes; unavailable
temperature stops the batch. Severe-or-higher reported thermal status also stops it.
Battery temperature is **not CPU temperature**, and vendor thermal status accuracy
is not assumed. Cooldown does not normalize CPU frequency; full thermal control is
still absent. Charging, governor and device settings are never modified.

Memory: 10ms sampled process RSS during each operation, baseline RSS, and cumulative
VmHWM before/after. Capture happens before output oracle. This is **not peak PSS,
not allocation count or exact incremental engine memory**; short peaks can be missed,
ART/framework/native library loading and prior input identity hashing affect process
baseline/high-water. Native library loading is excluded from engine timing. Sampling
itself adds overhead, especially for short text operations.

Inputs are capped at 16 MiB in 0.3; array adapters remain non-streaming. xdelta's
temporary encode output buffer is now bounded to `2*input+64KiB` (capped at64MiB),
instead of touching a fixed16MiB buffer even for tiny inputs. **Do not merge 0.2 and
0.3 timing/memory rankings**. Native source pins and codec parameters are unchanged.

Known JVM candidate failure: added 16KiB quarter-rotation fixture generates a
BSDIFF43 patch rejected by the existing hardened shared decoder with
`non-progressing directive`. A regression explicitly records that incompatibility.
It is a failed selection case, not a successful round trip; the benchmark does not
modify shared archive implementation or relax decoder safety checks to hide it.

Rebuild tests: JVM4 and Python6 expected. On-device evidence/results are recorded
separately after the batch completes; a successful build is not proof of the batch.

### Completed 0.3 Android batch

`agent-tmp/delta-phase-batch.log` ends with **129 verified pairs, 6 failed pairs**
out of135 scheduled. All six failures are JVM BSDiff `move-quarter` application:
both 1/4MiB, all three repeats, `non-progressing directive`. The other four candidates
passed27/27 pairs each; JVM passed21/27. Generation-only results for failed pairs
remain in individual logs, not in the successful-pair summary.

4MiB middle insertion, median3, process-first execution including file I/O/sync:

| Candidate | Patch B | Generate ms | Apply ms |
| --- | ---: | ---: | ---: |
| JVM BSDiff | 4124 | 7627.15 | 88.44 |
| Native BSDiff | 4132 | 1098.22 | 104.93 |
| HDiffPatch | 38 | 316.92 | 80.34 |
| xdelta3 | 34 | 53.34 | 84.31 |
| Replacement | 4195621 | 170.88 | 72.77 |

4MiB sparse patch sizes: HDiff416 B vs xdelta864 B; generation326.78 vs60.39 ms;
application79.41 vs86.67 ms. Thus xdelta is not always smallest. Real README pair:
HDiff379 B /9.62ms generation /6.62ms apply; xdelta377 B /7.04ms /6.17ms;
replacement5988 B. These are not JAR measurements.

At4MiB the maximum sampled whole-process RSS across measured phases is approximately
HDiff111.5MiB vs xdelta169.9MiB (nativeBSD148.5, JVM142.7, replacement110.7).
Do not treat these as exact engine working-set sizes: Android process baselines
already approach111MiB, many operations report tiny RSS deltas, and 10ms polling
can miss short allocation peaks. xdelta showed ~57--59MiB sampled generation RSS
increase in these runs. More precise allocation/PSS and larger-file testing is
required before claiming HDiff's engine memory advantage at a given scale.

All reported thermal statuses were0; **every battery temperature reading was24.2C**.
Constant telemetry may be stale/vendor-limited. It proves only what Android reported,
not absence of CPU heating or throttling; no thermal-normalized ranking is claimed.

Evidence: `agent-tmp/delta-phase-batch/{schedule.json,observations.jsonl,summary.json,failures.json}`
and individual per-phase logs. No OOM/timeouts occurred in this batch. JNI buffering,
non-streaming adapters, shared outer codec, filesystem effects and absent real binary
version pairs remain selection limitations. Current evidence favors further evaluation
of xdelta3 (generation speed) and HDiffPatch (size tradeoffs/observed memory), not a
final production engine choice.

## Larger samples / genuine Maven JAR pairs

The next batch uses16MiB with xdelta/HDiff/replacement and adds unrelated data.
It must finish before installing an updated APK; never replace an APK during a batch.

`prepare_corpus.py --jar-base <path> --jar-target <path> --label <name>` explicitly
stages cached JAR pairs into ignored build assets, hashes original bytes and records
artifact filenames. Current pairs are kotlinx-coroutines-core-jvm1.8.0→1.8.1 and
1.8.1→1.9.0 from local Gradle's Maven cache (upstream Apache-2.0). No dependency
download, source fabrication or third-party artifact is committed. This is cache
provenance, not independent publisher signature verification. Do not distribute the
corpus-containing APK without reviewing all artifact notice/license obligations.

Each pair provides `real-coroutines-{patch,minor}-{raw,entries}`. `raw` measures exact
JAR bytes. `entries` sorts non-directory entry names and length-frames name/content
bytes, ignoring original compression/order/ZIP metadata. It is a deterministic
delta-friendly test stream, **not a ZIP transformation/rebuilding implementation**.
Entries results omit decompression/rebuild time and do not prove exact JAR recovery.
No entry is extracted to an archive-provided filesystem path.

Real-only invocation after rebuilding/installing with staged assets:

```
python3 tools/delta-benchmark/run_phases.py --model M2007J3SY --output agent-tmp/delta-phase-real --engines native-xdelta3-deflate6 native-hdiff-single-deflate6 replace-deflate6 --sizes --fixtures --real-fixtures real-coroutines-patch-raw real-coroutines-patch-entries real-coroutines-minor-raw real-coroutines-minor-entries --repeats 3
```

These pending experiments have no claimed results until their completed logs are
reviewed. Python corpus tests verify deterministic entry framing across ZIP order
and compression differences. The staging utility is for trusted local artifacts,
not an untrusted archive upload parser or a decompression-bomb sandbox.

### Completed 16MiB batch

`agent-tmp/delta-phase-16m.log`: **54/54 verified pairs, no failures**, with
xdelta/HDiff/replacement, five synthetic fixtures plus README, three repeats.
At16MiB middle insertion: HDiff38 B, generate1340.46ms/apply235.93ms;
xdelta67 B, generate197.25ms/apply245.73ms. Sparse edits: HDiff799 B vs xdelta1569 B.
Unlike the smaller insertion sample, HDiff now has the smaller insertion package.

Unrelated random bytes: HDiff16782371 B /20146.37ms generation; xdelta16782618 B
/7599.04ms; replacement16782336 B /614.40ms. Delta is not beneficial in this
case; avoiding expensive generation is a separate policy problem, not solved by
choosing a winner after generation. These data do not justify an unvalidated
similarity threshold or sampling heuristic in production.

Sampled whole-process RSS is variable across repeats: e.g. HDiff sparse apply
~206MiB, xdelta unrelated generation ~361MiB. Some larger-buffer operations still
show little RSS increase relative to baseline, consistent with resident/preexisting
heap or sampling effects. Therefore earlier small-sample low deltas must not be
interpreted as bounded/constant engine memory. All current native adapters buffer
entire inputs/output; stream-mode evaluation remains necessary for larger files.

Evidence: `agent-tmp/delta-phase-16m/{summary.json,observations.jsonl,schedule.json}`.
The real JAR batch is separate and must be reviewed before drawing corpus conclusions.

### Completed real JAR batch

`agent-tmp/delta-phase-real.log`: **36/36 verified pairs**, no failed pairs.
Three candidates × four real fixtures × three fresh-process repetitions.
All byte oracles and unchanged-base checks passed. Exact raw JAR target bytes
are restored; entries restore only the framed content stream, not a JAR.

| Pair / representation | HDiff bytes | xdelta bytes | Replacement bytes |
| --- | ---: | ---: | ---: |
| 1.8.0→1.8.1 raw JAR | 260963 | 267692 | 1417451 |
| 1.8.0→1.8.1 entries | 27752 | 63418 | 827438 |
| 1.8.1→1.9.0 raw JAR | 1305823 | 1312996 | 1340215 |
| 1.8.1→1.9.0 entries | 215419 | 369401 | 799779 |

Raw patch-update generation/apply medians: HDiff282.77/32.84ms,
xdelta96.70/41.35ms; minor-update raw: HDiff1037.77/42.94ms,
xdelta525.19/37.16ms. Entries patch-update: HDiff302.38/67.19ms,
xdelta100.69/75.37ms; minor-update: HDiff506.10/70.81ms,
xdelta429.04/73.27ms. Codec/JNI/materialization/sync costs included, ZIP
extraction/rebuilding excluded. No application-speed winner across all raw cases.

HDiff packages are smaller in all four real fixtures, especially uncompressed
entry streams; xdelta generation is faster in all four. Minor raw JAR savings
are only ~2.6% HDiff /2.0% xdelta relative to compressed replacement, unlike
~81% for the patch raw JAR. The two pairs belong to one library family and do
not establish general performance on game/mod updates. No final algorithm chosen.

Evidence: `agent-tmp/delta-phase-real/{schedule.json,observations.jsonl,summary.json}`
plus individual phase logs. The first real-only launch failed in CLI parsing
before device operations; empty-list argument handling was corrected with a
regression test, then all36 pairs ran. JVM4/Python9 verification remains passing;
offline lint was not run. Both current candidates should remain in evaluation;
test stream-mode resource usage and exact archive reconstruction before selecting
an Android production configuration.
