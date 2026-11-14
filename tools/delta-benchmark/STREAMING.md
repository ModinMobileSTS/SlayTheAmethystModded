# Streaming comparison (0.4, experimental)

**Final decision: use HDiffPatch**, confirmed after these experiments. See
[DECISION.md](DECISION.md) and versionable summary snapshots in [results/](results/README.md).
Provisional/pending statements below describe the stage when they were written;
they do not reopen the final engine choice. Outstanding production safety gates
remain valid.

Four opt-in engines: `native-{hdiff,xdelta}-stream{256,1024}-raw`.
All use file paths through JNI, file-backed old-data reads, bounded file writes,
no whole-file Java/JNI byte arrays, and no outer or inner compression. This is a
**raw algorithm/resource comparison**, not directly comparable to 0.3 deflate sizes.

HDiff: `create_single_compressed_diff_stream`, match block64B, one thread, step
cache256KiB or1MiB; apply with `patch_single_compressed_diff`. This is stream matching,
not the earlier suffix-sort/memory matching configuration. Its match index may grow
with file size: step-cache size is **not a total-memory cap**. Decoder rejects headers
requesting more step memory than the selected profile. Base/target/patch use stdio
positioned callbacks. Output may seek to rewrite its header during generation.

xdelta: incremental `xd3_encode_input` / `xd3_decode_input`, level6, Adler32, secondary
compression disabled. Target input window and source block/max-winsize are256KiB or
1MiB. Source blocks are read on demand; an update referring outside the current
encoding search window may produce a larger patch. Decoder performs random source
file reads as requested. This is not a guarantee of arbitrary-stream resume.

Resource controls:

- Each source file and produced patch/output is limited to128MiB. Synthetic preparation
  currently remains capped at16MiB; larger file preparation must be separately designed
  to avoid allocating full fixture arrays.
- A bounded output checks each write; resource-limit failures leave a private partial
  file, never a published artifact. No automatic cleanup/history transaction is claimed.
- xdelta allocator hooks track current/peak requested bytes and reject allocations above
  64MiB by default. This limits **xdelta callback allocations only**, excluding file
  buffers, JNI, STL, ART, allocator overhead and process baseline. It is not an RSS cap.
- HDiff does not expose an equivalent allocator in this adapter. It has a decoder
  step-cache bound, not a tested generator hard-memory ceiling. Do not equate this
  with xdelta's allocator limit. No RLIMIT_AS/process-memory-kill policy is installed.
- `adapterReadBytes`/`adapterWriteBytes` count native callback bytes, not physical I/O,
  page-cache misses or storage traffic. Rewritten header bytes count more than once.
  No raw temporary patch is created in these no-compression profiles; final private
  patch and candidate remain on disk. No peak physical-disk measurement is claimed.
- Existing host120s timeout is not a cooperative native deadline. Large/hostile-input
  CPU bounds, ENOSPC and interrupted publication remain outside current verification.

Measurement uses the existing separate-process phases,10ms RSS sampling, seeded
random schedule and reported-temperature gates. Synthetic data, actual JAR raw pairs
and profile changes must be reported separately. First batch is16MiB insertion,
quarter rotation, sparse and unrelated, plus two real raw JAR updates, all four
profiles×three repeats (72 pairs). Results are not claimed before completion.

`limits` phase (prepared/generated run token required) is untimed: force output cap1B
for apply and generation, then xdelta allocator cap1B, and finally rerun application
normally. Validate rejection, capped partial output size, unchanged base/patch and
byte-exact successful retry. A passing injected low cap is not a real disk-full test.

Build uses existing native pins from NATIVE_SOURCES.md. APK is independent and only
installed with target-device authorization. No production Delta API is changed.

## First completed batch and resource probes

`agent-tmp/delta-stream-pilot.log`:72/72 verified pairs, no failures. HDiff's
16MiB middle insertion raw patch78B, generation62.84ms (256KiB) /64.40ms (1MiB),
apply190.18/186.09ms. Quarter rotation47B, sparse20516B, unrelated16777251B.
The two HDiff step profiles produced the same sizes on these samples.

Initial xdelta profiles couple source search horizon to the input/source block size.
These configurations performed poorly:16MiB insertion7--8MiB patches, rotation
~16MiB. This is **not a general xdelta streaming conclusion**. Inspecting the pinned
implementation confirms `source.max_winsize` controls source matching horizon and
hash table sizing, independently of on-demand source-block I/O.

Additional profile `native-xdelta-stream1024-src32m-raw` retains1MiB input/source
buffers but uses a32MiB search horizon. It must be compared separately and is not
equivalent to increasing the I/O block allocation to32MiB. With allocator hooks,
its larger index must still fit the64MiB xdelta-only budget. A new54-pair batch
compares it to both HDiff profiles; no results claimed until completion.

Resource probes completed before the search-horizon variant: all four original
profiles passed `prepare/generate/limits` at1MiB, evidence
`agent-tmp/delta-stream-limits/`. HDiff: output limit for generation/application
and byte-exact normal retry (2 injected failures/profile). xdelta adds allocator
cap1B rejection (3/profile). Total10 injected limits enforced, base and original
patch unchanged. These are targeted adapter tests, not fuzzing or physical ENOSPC.
Probe APK contained allocator hooks; the earlier72-pair timing APK did not. Do not
claim those original72 observations include allocator peak/cap data.

## Completed budget batch

`agent-tmp/delta-stream-budget.log`:36 verified pairs,18 failed pairs. All failures
are xdelta1MiB I/O /32MiB source search generation, across all six samples×three
repeats, reporting `out of memory` with the64MiB callback budget enabled. This is
an adapter-budget failure, not an Android process OOM; HDiff's36 pairs all pass.
Successful-pair summaries intentionally contain no performance results for those
failed xdelta jobs. The first error did not report rejected request bytes, so do
not infer exact required memory from the message alone.

HDiff16MiB insertion medians: step256KiB78B/69.99ms generation/193.50ms apply;
step1MiB78B/69.93ms/212.71ms. Maximum sampled process RSS across successful HDiff
observations117604KiB (~114.8MiB). Callback reads up to50852096B (~48.5MiB) in
generation, about3× a16MiB file, not physical disk reads. Step cache enlargement
does not provide a consistent speed or patch-size benefit in this corpus.

Follow-up profiles keep1MiB I/O but test4/8MiB source horizons under the same64MiB
callback allocation limit. Allocation failure messages now include peak admitted
bytes, rejected request size and cap. A single-repeat18-job screening batch also
rechecks32MiB to diagnose limits; it is screening, not a three-repeat ranking.
Evidence will be under `agent-tmp/delta-stream-frontier/` when completed.

## Source-cache correction and incomplete screening

Frontier screening aborted at job6/18: src4MiB16MiB rotation generation exceeded
the120s host timeout. Only three successful pairs and two allocation failures
completed; no complete18-job result or comparative ranking is claimed. The timed-out
instrumentation process was explicitly force-stopped (only independent benchmark
package) before installing the next APK.

Failure diagnostics establish src32MiB requested67108864B while88328B was already
admitted under67108864B cap. This is expected budget rejection, not system OOM.
src8MiB insertion passed with27351304B callback peak /423B raw patch, but the
single-source-block adapter is unsuitable for other workloads: src4MiB real minor
JAR generation read173228376379 logical bytes, src8MiB patch JAR20659766111 bytes.
These huge rereads reveal alternating source-block requests, not equivalent
physical storage traffic; the old timings are adapter-thrash evidence, not credible
xdelta algorithm ranking. Logs are preserved under `agent-tmp/delta-stream-frontier`.

Corrected source-block adapter uses an8MiB bounded LRU shared across requests,
1MiB blocks loaded on demand, independent of the4/8MiB search horizon. **This cache
is outside the64MiB xdelta callback allocation budget**, so total controlled buffers
include up to8MiB source cache +1MiB input plus stdio/allocator/framework overhead.
File-backed access remains streaming and bounded; there is no full-base allocation.
Rerun54 pairs: HDiff256, xdelta-src4MiB, xdelta-src8MiB with three repeats, same
six fixtures; evidence `agent-tmp/delta-stream-cache/` when completed. No results
claimed in advance and no previous failed/timeout runs silently reclassified.

## Completed corrected-cache comparison

`agent-tmp/delta-stream-cache.log`: **54/54 verified pairs**, no timeout/failure.
The three profiles each passed all six fixtures×three repeats. Raw patch bytes
and generation/application median milliseconds:

| Fixture | HDiff step256KiB | xdelta source4MiB | xdelta source8MiB |
| --- | --- | --- | --- |
| 16MiB insertion | 78B /64.15 /188.47 | 423B /55.58 /201.13 | 423B /72.60 /202.10 |
| 16MiB rotation | 47B /66.44 /187.40 | 16777755B /6561.55 /201.85 | 4194750B /765.55 /201.66 |
| 16MiB sparse | 20516B /82.94 /191.01 | 29052B /139.36 /200.08 | 29052B /172.17 /201.26 |
| 16MiB unrelated | 16777251B /480.67 /195.33 | 16777747B /6645.91 /198.82 | 16777747B /7543.57 /198.54 |
| Real patch JAR | 307180B /17.61 /28.79 | 267845B /78.00 /32.65 | 267772B /73.97 /30.98 |
| Real minor JAR | 1424155B /41.29 /28.76 | 1320973B /294.88 /27.69 | 1320859B /279.49 /27.86 |

Source caching removed the pathological rereads: maximum logical adapter read in
generation33,554,469B (4MiB horizon) /54,525,952B (8MiB). The former hundreds-of-GiB
read counts and timeout must not be merged into corrected performance summaries.
Finite search horizon still loses long-distance matches; a cache alone does not
make a4/8MiB horizon equivalent to full-base matching.

Measured xdelta callback allocation peaks across this corpus:

- source4MiB generation20,127,848B (~19.20MiB), apply2,103,328B (~2.01MiB).
- source8MiB generation28,516,456B (~27.20MiB), apply2,103,328B.
- Excludes up to8MiB LRU,1MiB input, libc/stdio/ART/allocator overhead.64MiB cap
  was never exceeded by successful runs; no whole-process memory cap established.
- HDiff still has no equivalent allocator accounting/hard generation cap. Largest
  sampled RSS was115948KiB vs xdelta117000/111716KiB; these similar baseline-heavy
  observations do not establish an accurate working-set ordering.

Corrected xdelta source4/8MiB profiles also passed six injected limit checks total
(generation output1B, apply output1B, allocator1B), normal byte-exact retry, unchanged
base/original patch. Evidence `agent-tmp/delta-stream-cache-limits/`. This supplements
the earlier ten probes, not a physical disk-full or process-death test.

**Provisional recommendation:** HDiff's256KiB-step streaming configuration is the
strongest default candidate in the tested raw/resource-constrained workload, notably
movement and low-similarity generation cost. xdelta yields smaller raw real-JAR
patches here, but requires explicit source horizon/cache budgeting and is slower to
generate on these pairs. No universal winner: missing stream-mode compression,
more library/game/mod pairs, >16MiB streaming fixture preparation, HDiff hard-memory
enforcement and process/ENOSPC/fuzz testing prevent production acceptance.

## 0.5 streaming envelope and64MiB experiment

Additional engines `native-hdiff-stream256-deflate6` and
`native-xdelta-stream1024-src8m-deflate6` wrap the existing file-native adapters
with the same raw DEFLATE level6,64KiB codec buffer. Native generation first writes
an owned raw patch, then streams it into the compressed package; apply streams
decompression into an owned raw patch and invokes the native file applier.
Thus there are **two passes and temporary disk**, not an integrated decoder pipeline.
Temporary patches are capped at128MiB and removed on normal/exception return;
process death may leave them, and no startup cleanup/recovery protocol is claimed.

Timing includes raw patch writing/sync, compression/decompression, candidate sync
and cleanup. `adapterReadBytes`/`adapterWriteBytes` remain **native-only counters**,
excluding Java codec I/O. `logicalOutputBytes` means the native output (raw patch
on generation), not compressed distribution size; use `patchBytes` for that.
No aggregate physical-space cap or disk high-water instrument is added here.
At most a128MiB extra raw patch can be live under this adapter's limit, in addition
to base/target/package/candidate and normal process overhead.

`FileCorpus` now prepares up to64MiB using64KiB buffers instead of full-file arrays.
JVM oracle checks ensure random bytes and six mutations match earlier array fixtures
across buffer boundaries. Old array native engines are still limited to16MiB;
do not select them for the64MiB run. All measured input hashes are unchanged in role.

After checking ~9GiB available on the authorized device, started20-pair screening:
two compressed stream engines × (64MiB insertion/sparse/unrelated + two real raw
JAR pairs) × two repeats. Only two repeats, not a stable tail-latency ranking.
Evidence `agent-tmp/delta-stream-large/` and `.log` upon completion. No advance
claim of success; future64MiB rotation is separate. Process baseline RSS is sampled,
and64MiB xdelta callback allocation cap remains enabled independently of LRU/codec.

Verification so far: JVM5/Python9; lint explicitly excluded due offline artifacts.
Hard HDiff generator-memory enforcement, process interruption, real ENOSPC, corrupt
patch fuzzing and exact ZIP reconstruction remain outstanding, not silently accepted.

### Completed64MiB compressed batch

`agent-tmp/delta-stream-large.log`:20/20 verified pairs, no timeout or failures.
Two repetitions only; medians here are midpoints of two samples, not stable p50/p95
estimates. All targets restored byte-for-byte and bases unchanged. Package B /
generation ms / application ms (including temporary patch/codec/sync/cleanup):

| Fixture | HDiff stream256 +deflate6 | xdelta source8MiB +deflate6 |
| --- | --- | --- |
| 64MiB insertion | 39 /326.55 /648.83 | 576 /217.86 /679.60 |
| 64MiB sparse | 3727 /542.58 /801.95 | 5469 /707.00 /699.59 |
| 64MiB unrelated | 67129374 /5927.95 /1414.20 | 67131520 /31041.13 /1471.04 |
| Real patch JAR | 276503 /44.24 /42.92 | 266721 /96.85 /45.91 |
| Real minor JAR | 1334725 /115.25 /57.84 | 1317967 /333.06 /52.95 |

Largest xdelta callback allocation peaks remained28,516,456B generation and
2,103,328B apply under64MiB cap, plus separate bounded source/codec buffers.
HDiff lacks corresponding allocator instrumentation, so no hard-memory claim.
Max sampled process RSS HDiff123852KiB (~121MiB), xdelta112108KiB (~109.5MiB);
do not interpret differing baseline-heavy RSS or sampled peaks as exact working
sets. Constant battery24.2C/thermal0 telemetry limitation persists.

HDiff remains preferred for the tested bounded-file streaming default, but xdelta
is faster on insertion generation and sparse application, and packages the two
raw real JAR updates smaller (~3.5% /1.3% versus HDiff). Both unrelated packages
exceed the64MiB target size slightly; HDiff generation5.93s vs xdelta31.04s.
No64MiB compressed replacement baseline was rerun in this batch, so no direct
replacement latency claim. Real minor-JAR delta savings remain marginal; selection
should account for transfer savings vs CPU/temp-space cost rather than always diff.

No jobs remain running after this batch. Outstanding production gates remain
HDiff hard generator-memory enforcement, robust process timeout/interruption cleanup,
real ENOSPC/fuzzing, more independent real version families and exact archive
reconstruction. These results support a provisional configuration, not production
activation or a claim that all resource limits have been solved.
