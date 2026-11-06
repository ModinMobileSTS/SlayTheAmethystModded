# SlingBreak 旧存档迁移

新版 `GodotMinigameActivity` 在创建引擎前检测旧存档，独立小游戏入口和主游戏启动加载小游戏共用同一流程。主游戏加载仍继续运行，真实进度暂存，迁移或跳过后再交给小游戏显示。

## 用户行为

- 没有旧存档时不弹窗，也不记录“已迁移”，以后仍可检测。
- 找到存档时询问是否迁移；“暂不迁移”或取消只跳过本次，下次启动仍询问。
- 多个来源的 JSON 完全相同时去重；不同存档按来源和关卡列出，由用户选择，不自动合并钱包或进度。
- 新版已有进度时明确提示替换，并先备份当前新版进度。
- 校验、备份、原子写入和回读确认全部成功后，持久化完成标记。以后启动不再读取旧浏览器存档或显示迁移弹窗。
- 失败不记录完成；保留旧版浏览器数据和已生成的备份，用户可重试或先继续游戏。

## 存储与实现边界

- 旧 WebView 的 `slingbreak-save-v1` 通过 `LegacySlingBreakSaveService` 在原所属进程中读取：启动器默认进程对应 `app_webview`，`:game` 对应 `app_webview_game`。只加载原 `file:///android_asset/slingbreak/index.html` origin 下的空白 HTML，不运行旧游戏、不删除或修改 localStorage，不直接复制或解析正在使用的 LevelDB。
- 旧 Gecko 兼容模式通过 `LegacyGeckoSlingBreakSave` 只读查询 `files/mozilla/*/storage/default/http+++127.0.0.1+<port>/ls/data.sqlite` 的同名键，支持 Mozilla LSValue UTF-8 / UTF-16LE 和有界 raw Snappy 解码。不加载或下载 Gecko 引擎，不迁移主题或其他站点数据。
- 单份 Web JSON 最大 256 Ki 个 UTF-16 code units。损坏、超限或读取失败的数据不会删除；读取错误写入 `SlingBreakMigration` 日志，下次可重新检测。
- `LegacySlingBreakProgress` 将旧状态校验并包装成 `slingbreak-progress` version 1，保留关卡、金币、升级、战场伤害、障碍、技能选择、成就及音量。缺失的新升级字段补零，最早累积多级技能转为新版的单级技能。新版自身的技能等级门槛和槽位规则仍生效。
- 适配器支持旧 Web v1 状态，不猜测转换原生专属的 `nativeBoss` 或 `board.variant` 规则数据；遇到不支持的格式会明确迁移失败，不写完成标记，不静默丢弃这些字段。

目标目录是应用私有 `files/godot-prototype-user/`：

```text
slingbreak-progress.json                 # Godot user:// 进度
legacy-web-migration-v1.complete          # 内容为 v1 + 换行的成功标记
legacy-migration-backup-<随机后缀>/
  web-save.json                          # 本次选择的原始 Web JSON
  native-progress.json                   # 替换前的新版进度（若存在）
```

迁移只在引擎启动前执行，避免与 Godot 自动保存竞争。写入采用同目录临时文件、文件同步和原子重命名；旧浏览器存档始终保留。清除应用数据会同时清除进度、备份和完成标记。

## 验证

```sh
./gradlew :app:testDebugUnitTest --tests 'io.stamethyst.godot.Legacy*' :app:compileDebugJavaWithJavac
```

单元测试覆盖进度适配、战场和成就保留、损坏/不支持数据拒绝、原生存档备份、写入失败不置完成标记、完成标记跨实例和重复调用，以及 Gecko 编码、压缩与解压边界。

真机回归应分别准备默认 WebView、启动加载 WebView 和 Gecko 兼容模式存档，检查无存档不弹窗、多份选择、取消后重进再询问、成功重进不再询问、新版已有进度时备份、主游戏进度继续到 ready。不要为测试清除用户数据或卸载应用。
