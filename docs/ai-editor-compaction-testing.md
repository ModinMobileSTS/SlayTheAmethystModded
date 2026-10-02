# AI 编辑器压缩验收（仅手动触发）

```bash
./gradlew :app:aiCompactionAcceptanceTest --console=plain
```

这个任务调用 app 实际使用的 `AgentChatModelFactory` 和 `AgentContextManager`，不需要 Android 设备。测试不修改 app 的设置，也不读取用户对话，只发送测试生成的虚构 Mod 编辑历史。

## 超时问题

压缩请求是非流式的。`OpenAiChatModel` 在未显式指定模型层 `timeout` 时，会将底层 HTTP builder 的读取超时覆盖为 60 秒，即使用户设置为 300 秒。模型工厂现在为普通及流式 Chat Completions 都显式设置经过范围限制的超时，连接阶段仍限制为 30 秒。Responses 接口直接使用 HTTP builder 的设置，不受这一覆盖问题影响。

DeepSeek Chat Completions 的 OFF 模式还需要显式设置 `thinking.type=disabled`；只省略 `reasoning_effort` 会沿用服务端默认思考模式。该专用参数仅发给 DeepSeek 模型；其他模型和 HIGH 等思考档位不变。

部分兼容网关即使收到关闭思考参数，仍要求历史工具调用带上原始思考内容。模型工厂现在启用 Chat Completions 思考内容的收发；会话恢复时也保留已存的 `thinking`，而非丢弃它。验收使用真实模型生成的工具调用作为最近历史，不伪造思考内容。

网络/服务端仍可能超时；这不代表原对话被删除。压缩只有完整成功并通过验证后才提交摘要，失败时保留原始历史。

## 验证内容

- 本地模拟摘要接口先等待 65 秒再返回：配置 120 秒超时时应成功，避免非流式模型被 LangChain4j 默认的 60 秒读取超时覆盖。
- 检查 DeepSeek 的 OFF 模式显式发送 `thinking.type=disabled`，而非仅省略 `reasoning_effort`（DeepSeek 默认开启思考）。
- 真实接口对多段长历史生成并合并摘要，验证上下文确实缩小、原始消息完整保留、最近的用户请求及工具调用/结果没有被截断。
- 检查摘要保留准确的补丁 ID 和源文件路径。
- 序列化并恢复压缩后的会话（包括真实工具调用的思考内容），再向真实接口发送请求，检查工具消息协议被接受，而且不会把“未编译、未启用”误报为成功。测试中的工具结果为虚构只读结果，不会访问本机 Mod 文件。

`AgentConversationContextTest` 已有不联网的确定性单元测试，覆盖分块、摘要失败/取消时原子保留历史、工具边界修复、上下文溢出后的恢复等。这些不替代真实接口验收。

## 配置与安全

默认在运行时读取本机：

- `$XDG_CONFIG_HOME/opencode/opencode.json`（未设置时使用 `~/.config`），provider 为 `server`，model 为 `deepseek-v4-flash`。
- `$XDG_DATA_HOME/opencode/auth.json`（未设置时使用 `~/.local/share`）中相应 provider 的 API key；也支持配置中的 `apiKey`。

不会把 key 复制进代码、Gradle 参数或 app；测试日志只输出耗时、token 数、结束原因等。真实请求会消耗 API 额度。配置文件需为 JSON；JSONC 用户可用下面的环境变量覆盖接口和 key。

可选环境变量：

| 变量 | 用途 |
| --- | --- |
| `STS_AI_COMPACTION_PROVIDER` | OpenCode provider ID，默认 `server` |
| `STS_AI_COMPACTION_MODEL` | 模型 ID，默认 `deepseek-v4-flash` |
| `STS_AI_COMPACTION_OPENCODE_CONFIG` | 指定本机 OpenCode JSON 配置文件 |
| `STS_AI_COMPACTION_BASE_URL` | 覆盖 base URL |
| `STS_AI_COMPACTION_API_KEY` | 覆盖 API key（不要把值写进仓库或命令行历史） |
| `STS_AI_COMPACTION_TIMEOUT_SECONDS` | 真实摘要请求超时，默认 300 秒，按 app 的 60–1200 秒范围限制 |

## 不自动触发

普通 `testDebugUnitTest`、`test`、`check`、构建及 CI 均排除这个测试类。只有显式请求 `:app:aiCompactionAcceptanceTest` 才启用它；类本身另有手动开关检查。每次手动调用都会重新测试，不复用旧的成功结果。

结果位于 `app/build/reports/tests/testDebugUnitTest/index.html`，XML 位于 `app/build/test-results/testDebugUnitTest/`。65 秒模拟用例需要至少一分钟，真实接口部分耗时取决于服务端。

## 本机验收记录（2026-10-02）

- 3 个手动验收用例全部通过；65 秒慢响应实际等待约 65.3 秒后成功。
- 本机 OpenCode 的 `server/deepseek-v4-flash`：3 段摘要成功合并，估算上下文从 18,488 降到 1,048 tokens；序列化恢复后的真实续聊成功，并保持未编译、未启用状态。
- `AgentConversationContextTest`、`PolicyGatedAgentGatewayTest`、`AiConversationContextTest` 共 58 个回归用例全部通过，普通测试运行未包含联网验收类。
- 这是 app 后端代码的 JVM + 真实 API 验收，未进行 Android 设备 UI 验收。
