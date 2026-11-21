# Agent 提问（`ask_user`）

模组编辑器和市场 AI 搜索共用聊天底部提问卡。Agent 遇到会影响后续操作的不明确需求时，可用 `ask_user` 暂停当前执行。新提问卡从底部轻微上滑并淡入；切换题目不会重播入场动画。卡片支持单选、多选、自定义回答和多题切换，聊天草稿保留。「跳过提问」结束当前执行，不提交答案，也不替用户选择。

工具移植自 [OpenCode v2 的 `question` / Form](https://github.com/anomalyco/opencode/blob/4617210822bdbb31e5749751afbfb4584ccedac8/packages/core/src/tool/plugin/question.ts) 的协议与等待生命周期，使用应用已有的 Kotlin 工具执行器和 Compose，不引入 OpenCode 服务。请求包含 `questions`，各项使用 `header`、`question`、`options`（`label`、`description`）及可选的 `multiple`。成功结果为 `{"answers":[["选项"],["选项","自定义回答"]]}`，按题序匹配，作为同一轮次的工具结果回传。`ask_user` 必须单独调用；混合调用整批拒绝，避免在用户作出选择前执行同批写入。

待答请求只归属发起它的执行，停止、超时或进程退出后不复用。等待不占用单次模型请求的网络超时，但仍受整轮执行的限时约束。编辑器后台任务等待回答时释放 CPU 和 Wi-Fi 锁，收到回答后再恢复。市场切换 AI 会话沿用停止上个会话的逻辑；只切换市场显示模式不会停止。
