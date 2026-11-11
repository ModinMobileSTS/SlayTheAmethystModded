---
name: tdd-development
description: "Dispatch one accepted-design behavior slice through serial Red, Green, Refactor and independent review with explicit project context."
---

# Single-slice TDD

## Ownership and inputs

The parent owns accepted design, slice selection, integration and final acceptance. The read-only
tdd-slice dispatcher owns gates, not implementation. Red, Green and Refactor are separate leaf agents.
Require the complete references/slice-task-template.md handoff and an explicit project-context path.
Read applicable AGENTS.md, that context and only necessary design/module references. Draft proposals
are not accepted contracts. Missing semantics, scope or verification prerequisites means Blocked.
Use references/project-context-template.md for localization; generic procedure has no project toolchain.
Preserve existing changes and distinguish them from slice edits. No overlapping writers. Stop on
unexpected edits instead of overwriting them or attributing them to a stage.

## Gates

1. Accept exactly one independently testable behavior. No feature decomposition by the dispatcher.
2. **Red — tdd-red:** edit only assigned tests/fixtures and run the focused command. Require an
   expected behavioral assertion failure, or structural Red for a missing API explicitly required by
   accepted design. Report these separately. Infrastructure, dependency and unrelated compilation
   failures are Blocked, not Red. Unexpected pass requires coverage explanation and No change required;
   never break working code or proceed silently to Green.
3. **Green — tdd-green:** tests are frozen. Edit only assigned production paths. Require passing
   focused tests, scoped diff and frozen-file confirmation. Implement the full accepted behavior, not
   hardcoded examples; no skipped/weakened tests, swallowed errors or success-shaped fallbacks.
4. **Refactor — tdd-refactor:** require Green first. Simplify this slice only, with unchanged behavior.
   No change is valid. Tests stay frozen unless parent-authorized structural cleanup preserves meaning.
   Require passing focused verification after edits; broaden only for affected shared behavior using
   explicitly authorized checks.
5. **Optional test-runner:** independently rerun supplied checks when requested. Do not mandate a
   duplicate runner at every stage. Static checks/builds do not replace behavior tests.
6. **Review — code-reviewer:** provide accepted design, criteria, slice diff/baseline attribution,
   frozen paths, integrated required documentation and all stage evidence. Concrete correctness or
   accepted-requirement gaps block; hypothetical future needs or style preferences do not.
7. Report and stop after this slice. Never claim completion with required checks blocked.

Dispatch children serially in the foreground. Each receives a complete handoff; prior context and
loaded skills are not inherited. The dispatcher inspects diffs/evidence but never edits or runs tests.
The parent must not substitute its own test runs for stage evidence during a normal slice.
Only the dispatcher may delegate to the five named leaf roles; leaves never delegate. Use native V2
shell/subagent permissions and a depth of two. Do not bypass boundaries through sessions or tools.
Dispatcher/reviewer shell access is separate git status / git diff calls only, without compound
commands, pipelines or redirection. Read files using read, not shell cat.

## Failures and rework

- Implementation defect: Green, Refactor, required verification, Review again.
- Missing/incorrect tests: return to Red and establish effective Red. If supplemental coverage already
  passes current code, the parent decides whether to accept it; do not fabricate historical Red.
- New requirements, contract conflicts and out-of-scope failures: Blocked, return to parent.
- Allow at most two corrective cycles per slice; then return Partial or Blocked with evidence.
- No stage installs dependencies, changes Git index/history, deploys, operates devices or contacts
  live services. Parent authorization for external checks is separate from the local TDD chain.
- New test infrastructure requires a separate authorized task, not creation inside a behavior slice.
- Documentation-only changes, workflow bootstrapping and pure behavior-preserving refactors route
  outside the behavior-slice dispatcher; do not manufacture Red for them.

## Maintenance self-check

Only an explicit parent request enables toolchain self-check, never an incomplete product handoff.
Require configuration scope, project context, an existing offline check, exact command and working
directory. Read configuration, inspect separate git status / git diff calls, then serially dispatch
code-reviewer for static configuration/deny-rule review and test-runner for only that check.
No Red/Green/Refactor, file edits, new tests, agents, permission expansion or denied-command probes.
Stop on blockers and report commands/results/gaps as toolchain evidence, not product completion.

## Simplicity and reporting

Every changed line serves accepted requirements. Use clear idiomatic code and existing helpers;
avoid speculative features and abstractions, and do not merge superficially similar business rules.
Required validation is not overengineering. Parent-owned documentation must be integrated before Review.
Broad writer/shell permissions are not a per-slice sandbox: audit actual diffs and commands, and
never use shell, plugins or alternate tools to bypass role boundaries.

Status: Complete | Partial | Blocked | Failed | No change required.
Report design/slice, attributable paths, Red classification/evidence, Green results, Refactor changes
or no-change reason, Review findings/disposition and unresolved checks. Each check has
PASS | FAIL | NOT RUN | BLOCKED, exact command, working directory, exit result and evidence excerpt/path.
Never infer a passing check from a stage status or claim an unrun check passed.
