# AI Agent：局部编辑与准备流程

本阶段对应效率审查 1、7。目标是减少小改动的整文件输出及重复准备/预检调用；未测量真实模型提速比例。

## 局部文本编辑

`edit_agent_workspace_file` 是独占执行的 `WORKSPACE_WRITE` 工具，复用 write/delete 的路径解析策略，仅编辑 `patch_source/patch-*/` 下已有文件。拒绝路径越界、指向 source/ 或工作区外的符号链接，以及被符号链接重定向的 patch_source 根目录。创建文件继续使用 `write_agent_workspace_file`。

参数：

- `path`：工作区相对路径。
- `old_text`：必须非空、按字面精确匹配且只出现一次（重叠匹配也算多匹配）。
- `new_text`：替换文本；空字符串表示删除这一段。
- `expected_sha256`：可选的完整文件版本哈希，推荐传入最近 read/edit 返回的 `sha256`；冲突不写入。

文本 read 的 `utf8` 和 `utf8_chars` 模式均返回完整原始文件的 SHA-256，即使只读取一页。不对带行号的显示内容计算哈希。按行读取在同一输入流上增量计算哈希并严格解码 UTF-8，只保留请求窗口和受限行预览；仍扫描剩余内容以获得完整哈希并检查非法文本，避免为小窗口保留整文件字节数组及字符串。`utf8_chars` 在 16 MiB 上限内使用字节快照。普通读取的行号不是文件内容；需要精确多行匹配或保留 CRLF 时，使用 `encoding=utf8_chars`。

编辑不会规范化换行、删除 BOM 或自动补末尾换行。无效 UTF-8、孤立 Unicode 代理项、NUL 文本、空匹配、缺失/多匹配、无变化替换及超过 16 MiB 的输入/输出均被拒绝。失败结果含错误码和下一步提示；成功仅返回路径、字节数、替换次数和新哈希，不回显文件。

写入使用同目录临时文件和原子替换，提交前再次核对路径和原始字节，避免中途写坏文件。工具调度仍使用现有独占屏障；哈希是乐观版本校验，不是对任意外部进程的文件系统事务锁。

## 证据与验证流程

- 解释母模组时直接 inspect/decompile 单类；`decompile_agent_class` 自己准备快照。`decompile_agent_mod_source` 保留为显式准备/查看快照元数据工具。
- 当前已安装版本的 skill、API、源码及 target 证据可以复用。只有证据缺失、不完整、过期或相关依赖/目标变化时才重新查询；普通源码编辑不会使已确认的目标签名失效。
- 小改动优先局部编辑，保留正确 skeleton，避免重新生成完整文件。
- 默认流程为编辑 → compile → package/update。package/update 仍强制执行最终 preflight，并返回其结果和警告。`validate_agent_patch_mod` 保留给独立诊断，不再是打包前的强制重复调用。
- 预检失败先修复；缺少显式参数签名的警告要求修正 skeleton、重编译并更新。打包后仍必须 smoke test，启用权限要求不变。

## 离线回归

`AgentWorkspaceEditToolTest` 覆盖精确替换、非目标字节保真、分页哈希接续、冲突不写入、路径/符号链接边界、非法文本、文件大小和权限/独占策略。`AiAgentEditingWorkflowTest` 通过真实 executor 工具列表验证注册与本地化说明，并检查最终系统提示的流程约束。`AgentPatchPackagingWorkflowTest` 通过本地合成 JAR 验证无需独立 validate 也会返回预检警告，非法字节码仍阻止 package/update，失败更新保留原 JAR。既有 workspace read、调度和网关测试继续覆盖相关行为。
