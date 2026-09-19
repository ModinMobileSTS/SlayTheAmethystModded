# 现成 Agent 工具调研

更新日期：2026-09-17

## 1. 结论

没有现成项目能够直接嵌入 Android APK，同时满足以下全部要求：OpenAI/DeepSeek 可切换、云端模型自主 tool calling、本机受控文件/JAR/游戏工具、用户审批、任务恢复，以及真正隔离的任意命令执行。

可复用部分已经成熟，应采用组合方案：

| 需求 | 推荐工具 | 结论 |
| --- | --- | --- |
| OpenAI/DeepSeek Agent 编排 | **LangChain4j** 先做 Android PoC；不通过则保留自有轻量 Kotlin loop | 最接近本项目 JVM/Kotlin 技术栈，Apache-2.0，支持 OpenAI-compatible endpoint 和 tool calling。 |
| 跨客户端工具协议 | **MCP Kotlin SDK**，仅在需要 MCP 互通时接入 | 官方 Kotlin Multiplatform SDK；不是 Agent runtime 或安全边界。 |
| 不可信扩展工具 | **Chicory** | 纯 JVM WebAssembly runtime，Apache-2.0，避免 JNI/ABI 包袱；仅用于 WASM 工具，不是 Linux 终端。 |
| 完整 Coding Agent | **DeepSeek Harness / OpenCode / Codex CLI** 在远程或开发机运行 | 适合完整仓库、Git、shell、编译器任务；不应塞入 APK。 |
| Android 本机 JAR 处理 | 仓库现有 ZIP/ASM/启动代码 | 固定、类型化工具比通用代码 Agent 更安全，也无需引入第三方。 |

首选产品架构：

```text
Android UI
  -> LangChain4j 或自有 Kotlin AgentRunner
  -> OpenAI / DeepSeek HTTP API
  -> 本机 ToolRegistry + ApprovalGate
  -> 私有 workspace + 既有 JAR/启动工具
  -> 可选 Chicory WASM 工具
```

若产品目标是“让 Agent 任意读写仓库、运行 Git/Gradle/shell、编译生成代码”，执行环境应改为远程容器或开发机 Harness：

```text
Android UI -> 自有后端 -> DeepSeek Harness/OpenCode/Codex CLI -> 隔离容器 workspace
```

Android App 不应把 App UID 下的 `ProcessBuilder` 当作安全沙箱。

## 2. 可嵌入 Android 的候选

### 2.1 LangChain4j

| 项目 | 评估 |
| --- | --- |
| 项目 | https://github.com/langchain4j/langchain4j |
| 许可证 | Apache-2.0 |
| 语言 | Java，提供 Kotlin 使用方式 |
| Provider | `OpenAiChatModel` 可接 OpenAI-compatible API；官方文档特别说明 DeepSeek 流式 tool call ID 需设 `accumulateToolCallId(false)`。 |
| Agent 能力 | tools、自动工具调用、AI services、chat memory、流式响应。 |
| Android 适配 | 值得优先 PoC，但不是 Android 专用库。必须验证依赖树、方法数、R8、网络客户端和最低 API；不能直接信任默认 HTTP 实现适合当前 App。 |
| 缺口 | 不替代 workspace、权限、审批、artifact 事务、预算、审计或沙箱。默认自动执行工具必须被本项目的批准层包裹。 |

适用结论：**首选试验候选**。先以一个只读工具和 DeepSeek/OpenAI MockWebServer 集成验证，再决定是否引入生产依赖。若其 Android 体积、网络实现或 tool loop 不合适，应直接保留小型 Kotlin `AgentModelGateway`，不应为了框架而增加一层不可控抽象。

资料：

- https://docs.langchain4j.dev/integrations/language-models/openai-compatible
- https://docs.langchain4j.dev/tutorials/kotlin

### 2.2 Semantic Kernel Java

| 项目 | 评估 |
| --- | --- |
| 项目 | https://github.com/microsoft/semantic-kernel-java |
| 许可证 | MIT |
| 语言 | Java |
| Provider | Java SDK 支持 OpenAI 与兼容 OpenAI API endpoint。 |
| Agent 能力 | Native functions/plugins、OpenAPI plugins、automatic function calling、chat history、filters。 |
| Android 适配 | Java 实现存在，但其依赖面通常比 LangChain4j 重。必须实测 Reactor、Azure/OpenAI 依赖、desugaring、APK 增量与最小 API；当前没有项目级 Android 支持承诺。 |
| 缺口 | 同样不提供 Android 文件权限、用户批准、真实沙箱或 JAR artifact 生命周期。 |

适用结论：**备选 PoC，不建议先引入**。只有 LangChain4j 缺少所需的插件/过滤能力时才比较实际 APK 影响。

资料：

- https://learn.microsoft.com/en-us/semantic-kernel/get-started/supported-languages
- https://github.com/microsoft/semantic-kernel-java

### 2.3 MCP Kotlin SDK

| 项目 | 评估 |
| --- | --- |
| 项目 | https://github.com/modelcontextprotocol/kotlin-sdk |
| 许可证 | 新贡献 Apache-2.0，历史代码 MIT，须随包携带许可证说明。 |
| 语言 | Kotlin Multiplatform，提供 client/server、coroutine API。 |
| 能力 | Tool、Resource、Prompt、progress、stdio/HTTP/SSE/WebSocket transport。 |
| Android 适配 | Kotlin 技术栈匹配，但 SDK 文档侧重 JVM/KMP 与 Ktor transport；需验证 Android 目标、Ktor 依赖与包体。 |
| 关键限制 | MCP 只定义通信协议，**不**限制工具的文件、网络、命令或用户权限。 |

适用结论：**仅在需要与外部 Agent/桌面工具共享本地工具时采用**。Android 内部的第一版可以直接以函数 JSON schema 调用 `ToolRegistry`，避免运行本地 HTTP server 和扩大攻击面。

## 3. 本地受控执行环境

### 3.1 Chicory

| 项目 | 评估 |
| --- | --- |
| 项目 | https://github.com/dylibso/chicory |
| 许可证 | Apache-2.0 |
| 运行时 | 纯 Java/JVM WebAssembly runtime，零 JNI、零 native library；仓库含 Android 测试模块。 |
| 能力 | 运行 WebAssembly/WASI 工具，宿主显式授予文件、环境、网络等能力。 |
| 适用 | 将不可信、可移植的小工具作为 WASM 执行，例如文本转换、格式校验、JSON/JAR 元数据处理。 |
| 不适用 | 不能运行 Linux ELF、普通 JAR、Gradle、JDK compiler 或任意 shell 脚本。 |

适用结论：**若确有第三方/可下载工具执行需求，优先 PoC**。第一版固定 JAR/ZIP/ASM 工具已可由 App 自有 Kotlin 代码实现，不需要为此引入 WASM runtime。

资料：

- https://chicory.dev/docs/
- https://github.com/dylibso/chicory

### 3.2 ProcessBuilder、Termux 与 PRoot

| 工具 | 结论 |
| --- | --- |
| `ProcessBuilder` | 可复用为第一方固定命令的调度器；子进程继承 App UID，不是沙箱。只接受任务 ID 和类型化参数，不能传 shell 字符串。 |
| Termux | 完整终端 App，不是可安全嵌入的 SDK；Android 版本/分发限制和 GPLv3 义务使其不适合作为产品安全边界。 |
| PRoot / PRoot-Distro | 是兼容层，不提供 PID、网络、IPC、cgroup 或 seccomp 隔离；不应运行模型生成的命令。 |
| Android `isolatedProcess` | 可作为额外 broker 进程，不是 Linux 容器或任意命令 sandbox。 |

适用结论：Android 内没有适合普通 APK 的“把任意 Agent shell 命令安全跑在手机上”的现成方案。需要该能力时，使用远程容器；需要小型不可信工具时，使用 WASM。

## 4. 完整 Coding Harness

### 4.1 DeepSeek Harness

| 项目 | 评估 |
| --- | --- |
| 项目 | https://github.com/deepseek-ai/deepseek-harness |
| 许可证 | MIT |
| 运行时 | Node.js/pnpm，Web UI 或 CLI，plugin architecture。 |
| 能力 | 选择 workspace 后可读写文件、运行命令、委派任务、维护计划和审批。 |
| 状态 | Developer preview，官方明确可能出现 breaking changes。 |
| Android 结论 | 不可直接嵌入。可作为开发机/服务器上的完整 Agent worker，由 Android 远程控制。 |

资料：

- https://deepseek-harness.github.io/deepseek-harness/en/guide/quickstart
- https://github.com/deepseek-ai/deepseek-harness

### 4.2 OpenCode、Codex CLI、Cline、Aider、Continue

这些工具都可以完成“模型自主规划、读写工作区、运行命令”的开发者工作流，但共同依赖桌面/服务器环境、终端和完整文件系统：

| 工具 | 许可证 | Android 内嵌结论 | 可用位置 |
| --- | --- | --- | --- |
| OpenCode | MIT | 不适合，Bun/TypeScript CLI runtime | 开发机或远程 worker |
| Codex CLI | Apache-2.0 | 不适合，原生 CLI/完整 workspace runtime | 开发机或远程 worker |
| Cline / Roo Code | Apache-2.0 | 不适合，Node/VS Code 环境 | 桌面 IDE/远程 worker |
| Aider | Apache-2.0 | 不适合，Python、Git、终端环境 | 开发机/容器 |
| Continue | Apache-2.0 | 不适合，IDE/CLI runtime，且项目维护状态需在选型时重新核验 | 桌面 IDE/远程 worker |

适用结论：如果目标改为“远程完整 Coding Agent”，这些项目可降低实现成本；但它们不能安全地变成用户 Android 设备上的本地 Agent 环境。

## 5. 本仓库已有能力

| 能力 | 可复用位置 | 使用边界 |
| --- | --- | --- |
| 加密凭据模式 | `BaiduTranslationCredentialsRepository` | 可作为 provider credential store 模板；生产版本不应把明文 fallback 视为同等安全。 |
| JAR/ZIP 处理 | `JarFileIoUtils`、`StsJarValidator`、`StsDesktopJarPatcher`、ASM patchers | 对 Agent 暴露为固定工具；原用户 JAR 只读，所有变更生成 session artifact。 |
| 长任务持久化 | `WorkshopDownloadTaskStore` | 可借鉴状态、日志上限、恢复语义。 |
| 前台服务 | `WorkshopDownloadProcessService`、`SteamCloudSyncProcessService` | 用于离开页面仍需运行的 Agent；前台交互不必启动服务。 |
| 开发机调试通道 | `scripts/tools/connector/client.py`、`scripts/tools/lib/agent_client.py` | 仅开发/诊断。其 `execute`、`console_exec`、`load_agent`、`redefine_class` 等能力不得暴露给云端模型。 |

## 6. 推荐的验证计划

1. 建立无生产依赖的 `AgentModelGateway` 与 `ToolRegistry` 接口，使用 MockWebServer 覆盖 OpenAI/DeepSeek tool call 循环。
2. 单独创建 LangChain4j Android PoC：最小 OpenAI-compatible 调用、一个只读工具、R8 release build、APK 体积、最低支持设备和取消测试。
3. LangChain4j 合格则将其仅用于 provider/tool loop；workspace、审批、持久化仍由 App 自有代码负责。若不合格，保留直接 OkHttp 适配器。
4. 只有在需要加载非第一方工具时再做 Chicory Android PoC；先测 WASI 文件能力是否能严格绑定 session workspace。
5. 只有需要对接桌面 Agent 时再接 MCP Kotlin SDK；默认不在 Android 上暴露网络 MCP server。
6. 若需求包含任意 Git/Gradle/shell，另建远程 worker 方案，不将该能力加入 APK。

## 7. 外部资料

- OpenAI Function Calling: https://developers.openai.com/api/docs/guides/function-calling
- DeepSeek Tool Calls: https://api-docs.deepseek.com/guides/tool_calls
- DeepSeek Responses API: https://api-docs.deepseek.com/guides/responses_api
- Android ProcessBuilder: https://developer.android.com/reference/java/lang/ProcessBuilder
- Android isolated service: https://developer.android.com/guide/topics/manifest/service-element
- Android dynamic code loading restrictions: https://developer.android.com/about/versions/14/behavior-changes-14#dcl
- Google Play Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379
