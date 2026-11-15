# AI Agent 会话压缩

## 上游基线

默认摘要压缩和时间线移植自 OpenCode V2 的 `v2` 分支：
`4617210822bdbb31e5749751afbfb4584ccedac8`。

- `packages/core/src/session/compaction.ts`
- `packages/core/src/session/model-request.ts`
- `packages/session-ui/src/message/message-content.tsx`
- `packages/session-ui/src/components/timeline-separator.tsx`
- `packages/gui-extensions/src/context/indicator.tsx`

许可证及版权声明见根目录 `NOTICE`。对照源码保存在本地 `agent-tmp/`，不提交。

## 行为

- 该版本上游的 `calculateCeiling` 从总窗口中预留 10%，窗口至少 32,000 时至少预留
  16,000。启动器另有输入上限设置：当前配置值表示输入额度，不再次扣除输出预留；
  输出额度独立分配。这是启动器的适配差异，不是完全一致的 V2 窗口配置语义。
- 使用启动器现有系统提示和工具定义构造摘要请求，在最后追加结构化摘要提示；摘要
  不执行工具。单独关闭摘要模型的可配置思考功能。
- 按用户轮次分割，保留约 15,000 tokens 的近期对话。工具输出最多保留 1,250 个
  Unicode 码点。近期部分保存为文本，不作为原始工具协议重放。
  新记录的文本附件在近期部分只保留名称与类型；原始附件内容仍留在归档中。旧版本已经
  内嵌到用户正文的附件保留原样，避免通过猜测分隔符损坏用户内容。
- 摘要使用 Objective、Requirements、Decisions、Work State、Next Move、Relevant Files、
  Important Context 等标题。没有匹配标题时追加一次模板提醒；截断、过滤和工具调用
  直接失败，不把不完整摘要激活。
- 预计请求过长时转成文本，去除思考内容、缩短工具输出，再按完整用户轮次移除最旧
  对话。旧检查点和最新待摘要轮次不得删除。服务端溢出依次以首次拒绝请求估算量的
  70%、50%、35% 缩小。主 Agent 溢出后仍只压缩重试一次，不重放已执行工具。
  HTTP 413 / payload-too-large 先转成精简文本重试，不消耗这三次溢出裁减；原始归档不变。
- 摘要与主任务共用输出上限，默认 128,000 tokens；修改输出预算会同时修改摘要限制，没有独立摘要上限。模型目录提供的能力约束仍自动生效。
- 成功后只更新检查点和逻辑边界，原始消息不删除。后续请求发送
  `<conversation-checkpoint><summary>…</summary><recent-context>…</recent-context>`
  加检查点之后新增的消息。后续压缩会合并旧摘要和其近期对话。
- 已测量的主模型输入量作为估算锚点，再加之后新增消息的估算量；更换模型、端点、
  回滚或创建检查点时使旧锚点失效。

## 界面

上下文入口使用 V2 风格的 16dp 中性圆环，保留 Android 48dp 点击区域。上下文详情中
可手动压缩；与普通请求使用相同的后台任务、停止、持久化及恢复机制。
也可在输入框发送 `/compact`，它不会作为普通用户目标发送给模型。

聊天时间线使用居中文字和两侧细分隔线：开始压缩、流式 Markdown 摘要、运行状态、
完成/失败/中断及本次压缩输入输出用量。压缩状态以独立 message part 保存在既有 JSON
列中，不要求数据库 schema 迁移。进程退出后遗留 running 状态显示为中断。

## 启动器适配边界

- 本次不移植实验性后台预摘要、插件 hooks、原生加密检查点、模型目录或服务端 RPC。
- 保留不能单独有损压缩一个全新用户请求的安全限制；仅缩小附件不能解决固定提示和
  工具定义占满窗口的问题，此时明确失败。
- Android 旧会话继续使用旧检查点直至下一次成功压缩；新字段都有默认值，回滚会同时
  失效受影响的摘要和 recent 文本。
- 使用 Compose 和启动器主题颜色复现 V2 时间线结构，而不是把 Web CSS 引入 Android。
