# AI 编辑器请求缓存：编排、统计与验收

## OpenCode 实现调查

调查基于 OpenCode V2 源码提交 `ba06ff9e0bf2dcefbbbc3a1d6094f3572d77c84e`，不是根据旧版配置推断：

- [context.ts](https://github.com/anomalyco/opencode/blob/ba06ff9e0bf2dcefbbbc3a1d6094f3572d77c84e/packages/core/src/session/context.ts#L144-L158)：指令按共享范围从大到小排列，通用指导先于项目、日期和环境，形成跨会话可复用前缀。
- [runner/llm.ts](https://github.com/anomalyco/opencode/blob/ba06ff9e0bf2dcefbbbc3a1d6094f3572d77c84e/packages/core/src/session/runner/llm.ts#L235-L245)：最后一轮仍保留工具定义，避免改变缓存前缀。
- [model-request.ts](https://github.com/anomalyco/opencode/blob/ba06ff9e0bf2dcefbbbc3a1d6094f3572d77c84e/packages/core/src/session/model-request.ts#L278-L301)：稳定会话亲和标识通过请求头和内部 promptCacheKey 传递。
- [openai-chat.ts](https://github.com/anomalyco/opencode/blob/ba06ff9e0bf2dcefbbbc3a1d6094f3572d77c84e/packages/ai/src/protocols/openai-chat.ts#L787-L800)：只有服务兼容性明确声明支持时，才发送 `prompt_cache_key`。不是所有兼容接口都能接受它。
- 同文件的 `usage` 转换兼容 OpenAI 的 `prompt_tokens_details.cached_tokens`、DeepSeek 的 `prompt_cache_hit_tokens` 以及其它兼容字段，并将总输入与缓存子集区分。
- [缓存标记](https://github.com/anomalyco/opencode/blob/ba06ff9e0bf2dcefbbbc3a1d6094f3572d77c84e/packages/ai/src/protocols/utils/cache.ts)：显式缓存控制按服务能力下发，并有断点数量限制。
- [官方保温说明](https://opencode.ai/v2/docs/warming)：保温请求默认关闭，开启会产生真实费用。本编辑器不添加后台保温。

这些机制提高缓存复用条件，不能控制上游缓存容量、过期或路由。

## 编辑器的请求布局

```text
固定 system：通用安全/工具规则 → 末尾的 Mod 与工作区信息
压缩记忆 user 消息（仅发生成功压缩时替换）
历史 user / assistant / tool 消息，保留原始调用 ID、参数、thinking 和工具结果
追加的 Launcher 状态通知（活动补丁改变时追加，绝不回写 system）
后续对话继续追加
```

工具 schema 在注册时按名称排序并固定，普通/流式/最后一轮使用同一列表。会话恢复不重写状态通知，工具调用与结果保持相邻；状态通知不会成为“最近的用户目标”。压缩时保留最后真实用户请求，并在需要时补充活动补丁状态。

请求携带由会话 ID 确定性派生的、不包含路径或用户文本的 `x-session-affinity` / `X-Session-Id`。这是中转站可选择使用的亲和提示，不是命中承诺。DeepSeek 使用自动前缀缓存；不盲目发送 Anthropic `cache_control` 或未确认支持的 `prompt_cache_key`。

修改编排后的第一次请求、Mod/系统提示/工具版本变更、回滚、上下文修复和压缩都可能重新建立部分缓存。压缩后的固定 system 前缀仍保留，后续请求继续追加。

## 上下文用量窗口

- 最近缓存率 = 本次缓存读取 token / 本次输入总 token；输入总量已经包含缓存，不可再次相加。
- 累计缓存率 = 已报告响应的缓存读取 token 总和 / 对应输入 token 总和，不是逐次百分比平均。
- 显示最近缓存/输入 token 数及统计覆盖的响应数。
- `--` 表示服务端未报告或分母为零；显式报告 0 才是 0% 命中。无统计的响应不进入累计分母，亦不沿用上次读数。
- 统计范围是当前会话、当前模型配置的已完成主助手响应，包括空响应重试/回退；不含独立压缩摘要请求，也不估算失败网络请求的服务端计费。
- 统计随会话持久化，模型地址/名称或接口类型变化时清零。回滚历史不会撤销已经发生的 API 用量。
- 缓存不减少上下文占用，窗口仍以完整输入估算剩余空间。

### 非标准网关的输入口径

本机接口实测出现 `prompt_tokens=187, input_tokens=0, completion_tokens=68, total_tokens=255, prompt_tokens_details.cached_tokens=32768`。这不符合 OpenAI 的“prompt_tokens 包含缓存”约定；它更像兼容桥将未缓存输入和缓存读取分开计数。直接相除会超过 100%，丢弃则漏统计。

仅当 **缓存读取大于 prompt_tokens、input_tokens 显式为 0、total_tokens 等于 prompt_tokens + completion_tokens** 同时成立时，自动识别该非标准口径。此后在当前模型统计中，以 `报告输入 + 缓存读取 + 缓存创建` 归一化总输入，并修正之前已统计响应的累计分母。归一化总输入也用于上下文估算校准，避免把热请求误认为只占几百 token。

窗口明确提示该口径由网关响应推断，不声称是标准协议保证。正常 OpenAI/DeepSeek 不触发该转换；没有完整特征的非法缓存数仍视为未知。通过同一地址切换上游且上游口径不一致时，累计值可能不准确，应使用新会话重新统计。

## 确定性回归

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.llm.AgentCache*Test' \
  --tests 'io.stamethyst.backend.llm.AgentConversationContextTest' \
  --tests 'io.stamethyst.backend.llm.PolicyGatedAgentGatewayTest' \
  --tests 'io.stamethyst.backend.llm.AgentContextCompactionTest' \
  --tests 'io.stamethyst.backend.llm.OpenCodeLiveTestConfigTest' \
  --tests 'io.stamethyst.ui.aimod.*Test' --console=plain
```

覆盖缓存字段归一、缺失/零值/无效值、加权汇总、旧会话迁移、持久化、状态追加与恢复的前缀不变性、压缩后的固定前缀、工具排序，以及真实 LangChain4j 编解码器的非流式/SSE 用量提取。普通测试不联网。

`AgentCacheWorkflowTest` 额外使用 `AiModAgentExecutor` 的真实入口捕获 system 和完整工具 schema，不复制一份可能过期的工具列表。它验证：

- 三次较长、非重复的合成源码读取结果超过固定 system/tools 的估算体量，但每个结果不超过网关的 51,200 字节上限。
- 两次工具循环及序列化恢复之后，真实 HTTP 编码中的既有消息逐条不变，工具 schema、调用 ID、原生 thinking 和结果顺序不变；恢复不会重放工具。
- 压缩确实替换历史前缀，但保留 system、工具定义和归档；checkpoint 恢复及后续追加保持稳定。
- 没有服务端 usage 时缓存率仍为未知，不能将离线结构测试当成真实缓存率测量。

`AgentCacheAccountingRegressionTest` 用已记录的 Terra 数值回放统计，验证最近值 93.80% 与已报告样本累计值 70.95% 可以同时成立；另外验证标准 OpenAI 的 16,000 缓存 / 20,000 输入就是 80%，不把缓存或输出 tokens 再加入分母，以及最近接近 100% 时累计仍可能只有约 75%。这不能替代用户实际请求的计数核对。2026-11-17 的统计专项回归（`AgentCache*Test` + `AiEditorCachePresentationTest`）31 项全部通过，未联网。

### Responses SDK 默认零值回归与修复（2026-11-17）

用户实际截图显示最近为 **0 / 106,891（0%）**，累计为 **78.7%**，压缩次数 0；用户确认使用 **Chat Completions**。截图中的上次输入 106,891 加输出 806 等于估算上下文 107,697，会话输入 4,180,157 加输出 24,195 等于已报告总量 4,204,352。界面没有显示网关归一化标记。这些数值本身没有显示分母重复计数，但不能仅凭截图判断缓存 0 的上游来源。

为排除 SDK 把未知转成 0，新增 `AgentCacheStreamingAccountingTest`，通过真实 LangChain4j 编解码器和本机 MockWebServer 检查：

- Chat Completions SSE 的缓存字段缺失、空详情、null 保持未知，显式 0 才计为已报告的 0%；未报告的新响应不稀释先前已知累计值。
- **确实复现了另一个接口的 bug**：Responses SDK 在原始 usage 没有缓存字段时仍生成 `cachedTokens=0`，旧 `AgentCacheUsage.from` 取该 SDK 值回填，导致未知被计成 0%，错误扩大累计分母。原实现回归失败：`expected null, but was:<0>`，记录为 `agent-tmp/kv-cache-screenshot-accounting-tests.log`。
- 修复为：有捕获的原始响应/SSE payload 时，缓存统计只认原始缓存字段；仅完全没有原始 payload 的 SDK-only 响应允许使用 SDK 缓存计数。原始 usage 存在时也不从 SDK 补出缓存字段。显式零、正数、无原始 payload 的合法 SDK-only 数值均保持支持。
- 补充 Responses 缓存字段缺失、空详情、null、整个 usage 缺失/null/空对象、显式零及连续累计的回归。专项 `AgentCache*Test`、`AiEditorCachePresentationTest`、`AiExecutionHistoryTest` 共 **58 项通过**，日志为 `agent-tmp/kv-cache-responses-accounting-fix-tests.log`。没有付费请求或设备操作。
- 最终更广回归：`backend.llm.*Test` 与 `ui.aimod.*Test` 共 **52 类、472 项全部通过**，无跳过；日志为 `agent-tmp/kv-cache-accounting-fix-broad-tests.log`。

这个 Responses bug **不能直接归因于用户已确认的 Chat Completions 截图**。当前 SSE 缺失字段回归没有复现假零，仍需该请求原始 usage 的纯数值字段判断是否真的未命中、网关填零或兼容字段冲突。顶部用量覆盖按所有模型/摘要请求步骤统计，缓存覆盖按已完成主响应统计，46/49 与 46/46 不必相等。

修复只保证新响应不再发生上述误计；历史缓存记录没有保留逐请求原始字段，无法安全区分历史真零与 SDK 默认零，不能把旧 0% 全部改成未知或自动清除用户统计。

## 手动真实接口验收

```bash
./gradlew :app:aiCacheAcceptanceTest --console=plain
```

只在显式调用时开启；每次都重测，不复用 Gradle 成功结果。默认模型为 `deepseek-v4-flash`；可通过 `STS_AI_CACHE_MODEL` 明确选择配置中声明的其他模型，绝不悄悄退回默认模型。使用 app 的模型工厂和上下文管理器发送虚构参考材料与工具 schema，不读取或发送用户对话/Mod 文件。

包含三个独立用例：

1. 冷请求 → 创建补丁状态 → 三次状态变化和序列化恢复 → 真实模型生成工具调用及结果恢复。要求热续聊至少一次缓存读取比例达到 90%，工具恢复续聊达到 90%。有限次数重试允许服务端异步建立缓存，但不会无限重试掩盖故障。初次冷请求也进入累计值，因此累计值不要求达到 90%。真实工具是无副作用的虚构只读工具，测试不伪造历史 thinking/调用 ID。
2. 编辑器真实 system 和完整工具集的非流式预热 → 单次流式续聊。不再向 system 填充 400 行参考材料。要求收到真正的文本 delta、缓存统计、90% 以上缓存率，并记录首个文本 delta 的耗时；不允许用非流式回退假装流式通过。若服务端流式协议异常，此用例应失败并单独报告。
3. `completeEditorToolHistoryRestoreAndCompactionReportMeasuredCacheReuse`：真实 system/完整工具集 → 两轮模型生成的真实文件读取调用及较长合成结果 → 会话恢复 → 压缩 → checkpoint 续聊。不会执行任何真实编辑器工具；仅接受模型的一次 `read_agent_workspace_file`，由测试提供合成结果。正常为 8 次主请求 + 1 次压缩请求，总尝试数上限 10，每次输出上限 2,048 tokens。逐请求输出输入、缓存读取/创建、比例、相同前缀消息数、耗时和非标准输入口径标志；另外汇总包含压缩的全部已完成响应。

第三个用例要求热请求缓存读取量**超过整个初始输入 token 数**，证明工具/对话历史也在复用，而不是仅凭固定 system/tools 的缓存达到漂亮比例。主请求使用 LOW，压缩请求像编辑器一样使用 OFF。每次追加大量新工具结果时缓存率自然下降，因此不要求每一轮都超过 90%。初始请求可能已经命中公共 system/tools，不强行称为“全冷”；压缩后第一次请求和再次续聊分开报告。

部分网关会在冷请求/未命中时省略缓存字段：此时保留未知，不填 0，恢复续聊、第二批工具结果、压缩后续聊三个热检查点仍必须报告真实缓存读取。除已报告样本的加权比例外，报告也按未知读取的可能范围给出整个工作负载的比例下界/上界，避免未报告的冷启动成本被悄悄隐藏；`AgentCacheProbeBoundsTest` 验证其加权和未知值语义。这里只测配置接口报告的缓存计数，不证明其后端实际 GPU KV 的驻留位置或计费准确性。

仅运行完整工作流和流式两个用例，例如：

```bash
STS_AI_CACHE_MODEL=gpt-6.1-terra \
./gradlew :app:aiCacheAcceptanceTest :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.llm.AgentCacheNetworkAcceptanceTest.completeEditorToolHistoryRestoreAndCompactionReportMeasuredCacheReuse' \
  --tests 'io.stamethyst.backend.llm.AgentCacheNetworkAcceptanceTest.streamingEndpointReportsWarmCacheUsage' \
  --console=plain
```

同模型有多个 provider 且默认模型所属 provider 无法消歧时，再设置 `STS_AI_CACHE_PROVIDER`。这些测试需要明确授权，因为会消耗真实 API 额度。

### 密钥安全

仅测试进程在运行时读取 `$XDG_CONFIG_HOME/opencode/opencode.json`（默认 `~/.config`）。API key 优先读取配置，再读取 V2 的 `$XDG_DATA_HOME/opencode/opencode.db` 中该 provider 的活动 API-key 凭据，再回退旧 `auth.json` 或 provider 的环境变量。默认 data home 为 `~/.local/share`。根据 [V2 凭据存储说明](https://opencode.ai/v2/docs/cli/providers#storage)，新版不会将更新后的凭据写回 auth.json。

SQLite 通过 Python 3 标准库以 `mode=ro` 打开，不修改数据库、不导出副本；key 经子进程管道直接进入测试进程内存，不进入命令行、文件或日志。测试需要本机 `python3`。兼容 `providers/settings` 和本机旧配置 `provider/options`，支持 `{env:...}` 和相对配置文件的 `{file:...}`；此测试读取器要求 JSON，不是完整 OpenCode 配置合并器。

选择顺序：环境变量指定 provider → 全局默认模型所属的候选 provider → 唯一配置 `deepseek-v4-flash` 的 provider。多个候选无法确定时显式失败。

- `STS_AI_CACHE_OPENCODE_CONFIG`：另一个本机 JSON 配置文件。
- `STS_AI_CACHE_PROVIDER`：选择 provider ID。
- `STS_AI_CACHE_MODEL`：测试模型 ID，默认 `deepseek-v4-flash`；所选 provider 必须声明该模型。
- `STS_AI_CACHE_OPENCODE_DB`：只读凭据数据库路径（其次使用 `OPENCODE_DB`，最后使用默认路径；可用 `opencode debug paths db` 查询）。

密钥不写入项目、Gradle 属性、命令行或报告。日志只显示 token 数和比例；真实 HTTP 异常替换为不包含 endpoint、凭据或请求体的错误。不要启用 HTTP 请求/响应调试日志。验收会产生实际 API 消耗。

报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`。

## 设备 UI 验收

`AiContextCacheInstrumentedTest` 使用隔离 Compose 对话框验证最近/累计比例、实时变为“未报告”、切换模型不显示旧缓存值。不会读取密钥、启动模型任务或改动用户对话。

```bash
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
```

此命令仅本地编译测试。会自动安装/卸载应用的 connected/托管设备测试已禁用；设备验收必须明确指定并授权目标设备，使用已安装的测试构建。安装/卸载另需授权，不得隐式执行（见 `AGENTS.md`）。

## 本机验收记录（2026-10-19）

- `backend.llm.*Test` 和 `ui.aimod.*Test` **88 项全部通过**，普通运行没有包含联网验收类；`compileDebugAndroidTestKotlin` 成功。覆盖真实 LangChain4j 普通/流式/Responses 编解码、缺失统计、重试/回退、工具调用相邻性、前缀稳定性、压缩/回滚与只读凭据读取。
- 真实接口来自本机 OpenCode 配置及其活动凭据，模型 `deepseek-v4-flash`；没有将 endpoint/key 写入项目。
- 在接口正常的非流式阶段，32k 左右输入的创建补丁/恢复续聊，经网关非标准口径归一化后，四次热请求分别为 **99.44%、99.26%、99.47%、99.36%**。这是合成测试结果，不代表所有用户任务都能达到同一比例。冷请求明显较低，累计值会包含冷启动成本。
- 流式最小探针（不经过编辑器）返回 HTTP 200 + SSE 注释心跳，随后裸 JSON 错误 `bad_response_status_code`，没有正常数据事件。最终重跑时，非流式预热及编排用例亦遭遇上游 **HTTP 504**，故完整真实验收 **未通过**；真实工具恢复、最终流式缓存率没有验收结论。保留失败，不改成跳过或降低阈值。
- 设备显示 ADB 在线，但 shell/UI hierarchy 请求无响应；没有重置设备或覆盖其运行中的 app。设备对话框测试已编写并单独编译，尚未在设备执行。
- 本地临时源码、探针和执行日志均在被忽略的 `agent-tmp/`；真实请求日志仅包含数值计数、比例和脱敏错误类型。

接口恢复后重跑 `:app:aiCacheAcceptanceTest`，设备恢复后执行上述 scoped instrumented test。这两项不能用本地模拟测试替代。

## 本机验收记录（2026-11-17，GPT-6.1 Terra）

用户授权调用 `gpt-6.1-terra`，使用本机已配置网关的 Chat Completions 接口。仅运行新增完整工作流和改进后的流式用例，没有重跑原来的 400 行固定材料用例。未修改业务缓存策略，也没有发送用户对话或 Mod 文件。

完整工作流最终通过，使用编辑器实际生成的 **28 个工具定义**，没有向 system 注入长参考材料：

| 阶段 | 输入 tokens | 缓存读取 tokens | 缓存率 |
| --- | ---: | ---: | ---: |
| 初始请求 | 6,878 | 未报告 | 未知 |
| 第一次文件工具调用 | 6,937 | 未报告 | 未知 |
| 加入第一批长工具结果 | 26,508 | 6,784 | 25.59% |
| 序列化恢复后的续聊 | 26,531 | 26,368 | 99.39% |
| 第二次文件工具调用 | 26,590 | 26,368 | 99.17% |
| 加入第二批长工具结果 | 46,195 | 26,496 | 57.36% |
| 独立压缩摘要请求 | 46,678 | 未报告 | 未知 |
| 压缩后的首次主请求 | 7,073 | 6,656 | 94.10% |
| checkpoint 再次续聊 | 7,096 | 6,656 | 93.80% |

- 主请求 8 次，6 次报告缓存读取；已报告样本的 token 加权比例为 **70.95%**。将两次未报告读取的请求纳入不确定范围后，整个主工作负载的比例在 **64.58%–73.56%**，不能把 70.95% 当作覆盖所有请求的精确值。
- 包含独立压缩共 9 次已完成调用，总输入 **200,486 tokens**，已确认缓存读取 **99,328 tokens**。因为摘要请求也未报告缓存读取，全部调用的比例只能界定为 **49.54%–79.72%**。缓存创建字段全部未报告，累计日志中的已知创建数为 0 不代表服务端实际没有创建。
- 恢复续聊的读取超过整个初始输入 **19,490 tokens**，第二批工具结果阶段超过 **19,618 tokens**，证明真实工具/对话历史也在复用，而不是只有 system/tools。
- 第一、第二批长结果分别新增约 19.5k 输入 tokens；当轮的 25.59% / 57.36% 不能直接解释为旧前缀失效。恢复后小增量续聊达 99.39%。
- 压缩将主请求从约 46k 降至约 7k；虽然随后比例仍约 94%，但读取量 6,656 不超过初始输入，**本轮只证明固定前缀仍可复用，没有证明新 checkpoint 内容已进入缓存**。未追加付费保温或额外探测来美化结果。
- 独立流式用例通过：输入 **6,943**、缓存读取 **6,784**、比例 **97.71%**，收到真实文本 delta，首个文本约 **2,534 ms**；没有非流式回退。
- 最终离线回归：`./gradlew :app:testDebugUnitTest --tests 'io.stamethyst.backend.llm.*Test' --tests 'io.stamethyst.ui.aimod.*Test' --console=plain`，**50 个测试类、461 项全部通过**，没有跳过，也没有运行任何联网验收类。日志为 `agent-tmp/kv-cache-final-offline-tests.log`。本次未进行设备/UI 验收。
- 两次前置探测曾因测试假设失败：首次错误要求冷请求一定报告缓存字段；第二次错误要求压缩后消息总列表不追加合法的活动补丁状态。现已分别保留未知语义、验证原归档逐条保留并只允许追加对应状态通知；后者补入离线回归。没有修改业务实现或降低热检查点的报告要求。

上述是所配置网关报告的合成测试结果，不是所有真实任务的命中保证，也不证明上游 GPU KV 驻留或账单口径。脱敏执行日志保存在被忽略的 `agent-tmp/kv-cache-terra-live-initial-attempt.log`、`agent-tmp/kv-cache-terra-workflow-live-tests.log`、`agent-tmp/kv-cache-terra-workflow-live-final.log`；最后一次完整工作流的 XML 另存为 `agent-tmp/kv-cache-terra-workflow-live-final.xml`。
