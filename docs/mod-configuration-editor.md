# 启动器模组配置编辑

当前入口：**设置 → 开发者设置 → 实验功能 → 模组配置（实验性）**。点击后选择已安装模组，再进入配置编辑。

普通模组卡片的三点菜单不显示此入口。功能仍处于实验阶段，无法保证定位全部模组的配置。

## 支持范围

- 读取 `sts/home/.config/ModTheSpire/` 内已有的 `.properties` 文件（常见的 `SpireConfig` 格式）。
- 静态分析模组 JAR 内 `SpireConfig` 构造器 / `makeFilePath` 的调用参数，仅关联可证明为字符串常量（或空目录参数）的路径，不执行模组代码。支持编译器内联的常量、局部变量传递；无法解析的动态参数不猜测。
- 默认仅显示代码引用或用户已关联的配置；引用路径尚未生成文件时，显示缺失路径与生成方式。
- `SpireConfig` 允许自定义目录名，无法仅凭模组元数据准确判断归属。需要显式点击“手动关联配置文件”才展开其他文件，并确认关联；关联按原始模组 ID 记住。
- 手动列表按模组 ID、名称、JAR 文件名的名称相似度优先排序，支持按路径筛选。名称相似明确标为“未确认归属”，不视为自动定位依据。
- 代码引用也不代表独占所有权：共享库可能引用其他模组的配置；不解析任意文件 IO、自定义配置封装或动态拼接路径，不能承诺所有模组自动定位。
- JAR 分析限制为 10000 个类、单类 2 MiB、累计 64 MiB；超过限制或无法分析时标记为不完整，不将名称匹配升级为确定关联。
- 仅编辑文件内已保存的键值：`true` / `false` 显示为开关，整数和小数提供数字输入及基础格式校验，其他值为文本。
- 显示原始键名，不执行模组代码、不生成默认配置、不推断说明、枚举或滑块范围。未保存到文件的默认项需要先在游戏内保存一次才会出现。
- 暂不支持 JSON、YAML 或模组自定义格式。配置文件缺失、为空或格式无效时显示提示，不创建替代文件。

## 保存与保护

- 游戏运行、启动中或启动器忙碌时只读，修改在下次启动游戏时生效。
- 文件读写在 IO 线程执行。关闭或切换文件时，未保存的修改需确认放弃。
- 使用与 `Properties.load(InputStream)` 相同的 ISO-8859-1 / Unicode 转义语义；保留未修改记录的注释、换行和转义，仅重写修改项。
- 保存前检查文件内容是否仍与读取时一致；外部修改或删除后拒绝覆盖，需重新打开文件。
- 保存前将原始字节备份到同目录 `<原文件名>.bak`（每次保存保留上一次版本），再通过同目录临时文件替换原文件。
- 拒绝目录越界和符号链接。扫描深度最多 4 层子目录、最多 300 个配置文件；单文件最多 1 MiB、1000 个配置项。

## 实现与测试

- 解析、匹配和安全保存：`app/src/main/java/io/stamethyst/backend/mods/ModConfigRepository.kt`
- JAR 常量路径分析：`app/src/main/java/io/stamethyst/backend/mods/ModConfigLocator.kt`；覆盖范围与安全拒绝测试：`ModConfigLocatorTest`。
- 手动关联记录：`app/src/main/java/io/stamethyst/ui/main/ModConfigAssociationStore.kt`；可在打开文件后解除关联，解除操作不删除配置文件。
- 编辑界面：`app/src/main/java/io/stamethyst/ui/main/ModConfigDialog.kt`
- 开发者入口与模组选择：`app/src/main/java/io/stamethyst/ui/settings/sections/DeveloperModConfigDialog.kt`
- 单元测试：`ModConfigRepositoryTest`，覆盖 Java Properties 转义、中文、重复键、续行、注释保留、备份、文件冲突、路径越界与无效输入。
- 界面测试：`ModConfigDialogInstrumentedTest`，使用独立临时配置目录，不读写用户配置。

```bash
./gradlew :app:testDebugUnitTest \
  --tests io.stamethyst.backend.mods.ModConfigRepositoryTest \
  --tests io.stamethyst.backend.mods.ModConfigLocatorTest

./gradlew :app:compileDebugAndroidTestKotlin
```

以上仅执行本地单元测试和测试代码编译。已禁用会自动安装/卸载应用的 connected/托管设备测试；如需设备验收，必须明确指定并授权目标设备，使用已安装的测试构建。安装/卸载另需授权，不能作为测试准备隐式执行（见 `AGENTS.md`）。
