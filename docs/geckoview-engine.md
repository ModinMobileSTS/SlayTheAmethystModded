# GeckoView 外置 Web 依赖

兼容模式使用 Mozilla GeckoView，系统 WebView 模式不变。保留旧 X5 SDK／代码，
但不再通过 X5 下载内核。**所有启动器 APK（包括 full）均不包含 GeckoView**。

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

上传后提供 ZIP 的 HTTPS URL。将输出的 `runtime.properties` 同步至
`web-runtime/runtime.properties`，并使用：

```sh
./gradlew :app:assembleDebug -PwebRuntime.url=https://your-cdn.example/geckoview-148.0.20260309125808-v1-arm64-v8a.zip
```

其他 APK 变体使用同一参数。URL 默认空，未配置时明确提示依赖 CDN 尚未配置，
不会悄悄请求旧 X5 下载。CDN 无需提供动态接口；保留文件内容、允许 HTTPS GET／重定向，
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
- Gradle 会拒绝任何启动器配置重新引入 `org.mozilla.geckoview` 依赖。

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

## 验证

本地归档测试：

```sh
./gradlew :app:testDebugUnitTest --tests 'io.stamethyst.web.GeckoDependencyArchiveTest'
```

覆盖成功解压、固定哈希错误、路径穿越、错误 ABI、Dex／native 篡改和缺失 arm64 引擎。
普通 debug 和 fullDebug 均已构建并通过 APK Dex／native／资源扫描，不包含 Gecko。
Android 16（2206122SC）独立测试壳已验证只解压的依赖创建 GeckoHost、子进程启动、页面渲染、
`onPageReady` 回调、native→JS 脚本回传确认，以及程序触发网页就绪按钮后 `enterGame`
回调使 Activity 退出。测试壳不是启动器，也没有安装 `runtime.apk`。
仍需最终启动器的完整启动流程回归、Android 9 边界设备和实际 Web Audio／生命周期长测；
CDN 下载端到端测试需要用户提供上传后的 URL。
