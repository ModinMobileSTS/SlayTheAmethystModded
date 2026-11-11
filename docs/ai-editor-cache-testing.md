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
  --tests 'io.stamethyst.backend.llm.OpenCodeLiveTestConfigTest' \
  --tests 'io.stamethyst.ui.aimod.*Test' --console=plain
```

覆盖缓存字段归一、缺失/零值/无效值、加权汇总、旧会话迁移、持久化、状态追加与恢复的前缀不变性、压缩后的固定前缀、工具排序，以及真实 LangChain4j 编解码器的非流式/SSE 用量提取。普通测试不联网。

## 手动真实接口验收

```bash
./gradlew :app:aiCacheAcceptanceTest --console=plain
```

只在显式调用时开启；每次都重测，不复用 Gradle 成功结果。固定测试模型为 `deepseek-v4-flash`。使用 app 的模型工厂和上下文管理器发送虚构参考材料与工具 schema，不读取或发送用户对话/Mod 文件。

包含两个独立用例：

1. 冷请求 → 创建补丁状态 → 三次状态变化和序列化恢复 → 真实模型生成工具调用及结果恢复。要求热续聊至少一次缓存读取比例达到 90%，工具恢复续聊达到 90%。有限次数重试允许服务端异步建立缓存，但不会无限重试掩盖故障。初次冷请求也进入累计值，因此累计值不要求达到 90%。真实工具是无副作用的虚构只读工具，测试不伪造历史 thinking/调用 ID。
2. 非流式预热 → 单次流式请求。要求直接收到可见文本、缓存统计、90% 以上缓存率，不允许用非流式回退假装流式通过。若服务端流式协议异常，此用例应失败并单独报告，不影响识别非流式编排的结果。

### 密钥安全

仅测试进程在运行时读取 `$XDG_CONFIG_HOME/opencode/opencode.json`（默认 `~/.config`）。API key 优先读取配置，再读取 V2 的 `$XDG_DATA_HOME/opencode/opencode.db` 中该 provider 的活动 API-key 凭据，再回退旧 `auth.json` 或 provider 的环境变量。默认 data home 为 `~/.local/share`。根据 [V2 凭据存储说明](https://opencode.ai/v2/docs/cli/providers#storage)，新版不会将更新后的凭据写回 auth.json。

SQLite 通过 Python 3 标准库以 `mode=ro` 打开，不修改数据库、不导出副本；key 经子进程管道直接进入测试进程内存，不进入命令行、文件或日志。测试需要本机 `python3`。兼容 `providers/settings` 和本机旧配置 `provider/options`，支持 `{env:...}` 和相对配置文件的 `{file:...}`；此测试读取器要求 JSON，不是完整 OpenCode 配置合并器。

选择顺序：环境变量指定 provider → 全局默认模型所属的候选 provider → 唯一配置 `deepseek-v4-flash` 的 provider。多个候选无法确定时显式失败。

- `STS_AI_CACHE_OPENCODE_CONFIG`：另一个本机 JSON 配置文件。
- `STS_AI_CACHE_PROVIDER`：选择 provider ID。
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
