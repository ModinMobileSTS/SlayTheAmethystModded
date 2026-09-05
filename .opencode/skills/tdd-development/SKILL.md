---
name: tdd-development
description: Guide test-driven development for code changes by enforcing a red-green-refactor loop, minimal behavior tests, focused implementation, and relevant verification commands. Use when asked to implement, fix, refactor, or review code using TDD.
---

# TDD Development

## Quick Start

1. Define the smallest observable behavior that proves the requested change.
2. Add or update the minimal failing test before changing production code.
3. Run the smallest relevant test command and confirm the failure is expected.
4. Implement the smallest production change that makes the test pass.
5. Rerun the same test, then refactor only when it improves clarity without changing behavior.
6. Finish with the relevant regression command and report Red, Green, Refactor, and verification results.

## Workflow

1. Clarify the behavior.
- Restate the requested behavior, bug, or refactor target in testable terms.
- Identify the smallest unit or integration boundary that can prove it.
- Search for nearby tests and existing test style before creating a new test file.
- If requirements are ambiguous, ask one focused question before writing tests.

2. Red: write the failing test.
- Add the smallest test that fails for the right reason.
- Prefer behavior assertions over implementation details.
- Cover important edge cases only when they are part of the requested behavior or likely regression surface.
- Do not edit production code during the Red step unless needed to expose a test seam that already exists in the design.

3. Verify Red.
- Run the narrowest relevant test command.
- Confirm the test fails because the behavior is missing or broken.
- If the test cannot run locally, state the blocker and use the closest static or build verification available.
- If the test passes unexpectedly, stop and reassess whether the behavior already exists or the test is insufficient.

4. Green: implement minimally.
- Make the smallest production change that satisfies the failing test.
- Keep the change local to the behavior under test.
- Avoid opportunistic cleanup, broad rewrites, new abstractions, or unrelated fixes.
- Preserve existing architecture and style.

5. Verify Green.
- Rerun the exact Red command first.
- If it passes, run the next relevant regression command when practical.
- If it fails for a different reason, diagnose and keep the loop focused.

6. Refactor safely.
- Refactor only when the passing test protects the behavior and the cleanup is worthwhile.
- Keep refactors small and behavior-preserving.
- Rerun the relevant tests after refactoring.
- Do not combine unrelated refactors with the TDD change.

7. Report clearly.
- State the Red test added or changed.
- State the Green implementation change.
- State any Refactor step or say none was needed.
- List commands run and their results.
- Call out skipped verification, environmental blockers, and residual risks.

## Project Test Commands

- Android app unit tests: `./gradlew :app:testDebugUnitTest`
- Android app local tests: `./gradlew :app:test`
- JVM module tests: `./gradlew :boot-bridge:test`, `./gradlew :patches:gdx-patch:test`, `./gradlew :workshop-core:test`, or the matching module task.
- Mod module tests: `./gradlew :mods:amethyst-runtime-compat:test` or `./gradlew :mods:ram-saver:test` when available.
- Broader regression: `./gradlew test` or `./gradlew check` when the change spans modules and runtime cost is acceptable.
- Instrumented or UI behavior: prefer a local unit test first; use `connectedAndroidTest` only when a device or emulator is available and the behavior requires it.

## Command-Specific Modes

- Full TDD mode: complete Clarify, Red, Verify Red, Green, Verify Green, optional Refactor, and final report.
- Red-only mode: stop after adding the failing test and confirming the expected failure.
- Green-only mode: start from an existing failing test, implement minimally, and verify the test passes.
- Refactor-only mode: require passing tests first, refactor narrowly, then rerun relevant tests.
- Review mode: inspect whether an existing change has meaningful tests, whether tests assert behavior, and whether implementation scope is minimal.

## Anti-Patterns To Avoid

- Writing production code before a failing test when the request explicitly asks for TDD.
- Adding broad snapshot or brittle implementation-detail tests as the primary proof.
- Skipping the Red verification and claiming TDD based only on a final passing test.
- Expanding the task into unrelated cleanup or architecture changes.
- Silencing or weakening tests to reach Green.
- Treating unrun tests as passed.

## Response Rules

- Be explicit about the current phase: Red, Green, Refactor, or verification.
- Cite file paths for tests and production changes.
- Include exact commands run.
- If a command fails, summarize the relevant failure and next action.
- Keep the final summary concise and focused on behavior, tests, and verification.
