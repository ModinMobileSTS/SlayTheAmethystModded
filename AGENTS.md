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

## Device safety

- **如果用户没有明确指定本次操作的目标设备，禁止进行任何安装、覆盖安装、降级安装或卸载应用的操作。** 唯一在线设备、自动发现的设备、脚本默认值、历史对话中的设备、已有环境变量都不等于用户授权。
- 即使指定了设备，也必须先获得安装/卸载操作本身的明确授权；普通的测试、回归或性能验证请求不包含这项授权。命令必须绑定该具体设备序列号，禁止自动选择或回退到其他设备。
- 禁止通过真机测试任务间接安装或卸载应用，包括 `connected*AndroidTest`、`connectedCheck`、托管设备测试和 macrobenchmark；这些任务可能由 AGP/UTP 在结束时自动卸载主应用并丢失数据。本仓库已禁用此类自动部署任务。
- 冒烟、单房间、性能、启动缓存、Steam Cloud 等测试只能使用设备上已安装的应用，不得自动构建后安装。默认使用本地编译、Android 测试代码编译和 JVM/mock 测试验证。
- 不得在操作失败后擅自重装、卸载、清除应用数据或恢复设备；需要这些操作时，重新说明目标设备、包名及数据风险并取得授权。
