# archive-delta-generator

独立、离线 Kotlin/JVM 生成库，派生自 Archive Patcher。不是 HTTP 服务，不依赖 Android，也不接入导入或启动路径。实现采用 **Test-After**：先实现，再补针对需求的测试并修正发现的问题。

## API

```kotlin
val result = ArchiveDeltaGenerator().generate(
    base = baseJar,
    target = targetJar,
    destination = newPackage,
    temporaryDirectory = ownedWorkDirectory,
    limits = ArchiveLimits(),
    control = ArchiveControl(progress = { phase -> println(phase) }),
)
val info = ArchivePackages.inspect(result.path)
ArchivePackages.buildCandidate(baseJar, result.path, newCandidateJar, ownedWorkDirectory)
```

API 包：`io.stamethyst.archivedelta.generator`、`io.stamethyst.archivedelta`。
`ArchiveDeltaException.failure` 区分输入错误、不支持、超限、取消、验证失败和 I/O。底层 ZIP/EOF I/O 错误保留在 cause 中，不保证所有损坏输入都归类成 INVALID_INPUT。进度是阶段通知，不是百分比。

生成必须完成：校验 ZIP 内容/CRC → 推断参数与规划 → 有限展开 → BSDiff → 压缩封装 → **回放应用并验证整文件长度/SHA-256** → 无覆盖发布。
输入必须在调用期间保持不变且由单写者持有；前后摘要检查不是跨进程锁。生成器不修改基础或目标文件。输出必须不存在，包括符号链接；在输出目录的私有临时目录构建，经硬链接无覆盖发布。不支持硬链接的文件系统直接失败，不退化成覆盖。工作目录由调用方显式指定，不修改进程全局 `java.io.tmpdir`。

## 与上游的区别

- 保留 GFbFv1_0 / ENDSLEY/BSDIFF43、条目规划、参数推断与后缀排序算法。
- 拆为 `archive-delta-core`（shared/applier + 封装）和本生成模块。
- 项目 API 使用**堆数组** BSDiff，预算检查为 `N + M + 4*(N+1)`，不是原 mmap 默认入口。这是主要数组字节预算，**不含** JVM、压缩器、桶数组、对象及 GC 开销，不是最大 RSS。
- 严格入口校验区间、跨层长度、指令进展、EOF、尾随数据，限制展开与目标输出，验证全部重压缩完成。
- 修复上游精确读取 EOF 判断和单字节流符号问题；改动文件有修改声明。
- 完整许可证、上游提交和归属见 `UPSTREAM.md`、`LICENSE.archive-patcher`，并打入模块 JAR。

`com.google.archivepatcher.*` 仍保留上游公开类以便互操作，但**不是受支持的安全 API**：直接调用这些入口会绕过项目限额、摘要、临时目录与发布保护。不要用 legacy `FileByFileV1DeltaGenerator` 作为服务入口。

## 支持范围与安全边界

默认：文件 32 MiB、实际 ZIP 内容总展开 64 MiB、单条目 32 MiB、条目数 10000、原生补丁 128 MiB、包 64 MiB、BSDiff 主数组 256 MiB。生成的 delta-friendly 文件还受独立 64 MiB 输出限制（包括头部和保留压缩字节）。允许调用方调小限额；调大不保证机器有足够资源。后缀数组仍有略低于 512 MiB 的旧中间文件限制。

正常支持 STORE/DEFLATE。重复名称拒绝。不执行任何归档内代码，也不把条目解压到命名路径。未实现 ZIP64；保留上游 EOCD 搜索/压缩参数推断限制，长注释等合法 ZIP 也可能不被支持。精确字节还原不等于签名验证或可玩兼容修复。

取消是协作式：阶段、复制和输出有检查，BSDiff 自身支持线程中断；**参数推断和后缀排序不保证立即响应 cancellation lambda**。没有 CPU 截止时间、总物理磁盘配额或硬 RSS 限制。展开/补丁等文件分别限额，不代表聚合工作空间硬配额。不能直接作为处理匿名恶意上传的沙箱；未来网络服务需要进程和 OS 级隔离。

临时目录正常返回及异常时清理；清理错误会报告或作为 suppressed exception 保留。发布成功后若清理失败，可能抛错但输出已存在，调用方应检查输出；不承诺断电持久性、fsync、崩溃恢复或并发恶意目录防护。

Deflate 兼容窗口在生成和应用入口检查；当前仅 JVM 21 验证，没有 Android 或跨运行时压缩兼容保证。

## 验证

```bash
./gradlew :archive-delta-core:build :archive-delta-generator:build :jar-delta:build --offline --console=plain
./gradlew :archive-delta-generator:corpusTest --offline --console=plain
```

普通测试使用独立构建的 ZIP 和完整字节 oracle，覆盖 STORE/DEFLATE、增删改、元数据、空条目、8组固定种子输入、限制、取消、输入变化、错误基础、损坏封装及不覆盖输出。原版生成的 synthetic golden 补丁保存在测试资源，附来源；还测试新生成补丁经 legacy 应用链还原。

显式 corpusTest 使用已有且忽略的 `jar-delta/test-corpus/base.jar`，不存在则失败，不下载文件。选择条目只按名称排序，无模组类名或兼容逻辑；结果不是可玩修复。不会提交第三方 JAR。单一样本不证明性能优势。

本轮没有做设备、HTTP 服务、原子安装/历史恢复接入、资源耗尽压力测试或全量模糊测试。
