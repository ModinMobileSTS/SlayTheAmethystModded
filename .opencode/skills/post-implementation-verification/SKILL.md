---
name: post-implementation-verification
description: Verify implementation changes with risk-based tests after implementation, including focused regression coverage and honest reporting. Use for changes needing systematic post-implementation testing or when explicitly requested.
---

# Post-implementation verification

Use this lightweight workflow for implementation changes needing systematic testing. The main agent
owns implementation, tests and final acceptance; do not automatically delegate or require independent
review. Follow applicable AGENTS.md rules and preserve existing user changes.

1. Before implementation, identify the accepted behavior, important boundaries and a practical
   verification approach. Resolve material ambiguity with the user. Tests may be written afterward.
2. Implement the change and make any necessary scoped refactoring. Avoid unrelated changes.
3. After implementation, add or update focused tests where feasible, based on the requirements rather
   than merely mirroring the implementation. Cover important normal, boundary, error and regression
   behavior in proportion to risk. Use existing test infrastructure and the affected module's actual
   toolchain; do not add a test framework or expand scope without authorization.
4. For bug fixes, when practical and safe, establish that the regression test fails against the old
   behavior and passes with the fix. Never discard or overwrite user changes to do this, and do not
   fabricate a failure. If this comparison is not safe or feasible, state the missing evidence.
5. Run focused tests and any broader checks justified by the affected behavior. Investigate failures,
   fix defects and rerun the relevant checks. Do not weaken assertions, skip tests or change accepted
   requirements merely to pass. A build or static check alone does not prove runtime behavior.
6. Review the final diff and required documentation. Report commands and outcomes, test coverage,
   failures and checks not run or blocked. Do not claim unverified behavior works.

## Project verification

Select existing commands and prerequisites from the affected module's build files, tests and docs;
do not assume a single runner covers the repository. Examples, from the repository root:

- OpenCode workflow: `node --test .opencode/tests/post-implementation-verification.test.mjs`;
  broader plugin tests: `npm test --prefix .opencode`; type check: `npm run typecheck --prefix .opencode`.
- Android JVM: `./gradlew :app:testDebugUnitTest --tests '<test class>'`.
- JVM modules: select the affected module's existing test task and tests, for example
  `./gradlew :workshop-core:test --tests '<test class>'`.
- Node services and Python scripts: select focused offline tests from the affected module's own
  package, runner and test conventions. Inspect side effects before running them.

These examples are entry points, not promises that dependencies or runtime coverage are available.
Missing infrastructure or prerequisites must be reported; do not install dependencies without
authorization. Device, deployment and live-service checks need separate user authorization. Follow
AGENTS.md for Android tools and mod documentation. Offline tests do not establish device, driver,
Android UI or gameplay runtime correctness.
