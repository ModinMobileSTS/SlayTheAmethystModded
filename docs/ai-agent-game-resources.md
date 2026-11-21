# Agent 读取游戏本体资源

现有 `list_agent_mod_entries` 和 `read_agent_mod_resource` 支持可选参数 `source`：

- 省略或传 `"mod"`：默认读取父模组；通过 `mod_id` 读取已安装模组，或通过 `patch_mod_id` 读取本机已打包补丁。两个 ID 不能一起使用。
- 传 `"game"`：读取导入的 `desktop-1.0.jar`，省略 `mod_id` 和 `patch_mod_id`。

先发现实际资源路径，再读取，不要猜测卡图文件名：

```json
{"source":"game","prefix":"images/","entry_type":"resources","limit":"100"}
```

将返回的路径用于 `read_agent_mod_resource`：

```json
{"source":"game","entry":"<上一步返回的图片路径>"}
```

图片在默认编码且 `offset=0` 时作为可视附件返回；显式指定 `encoding="base64"` 则返回可复制的二进制文本。文本和二进制资源仍可使用 `offset` / `limit` 分页；列表支持前缀过滤、类型过滤及分页。成功结果含 `source` 和 `archive`，并按来源返回 `mod_id` 或 `patch_mod_id`；游戏来源不返回 ID。

游戏来源固定映射到 `RuntimePaths.importedStsJar(context)`，直接读取含资源的游戏 JAR，不能使用可能仅含 class 的编译缓存。模型不能指定任意磁盘路径；这些工具不会修改归档、补丁目标或编译 classpath，也不会把游戏本体加入已安装模组目录。`source="game"` 仅用于资源；查看游戏类仍使用 `decompile_agent_api_class`。

错误：`invalid_source` 表示未知来源，`mod_id_not_allowed_for_game_source` 或 `patch_mod_id_not_allowed_for_game_source` 表示参数冲突，`game_jar_not_found` 表示游戏 JAR 缺失，`resource_not_found` 表示资源条目不存在或路径非法。
