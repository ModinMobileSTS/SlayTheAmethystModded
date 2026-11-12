# Android 进程退出 / 崩溃恢复测试

测试源码：`app/src/androidTest/java/io/stamethyst/iotask/`；进程与存储夹具位于
`app/src/debug/java/io/stamethyst/iotask/`，release 构建不包含。
入口：`io.stamethyst.iotask.TaskProcessRecoveryInstrumentedTest`。

## 测试范围

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
文件事务。生产代码仍只有内存后端。

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
