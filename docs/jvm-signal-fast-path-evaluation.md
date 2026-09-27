# JVM signal fast-path evaluation

Date: 2026-09-26. Status: **isolated proof of mechanism; not approved for game integration**.

## Decision

A special handler can avoid the expensive OEM signal-forwarding backtrace for a fault
it demonstrably owns. The device experiments support the performance mechanism, but
**the installed HotSpot and ART interfaces do not meet the strict fallback contract**:

1. `JVM_handle_linux_signal(..., 0)` can invoke a previous handler and return success
   for a fault HotSpot did not recognize. This was reproduced with the installed JRE.
2. `AddSpecialSignalHandlerFn` aborts on slot exhaustion. Symbol availability and a
   successful registration in a separate process do not make registration safe in
   the game process.
3. Actual HotSpot and ART have not yet been exercised together with this fast path.
   The timing tests use a simulated provider owning exactly one assembler PC.

Do not enable the commented `TRY_SIG2JVM` idea or wire the stock HotSpot handler into
ART's special-handler list. A future experiment remains default-off, and must skip
activation when either the runtime provider or safe registration is unavailable.
Changing the JRE alone would resolve neither ART's registration contract nor all
the lifecycle requirements below.

This evaluation added no production signal handler and made no changes to the
running game, installed runtime, APK, or mod jars. Experiments ran in disposable
processes on `adb -s 10.126.126.2:5555`; root access for the HotSpot-only probe was
used to read the installed runtime, not to modify it.

## Symptom and evidence

The reported symptom is several minutes of stuttering after restarting, even with
the launcher's default `TieredStopAtLevel=4`. The relevant observed run had repeated
caught NPEs in `SpireHelp$Gameplay.GetMapLocation` on the render path. The launcher
must tolerate arbitrary mods; a fix to that individual mod is not the objective.

The earlier live captures establish the following, separately from the probes:

- Roughly 15 fully traced NPEs/s coincided with 14–16 FPS. The OEM forwarding path
  spent roughly 55–56 ms per event producing a native backtrace, with CPU samples
  concentrated in `liblzma`/`libunwindstack`.
- On 2026-09-26 at **19:24:36.681 UTC+8 / 11:24:36.681 UTC**, the observed run
  transitioned from full-stack to stackless NPEs after 5,121 full-stack occurrences.
  Performance recovered to roughly 60–61 FPS despite about 120 NPEs/s, and the
  sustained same-PC signal storm disappeared.
- That transition is consistent with C2's hot-throw optimization. The actual VM
  flags and faulting nmethod compilation level were not read, so the attribution
  remains an inference. Neither the elapsed warm-up time nor the exception count
  is a universal threshold; restarting or hitting a new exception site can repeat
  the problem.

Verdict: **mixed, with strong evidence** — mod exceptions trigger an expensive
device/runtime interaction. C2 is an eventual mitigation, not an immediate cure.
The separate MTS Swing-console patch addresses another cost and does not establish
removal of this native backtrace cost. The installed product VM rejects
`-XX:-ImplicitNullChecks` as develop-only; it is not an available command-line fix.

### Device and runtime identity

| Item | Evaluated value |
| --- | --- |
| Device | Xiaomi `2206122SC`, Android 16, HyperOS `OS3.0.3.0.WOBCNXM` |
| ABI | Android AArch64 |
| Installed JRE | `1.8.0_482`, release source `5c0b36a630a4+` |
| Device `libjvm.so` SHA-256 | `4a3b92849da32420cf7b4d04353e6de49f7b2863cb21183a59c1532a680d13df` |
| Device `libsigchain.so` SHA-256 | `c6901ef11c70d77d62cdabdb888904abbe7fdae03fd5e8b404e38a56464b362b` |

The tested OEM binary backtraces on ordinary `sa_sigaction` forwarding after
unconsumed special handlers. The checked AOSP Android 16 source does not log a
stack on that ordinary branch; its other `LogStack` call sites must not be confused
with the OEM behavior. These measurements do not describe all Android devices.

## Experiments and results

### 1. Native process with a simulated first ART slot

`sigchain-probe` exercised the device's real `libsigchain`, but neither ART nor
HotSpot was loaded. A dummy first special handler declined every signal. The
experimental second handler recognized only a null-address `SEGV_MAPERR` at one
known `ldr` instruction and redirected that instruction to its own resume label.
This is a probe-specific ownership proof, not a Java-fault classifier.

All **12 cases** matched their expected outcomes: rejection contract, baseline,
enabled, disabled, missing provider, software signal, live disable, unknown native
fault, high-address fault, nested fault, abort, and full slots. The rejection case
included ten checks covering wrong signal/PC, software delivery, an MTE code,
tagged/high addresses, missing metadata/context, disabled state, and absent provider.

### 2. Fresh `app_process` with real ART

`SigchainArtProbe` loaded the JNI build of the same probe into a disposable ART
process. ART supplied its own first handler; the provider still owned only the
assembler instruction, and no HotSpot was loaded.

All **11 cases** matched their expected outcomes (the same matrix except the
standalone rejection-contract case). Each of the six nonfatal cases subsequently
caught 20,000 ART Java NPEs, completed an allocation loop and a `System.gc()` call,
and observed no additional fast-handler consumption. This verifies those Java
semantics in this workload, not the number of ART hardware faults or exhaustive
ART GC/safepoint coverage.

| Workload, eight simulated owned faults | Original forwarding | Fast handler enabled |
| --- | ---: | ---: |
| Native process, simulated ART slot | 81.727 ms | 0.409 ms |
| Real ART process | 148.601 ms | 0.032 ms |

These are single-suite batches, not warmed statistical benchmarks or predicted
game speedups. The enabled handler preserved `errno` and the signal mask. The
original forwarding path changed `errno` in these measurements; only the fast
handler's own rejection/consumption contract promises preservation.

In the real-ART live-disable case, eight events were consumed before disabling;
the next event used the fallback, and ART's subsequent Java workload completed.
This was a sequential toggle test, not a concurrent-disabling stress test.

### 3. Native-crash preservation in the probes

Both suites kept the process's original crash handling for fatal cases instead of
installing the nonfatal probe recovery handler. Debuggerd crash records were
matched to the probe PIDs and checked for the expected signal and backtrace.

| Case | Native probe PID | ART probe PID | Observed outcome |
| --- | ---: | ---: | --- |
| Unknown native PC, null address | 20935 | 24963 | SIGSEGV |
| Known PC, non-null address `0x10000` | 20947 | 25018 | SIGSEGV |
| Fault inside the special handler | 20959 | 25050 | SIGSEGV |
| Explicit abort | 20970 | 25085 | SIGABRT |
| Register beyond available slots | 20982 | 25109 | SIGABRT, `too many special signal handlers` |

The first four outcomes support crash preservation for these probes. The last is
**a reproduced integration blocker**, not evidence of graceful fallback. The
native runner checked child termination with `waitpid`; the ART runner checked
shell exit 139/134 and the associated debuggerd record. Some temporary-library
frames lack symbol names under the device's normal access restrictions, but the
records retain module offsets/backtraces. No SELinux policy was changed.

### 4. Installed HotSpot's handler contract

`hotspot-contract-probe` loaded the installed `libjvm.so` into a separate native
process with a 64 MiB maximum heap. It installed a counting previous SIGSEGV
handler, created HotSpot through `JNI_CreateJavaVM`, then called the exported
handler with synthetic software-signal and unknown-native-PC contexts.

Both calls passed `abort_if_unrecognized=0`. The observed output was:

```text
create_vm=0
case=synthetic-software-signal abort_if_unrecognized=0 returned=1 previous_handler_calls=1 context_unchanged=1
case=synthetic-unknown-native-pc abort_if_unrecognized=0 returned=1 previous_handler_calls=1 context_unchanged=1
destroy_vm=0 result=PASS
```

The previous handler really ran. `returned=1` does not mean HotSpot recognized the
fault, even when the supplied context remained unchanged. The source also calls
crash-protection logic before normal dispatch, which can perform a nonlocal exit,
and contains fatal/error paths before the final unrecognized check. Wrapping this
entry point with `abort_if_unrecognized=0` cannot provide a no-chain/no-abort/
normal-return contract.

The first HotSpot-only attempt failed during VM creation with
`assembler_aarch64.hpp:237`, `Field too big for insn`; its `hs_err` was retained.
Disabling heap pointer tagging **in the disposable process**, matching the app's
existing `allowNativeHeapPointerTagging=false`, allowed creation and the test to
finish. The successful process still printed a monotonic-clock warning. This
probe establishes the handler's chaining behavior, not full launcher equivalence
or timing behavior.

An earlier ART startup attempt with an overridden `LD_LIBRARY_PATH` exited 139
before probe output. Its exact cause is unconfirmed. Successful ART tests used
`app_process`'s normal library environment; they did not override that variable.

## Launcher lifecycle and possible integration boundaries

The current launch sequence is:

```text
JvmLaunchController (STS-JVM-Thread, running under ART)
  -> JREUtils: library paths/environment, extra native libraries, JRE dlopen
  -> setupExitMethod / initializeHooks / chdir / input bridge
  -> runtimeLifecycleReady = true; onRuntimeReady()
  -> VMLauncher.launchJVM(...)
  -> jre_launcher.c: signal setup, JLI_Launch(...)
  -> JLI: JavaMain / InitializeJVM / JNI_CreateJavaVM
  -> game and LWJGL initialization; HotSpot loads the JNI bridge
  -> normal JLI leave path: detach, DestroyJavaVM, return
```

The JLI steps are traced in the matching upstream source; the app calls the
installed JLI rather than owning `JNI_CreateJavaVM` directly.

| Location | What it establishes | Fast-path implication |
| --- | --- | --- |
| `JREUtils.initJavaRuntime` | Preloads `libjli`, `libjsig`, `libjvm`, and dependencies | Suitable for ordinary-thread capability discovery only; not VM readiness |
| `JvmLaunchController.onRuntimeReady` call site | Launcher/bridge handoff, before `VMLauncher.launchJVM` | Must not enable a JVM signal handler here |
| `jre_launcher.c:launchJVM` | Resets non-reserved signal dispositions, installs the current fatal reporter, enters JLI | Preserve existing ordering; a new ordinary `sigaction` wrapper would still sit behind ART/OEM forwarding |
| `input_bridge_v3.c:JNI_OnLoad` | First VM is saved as ART; a different VM is saved as `runtimeJavaVMPtr` | Potential post-creation rendezvous, but bridge-load timing and this pointer are not a complete runtime-ready/shutdown contract |
| `JvmLaunchController` cleanup/finally | Clears launcher state around cancellation or JLI return | Too late to guard all VM teardown; thread interruption does not prove HotSpot stopped |
| `native_hooks/exit_hook.c` | Captures `exit` code and registers an `atexit` callback | Does not cover every fatal exit or provide pre-teardown synchronization |

There is no `JNI_OnUnload`-based provider shutdown protocol in the inspected JNI
tree. A retained shared-library handle protects code addresses, but not torn-down
HotSpot thread/code-cache metadata. Readiness and quiescence must be implemented
inside the runtime provider; enabling merely because `dlsym` succeeded is unsafe.

The launcher's current fatal reporter writes metadata and re-raises the signal.
The probe crash tests did not include this reporter, HotSpot's fatal reporting,
and ART together. Its original-PC preservation and handler reentrancy therefore
remain explicit integration tests, not an assumed property of the probes.

## Required runtime-owned contract

The following describes a **proposed interface**, not an existing exported API.
The runtime must own recognition, continuation lookup, thread state, code-cache
lifetime, and readiness. Reconstructing these from exported VMStructs, module
ranges, instruction guesses, or launcher-side hardcoded offsets is insufficient.

### Admission and fault classification

- Require explicit opt-in, a supported ABI, a versioned provider contract, proven
  runtime readiness, and safe registration. A missing capability leaves the
  original chain in effect and records a skip reason outside signal context.
- Initially accept only synchronous `SIGSEGV`/`SEGV_MAPERR` implicit-null faults
  with valid metadata/context and a raw, untagged address in the runtime's defined
  null-check range. Do not assume all devices have 4 KiB pages or strip tags to
  make an address eligible.
- Verify a live HotSpot Java thread in the required execution state and an exact,
  runtime-owned null-check site with a valid continuation. A thread can interact
  with both VMs; thread attachment alone does not prove ownership.
- For compiled methods, require a valid exact-PC implicit-exception mapping with
  signal-safe lifetime guarantees. Interpreter, vtable and adapter sites need
  separately validated metadata. Broad `Interpreter::contains`/code-cache/module
  membership is not a strict native-fault classifier.
- Reject ART/JNI/native PCs, software signals, MTE faults, unsafe accesses,
  safepoint/serialization traps, stack-guard faults, unsupported signals, stale
  metadata, and startup/shutdown states. Their original handling remains intact.

### Rejection, success, and signal safety

- On rejection: return normally without modifying `siginfo`, `ucontext`, HotSpot
  exception state, `errno`, or the caller's signal mask; do not invoke any other
  handler. Let `libsigchain` advance the chain once.
- On success: commit the validated continuation and required saved exception PC,
  preserve unrelated state, and return normally. There must be no fallible lookup
  or side effect after the commit begins.
- No allocation, locks, JNI calls, `dlopen`/`dlsym`, symbolization, logging,
  unwinding, property/file I/O, lazy initialization, abort, or `longjmp` in the
  provider. Audit helper calls too: the existing implicit-exception helper contains
  logging/fatal branches and is not a drop-in recognition-only helper.
- Keep `sc_flags=0` and ART's normal recursion protection. `SIGCHAIN_ALLOW_NORETURN`
  is not a workaround for calling the stock HotSpot entry point. A genuine nested
  native fault must reach fatal handling, not be converted into a Java exception.

### Registration, disable, and teardown

- The checked ART implementation has two special-handler slots. No verified
  public capacity/reservation/try-register interface was found. The existing add
  function has no failure return, and removal is not a safe concurrent fallback.
- An acceptable registrar must reject a full/incompatible chain without mutation
  or termination and define safe publication under concurrent signals. Reading
  private slots, probing registration in a child, or using an app-local mutex does
  not provide that contract in the game process. A spare slot in a disposable
  `app_process` is not a reservation for this app or future native libraries.
- Until such a registrar is available, the supported outcome is
  `skipped: registration_contract_unavailable`; do not call the aborting add API
  speculatively. Runtime-provider absence must likewise skip before registration.
- Resolve and pin dependencies outside signal context. Publish only fully
  initialized immutable state, using atomics verified lock-free for the target ABI.
  The probe's sequential `volatile sig_atomic_t` toggle is not a production
  cross-thread synchronization design.
- Disable by clearing an enable flag, keeping registered code resident. This stops
  new admissions; it does not cancel an already admitted callback. Runtime teardown
  must close admission and drain in-flight users before reclaiming metadata.
  Do not race handler removal or unload the provider during gameplay.
- Diagnostics should contain opt-in/effective state, runtime/ART identity, provider
  ABI, registration/readiness state and skip reason. Any per-signal counters must
  be lock-free; sample/report them from a normal thread, not per fault.

## Next experiment and acceptance gates

1. **Resolve the two interface blockers first.** Build a runtime-owned, narrowly
   scoped no-chain try-handle provider in an experimental JRE and establish a
   nonfatal registration/publication contract. If the platform offers no such
   contract, reject this integration route on that platform. A runtime-side
   explicit-null-check experiment is a separate option that avoids this particular
   registration dependency; it still needs its own performance and semantic tests.
2. **Exercise actual ART plus actual HotSpot in disposable processes.** Test real
   interpreted/C1/C2 NPEs and their catch/stack semantics, JNI/native faults, ART
   allocation/GC/safepoints, JVM safepoints/deoptimization, stack overflow, MTE and
   software-signal rejection, nested faults, unavailable/invalid provider, full
   registration, concurrent disable, initialization failure, and VM teardown.
   Compare against the same harness with the feature off.
3. **Include the launcher's crash path.** For native faults, inspect original PC,
   signal and fault address plus expected `hs_err`, signal dump and/or debuggerd
   records relative to the baseline. Exit code alone is insufficient; turning an
   unknown SIGSEGV into a successful Java return is a failure.
4. **Only then use a controlled game restart.** Keep the same APK/runtime/mods/save
   and workload except for the experimental feature. Capture effective VM flags
   from that launch, compilation evidence, cold-start NPE latency, signal counts,
   CPU, and frame-time p50/p95/p99. Verify the hot faulty path is actually accepted;
   an implementation limited to already-warm compiled sites may miss this symptom.
5. **Run a normal-gameplay regression workload and more devices.** Cold/warm launch,
   menu/combat transitions, background/resume and exit must retain semantics and
   stability. A user switch is not a substitute for fallback safety. Default-on
   consideration requires these gates and measured non-error overhead.

The current evaluation passes the limited mechanism and isolated negative-control
experiments. It fails production admission because both interface contracts and
the dual-runtime/lifecycle/crash integration tests remain unresolved.

## Evidence and source index

Local experimental files are under the ignored directory
`agent-tmp/error-storm-live-20260926/`; this report retains the important outcomes
because those temporary files are not part of a normal repository checkout.

| Local artifact | Purpose |
| --- | --- |
| `diagnosis.md`, `after-tier4/diagnosis.md` | Live slowdown, sampled signal/CPU cost and stackless transition |
| `upstream-source-check.md` | Installed/runtime-source comparison and original source-path analysis |
| `signal-eval/sigchain_probe.c`, `SigchainArtProbe.java` | Single-PC provider and real-ART workload (Java file also under `signal-eval/`) |
| `signal-eval/run-probes.py`, `run-art-probes.py` | Separate standalone and ART runners (both under `signal-eval/`) |
| `signal-eval/probe-results.json`, `art-probe-results.json` | 12 native cases and 11 ART cases (both under `signal-eval/`) |
| `signal-eval/crash-buffer.log`, `art-crash-buffer.log` | PID-matched debuggerd records (both under `signal-eval/`) |
| `signal-eval/hotspot_contract_probe.c`, `hotspot-contract-results-untagged.txt` | Installed HotSpot chaining-contract counterexample (both under `signal-eval/`) |
| `signal-eval/hs_err_pid25560.log`, `art-startup-crash.log` | Failed setup attempts retained separately (both under `signal-eval/`) |
| `signal-eval/evidence-sha256.txt` | Hashes of tested binaries, current probe sources and key result files |
| `signal-eval/device-closeout.json` | Device cleanup, remote/local hash checks and game PID before/after |

On 2026-09-26 at 21:25 UTC+8, the five device-side probe artifacts were matched to
their local copies by SHA-256 and removed along with their dedicated temporary
directory. No probe processes remained. The game PID was `30993` both before and
after cleanup. Re-running the probe scripts requires staging their artifacts again.

The tested standalone executable predates the JNI/`with_art` source extension;
the shared library includes it. The manifest distinguishes the binaries from the
current source. Rebuilding the current source is a new test run, not bit-for-bit
reproduction of that original standalone binary.

Primary source references, checked on 2026-09-26:

- AOSP ART `android16-release`, `sigchainlib/sigchain.cc`: `AddSpecialHandler`,
  `SignalChain::Handler`, `RemoveSpecialHandler`, `AddSpecialSignalHandlerFn`.
- AOSP ART `android16-release`, `runtime/fault_handler.cc`: ART's special-handler
  registration and mask setup.
- OpenJDK/jdk8u commit `5c0b36a630a4280d3ea4a6494e9bcf76c57191a6`,
  `hotspot/src/os_cpu/linux_aarch64/vm/os_linux_aarch64.cpp`,
  `hotspot/src/share/vm/runtime/sharedRuntime.cpp`, and `jdk/src/share/bin/java.c`.
- Repository call sites: `JvmLaunchController.kt`, `JREUtils.java`, `VMLauncher.java`,
  `jre_launcher.c`, `input_bridge_v3.c`, `native_hooks/exit_hook.c`,
  `AndroidManifest.xml`, and JNI `CMakeLists.txt`.

```text
https://android.googlesource.com/platform/art/+/refs/heads/android16-release/sigchainlib/sigchain.cc
https://android.googlesource.com/platform/art/+/refs/heads/android16-release/runtime/fault_handler.cc
https://github.com/openjdk/jdk8u/blob/5c0b36a630a4280d3ea4a6494e9bcf76c57191a6/hotspot/src/os_cpu/linux_aarch64/vm/os_linux_aarch64.cpp
https://github.com/openjdk/jdk8u/blob/5c0b36a630a4280d3ea4a6494e9bcf76c57191a6/hotspot/src/share/vm/runtime/sharedRuntime.cpp
https://github.com/openjdk/jdk8u/blob/5c0b36a630a4280d3ea4a6494e9bcf76c57191a6/jdk/src/share/bin/java.c
```
