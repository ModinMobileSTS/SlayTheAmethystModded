# archive-delta-core

Archive Patcher 的 shared/applier 派生代码，以及项目的独立完整文件差分封装。生成端在 `:archive-delta-generator`。核心不依赖生成端。

支持 API：`ArchivePackages.inspect` 验证包结构和 payload；`ArchivePackages.buildCandidate` 验证基础，构建并验证**新的候选文件**。不会覆盖现有文件，不保存历史、不安装文件。`inspect` 使用包所在目录的短生命周期临时目录，因此该目录须可写。

## 封装 v1

ZIP **恰好**包含：

- `manifest.json`：最多 16 KiB，严格 JSON schema，不接受未知字段；
- `payload.deflate`：整个 GFbFv1_0 原生差分的 raw DEFLATE。内部是 ENDSLEY/BSDIFF43，非 BSDIFF40。

两项输出为 STORED，避免再次压缩。Manifest：

```json
{
  "format": "stamethyst-archive-delta",
  "version": 1,
  "codec": "archive-patcher-file-by-file-v1",
  "compression": "deflate-raw",
  "base": {"size": 123, "sha256": "<64 lowercase hex>"},
  "target": {"size": 124, "sha256": "<64 lowercase hex>"},
  "payload": {"size": 80, "sha256": "<64 lowercase hex>"}
}
```

三个 SHA-256 均针对实际文件字节；payload 针对压缩后的字节。输出目标验证为**完整文件**，不是条目逻辑摘要。SHA-256 不是发布者认证，不提供签名信任。

此格式与 `jar-delta` 的实验条目 v1 格式不同，不自动检测或迁移；调用显式 `ArchiveDeltaAdapter`。Steam manifest/chunk 组装可以独立生成候选文件，不塞进 GFbFv1_0 或本 manifest。

`NativePatch`、`ArchiveIO` 为跨模块基础设施，调用方应优先使用高层封装。底层 apply 的 output/work 必须是调用方拥有的独占临时路径。上游 Java 入口不是有预算的安全 API。

限额、并发/崩溃边界、测试命令与许可说明见 `../archive-delta-generator/README.md` 和本模块 `UPSTREAM.md`。未来接入现有 history/commit 需单独实现；本轮不会偷偷将候选文件替换为正在使用的 JAR。
