# 设置搜索原地编辑

设置搜索结果直接复用分类页的设置控件和保存回调，不再先跳转分类页面。

## 行为

- 开关、滑块、下载数量、选项及玩家名均可在搜索结果中修改；需要弹窗的选项仍使用原弹窗。
- 百度翻译凭据、MobileGlues、兼容性和 LLM 设置复用专用页面内容，搜索中不显示第二套页面导航栏，也不嵌套纵向滚动容器。
- 云存档模式、同步黑名单、自动导入默认项和 MTS 缓存启动优化可原地修改。
- 设置值仍由原 ViewModel/Repository 持久化，保留校验、危险操作确认和依赖禁用逻辑。
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

设备连接正常后执行：

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.stamethyst.ui.settings.SettingsSearchInstrumentedTest,io.stamethyst.ui.settings.SettingsDependencyLoadingInstrumentedTest
```

搜索界面测试使用替代保存回调和专用内容槽，避免修改真实凭据或触发文件操作。另需在设备上手测专用编辑器、长结果滚动、横屏/键盘、开发者和 MobileGlues 警告，以及搜索编辑后返回分类页的值同步。界面测试编译通过不等于设备验收通过。

本次本地回归：65 项 JVM 测试通过（设置和搜索历史 63 项、LLM 推理强度 2 项），Android 测试编译通过。`SettingsSearchInstrumentedTest` 共 31 项，依赖加载测试共 8 项；本地测试设备的 ADB shell/getprop 超时，尚未执行设备验收。日志：`agent-tmp/settings-search-final-validation.log`。
