# 模组硬编码文本翻译技术调研

本文讨论在 SlayTheAmethyst 启动器中，为没有使用 Slay the Spire 标准本地化资源的第三方模组提供翻译能力。目标语言以简体中文为首要目标，但设计上不应绑定单一语言或单一翻译服务。

## 1. 结论摘要

推荐采用三层方案：

1. **启动前离线扫描和翻译**：启动器读取已启用模组 JAR，提取 class 常量池中的用户可见字符串，生成翻译候选集。
2. **本地缓存字典**：翻译结果按模组文件指纹、源文本、源语言、目标语言、翻译器版本和术语表版本缓存。游戏运行时只查本地字典，不发网络请求。
3. **运行时窄范围替换**：内置一个独立的翻译 Mod，优先拦截已知 UI/文本入口；对无法定位来源的文本，再提供可选的渲染层替换或调试探针。

AI 适合用于“批量生成初稿”和“低频漏网文本兜底”，不适合放在游戏渲染线程中逐字符串实时调用。默认行为应是保留原文，翻译失败不能阻塞游戏启动。

## 2. 当前仓库可复用的基础设施

### 2.1 启动前准备点

MTS 启动前会在主进程执行：

- `LaunchPreparationService.prepare`
- `ModManager.buildLaunchModSnapshot`
- `OptionalModStorageCoordinator.prepareMtsModFileList`
- `MainProcessGameBodyPatchCoordinator.prepareBeforeLaunch`
- `MtsClasspathWarmupCoordinator.prepareForLaunch`

因此翻译预处理应插入 `LaunchPreparationService` 解析出 `LaunchModSnapshot` 之后，或者作为 `MainProcessLaunchPreparationCoordinator` 的独立步骤。此时仍在 Android 主进程，适合读写 JAR、调用网络、更新启动进度；不要把这些工作放到 `StsGameActivity` 或游戏 JVM 的渲染线程。

### 2.2 模组文件和启动顺序

当前运行时路径已经区分：

- `required_mods/`：启动器内置或必需 Mod。
- `mods_library/`：用户导入的可选 Mod。
- `.mts_mod_file_list`：实际交给 ModTheSpire 的 JAR 文件列表。
- `enabled_mods.txt` 和 `priority_mod_roots.txt`：用户选择和优先级。

翻译生成的产物不应覆盖用户原始 JAR。建议使用独立目录，例如：

```text
sts/
  translation/
    cache/
    artifacts/
    dictionaries/
    reports/
```

如果采用运行时 Mod，翻译 Mod 可以作为 `required_mods/AmethystTranslation.jar` 进入 `.mts_mod_file_list`；如果采用启动前字节码改写，改写后的 JAR 应放在 `translation/artifacts/`，并在生成启动列表时替换对应的原 JAR。

### 2.3 现有缓存机制

`MtsPatchCacheCoordinator` 已经使用 JAR 内容相关指纹判断 MTS 缓存是否需要重建。翻译缓存应复用相同思想，但不能只依赖文件大小和修改时间：用户可能原地重打包且保留时间戳。

建议翻译缓存键至少包含：

```text
modJarFingerprint
sourceLanguage
targetLanguage
translatorProvider
translatorModelOrApiVersion
promptOrRuleVersion
glossaryVersion
sourceTextHash
```

## 3. 硬编码文本的分类

“字符串硬编码”不是单一问题，必须先分类。

### A. class 常量直接作为 UI 文本

例如：

```java
new Label("Some text");
FontHelper.renderFont(..., "Some text", ...);
this.name = "Some name";
```

这是最适合自动化处理的类型。字符串通常存在于 class 常量池，可被扫描；如果能在字节码层将 `ldc "原文"` 改成 `TranslationRuntime.text("原文")`，也可以做到运行时查表。

风险：同一个字符串可能同时用于 UI、日志、存档键、比较条件或资源路径，不能无差别翻译所有常量。

### B. 字符串通过拼接、格式化或变量产生

例如：

```java
"Damage: " + amount
String.format("%s has %d HP", name, hp)
```

可以在模板级翻译，但需要保留占位符、颜色标记和换行。不能简单翻译运行时已经拼好的整句，否则上下文不足且每次渲染都会产生性能开销。

### C. 通过资源文件或 BaseMod 标准 API 注册

例如 `CardStrings`、`RelicStrings`、`PowerStrings`、`UIStrings`、事件文本等。这类文本不应通过通用硬编码扫描处理，应优先生成或加载标准本地化资源，或者在注册入口替换数据。语义、格式和兼容性最好。

### D. 贴图内文字

JAR 里的 PNG、JPG、纹理图集或 Spine/字体资源中的文字无法通过字符串翻译解决。需要 OCR、图片重绘、贴图替换或提供独立的文本覆盖层。该路线复杂度和验收成本最高，应单独立项。

### E. 代码逻辑依赖的字符串

例如状态 ID、卡牌 ID、资源路径、命令名、JSON 键、存档字段、枚举名。它们不能翻译。任何自动化方案都必须有“用户可见文本判定”和排除规则。

## 4. 候选技术路线对比

### 4.1 仅使用运行时文本替换 Mod

实现方式：内置 Mod 对已知渲染/构造入口打补丁，例如 BaseMod 的卡牌、遗物、能力、UI 注册和 `FontHelper` 调用。

优点：

- 不修改第三方原始 JAR。
- 可通过 ModTheSpire 补丁按类、方法、模组单独控制。
- 可以获取调用方类名、对象类型和当前 UI 上下文，翻译质量更好。

缺点：

- 不可能覆盖所有第三方自定义 UI。
- 对直接调用 LibGDX `BitmapFont.draw`、自定义缓存或贴图文本的模组覆盖不足。
- 过度 patch 全局 `FontHelper` 或 GDX 绘制方法会影响原版、日志和性能。

结论：作为运行时执行层保留，但不能单独解决问题。

### 4.2 启动前直接改写 class 常量

实现方式：启动器读取 JAR，使用 ASM/Javassist 修改字节码，把符合规则的字符串常量替换为翻译后的字符串，输出翻译副本。

优点：

- 游戏运行时不需要查表，性能最好。
- 翻译结果可以在启动前一次性审查、导出和回滚。
- 与现有启动前准备、JAR 构建和 MTS 缓存机制天然契合。

缺点：

- 常量池没有调用上下文，误翻译风险高。
- 直接替换常量可能同时改变逻辑比较、ID、资源路径和存档键。
- class 改写可能影响栈映射、签名、混淆代码和其他 MTS patch 的匹配。
- 每个模组版本都可能导致规则失效，需要按版本回归。

结论：适合做“明确白名单类/方法”的预处理，不适合全 JAR 全字符串替换。

### 4.3 启动前扫描，运行时字典替换

实现方式：启动器扫描 class 和标准资源，生成 `source -> translation` 字典；运行时 Mod 在受控入口调用字典。

优点：

- 扫描和网络请求都在启动前完成。
- 原始 JAR 不变，规则和翻译可独立更新。
- 同一源文本可按调用方、模组和上下文拥有不同翻译。
- 便于用户编辑、导入社区翻译和回退原文。

缺点：

- 仍需要找到文本进入 UI 的入口。
- 对完全自绘文本和图片文本无能为力。
- 字典查找需要避免出现在每帧绘制的热路径中，或使用无锁内存缓存。

结论：推荐作为主架构。

### 4.4 Java Agent / Instrumentation 运行时改写

仓库的 `game-probe` 已经有 `javaagent`、ASM transformer、游戏 ClassLoader 捕获和 retransformation 能力，技术上可以修改已加载类。

优点：

- 可以在类加载或重转换时处理第三方类，不需要重打包 JAR。
- 适合调试、发现漏网文本和验证 patch 点。

缺点：

- 当前 agent 主要是诊断用途，不是稳定的发行时依赖。
- Android 上 Instrumentation、ClassLoader 和类重转换存在额外兼容性风险。
- 与 ModTheSpire 已经进行过的字节码 patch 叠加时，顺序和幂等性很敏感。
- agent 自身异常可能影响类加载；翻译功能不应依赖它才能正常启动。

结论：用于开发期探针和生成候选清单，不作为第一版用户功能的核心路径。

### 4.5 GDX 最终绘制层替换

实现方式：拦截 `BitmapFont.draw`、`GlyphLayout.setText` 或项目已有的字体桥接，使用当前字典替换绘制文本。

优点：覆盖面广，能抓到大量非标准 UI。

缺点：

- 字符串已经离开业务上下文，容易把调试文本、资源 ID、数字格式误翻译。
- 每个绘制调用都查字典，可能造成明显 CPU、分配和布局开销。
- 长度变化会破坏布局，原来一次 draw 的换行和截断规则可能不再适用。
- 文本绘制过程中再触发 AI 请求不可接受。

结论：只适合做可选的高级兜底，并且必须是本地字典、短路缓存、长度/格式保护和调试开关模式。

### 4.6 OCR/图像翻译

适用于贴图内文字，但应视为独立的图像本地化系统：识别、翻译、重新绘制、纹理替换、字体适配、图集坐标和版权处理都需要单独解决。

结论：不纳入首期 MVP。

### 4.7 样本 Mod 验证

对 `agent-tmp/translate` 下 4 个 JAR 做了资源清单、源码和 class 常量池扫描，结论是“资源生成 + 少量白名单 patch”比“全局字符串替换”更可靠。

| JAR | 资源形态 | 硬编码证据 | 推荐路线 | 剩余风险 |
| --- | --- | --- | --- | --- |
| `tblostelites.jar` | 标准资源最完整。存在 `tblostelites/localization/eng/*` 和 `tblostmonsters/localization/eng/*` 两套资源，覆盖 `CardStrings`、`CharacterStrings`、`EventStrings`、`Keywords`、`OrbStrings`、`PotionStrings`、`PowerStrings`、`RelicStrings`、`UIStrings`。 | class 常量池里只有少量自然语言候选，主要是日志、资源路径错误信息；`SummonGremlinNobAction` 的 `Time Dilation` 是 `ModHelper.isModEnabled` 的模组 ID 判断，不应翻译。`getLangString()` 直接使用 `Settings.language.name().toLowerCase()`，`receiveEditStrings()` 先加载 `eng`，再尝试当前语言目录。 | 第一优先级样本。生成并注入 `zhs` 目录即可验证标准资源路线，两个包根都要生成；繁中同理输出 `zht`。 | 仓库现有兼容补丁已经引用 `Settings.GameLanguage.ZHS/ZHT`，因此目录名应使用 `zhs`/`zht`，但仍要以运行时 `Settings.language.name().toLowerCase()` 为最终来源。 |
| `sts-relics-mod-1.0.0.jar` | 没有外部 localization JSON；`RelicsMod.receiveEditStrings()` 内联了一整段 `RelicStrings` JSON，包含 5 个遗物的 `NAME`、`FLAVOR`、`DESCRIPTIONS`。 | 每个遗物类的构造器把 `BagOfGreed` 等 ID 传给 `CustomRelic`，不能翻译；同时每个 `getUpdatedDescription()` 又直接返回英文描述字面量，标准 `RelicStrings` 不足以覆盖运行时描述。 | 第二优先级样本。启动前扫描可识别内联 JSON 和 5 个 `getUpdatedDescription()` 返回值；运行时用独立 patch 覆盖 `getUpdatedDescription()` 返回文本，或对白名单方法做字节码替换。 | 适合验证“同一遗物既有注册字符串又有方法返回字符串”的双路径问题。 |
| `NecroMod.jar` | 有 `localization/NecroMod-CardStrings.json` 和 `localization/NecroMod-RelicStrings.json`；卡牌资源约 50 个 `NAME`/`DESCRIPTION`，遗物资源约 2 个 `NAME`。 | JAR 内含源码。源码扫描显示约 56 个静态 `NAME`、29 个静态 `DESCRIPTION*`、5 个 `EXTENDED_DESCRIPTION[]`、44 处 `getCardStrings()`、7 处 `ThoughtBubble`、2 处 `gridSelectScreen.open()`。例如 `Life_Drain` 在使用后重写 `rawDescription`，`Bone_Prison` 直接显示 `"The enemy is not attacking!"`，`DiscardPileToHandAction` 直接传 `"Select cards to add to your hand."`。`Summon_Lich.use()` 还用 `this.name.equals("Summon Lich")` 做逻辑判断，说明不能盲目翻译所有名字常量。 | 第三优先级样本。先生成标准资源，再对硬编码卡牌、气泡、选牌标题建立按类拆分的白名单 patch；涉及 `this.name` 逻辑比较的类应先改为 ID/upgraded 判断或跳过自动翻译。 | 图片资源很多，贴图内文字不在文本 MVP；源码和 class 可能不完全一致，最终以 class 调用点验证为准。 |
| `TheDisciple.jar` | 大型旧模组。含 `localization/eng/chronoRelics.json`、`chronoCards.json`、`chronoEvents.json`、`chronoPotions.json`、`chronoUI.json`、`chronoKeywords.json`、`chronoOrbs.json`、`chronoPowers.json`，以及一套根目录 `localization/chrono*.json`。资源大致覆盖 129 张卡、29 个遗物、25 个 orb、18 个 power、事件、UI 和关键词。 | `ChronoMod.receiveEditStrings()` 和 `receiveEditKeywords()` 的语言 switch 没有非默认分支，实际总是 `lang = "eng"`，所以单纯新增 `zhs` 目录大概率不会生效。`ChronoMod` 还硬编码多条 `TalkAction` 战斗对白，例如 `"Whale brought you back did they?"`、`"Your TIME... is up."`。另有少量 power/气泡文本硬编码，例如 `StrengthDamagePower.updateDescription()` 直接设置英文描述，`EchonomicsPower` 直接创建 `ThoughtBubble`。 | 第四优先级样本。先用宽容 JSON 解析生成资源翻译；再决定是生成翻译后的 artifact 覆盖 `localization/eng`，还是用专用 patch 改语言选择；最后补 `TalkAction`、`ThoughtBubble`、个别 power 描述。 | 资源 JSON 不是严格 JSON：`chronoCards.json` 有尾随逗号，`chronoKeywords.json` 有注释；扫描器不能只用严格 JSON parser。class 常量池还包含 SQL、日志、原版遗物名等大量非翻译字符串，必须按调用点过滤。 |

样本验证建议按以下顺序推进：

1. `tblostelites.jar`：证明资源目录生成和加载链路。
2. `sts-relics-mod-1.0.0.jar`：证明内联 JSON 与 `getUpdatedDescription()` patch 的最小闭环。
3. `NecroMod.jar`：证明混合资源、硬编码卡牌、气泡和选牌标题的白名单 patch。
4. `TheDisciple.jar`：证明旧模组、非严格 JSON、语言选择 patch 和硬编码对白的组合处理。

## 5. 推荐架构

### 5.1 模块划分

建议新增两个模块或两个明确的职责边界：

```text
app/
  TranslationPreparationService.kt
  TranslationCacheStore.kt
  TranslationProvider.kt
  ModTextScanner.kt

mods/amethyst-translation/
  TranslationRuntime.java
  TranslationRuntimePatches.java
  TranslationDictionary.java
  ModTheSpire.json
```

如果第一阶段不想新增 Gradle 子项目，也可以先把运行时 Mod 放入 `mods/amethyst-runtime-compat`，但必须保持独立包名和独立 patch 类。不要把所有翻译 patch 直接塞进现有兼容补丁类，否则后续无法独立禁用、回滚和定位问题。

### 5.2 翻译流程

```text
启动器解析启用模组
  -> 计算模组指纹
  -> 扫描标准本地化资源和 class 字符串
  -> 过滤非用户文本
  -> 命中本地/社区缓存的直接采用
  -> 未命中项批量提交翻译服务
  -> 校验占位符、标签、换行和长度
  -> 写入原子字典和报告
  -> 写入运行时 Mod 配置路径
  -> 启动游戏
```

翻译服务不可用、API Key 未配置、响应解析失败或质量校验失败时，应该只保留原文并继续启动。不能因为翻译失败阻断游戏。

### 5.3 字典键

不建议只使用原文作为全局 key。最低应支持以下层级：

```text
targetModId
sourceClassName
sourceMethodName or textContext
sourceText
sourceLanguage
targetLanguage
```

查找顺序可以是：

1. `modId + class + method + sourceText`
2. `modId + sourceText`
3. `sourceText`

如果同一文本在不同位置语义不同，必须使用更具体的 key；否则宁可保留原文。

### 5.4 运行时入口优先级

建议按以下顺序实现：

1. 标准对象数据：`CardStrings`、`RelicStrings`、`PowerStrings`、`UIStrings`、事件/角色字符串。
2. BaseMod 注册和常见对象构造入口。
3. 已知模组的专用 patch，按模组独立文件拆分。
4. `FontHelper` 等业务字体入口的窄范围 patch。
5. 最终 GDX 绘制层兜底，默认关闭或仅在调试模式启用。

## 6. AI 翻译的使用边界

### 6.1 AI 适合做什么

- 从 class 常量和资源中批量生成初始翻译。
- 根据模组名、类名、卡牌/遗物/事件上下文提高翻译质量。
- 生成术语表候选，例如卡牌关键词、状态名、专有名词。
- 对用户反馈的未翻译文本进行离线补翻译。

### 6.2 AI 不适合做什么

- 每次 `render` 或 `update` 中联网翻译。
- 在游戏启动的关键线程上等待网络响应。
- 把完整 JAR 或包含用户存档/日志的数据直接上传。
- 在没有占位符和格式校验的情况下直接覆盖原文。

### 6.3 请求策略

仓库已有 `BaiduAiTextTranslationClient`，可以复用其网络、凭据和错误处理模式，但翻译模组应抽象成 `TranslationProvider`，不要把运行时组件绑定到百度 API。

建议：

- 按模组、按语义类型批量请求，而不是逐条请求。
- 单批限制字符数和条目数，失败时二分重试。
- 使用固定提示词和结构化 JSON 响应。
- 保留原文、翻译、模型/接口版本、时间和质量检查结果。
- API Key 只存在 Android 加密存储；不要传进游戏 JVM 的系统属性，也不要写入日志。
- 提供“仅本地缓存”“使用配置的在线服务”“完全关闭”三种模式。

### 6.4 隐私和版权

默认上传内容应限制为候选文本和必要上下文，不上传完整模组 JAR。应向用户明确：第三方模组文本会发送给所选翻译服务，服务商的数据保留和版权条款由用户自行确认。

## 7. 过滤和质量校验

### 7.1 初始排除规则

优先排除：

- 空字符串、单个字符、纯数字、纯标点。
- 文件路径、URL、类名、包名、资源 ID。
- JSON/XML 键、存档键、命令名、枚举名。
- 含有明显格式占位符但无法解析的字符串。
- 仅用于日志、异常、调试输出的调用点。
- 已经包含目标语言文字且没有源语言内容的字符串。

初始允许规则可以只接受：

- 含空格的自然语言句子。
- 已知 UI 方法参数。
- 已知卡牌/遗物/能力/事件对象的字段赋值。
- 用户手工标记的类、方法或字符串。

### 7.2 结果校验

翻译写入前检查：

- `%s`、`%d`、`{0}`、`#` 等占位符数量和顺序。
- `[E]`、`NL`、颜色标签、富文本标记和换行数量。
- JSON/XML/正则转义没有被破坏。
- 源文本和翻译都不为空。
- 翻译不是服务错误消息或 Markdown 包装。
- 长度异常时标记人工审核，而不是自动覆盖。

## 8. 运行时性能和稳定性

翻译查找必须不进入无界分配或网络路径。建议：

- 启动时加载不可变字典。
- 使用 `HashMap` 或紧凑的预构建索引。
- 对最终绘制层使用“原文对象/字符串 identity + context”短期缓存。
- 只在文本改变时重新计算 `GlyphLayout`，不要每帧翻译和重排。
- 统计命中、未命中、替换次数和平均耗时，但默认不要逐条写日志。
- 捕获翻译 Mod 内部异常并回退原文，不能让翻译异常改变游戏逻辑。

需要特别避免：

- 在 `FontHelper`/GDX 全局入口中调用 Android API。
- 在游戏 JVM 中访问 Android Context。
- 在 render/update 线程进行文件读写或 HTTP。
- 修改原文用于逻辑比较后再把翻译文本传回业务对象。

## 9. 建议实施阶段

### Phase 0：探针和数据集

目标：先知道哪些文本真的漏翻译。

- 在 `game-probe` 增加只读字符串观测模式，记录调用方类、方法和文本样本。
- 使用现有 ClassLoader 捕获和 ASM transformer，仅对用户指定模组启用。
- 不改变返回值，不联网，不影响游戏逻辑。
- 生成按模组聚合的候选报告，验证过滤规则和热路径数量。

验收：跑 3 到 5 个代表性模组，报告中能区分卡牌文本、UI 文本、日志、资源 ID 和贴图文本。

### Phase 1：本地字典和标准入口

目标：先解决质量最高、风险最低的文本。

- 新增翻译字典格式和缓存存储。
- 支持标准本地化资源和 BaseMod 数据入口。
- 内置少量人工翻译作为回归样本。
- 增加启动器开关和“仅本地缓存”模式。

验收：无网络时可正常启动；字典命中时标准卡牌/遗物/能力/UI 文本变为目标语言；关闭功能时行为完全不变。

### Phase 2：AI 批量生成

目标：减少人工录入成本。

- 复用现有凭据页面和 HTTP 客户端模式，但抽象 Provider。
- 启动前异步批量翻译未命中候选。
- 添加术语表、占位符校验、人工审核报告和缓存失效规则。
- AI 失败时继续启动，结果仅在校验通过后写入字典。

验收：断网、限流、错误响应、半批次失败都不会阻塞启动或损坏缓存。

### Phase 3：硬编码 UI 运行时 patch

目标：覆盖没有使用标准本地化 API 的模组。

- 为已知模组建立独立 patch 类。
- 先 patch 构造器/字段赋值，再考虑业务字体入口。
- 每个模组 patch 都有开关、版本约束和原文回退。
- 翻译 Mod 与现有 `AmethystRuntimeCompat` 保持独立可禁用。

验收：至少覆盖一个卡牌型模组、一个自定义 UI 模组和一个事件型模组；升级模组版本后旧规则不会静默误翻译。

### Phase 4：高级兜底

仅在数据证明有价值后考虑：

- GDX 最终绘制层本地字典替换。
- 用户点击文本后提交翻译请求。
- OCR/贴图翻译。
- 社区共享翻译包和签名更新。

## 10. 失败模式和回滚

每次翻译产物都必须可以独立删除或失效：

- 翻译字典损坏：删除当前字典，使用原文。
- 翻译 Mod 加载失败：从启动列表移除翻译 Mod，继续原始模组启动。
- 字节码改写失败：保留原始 JAR，不覆盖。
- 规则与模组版本不匹配：按指纹失效，不复用旧产物。
- AI 返回格式错误：不写入缓存，只记录摘要。
- 运行时替换抛异常：返回原文并限制重复日志。

缓存和翻译产物应纳入现有 MTS patch cache marker 的输入，或者在启动列表中使用翻译产物的独立指纹。否则可能出现“翻译文件更新了，但 MTS 仍启动旧缓存字节码”的问题。

## 11. 推荐的第一批代码任务

1. 新增 `ModTextScanner`，读取启用模组 JAR，输出带来源位置的候选文本，不修改任何文件。
2. 新增过滤器和占位符校验器，并用固定的合成 JAR 编写单元测试。
3. 新增 `TranslationCacheStore`，采用原子写入、版本化 schema 和按模组指纹分目录存储。
4. 将翻译准备步骤接入 `LaunchPreparationService`，默认关闭且不改变现有进度流程。
5. 新增独立 `amethyst-translation` Mod，仅实现本地字典读取和一个标准 UI 入口的替换。
6. 用 `game-probe` 生成真实模组样本，确认是否需要扩大运行时 patch 范围。

## 12. 最终建议

不要把“翻译”实现成一个全局 `String` 替换器，也不要把 AI 调用放进游戏运行时。第一版应把启动器定位为翻译构建器：负责扫描、批量翻译、校验、缓存和生成运行时配置；内置 Mod 负责少量、明确、可回退的文本入口替换。

这样既能处理硬编码字符串，又能保留原始模组、避免启动期间网络不稳定影响游戏，并为后续人工修订、社区翻译包和更强的 AI 上下文翻译留下清晰边界。
