# AI Patch Mod Workflow

## Scope

The AI mod editor lets the agent build a separate, versioned patch-mod revision for the selected mod.
The original mod JAR is held as a read-only, SHA-256-pinned snapshot and is never rewritten. Parent
classes and resources are read or decompiled on demand; the full archive is not extracted.

The patch-mod workspace is **not** created when the editor opens. It is created on demand by the
agent through the `create_agent_patch_workspace` tool, and only when the user actually asks for a new change with no existing patch workspace to reuse.
Opening the editor or starting a new conversation must not generate a workspace.

## Storage Layout

The launcher stores AI artifacts under the STS runtime root:

```text
agent_workspace/<modid>/
  source/                 # on-demand decompiled class cache; readable context, agent read-only
  source-metadata.json
  patch_source/           # one isolated source tree per patch mod
    <patch_id>/
      ModTheSpire.json
      agent-patch.json
      src/                 # Java sources written by the agent
  mod-metadata.json

agent_workspace/.conversations/<modid>/conversations.json
agent_mods/<modid>/
  amethyst.ai.patch.<modid>.<patch_id>.jar
```

Each `patch_source/<patch_id>/agent-patch.json` records the parent mod ID, patch ID, patch mod ID, display name, version, and
description. The generated `ModTheSpire.json` always contains the parent mod as a dependency.

Packaged patch mods are loaded by the launcher directly from `agent_mods/`; there is no separate
"installed" copy in the optional-mod library. Enablement is tracked by `patchModId` in the
`enabled_agent_patch_mods.txt` config file.

Conversation history lives in `agent_workspace/.conversations/<modid>/conversations.json` and is
independent of any `patch_id`, so multiple conversations share one workspace within an editor session.

## Context Management

Parallel tool calls use the native OpenAI message structure: one assistant message declares all
calls, followed by one tool result per call ID. Neither the gateway nor context conversion splits
the assistant message, so the original `reasoning_content` is preserved once rather than duplicated.
This also satisfies DeepSeek thinking-mode replay requirements. Regression tests inspect actual
serialized HTTP requests for both streaming and non-streaming responses. Provider-specific
OpenAI-to-Anthropic bridge workarounds are not applied.

The editor uses an archived protocol transcript separately from the display transcript. Each session
stores structured user messages, assistant messages (including tool call IDs and reasoning when
provided), tool results, the active patch ID, and a successful summary boundary. Tool previews in the
UI are not used as the source of truth for subsequent requests. Legacy conversations migrate on first
use; old tool previews are explicitly marked incomplete because omitted output cannot be recovered.

Context limits are configured per service URL and model. An unset or legacy zero limit defaults to
**200,000 tokens**. A task snapshots the limit when it starts. The budget reserves up to 16,000 output
tokens (10% for smaller windows) plus 5% safety headroom. With the default limit, compaction starts
at an estimated 174,000 input tokens. Model requests explicitly cap output to the reserved amount.
The estimate includes system instructions, tool schemas, summary and retained protocol messages;
provider-reported input usage calibrates it upwards. It is an estimate, not a provider tokenizer.
The UI separately displays the latest reported input/output usage and successful compaction count.
Switching models resets the usage calibration; a model without usage metadata still has a local estimate.

Inspired by OpenCode's session compaction and overflow handling:

- Before every model request, check the entire active context budget, including within long tool loops.
- Retain a recent tail of up to 15,000 estimated tokens, reduced for smaller windows. Never split a
  tool-call message from its result group. Preserve the latest user request verbatim even when the
  beginning of its long tool loop has been summarized.
- Summarize older history sequentially in bounded chunks with no tools enabled. Carry forward goals,
  constraints, exact patch IDs/paths, verified signatures, changes, approvals, results and pending work.
- Atomically checkpoint a new summary only after all chunks succeed, the response is nonempty and
  complete, and the resulting active context is smaller. On cancellation, errors, empty summaries or
  output truncation, keep the original context. Compaction never deletes the archived messages.
- On a recognized provider context-overflow error, compact and retry the model request once. This
  retry never re-executes tools. Summary overflow retries use progressively smaller chunks, with a
  finite retry limit. An oversized current request/attachment that cannot fit produces an explicit
  capacity error rather than silently dropping user instructions.
- Persist assistant tool calls before execution and results afterwards. After interruption, unmatched
  calls receive an explicit unknown-outcome result; the agent must inspect state before retrying.
  Restore the active patch workspace on the next task. Rollback discards affected protocol messages
  and invalidates summaries that covered them; filesystem side effects are not undone by chat rollback.

Session edits are per-session read/modify/write transactions under a file lock, with monotonically
increasing revisions. Delayed UI polls cannot overwrite newer local state. The foreground task is
reserved before its RUNNING record is published, and only one task is admitted per process. Streamed
display text is flushed at most every 250 ms and at tool/final checkpoints to reduce whole-file writes;
an abrupt process kill may lose the last unflushed display fragment, not completed protocol checkpoints.

Oversized tool results retain a bounded UTF-8 preview and a durable `tool_output/` file. Referenced
outputs are no longer deleted by a global last-20-files policy. Use `read_agent_workspace_file` with
`encoding=utf8_chars`, zero-based character `offset`, and `next_offset` to recover long single-line
JSON without the normal line reader's 2,000-character truncation. Archives and overflow files currently
remain on disk; automatic storage garbage collection is not part of context compaction.

References: [OpenCode compaction](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/compaction.ts)
and [overflow budgeting](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/session/overflow.ts).

## Tool Scheduling

Both streaming and non-streaming agent loops use `AgentToolScheduler`. A model turn may return
multiple calls; the next model request waits for all of them, not one request per tool. Consecutive
`PARALLEL` tools run in a bounded pool (default **4**). Every `EXCLUSIVE` call is a barrier: all earlier
calls finish first, it runs alone, and only then may later calls start. Even reads cannot cross a write
barrier, so a read → write → read sequence observes the intended versions of a file.

Every built-in `AgentTool` explicitly declares `concurrency`, independently of its safety/approval
classification. The interface defaults to `EXCLUSIVE` for new or third-party implementations:

- **Parallel:** workspace list/read/glob/grep, bundled skill reads, API search/describe, bytecode class
  and patch-target inspection, JAR entries/resource reads, and packaged-patch lists.
- **Exclusive:** workspace create/write/delete, source snapshot preparation and class decompilation
  (shared metadata/scratch cache), skeleton generation, compile/preflight, package/update, enable/
  disable, patch deletion, and smoke tests. Installed-mod listing is also exclusive because its
  underlying catalog may normalize and persist the enabled-mod selection.

API tools serialize only shared classpath cache resolution; subsequent bytecode queries can overlap.
Tool start/completion callbacks are serialized for UI persistence, but tool bodies run concurrently.
Results and context checkpoints are delivered on the agent thread in the original call order,
regardless of completion order. Ordinary tool errors produce error results without cancelling sibling
calls. Cancellation or infrastructure/checkpoint failures stop admitting work and interrupt outstanding
workers; interrupted calls must not be automatically retried or reported as successfully completed.
Blocking tool implementations must cooperate with interruption to stop promptly.

This follows OpenCode V2's [step-scoped tool execution and cancellation](https://github.com/anomalyco/opencode/blob/dev/packages/core/src/session/runner/step.ts)
(`forkScoped`, `awaitAll`, `interruptAll`), with explicit opt-in concurrency and exclusive barriers added
for this editor's shared mutable patch workspace. Unlike OpenCode's streamed tool-call execution,
the editor currently schedules calls after a full model response has been assembled.

Scheduler/loop regression tests (no real LLM service or device required):

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.llm.AgentToolSchedulerTest' \
  --tests 'io.stamethyst.backend.llm.AgentToolSchedulerGatewayTest' \
  --tests 'io.stamethyst.backend.llm.PolicyGatedAgentGatewayTest'
```

## Agent Tools

The editor exposes these tools for the selected mod workspace:

All workspace tools share a fixed virtual root `/` containing `/source/`, `/patch_source/` (multiple
revisions), and `/tool_output/`. Returned absolute paths always keep their original target. An
unqualified relative file path such as `src/A.java` resolves inside the conversation's selected
patch. Mount-qualified paths such as `source/A.java` retain their older meaning. Only the selected
patch is writable. See [the virtual path contract](ai-agent-local-editing.md#固定虚拟文件视图).

- `switch_agent_workspace`: call without arguments to list this parent's drafts and packaged
  workspaces, or pass `patch_id` to bind an existing workspace. Switching must be the only call in
  the batch. The composer folder picker uses the same persistent binding and permits clearing it
  while idle. A fresh inspection session can remain unbound. Multiple conversations may select
  the same patch; the editor's existing single-running-job reservation serializes agent jobs.
- Upgrade: each conversation gets a nullable binding column in schema v10. The first full load
  migrates once, after legacy JSON/context import. Prefer saved IDs corroborated by successful
  actions; infer an unambiguous target only from durable state/structured results, not filenames
  in prose or read/enable calls. Unavailable and ambiguous targets need a selection before
  writing. The binding is separate from model checkpoints and conversation rollback.

- `list_agent_workspace`: list materialized and generated files in the public virtual view.
- `read_agent_workspace_file`: read UTF-8 or base64 content from an exposed workspace file.
- `glob_agent_workspace`: find files by glob pattern (for example `**/*.java`), optionally scoped with `path`.
- `grep_agent_workspace`: search workspace file contents by regular expression, optionally scoped with `path` and filtered by an `include` glob. Returns file, line number, and a preview of each matching line.
- `create_agent_patch_workspace`: create and activate a new patch workspace/revision. Required arguments: `name` (the agent
  chooses it), optional `version` and `description`. Records the immutable parent JAR in the shared
  source snapshot metadata and seeds a new `patch_source/<patch_id>/` with `ModTheSpire.json` and
  `agent-patch.json`. This only prepares a workspace; it does not compile, package, install, or enable a mod.
  Do not call it for changes to an existing patch revision: reuse that revision's `patch_source/<patch_id>/` tree,
  compile it with the same `patch_id`, and call `update_agent_patch_mod`.
- `write_agent_workspace_file`: create or replace files only inside `/patch_source/<patch_id>/`. For patch-mod
  work, the agent uses the `patch_workspace_path` returned by `create_agent_patch_workspace`, while
  `source/` remains read-only context.
- `delete_agent_workspace_file`: delete files or, with `recursive=true`, non-empty directories only
  inside `/patch_source/<patch_id>/`. Read-only trees, `/`, `/patch_source/` and individual patch roots cannot be deleted by this tool.
- `decompile_agent_mod_source`: prepare the parent JAR snapshot without extracting it.
- `list_agent_mod_entries`: discover resource or class paths in a mod or packaged patch JAR without extraction; use `source="game"` for the imported `desktop-1.0.jar`.
- `inspect_agent_class`: inspect one mod or packaged patch class and its exact declared API without extraction.
- `decompile_agent_class`: decompile one mod or packaged patch class on demand. Only parent classes can enter the shared `source/` cache.
- `decompile_agent_api_class`: decompile one or more game, BaseMod, StSLib, ModTheSpire, or bundled
  dependency classes from the compile classpath and return the Java source inline. Several names can
  be passed per call; a class above the inline cap returns a member index plus a signature skeleton,
  and `member` reads one method, constructor, or field body. Use this for vanilla behavior; installed
  mod classes still go through `decompile_agent_class`.
- `read_agent_mod_resource`: read a bounded byte range from a mod or packaged patch JAR resource; use `source="game"` for original game resources, including card art. Whole-image reads return viewable attachments.
  The `source/` tree is available to the agent through read/list tools and cannot be modified
  through workspace write/delete tools. See "On-device decompilation" below.
- `read_agent_skill`: return a bundled reference skill, or list the available skills when `name` is
  omitted. The `basemod-and-stslib` skill documents the BaseMod 5.56.0 / StSLib 2.12.0 APIs the agent
  must use: registration lifecycle and ordering, where each content type (card, relic, potion, event,
  keyword, character, color) is registered, the hook interfaces, StSLib keywords/hooks/actions, and
  the mistakes that crash the game at load. The asset lives at
  `app/src/main/assets/agent/skills/basemod-and-stslib.md`. It carries the semantics; it tells the
  agent to confirm every exact signature with the API tools below rather than trust the document.
- `search_agent_api`: search the resolved compile classpath (game, ModTheSpire, BaseMod, StSLib,
  required mods, parent mod) for types by name, optionally filtered by `implements`. Backed by
  `AgentApiIndex`, a header-only ASM index cached per classpath signature; results carry the origin
  JAR so the agent can tell BaseMod API from game internals.
- `describe_agent_api_class`: return one type's exact public and protected constructors, methods, and
  fields, read from the installed class files, with an optional `member_filter`. Nested types resolve
  as either `Outer.Inner` or `Outer$Inner`. This is how the agent confirms an overload instead of
  recalling one from training data.
- `compile_agent_patch_source`: compile Java sources under the active
  `patch_source/<patch_id>/src/` into Java 8 `.class` files at that patch root. Bind an existing
  revision first; explicit `patch_id` must match that binding. See "On-device
  compilation" below.
- `package_agent_patch_mod`: validate and package the active `patch_source/<patch_id>/` into
  `agent_mods/<modid>/`. Rejects
  packaging when Java sources are present but no classes have been compiled. Returns `jar_name` and
  the virtual `patch_workspace_path`, not a physical `jar_path`.
- `update_agent_patch_mod`: repackage an already packaged revision in place from its patch
  workspace with a new `version` (and optional `name`/`description`). The `patch_id`, patch mod id,
  and enabled state are unchanged, so updating never creates a duplicate or forces re-enabling.
  Runs the same bytecode preflight as packaging and refuses to replace the JAR when it fails.
- `smoke_test_agent_patch_mod`: launch the game with one packaged revision and report whether the
  main menu was reached. Optional `patch_id` (defaults to the current session's revision),
  `timeout_seconds` (default `240`), and `mod_ids` (extra optional mods to enable for the run). See
  "Smoke test" below.
- `list_installed_mods`: list the mods the launcher has installed with their `mod_id`,
  `manifest_mod_id`, name, version, dependencies, built-in flag, and current enabled state.
  The separate `patches` list includes enabled and disabled local packaged patches from all parents.
  Use `patch_mod_id` from that list with the four read tools above, instead of `mod_id`.
  Use `selectable_mod_ids`, not `patch_mod_id`, for extra mods in the smoke test.
- `set_agent_patch_mod_enabled`: enable or disable a packaged patch revision for the parent mod.
- `list_agent_patch_mods`: list packaged revisions and their enabled state.
- `delete_agent_patch_mod`: delete a packaged revision by `patch_id` and clear its enablement.

Enablement is a separate launcher-managed operation and is never implied by packaging. The delete tool
asks for confirmation; it removes the packaged JAR and its enabled selection.

## Smoke test

Compilation and the bytecode preflight only prove that the patch targets existing code. They cannot
prove the game still loads, so `smoke_test_agent_patch_mod` performs the real check: it launches the
game once and passes only when the boot bridge reports `READY` (main menu) with the process still
alive.

The launch mod set is fixed and does not depend on the user's optional-mod selection:

1. the launcher's built-in mods (BaseMod, StSLib, Amethyst Runtime Compat, Amethyst Floating Tools,
   Ram Saver, Amethyst Frame Probe),
2. the parent mod's transitive dependencies, resolved from the installed-mod list and the parent's
   own manifest,
3. the parent mod,
4. the patch mod under test.

This keeps a failure attributable to the patch instead of an unrelated enabled mod. A dependency the
launcher does not have installed fails the run immediately with `unresolved_dependencies` rather than
being silently dropped.

On top of that baseline the agent may enable additional optional mods with `mod_ids`. A patch that is
only meaningful alongside another mod has to be verified together with it, so the selected mods are
resolved through `list_installed_mods`, added after the baseline, and bring their own dependency
closure. Selecting a built-in mod is a no-op, and a mod id the launcher does not have fails the run
with `unknown_mod_ids` instead of being ignored.

`AgentPatchSmokeTestModList` holds the ordering logic. The run itself is split across the two
processes the launcher already uses:

- The launcher (main) process resolves the mod set and reuses the normal launch path by passing it as
  a `LaunchModSnapshot` override into `MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch`,
  which writes it to `.mts_mod_file_list`. This part must run here because it repairs
  `desktop-1.0.jar`, which the `:game` process is not allowed to do.
- It invalidates the MTS classpath and patch caches before and after the run, because otherwise the
  patch cache would skip the patch pass and the test would be meaningless. Interactive launches are
  unaffected: they still reuse the cache.
- It then writes `.agent_smoke_test/request.json`, binds `AgentPatchSmokeTestService` in the `:game`
  process, and polls `.agent_smoke_test/result.json`. The binding is what keeps the game process at a
  visible importance for the whole run: the client is a foreground Activity and the service is bound
  rather than started, so no foreground-service notification is ever shown.

`AgentPatchSmokeTestService` runs the game with no UI of any kind:

- The game JVM is loaded into `:game`, the same process a normal session uses, so one process can
  only host one game. A live session makes the tool return `game_already_running`.
- It renders into `HeadlessGameSurface`: a standalone `ImageReader` surface handed to
  `JREUtils.setupBridgeWindow`. The surface belongs to no display and no window, so the game gets a
  real render target while nothing is composited anywhere and the launcher never loses the
  foreground. The image queue is drained continuously; otherwise the producer would block once the
  queue filled and the game would stall in `eglSwapBuffers`.
- A display-based variant was rejected by the platform and must not be reintroduced. Launching an
  Activity onto an app-created virtual display requires
  `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`/`PUBLIC`, which are signature-protected, so `startActivity`
  failed with `Permission Denial ... with launchDisplayId`. An `ImageReader` the app owns has no such
  restriction.
- The session is muted, because it is deliberately invisible and its audio would have no source the
  user could attribute it to.
- The verdict comes from the boot bridge terminal events plus a crash-marker scan of the part of
  `latest.log` this run wrote. A failure returns the boot events and a log excerpt so the agent can
  fix the patch.
- `ExitActivity.showExitMessage` short-circuits while the run marker
  (`.agent_smoke_test/run.active`) exists. The native JVM exit trap would otherwise restart
  `LauncherActivity` with `FLAG_ACTIVITY_CLEAR_TASK`, destroying the AI editor and cancelling the
  tool call that is waiting for this verdict.
- After writing the verdict the service closes the game with
  `CallbackBridge.nativeRequestCloseWindow`, the same graceful path an in-game back exit uses, and
  kills the process only if the JVM does not exit within the grace period.
- The game cannot take the foreground at all: the session has no Activity and no display window, so
  the run is safe whether or not the launcher is visible. There is deliberately no check on launcher
  visibility — such a check would abort a run whenever the user switches away.
- A debug build exposes `AgentPatchSmokeTestAdbReceiver` so this flow can be run over adb without
  driving the AI editor, which is how it is regression-checked.

## Launcher Lifecycle

1. Opening AI Edit creates no workspace. It only prepares the parent's conversation directory.
2. When the user asks for inspection, the agent calls `decompile_agent_mod_source`, which records a
   SHA-256-pinned parent snapshot without extracting the parent into `<modid>/source/`.
3. When the user asks for a new change and no existing patch revision can be reused, the agent calls
   `create_agent_patch_workspace`, which prepares the shared on-demand source cache, allocates a `patch_id`, and creates writable
   `<modid>/patch_source/<patch_id>/` for packaging. For follow-up changes, the agent edits the existing
   `patch_source/<patch_id>/` directly and updates that revision instead of creating another workspace.
4. The agent reads selected classes/resources on demand and writes a self-contained patch mod under its
   dedicated `patch_source/<patch_id>/`.
5. Packaging writes a JAR under `agent_mods/<modid>/` and injects the parent dependency into its manifest.
6. The agent calls `smoke_test_agent_patch_mod` to prove the revision reaches the main menu.
7. The normal ModTheSpire launch preparation loads enabled patch JARs directly from `agent_mods/`.
8. The parent mod card shows its packaged patch revisions when expanded. Revisions expose enable
   switches; the agent can also toggle them with `set_agent_patch_mod_enabled`.

Patch mods are hidden from the top-level mod list and displayed under their parent. Their manifest
dependency also prevents a patch from being enabled without its parent.

### Background execution

The run itself is owned by `AiAgentExecutionService`, a started foreground service
(`foregroundServiceType="dataSync"`) in the launcher process, not by the editor ViewModel. Leaving the
editor, switching mods or any other navigation therefore does not cancel the job; the editor polls the
job table in SQLite (500 ms while a job runs, 2 s idle) and the notification opens the same
conversation. Only one job may run per process (`reserve`).

A foreground service keeps the process alive but not the CPU awake, so the service also holds a
`PARTIAL_WAKE_LOCK` named `$packageName:ai-agent` for the duration of the run. The lease is time
bounded instead of indefinite: it is sized from the configured LLM request timeout plus a two minute
margin, so it always outlasts one silent non-streaming round. Every stream flush and every
`checkActive()` call (model rounds, tool batches, compaction) renews the lease once per minute by
replacing it. All exit paths release it — `stopWhenIdle` is the single choke point, `onDestroy` covers
a destroyed service, and a failed acquire is only logged because the run is still worth attempting
without it.

A CPU wake lock is not enough on its own. When the screen turns off the platform may put the Wi-Fi
radio into power save even though the process is runnable and its notification is still posted; the
established socket is then aborted and the request fails with
`IOException: Software caused connection abort`. The service therefore also holds a
`WifiManager.WIFI_MODE_FULL_HIGH_PERF` lock for the run (`$packageName:ai-agent`). It is acquired and
released together with the CPU lock, uses reference-counted-off semantics so re-acquiring cannot leak
a count, and a failure to acquire it is logged rather than fatal: some devices refuse a high-perf lock
and the retry path below still recovers.

Radio power save is only one of the causes. Doze suspends networking for the whole device and
**ignores wake locks**, so no lock prevents it. The platform documents exactly one mechanism for
keeping the network during Doze: the battery-optimisation allowlist, because an exempted app "can use
the network and hold partial wake locks during Doze and App Standby". The AI settings screen therefore
offers that exemption (`BatteryOptimization`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, with the
battery list as a fallback when a ROM refuses the per-app dialog). Without it, Doze still applies.

Retrying alone never recovered from an abort, for two separate reasons that are both fixed:

- `hasValidatedNetwork` reports `true` even while the radio is in power save, so the network wait was
  skipped instantly. The retry path now also treats the abort itself as the signal.
- OkHttp kept the torn-down connection in its pool, so the retry was handed a dead socket and failed
  again immediately. The model factory owns one `ConnectionPool` per endpoint, and an abort evicts it
  before the retry so the next attempt opens a fresh TCP connection.

An abort also gets its own, larger budget: `MAX_ABORT_RETRIES` (3) with 5 s, 15 s and 30 s backoff,
instead of the 1 s/2 s used for a transient provider failure. `isConnectionAbort` matches
`SocketException` (connection abort/reset/broken pipe), `SocketTimeoutException`, `EOFException` and
`ClosedChannelException` anywhere in the cause chain. The bounded `hasUsableNetwork` wait (60 s, 500 ms
polls, cancellable in 200 ms slices) still runs first, so a genuinely offline device fails with its
real error instead of hanging.

Still missing, and deliberately not implied by the wake lock: the job is not restored after process
death (the record stays `RUNNING` and the next editor open converts it to the explicit interrupted
error), pending inbox messages are not replayed automatically after such a death, and heavy work such
as decompilation and Janino compilation shares the launcher's main process and heap.

### Updating a packaged revision

An update reuses the revision's `patch_id`, so `resolvePatchWorkspace` reconstructs the workspace
from disk even when the revision was packaged in an earlier session. The agent edits
`patch_source/<patch_id>/`, compiles it with `compile_agent_patch_source` and that `patch_id`, then
calls `update_agent_patch_mod`. The repackaged JAR replaces the same
`agent_mods/<modid>/amethyst.ai.patch.<modid>.<patch_id>.jar`, and the parent dependency keeps its
original manifest casing (read from the parent JAR, falling back to the revision's own manifest), so
ModTheSpire's case-sensitive dependency check still passes. Enablement is keyed by patch mod id and
therefore survives the update.

## BaseMod and StSLib guidance

Content mods are the common case, so the agent needs to know the BaseMod/StSLib API without
hallucinating it. The knowledge is split by what each layer can be trusted to know:

- **Semantics live in a skill.** `read_agent_skill` serves a pinned reference for BaseMod 5.56.0 and
  StSLib 2.12.0, distilled from their wikis. Registration order, which subscriber registers which
  content, hook names, keyword fields, and the load-time crash pitfalls are facts no bytecode can
  express, so they are written down once and read on demand instead of being paid for in every
  request.
- **Signatures live in the installed bytecode.** `search_agent_api` and `describe_agent_api_class`
  read the same classpath the patch is compiled and validated against, so the agent can never be
  told a signature that does not exist in the version it is patching. The skill deliberately
  contains no authoritative signatures.

The system prompt carries only the routing rule: read the skill before writing BaseMod/StSLib code,
confirm every signature with the API tools, and prefer the BaseMod API over ModTheSpire patches.

The skill text was distilled from the upstream wikis
([BaseMod](https://github.com/daviscook477/BaseMod/wiki),
[StSLib](https://github.com/kiooeht/StSLib/wiki)). A working clone of both was kept under the
scratch `agent-tmp/wiki-research/` directory while writing it; that directory is not shipped in the
APK and may be discarded.

## Safety Boundaries

- Workspace paths are canonicalized and reject escapes outside the selected mod workspace. Agent writes
  and deletes are additionally restricted to `patch_source/`; `source/` is read-only agent context.
- On-demand class/resource reads and patch packaging are bounded by byte limits.
- A patch package must contain both root `ModTheSpire.json` and `agent-patch.json`.
- Package output is written through a temporary file and verified as a readable ZIP before replacement.
- The original parent JAR is never modified by the patch-mod workflow.
- Destructive or workspace-mutating tools (`PATCH_CREATE`, `MOD_INSPECTION`, `PATCH_COMPILE`,
  `PATCH_PACKAGE`, `PATCH_ENABLE`, `PATCH_DELETE`, `WORKSPACE_WRITE`, `PATCH_SMOKE_TEST`) are gated by
  the policy allow-list in the editor.
- The smoke test never uses the user's enabled optional mods, never enables the patch being tested,
  and never leaves the MTS caches pointing at its temporary mod set.

## On-device compilation

The AI editor compiles patch source on the device; no host toolchain is involved.

- The launcher runs on Android/ART, which has no `javax.tools` compiler. ECJ (`org.eclipse.jdt:ecj`,
  3.26.0) is bundled in the APK and invoked through `org.eclipse.jdt.core.compiler.batch.BatchCompiler`
  from the launcher process.
- Sources are written under `patch_source/<patch_id>/src/<package>/...` and compiled to `.class`
  files at that patch root, so the packaged JAR is a normal ModTheSpire mod. Its `src/` directory is
  excluded from the package.
- Every compile goes to a staging directory first and only replaces the patch root's class tree when
  ECJ succeeds. New classes are copied, then every class the new build did not produce is deleted, so
  a renamed or deleted source cannot leave a stale top-level or inner class for validation and
  packaging to pick up; a failed build leaves the previous tree untouched.
- Compilation is pinned to `-source 1.8 -target 1.8 -g` because the game runs on the embedded
  OpenJDK 8 JVM and ModTheSpire expects Java 8 bytecode. `-g` is not optional: ModTheSpire's
  `ParamInfo2.findName` resolves `__instance`, `__result`, `__args` and `localvars` names from the
  patch method's `LocalVariableTable`, and ECJ emits only `LineNumberTable` without it, which fails at
  load time with `PatchingException: Unknown error acquiring parameter name for "null"`.

- The bootclasspath is the installed runtime's `rt.jar`, and the classpath is the game jar, ModTheSpire,
  the required mods, and the parent mod being patched. This is required because ART's `java.*` surface
  differs from the JRE the resulting mod runs on; ECJ only reads those class files for symbol resolution.
- Diagnostics from ECJ are returned to the agent so it can fix source before packaging.
- Compilation is cancellable. ECJ is given a `CompilationProgress` whose `isCanceled()` returns the
  worker thread's interrupt flag, which ECJ polls at its own checkpoints; the earlier code passed
  `null`, so a cancelled run kept compiling while holding the exclusive tool lock and its class tree.
  Cancellation also re-checks immediately before `replaceCompiledClasses`, so a stopped run throws
  `CancellationException` instead of installing classes its session no longer owns.
- `inspect_agent_patch_target` reads the installed game/mod class files with ASM and returns the exact
  method or constructor overloads, JVM descriptors, parameter types, return type, and fields. It does
  not load game classes into Android or infer signatures from decompiler text. By default it also
  returns a bounded javap-style bytecode listing and a structured `local_variables` list for each
  overload, including `LocalVariableTable` names when the target was compiled with debug information;
  `include_bytecode=false` omits both.
- `generate_agent_patch_skeleton` consumes that inspection result and writes a Java 8 patch skeleton
  with the selected `paramtypez` and a safe `Object[] __args` hook shape. The agent edits the body
  instead of hand-writing the patch annotation and overload signature.
- `validate_agent_patch_mod` runs after compilation and before packaging. It reads the compiled patch
  annotations and compares every target class, method, constructor, and explicit parameter signature
  against the same runtime classpath. It also checks static hook methods, Prefix/Postfix return types,
  `__instance`, `__result`, `__args`, and `___field` injections. Unknown named parameters are rejected
  so the agent must use `Object[] __args` when bytecode cannot prove a parameter name. Packaging runs
  this preflight again and refuses to emit a JAR when it fails, so a tool-call omission cannot bypass
  validation. Pass `patch_id` to validate an existing revision instead of the current session's
  workspace. A compiled class that no source in the workspace declares is reported too: a stale patch
  or initializer class blocks packaging, and a stale helper only warns. `compile_agent_patch_source`
  removes such classes on the next build, so the fix is to recompile rather than delete files by hand.
- Non-hook mods are valid too. A patch mod with no `@SpirePatch` annotation is accepted when it is a
  resource-only package, when it uses `@SpireInitializer` for content registration, or when it only
  ships helper classes. `@SpireInitializer` classes are checked for a no-arg static `void
  initialize()` method, which ModTheSpire calls reflectively; a missing or non-static method is
  rejected because ModTheSpire would otherwise skip the mod silently. Packaging a patch mod with no
  `@SpirePatch` or `@SpireInitializer` entry point succeeds but returns a warning.
- `Insert`, `Instrument`, and `Raw` hooks are recognized and checked for static structure, but their
  locator bytecode and local-variable semantics still require a game smoke test before enabling.
Two Android-specific details make this work:

- **Missing JDK types.** ART ships no `javax.lang.model`, but ECJ's batch `FileSystem` static
  initializer probes `SourceVersion.valueOf("RELEASE_12")`. A minimal shim at
  `app/src/main/java/javax/lang/model/SourceVersion.java` satisfies that single call site and makes
  ECJ treat the environment as pre-Java-12, which is correct for `-target 1.8`.
- **Duplicate JAR entries.** The shipped `desktop-1.0.jar` contains duplicate entry names, which
  Android's `java.util.zip.ZipFile` rejects, so ECJ cannot index it directly. `buildClasspath`
  detects unreadable jars and substitutes a cached, duplicate-free class-only overlay produced by
  `DuplicateZipEntryNormalizer.copyClassEntries`. Resources are not copied into this compile cache.
  The overlay is content-addressed by name, size, and mtime, so it is reused across compiles. It
  lives under the app cache, falling back to internal storage when `cacheDir` is unusable.
- When a classpath jar still cannot be indexed (for example no writable scratch directory), the
  compile diagnostics list it under a warning instead of silently dropping it.

Compiling arbitrary Java is intentionally limited to Java 8 language/bytecode and to the bundled
compile classpath; the workflow does not run Gradle or fetch dependencies on the device.

## On-device on-demand decompilation

The parent JAR is bytecode, so the agent needs readable source for a class before rewriting behavior.
`decompile_agent_mod_source` now only prepares a source snapshot. `decompile_agent_class` reads one
class directly from the parent JAR, gives CFR a tiny temporary JAR containing that class, and uses the
resolved compile classpath for references. The resulting `.java` is cached under `source/`; resources
are read with `read_agent_mod_resource` and are never copied into the workspace by default. The source
tree remains readable agent context and is protected from workspace writes/deletes. Agent-authored
patch sources and generated patch metadata live under the separate writable `patch_source/<patch_id>/`
tree for each patch mod.

`decompile_agent_api_class` covers the other half of the classpath: game classes, BaseMod, StSLib,
ModTheSpire, and bundled dependencies. It locates each class in the resolved compile classpath, gives CFR
the same one-class temporary JAR treatment, and returns the source inline instead of writing it into
`source/`. That keeps vanilla source out of the parent-mod cache while still letting the agent read the
actual installed implementation before designing a patch.

- CFR (`org.benf:cfr`, 0.152) is bundled in the APK and invoked through its `CfrDriver` API from the
  launcher process. CFR is compiled to class file 50 and does not execute the class it reads.
- The tool accepts up to eight binary names per call (`class_names`, or `class_name` as an array) so
  independent classes cost one round trip. A single class keeps the flat response shape; several
  classes return `{status, results, next_step}`.
- Decompiled API source is cached on device under the app cache (`agent-api-source/`), keyed by the
  archive fingerprint `name-length-lastModified` plus the binary class name. The fingerprint is not a
  content hash because hashing the 365 MB game JAR costs more than the decompilation it would guard.
  The cache is bounded (24 MB / 512 files) and pruned oldest-first.
- One CFR driver is kept per classpath per thread and reused, so the archive index and parsed options
  stay warm between calls. CFR's class-file source is not thread-safe, which is why the reuse is
  thread-local; the tool is therefore `PARALLEL` in the scheduler.
- A class above the 32,000-character inline cap is never cut mid-file. The response carries a member
  index (name, kind, declaration) and a signature-only skeleton whose bodies are `{ /* … */ }`; passing
  `member` returns that method, constructor, or field body instead. `source_chars` is always the full
  decompiled length, `returned_chars` the returned text, `truncated` whether the returned text was cut,
  and `sliced` whether a member subset was returned.
- The API index (`search_agent_api`, `describe_agent_api_class`) is persisted as a binary `.idx` file
  keyed by the SHA-256 of the classpath signature (paths, sizes, modification times), so a new process
  does not re-parse thousands of class headers. Indexes are validated by magic and version; a corrupt
  or stale file is deleted and rebuilt.
- Building that index opens each archive **once** and reads every class header through that handle.
  Constructing a `ZipFile` parses the whole central directory, so reopening it per class made the
  build quadratic over the game JAR's thousands of entries; `search_agent_api` then appeared to hang.
  `AgentModJarReader` exposes `readClassEntryBytes(ZipFile, …)`, `readEntryBytes(ZipFile, …)` and
  `classEntries(ZipFile)` for this bulk path, and a jar Android cannot index falls back to the
  sequential reader once for that archive rather than once per class.
- The build checks for interruption every 64 classes, so cancelling a run stops indexing instead of
  waiting for the whole classpath to be scanned.
- Class and resource reads use random-access `ZipFile` where possible and a sequential Commons
  Compress fallback for duplicate-entry archives. If Android cannot index a duplicate-entry parent,
  the compile cache stores only a deduplicated `.class` overlay rather than a second full-size copy.
- A changed parent JAR hash clears the old source cache before new classes are materialized.

## Current Patch-Mod Format

The launcher packages files supplied by the agent. Code patches must be compiled through
`compile_agent_patch_source` first; resources and manifest changes can be generated directly.

The legacy direct JAR patch flow remains available for compatibility, but new AI instructions use patch
mods by default.
