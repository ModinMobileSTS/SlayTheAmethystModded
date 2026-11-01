package io.stamethyst.backend.minigame

import android.content.Context
import android.os.Build
import android.os.Process
import io.stamethyst.R
import io.stamethyst.backend.launch.StartupProgressCallback
import io.stamethyst.backend.resources.DownloadedResourceArchiveValidator
import io.stamethyst.backend.resources.ExternalResourcePackService
import io.stamethyst.backend.resources.ResourcePackDownloadMirrorSwitchController
import io.stamethyst.backend.update.LauncherUpdateService
import io.stamethyst.backend.update.UpdateCheckExecutionResult
import io.stamethyst.backend.update.UpdateMirrorManager
import io.stamethyst.backend.update.UpdateReleaseInfo
import io.stamethyst.godot.GodotFiles
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

object SlingBreakService {
    /** Shared worker adapter for settings and the standalone dependency gate. */
    fun ensureAsync(
        context: Context, progress: StartupProgressCallback,
        mirrors: ResourcePackDownloadMirrorSwitchController,
        release: UpdateReleaseInfo? = null,
        complete: (String?) -> Unit,
    ) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val failure = runCatching {
                val selected = release ?: when (val result = check(app)) {
                    is UpdateCheckExecutionResult.Success -> result.release
                    is UpdateCheckExecutionResult.Failure -> throw IOException(result.errorSummary)
                }
                install(app, selected, StartupProgressCallback { value, message ->
                    main.post { progress.onProgress(value, message) }
                }, mirrors)
            }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            main.post { complete(failure) }
        }, "slingbreak-install").start()
    }

    fun supported() = Build.VERSION.SDK_INT >= 28 && Process.is64Bit() &&
        Build.SUPPORTED_ABIS.contains("arm64-v8a")

    fun check(context: Context): UpdateCheckExecutionResult {
        check(supported()) { context.getString(R.string.godot_unsupported) }
        val installed = SlingBreakInstallation.read(context)
        return LauncherUpdateService.checkForUpdates(
            context, installed?.rawTagName.orEmpty(), UpdateMirrorManager.current(context),
            SlingBreakReleases.target(installed),
        )
    }

    /** Worker-thread only. No activation or save deletion until every component has been validated. */
    @Synchronized fun install(
        context: Context,
        release: UpdateReleaseInfo,
        progress: StartupProgressCallback,
        mirrors: ResourcePackDownloadMirrorSwitchController,
    ) {
        check(supported()) { context.getString(R.string.godot_unsupported) }
        val root = SlingBreakInstallation.root(context)
        require(release.assetSha256.matches(Regex("[a-f0-9]{64}"))) { "Missing GitHub PCK SHA-256" }
        require(release.assetSize in 40..SlingBreakReleases.MAX_COMPONENT_BYTES) { "Invalid PCK size" }
        check(root.isDirectory || root.mkdirs()) { "Cannot create minigame directory" }
        RandomAccessFile(File(root, "install.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val installed = SlingBreakInstallation.read(context)
                if (installed != null && SlingBreakReleases.compare(release, installed) < 0) {
                    throw IOException("小游戏版本已变化，请重新检查更新，不能降级到旧镜像版本")
                }
                val stage = File(root, "stage-${UUID.randomUUID()}")
                check(stage.mkdir())
                try { SlingBreakInstallation.activateWhenReady(context, release) {
                    val engine = GodotFiles.engine(context)
                    val engineApk = File(engine, "engine.apk")
                    if (runCatching { GodotFiles.verifyEngine(engine) }.isFailure) {
                        val downloaded = File(stage, "engine.download")
                        if (runCatching { GodotFiles.verify(engineApk, SlingBreakReleases.ENGINE_SHA256) }.isSuccess) {
                            engineApk.copyTo(downloaded)
                        } else download(context, SlingBreakReleases.ENGINE_URL, downloaded,
                            SlingBreakReleases.ENGINE_SHA256, 0, "引擎", progress, mirrors)
                        val engineStage = File(stage, "engine").apply { check(mkdir()) }
                        val apk = File(engineStage, "engine.apk")
                        apk.outputStream().use { output ->
                            check(apk.setReadOnly()) { "Cannot protect dynamic code" }
                            downloaded.inputStream().use { it.copyTo(output) }
                        }
                        GodotFiles.extractEngine(apk, engineStage)
                        // Keep old inodes alive if an already-running engine owns them.
                        if (engine.exists()) check(engine.renameTo(File(root, "engine-retired-${UUID.randomUUID()}")))
                        check(engineStage.renameTo(engine)) { "Cannot publish engine" }
                    }
                    val files = GodotFiles(context, release)
                    if (runCatching { GodotFiles.verify(files.pck(), release.assetSha256) }.isFailure) {
                        val pck = File(stage, "game.pck")
                        download(context, release.assetDownloadUrl, pck, release.assetSha256,
                            release.assetSize, "游戏 ${release.rawTagName}", progress, mirrors)
                        validatePck(pck)
                        replace(pck, files.pck())
                    }
                    validatePck(files.pck())
                    progress.onProgress(-1, context.getString(R.string.godot_verifying))
                    // This builds/validates the code-free asset mount and compares SOs to the pinned APK.
                    files.prepare()
                }
                    progress.onProgress(100, context.getString(R.string.godot_installed_offline, release.rawTagName))
                } finally {
                    // Only this install's UUID staging directory, never saves or active generations.
                    stage.deleteRecursively()
                }
            }
        }
    }

    private fun download(
        context: Context, url: String, target: File, sha: String, expectedSize: Long,
        label: String, progress: StartupProgressCallback, mirrors: ResourcePackDownloadMirrorSwitchController,
    ) = ExternalResourcePackService.downloadValidatedArchive(
        context, listOf(url), target,
        StartupProgressCallback { value, message -> progress.onProgress(value, "$label · $message") },
        mirrors, SlingBreakReleases.MAX_COMPONENT_BYTES,
        DownloadedResourceArchiveValidator { file, _ ->
            if (expectedSize > 0 && file.length() != expectedSize) throw IOException("下载文件大小不符")
            GodotFiles.verify(file, sha)
            if (expectedSize > 0) validatePck(file)
        },
    )

    internal fun validatePck(file: File) {
        file.inputStream().use { stream ->
            val bytes = ByteArray(24)
            if (stream.read(bytes) != bytes.size) throw IOException("PCK 文件不完整")
            val header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (header.int != 0x43504447 || header.int != 4 || header.int != 4 ||
                header.int != 7 || header.int != 2 || header.int != 2) {
                throw IOException("PCK 与当前 Godot 4.7.2 引擎不兼容")
            }
        }
    }

    private fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
