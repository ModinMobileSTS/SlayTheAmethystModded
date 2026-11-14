# 固化的基准结果

这些 CSV 从已完成批次汇总整理，保存实际观测的关键数据，不包含第三方文件内容。
时间单位ms，补丁单位B，重复数见列。数据为当时 APK/配置观测，不在工具改动后自动更新。

- `stream-cache.csv`：修正8 MiB LRU后的raw文件流式，3次，54/54通过。
- `stream-large.csv`：两遍raw DEFLATE6文件流式，2次，20/20通过。

原始日志/逐次JSONL仍在本地忽略的 `agent-tmp/delta-stream-cache/`、
`agent-tmp/delta-stream-large/`。CSV是汇总快照，**不能替代完整逐次观测或提供原始日志
永久可用保证**。历史各批结果/失败见 EXPERIMENT_HISTORY.md 和 STREAMING.md。

计时包含原生/文件I/O和fd.sync，验证在计时外；压缩批还含codec和临时文件处理。
2次的“median”只是中点。所有目标字节验证通过，基础保持不变。采样RSS非内存硬上限。
真实样本是缓存coroutines JAR的1.8.0→1.8.1、1.8.1→1.9.0，不代表游戏模组普遍分布。

允许不同算法在不同场景获胜。最终采用HDiffPatch是综合决策，见DECISION.md。
