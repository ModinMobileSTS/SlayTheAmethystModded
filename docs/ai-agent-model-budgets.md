# AI Agent 模型预算与压缩模型接线

## 预算语义

`AgentModelBudgetPolicy.resolve(...)` 是纯 Kotlin 策略入口。它区分：

- `userInputLimit` / `AgentContextBudget.limit`：用户输入预算，包含系统提示、工具定义、历史。现有 `ai_context_limits` 中保存的正整数继续按输入解释，不迁移为共享窗口。
- `AgentModelLimits.contextWindow`：可选的输入 + 输出共享窗口。
- `maxInputTokens` / `maxOutputTokens`：可选的模型独立能力上限。
- `outputTarget`：主任务输出目标，默认 **4,000**，不再由输入额度推导。
- `summaryOutputTarget`：摘要独立输出目标，默认 **2,000**，应用上限 **8,000**，继续受模型最大输出和共享窗口约束。
- `AgentCompactionMargin`：提前压缩余量，与输出额度分开计算。

未知模型默认采用 **32,000 输入 / 4,000 主任务输出**的保守应用预算。这不是模型能力声明；未知的能力始终为 `null`，较小模型仍需元数据或用户覆盖。`usesDefaultInputBudget` 标识策略是否采用缺省输入预算。显式输入 200,000 会被保留，不会静默改成 32,000。

能力优先级逐字段为：**模型级用户覆盖 > 模型目录元数据 > 未知**。未显式配置输入时使用 32,000，即使模型元数据支持更大输入也不会自动扩大应用预算。

主任务分配规则：

```text
outputReserve = min(outputTarget, model.maxOutputTokens?, model.contextWindow? - 1)
inputLimit = min(userInputLimit or 32,000, model.maxInputTokens?, model.contextWindow? - outputReserve)
```

因此已知共享窗口时，先保留受限输出目标，再约束输入，保证两者之和不超窗口。没有共享窗口时，绝不再次从用户输入额度里减输出。摘要用同样规则单独计算 `summaryOutputLimit` / `summaryInputLimit`，不是沿用主任务输出预留。

## 压缩边界

- 默认预留有效输入预算的 **10%**（最多 **8,192 tokens**），例如 32k 预算在 90% 触发。最近一批工具结果较大时增加余量，最多 **20%**，仍受 8,192-token 上限约束。
- `AgentCompactionMargin(fraction, maxFraction, maxTokens)` 可配置；全部比例设为零可禁用提前余量。小窗口按比例缩小，不收取固定几千 token 的余量。
- 用户单独输入尚未达到硬限制时，不因落入余量区就尝试无意义压缩。
- 已有 V2 检查点在余量区时，需要至少半个当前余量的新内容增长才再次自动压缩；硬输入上限始终优先。重复 `prepare()` 不会重复压缩检查点。
- 摘要模型请求使用它自己的输入预算，包括工具 schema、模板与一次模板纠正提醒。无法容纳最新完整交换或既有摘要时明确失败，保留原始归档，不再发送明知超额的请求。
- 原有整交换缩减、原始归档保留、原子成功提交、LENGTH / CONTENT_FILTER / OTHER 拒绝、工具调用拒绝、一次模板纠正保持有效；只有标题、无正文的摘要也被拒绝。模板允许省略不适用章节，未强制添加所有章节。

## 模型目录与本地设置

`NewApiModel.limits` 兼容以下**可选顶层字段**：

- `context_window`，或 `context_length`；前者优先。
- `max_input_tokens`。
- `max_output_tokens`。

接受正整数字面量及整数字符串；缺失、null、非数字、负值、非整数、越界整数或未知结构均不产生能力值，也不导致整个目录失败。不根据 provider/model 名称猜测能力，不把一般 `max_tokens` 误解释为输出能力。

`AiContextLimits`：

- `get(context, key)`：保留旧接口，仅返回用户输入预算/默认值，**不是完整预算解析器**。
- `budget(context, key)`：解析完整 `AgentContextBudget`，executor 启动和运行中设置更新已使用此接口。
- `explicitInputLimit(context, key)`：未设置时返回 null。
- `overrides(context, key)` / `set(context, key, inputLimit?, overrides, outputTarget?)`：模型级能力覆盖与独立主任务输出目标。`explicitOutputTarget` 读取已保存的可选目标。清空输入/输出目标字段删除旧键，不把默认值重新保存为显式覆盖。
- `cacheCatalog(context, baseUrl, models)`：成功获取模型目录后、提取显示用 id 前调用，保存可选元数据；某个返回模型不再携带元数据时会清除该模型之前缓存的能力。

独立 preference 文件 `ai_model_budget_overrides`、`ai_model_budget_catalog`、`ai_model_output_targets` 使用现有 `AiContextLimits.key(baseUrl, model)` 隔离端点/模型。目录缓存端点按 repository 的规则 trim / trimEnd('/')，兼容设置页尚未保存的 URL 草稿。`AiContextCounter` 的现有设置对话框已加入主任务输出目标，以及共享窗口、最大输入、最大输出三个可选能力覆盖字段。所有新文案放在三种语言的 `ai_agent_budget.xml`，未改公共 strings.xml。

## 集成状态：executor 与目录入口（已完成）

集成阶段已完成 executor、gateway、设置目录和上下文提示接线；采用**推送式摘要目标**，没有混用供应器。

### 1. 启动与运行中模型切换

新类型把摘要双传输和预算绑定为同一个快照：

```kotlin
data class AgentCompactionTarget(
    val model: ChatModel,
    val streamingModel: StreamingChatModel? = null,
    val budget: AgentContextBudget,
)
```

供应器接口仍可供独立调用方使用：

```kotlin
var activeBudget = AiContextLimits.budget(context, modelKey)
var activeSummaryTarget = AgentCompactionTarget(
    AgentChatModelFactory.create(activeConfig.copy(reasoningEffort = LlmReasoningEffort.OFF)),
    AgentChatModelFactory.createStreaming(activeConfig.copy(reasoningEffort = LlmReasoningEffort.OFF)),
    activeBudget,
)
// AgentContextManager 构造时：
// budget = activeBudget,
// model = activeSummaryTarget.model,
// streamingModel = activeSummaryTarget.streamingModel,
// currentCompactionTarget = { activeSummaryTarget }
```

若采用供应器，每次更换模型/端点/相关配置后：重新创建摘要的非流式和流式模型、解析该模型预算，**先更新整个 `activeSummaryTarget`，再调用 `manager.switchModel(key, nextBudget)`**。供应器每次压缩只读取一次，因此一次压缩内的模型和预算不会漂移。不要在供应器里每次新建 HTTP 模型。

应用 executor 实际使用以下推送式接口：

```kotlin
manager.switchModel(key, nextBudget, AgentCompactionTarget(newSummaryModel, newSummaryStream, nextBudget))
// 独立摘要模型配置变更：
manager.updateCompactionTarget(summaryTarget)
```

启动时解析完整预算，同时创建 OFF 的同步/流式摘要模型。`applyInputSettings` 在主模型配置变化时
重建主模型，并把新 key 的完整 budget + OFF 双传输摘要模型一次推送给 manager。同 key 的预算更新
也重新建立摘要目标。模型对象保留到下一次配置更新，不在每次压缩时创建。主请求输出仍用受限的
`budget.outputReserve`；已有显式输入值和已选 HIGH 均保留。

供应器优先于推送目标；不要混用两种方式。同一 modelKey 的预算/摘要目标更新也会生效；切换 key 时仍清空旧模型 token 标尺和缓存计数。已有 `(key, contextLimit: Int)` 重载仅用于源码兼容，**不会自动重新构建模型**；executor 已改用完整预算及目标重载。若单独选摘要模型，目标预算必须解析摘要模型自己的 key；主任务预算继续属于主任务模型。

### 2. 主任务请求输出

gateway 现有 `.maxOutputTokens(contextManager.budget.outputReserve)` 可继续使用，无需再扣减输入。如果 integration 改造请求构建，仍须使用 `outputReserve`，不能使用未经过模型约束的 `outputTarget`。

### 3. 模型目录/UI 外部位置

- [x] `ui/settings/llm/LlmSettingsScreen.kt`：目录返回后在 IO 上调用 `cacheCatalog`，再发布目录供 UI 提取 id。
- [x] `ui/aimod/AiContextUsagePanel.kt`：已改用 `ai_agent_budget_input_hint`，说明 32k 保守预算与模型能力的区别。
- [x] `ui/aimod/AiModEditorScreen.kt`：启动占位 LOW，异步加载 repository 后仍以保存的 OFF/LOW/HIGH/MAX 为准。

## OFF 与默认推理

`LlmSettings` 新默认 LOW。`LlmReasoningEffort.fromStored` 精确保留 OFF/LOW/HIGH/MAX；遗留 MEDIUM 仍沿用原有迁移到 HIGH，缺失/未知值用 LOW。选择器包括 OFF，并沿用现有滑动/选项设计。

gateway 保持以下已接线规则：

- OFF 是用户意图，不是对未知 provider 已关闭推理的证明。
- 不把枚举名称直接转成 `reasoning_effort: "off"`；未知 provider 不发送猜测的 `thinking` / `enable_thinking` / `reasoning` 字段或档位。
- 保留已有 DeepSeek Chat Completions（含流式）关闭规则：model id 最后一段以 `deepseek-` 开头时，OFF 使用 `thinking: {"type":"disabled"}`，而不是仅省略 `reasoning_effort`。
- 未知兼容服务的 OFF 暂可保守省略可选字段，但 UI 已明确说明这不保证禁用；Responses 或其它 provider 的显式禁用仅在已知支持时映射。
- 已有 LOW/HIGH/MAX provider 映射由 gateway 所有者维护，不因新增 UI 选择而推断所有 provider 支持所有字段。

## 验证

本阶段使用精确 JVM 测试类：`AgentConversationContextTest`、`AgentContextCompactionTest`、`AgentModelBudgetTest`、`NewApiModelServiceTest`、`LlmReasoningEffortTest`、`AiModelBudgetSettingsTest`、`AiConversationContextTest`。模型目录 HTTP 仅现有 MockWebServer 回环测试；无真实 provider、设备或安装操作。

```sh
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.llm.AgentConversationContextTest' \
  --tests 'io.stamethyst.backend.llm.AgentContextCompactionTest' \
  --tests 'io.stamethyst.backend.llm.AgentModelBudgetTest' \
  --tests 'io.stamethyst.backend.llm.NewApiModelServiceTest' \
  --tests 'io.stamethyst.backend.llm.LlmReasoningEffortTest' \
  --tests 'io.stamethyst.ui.aimod.AiModelBudgetSettingsTest' \
  --tests 'io.stamethyst.ui.aimod.AiConversationContextTest' \
  --init-script agent-tmp/ai-budget-test-report.gradle --console=plain --max-workers=2
```

测试输出保存在 `agent-tmp/ai-agent-model-budgets-gradle.log`；锁内的 Gradle afterSuite listener 将实际测试数写入 `agent-tmp/ai-agent-model-budgets-test-results.txt`，避免被并行任务覆盖 JUnit 输出目录。摘要模型切换覆盖双向切换、同步/流式路径、动态供应器与同 key 更新；阈值覆盖正常余量、工具批次余量、重复 prepare 和小增量恢复。

静态检查：本阶段受控文件 `git diff --check` 通过；三种语言资源 XML 解析通过，9 个 key 及格式化占位符一致。

旧长历史测试 fixture 现在显式配置独立 32k 摘要输入预算，继续验证原始归档/成功提交/失败保留等行为；不再依赖旧实现发送超出摘要预算的请求。新的小摘要窗口回归验证请求过大时不发起调用、不丢失最新交换。输入额度“不额外扣输出”的既有断言保持。

上述预算阶段测试不证明真实 tokenizer、真实 provider 参数支持或设备 UI 显示。集成测试结果另见
`ai-agent-efficiency.md`。未在旧工作区上回滚执行新回归，避免覆盖并行修改。

另有并行 agent 在 `AiContextCounter.kt` 添加 `edit_agent_workspace_file` 工具说明映射；该 hunk 不属于本阶段预算修改，已保留。
