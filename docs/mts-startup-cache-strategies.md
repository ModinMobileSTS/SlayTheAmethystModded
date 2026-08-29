# MTS startup cache strategies

This document summarizes the startup cache and cache-hit-only optimization path for
ModTheSpire launches on Amethyst. The user-facing setting is `Enable mod cache` /
`启用模组缓存`; when it is disabled, the launcher does not build or use the MTS
patch cache and the cache-hit runtime optimizations stay inactive.

## Launcher and boot-bridge caches

### MTS classpath warmup cache

Implemented by `MtsClasspathWarmupCoordinator`.

Before the JVM game process starts, the main launcher process validates the imported
`desktop-1.0.jar`, ensures runtime components are installed, resolves the enabled mod
list, patches the game body if needed, and prepares the derived MTS classpath jars.
The cache marker is keyed by the desktop jar, ModTheSpire, BaseMod, StSLib, and the
GDX patch jar. A cache hit lets the launcher skip rebuilding these derived classpath
artifacts during later starts.

This warmup deliberately runs in the main process before launching the game process.
That keeps the old `:prep` process out of the launch path and lets the main process
release its preparation resources once the game process is running.

### MTS patch output cache

Implemented by `MtsPatchCacheCoordinator`, `MtsLoaderCrashPatcher`,
`MtsPatchCacheStore`, and `MtsPatchCacheBootstrap`.

On a cache miss, the patched ModTheSpire loader still runs the normal patching flow.
During that flow, Amethyst temporarily enables the MTS out-jar/package path, captures
the patched main game jar, writes modded package jars, merges compiled base-game patch
classes back into the cached jar, and then writes a marker file after all cache files
are valid.

On a cache hit, `MtsPatchCacheBootstrap.launchIfCurrent()` bypasses the normal MTS
patching flow and directly invokes the cached `PackageJar.PrepackagedLauncher` with:

- `desktop-1.0-modded.jar`
- the cached per-mod `*-modded.jar` package directory
- the original base jar as a fallback classpath entry

The cache key includes the desktop jar, ModTheSpire, BaseMod, StSLib, boot bridge,
GDX patch jar, the MTS mod file list content, and every enabled mod jar's path,
length, and last-modified timestamp.

### Cached MTS annotation database

Implemented by `MtsPatchAnnotationDbCache`.

The cache-miss patching flow serializes MTS's `Patcher.annotationDBMap` after patch
discovery. On cache-hit launch, the prepackaged launcher restores the annotation DB
for the cached mod package URLs instead of rescanning every mod jar for Spire patch
annotations.

If the annotation DB cache is missing or incomplete, boot bridge falls back to MTS's
original `Patcher.findPatches(...)` scan.

### Cached main-jar SpireEnum index

Implemented by `MtsPatchMainJarSpireEnumCache`.

MTS still needs `@SpireEnum` entries from the patched main jar when launching through
the prepackaged cache. The cache-miss path scans the patched main jar once and stores
the holder class names. The cache-hit path reads that small text index and applies
enum busting without rescanning the large cached jar.

If the index is missing or invalid, boot bridge falls back to scanning the patched
jar for `@SpireEnum` annotations.

## Runtime cache-hit optimizations

These patches live in `mods/amethyst-runtime-compat` and are gated by
`amethyst.mts.patch_cache.current=true` unless noted otherwise. They are intentionally
inactive for non-cache launches.

### Downfall ClassFinder scan cache

Implemented by `ClassFinderScanCachePatches`.

Downfall registers several character modules during `BaseMod.publishEditCards()`.
Each module creates a new `ClassFinder` and scans the same `Downfall.jar` to find
its card classes. Amethyst instruments those Downfall `autoAddCards()` call sites so
the first scan builds an in-memory `ClassInfo` list, and later modules reapply their
own original `ClassFilter` against that shared list.

This preserves each module's filter, ordering, and `ClassFinder` superclass/interface
lookup behavior. The same patch also falls back to the class-resource jar URL when
`ProtectionDomain.getCodeSource()` is unavailable under the cached MTS modded-jar
loader.

Disable with `amethyst.runtime_compat.class_finder_scan_cache=false`. Optional
profiling: `amethyst.runtime_compat.class_finder_scan_cache_profile=true`.

### Lazy custom card images

Implemented by `LazyCustomCardImagePatches`.

During cache-hit `BaseMod.publishEditCards()`, many `CustomCard` instances decode
their portrait images even though no card art is visible before the main menu. This
patch records the requested image path and skips the immediate `loadCardImage(...)`.
The real image is loaded before the card is first rendered in normal card render
paths.

Disable with `amethyst.runtime_compat.lazy_custom_card_images=false`. Optional
profiling: `amethyst.runtime_compat.lazy_custom_card_images_profile=true`.

### Lazy startup card descriptions

Implemented by `LazyStartupCardDescriptionPatches`.

During cache-hit card registration, thousands of startup-only card prototypes call
`AbstractCard.initializeDescription()`. This patch defers description parsing while
`BaseMod.publishEditCards()` is running, then initializes the description before the
card is rendered, previewed, copied, checked for `canUse`, or has upgrade text
displayed.

Disable with `amethyst.runtime_compat.lazy_startup_card_descriptions=false`.
Optional profiling: `amethyst.runtime_compat.lazy_startup_card_descriptions_profile=true`.

### Lazy card library screen

Implemented by `LazyCardLibraryScreenPatches`.

The base main menu constructs and initializes the card library screen during startup.
On cache-hit launches, Amethyst defers the startup-only `CardLibraryScreen.initialize()`
call and runs it synchronously the first time the player opens the card library.

Disable with `amethyst.runtime_compat.lazy_card_library_screen=false`.

### Fast cache splash

Implemented by `FastCacheSplashScreenPatches`.

This is not a data cache. It is a cache-hit-only startup timing optimization. Once
the game is already in the cached path, the patch moves the base splash screen
directly to a visible logo hold, writes the launcher splash event only after the logo
has rendered, accelerates the remaining fade-out, and writes the ready event after
`MainMenuScreen` is constructed.

Disable with `amethyst.runtime_compat.fast_cache_splash=false`. Tune with
`amethyst.runtime_compat.fast_cache_splash_visible_hold_seconds` and
`amethyst.runtime_compat.fast_cache_splash_fade_out_seconds`.

## Supporting non-cache optimization

`BaseModEditCardsTimingPatches` and `BaseModPostInitializeTimingPatches` are
not cache strategies. They are diagnostic wrappers used to identify which subscriber
owns the remaining startup work.

## Invalidation and fallback rules

The launcher invalidates startup caches when core runtime assets, game body patches,
MTS components, or the enabled mod set change. The patch cache marker also changes when
any enabled mod jar's contents change.

Jar identity is size plus a SHA-256 over the zip central directory — every entry's name,
uncompressed size, and CRC32 — not the file's last-modified timestamp. Both marker
builders (`MtsPatchCacheCoordinator` and `MtsClasspathWarmupCoordinator`) use this.

The reason is that size and mtime miss the case that matters most: a mod jar rebuilt in
place keeps its size and can keep or reset its mtime, and would then pass as unchanged,
producing a cache hit over mod bytecode that no longer exists. Hashing whole files would
also catch it, but it has to read every byte of every mod on each launch, which cancels
out the cache hit it is meant to protect. The central directory already carries a
per-entry CRC32 computed by the writer, so any content change moves it, and reading it
costs a few KB of seeks rather than the whole archive. The same change also stops a copy
or restore that only moves mtime from forcing a needless rebuild.

Files that are not readable zips fall back to size and mtime with a `nozip` tag, so a
corrupt or non-jar entry still contributes something instead of collapsing to a
constant. Both markers carry a leading `schema|` line so that adding or reordering a
field cannot let an older marker compare equal to a newer one.

The marker is computed once per launch, in `appendRuntimeProperties`. On a desktop
machine over a synthetic 47-jar / 71 MB load order with a warm page cache, the three
options measured at 0 ms for size+mtime, 15 ms for the central directory digest, and
54 ms for a full content hash. Absolute numbers will be higher on a phone and on a cold
cache, but the ratio is what motivated the choice: correctness against in-place rebuilds
for roughly a third of the cost of hashing whole files, against a cache hit that saves
seconds.

Two launch-path measures keep that per-launch cost down without weakening the marker:

- The mod-jar fingerprints fan out across a small fixed pool (capped at
  `min(availableProcessors, 4)`, mirroring the cache build's package-jar pool) because
  each one is an open plus a central-directory seek against storage that may be cold.
  Results are reassembled in input order, so the digest stays deterministic.
- The GDX patch jar is still hashed over its whole content — it is merged into the
  cached main jar, so archive-level shortcuts would miss metadata-only changes — but
  the digest is recorded in a sidecar under the patch cache directory, keyed by the
  file's size and mtime. The patch jar ships with the launcher and is replaced
  wholesale by the component installer, never edited in place, so unlike user mods its
  size+mtime identify the installed artifact well enough to reuse a recorded digest. A
  mismatch recomputes; a missing or corrupt sidecar falls back to the full read.

Cache reads are conservative:

- If the marker is missing or mismatched, MTS runs the normal patching flow.
- If the cached main jar is missing or too small, MTS runs the normal patching flow.
- If cached package jars are missing, MTS runs the normal patching flow.
- If annotation or enum sub-caches are missing, only that sub-cache falls back to
  the original scan.
- Runtime compat cache-hit patches check `amethyst.mts.patch_cache.current=true`,
  so they do not alter non-cache launches.

## Cache build parallelism

Every jar the cache build writes is an independent read-modify-write over a distinct
target file, sharing only the immutable entry snapshot. `writeFastCacheJars` therefore
puts all of them — the merged main jar and one task per mod package jar — on a single
fixed pool instead of writing them one at a time.

The main jar shares the pool rather than running ahead of it. It carries the whole base
game jar, so it is the single largest write, and overlapping it with the package jars is
where most of the wall-clock saving comes from.

Work that touches MTS statics stays on the calling thread: `createClassPath()`, the
`Loader.MODINFOS` reflection reads, and opening the MTS/Kotlin/core-patches/base-game
source streams all happen while building the task list, never inside a worker.

The pool is capped at `min(availableProcessors, 4, taskCount)`. The work is bound by
storage as much as by CPU, so a larger pool mostly thrashes the flash on the devices
this runs on. Override with `-Damethyst.mts.patch_cache.package_jar_threads=<n>`;
`1` forces the original serial path, running every task inline on the calling thread.

Two mods whose source jars share a file name resolve to the same target path. MTS's
serial loop let the later mod overwrite the earlier one; writing them concurrently
would instead interleave both into one file and corrupt it. Tasks are keyed by target
path so only the last mod per target is written, which preserves the original
last-wins result and removes the collision.

A worker failure is re-raised from `writeFastCacheJars`. By the time the fast path runs
it has already taken over MTS's output stream, so it must fail loudly rather than report
success over a partially written cache — `store` then falls back to the normal patching
flow and never commits a marker.

Three later stages of `store` use the same `runTasks` helper and the same thread cap:

- `createJsonEscapedPackageAliases` copies each apostrophe-bearing package jar to its
  `u0027` alias. Each alias is a full copy into a distinct target. The directory listing
  is snapshotted before the copies start, since the stage adds files to the directory it
  is scanning.
- `writeMetadataCaches` writes the annotation DB and the SpireEnum index concurrently.
  They read different sources — the annotation DB serializes `Patcher.annotationDBMap`,
  the enum index rescans the finished main jar — and target different files. The enum
  scan is the slower of the two because it walks the whole main jar. Both helpers
  swallow their own failures and delete their partial output, so neither propagates; the
  caches are optional and a miss only costs a slower cache hit later.
- `syncCacheArtifacts` issues the per-file fsyncs concurrently. Each one blocks on the
  device with no CPU work to overlap, so running them together lets the storage stack
  coalesce the flushes instead of paying one round trip per file. The two directory
  fsyncs stay last and serial — they are what makes the preceding file syncs reachable
  through the tree. A pool failure here falls back to a serial pass rather than skipping
  durability.

## Write durability

The marker is the commit point for the whole cache, so it must never become durable
before the artifacts it vouches for. `MtsPatchCacheStore.store` fsyncs the main jar,
the package jars, and both sub-caches, and only then writes the marker. Without that
ordering the filesystem is free to persist the small marker ahead of the large jars,
which would turn a power loss during a cache build into a silent cache hit on
truncated data — the 1 MiB size floor only catches truncation to near-zero.

Every cache file is replaced through `AtomicFileWriter`: write to `<name>.tmp`, fsync
the contents, rename over the target, then fsync the directory entry. `renameTo` over
an existing path is atomic on the Android filesystems the launcher targets; the
delete-then-copy path is a fallback for filesystems that refuse it. This removes the
window where a crash mid-write could leave a half-written sub-cache in place.

## Class loading under a cache hit

`ChildFirstJarClassLoader` loads the cached jars child-first so the patched copies win
over the unpatched ones still on the launch classpath. Two rules keep that from
breaking type identity:

- A small set of namespaces is parent-first, because a class loaded on both sides
  produces two distinct `Class` objects and fails with `ClassCastException` as soon as
  an instance crosses the boundary: the JDK namespaces, the endorsed XML/GSS packages,
  `com.badlogic.gdx`, `org.lwjgl`, log4j/slf4j, and `io.stamethyst.bridge`. ModTheSpire's
  own classes are deliberately *not* parent-first — the cached jar carries the patched
  copies and those are the ones the game must run. Matching is on a package boundary,
  so an unrelated mod class such as `javafx.Thing` is not mistaken for a JDK class.
  Parent-first lookups fall back to the child on miss, since these namespaces are not
  guaranteed to be complete in the parent: `io.stamethyst.bridge.FirstPersonGyroBridge`
  ships inside the gdx patch merged into the cached jar and has never been on the
  launch classpath.
- Resource lookup is child-first to match class lookup. Otherwise the parent's
  unpatched `ModTheSpire.jar` can answer for a resource whose class-side counterpart
  came from the cached jar. `getResources` returns child entries before parent ones.

The loader registers as parallel-capable and locks per class name. Locking the whole
loader instead would serialize every load performed by BaseMod, mod scanner threads,
and the GDX asset threads, and risks deadlock when a parent-first delegation
happens while another thread holds the parent's lock.

## Space precheck

`store` refuses to start when the filesystem cannot plausibly hold the result. The
estimate is coarse — three times the base game jar, floored at 256MB — because the real
output size is unknown until the jars are written, and the aim is only to reject the
hopeless case. The check runs *before* `store` enters its cleanup block, which is the
point of it: that block deletes the previous marker, the previous metadata caches, and
the previous package jars. Without the precheck, a build doomed by a full disk would
destroy a working cache, fail, fall back correctly, and then repeat the same doomed
build and its full cost on every subsequent launch. `amethyst.mts.patch_cache.min_free_bytes`
overrides the estimate; unknown free space is treated as permission to proceed, so an
unreadable filesystem does not silently disable caching.

## Fallback boundary on a cache hit

`launchIfCurrent` returning false means "the cache could not be used, run the normal
ModTheSpire path instead", and the patched `Loader.runMods` acts on that by falling
through into a full patch-and-launch pass. That answer is only safe before control
reaches the cached launcher.

`invokeCachedLauncher` calls `PrepackagedLauncher.main` synchronously, and that call runs
the entire game. Anything it throws — a mod crashing during initialization, a GDX failure,
a crash deep inside `DesktopLauncher.main` — used to be caught by the same broad
`catch (Throwable)` and reported as a cache miss. The fallback then re-ran the whole
pipeline inside a JVM that had already taken MTS static state, loaded the game classes,
and possibly opened a window. That second pass cannot succeed, and it buries the original
crash.

Failures raised from inside that invocation are now wrapped in `CachedGameLaunchFailure`
and rethrown instead of downgraded to a miss. Everything before it — building the URL
list, constructing the classloader, resolving the launcher class and its `main` — is still
a legitimate miss, because none of it has handed control to the game yet. The wrapper is a
`RuntimeException` subclass because the hook site calls `launchIfCurrent` through a `()Z`
descriptor and cannot declare checked exceptions.

A related point at the same layer: `MtsPatchAnnotationDbCache.restoreIntoPatcher` is called
purely for its side effect of populating `Patcher.annotationDBMap`, which the subsequent
SpireEnum pass reads. It used to also build per-mod patch sets and return them, and the
caller dropped the result on the floor. That was correct to discard — the only consumer of
patch sets is `Patcher.injectPatches`, which must not run on a hit since the cached jar
already carries the injected bytecode — but computing them cost four `Class.forName`
lookups and a set copy per installed mod on every cache hit for nothing. The collection is
gone and the method returns `void`.

## Cached jar compression level

Both fast-path writers deliberately set `Deflater.NO_COMPRESSION` on the jars they
produce. The trade is intentional: these files live in app-private storage and are
rebuilt whenever the marker changes, so the disk they cost is cheap and temporary,
while every class the JVM loads from them on a hit would otherwise pay inflater time.
The main jar carries the whole base game, so it dominates that cost.

## Compiled-class merging

When Javassist produces patched base-game classes, those bytes must end up in the
cached main jar ahead of every original copy of the class. Two paths provide that,
with identical content and precedence:

- Primary: `store()` collects the compiled classes before invoking MTS's
  `packageJar`, and the fast main-jar writer substitutes them inline wherever a
  matching entry appears while it writes, appending classes no source jar contains.
  No second pass over the archive happens. This matters because the merge rewrite
  used to re-read, inflate, and re-write the entire base-game-sized jar just to
  replace a handful of class entries.
- Fallback: when MTS's own package writer produced the jar instead (fast path
  unavailable or failed), the serial `mergeCompiledClasses` rewrite still runs over
  the finished jar. It remains stream-based on purpose: a raw zip-record-level
  rewrite would avoid inflating every entry but means hand-parsing local headers
  and rebuilding the central directory on the durability-critical artifact, a risk
  not worth taking for a path that now almost never runs.

In both paths the compiled-classpath bytes win over the OUTJAR snapshot bytes and
over every source jar copy; the two must never coexist in the output. When the fast
writer took over, `store()` reports `mergeCompiledClasses skipped, folded into fast
write` in its step log instead of paying the rewrite again;
`store_skipsMergeRewriteWhenFastPathTookOver` locks the skip in, and
`store_mergesCompiledBaseGameClassesIntoCacheJar` plus
`packageJarFastPath_foldsCompiledClassOverridesIntoMainJar` lock the content rules.

Historical note: the fallback rewrite once created its `ZipOutputStream` without
setting a level, silently re-deflating the entire main jar at the default level. It
now sets the same `NO_COMPRESSION` level as both fast writers, and
`store_keepsMergedCacheJarUncompressed` locks that in. Note that `NO_COMPRESSION`
still emits `DEFLATED` entries, just with stored blocks, so the entry method is not
a usable signal; the test asserts on compressed size using a highly compressible
payload instead.
