# 设置搜索原地编辑

设置搜索结果直接复用分类页的设置控件和保存回调，不再先跳转分类页面。

## 行为

- 开关、滑块、下载数量、选项及玩家名均可在搜索结果中修改；需要弹窗的选项仍使用原弹窗。
- 百度翻译凭据、MobileGlues、兼容性和 LLM 设置复用专用页面内容，搜索中不显示第二套页面导航栏，也不嵌套纵向滚动容器。
- 云存档模式、同步黑名单、自动导入默认项和 MTS 缓存启动优化可原地修改。
- 设置值仍由原 ViewModel/Repository 持久化，保留校验、危险操作确认和依赖禁用逻辑。
- LLM 搜索编辑器与独立设置页交替使用时，只保存当前编辑器实际修改的字段；旧页面的超时等修改不能覆盖另一页面已保存的新地址、密钥或模型。退出搜索或导航返回也遵循同一规则。
- 配置或依赖未加载时保留原控件并禁用，不使用“加载中”占位。
- 开发者结果共享五秒风险确认；MobileGlues 的额外警告在搜索中首次操作时出现，而非输入搜索词时自动弹出。
- 匹配分类名或描述时展示该分类设置，不额外插入重复的分类跳转项。
- 教程、首次运行向导、反馈、登录和原生库市场属于独立功能流程，仍打开相应页面；导入/导出等操作则直接执行原操作或打开文件选择器。

## 代码入口

- `SettingsHomeAndScaffold.kt`：匹配、分组和开发者风险确认作用域。
- `SettingsSearchResultItem.kt`：按搜索项选择原设置控件。
- `SettingsSearchActions.kt`：绑定实际保存回调及专用设置内容。
- `SettingsDetailLayout.kt`：区分独立页面与搜索嵌入布局，嵌入时由搜索页负责滚动。

以上文件均位于 `app/src/main/java/io/stamethyst/ui/settings/` 下的相应子目录。

## 验证

本地编译与回归：

```sh
./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest \
  --tests 'io.stamethyst.ui.settings.*' \
  --tests 'io.stamethyst.ui.SearchHistoryStoreTest' \
  --tests 'io.stamethyst.backend.llm.LlmReasoningEffortTest'
```

已禁用会自动安装/卸载应用的 connected/托管设备测试。上方命令只编译 Android 测试并运行本地 JVM 测试；设备验收必须明确指定并授权目标设备，使用已安装的测试构建，安装/卸载另需授权（见 `AGENTS.md`）。

搜索界面测试使用替代保存回调和专用内容槽，避免修改真实凭据或触发文件操作。另需在设备上手测专用编辑器、长结果滚动、横屏/键盘、开发者和 MobileGlues 警告，以及搜索编辑后返回分类页的值同步。界面测试编译通过不等于设备验收通过。

LLM 多入口回归应覆盖：先显示搜索内嵌编辑器，再在独立页面保存连接信息，回到搜索修改请求超时，确认两次修改均保留；还应覆盖快速连续编辑和退出时保存，防止延迟写入重新提交陈旧完整快照。

保存失败的回归需区分 SharedPreferences 内存值与磁盘值：`commit()` 失败时内存可能已经更新，重试不能仅凭内存值相等就报告成功；实际落盘成功后才清除失败提示。失败后另一入口更新过的字段仍以最新编辑为准。

初版搜索原地编辑的本地回归：65 项 JVM 测试通过（设置和搜索历史 63 项、LLM 推理强度 2 项），Android 测试编译通过。`SettingsSearchInstrumentedTest` 共 31 项，依赖加载测试共 8 项；当时本地测试设备的 ADB shell/getprop 超时，未执行设备验收。日志：`agent-tmp/settings-search-final-validation.log`。该记录不代表后续多入口编辑修复的验证结果。
