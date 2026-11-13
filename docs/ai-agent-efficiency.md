# AI Agent 效率修复整合

本次按审查 1–10 接入局部编辑、请求策略、预算、缓存与持久化；收益是减少可避免的工作，未测量真实 provider 的提速百分比或费用。

## 请求和恢复

- 同一执行、同一完整模型配置中，最多两次流式空响应后若非流式成功，后续轮次直接非流式。模型/端点/推理配置变化重新探测流式；新输入本身不清除降级记忆。任务恢复创建新执行，也重新探测。
- 空响应与传输失败分别处理。空 `LENGTH`/`CONTENT_FILTER` 给出具体处理建议，不盲目重发或 fallback；已有可见截断文字保留，并在执行历史记录结束原因和 usage。正常流式不触发降级，thinking 与工具 ID/同批顺序保持。
- 单次总时间仍使用用户 `requestTimeoutSeconds`。流式首个有效事件最多 120 秒、空闲最多 60 秒，并受总时间限制；text/thinking/partial tool arguments 都算进展，keepalive 不算。同步生成没有可观察进展，保留完整总时间。
- 每个模型/工具轮次累计预算等于用户单次超时；重试、empty、fallback、backoff、网络等待共用余额。摘要独立累计预算也等于该超时，涵盖模板纠正、缩减与重试。`ROUND_BUDGET` 不重试。
- Service 的停止和销毁直接取消执行专属 control，覆盖阻塞同步 HTTP、首 token 前 SSE、正在生成的 SSE 和摘要。SDK 线程绑定 scope，异步 chat 返回不关闭 scope；终止/取消 finally 才释放。

## 无进展与累计执行限制

`AgentExecutionPolicy` 可由调用者通过 `AgentRequestControl(executionPolicy = ...)` 配置，不增加一套设置 UI：

- 默认累计时长 **2 小时**；默认 token 上限 **null**；两者都允许 null 关闭。请求超时和用户模型预算是独立设置，保持其既有值。
- token 使用主请求和摘要的 provider usage，包含完成的空响应；不提供 usage 的服务无法精确执行 token ceiling。
- 累计上限在请求准入和完整工具边界检查；已经开始的请求/工具批次可先完成，因此不是抢占任意第三方工具的硬 deadline。模型请求仍受独立单次/整轮 deadline 约束。
- 默认连续 **3 个相同且没有成功变更的完整工具批次**停止。签名忽略调用 ID 和 JSON 对象 key 顺序，包含规范化参数、完整结果及附件内容；不动态增删工具或更改 schema 顺序。
- 成功状态变更、新参数/结果、新用户输入或模型切换重置停滞检测。新输入在完整工具边界被接收后再判定停止。成功的写入/编译/打包等工具按状态变更处理；这不是对任意工具副作用的文件系统审计，也不检测任意交替周期。
- 达到限制时先保留已完成结果的逐条检查点，再报告可行动原因。恢复任务使用保存的协议历史继续，已执行工具不会因策略停止而重放。持续获得新证据的长任务没有固定小轮数上限。
- 未捕获工具异常返回有界、脱敏的实际原因（沿用 480 字符错误详情上限）及纠错 hint，而非仅 `tool_execution_failed`。

## 模型预算和其它优化

- 主模型和 OFF 同步/流式摘要模型随配置一起切换，完整预算通过推送目标传递；目录元数据在 UI 使用 id 前缓存。未知模型 32k 输入 / 4k 主输出，保留用户显式输入预算和 HIGH 等已选档位。
- OFF 对已知 DeepSeek Chat Completions 保持 `thinking: {type: disabled}`；未知服务不猜新字段。保留设置 UI 的能力说明。
- 局部编辑、准备流程与强制打包预检：[ai-agent-local-editing.md](ai-agent-local-editing.md)。
- 母模组源码/classpath 缓存：[ai-agent-source-caching.md](ai-agent-source-caching.md)。
- 增量上下文、逐结果耐久性：[ai-agent-context-storage.md](ai-agent-context-storage.md)。
- 模型预算公式、兼容性：[ai-agent-model-budgets.md](ai-agent-model-budgets.md)。
- 取消传输和 deadline 细节：[ai-agent-request-lifecycle.md](ai-agent-request-lifecycle.md)。
- 只读并发仍为 4，变更独占屏障、稳定 schema 顺序及会话 affinity 保留。

## 验证

离线命令（真实模型网络验收类由 Gradle 默认 exclude）：

```sh
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.llm.*Test' --tests 'io.stamethyst.ui.aimod.*Test' \
  :app:compileDebugAndroidTestKotlin --init-script agent-tmp/ai-agent-integration-report.gradle \
  --console=plain --max-workers=2
```

完整集成基线：**365/365 通过，失败 0、跳过 0**；`compileDebugAndroidTestKotlin` 实际执行通过，
`BUILD SUCCESSFUL`，耗时 1 分 21 秒。锁内 afterSuite 计数写入
`agent-tmp/ai-agent-integration-results.txt`，成功日志为 `agent-tmp/ai-agent-integration-final.log`。

前轮失败已保留：`ai-agent-integration-gradle-2.log` 的 364 项中 9 项失败；精确复跑缩为 66 项中
3 项失败。修复了 SDK partial-tool fixture 必填 name、超时新语义断言、mock Android cipher
偏好设置 fixture，以及主 agent 修正的相对 symlink 目标 fixture（保留安全边界断言）。

两个 transport 用例首轮曾波动，随后精确复跑和 365 项全套通过；首轮完整栈被共享 JUnit 目录覆盖，
**不据此声称已确认其唯一根因**。之后 `AgentRequestTransportTest` 增加 runner 线程 `@After`
资源清理（包括超时后迟到注册），silent TLS 改为专用 executor，并用 readiness future 传播后台
异常及 accepted / secure-connect / ClientHello 阶段。原 5 秒测试上限和 2 秒 EOF/Call 终止断言
均保持；没有修改生产 deadline 或固定单 JVM。该测试卫生修改仅需精确复跑 transport 类，日志为
`agent-tmp/ai-agent-transport-hygiene.log`，锁内计数仍写入上述结果文件。修后精确类结果为
**15/15 通过，失败 0、跳过 0**，`BUILD SUCCESSFUL`，耗时 10 秒：

```sh
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.llm.AgentRequestTransportTest' \
  --init-script agent-tmp/ai-agent-integration-report.gradle --console=plain --max-workers=2
```

新增测试验证多轮流式降级/模型切换、timeout 与网络等待、main/summary localhost 取消、完整失败批次停止和历史、长任务进展。未回滚前序工作来证明旧版本失败；回归用请求数及持久化协议断言覆盖原失效路径。没有设备、安装、依赖安装或真实模型服务验证。
