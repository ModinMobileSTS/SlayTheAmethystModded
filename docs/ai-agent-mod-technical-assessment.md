# AI Agent 修改模组技术调研

更新日期：2026-09-20

## 结论摘要

当前方案的**总体架构方向是合理的**：云端 LLM 只负责规划和生成，Android App 通过受控 tool calling 执行；原模组只读，修改写入独立 patch mod，设备端用 ECJ 编译，打包前用 ASM 做目标签名和钩子结构检查。这比让模型直接改原 JAR、执行任意 shell 或依赖远程 Agent 直接访问手机文件系统安全得多。

但当前实现还不能把生成的 JAR 定义为“可信、稳定、一次成功”。它目前更准确的定位是：**有边界的代码生成和静态预检流水线**。它能显著降低低级错误和误操作，但不能证明 patch 在真实 ModTheSpire/JVM/游戏状态下能运行，更不能证明不会在启动、特定 UI、战斗、资源加载或特定模组组合中崩溃。

直接回答：

| 问题 | 判断 |
|---|---|
| 现在 AI agent 修改模组的方式是否合理 | 方向合理，适合作为第一版；应坚持 patch mod + typed tools，而不是任意文件/命令 Agent。 |
| 打包出的模组是否可能导致崩溃 | 可能。当前检查可拦截一部分加载错误和签名错误，但不能拦截运行时异常、JVM verifier/linkage 问题、钩子语义错误、资源/依赖冲突和状态相关崩溃。 |
| APP 暴露的能力能否生成足够可信稳定的模组 | 对小范围、局部、已有 API 的 Prefix/Postfix 修改有希望；对复杂行为、Insert/Instrument/Raw、资源系统、跨模组逻辑和需要运行验证的任务，不足。 |
| 信息是否足够支撑大模型一次成功 | 通常不足。当前信息足够支撑“开始修改”和“生成候选 patch”，不足以支撑无测试的一次成功交付。 |

## 当前实现方式

主要证据：`docs/ai-agent-patch-mod.md`、`AiModEditorScreen.kt`、`PolicyGatedAgentGateway.kt`、`AgentPatchModManager.kt`、`AgentPatchSourceCompiler.kt`。

流程是：

1. 用户选择父模组，Agent 通过 `decompile_agent_mod_source` 提取并反编译父 JAR。
2. 用户提出修改后，Agent 调用 `create_agent_patch_mod`，生成独立的 `patch_source/<patch_id>/`，原模组不被覆盖。
3. Agent 通过 workspace tools 写 Java 8 源码。
4. Agent 可调用 `inspect_agent_patch_target` 读取真实 classfile 的方法描述符、重载和字段，再调用 `generate_agent_patch_skeleton` 生成带精确 `paramtypez` 的骨架。
5. App 使用 ECJ，以 Java 8 source/target 编译到 patch 目录。
6. `validate_agent_patch_mod` 和 `package_agent_patch_mod` 使用 ASM 检查 patch 注解、目标方法、参数签名、静态 hook、部分注入参数和返回类型。
7. JAR 写入临时文件，检查 ZIP 可读性、根目录 `ModTheSpire.json` 和 `agent-patch.json`，再原子替换产物。
8. Agent 可以请求启用；启用后下一次游戏启动才加载，且 patch manifest 依赖父模组。

工具边界也基本正确：workspace 路径有 canonical path 检查，写入限定在 `patch_source`，没有把 `ProcessBuilder`、任意 shell、任意网络或 game-probe 的 `redefine_class` 暴露给云端模型。

## 已经解决的问题

### 1. 产物隔离和回滚基础较好

- 父 JAR 只读，patch revision 独立。
- patch 默认不启用，启用是单独操作。
- 依赖父 mod，父模组未启用时不允许启用 patch。
- 输出先写临时 JAR，ZIP 验证通过后替换。
- workspace 写入、删除和归档路径有范围限制。

这些措施主要解决“破坏原模组”和“模型把文件写到任意位置”的问题，不能解决 patch 自身逻辑错误。

### 2. 低级字节码目标错误有较强防线

`AgentPatchTargetInspector` 从 classfile 读取真实 descriptor，而不是信任反编译文本；`AgentPatchPreflight` 会拒绝不存在的类/方法、未指定的重载、参数签名不匹配、明显错误的 Prefix/Postfix 返回类型、非法 `__instance`/`__result`/字段注入。

这对常见的“方法名写对但重载错了”“模型猜错参数类型”“Postfix 返回类型不匹配”很有效。

### 3. Android 端编译方案可行

ECJ + runtime `rt.jar` + 游戏/ModTheSpire/required mods/父模组 classpath 的组合解决了 Android 没有 `javax.tools` 和 ART/JDK API 差异的问题。已有单元测试和 instrumented test 覆盖 Java 8 字节码、重复 ZIP entry 和 classpath fallback。

## 关键风险和缺口

### P0：编译失败后可能打包旧 class，造成源码和产物不一致

`AgentPatchSourceCompiler.compile` 把 class 输出到现有 `patchRoot`，没有在编译前清理上一轮生成的 class；`AgentPatchModPackageTool` 只判断“源码存在时是否存在任意 `.class`”。因此可能出现：

1. 第一次编译成功，目录留下旧 class。
2. Agent 修改源码，第二次编译失败。
3. 打包检查仍发现旧 class，于是继续执行 preflight。
4. 如果旧 class 仍通过 preflight，产物包含旧实现，而 Agent 以为它包含新实现。

这是一个实际的正确性问题，不只是理论上的崩溃风险。应将每次编译变成事务：清理或切换到新的 `classes/<compile_id>` 输出目录；只有本次 ECJ 成功、class 数量和 class 指纹写入 `compile-state.json` 后，package 才允许继续。源码、编译结果和 preflight 必须绑定同一个 source hash。

### P0：没有 APP 内的真实游戏试启动/冒烟验证

当前 APP 暴露的 patch 工具到 `package` 和 `enable` 为止，没有 `prepare_launch_check`、隔离试运行、`launch_trial` 或自动禁用机制。仓库的主机侧 Harness 有 `smoke`、logcat、启动状态和崩溃检测能力，但这些能力没有形成 Android Agent tool 闭环。

因此当前 preflight 只能回答“字节码和注解看起来合法”，不能回答：

- ModTheSpire 是否能实际加载该 patch；
- 类加载时是否 `NoClassDefFoundError`、`NoSuchMethodError`、`VerifyError`；
- hook 是否在真实调用点触发；
- `__args` 下标、空值、生命周期和线程假设是否正确；
- 资源路径、Texture/Spine/JSON、事件队列和 UI 状态是否正确；
- 与其它启用模组组合后是否冲突。

至少应增加“默认不持久启用”的试运行：生成临时 launch snapshot，只加载父模组和 patch，捕获启动日志、退出码、logcat、最新日志和 crash marker；成功标准不能只看进程存活，还要经过 MTS 初始化完成并执行一个固定的最小场景。

### P0：高级 Spire hook 只做结构检查

`AgentPatchPreflight` 对 `Insert`、`Instrument`、`Raw` 明确只发 warning，不验证 locator、字节码插入位置、local variable 语义或运行时栈约束。文档也承认这些需要 game smoke test。

这类 hook 应在没有真实 smoke test 时禁止 package 或至少禁止 enable。第一版建议只允许生成和启用受限的 Prefix/Postfix，先不把高级 hook 当作普通成功路径。

### P1：JAR 验证不足以证明 ModTheSpire manifest 可加载

`AgentPatchModManager.verifyZip` 目前主要检查 ZIP 可读以及两个文件存在。它没有在 package 阶段复用 `MtsLaunchManifestValidator` 检查 `modid`、字段类型和 manifest 结构，也没有检查 patch 依赖是否都能在当前 launch snapshot 解析到。

虽然正常创建流程会写入合法 manifest，但 Agent 可以写/替换 patch 目录内的 `ModTheSpire.json`。应在 package 和 enable 前重新解析最终 JAR，并验证：

- manifest schema 合法；
- `modid` 与生成的 patch id 一致；
- 父依赖精确匹配且存在；
- 额外依赖能被当前 launch 配置满足；
- JAR 内 class entry 和目录路径合法；
- classfile 能被 ASM 读取，版本不超过游戏 JVM 支持范围。

### P1：静态预检无法覆盖 JVM verifier/linkage 和运行时语义

ECJ 通过只代表源码能对当前 classpath 编译。仍可能发生：

- 游戏实际加载的 JAR 版本与编译 classpath 不同；
- required mod 或父 mod 在运行时缺失/版本不同；
- 类初始化、静态字段、资源初始化抛异常；
- 方法体产生 `ClassCastException`、`NullPointerException`、数组越界或错误线程访问；
- hook 逻辑递归调用自身、改变返回值契约或破坏游戏状态；
- 类重复、资源覆盖、依赖顺序和多个 patch 交互导致问题。

这类问题不能靠更多 prompt 文字彻底解决，必须靠运行验证、日志反馈和回滚。

### P1：Agent loop 没有明确的轮次、费用和重复调用上限

`PolicyGatedAgentGateway.respond` 和 streaming 路径使用持续循环，当前代码没有看到最大 tool rounds、总 token、费用、单工具重复调用或总耗时的本地硬限制。模型可能反复读取、反复编译或重复创建 patch，导致耗时、费用和文件膨胀。

应把轮次、总 wall-clock、每工具调用次数、同参数重复次数、文件总量和 API 预算放在本地 runner，而不是交给模型自行停止。

### P1：工具策略名为 Gate，但当前编辑器一次性允许所有高风险操作

`AiModEditorScreen` 的 allow-list 同时包含 `WORKSPACE_WRITE`、`PATCH_CREATE`、`PATCH_COMPILE`、`PATCH_PACKAGE`、`PATCH_ENABLE` 和 `PATCH_DELETE`。工具确实有分类，但 `AgentToolPolicyGate` 只是静态集合判断，不是按当前调用、用户批准、目标 artifact、会话状态和风险等级做动态决策。

系统 prompt 要求“启用前询问”和“删除前询问”，但自然语言不是安全边界。尤其是模型可能在一次连续 tool loop 中直接调用 enable/delete。应把批准变成真实状态机：工具请求先进入 `AwaitingApproval`，UI 展示精确目标、diff、JAR hash 和风险，用户批准后只放行该次调用。

### P1：会话恢复没有完整恢复 Agent 执行状态

会话文件保存了用户/助手文本和 UI tool call 展示，但没有看到完整的 provider assistant tool-call message、tool result、当前 patch workspace id、source hash、compile state 和待批准操作的可靠持久化。进程被杀或切换会话后，`activePatchWorkspace` 是内存字段，可能无法继续同一 patch revision 的操作。

这会降低长任务的恢复能力，也会让模型在恢复后重复创建 patch、丢失工具上下文或误判当前状态。应持久化结构化 transcript 和 artifact state，而不是仅保存展示文本。

### P2：父模组 source workspace 可能残留旧文件

`createWorkspace`/inspection 会向既有 `source/` 提取内容，但没有明显的“按 source JAR hash 清空并重建”事务。如果用户重新导入同一 modid 的新版本，旧 class/resource 可能残留并被模型读到。应按 JAR SHA-256 建立 source snapshot，hash 变化时使用新目录或原子重建。

### P2：反编译信息天然不完整

CFR 能提供很有价值的上下文，但反编译代码不是原始源码：泛型、局部变量名、控制流、合成类、注解保留情况和异常边界可能失真；超过 3000 个 class 时还会保留 raw class。模型若只读取少量文件，也可能遗漏初始化顺序、注册入口和依赖约束。

因此 `decompile_agent_mod_source` 应配套结构化索引：manifest、类列表、父类/接口、public API、patch 注解、资源列表、classfile 版本和来源 hash。对目标方法应优先提供 ASM descriptor、调用者/被调用者摘要和相关字段，而不是让模型依赖全文反编译。

## APP 当前暴露能力是否足够

### 足够支持的任务

- 修改一个已有方法的简单 Prefix/Postfix 行为。
- 调整返回值或对已有参数做局部、无状态处理。
- 修改一个已知资源/配置文件，且格式和生命周期清楚。
- 基于父模组现有 API 的小型兼容补丁。
- 先检查目标签名、生成骨架、编译、静态 preflight，再由用户手动试运行。

这些任务仍不保证一次成功，但工具提供的信息和边界已经能让强模型较高概率完成候选 patch。

### 明显不足的任务

- 需要知道真实运行时状态、调用时机或 UI/战斗流程的修改。
- `Insert`、`Instrument`、`Raw`、复杂 bytecode locator。
- 新增跨模组依赖、第三方库、Gradle 构建或非 Java 8 API。
- 资源打包、动态加载、纹理/Spine/shader、原生库和线程相关修改。
- 需要自动复现崩溃并验证修复的任务。
- 需要多次启动、进入特定房间、出牌、打开界面才能观察结果的任务。
- 修改父模组核心初始化或多个 patch 之间有顺序/冲突关系的任务。

对于这些任务，当前暴露的信息不足以让模型一次成功。模型最多能生成一个可编译候选，需要运行闭环。

## 建议的目标流水线

```text
用户需求
  -> 只读环境快照和目标定位
  -> 生成 patch revision
  -> 写源码/资源
  -> clean compile（绑定 source hash）
  -> classfile + manifest + dependency preflight
  -> artifact fingerprint
  -> isolated trial launch（默认不持久启用）
  -> 固定 smoke scenario
  -> 收集结构化日志/crash/exit 状态
  -> Agent 根据失败反馈修复
  -> 用户审批
  -> enable + 保留上一版本回滚
```

### P0 实施项

1. 修复编译事务：清理旧 class；记录 source hash、classpath fingerprint、编译 id、class list 和 diagnostics；package 只接受本次成功编译结果。
2. 增加 Android 侧 `prepare_launch_check` 和 `launch_trial`，但只允许临时 artifact，不直接持久启用。
3. 试运行捕获 `latest.log`、boot bridge、logcat、进程退出、crash marker、实际 launch mod snapshot，并把结果作为结构化 tool output 返回模型。
4. 在启用前强制 manifest/dependency/classfile 完整验证；失败自动保持 disabled。
5. 将 `Insert`/`Instrument`/`Raw` 设为需要 smoke test 的高风险类型，未通过 smoke 不允许 enable。

### P1 实施项

1. 把静态 allow-list 改成 per-call approval state machine；尤其保护 package、enable、delete 和 launch。
2. 为 Agent runner 增加最大轮次、总时长、token/费用、重复调用和工作区预算。
3. 持久化完整结构化 transcript、tool call/result、patch id、artifact hash、compile state 和 pending approval。
4. 生成 source snapshot index 和 runtime compatibility manifest，减少模型依赖反编译全文。
5. 增加“只读分析模式”“生成候选模式”“试运行模式”“持久启用模式”四个明确阶段，按阶段逐步增加工具集合。

### P2 实施项

1. 用 source JAR hash 隔离 inspection/source workspace，避免旧版本残留。
2. 增加 classfile verifier、重复 class/resource 检查、依赖版本和实际启动列表对比。
3. 建立 patch 回归样本集：常见 Prefix/Postfix、构造器、重载、静态方法、资源读取、父模组初始化和已知崩溃案例。
4. 统计真实任务的首次编译成功率、首次 smoke 成功率、运行后崩溃率、平均修复轮次和人工回滚率，不以模型最终文本判断成功。

## 可验收标准

在宣称“可信稳定”前，至少应满足：

- 任何 package artifact 都能追溯到唯一 source hash 和唯一成功 compile id。
- 编译失败、preflight 失败、manifest/dependency 失败都不能产生可启用 artifact。
- 未经过 trial 的 artifact 永远不能被默认启用。
- trial 崩溃后 patch 自动保持 disabled，父模组和原 JAR 不受影响。
- 模型可以收到机器可读的启动失败原因和相关日志，而不是只收到“启动失败”。
- 删除、启用、试运行和导出都有真实用户批准记录。
- 进程被杀后可恢复到明确状态：已完成、等待批准、编译失败、试运行失败或可重试，而不是重新猜测 workspace 状态。
- 以固定 patch benchmark 测量：编译通过率、preflight 拦截率、首次 smoke 通过率、崩溃率和修复成功率。

## 最终判断

当前设计已经跨过“概念验证”阶段，安全边界和 patch 产物模型是可继续投资的；但它还没有跨过“可自动交付运行代码”的门槛。静态检查解决的是**加载前的部分错误**，不能替代真实游戏执行。

推荐的产品承诺应是：

> Agent 可以在受控 workspace 中生成、编译并静态验证一个候选 patch；用户在隔离试运行通过后再启用。

不应承诺：

> Agent 能一次生成稳定模组，打包成功就不会导致游戏崩溃。
