# Agent 源码与类路径缓存

## 范围与接口

`AgentModSourceSnapshot.prepare`、`AgentModInspectionManager`、
`AgentPatchClassDecompiler.decompileClass`、`AgentPatchSourceCompiler` 保留原有调用方式。
缓存覆盖母模组以及通过同一 CFR 入口读取的其他 JAR。完整旧版 JAR 反编译接口保持原语义。

API 磁盘缓存使用公开 opaque `SourceToken` 标记源码来源。必须在读缓存/反编译**之前**
调用 `capture(jar, classpath)`，并把同一 token 传给 read 和 write。有依赖时必须传 CFR 实际使用
的 classpath。旧 read 接口仍可用；**旧 write 未传 token 时不缓存**，防止给已计算的旧源码
重新标注新文件身份。Gateway 的 `decompileOne` 接线如下：

```kotlin
val sourceToken = AgentApiSourceCache.capture(jar, classpath) // 在任何读取/计算之前
AgentApiSourceCache.read(cacheRoot, jar, binaryName, classpath = classpath, sourceToken = sourceToken)
// cache miss: decompiled = CFR(...).source
AgentApiSourceCache.write(cacheRoot, jar, binaryName, decompiled, classpath = classpath, sourceToken = sourceToken)
```

write 校验当前源/依赖身份仍等于捕获 token，在写临时文件后、发布前再次校验；最终路径始终来自
原 token。计算完成后依赖 A→B 时，A 的源码不会发布到 B 目录。即使发布检查后再次变化，文件也只
属于原 A 键。`provenance-v1` 磁盘命名版本隔离此前没有来源 token 的缓存。身份无法获取时 token
为 null，保持普通反编译但不缓存。

## 文件身份和失效

- 快速身份由规范化路径、Unix fileKey（设备/inode）、size、高精度 mtime、ctime、文件类型组成。
  不同目录里的同名、同大小、同 mtime 文件不会串用。
- 同路径 rename-over 替换即使保留 size/mtime，也会因 fileKey/ctime 失效。
  原地改写后恢复 mtime 会因 ctime 失效。没有 inode + ctime 的文件系统提供器保守禁用命中，
  不退回仅 size/mtime 的判断。依赖目录或通配符也禁用 CFR 结果和 driver 复用。
- 这些是文件系统变更标记，不是安全意义上的内容证明；无法防御能同时伪造 inode、ctime 等
  元数据的外部写入方。文件系统时间精度也由提供器决定。正式 SHA-256 仍在首次读取时计算。
- JAR hash 的工作缓存最多 64 项；只有整个文件真实 SHA-256 完成、前后身份一致时才入缓存。
  snapshot 的 `source_sha256` 仍是内容 hash。内容不变保留已有源码，内容改变清空旧 source 树。
  进程重启、缓存淘汰会重新计算内容 hash。

## CFR 结果与并发

- 结果键：源 JAR 身份 + 原始 binary class 请求 + **有序**依赖身份（包含缺失依赖状态）+
  反编译选项版本。依赖新增、删除、更新、顺序变化均失效。修改 CFR 版本或选项时需同步提升
  `AgentDecompileIdentity.OPTIONS_VERSION`。
- 结果缓存为访问顺序 LRU，最多 128 项、4 MiB 源码字符载荷（每字符按 2 字节计；不含对象开销）。
  超预算的单个结果可返回，但不长期保留。进程重启或淘汰后允许再次 CFR；不把缺少依赖来源信息的
  任意旧 `source/*.java` 当成命中依据。
- 同键并发请求共用正在执行的 FutureTask；不同键可并发。失败不入缓存，正在计算的结果只存活于
  活跃调用期间。CFR 前后重新检查输入身份，检测到变化则失败并要求重试。
  等待者中断只结束该等待者，不取消 owner 或其他等待者。
- CFR driver 仍按线程隔离，driver 键同样包含源 JAR 和依赖身份，防止源码缓存正确失效后却继续
  使用旧依赖的类型状态。每线程最多 2 个 driver，每个最多分析 32 次后更新，限制 CFR 内部历史；
  线程输出 sink 不捕获其他调用的 buffer。
- API 磁盘缓存沿用原来的 512 文件/24 MiB 预算，目录键升级为上述身份及选项指纹，旧目录自然冷失效。
  单类 CFR 热命中不需要打开 JAR、创建临时 JAR 或再次执行 analyse。

## 源码数量

`recordOnDemandClass` 首次接触一个有效 snapshot 时扫描一次现有 source 树，兼容旧 metadata；
随后按已物化的唯一相对路径增量统计，重复写同一类不会增加计数。索引按 sourceRoot 路径、目录
fileKey 和内容 hash 区分，最多保留 8 个 snapshot、合计 32,768 路径。淘汰、进程重启或超过路径
预算时重新扫描。普通类直接检查文件；源码形式的内部类名称才补做 JAR entry 解析。

同一 root 的 prepare/inspection/record metadata 操作用固定锁条带串行化。旧 snapshot 的迟到
record 不覆盖新 metadata。source 树视为 launcher 管理的只读产物；不监听外部对任意未请求路径
的手动增删，这类操作在下次冷索引时重新统计。原有源码写入调用仍在 Gateway 中。

## 类路径

- `required_mods` 的目录身份缓存候选文件清单（最多 8 个目录版本），目录变更后重新列举。
  每次仍 stat 候选 JAR，因而不漏掉不改变父目录时间的原地 JAR 更新。
  不存在的目录与成功读取的空目录可以缓存；现存目录 `listFiles == null` 属于读取失败，
  只对当前调用降级为空，不进入目录缓存，因此同身份下下一次调用也会重试读取。
- 每个候选的验证结果按存储 namespace + 文件身份缓存（最多 128 项）。热调用不再打开 ZipFile
  重复验证，修改一个候选只验证该候选。输出 overlay 的身份同样检查，删除或破坏后重建。
- 解析器保留锁保护，包含验证与 overlay 发布。overlay 使用规范路径 + 真实内容 hash + 格式版本
  定名，先写唯一临时文件、验证成功后才 move 到最终路径；读者不会接收到半成品路径。
- 无法索引的结果不记为成功，后续调用可恢复。读者取得 classpath 后，外部程序仍可能改动文件；
  本实现不是文件系统快照事务，也不承诺阻止外部写入。
  hash、cacheDir 和临时文件准备均在单候选的恢复边界内，I/O 失败返回 unindexable，继续解析其余
  候选；CancellationException 保持取消，InterruptedException/InterruptedIOException 保留线程
  中断标记并转为取消，已有临时文件在 finally 清理。

## 离线工作次数证据

定向 JVM 测试使用真实文件及 CFR，并通过可注入 hash/扫描/验证函数和 `analysisCount` 统计工作：

- 同一稳定 JAR 连续 10 次 hash 请求：真实全文件 hash **1 次**；不同路径隔离，改写/替换重新 hash。
- 同类连续 9 次实际 CFR 请求：analyse **1 次**；源更新、依赖更新、依赖删除各增加 **1 次**。
- 6 个并发请求（5 个同键 + 1 个不同 JAR）：analyse **2 次**，源码不交叉。
- 2 个稳定 classpath JAR 连续解析 10 次：目录列举 **1 次**、ZIP 验证 **2 次**。
  添加一个 JAR 只增加一次验证；单个更新只增加一次验证。
- 冷 source 树与随后 10 次相同类 record 计数：树扫描 **1 次**；新 snapshot 重新扫描。

这些数字是确定性的离线工作次数，不代表 Android 实机耗时或总体 Agent 加速百分比。
覆盖还包括失败重试、LRU/字节预算、依赖顺序/配置版本、overlay 完成前并发访问、overlay 损坏恢复、
反编译完成后更新依赖再发布、同目录身份读取失败后恢复、单候选准备失败和取消清理。依赖行为测试
让被调用方法从普通 Object 签名变为泛型签名，验证 CFR 实际输出中的 String 强制转换变化，而不只
统计 analyse 次数。mtime 保持测试使用完整 FileTime，保留纳秒精度。

验证命令（仓库根目录）：

```sh
flock agent-tmp/ai-agent-gradle.lock ./gradlew --offline :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.mods.AgentSourceCacheTest' \
  --tests 'io.stamethyst.backend.mods.AgentModSourceSnapshotTest' \
  --tests 'io.stamethyst.backend.mods.AgentModInspectionManagerTest' \
  --tests 'io.stamethyst.backend.mods.AgentPatchClassDecompilerTest' \
  --tests 'io.stamethyst.backend.mods.AgentCompileClasspathCacheTest' \
  --tests 'io.stamethyst.backend.mods.AgentPatchSourceCompilerTest' \
  --tests 'io.stamethyst.backend.mods.AgentApiSourceCacheTest' \
  --console=plain --max-workers=2
```

## 本次执行结果

最终执行退出码 **0**，`BUILD SUCCESSFUL`；7 类共 **47 项通过，0 失败、0 跳过**。
Gradle 报告 35 秒（仅本次离线构建/测试时长，不代表实机性能）。分项：
AgentSourceCacheTest 6、AgentModSourceSnapshotTest 3、AgentModInspectionManagerTest 3、
AgentPatchClassDecompilerTest 5、AgentCompileClasspathCacheTest 4、AgentPatchSourceCompilerTest 14、
AgentApiSourceCacheTest 12。

实际命令另附 `--init-script agent-tmp/ai-agent-cache-test-summary.gradle`，在测试回调内保存结果，
避免其他并行任务覆盖共享 XML。摘要位于 `agent-tmp/ai-agent-cache-test-results.json`；
完整命令和交付记录位于 `agent-tmp/ai-agent-cache-verification.md`。Gateway 已接入计算前捕获的
同一个 sourceToken，来源校验直接覆盖计算完成至发布之间的依赖变更窗口。

首轮构建因并行 UI/持久化代码尚未完整编译而退出 1，测试未执行；对应 owner 修复后，最终执行
包含上述全部缓存代码、review 修复及新增回归。未回退旧实现运行对比；工作次数证据来自可观察
计数器与注入函数。不涉及安装、设备、网络服务或依赖下载。
