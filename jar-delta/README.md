# jar-delta：独立的条目级增量实验

另有独立的 Archive Patcher 整文件差分：`:archive-delta-generator` 负责生成，`:archive-delta-core` 负责封装与候选验证。本模块通过显式 `ArchiveDeltaAdapter` 提供 inspect/buildCandidate，不自动改写以下条目格式、不接入旧 apply/restore，不提交安装文件；调用方仍须另行管理历史和提交。见 `../archive-delta-generator/README.md`。

纯 Kotlin/JVM 模块。只按数据声明修改 ZIP/JAR 条目，输出完整 JAR；不依赖 Android、ASM、模组身份、现有导入补丁或游戏运行环境。根构建注册模块，但 `app` 不依赖它，也没有接入生产导入/启动路径。

## 范围与非目标

- 根据整个基础文件的 SHA-256 严格匹配输入。
- 新增、替换、删除完整条目；未修改条目的未压缩内容不变。
- 保存修补前的实际文件，验证后以内容摘要命名；它不一定是上游原版。
- 在同目录临时文件中生成完整结果，完整读取并校验后原子替换。
- 从历史包恢复，恢复后的文件与历史包逐字节一致。
- 不执行任何增量包提供的转换代码；不下载、推荐或自动应用补丁。
- 不提供二进制差分、多补丁组合、历史清理、下载信任或配置系统。
- 不保证条目压缩方式、时间戳、权限等 ZIP 元信息不变。
- 不验证模组是否能启动或玩法是否正确。引擎不会按某个模组调整行为。

## API

```kotlin
val engine = JarDeltaEngine()
val applied = engine.apply(currentJar, deltaZip, historyDirectory)
// 调用方应保留此引用；引擎不建设历史索引数据库。
engine.restore(currentJar, applied.history)
```

输入为 `java.nio.file.Path`；I/O/格式错误抛出异常，不返回伪成功。
`DeltaLimits` 限制条目数、单条目/总解压大小、清单大小。路径拒绝绝对路径、反斜杠、控制字符、空路径段和 `.`/`..`；格式 v1 暂不支持非 BMP 名称。重复 ZIP 条目和重复操作直接拒绝。

## 增量格式 v1

增量包是 ZIP，恰好包含 `manifest.json` 与清单声明的 payload 文件。不允许额外条目（包括未声明的目录条目）。清单示意：

```json
{
  "formatVersion": 1,
  "baseSha256": "<64 lowercase hex>",
  "resultEntriesSha256": "<64 lowercase hex>",
  "operations": [
    {"type":"replace", "entry":"example/A.class", "payload":"payload/1", "sha256":"<64 lowercase hex>"},
    {"type":"add", "entry":"assets/new.frag", "payload":"payload/2", "sha256":"<64 lowercase hex>"},
    {"type":"delete", "entry":"assets/old.frag"}
  ]
}
```

- `add` 必须原先不存在；`replace`/`delete` 必须存在。
- 每个目标只能操作一次；每个 payload 只能声明一次。
- payload 是整个条目的原始内容，不是压缩差分；必须在 `payload/` 下。
- `delete` 不带 payload/hash。目录名以 `/` 结尾，目录内容必须为空。
- 不支持签名 JAR：基础包包含顶层 `META-INF/*.SF/*.RSA/*.DSA/*.EC/SIG-*` 时拒绝，补丁也不能操作这些文件；不偷偷删除签名。
- CRC、条目长度和内容 SHA-256 均参与校验；SHA-256 只提供内容一致性，**不是发布者认证**。

基础包/历史包摘要是整个文件 SHA-256。结果摘要是：按条目名称排序，对每个条目依次追加 `UTF-8 名称字节长度（u32 大端）+ 名称 UTF-8 字节 + 未压缩内容长度（u64 大端）+ 内容 SHA-256 原始 32 字节`，再对所有追加字节计算 SHA-256。名称限制在 BMP，因此 Kotlin 字符串排序与 Unicode 码点/UTF-8 顺序一致。包括显式目录条目，不包含 ZIP 时间戳和压缩元数据。

## 历史包与提交边界

历史包为 `<historyDirectory>/<baseSha256>.jar`：

1. 先完成基础/增量/预期结果校验。
2. 已有历史包则验证摘要并复用；损坏则报错，不覆盖。
3. 不存在则复制到历史目录临时文件，校验后用硬链接原子、无覆盖地发布，再移除临时名。
4. 从已验证历史包生成候选完整 JAR；读取验证结果内容摘要。
5. 再次校验历史和当前文件，使用 `ATOMIC_MOVE + REPLACE_EXISTING` 提交。

文件系统必须支持历史目录内硬链接和当前文件目录内原子替换；不支持时明确失败，**不退化为先删当前文件**。后续应用失败时保留已经成功保存的历史包；清理本次临时文件。恢复也采用临时复制、整文件校验、原子替换。

这是**单写者、离线原型**：调用方必须拥有这些目录，期间不得运行游戏、并发修改基础文件/历史文件/补丁或替换父目录。拒绝输入文件和历史文件自身为符号链接，但不试图防御恶意父目录所有者。提交前摘要检查不能替代跨进程锁。本轮不承诺断电持久性、进程被杀后的自动清理或崩溃恢复；没有 fsync 协议。无法清理临时文件等文件系统错误会如实报告，不宣称绝对事务。

## 验证与防止针对测试模组硬编码

```bash
./gradlew :jar-delta:test --offline --console=plain
./gradlew :jar-delta:corpusTest --offline --console=plain
```

普通测试使用合成 JAR 和独立的内容摘要 oracle，不依赖下载文件。覆盖摘要/操作/安全边界、重复应用、备份复用、恢复、损坏输入、限额、部分写出失败、候选损坏、原子提交失败和较大二进制条目。

`corpusTest` 是独立显式任务，没有 `base.jar` 会失败而非跳过。它把真实 JAR 当任意数据集，按名称排序选两个文件执行替换/删除，并新增实验条目，逐条对照预期后恢复验证。**不使用 SuperFastMode 的类名、mod ID、既有 Patcher 或兼容性输出作为引擎分支；结果不是可玩的兼容修复。** 普通测试和引擎规则不能为迎合此样本而放宽。

本地测试集存放在 `jar-delta/test-corpus/`，整个目录被 `.gitignore` 排除，不提交第三方 JAR、下载产物和实验报告。SuperFastMode 的 Steam Workshop ID 为 `1612867189`，游戏 App ID 为 `646570`。使用系统已有 SteamCMD 下载：

```bash
steamcmd +force_install_dir "$PWD/agent-tmp/jar-delta-steam" +login anonymous +workshop_download_item 646570 1612867189 validate +quit
```

下载成功后，将 SteamCMD 输出目录中的模组 JAR **复制**到 `jar-delta/test-corpus/base.jar`；保留下载文件，不对其原地修改。SteamCMD 的实际 Workshop 目录因安装布局可能不同，以成功日志为准。若服务端拒绝匿名下载，不将失败当作成功，也不改用非 SteamCMD 来源冒充。

可在忽略目录内保留 `source.txt`，记载来源、下载时间、摘要和文件大小。测试报告写到该目录的 `last-report.txt`。

报告包括各阶段纳秒耗时、增量包大小、完整结果大小、峰值额外**逻辑文件字节**：本轮新历史包（复用时为零）加候选结果大小。恢复为候选文件大小。不包含已存在输入、文件系统分配块、目录元数据、SteamCMD 下载空间或堆内存，不是物理磁盘监控。一次实验不能证明性能收益，特别不能把增量分发较小等同于完整 JAR 重建更快。
