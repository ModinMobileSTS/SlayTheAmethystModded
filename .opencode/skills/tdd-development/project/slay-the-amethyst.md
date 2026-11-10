# SlayTheAmethyst TDD context

Paths and commands below are relative to the repository root unless a working directory is stated.
AGENTS.md remains authoritative. This file describes tools, not permission grants or accepted design.

## Authority and adoption

- The parent supplies accepted user requirements/design clauses and observable acceptance criteria.
  There is no single repository-wide design document; select the relevant docs for each slice.
  Drafts, archives and neighboring repositories are not automatically binding contracts.
- Initial adoption covers behavior slices with existing deterministic offline tests. Missing local
  test infrastructure means Blocked before Green; authorize infrastructure as a separate task.
- The parent owns required documentation and integrates it before Review. For mods, AGENTS.md requires
  each fix's README.md entry to explain behavior, symptom and patch class. Keep patch domains separate.

## Existing verification entry points

These are entry points from checked-in configuration, not claims they pass in every environment.
The handoff must select a focused test, cwd, prerequisites and authorized broader checks.

| Area | Authority | Local verification |
| --- | --- | --- |
| OpenCode plugin/workflow | .opencode/package.json; .opencode/README.md | From root: `npm test --prefix .opencode`; focused workflow static regression: `node --test .opencode/tests/tdd-workflow.test.mjs`; type check separately: `npm run typecheck --prefix .opencode`. Harness tests use a fake CLI, not devices. |
| Android app JVM unit tests | app/build.gradle.kts | From root: `./gradlew :app:testDebugUnitTest --tests '<accepted test class>'`. Requires Android SDK/JDK and existing dependencies; not Android UI/runtime coverage. |
| Pure JVM modules | workshop-core/build.gradle.kts; lan-core/build.gradle.kts; steam-protocol/build.gradle.kts | From root: `./gradlew :workshop-core:test --tests '<accepted test class>'` (select the actual module). These modules declare JDK 21; inspect test side effects before selecting. |
| Bridges, game probe, mods and patches | Each module's build.gradle.kts and existing tests | Select the actual existing task/test; some require local game JARs. Do not infer a working task or runtime fidelity merely from a JUnit dependency. |
| Online service | online-service/package.json | cwd `online-service`: `node --test test/<selected>.test.js`; broader `npm test`. Existing Node dependencies required. |
| Cloud function | cloud-function/package.json | cwd `cloud-function`: `node --test test/<selected>.test.js`; broader `npm test`. Do not invoke live integrations. |
| Python harness/build scripts | scripts/tools/tests/, scripts/tools/harness/tests/, scripts/build/tests/ and the selected file's runner | Determine the existing runner from that test's conventions and documentation. Select mocked/offline cases; no blanket test-directory execution that may start real connectors. |
| JNI/C++, UI, benchmark and other areas | Affected module's scripts/docs | No universal offline command established here. Verify the actual entry point before dispatch; missing infrastructure blocks behavior slices. |

Gradle may need cached artifacts or dependency downloads; missing dependencies are infrastructure
blockers. Do not install tooling or introduce dependencies inside stages. A build is not a behavior test.
Explicit app tasks such as aiCacheAcceptanceTest and aiCompactionAcceptanceTest use real provider
credentials/services. Network acceptance, Steam network simulations, performance baselines and
device benchmarks are not default gates; require separate parent scope and explicit authorization.
Do not read credentials as part of local verification.

## Boundaries and research

- Preserve user work. No stage modifies Git index/history, deploys or operates devices/live services.
- Android operations remain parent-owned, separately authorized through sts tools and workspace-owned
  ADB per AGENTS.md and .opencode/README.md. Never bare/global adb. Local tests do not prove driver,
  Android UI, classloading or gameplay runtime behavior; device smoke cannot replace unit semantics.
- Stage sts_* tools are explicitly denied. Broad shell/edit permission is not a sandbox: review
  actual commands/diffs; never invoke a wrapper, plugin or alternate tool to bypass role boundaries.
- Prefer Serena symbol tools for Kotlin/Python when available. The checked-in opencode.json currently
  has Serena disabled; fall back to scoped reads/searches without changing unrelated MCP configuration.
- Temporary diagnostics/decompilations and evidence go under ignored agent-tmp/. Keep normal build
  outputs in existing build directories; no secrets, endpoints or local environment files in commits.

## Invocation and maintenance

Pass this context explicitly to tdd-slice with the completed references/slice-task-template.md.
No stage decomposes features or invents accepted semantics. Normal behavior stages run serially.
For an explicit toolchain self-check, supply the six agent files, skill/context/templates, opencode.json,
AGENTS.md and .opencode/README.md as review scope, and the existing offline command
`node --test .opencode/tests/tdd-workflow.test.mjs` from root. This checks static configuration
declarations only, not runtime permission enforcement, nested dispatch or product behavior.
