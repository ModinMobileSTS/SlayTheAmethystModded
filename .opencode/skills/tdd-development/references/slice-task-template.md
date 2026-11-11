# Slice task and stage handoff

All project paths are repository-root-relative unless labeled otherwise. Complete before dispatch:

```text
Slice ID / one behavior:
Project context path:
Applicable AGENTS.md / module instructions:
Accepted design clauses and acceptance authority:
Acceptance criteria (observable inputs, outputs, effects, required failures):
Non-goals / unresolved clauses excluded:
Allowed production paths:
Allowed test/fixture paths:
Frozen / forbidden paths:
Verification commands: exact command, working directory, check type, expected result, prerequisites:
Broader verification trigger and authorized commands:
Baseline changes / attribution / no overlapping writers:
Documentation owner, required paths and integration before Review:
```

For each child include all preceding context plus:

```text
Phase / role:
Allowed paths for this phase:
Frozen paths (Red tests for Green/Refactor):
Previous evidence: exact command/cwd/exit, test/result/reason, evidence excerpt or artifact path:
Scoped diff / baseline distinction:
Required output and gate:
Review findings to address, if rework:
```

Stage return:

```text
Status: Complete | Partial | Blocked | Failed | No change required
Phase / slice:
Changed paths attributable to this phase:
Criteria covered / remaining:
Verification: PASS | FAIL | NOT RUN | BLOCKED
  Exact command / working directory / check type / exit result:
  Relevant test and evidence excerpt or artifact path:
Red classification: behavioral | structural | unexpected pass | infrastructure blocker
Frozen-path preservation:
Remaining blocker / risk / bounded next step:
```

Final slice report summarizes all phases, optional independent checks, Review and unresolved gaps.
