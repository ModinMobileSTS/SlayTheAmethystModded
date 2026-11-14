# Delta Core

## 状态与依赖

独立 Kotlin/JVM 模块，当前只定义未来 Delta Task 的职责，**没有内容管理、增量或恢复实现及测试**。
以 `api` 依赖 `:io-task-manager`，用于实现具体 Task/TaskPlan；Manager 不反向依赖本模块。
不依赖 Download Core 或 Android，`:app` 尚未接入。
完整架构见 [IO Task Manager](../io-task-manager/README.md)。

## 已确认的算法选型（2026-11-14）

通用二进制差分采用 **HDiffPatch**，优先使用单线程、256 KiB step 的文件流式能力。
ZIP/JAR 作为上层输入/输出组件，不绑定通用内核。Android实测数据、固定源码版本、
与xdelta3/BSDiff的取舍见 [选型记录](../tools/delta-benchmark/DECISION.md)。
这是已确认的引擎选择；本模块仍未实现或接入HDiffPatch。压缩封装、硬内存/磁盘预算、
进程中断恢复、候选发布与回退协议仍需实现验证，不能把测试工具当作生产适配器。

## 目标职责

- 为可逆增量内容存储和管理提供无状态 API 及可实例化执行单元。
- 核对基础内容和历史包，保存并验证修补前内容，生成和校验完整候选产物，提供安全
  发布、版本引用和恢复所需的内容操作；Manager 负责驱动提交及恢复协议。
- 历史包保存的是修补前实际内容，不据此认证为上游原版。
- 支持不同转换方式，不能将 ZIP 条目修改、精确二进制差分和 Steam chunk 组装强行
  等同为一种补丁格式。完整替换也应能够进入可逆内容模型。
- 保存长期完整物理产物和回退路径，不引入运行期文件拦截或虚拟覆盖。
- 接受 Manager 的预算、停止信号和检查点，报告候选、校验及内容操作结果。

## 禁止越界

不拥有任务数据库、独立队列、调度器或恢复服务；不直接启动 Download Core。
内容对象和版本索引不是任务状态，但它们的变更只能由 Manager 控制的执行过程驱动。
修补与模组导入分离，不能将移动适配或修补作为导入的强制步骤。

历史、当前和共享内容的引用须保护到回退承诺结束；恢复前需核对目标，不能覆盖后来
修改。永久删除不等于可撤销操作。具体存储布局、提交协议和保留策略尚未确定。

Delta Core 未来以 `Task` 的具体类型接入 Manager；宿主不会直接创建或执行这些类型。
任务使用 durable 包的 Context、Result 和 Recovery，由 TaskFactory 重建并受 Manager
叶子并行上限约束。恢复须核对已存在候选与提交结果，不能无条件重放转换。
当前尚无具体增量 Task 或内容撤销协议。采用块 revert、Task.revert(context) 和 Plan
显式编排撤销，不支持任意 checkpoint 的 revertTo。长期历史不能放在 Plan 回收目录中。
现有 `archive-delta-core` 和 `archive-delta-generator` 仍是独立实验，
本模块未直接依赖它们，也不将其单写者等限制视作已解决。
构建入口：`./gradlew --offline :delta-core:build`，目前只能验证模块配置。
# TaskPlan 编排约定

Delta 业务由 TaskPlan 组织可复用 Task；历史准备、候选生成、校验和发布的阶段元数据通过
checkpoint 保存。撤销针对块、Task 或 Plan 的业务效果，不提供目标节点；取消只清理
拥有的未提交工作，不能删除已发布或共享历史。持久化模型见 `io-task-manager/DURABLE_TASKS.md`。
