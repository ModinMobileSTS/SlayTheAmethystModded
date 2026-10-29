# AI 对话加载增长测试

## 本机内容准备测试

```sh
./gradlew :app:aiConversationLoadingCostTest --console=plain
```

测试输出 `LOAD_COST` CSV 行（毫秒），分别改变：

- 历史消息数：10、100、1000、5000；每条回答 4096 字符。
- 单条回答长度：1024、16384、65536、262144 字符；历史固定 100 条。
- 旧版流式片段数：100、1000、10000、50000；历史固定 100 条。

调用实际的 `applyIncomingMessages` 和 `buildAiTranscriptRows`，首次进入按页面当前策略只准备最后 3 条消息；每组预热 3 次，再采样 9 次，输出中位数及最近秩 P90。每次使用全新的列表/行准备，不命中页面的行缓存。计时不含生成数据。

这不是端到端加载时间：不包含 Android SQLite、设置初始化、协程排队、Compose 排版、滚动定位和首帧绘制。JVM 普通列表安装成本也不能替代 Compose SnapshotStateList 的成本。测试不设置容易受机器负载影响的绝对耗时断言，只检查结果正确性。

## Android 实际存储读取测试

连接设备后运行（会安装 debug 应用及测试 APK）：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.stamethyst.ui.aimod.AiConversationLoadingCostInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.aiLoadingBenchmark=true
```

使用独立缓存目录，不读写用户对话；每组结束清理测试数据库。测量同样的历史数量和回答长度，输出数据库文件总字节数、首次读取、9 次重复读取的中位数/P90、摘要读取及最近 3 条消息的行准备中位数。写入和造数不计时。每次重新构造 store，但**不清除操作系统页缓存**，首次读取也不是设备冷启动。

报告保存在被测应用外部 files 目录的 `ai-conversation-loading-cost.csv`，并输出到 `AiLoadingCost` logcat。运行时测试 APK 的 XML 报告在 `app/build/outputs/androidTest-results/connected/`；本机单元测试报告在 `app/build/reports/tests/testDebugUnitTest/`。

建议保持设备温度、电量、性能模式一致，无后台游戏/下载；比较多轮测试的趋势，不用一次 P90 判断回归。当前用例只包含一条历史会话，不衡量历史会话数量增长、附件/工具结果/上下文载荷，也不代表完整页面首次可见时间。
