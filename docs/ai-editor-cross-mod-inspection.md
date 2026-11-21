# AI 编辑器跨模组代码查看

编辑 A 模组时，可以要求 AI 阅读其他已安装模组或本机已打包补丁的代码，用于理解行为或分析兼容性。补丁可以属于当前模组，也可以属于其他模组；不必启用，也不需要源码工作区。已安装的社区补丁同样适用。

AI 先调用 `list_installed_mods`，再从 `mods` 取 `mod_id`，或从 `patches` 取 `patch_mod_id`。这两个参数不能一起使用。`list_agent_patch_mods` 也会返回当前模组的 `patch_mod_id`；不要把它和工作区的 `patch_id` 混用。

- `list_agent_mod_entries`：列出类或资源；使用 `entry_type="classes"` 发现类。
- `inspect_agent_class`：查看类的公开/受保护 API。
- `decompile_agent_class`：按需反编译一个类，查看实现。
- `read_agent_mod_resource`：读取 JSON、文本等资源。

两个 ID 都不传时，仍查看当前编辑的模组。普通模组可使用列表中的 `mod_id` 或唯一的 manifest ID；存在同名 manifest ID 时应使用列表中的明确 `mod_id`。查找 ID 时不区分大小写。

其他模组和补丁的反编译代码直接返回，不写入当前工作区的 `source/`，`materialize` 始终为 false。读取不会修改 JAR、切换工作区、启用补丁或扩展编译类路径。`search_agent_api` 仍只搜索原有编译类路径；其他模组和补丁的类应通过文件列表发现。

只读取已安装模组或本机已打包补丁的 JAR，不接受文件路径。未知、未安装、文件缺失或歧义 ID 会返回错误，不会回退到当前模组。

游戏本体、BaseMod、StSLib、ModTheSpire 等编译类路径上的类不属于“其他已安装模组”，应使用 `decompile_agent_api_class` 按需读取；该工具不接受 `mod_id`，也不会把源码写入当前工作区。

读取游戏本体的卡图、JSON 等资源时，现有 `list_agent_mod_entries` 和 `read_agent_mod_resource` 可指定 `source="game"`，并省略两个 ID。先列出精确资源路径，再读取；这不会将本体加入模组目录。详见 [游戏本体资源读取](ai-agent-game-resources.md)。
