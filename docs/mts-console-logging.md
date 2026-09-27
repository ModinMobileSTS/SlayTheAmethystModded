# Android MTS console logging

## Symptom and cause

A mod can catch an exception and print it on every game update without crashing. MTS's
`MessageConsole.ConsoleOutputStream` previously inserted every output fragment into a
line-wrapped Swing document and updated its caret synchronously on the printing thread.
The document had no effective size limit. Repeated errors therefore grew both retained
text and the cost of each subsequent output, blocking the LWJGL update/render thread even
when the MTS window was not the visible game UI.

`--skip-launcher` does not prevent MTS from creating that console: it clicks the launch
button automatically, whose callback installs the stdout/stderr tee.

## Fix

`app/src/main/java/io/stamethyst/backend/mods/MtsConsoleLogPatcher.kt` rewrites the four
`MessageConsole.redirectOut` / `redirectErr` overloads in the installed MTS jar:

- No-argument calls and null destinations leave the existing stream unchanged.
- Explicit non-null destinations become the corresponding standard stream directly,
  with no Swing wrapper or document updates.
- Output is not filtered, deduplicated or discarded. Both Log4j console messages and
  direct `printStackTrace()` output continue through the original BootBridge/stdout/stderr
  path into `latest.log`. Startup progress and fatal crash markers remain available.

The fix is applied **before game/Log4j initialization**, rather than swapping streams
after startup. Otherwise a ConsoleAppender can keep a reference to the old Swing tee.
The bundled source jar is not modified; only the Android-installed copy is patched.

`MtsLoaderCrashPatcher.ensurePatchedMtsJar(...)` includes the console class in its existing
jar rewrite, validation and idempotency checks. `ComponentInstaller` calls this even when
components are current, and invalidates the MTS startup cache when the jar changes.
The MTS update service uses the same patch path. Existing installs therefore receive the
fix without requiring a manual reimport, and warm patch caches do not retain the old code.

## Coverage and limitations

- `MtsConsoleLogPatcherTest` executes the patched bundled class, checks both overload forms,
  verifies stream identity (including cached references), and replays 1,000 exceptions:
  complete stack traces and fatal markers are retained without growing the Swing document.
- `MtsLoaderCrashPatcherTest` covers cold installation, upgrading an otherwise-current
  jar with an old console, and repeat installation without rewriting the jar.
- This removes a generic logging performance amplifier; it does not fix a mod's original
  exception or eliminate the remaining cost of constructing, formatting and writing it.
  BootBridge parsing, logcat mirroring and live log-file retention are unchanged.
- Android device FPS/CPU comparisons are still needed to quantify the improvement on
  specific devices; host tests do not establish phone frame times.
