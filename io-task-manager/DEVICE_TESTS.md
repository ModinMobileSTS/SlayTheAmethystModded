# Android 进程退出 / 崩溃恢复测试

测试源码：`app/src/androidTest/java/io/stamethyst/iotask/`；进程与存储夹具位于
`app/src/debug/java/io/stamethyst/iotask/`，release 构建不包含。
当前持久化入口：`io.stamethyst.iotask.DurableRecoveryInstrumentedTest`，使用实际
FileTaskStore、TaskManager 和 TaskFactory。覆盖 checkpoint 后 kill、已提交结果避免重放、
清理/Plan 回收中途 kill 后恢复、暂停重开、块失败失效与半块写入 kill 后重放。
2026-11-14 本轮接口修改前，新旧测试合计 `OK (11 tests)`，4.33 秒；证据为
`agent-tmp/replayable-block-device.log`。这不是当前 schema 2 / 对象级 revert 的真机结果。

以下“原模型”部分描述旧入口 `io.stamethyst.iotask.TaskProcessRecoveryInstrumentedTest`，
它使用 AtomicFile 夹具，不是新持久化后端。详细新模型契约见
[DURABLE_TASKS.md](DURABLE_TASKS.md)。

## 当前能力覆盖清单（源码范围，不等于已真机通过）

新增 `DurableContractInstrumentedTest` 14 项，扩展 `DurableRecoveryInstrumentedTest` 至10项；
加上旧模型4项，当前定向套件共28项。所有新用例只使用 `files/durable-recovery/<UUID>`。

| 能力 | Android 用例 |
| --- | --- |
| Task/Plan/子Task撤销中途kill后工厂重建、幂等续做 | Recovery: taskRevert / planRevert / childRevert |
| 成功Task撤销清空output、重复撤销、不支持撤销、排队/活动拒绝 | Contract: taskRevertClearsOutput / parallelLeaves |
| 安全暂停、checkpoint不退出执行、暂停请求落盘与重开resume | Contract: pauseRequestPersists |
| WAITING不自动唤醒、重建后resume、观察快照、回调异常隔离 | Contract: waitingResumes |
| FAILED显式retry、恢复核对而非盲目重放 | Contract: failureRequiresRetry |
| 运行取消等待安全点、先清理后终态、父子取消顺序 | Contract: runningCancel / planCancellation |
| 强制协程取消保留RUNNING、默认恢复等待 | Contract: abruptCancellation |
| 叶子并发额度、Plan等待不占叶子额度、稳定子键/定义冲突 | Contract: parallelLeaves / planChildKeys |
| schema2、旧schema拒绝、摘要损坏、pending忽略、owner锁与路径约束 | Contract: schemaChecksumPendingAndOwner |
| 块重复revert持久化、其他块与字节保护、generation变更拒绝 | Contract: blockRevertPreservesBytes |
| 空候选、布局不完整、无归属候选拒绝覆盖 | Contract: emptyBlockLayout |
| 控制请求重开不丢失 | Contract: recoveredControlRequests |
| Plan成功回收、发布产物保留、删除不跟随符号链接 | Contract: successfulPlanReclaimsWork |
| 原有checkpoint/提交/cleanup/reclaim/半块写入kill | Recovery原有用例 |

覆盖当前公开模型的主要正常、失败、边界与进程恢复路径，不宣称穷尽所有交错。
不覆盖断电、设备重启、磁盘耗尽/硬件I/O故障、任意元数据规模、真实下载/Delta事务、
Android后台自动唤醒或多设备适配。编译和打包结果与真机结果必须分别记录。

2026-11-14 本轮离线验证：执行 `./gradlew --offline :io-task-manager:test
:app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 成功（最终复核6秒）。
JVM34项回归通过，主APK和测试APK打包成功。日志：
`agent-tmp/android-durable-coverage-build-final.log`。

本轮真机验证：用户授权沿用 M2007J3SY，核对型号后经仓库 local_adb 非流式覆盖安装
主APK与测试APK，均返回 Success。仅运行上述三类，结果 **OK (28 tests)**，6.093秒，
无失败或跳过。包含schema2、对象级撤销及三项真实kill恢复；不清数据、不卸载。
证据：`agent-tmp/android-durable-coverage-device.log`。该结果仅证明本轮设备/执行窗口，
不扩大为断电、磁盘故障或真实Delta业务验证。

设备获授权后应仅定向运行以下三类，不执行整个应用测试套件：
`DurableContractInstrumentedTest`、`DurableRecoveryInstrumentedTest`、
`TaskProcessRecoveryInstrumentedTest`（均在 `io.stamethyst.iotask` 包）。

## 原模型测试范围

四个用例分别在检查点已保存、产物已保存但终态未写入两个窗口，触发正常进程退出或
进程级 kill 和正常退出。使用 debug 主 APK 声明的独立 `:io_task_recovery_test` Service
进程，不终止启动器或游戏进程。
Service 不导出；instrumentation 与该 Service 使用相同宿主 UID，访问同一私有目录。
请求目录被约束为 UUID，不能指定任意文件路径。

测试主进程使用 Binder death 确认执行进程死亡，随后重新打开磁盘快照，重建 WorkerGroup
及 Task；断言保存状态仍是 RUNNING、检查点完整、写入 PID 与测试主进程不同。恢复后：

- 仅有检查点：经过 recover 核对后继续一次，最终 COMPLETED。
- 已有正确产物：recover 确认完成，不重新执行 execute。
- 重新打开存储仍是 COMPLETED，重复 recover 不重放，恢复完成回调只通知一次。

测试夹具 `ProcessTestStore` 使用 Android AtomicFile 保存单任务快照，只有前后两个
进程顺序访问。它不是生产后端，也不是通用快照序列化实现，不支持任意任务失败数据。
测试仅在写入完成后终止进程，不覆盖写入途中断电、系统重启、多写者竞争或真实业务
文件事务。该夹具不代表当前已实现的 FileTaskStore；真实后端测试见下方新模型说明。

每个用例只访问 debug 宿主私有 files 目录下独立 UUID 子目录，结束时清理该子目录。
意外退出使用 `Process.killProcess`，避免未捕获异常触发系统崩溃对话框和 Service 自动重启；
测试不更改全局系统设置。正常退出使用 `exitProcess`，两者都发生在终态快照写入前。

## 本地验证

从仓库根目录执行，不安装应用：

```bash
./gradlew --offline :app:compileDebugAndroidTestKotlin :io-task-manager:test
```

编译不等于 APK 已经完成打包、DEX 处理或真机执行通过。设备侧还需验证测试 Service
打包与加载、Binder death、崩溃提示和文件存储行为。

## 真机执行条件

必须由用户明确指定本次目标设备，使用仓库 sts / local_adb 工具绑定该设备。
如果设备没有包含上述测试的测试 APK，安装或覆盖安装还需要用户单独明确授权。
不得运行 connectedAndroidTest、connectedCheck 或其他自动部署任务；不得清除数据、
卸载或清除启动器数据。测试要求匹配且包含夹具的新 debug 主 APK；更新主 APK 必须单独
得到授权。依赖通过 debugImplementation/androidTestImplementation 引入，不新增 release
生产依赖，不切换现有业务入口。

测试 APK 已安装且匹配宿主后，通过指定设备的 instrumentation 入口只运行上述测试类，
不运行整个真机测试套件。实际 instrumentation component 和包名以该构建产物为准。
进程退出后重新构建 WorkerGroup 属于被测流程；测试不声称验证了系统自动重启任务。
