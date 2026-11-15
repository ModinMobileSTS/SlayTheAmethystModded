# 模组页布局

在「设置 → 启动器 → 外观 → 模组页布局」切换，设置搜索也可直接操作。

- **经典布局（1.6.5 及更早版本）**：较大的圆角卡片、独立版本/大小信息行、两行简介、启用复选框、卡片操作按钮；文件夹标题显示启用复选框和计数。
- **紧凑布局（1.6.6）**：紧凑列表、标题下方版本/大小信息、启用开关、文件夹图标和状态信息、新版底部批量工具栏。此项为默认值。

仅改变页面展示和入口位置，继续共用当前的启用、安装、排序、拖动及批量处理逻辑。
详情、模组操作、导入补丁、更新确认、文件夹管理等二级窗口始终使用新版实现，不按布局分叉。
长按模组或文件夹进入批量操作时，底部工具栏及其按钮、操作菜单也统一使用新版，不随页面布局切换。
切换会立即生效并保存为 `mod_page_layout`，不重扫模组，也不改变启用状态、文件夹分配或排序。
缺失或未知配置回退为紧凑布局；「恢复启动器默认设置」也恢复紧凑布局。

验证入口：

```sh
./gradlew :app:testDebugUnitTest --tests 'io.stamethyst.config.ModPageLayoutTest' --tests 'io.stamethyst.ui.settings.core.ModPageLayoutWiringTest'
```

`ModPageLayoutInstrumentedTest` 覆盖卡片动态切换、共用新版详情及经典复选框回调；
`SettingsSearchInstrumentedTest.modLayoutCanBeChangedFromSearchWithoutNavigating` 覆盖设置搜索入口。
这两类 UI 测试需要 Android 测试依赖和设备，离线 JVM 测试不能替代视觉验收。
