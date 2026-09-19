# 云端 LLM + 本地 Agent 环境技术调研

更新日期：2026-09-17

## 1. 目标与更正

目标不是在 Android 设备上运行本地模型，也不是让模型只给出 JAR 编辑建议。目标是：

1. 用户选择 OpenAI、DeepSeek 或兼容供应商的云端模型。
2. Android App 在本机维护一个可恢复的 Agent 会话。
3. LLM 自主规划，并通过 function/tool calling 请求读取环境、修改工作区、构建、校验和试运行。
4. App 在本机执行工具，回传结构化结果；模型持续决策，直至完成、失败、取消或等待用户批准。

因此，**LLM 是远端推理和规划器，Agent Harness 与执行环境在本机**。这才是“给 LLM 提供环境”的含义。模型不能直接拥有 Android 文件系统、shell 或游戏启动权限；它只能看到 App 显式发布的工具。

## 2. 结论

推荐实现项目自有的 Kotlin `AgentRunner`、`ToolRegistry`、`WorkspaceManager` 和 `ApprovalGate`，使用 HTTP 调用供应商 API。不要把 LiteRT-LM、本地模型下载或本地推理作为该功能的前置条件。

首选协议分层：

| 层 | 推荐 | 原因 |
| --- | --- | --- |
| OpenAI | Responses API function calling | 原生多轮工具调用；函数参数可用 strict JSON schema；可用 `parallel_tool_calls=false` 保持有副作用操作有序。 |
| DeepSeek | Chat Completions function calling 作为兼容基线；Responses API 作为可选适配器 | DeepSeek 明确支持 OpenAI 兼容 Chat Completions 与 tools。其 Responses API 支持 function，但无状态且忽略 `parallel_tool_calls`，因此必须由本机管理完整历史和写操作串行化。 |
| App 抽象 | `AgentModelGateway` | 屏蔽 Responses 的 `function_call`/`function_call_output` 与 Chat 的 `tool_calls`/`tool` 消息差异。 |
| Agent 执行 | Kotlin/协程本地 Harness | Android App 已有 OkHttp、kotlinx serialization、加密凭据、ZIP/JAR、ASM、前台服务和进程启动模式；无需引入桌面 Agent 框架。 |

不推荐把 OpenAI Agents SDK、OpenAI Agents API 的托管 Codex harness、DeepSeek Harness 或 MCP server 直接嵌入 Android：它们解决的是云端、Node/Python 或桌面工作区问题，不能为本 App 提供 Android 权限、文件作用域、JAR 事务、用户批准和游戏生命周期边界。

## 3. 供应商能力核验

### 3.1 OpenAI

OpenAI 的 Function Calling 文档明确规定循环：请求携带 JSON Schema 工具定义，响应中的 `function_call` 返回 `call_id`、工具名和 JSON 参数；客户端执行工具，再提交 `function_call_output`，直到得到最终文本或新的工具调用。

对本项目的含义：

- OpenAI 不会执行本机工具。每一次工具调用仍由 Android 执行器验证和执行。
- `strict: true` 适合减少参数解析失败；每个 object 都必须 `additionalProperties: false`，字段须在 `required` 中声明。
- 一次响应可能含多个 tool call。读操作可并行，工作区写入、构建和启动必须由本机排队。
- reasoning 模型的响应项目必须原样保存在下一次请求上下文中，不能只保留自然语言文本或工具结果。
- 工具 schema 计入输入 token，应按任务阶段只暴露少量工具，不要一次提供整个系统的能力。

官方把 Responses API 定义为“应用自行构建 Agent”的路径；这与本项目要求的本地环境最匹配。

### 3.2 DeepSeek

DeepSeek 文档确认 API 兼容 OpenAI 格式，`https://api.deepseek.com/chat/completions` 支持 function tools。模型返回 `tool_calls` 后，客户端须追加 assistant 消息与 `role: tool`、对应 `tool_call_id` 的结果，再发起下一轮。

DeepSeek 也提供 `/responses`：

- 支持 `function` 工具、`function_call` 和 `function_call_output` 输入项。
- 是无状态接口，不支持 `previous_response_id`、`conversation` 或 `store`。
- `parallel_tool_calls` 被忽略，调用可并行产生。
- 支持的内建工具有限；不能依赖其托管 web search、code interpreter、MCP 或 computer use 来访问手机本地环境。

因此 DeepSeek 不能使用“只保存上一轮 response ID”的实现。`AgentSessionStore` 必须持久化经裁剪后的完整输入历史，下一次请求重放该历史。其 strict mode 需要 beta 基础 URL，属于供应商可选优化，不能取代本地 JSON schema 校验。

### 3.3 统一接口

不要把业务工具绑定到某家 API 的 JSON。定义应用内协议：

```kotlin
interface AgentModelGateway {
    suspend fun nextTurn(request: AgentTurnRequest): AgentTurnResult
}

data class AgentTurnResult(
    val assistantItems: List<AgentTranscriptItem>,
    val toolCalls: List<RequestedToolCall>,
    val finalText: String?,
    val usage: AgentUsage?,
)
```

供应商适配器负责把该协议转换为：

- `OpenAiResponsesGateway`
- `OpenAiCompatibleChatGateway`，用于 OpenAI Chat Completions、DeepSeek Chat Completions 和其他兼容服务
- `DeepSeekResponsesGateway`，仅在需要 Responses 兼容或图像工具结果时启用

`RequestedToolCall` 必须保留本地唯一 ID、供应商 `call_id`、工具名、原始参数 JSON、来源轮次和 provider response ID。所有适配器都必须把工具参数再次交给本地 schema parser；“供应商 strict mode”不是信任边界。

## 4. 本地 Agent Harness

### 4.1 状态机

`AgentRunner` 不是普通聊天 ViewModel，而是持久化状态机：

```text
Created
  -> RequestingModel
  -> ValidatingCalls
  -> AwaitingApproval
  -> RunningTools
  -> RequestingModel
  -> Completed | Failed | Cancelled | BudgetExceeded
```

每个状态变更写入 App 私有目录。进程被杀、网络中断或 Activity 重建后，可展示最后工具结果、等待的批准和恢复按钮。应限制：

- 最大模型轮次、总 token、预计费用、单次响应大小和总执行时长。
- 每工具最大调用次数、重复相同参数阈值、递归/循环检测。
- 单文件/工作区大小、单次读取字节数、搜索结果数和日志尾部大小。
- 取消信号向 OkHttp、协程和正在运行的子进程传播。

建议会话目录：

```text
files/agent/
  sessions/<session-id>/session.json
  sessions/<session-id>/transcript.jsonl
  sessions/<session-id>/approvals.json
  sessions/<session-id>/events.jsonl
  workspaces/<session-id>/
  artifacts/<session-id>/
```

不得在 transcript 中写 API Key、完整存档、无关私有文件或无限制日志。云端请求前应根据工具结果的敏感等级和大小限制进行裁剪。

### 4.2 环境不是通用 shell

Android App 的 `ProcessBuilder` 以 App UID 运行，不是容器或安全沙箱。把任意命令、任意路径和网络访问暴露给云端模型，会让 prompt injection、模型误操作和供应商响应错误等同于本机数据破坏。

因此，首版环境应是**能力型工作区**，而不是 POSIX shell：

| 工具域 | 首版工具 | 权限与限制 |
| --- | --- | --- |
| 工作区 | `list_workspace`, `read_file`, `search_text`, `read_jar_entries` | 仅当前 session 私有工作区及用户明确选择的只读输入；分页和字节上限。 |
| 编辑 | `apply_unified_patch`, `write_generated_file`, `copy_input_to_workspace` | 只能写 session 工作区；显示 diff；禁止直接写 Mod 库、存档和 App 配置。 |
| JAR | `inspect_jar`, `replace_zip_entry`, `build_jar_artifact`, `verify_jar_artifact` | 使用既有 ZIP/ASM 能力；原 JAR 永远只读；输出只在 artifact 目录。 |
| 游戏 | `prepare_launch_check`, `launch_trial` | 前者只读；后者必须单独确认，默认不持久启用 artifact。 |
| 受控程序 | `run_named_task` | 只允许开发者登记的任务 ID、固定可执行文件和 JSON 参数；超时、工作目录、输出大小、环境变量均固定。 |

`run_named_task` 可以复用现有 `ProcessBuilder`、Java runtime 和前台服务模式，但绝不能接受模型传来的 shell 字符串、二进制路径或环境变量。若未来确实需要“任意代码 Agent”，需要单独建设隔离进程/远端 sandbox 和安全审计；这不是当前 Android App 内一个 `exec()` 调用能解决的能力。

### 4.3 策略与批准

`ToolRegistry` 只负责解析和执行已注册工具；`PolicyGate` 在执行前独立判定：

- 当前会话是否有该能力、目标文件是否在允许根目录内、输入指纹是否仍匹配。
- 参数是否符合本地 schema、大小限制、黑名单和事务状态。
- 是否属于只读、可逆写入、不可逆写入、网络、启动游戏四种风险等级。
- 是否需要用户本轮确认或可使用已确认授权。

至少以下操作每次都需显式批准：写入工作区外部、覆盖/删除文件、导出/分享、网络上传新内容、执行受控程序、启动游戏、持久启用 artifact。模型的自然语言说明和自报风险不得作为授权依据。

## 5. 当前仓库的可复用基础

| 需求 | 已有基础 | 接入建议 |
| --- | --- | --- |
| HTTP 和 JSON | OkHttp 5、kotlinx serialization | 直接实现 provider HTTP adapter，不引入不确定 Android 兼容性的 Agent SDK。 |
| API Key | `BaiduTranslationCredentialsRepository` 的 `EncryptedSharedPreferences` + `MasterKey` 模式 | 新建通用 `AgentProviderCredentialStore`；加密不可用时应明确告警，而不是静默当作同等安全。 |
| JAR 读取/重打包 | `JarFileIoUtils`、`ModClasspathJarBuilder`、`ModAtlasFilterCompatPatcher` | 复制输入至 workspace 后处理，产物输出到 artifact；不得原地调用现有 `*InPlace` 方法处理用户原 JAR。 |
| 字节码检查 | ASM、现有各 Mod patcher | 工具实现使用固定、版本化的操作，不动态执行模型生成的 Java/ASM。 |
| 启动前验证 | `LaunchPreparationService.prepare(...)`、`ModManager`、MTS 缓存 | 作为 `prepare_launch_check` / `launch_trial` 的后端，先保证 artifact 映射和回滚设计。 |
| 长任务 | `SteamCloudSyncProcessService`、`WorkshopDownloadProcessService` 前台服务模式 | 用户离开页面仍需持续的 Agent 任务才使用前台服务及常驻通知；前台交互会话使用 ViewModel + coroutine 即可。 |

## 6. 不采用的方案

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| LiteRT-LM / llama.cpp 本地模型 | 不属于本需求首期 | 用户要求云端供应商；本地推理增加模型下载、内存、NPU/GPU 和质量变量，不能提供 Agent 环境。 |
| OpenAI 托管 Agents API / Codex harness | 不作为本地环境 | 执行环境不在用户手机和本 App 权限模型内；无法直接访问受控 Android workspace。 |
| OpenAI Agents SDK | 不作为 Android 核心依赖 | 该 SDK 是应用内 orchestration 选择，但现有 Android Kotlin 项目直接用 HTTP 可获得更小依赖面和完全的生命周期/批准控制。 |
| DeepSeek Harness | 不嵌入 | 当前为预览，文档目标是本地 Web UI/CLI 工作区，可读写文件、运行命令和委派任务；不是 Android 嵌入式 Kotlin runtime。可作为开发机验证工具，不是终端用户功能。 |
| MCP 作为本机工具总线 | 后置 | MCP 可在未来把受控工具暴露给外部开发工具，但移动端内部工具调用不需要本地 server、transport 和额外攻击面。 |
| 任意 shell / 任意 Java patch | 禁止 | 没有可信沙箱；会超出 App UID 文件与进程权限边界。 |

## 7. 建议实施顺序

1. 新建纯 Kotlin `agent` 包：会话模型、历史裁剪、预算、状态机、`ToolRegistry`、`PolicyGate`，先用 fake gateway 写单元测试。
2. 实现 `OpenAiCompatibleChatGateway`，先覆盖 DeepSeek 与 OpenAI Chat Completions 的 function calling；使用 MockWebServer 测试多 tool call、无效 JSON、取消、429 和工具错误回传。
3. 实现 `OpenAiResponsesGateway`，完整保留 response output items；DeepSeek Responses 适配器以本地完整 history 方式实现，不使用 `previous_response_id`。
4. 建立 session 私有 workspace 与只读工具；UI 显示计划、当前调用、返回摘要、预算和取消。
5. 实现 `apply_unified_patch`、JAR inspect/build/verify 等可回滚工具；所有写入均在 workspace/artifact 内完成。
6. 接入审批 UI 和 `launch_trial`。先只允许显式试运行，成功后再设计 artifact 持久启用与回滚。
7. 仅在以上闭环通过后评估 `run_named_task`；不要先提供任意命令执行。

## 8. 待产品决策

技术调研后仍需确认以下边界，才能实现：

1. 第一版 Agent 是否只服务于 Mod/JAR 工作区，还是还要编辑启动器配置、资源包或其他用户文件？
2. API Key 是用户自带并直连供应商，还是由项目服务端代理并统一计费/限流？
3. “完成任务”是否包含无人工确认的 workspace 内文件修改，还是任何写操作均要求确认？
4. 是否需要真正的通用代码/命令执行环境？若需要，应另立 sandbox 方案，不能用 App UID 的 `ProcessBuilder` 冒充隔离。

## 9. 参考

- OpenAI Function Calling: https://developers.openai.com/api/docs/guides/function-calling
- OpenAI Agents runtimes: https://developers.openai.com/api/docs/guides/agents-sdk
- DeepSeek Tool Calls: https://api-docs.deepseek.com/guides/tool_calls
- DeepSeek Responses API: https://api-docs.deepseek.com/guides/responses_api
- DeepSeek multi-round conversation: https://api-docs.deepseek.com/guides/multi_round_chat
- DeepSeek Harness guide: https://deepseek-harness.github.io/deepseek-harness/en/guide/quickstart
