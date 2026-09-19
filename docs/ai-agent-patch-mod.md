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
  inspection/<inspection_id>/
    inspection-metadata.json
    source/               # parent mod extracted and optionally decompiled for inspection

  <patch_id>/
    mod-metadata.json
    source/               # parent mod extracted as read-only patch context
    patch/                # files written by the agent
      ModTheSpire.json
      agent-patch.json

agent_workspace/.conversations/<modid>/conversations.json
agent_mods/<modid>/
  amethyst.ai.patch.<modid>.<patch_id>.jar
```

`agent-patch.json` records the parent mod ID, patch ID, patch mod ID, display name, version, and
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
- `create_agent_patch_mod`: create the current patch revision. Required arguments: `name` (the agent
  chooses it), optional `version` and `description`. Extracts the full parent JAR into `source/` and
  seeds `patch/` with `ModTheSpire.json` and `agent-patch.json`. It does not decompile; call
  `decompile_agent_mod_source` separately. Idempotent within a session: calling it again reports the
  existing revision instead of creating a second one.
- `write_agent_workspace_file`: create or replace any file under the selected mod's entire agent
  workspace, including inspection trees and previous patch revisions.
- `delete_agent_workspace_file`: delete any file or, with `recursive=true`, a non-empty directory under
  the selected mod's entire agent workspace. The workspace root itself cannot be deleted.
- `decompile_agent_mod_source`: independently extract and decompile the parent mod into an
  `inspection/<inspection_id>/source/` tree. It does not create or require a patch workspace. See
  "On-device decompilation" below.
- `compile_agent_patch_source`: compile Java sources under `patch/src/` into Java 8 `.class` files at
  the patch root. See "On-device compilation" below.
- `package_agent_patch_mod`: validate and package `patch/` into `agent_mods/<modid>/`. Rejects
  packaging when Java sources are present but no classes have been compiled.
- `set_agent_patch_mod_enabled`: enable or disable a packaged patch revision for the parent mod.
- `list_agent_patch_mods`: list packaged revisions and their enabled state.
- `delete_agent_patch_mod`: delete a packaged revision by `patch_id` and clear its enablement.

Enablement is a separate launcher-managed operation and is never implied by packaging. The delete tool
asks for confirmation; it removes the packaged JAR and its enabled selection.

## Launcher Lifecycle

1. Opening AI Edit creates no workspace. It only prepares the parent's conversation directory.
2. When the user only asks for inspection, the agent calls `decompile_agent_mod_source`, which creates
   an independent inspection revision under `inspection/<inspection_id>/source/`.
3. When the user asks for a change, the agent calls `create_agent_patch_mod`, which allocates a new
   `patch_id` and extracts the full parent JAR into that patch revision's `source/`.
4. The agent reads the independent inspection source and writes a self-contained patch mod under `patch/`.
5. Packaging writes a JAR under `agent_mods/<modid>/` and injects the parent dependency into its manifest.
6. The normal ModTheSpire launch preparation loads enabled patch JARs directly from `agent_mods/`.
7. The parent mod card shows its packaged patch revisions when expanded. Revisions expose enable
   switches; the agent can also toggle them with `set_agent_patch_mod_enabled`.

Patch mods are hidden from the top-level mod list and displayed under their parent. Their manifest
dependency also prevents a patch from being enabled without its parent.

## Safety Boundaries

- Workspace paths are canonicalized and reject absolute paths, drive prefixes, empty path segments, `.`
  and `..` segments.
- Source extraction, patch packaging, entry count, per-entry size, and total JAR size are bounded.
- A patch package must contain both root `ModTheSpire.json` and `agent-patch.json`.
- Package output is written through a temporary file and verified as a readable ZIP before replacement.
- The original parent JAR is never modified by the patch-mod workflow.
- Destructive or workspace-mutating tools (`PATCH_CREATE`, `MOD_INSPECTION`, `PATCH_COMPILE`,
  `PATCH_PACKAGE`, `PATCH_ENABLE`, `PATCH_DELETE`, `WORKSPACE_WRITE`) are gated by the policy
  allow-list in the editor.

## On-device compilation

The AI editor compiles patch source on the device; no host toolchain is involved.

- The launcher runs on Android/ART, which has no `javax.tools` compiler. ECJ (`org.eclipse.jdt:ecj`,
  3.26.0) is bundled in the APK and invoked through `org.eclipse.jdt.core.compiler.batch.BatchCompiler`
  from the launcher process.
- Sources are written under `patch/src/<package>/...` and compiled to `.class` files at the patch root,
  so the packaged JAR is a normal ModTheSpire mod. `patch/src/` itself is excluded from the package.
- Compilation is pinned to `-source 1.8 -target 1.8` because the game runs on the embedded OpenJDK 8
  JVM and ModTheSpire expects Java 8 bytecode.
- The bootclasspath is the installed runtime's `rt.jar`, and the classpath is the game jar, ModTheSpire,
  the required mods, and the parent mod being patched. This is required because ART's `java.*` surface
  differs from the JRE the resulting mod runs on; ECJ only reads those class files for symbol resolution.
- Diagnostics from ECJ are returned to the agent so it can fix source before packaging.

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
rewriting it. `decompile_agent_mod_source` independently extracts the parent JAR and decompiles the
whole mod into `inspection/<inspection_id>/source/`; `.java` files are written next to (and replace)
the raw `.class` files, while resources are kept as-is. Classes CFR could not decompile keep their
`.class` so nothing is lost. Inspection and patch creation have separate storage, state, and tool
preconditions; either operation can be used without invoking the other.

- CFR (`org.benf:cfr`, 0.152) is bundled in the APK and invoked through its `CfrDriver` API from the
  launcher process. CFR is compiled to class file 50 and does not execute the class it reads.
- Decompilation is bounded: above `AgentPatchClassDecompiler.DEFAULT_MAX_DECOMPILE_CLASSES` (3000) the
  archive is left as raw bytecode so a large parent mod cannot stall the launcher. `mod-metadata.json`
  records `source_decompiled` and the class counts.
- Decompilation reuses the same deduplicated compile classpath used for ECJ, so references resolve.

## Current Patch-Mod Format

The launcher packages files supplied by the agent. Code patches must be compiled through
`compile_agent_patch_source` first; resources and manifest changes can be generated directly.

The legacy direct JAR patch flow remains available for compatibility, but new AI instructions use patch
mods by default.
