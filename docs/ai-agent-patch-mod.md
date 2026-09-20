# AI Patch Mod Workflow

## Scope

The AI mod editor lets the agent build a separate, versioned patch-mod revision for the selected mod.
The original mod JAR is extracted for inspection and is never rewritten.

The patch-mod workspace is **not** created when the editor opens. It is created on demand by the
agent through the `create_agent_patch_mod` tool, and only when the user actually asks for a change.
Opening the editor or starting a new conversation must not generate a workspace.

## Storage Layout

The launcher stores AI artifacts under the STS runtime root:

```text
agent_workspace/<modid>/
  source/                 # parent mod extracted/decompiled; readable context, agent read-only
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

## Agent Tools

The editor exposes these tools for the selected mod workspace:

- `list_agent_workspace`: list all extracted and generated files.
- `read_agent_workspace_file`: read UTF-8 or base64 content from any workspace file.
- `create_agent_patch_mod`: create and activate a new patch revision. Required arguments: `name` (the agent
  chooses it), optional `version` and `description`. Extracts the full parent JAR into the shared
  `source/` tree and seeds a new `patch_source/<patch_id>/` with `ModTheSpire.json` and
  `agent-patch.json`. Calling it again creates and activates another isolated patch revision.
- `write_agent_workspace_file`: create or replace files only under `patch_source/`. For patch-mod
  work, the agent uses the `patch_workspace_path` returned by `create_agent_patch_mod`, while
  `source/` remains read-only context.
- `delete_agent_workspace_file`: delete files or, with `recursive=true`, non-empty directories only
  under `patch_source/`. The `source/` tree and `patch_source/` root cannot be deleted.
- `decompile_agent_mod_source`: extract and decompile the parent mod directly into the shared
  `source/` tree. The tree is available to the agent through read/list tools and cannot be modified
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
  `patch_source/<patch_id>/src/` into Java 8 `.class` files at that patch root. Pass `patch_id` to
  compile an existing revision that was not created in the current session. See "On-device
  compilation" below.
- `package_agent_patch_mod`: validate and package the active `patch_source/<patch_id>/` into
  `agent_mods/<modid>/`. Rejects
  packaging when Java sources are present but no classes have been compiled.
- `update_agent_patch_mod`: repackage an already packaged revision in place from its patch
  workspace with a new `version` (and optional `name`/`description`). The `patch_id`, patch mod id,
  and enabled state are unchanged, so updating never creates a duplicate or forces re-enabling.
  Runs the same bytecode preflight as packaging and refuses to replace the JAR when it fails.
- `smoke_test_agent_patch_mod`: launch the game with one packaged revision and report whether the
  main menu was reached. Optional `patch_id` (defaults to the current session's revision) and
  `timeout_seconds` (default `240`). See "Smoke test" below.
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

`AgentPatchSmokeTestModList` holds the ordering logic; `AgentPatchSmokeTest` owns the run:

- It runs in the launcher (main) process and reuses the normal launch path by passing this mod set as
  a `LaunchModSnapshot` override into `MainProcessLaunchPreparationCoordinator.prepareBeforeLaunch`,
  which writes it to `.mts_mod_file_list`.
- It invalidates the MTS classpath and patch caches before and after the run, because otherwise the
  patch cache would skip the patch pass and the test would be meaningless. Interactive launches are
  unaffected: they still reuse the cache.
- It opens the real `StsGameActivity`; Android cannot run the GL game headless. The test is silent in
  that it needs no user interaction and shuts the game down as soon as the verdict is known.
- The verdict comes from the boot bridge terminal events plus a crash-marker scan of the part of
  `latest.log` this launch wrote. A failure returns the boot events and a log excerpt so the agent
  can fix the patch.
- The game is stopped by terminating the `:game` process with the pending-launch marker cleared,
  never through `.harness_exit_request`: that route restarts `LauncherActivity` with
  `FLAG_ACTIVITY_CLEAR_TASK`, which would destroy the AI editor and cancel the tool call in flight.

## Launcher Lifecycle

1. Opening AI Edit creates no workspace. It only prepares the parent's conversation directory.
2. When the user asks for inspection, the agent calls `decompile_agent_mod_source`, which extracts
   and decompiles the parent directly into `<modid>/source/`.
3. When the user asks for a change, the agent calls `create_agent_patch_mod`, which prepares the
   shared `source/`, allocates a `patch_id`, and creates writable
   `<modid>/patch_source/<patch_id>/` for packaging.
4. The agent reads the shared read-only source tree and writes a self-contained patch mod under its
   dedicated `patch_source/<patch_id>/`.
5. Packaging writes a JAR under `agent_mods/<modid>/` and injects the parent dependency into its manifest.
6. The agent calls `smoke_test_agent_patch_mod` to prove the revision reaches the main menu.
7. The normal ModTheSpire launch preparation loads enabled patch JARs directly from `agent_mods/`.
8. The parent mod card shows its packaged patch revisions when expanded. Revisions expose enable
   switches; the agent can also toggle them with `set_agent_patch_mod_enabled`.

Patch mods are hidden from the top-level mod list and displayed under their parent. Their manifest
dependency also prevents a patch from being enabled without its parent.

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
- Source extraction, patch packaging, entry count, per-entry size, and total JAR size are bounded.
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
- Compilation is pinned to `-source 1.8 -target 1.8` because the game runs on the embedded OpenJDK 8
  JVM and ModTheSpire expects Java 8 bytecode.
- The bootclasspath is the installed runtime's `rt.jar`, and the classpath is the game jar, ModTheSpire,
  the required mods, and the parent mod being patched. This is required because ART's `java.*` surface
  differs from the JRE the resulting mod runs on; ECJ only reads those class files for symbol resolution.
- Diagnostics from ECJ are returned to the agent so it can fix source before packaging.
- `inspect_agent_patch_target` reads the installed game/mod class files with ASM and returns the exact
  method or constructor overloads, JVM descriptors, parameter types, return type, and fields. It does
  not load game classes into Android or infer signatures from decompiler text.
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
  workspace.
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
  detects unreadable jars and substitutes a cached, duplicate-free copy produced by
  `DuplicateZipEntryNormalizer.copyDeduplicated`. A patched `desktop-1.0.jar` is already
  duplicate-free, so the common path copies nothing. The copy is content-addressed by name, size,
  and mtime, so it is reused across compiles. It lives under the app cache, falling back to internal
  storage when `cacheDir` is unusable, and is registered in `LauncherJunkFileCleaner`.
- When a classpath jar still cannot be indexed (for example no writable scratch directory), the
  compile diagnostics list it under a warning instead of silently dropping it.

Compiling arbitrary Java is intentionally limited to Java 8 language/bytecode and to the bundled
compile classpath; the workflow does not run Gradle or fetch dependencies on the device.

## On-device decompilation

The parent JAR is bytecode, so the agent needs readable source to know what a class does before
rewriting it. `decompile_agent_mod_source` extracts the parent JAR and decompiles the whole mod into
`<modid>/source/`; `.java` files are written next to (and replace)
the raw `.class` files, while resources are kept as-is. Classes CFR could not decompile keep their
`.class` so nothing is lost. The resulting source tree is readable agent context and is protected from
workspace writes/deletes. Agent-authored patch sources and generated patch metadata live under the
separate writable `patch_source/<patch_id>/` tree for each patch mod.

- CFR (`org.benf:cfr`, 0.152) is bundled in the APK and invoked through its `CfrDriver` API from the
  launcher process. CFR is compiled to class file 50 and does not execute the class it reads.
- Decompilation is bounded: above `AgentPatchClassDecompiler.DEFAULT_MAX_DECOMPILE_CLASSES` (3000) the
  archive is left as raw bytecode so a large parent mod cannot stall the launcher. `source-metadata.json`
  records `source_decompiled` and the class counts.
- Decompilation reuses the same deduplicated compile classpath used for ECJ, so references resolve.

## Current Patch-Mod Format

The launcher packages files supplied by the agent. Code patches must be compiled through
`compile_agent_patch_source` first; resources and manifest changes can be generated directly.

The legacy direct JAR patch flow remains available for compatibility, but new AI instructions use patch
mods by default.
