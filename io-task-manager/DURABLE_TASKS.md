# 持久化 Task / TaskPlan

## 可重放块写入

`ReplayableBlockWrite` 是 Task 可组合的候选文件写入原语，不是私有任务调度器。
专用目录包含 FileTaskStore 管理的 `blocks.task`、锁及 `candidate`；建议位于 Task workspace
子目录，使用后关闭，随后由 Plan 回收目录。不能把已发布文件放入该目录。
open 指定 generation、目标长度与完整无重叠块布局（每块 offset/length/SHA-256）；首次先
持久化定义，再设置候选长度并同步，最后标记 prepared。恢复不重新截断已准备好的候选。
输入或布局改变拒绝复用旧 generation；已有无归属候选拒绝覆盖。

write 先持久化失效，再流式完整覆写固定块，校验摘要、fd.sync 后提交完成标记。
短输入、摘要错误或中断留下未完成块；重试从块起点覆写整个范围，不备份旧字节、不先清零。
isComplete 对有完成标记的块重算摘要；校验失败持久化失效。revert(index) 只撤销逻辑完成状态，
不是恢复原文件。verified 检查所有块；发布仍由外层 Task/Plan 完成。
输入流由调用者关闭，恰好消费块长度，支持较大输入流中的一段。实现有界缓冲、单 owner
串行写入，不提供并行写；调用者不得在外部修改候选。长度调整不承诺物理空间预分配。
块长度/布局总覆盖目标，空文件允许空块列表。字符串/块数量仍受文件存储元数据限制。
patch 解码器重启、输入身份和原文件保护由业务负责；本原语不实现任意压缩流随机恢复。

2026-11-14 Test-After 验证：JVM 29 项通过（块写入新增3项）；debug 主 APK 与测试 APK
构建成功，经 local_adb 非流式覆盖安装到 M2007J3SY，定向 Android 回归 `OK (11 tests)`，
4.33 秒。新增两项验证短输入/错误摘要不提交、失效标记重开保留，以及写完前64 KiB后
杀进程、重开128 KiB候选并从块起点重写，最终逐字节一致。未覆盖断电或真实 patch 解码。
证据：`agent-tmp/replayable-block-build.log`、`agent-tmp/replayable-block-jvm-final.log`、
`agent-tmp/replayable-block-device.log`。

实现入口：`io.stamethyst.iotask.durable`。这是确认设计后的独立新模型；现有
`TaskModel.kt` 是早期内存执行模型及其回归基线，启动器生产入口尚未切换。

## 责任

- Manager 持有唯一文件存储所有权，发布定义、状态、控制请求、父子关系和检查点。
- Task 执行具体业务，管理业务文件；下载 Task 的结果是已验证块集合，不自动合并。
- TaskPlan 是持久化业务编排与外部业务门面，组织顺序、并行、依赖和结果传递。
  Plan 通过 Context 发布子 Task，不能绕过 Manager 执行子 Task。
- 外部使用 Handle 查询 Snapshot、观察 StateFlow 和实时进度；不暴露可变任务实例。

## 执行与控制

Task.execute 持续执行，返回 Success、Fail、Paused、Waiting 或 Cancel（安全收束确认）。
不存在 Continue。checkpoint 合并业务元数据并落盘，不退出 execute，也不关闭下载连接。
Task 在业务安全点查看 Context.control，收束所有子协程和在途 I/O 后返回。
requestPause/requestCancel 先持久化请求；pause/cancel 等待执行和相应处理。
暂停与条件等待均释放执行名额；resume 开启新执行，不恢复内存调用栈。
协程强制取消、未处理异常与存储错误不视为安全取消，保留可恢复状态并向调用者传播。
业务失败应显式返回 Fail；FAILED 必须显式 retry，重试先 reconcile。

Success 表示业务提交完成，优先于迟到控制请求。发布业务产物的临界区由具体 Task
实现；不能在任意文件写入处假定可安全暂停。控制响应速度取决于业务分片和 I/O 超时。

## 检查点与文件存储

任务定义（类型、版本、输入、Kind）在首次执行前写入，由调用方注册的 TaskFactory 重建。
Task 提交的 metadata 使用 checkpoint 增量合并；当前存储实现每次原子替换完整记录，
不是增量 WAL。大量块索引应放在业务文件中，metadata 保存索引引用。
业务数据必须先同步提交，然后 checkpoint，最后通知观察者。

每条记录包含 schema=2、SHA-256 校验、定义、生命周期、控制请求、业务元数据、结果引用、
父节点/稳定子键，不保存回退节点。schema 1 明确拒绝，不自动迁移。记录限制 4 MiB，单字符串受 DataOutput UTF 长度限制。
临时记录写入后 fd.sync，再使用 ATOMIC_MOVE 替换；不支持原子替换的文件系统明确失败。
孤立 .pending 文件不作为已提交记录读取；坏记录/未知 schema 明确报错，不静默丢任务。
同一 root 通过 FileLock 排斥第二个活跃 owner，进程死亡后由系统释放锁。
这是单 owner、进程退出级恢复协议，不声明断电/目录 fsync 或分布式租约保证。

文件存储只管理 Manager 记录；业务文件不是自动事务化的。Task 必须自行保证历史、候选、
发布产物之间的提交协议，恢复时检查实际文件。当前为有界元数据小规模任务实现，非高频
下载遥测数据库。实时 progress 不落盘，重启从检查点重建可信进度。

## 恢复、回退与清理

遗留 RUNNING 必须调用 recover 核对业务现场；可执行、已完成、暂停、等待、失败由 Task
明确决定。默认等待，绝不默认重放。QUEUED 可首次执行；PAUSED/WAITING 保持不动，
持久化取消/暂停请求不会因重启丢失。成功终态不重新执行。

取消先进入 CLEANING，再调用幂等 cleanup，完成后保存 CANCELLED。清理异常保留
CLEANING，下一次恢复继续清理。终态记录保留，不能把取消简单等同于删除整个任务目录。
cleanup 必须只清理自己拥有的未提交文件，不能删除共享或已发布结果。

### Plan 完成后的统一回收

子 Task 完成后保留工作集供 Plan 后续步骤使用。根 Plan SUCCEEDED 或 CANCELLED 后，
Manager 自动执行后序回收：子 Task/Plan 的幂等 reclaim 钩子处理额外路径，再删除其工作
目录、pending 文件和 task 记录；根 Plan 最后删除。根终态记录在回收期间作为持久化意图，
恢复时仅续做回收，不重跑成功业务。失败、暂停和等待的 Plan 不自动回收。
reclaim 与取消 cleanup 分离；成功后的回收不能撤销发布结果。最终产物必须在成功前移交到
托管工作目录之外。删除目录不跟随符号链接。删除失败会传播错误，根业务成功状态仍保留。
回收结束不保留完成任务记录；需要历史的宿主应使用独立日志，普通回调不是可靠审计日志。
已取得的 Snapshot/返回结果可继续读取；记录回收后通过 ID 再查询会报告未知任务。
独立非 Plan Task 仍保留结果供调用者消费，本次自动回收边界仅为根 TaskPlan。

revert(id) 接受静止的 PAUSED/WAITING/FAILED/SUCCEEDED，或续做中断的 REVERTING，
且所有后代均无活动执行。
先持久化 REVERTING，再调用 Task.revert(context) 幂等撤销该对象的业务效果；中断后恢复
重新进入同一钩子，完成后 PAUSED 并清空旧 output/reason。不支持撤销默认报错。
没有 revertTo、目标节点或 checkpoint 回退功能。TaskPlan 覆写同一钩子，显式通过
Context.revert(child) 选择子任务及撤销顺序，不自动倒序所有子任务。
成功根 Plan 已自动回收，长期撤销依据须在成功前移交到工作目录外，由新任务消费。

## Plan 与子任务

child(key, definition) 原子保存子任务记录和父/键关联，同键同定义返回已有任务；不同定义
报错。Plan 即使在发布后、checkpoint 前退出，恢复也不会重复发布。
Plan 不占叶子 I/O semaphore；await 子任务不会导致并发名额为 1 时死锁。
并行编排可使用结构化协程同时 await 多个子任务；子 Task 实际执行仍受 maxParallelism 限制。
Plan 暂停/等待/失败前收束未完成子任务，取消先取消子任务再清理自身。
Plan 恢复中的业务分支、失败决策和结果聚合由 Plan 的持久化业务逻辑负责。

## 通知

状态先落盘，再发布只读视图；终态回调异常不能改变结果。普通回调仅进程内通知，
不提供跨进程恰好一次投递；必须执行的后续业务应写入 Plan 或另行设计持久化事件。
本版本不引入后台 Android 宿主服务、自动调度线程或生产下载/Delta 接入。

## 验证

### 2026-11-14 对象级 revert 与依赖纠正

删除节点式 revertTo/revertTarget，块使用 revert(index)，Task/Plan 使用无目标节点的
revert(context)。两个 Core 以 api 依赖 Manager，Manager 不再依赖 Core。
文件记录升级 schema 2，schema 1 明确拒绝，无自动迁移。

执行 `./gradlew --offline :io-task-manager:test :delta-core:build :download-core:build
:app:compileDebugAndroidTestKotlin --console=plain` 成功。JVM 共 34 项通过（原29项，新增
ObjectRevertTest 5项），覆盖成功 Task 撤销/清空 output、Plan 选择性撤销及子与父两级
中断恢复、活动/排队任务拒绝撤销、不支持撤销报错、旧 schema 拒绝；块测试增加重复
revert 后字节不变、其他块保留及重开持久性断言。
Android 测试代码编译通过，本轮未安装或执行真机测试，不宣称 schema 2 已真机验证。
证据：`agent-tmp/object-revert-verification-final.log` 与模块 test-results/test XML。
未对旧实现执行新增用例的失败对照；本轮属于 API 契约变更，旧 API 与新用例不兼容。

- JVM：`:io-task-manager:test`，新模型用例位于 DurableTaskTest。
- Android：DurableRecoveryInstrumentedTest 使用真实 FileTaskStore，而非 AtomicFile 模拟存储。
  独立 debug Service 进程在 checkpoint、业务提交、清理中途被 kill；测试重新打开存储并
  通过工厂重建任务，检查恢复、避免重放、清理与终态留存。另验证暂停后重开及取消清理。
- 测试仅操作 `files/durable-recovery/<UUID>`，不访问用户业务文件。
- 通过编译不等于通过真机；实际执行结果另行报告。

### 2026-11-13 验证记录

串行执行 `./gradlew --offline :io-task-manager:test :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`
成功。新增 DurableTaskTest 7 项通过。设备 M2007J3SY 经仓库 local_adb 非流式覆盖安装
debug 主 APK 与测试 APK；定向执行 DurableRecoveryInstrumentedTest 与原
TaskProcessRecoveryInstrumentedTest，结果 `OK (8 tests)`，耗时 4.619 秒。
新存储四项真机用例均通过，证明本次测试窗口下 Android 文件落盘、进程死亡后工厂重建、
已提交结果避免重放及中断清理恢复可用；不覆盖设备断电、系统重启、真实下载或 Delta 业务。

同日完成 Plan 统一回收后的回归：JVM 共 26 项通过（原模型18、持久化7、回收1），
Android 新旧恢复测试 `OK (9 tests)`，3.513 秒。新增用例在子任务 reclaim 提交元数据后
杀死独立进程，确认根 Plan 保留 SUCCEEDED、子任务工作文件仍在；重新打开存储后仅续做
回收，并断言所有任务记录和工作目录被删除。JVM 同时验证发布文件保留、业务不重复执行。
本地证据：`agent-tmp/plan-reclaim-build-final.log`、`agent-tmp/plan-reclaim-device.log`。
