package io.stamethyst

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import io.stamethyst.config.RuntimePaths
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.navigation.Route
import io.stamethyst.ui.LauncherContent
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.settings.SettingsFileService
import io.stamethyst.ui.settings.SettingsScreenViewModel
import java.io.File
import java.util.Locale

class LauncherActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DEBUG_LAUNCH_MODE = "io.stamethyst.debug_launch_mode"
        const val EXTRA_DEBUG_FORCE_JVM_CRASH = "io.stamethyst.debug_force_jvm_crash"
        const val EXTRA_CRASH_OCCURRED = "io.stamethyst.crash_occurred"
        const val EXTRA_CRASH_CODE = "io.stamethyst.crash_code"
        const val EXTRA_CRASH_IS_SIGNAL = "io.stamethyst.crash_is_signal"
        const val EXTRA_CRASH_DETAIL = "io.stamethyst.crash_detail"

        private val JAR_MIME_TYPES = setOf(
            "application/java-archive",
            "application/x-java-archive",
            "application/jar",
            "application/x-jar",
            "application/octet-stream"
        )
    }

    private data class ModImportPreview(
        val uri: Uri,
        val displayName: String,
        val manifest: ModJarSupport.ModManifestInfo?,
        val parseError: String?
    )

    private val mainViewModel: MainScreenViewModel by viewModels()
    private val settingsViewModel: SettingsScreenViewModel by viewModels()
    private var pendingImportDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        LauncherIconManager.syncSelection(this)

        val initialRoute = if (RuntimePaths.importedStsJar(this).isFile) {
            Route.Main
        } else {
            Route.QuickStart
        }

        setContent {
            MaterialTheme {
                LauncherContent(
                    initialRoute = initialRoute,
                    mainViewModel = mainViewModel,
                    settingsViewModel = settingsViewModel,
                )
            }
        }

        mainViewModel.handleIncomingIntent(this, intent)
        maybeImportModJarFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mainViewModel.handleIncomingIntent(this, intent)
        maybeImportModJarFromIntent(intent)
    }

    override fun onDestroy() {
        pendingImportDialog?.dismiss()
        pendingImportDialog = null
        super.onDestroy()
    }

    private fun maybeImportModJarFromIntent(incomingIntent: Intent?) {
        val uri = consumeJarImportUri(incomingIntent) ?: return
        loadModImportPreviewAndPrompt(uri)
    }

    private fun consumeJarImportUri(incomingIntent: Intent?): Uri? {
        if (incomingIntent == null || incomingIntent.action != Intent.ACTION_VIEW) {
            return null
        }
        val data = incomingIntent.data ?: return null
        if (!isJarIntent(incomingIntent, data)) {
            return null
        }

        incomingIntent.action = null
        incomingIntent.data = null
        incomingIntent.type = null
        incomingIntent.clipData = null
        return data
    }

    private fun isJarIntent(incomingIntent: Intent, uri: Uri): Boolean {
        val normalizedMime = incomingIntent.type
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (!normalizedMime.isNullOrEmpty() && normalizedMime in JAR_MIME_TYPES) {
            return true
        }
        val lowerPath = (uri.lastPathSegment ?: uri.path)
            .orEmpty()
            .lowercase(Locale.ROOT)
        return lowerPath.endsWith(".jar")
    }

    private fun loadModImportPreviewAndPrompt(uri: Uri) {
        Thread {
            val preview = buildModImportPreview(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                showModImportDialog(preview)
            }
        }.start()
    }

    private fun buildModImportPreview(uri: Uri): ModImportPreview {
        val displayName = SettingsFileService.resolveDisplayName(this, uri)
        val tempJar = File(cacheDir, "import-preview-${System.nanoTime()}.jar")
        var manifest: ModJarSupport.ModManifestInfo? = null
        var parseError: String? = null
        try {
            SettingsFileService.copyUriToFile(this, uri, tempJar)
            manifest = ModJarSupport.readModManifest(tempJar)
        } catch (error: Throwable) {
            parseError = error.message ?: error.javaClass.simpleName
        } finally {
            if (tempJar.exists()) {
                tempJar.delete()
            }
        }
        return ModImportPreview(
            uri = uri,
            displayName = displayName,
            manifest = manifest,
            parseError = parseError
        )
    }

    private fun showModImportDialog(preview: ModImportPreview) {
        pendingImportDialog?.dismiss()
        val message = buildModImportDialogMessage(preview)
        val dialog = AlertDialog.Builder(this)
            .setTitle("导入模组")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                settingsViewModel.onModJarsPicked(this, listOf(preview.uri)) {
                    mainViewModel.refresh(this)
                }
            }
            .create()
        dialog.setOnDismissListener {
            if (pendingImportDialog === dialog) {
                pendingImportDialog = null
            }
        }
        pendingImportDialog = dialog
        dialog.show()
    }

    private fun buildModImportDialogMessage(preview: ModImportPreview): String {
        val manifest = preview.manifest
        val builder = StringBuilder()
        builder.append("文件: ").append(preview.displayName)
        if (manifest != null) {
            val name = manifest.name.ifBlank { manifest.modId.ifBlank { preview.displayName } }
            val modId = manifest.modId.ifBlank { "未知" }
            val version = manifest.version.ifBlank { "未知" }
            val description = manifest.description.ifBlank { "暂无简介" }
            val dependencies = manifest.dependencies
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toList()

            builder.append("\n\n名称: ").append(name)
            builder.append("\nmodid: ").append(modId)
            builder.append("\n版本: ").append(version)
            builder.append("\n简介: ").append(description)
            if (dependencies.isNotEmpty()) {
                builder.append("\n前置: ").append(dependencies.joinToString(", "))
            }
        } else {
            val error = preview.parseError?.ifBlank { "未知错误" } ?: "未知错误"
            builder.append("\n\n无法读取 ModTheSpire.json：").append(error)
        }
        return builder.toString()
    }
}
