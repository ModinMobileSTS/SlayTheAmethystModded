# Repository Rules

## Mod-side fixes

- If a gameplay/runtime fix is shipped through a mod under `mods/`, document every individual fix in that mod's `README.md` in the same change.
- The mod `README.md` must state what each fix does, what symptom it addresses, and which patch class implements it.
- Do not accumulate unrelated Spire patches in one monolithic patch file. Split them by fix domain so each fix can be reviewed and reverted independently.

## Storage

- 对话过程中产生的反编译文件和临时文件等无需提交的文件放到：agent-tmp 目录下。

## Serena MCP

- 项目根目录的 `opencode.json` 为本仓库启用 Serena MCP（`--context=ide`，语言服务器为 Kotlin + Python）。探索/检索 Kotlin、Python 代码时优先使用 `serena_*` 符号工具（`serena_get_symbols_overview`、`serena_find_symbol`、`serena_find_referencing_symbols`、`serena_replace_symbol_body`），不要动辄整文件读取或全文 grep。
- `.serena/project.yml` 里固定了 `kotlin_lsp_version`：JetBrains 的 intellij-server 构建约 40 天后自过期。若 Kotlin 工具报 `This build of intellij-server has expired`，把该版本号更新为 https://github.com/Kotlin/kotlin-lsp/releases 上的最新 tag 即可。

## Git

- 提交 Git 时，应当注意 message 规范：feat：新特性、fix：修复问题、perf：性能更改、chores：其它工作。