# AI Agent 上下文增量存储

## 存储协议（schema 8）

每个会话仍使用原来的单个 SQLite 数据库，`AiConversationStore` 和
`AgentContextState` 的调用接口、导出 JSON 结构不变。

- `context_messages(position, changed_revision, data_json)`：完整协议消息按零起始位置保存，
  每条消息包含原始工具结果、thinking、工具调用和附件（含图片 Base64）。位置而非
  `sourceMessageId` 是主键，因为同一个 UI 消息可能对应多个协议消息。
- `context_state`：单行完成标记、数据库 generation、消息数量、消息最后修改 revision，
  以及 `AgentContextState.copy(messages = emptyList())` 的元数据 JSON。摘要、压缩边界、
  recent、token 统计等完整保留；存储层不调整调用者提供的边界。
- `context_legacy`：迁移前 `context_json` 的原始字符串备份，不参与正常读取和更新。
  备份完成后才在同一事务中清空旧列。把备份移出 `conversation` 行，可以避免后续
  revision 更新继续重写旧图片/归档的 SQLite 溢出页。

## 增量检查点与事务

`updateContext` 每次仍同步提交一个完整的逻辑检查点。新增 assistant 消息或每个已完成
工具结果都独立持久化，不合并、不推迟到整批工具结束。

与上次状态比较后：

- append 只编码和写入新增的消息行；未变的图片消息不会再次编码或更新。
- 同位置内容替换只改该行；截短删除超出新长度的行；中间删除、重排按新位置更新。
- summary/token-only 更新不改任何协议消息行。
- 相同状态不写消息/元数据，不修改 `updated_at` 或 revision；`updateContext` 对已存在
  会话仍返回 `true`（成功），不存在返回 `false`。完整 `mutate` 的相同状态也直接返回。
- 行变更、元数据和会话/context revision 在同一个 WAL 写事务中提交。失败回滚，同时
  丢弃该数据库的内存缓存，下一次不会误把未提交内容识别为已保存状态。

`writeSession`（含 JSON 恢复/旧文件导入）走同一个增量引擎，并用 UPDATE 保留已有
会话行。首次创建时正确使用“尚无已存消息”的基线，写入传入 session 的全部内容。

## 迁移与恢复

旧 schema 的表/列升级继续使用原来的事务性 `ensureSchema`，版本从 7 升到 8。
新表创建成功后，在首次读取/写入该会话的上下文时惰性转换旧 `context_json`；历史列表
摘要不需反序列化整份归档。

旧 JSON 的逐条展开、元数据完成标记、原文备份、旧列清空在一个事务内完成，不提升
会话 revision。任一步失败都保留原始旧数据，重新打开会重试；schema 已升级不阻止
上下文迁移再次执行。无法解析的旧 JSON 保留在原列且不打完成标记；如果用户后续
显式写入新上下文，原字符串仍移到备份表保留。缺少 `context_state` 完成标记却存在
`context_messages` 时，读取/写入均报错并保留全部消息、附件和旧备份，不会按空库初始化
或用旧 JSON 覆盖后来新增的工具结果。消息数大于零却缺少元数据时也报错；该检查在
缓存命中前执行。损坏的新格式消息不会被静默截断为部分归档，读取会报错。真正没有
完成标记、没有消息也没有旧 JSON 的新库，仍可以初始化为 null 上下文。

`load()`、`load(id)`、`loadChanges()` 和内部导入读取均在同一数据库事务内获取上下文
和会话 revision，复用现有 WAL、锁等待重试及大字段分片读取。消息 JSON 同样使用
`queryWindowSafe`，不受单条图片/工具结果的 CursorWindow 行大小限制。

序列化/恢复通过公开的 `AiEditorSession.context` 仍得到完整 `AgentContextState`；已有
`conversations.json` 导入继续可用。实际设置界面导出入口是
`SettingsFileService.exportAgentWorkspaceBundle` → `AgentWorkspaceExportService.exportWorkspace/zipWorkspace`，
遍历整个 `agent_workspace`（包括 `.conversations` 及数据库旁文件），因此新表仍随
原数据库文件导出。该入口只读文件，不向运行中的会话覆盖数据库。

目前未发现对应的工作区/会话数据库恢复入口：AI 编辑器的
`AiModEditorScreen.restoreBackup` → `JarPatchApplier.restore` 恢复的是已注册模组 JAR；
通用存档导入通过 `SaveArchiveResolver` 只映射 preferences/saves/runs 等存档目录；
旧 `conversations.json` 导入不会覆盖已有有效会话，也不替换数据库文件。

复制数据库文件作备份仍须遵守 SQLite 一致性要求（不能忽略有未 checkpoint 内容的
WAL）；现有 ZIP 导出逐文件读取，并不是 SQLite snapshot 备份。本变更不新增或重构
该协议，也不声称已解决运行中导出的一致性问题。
不支持旧版应用直接写入 schema 8；`context_legacy` 是恢复原文的备份，不是持续同步
给旧版应用的镜像。

## 缓存与成本

每个 store 的 `AiContextStorage` 只缓存最近一个会话，避免永久持有全部历史图片。
缓存匹配数据库路径、持久化 generation 和 context revision；替换成另一数据库时
generation 使旧缓存失效。同 revision 复用整个上下文；只有元数据改变时复用消息列表；
其他进程提交新消息后，用 `changed_revision > cachedRevision` 只读取/解析变化行，
并根据消息数量处理截短。首次打开/缓存未命中仍需读取完整归档。

generation 不会识别同一数据库谱系的手动备份覆盖：如果在 store 存活期间恢复旧文件，
然后在未被读取的情况下再次推进到完全相同的 revision，缓存可能仍是覆盖前的内容。
当前没有上述应用内覆盖入口；离线恢复后重新创建 store 不会复用旧缓存。未来增加
运行期数据库恢复时，入口必须在覆盖后重建 store/失效缓存，而不能只依赖 generation。
若已观察到 revision 回退，则现有读取路径会全量重载（有对应回归测试）。

比较状态仍需要 O(消息数) 的位置扫描；正常 checkpoint 共享不可变消息对象，旧图片
不重复序列化。此变更消除的是归档编码和 SQL 消息写放大，不声称整个 checkpoint
严格 O(1)，也不声称已经测得真实设备提速比例。调用者应继续按不可变值使用这些
data class/List（与现有 revision 缓存约定一致）。

## 离线验证

新增测试：`app/src/test/java/io/stamethyst/ui/aimod/AiContextStorageTest.kt`。
测试使用事务性文件行夹具，调用生产增量引擎，记录实际行读写数量并支持注入失败。
覆盖旧格式转换及原文保留、迁移失败重试、坏旧数据恢复、逐工具结果重开、相同值
no-op、summary-only、增量缓存读取、替换/截短/重排、空与 null、提交失败缓存失效、
不同数据库 generation、会话 JSON 导出恢复、附件和不完整归档检测。
还覆盖完成标记丢失时保留迁移前/迁移后工具结果及附件、坏旧 JSON 与残留消息共存、
缺少元数据但消息数非零（含已有缓存）和正常空库初始化。

执行命令（仓库根目录）：

```sh
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :app:testDebugUnitTest \
  --tests 'io.stamethyst.ui.aimod.AiContextStorageTest' \
  --tests 'io.stamethyst.ui.aimod.AiEditorMessagePersistenceTest' \
  --tests 'io.stamethyst.ui.aimod.AiConversationContextTest' \
  --console=plain --max-workers=2
```

现有设备端参考测试为
`app/src/androidTest/java/io/stamethyst/ui/aimod/AiConversationStoreInstrumentedTest.kt`。
工程目前没有 Robolectric/JDBC 单测基础设施；纯 JVM 测试不执行 Android SQLite
适配层、CursorWindow、实际进程崩溃或跨进程 WAL。未运行设备测试、安装应用或访问
真实网络服务。最终执行结果另见本任务报告。
