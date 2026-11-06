# 快速导入源文件生命周期

快速导入（Steam 登录下载和匿名自动导入）下载的 `desktop-1.0.jar` 暂存于应用外部目录的 `workshop/quickstart-steam/`，无外部存储时回退到内部 `files/workshop/quickstart-steam/`。正式游戏位于独立的 `sts/desktop-1.0.jar`。

- `QuickStartSteamImport` 先调用现有原子导入：复制到目标旁临时文件、同步写入、通过 `StsJarValidator` 校验，再原子替换正式游戏文件。
- **只有上述导入成功返回后**，立即删除 `quickstart-steam` 下载源目录及其下载附属文件；之后才进行 MTS 预热和可选的 Steam 云存档拉取。它们只使用正式游戏，不再需要下载源。
- 校验或提交失败不会触发“成功后清理”。下载取消仍由原有快速导入取消处理清理，未改变其语义。
- 删除失败或异常只记录警告，不将已提交成功的导入当作下载失败；残留由开发者设置的「清除垃圾文件」处理。
- 「清除垃圾文件」覆盖当前快速导入目录和旧内部路径，路径重复时只计一次；清理数量和释放字节沿用现有结果统计。
- 删除不跟随符号链接；不会清理整个工坊目录，不删除正式 `sts/desktop-1.0.jar`、已安装 Mod、存档或用户在共享 Download 中保留的文件。

实现位置：

- `app/src/main/java/io/stamethyst/ui/settings/files/QuickStartSteamImport.kt`
- `app/src/main/java/io/stamethyst/ui/settings/core/SettingsScreenViewModel.kt`
- `app/src/main/java/io/stamethyst/backend/steam/SteamStsJarDownloadService.kt`
- `app/src/main/java/io/stamethyst/backend/fs/LauncherJunkFileCleaner.kt`

定向测试：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'io.stamethyst.ui.settings.files.QuickStartSteamImportTest' \
  --tests 'io.stamethyst.backend.fs.LauncherJunkFileCleanerTest' \
  --tests 'io.stamethyst.backend.fs.FileTreeCleanerTest' \
  --tests 'io.stamethyst.backend.steam.SteamStsJarDownloadServiceTest'
```
