# IO Task Manager

## 当前交付状态

独立 Kotlin/JVM 模块，当前实现位于 `io.stamethyst.iotask.durable`：

- Task / TaskPlan、定义与工厂重建、只读 Handle / Snapshot / StateFlow。
- FileTaskStore 文件持久化、单 owner 文件锁、检查点与进程退出后恢复。
- 安全点暂停/取消、等待、显式重试、幂等清理和对象级 revert。
- Plan 稳定子键发布、子任务复用、叶子并发上限及根 Plan 终态统一回收。
- ReplayableBlockWrite 候选块校验、块 revert（失效）与整块重放。

**尚未实现真实下载、Delta 内容管理和发布事务、Android 后台运行宿主或生产入口接入。**
app 仅通过 debugImplementation / androidTestImplementation 引入本模块用于测试，release
业务没有迁移。详细契约见 [DURABLE_TASKS.md](DURABLE_TASKS.md)。

`TaskModel.kt` 与 TaskModelTest 是早期内存模型及回归基线，不是新功能使用的 API。

## 架构与依赖

编译依赖：

```text
delta-core    → io-task-manager
download-core → io-task-manager
```

Manager 不依赖具体 Core。Core 依赖 Manager 并实现具体 Task / TaskPlan；组合入口通过
TaskFactory 注册和重建执行实例，宿主只通过 Manager 发布、控制及观察任务，不自行执行
Core。注册真实 Core 的生产组合入口尚未实现。

### 所有权

- 宿主描述业务意图，提供生命周期、通知、权限和凭证引用。
- Manager 唯一拥有任务定义、状态、控制请求、父子关系、检查点、执行和恢复驱动。
- Download Core 提供下载任务，结果为已验证块集合，不自行决定组装和版本激活。
- Delta Core 提供内容历史、转换、校验、发布及业务撤销；业务数据可独立存储，但变更
  必须由受管理任务驱动，不拥有私有队列或独立恢复服务。
- TaskPlan 编排业务步骤，必须经 Context 发布和等待子 Task，不绕开 Manager 执行。

## 恢复与撤销边界

checkpoint 是可信业务进度，不是任意可回退节点。框架不提供 revertTo：

- 块 revert 只使该块完成状态持久化失效，不恢复旧字节，不修改其他块。
- Task revert 撤销该 Task 明确定义的业务效果；必须自行核对目标、幂等实现。
- Plan revert 显式选择子任务及顺序，经 Context.revert 驱动，不自动倒序所有子任务。
- Manager 持久化 REVERTING 并在中断后重新进入同一对象的 revert；成功后 PAUSED，清空
  旧 output。不支持撤销的 Task 默认报错，不能伪装成功。

撤销接纳静止的 PAUSED / WAITING / FAILED / SUCCEEDED，或续做中断的 REVERTING；
活动执行和活动后代不得撤销。
成功根 Plan 自动回收，不承诺完成后还能按其任务 ID 撤销。业务若需长期撤销，应在成功前
将内容、历史引用和撤销依据移交至工作目录之外，再由新的受管理任务执行撤销。

文件记录使用 schema 2，不自动迁移早期 schema 1；旧记录明确报错。文件原子替换和锁
提供单 owner、进程退出级恢复，不声明断电、目录 fsync 或分布式事务保证。

## 业务安全要求与尚未完成部分

- 转换生成新的完整物理候选，验证后再发布，不引入运行期虚拟覆盖。
- 模组导入与可选修补分离；历史保存实际旧内容，不据此认证为上游原版。
- 内容提交、业务登记和历史引用需具体 Task 实现可恢复协议，框架不会自动事务化。
- 恢复先核对副作用，不能无条件重放；回退前核对目标，不能覆盖外部或后续修改。
- cleanup / reclaim 只处理拥有的未提交或临时内容，不得删除发布结果或共享历史。
- 当前只有叶子任务并发额度，没有完整连接、CPU、内存、空间预算和目标冲突锁体系。
- 不持久化秘密；任务仅保存凭证引用。普通终态回调不是可靠审计或恰好一次事件。

## 验证

```bash
./gradlew --offline :io-task-manager:test :delta-core:build :download-core:build
```

JVM 测试覆盖旧模型、新持久化模型、Plan 回收、块重放和对象级撤销。两个 Core 仍是空壳，
它们的 build 仅证明模块配置，不证明下载或增量功能。

2026-11-14 本轮验证：JVM **34 项通过**，两个 Core build 及
`:app:compileDebugAndroidTestKotlin` 成功。日志：
`agent-tmp/object-revert-verification-final.log`。

后续补齐Android能力回归并完成真机验证：M2007J3SY覆盖安装主APK/测试APK后，
定向三类 **28项全部通过**，6.093秒，包含schema2与Task/Plan/子Task撤销kill恢复。
日志：`agent-tmp/android-durable-coverage-device.log`。覆盖与限制见 DEVICE_TESTS.md。

2026-11-14 本轮修改前的已有证据：JVM 29 项通过，Android 定向恢复回归 `OK (11 tests)`，
包含半块写入杀进程重放。详见 [DURABLE_TASKS.md](DURABLE_TASKS.md) 和
[DEVICE_TESTS.md](DEVICE_TESTS.md)。该真机记录不代表本轮接口和 schema 修改已在设备验证。
