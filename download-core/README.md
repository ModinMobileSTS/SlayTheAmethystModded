# Download Core

## 状态与依赖

独立 Kotlin/JVM 模块，当前只定义未来 Download Task 的职责，**没有下载实现或测试**。
以 `api` 依赖 `:io-task-manager`，用于实现具体 Task/TaskPlan；Manager 不反向依赖本模块。
不依赖 Delta Core 或 Android，`:app` 尚未接入。
完整架构见 [IO Task Manager](../io-task-manager/README.md)。

## 目标职责

- 为启动器一切下载提供底层能力，支持可配置并发、分块、镜像及协议适配。
- 暴露无状态 API 和可实例化的执行单元，由 Manager 实例化和执行。
- 接受 Manager 提供的预算、取消信号和已验证检查点，报告进度、新检查点和结果。
- 支持输入身份校验、断点续传、校验失败重取和镜像切换；来源不支持的能力应明确报告。
- 不同镜像混用分块前必须确认内容身份，URL 和大小相同不足以证明同一内容。
- HTTP Range、Steam manifest/chunk 和 GitHub release 解析保留协议差异；Range 不是
  跨版本增量，第三方 release 也不自动提供增量包。

## 禁止越界

不拥有任务数据库、私有队列、全局调度器、独立恢复扫描或脱离 Manager 的后台执行；
不直接调用 Delta Core，不自行决定业务版本激活。局部连接、缓冲区和内部并行工作
可以有执行期状态，但必须服从 Manager 的作用域、资源预算和停止控制。

Download Core 未来以 `Task` 的具体类型接入 Manager；宿主不会直接创建或执行这些类型。
任务通过 durable.Context 保存检查点，返回 Result，由 TaskFactory 重建；恢复必须先核对
远端身份和本地分块，再明确允许继续。Manager 统一限制叶子任务并行度。
当前已建立编译依赖，但没有虚假的 Download Task 空实现。
具体协议、配置和检查点类型尚未实现；现有 `workshop-core` 及 app 下载器没有搬迁。
构建入口：`./gradlew --offline :download-core:build`，目前只能验证模块配置。
# TaskPlan 编排约定

下载 Task 只下载和校验数据块，成功结果为块集合引用，不自动合并。组装、整体验证与发布
由更大的 TaskPlan 组织后续 Task。下载期间 checkpoint 不关闭连接，只有响应安全暂停
或取消请求才收束在途 I/O。持久化模型见 `io-task-manager/DURABLE_TASKS.md`。
