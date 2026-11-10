# Repository Rules

## Local Android tools

- Use `sts` tools for Android operations. They automatically use the workspace-owned wireless ADB configured in ignored `.env.local`.
- For CLI operations use `python3 -m scripts.tools.local_adb adb -- <device-command>`; for Gradle device tasks use `python3 -m scripts.tools.local_adb run -- ./gradlew <task>`.
- Do not run bare/global adb, change the machine-wide server, or hardcode/commit device endpoints. Local lifecycle: `python3 -m scripts.tools.local_adb ensure|status|stop`.

## Mod-side fixes

- If a gameplay/runtime fix is shipped through a mod under `mods/`, document every individual fix in that mod's `README.md` in the same change.
- The mod `README.md` must state what each fix does, what symptom it addresses, and which patch class implements it.
- Do not accumulate unrelated Spire patches in one monolithic patch file. Split them by fix domain so each fix can be reviewed and reverted independently.

## Storage

- 对话过程中产生的反编译文件和临时文件等无需提交的文件放到：agent-tmp 目录下。

## Serena MCP

- 项目根目录的 `opencode.json` 为本仓库启用 Serena MCP（`--context=ide`，语言服务器为 Kotlin + Python）。探索/检索 Kotlin、Python 代码时优先使用 `serena_*` 符号工具（`serena_get_symbols_overview`、`serena_find_symbol`、`serena_find_referencing_symbols`、`serena_replace_symbol_body`），不要动辄整文件读取或全文 grep。
- `.serena/project.yml` 里固定了 `kotlin_lsp_version`：JetBrains 的 intellij-server 构建约 40 天后自过期。若 Kotlin 工具报 `This build of intellij-server has expired`，把该版本号更新为 https://github.com/Kotlin/kotlin-lsp/releases 上的最新 tag 即可。

## TDD workflow (opt-in)

- Because this workflow is costly, do not invoke it proactively. Use it only when the user explicitly requests this TDD workflow or the `tdd-development` skill. Otherwise, implement and verify changes using the appropriate ordinary project practices.
- When explicitly requested for behavior slices with existing deterministic offline tests, use `.opencode/skills/tdd-development/SKILL.md` and project context `.opencode/skills/tdd-development/project/slay-the-amethyst.md`. The parent owns accepted requirements, slice selection, integration and final acceptance; delegate implementation through `tdd-slice` with the complete slice-task template.
- `tdd-slice` is read-only and dispatches exactly one slice through serial foreground `tdd-red`, `tdd-green`, `tdd-refactor`, optional `test-runner`, and mandatory `code-reviewer`. It is the only nested-delegation exception; leaves never delegate. Keep `experimental.subagent_depth` at 2, not unrestricted depth or independent sessions to bypass gates.
- Red edits only assigned tests/fixtures and establishes expected behavior failure (or explicitly required missing-API structural Red). Infrastructure failures are blockers. Green/Refactor edit only assigned production paths; tests are frozen. No overlapping writers or unrelated cleanup. The parent/dispatcher accepts stage evidence rather than running normal slice tests in their place.
- Use the affected module's real verification, not one language's default. Missing test infrastructure needs a separately authorized task before Green. Documentation, workflow bootstrapping and pure behavior-preserving refactors do not require fabricated Red. Unsupported UI/native/runtime work must state its verification limits rather than claim TDD coverage.
- Required documentation is parent-owned and integrated before Review, including every Mod fix's README entry. Every stage reports commands, working directories, actual results and gaps. Preserve existing changes; no stage commits, installs dependencies, deploys, operates devices or contacts live services.
- Device/runtime/live-service checks are separately authorized parent work via existing supported tools, not a local TDD gate. TDD agents deny `sts_*`; broad shell/edit permissions are not per-slice isolation and must not bypass role restrictions.
- Only an explicit toolchain self-check may use the skill's maintenance exception: static read-only configuration review, then the supplied existing offline check. No writers, Red/Green/Refactor, denied-command probes or product-completion claim.

## Git conventions

- 提交 Git 时，应当注意 message 规范：feat：新特性、fix：修复问题、perf：性能更改、chores：其它工作。
