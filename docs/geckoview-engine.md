# GeckoView 外置 Web 依赖

兼容模式使用 Mozilla GeckoView，系统 WebView 模式不变。旧 X5 SDK／下载代码／
WebView 适配／音频重载／清缓存调用／Dex 服务／混淆规则及专用文案已完全移除。
**所有启动器 APK（包括 full）均不包含 GeckoView 和 X5**。

## CDN 产物

```sh
./gradlew :web-runtime:bundleWebRuntime
```

输出：`web-runtime/build/outputs/web-dependency/`

- `geckoview-148.0.20260309125808-v1-arm64-v8a.zip`：上传 CDN 的依赖包。
- 同名 `.sha256`：SHA-256 校验文件。
- `manifest.json`：版本、大小、ABI、最低 Android 版本、SHA-256 和源码／许可证信息。
- `runtime.properties`：与本次包配套的启动器构建参数。

ZIP 内的 `runtime.apk` **只是 Dex／Android 资源／Gecko 资产容器**，不会调用安装器，
不会注册独立应用，也不需要“安装未知应用”权限。只分发外层 ZIP。
包中包含 `classes*.dex`、`resources.arsc`、`assets/omni.ja` 和 arm64 native 库；
不能只分发 `.so` 或直接拿原始 AAR 替代。

当前包为 **94,765,265 字节（约 90.4 MiB）**。按用户要求，确认弹窗仍保留原有
“使用兼容模式时需要拉取依赖，是否拉取？（50MB）”文案；这个数字不是实际下载大小。
私有目录保留校验 ZIP、APK 容器和解压 native 库，初次准备需要额外磁盘空间。

## 安装包体积

系统模式和兼容模式使用同一个启动器 APK，切换设置不会改变安装包大小。
启动器只保留外置依赖加载、校验、资产服务器和桥接代码／服务声明；约 90.4 MiB 的
Gecko ZIP、`runtime.apk`、`omni.ja` 和引擎 native 库均不嵌入启动器，full 版也不例外。
开启兼容模式后增加的是应用数据目录中的下载包、解压文件及 Gecko 页面数据，而不是 APK。
移除 X5 会去掉之前内嵌的 SDK 与相关资源；精确 APK 差值须比较同一源码／资源版本的同一变体。

2026-10-05 同源码隔离构建实测（排除并行 Steam 云同步改动，以下不是当前整棵工作区的发布包体积）：

| 变体 | 移除 X5 前 | 移除 X5 后 | 减少 |
| --- | ---: | ---: | ---: |
| debug | 114,147,602 字节（108.860 MiB） | 113,861,574 字节（108.587 MiB） | 286,028 字节 |
| fullDebug | 189,831,899 字节（181.038 MiB） | 189,546,099 字节（180.765 MiB） | 285,800 字节 |

两版 APK 的 X5 SDK／旧适配类定义均从 394 个变为 0；Gecko 类定义及引擎文件在移除前后均为 0。
因此当前兼容模式不会内嵌大引擎，移除 X5 后安装包反而各缩小约 0.27 MiB。

启动器默认使用以下两个已发布地址（各自的 Release tag 不同，不能相互替换）：

- GitHub：`https://github.com/ModinMobileSTS/SlayTheAmethystResource/releases/download/Resource/geckoview-148.0.20260309125808-v1-arm64-v8a.zip`
- Gitee：`https://gitee.com/apricityx/SlayTheAmethystResource/releases/download/v1.1/geckoview-148.0.20260309125808-v1-arm64-v8a.zip`

直接复用 `ExternalResourcePackService.downloadValidatedArchive` 的 `resources.zip` 下载链路：
读取当前镜像／网络加速设置、并行测速、GitHub 镜像和加速直连、Gitee 回退、Range 分块下载、
下载速度／已下载字节进度、低速切源以及各源错误汇总。固定哈希校验及解压属于每个候选源的
成功条件；某个源提供错误内容时继续尝试其他源。Web 包仍单独限制为 200 MiB，
不使用游戏资源包较大的大小上限，也不会调用 `ResourcePackStore` 安装游戏资源。

前端与资源准备页共用 `ResourcePreparationContent`，显示进度、镜像选择、低速切源和失败重试。
Web 下载失败时不提供“下载完整启动器”入口，因为 full APK 同样不包含 Gecko。
原有确认文案保持不变，只有依赖校验和解压成功才保存兼容模式。

发布新版本时将配套 `runtime.properties` 同步至 `web-runtime/runtime.properties`。
需要自定义主地址时可使用以下参数（保留默认 Gitee 回退）：

```sh
./gradlew :app:assembleDebug -PwebRuntime.url=https://your-cdn.example/geckoview-148.0.20260309125808-v1-arm64-v8a.zip
```

其他 APK 变体使用同一参数，也支持 `local.properties` 的 `webRuntime.url`。
不会请求旧 X5 下载。CDN 无需提供动态接口；保留文件内容、允许 HTTPS GET／重定向，
推荐保留 `Content-Length` 以显示下载百分比。启动器不信任 CDN 提供的哈希，
而是使用 `runtime.properties` 中编译进 APK 的固定 SHA-256。

**更新依赖必须使用新版本目录和文件名**，重新发布配套 SHA-256 的启动器；
不要覆盖已经发布的同版本 ZIP。进程内已加载的 Gecko 不支持热替换。

## 加载与安全

- Android 9+、arm64-v8a；Android 8 仍可使用系统 WebView。
- 设置中确认后才下载。下载／校验／解压成功后保存兼容模式；失败保留原模式，可重试。
- HTTPS 下载，限制重定向次数及大小；SHA-256 通过后才解压到应用的私有 no-backup 目录。
- 严格外层结构与 native 路径白名单；不按 ZIP 内路径任意写文件。
- 临时目录完成后发布；应用启动前再次核对容器、Dex 容器和 native 文件。
- `DexClassLoader` 加载只读 `runtime.apk`，native 库不占启动器 APK 空间。
- `RuntimeContext` 提供外置资源、`omni.ja`、native 路径，保持启动器包名及数据目录。
- 启动器清单只保留 Gecko 服务名字，`GeckoComponentFactory` 在内容／socket／GPU 等
  子进程实例化外置服务。Gecko 子进程使用轻量 Application，不执行启动器初始化。
- 保留多进程。**关闭 Gecko isolated process 和 App Zygote**，因为独立 UID 无法读取
  应用私有下载目录；此方案不是原版 GeckoView 的 Android isolated-process 沙箱配置。
- Gradle 会拒绝任何启动器配置重新引入 `org.mozilla.geckoview` 或 `com.tencent.tbs` 依赖。
  每个变体的 `verify…NoBundledGecko` 打包校验同时拒绝 Gecko／X5 类及引擎文件。

独立 `:web-runtime` 模块固定 GeckoView 148.0.20260309125808，与 AGP 8.13.2／compileSdk 36
兼容。GeckoView 157 的依赖要求更高的 AGP／compileSdk，升级时需要同时评估工具链。
Mozilla MPL-2.0 及源码获取链接位于包内 `assets/web-runtime-NOTICE.txt`。

## 页面与遮罩桥

游戏内容仍在启动器 `assets/slingbreak`。`SlingAssetServer` 仅监听 `127.0.0.1`，
通过私有随机路径提供游戏静态文件；外置 Gecko 的 `resource://android` 会指向依赖容器，
不能继续用它访问启动器资产。记录端口尽量保持 Web 存储 origin 不变；端口被占用时改用新端口，
此前 origin 的网页存储不会迁移。

Gecko 使用 Web Audio fallback，暂不接 native 音频。

独立 Activity 和启动遮罩均按设置选择 Gecko。遮罩使用回环 HTTP 桥而非 WebExtension：

- 在 launcher-mode HTML 的第一段脚本前注入 `AndroidSlingBreakLauncher`，支持原来的
  `onPageReady`、`enterGame`、`scriptError`，不需要修改或重打包上游网页源码。
- 每个视图使用独立随机 channel；回调在 Android 主线程执行，销毁时注销。
- native→JS 的进度／就绪脚本通过 100ms 轮询交付，保留原来的 `SlingBreakLauncher` API。
- 只提供这三个 launcher 方法，不暴露 native 音频或任意 Java 对象；脚本执行返回值不支持。
- 严格校验 HTTP Host、Origin、桥接请求头；没有 CORS opt-in，拒绝跨站请求和 DNS rebinding。
- Gecko 不可用时遮罩记录失败并使用系统 WebView，独立游戏入口则显示明确错误供用户切换模式。
- 关闭遮罩时的 `about:blank` 直接交给 Gecko，不经过游戏资产映射，也不携带桥接 token。
  修复此前点击“开始游戏”后因资产白名单拒绝该地址、导致遮罩不隐藏和游戏未恢复的问题。
  停止加载／暂停／清空页面／关闭音频／销毁逐项清理；引擎清理异常只记录
  `overlay_cleanup_error`，不阻断其他清理步骤及遮罩关闭。该修复仅需更新启动器，外置依赖包不变。

## 验证

下载候选源／大小限制、链路测速和归档测试：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.backend.resources.ExternalResourcePackServiceTest' \
  --tests 'io.stamethyst.backend.resources.ResourcePackLinkSelectorTest' \
  --tests 'io.stamethyst.web.GeckoDependencyArchiveTest'
```

遮罩关闭回归：`./gradlew :app:testDebugUnitTest --tests 'io.stamethyst.SlingBreakHostLifecycleTest' --tests 'io.stamethyst.BootOverlayFrameDismissGateTest'`。
覆盖 `about:blank` 不经过资产服务器／不泄露 token、游戏 URL 原有参数与 token、
资产白名单保持生效，以及每一个清理步骤失败后仍继续销毁并允许关闭遮罩。

覆盖成功解压、固定哈希错误、路径穿越、错误 ABI、Dex／native 篡改和缺失 arm64 引擎。
普通 debug 和 fullDebug 均已构建并通过 APK Dex／native／资源扫描，不包含 Gecko。
Android 16（2206122SC）独立测试壳已验证只解压的依赖创建 GeckoHost、子进程启动、页面渲染、
`onPageReady` 回调、native→JS 脚本回传确认，以及程序触发网页就绪按钮后 `enterGame`
回调使 Activity 退出。测试壳不是启动器，也没有安装 `runtime.apk`。
2026-10-05 已分别从 GitHub、Gitee 完整下载公开 ZIP，均为 94,765,265 字节，
SHA-256 均匹配固定值；使用启动器的 `GeckoDependencyArchive` 完成真实包解压及再次校验。
本次下载接入在排除并行 Steam 云同步改动的隔离快照中通过 Kotlin／Java 编译及上述 27 项测试。
X5 移除与遮罩关闭修复在同一隔离快照中通过 debug／fullDebug 打包、两版实际 APK 引擎排除扫描，
以及下载／测速／归档／遮罩关闭相关的 37 项测试（0 失败）；未覆盖设备上的启动器。
仍需最终启动器的完整启动流程回归、Android 9 边界设备和实际 Web Audio／生命周期长测；
实际启动器的下载弹窗、切源、失败重试和缓存复用仍需设备端回归。
