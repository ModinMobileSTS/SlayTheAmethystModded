package io.stamethyst

import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.kdt.pojavlaunch.MainActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LauncherActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "LauncherActivity"
        private const val PREF_NAME_LAUNCHER = "sts_launcher_prefs"
        private const val PREF_KEY_BACK_IMMEDIATE_EXIT = "back_immediate_exit"
        private const val PREF_KEY_TARGET_FPS = "target_fps"

        const val EXTRA_DEBUG_LAUNCH_MODE = "io.stamethyst.debug_launch_mode"
        const val EXTRA_CRASH_OCCURRED = "io.stamethyst.crash_occurred"
        const val EXTRA_CRASH_CODE = "io.stamethyst.crash_code"
        const val EXTRA_CRASH_IS_SIGNAL = "io.stamethyst.crash_is_signal"
        const val EXTRA_CRASH_DETAIL = "io.stamethyst.crash_detail"

        private const val DEFAULT_RENDER_SCALE = 0.75f
        private const val MIN_RENDER_SCALE = 0.50f
        private const val MAX_RENDER_SCALE = 1.00f
        private const val DEFAULT_TARGET_FPS = 120
        private val TARGET_FPS_OPTIONS = intArrayOf(60, 90, 120, 240)

        private val SAVE_IMPORT_TOP_LEVEL_DIRS = arrayOf(
            "betaPreferences",
            "betapreferences",
            "betaPerferences",
            "betaperferences",
            "preferences",
            "perferences",
            "saves",
            "runs",
            "metrics",
            "home",
            "sendToDevs",
            "sendtodevs",
            "multiplayer",
            "multiple"
        )

        private val SAVE_EXPORT_FOLDER_MAPPINGS = arrayOf(
            "multiplayer" to "multiple",
            "saves" to "saves",
            "betaPreferences" to "preferences",
            "runs" to "runs"
        )
    }

    private enum class Screen {
        MAIN,
        SETTINGS
    }

    private data class ModItemUi(
        val modId: String,
        val displayName: String,
        val required: Boolean,
        val installed: Boolean,
        val enabled: Boolean
    )

    private data class LauncherUiState(
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val statusText: String = "Initializing...",
        val logPathText: String = "",
        val mods: List<ModItemUi> = emptyList(),
        val optionalEnabledCount: Int = 0,
        val optionalTotalCount: Int = 0,
        val hasJar: Boolean = false,
        val hasMts: Boolean = false,
        val hasBaseMod: Boolean = false,
        val hasStsLib: Boolean = false,
        val renderScaleInput: String = String.format(Locale.US, "%.2f", DEFAULT_RENDER_SCALE),
        val selectedRenderer: RendererBackend = RendererBackend.OPENGL_ES2,
        val selectedTargetFps: Int = DEFAULT_TARGET_FPS,
        val selectedLauncherIcon: LauncherIcon = LauncherIcon.AMBER,
        val backImmediateExit: Boolean = true
    )

    private data class SaveImportResult(
        val importedFiles: Int,
        val backupLabel: String?
    )

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var uiState by mutableStateOf(LauncherUiState())
    private var screen by mutableStateOf(Screen.MAIN)

    private val importJarLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::onJarPicked)
    private val importModsLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), ::onModJarsPicked)
    private val importSavesLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::onSavesArchivePicked)
    private val exportSavesLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::onSavesExportPicked)
    private val exportDebugLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), ::onDebugExportPicked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.init(applicationContext)
        val selectedLauncherIcon = LauncherIconManager.syncSelection(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == Screen.SETTINGS) {
                    screen = Screen.MAIN
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        uiState = uiState.copy(
            renderScaleInput = formatRenderScale(readRenderScaleValue()),
            selectedRenderer = RendererConfig.readPreferredBackend(this),
            selectedTargetFps = readTargetFpsSelection(),
            selectedLauncherIcon = selectedLauncherIcon,
            backImmediateExit = readBackBehaviorSelection(),
            logPathText = buildLogPathText()
        )

        setContent {
            MaterialTheme {
                LauncherContent()
            }
        }

        refreshStatus()
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LauncherContent() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (screen == Screen.MAIN) "SlayTheAmethyst" else "设置")
                    },
                    navigationIcon = {
                        if (screen == Screen.SETTINGS) {
                            IconButton(onClick = { screen = Screen.MAIN }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        }
                    },
                    actions = {
                        if (screen == Screen.MAIN) {
                            IconButton(onClick = { screen = Screen.SETTINGS }) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "设置"
                                )
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (screen == Screen.MAIN) {
                MainScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                )
            } else {
                SettingsScreen(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp)
                )
            }
        }
    }

    @Composable
    private fun MainScreen(modifier: Modifier = Modifier) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier.verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Text(
                text = buildMainStatusSummary(),
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider()

            Text(
                text = "选择模组",
                style = MaterialTheme.typography.titleMedium
            )

            val optionalMods = uiState.mods.filter { !it.required }
            if (optionalMods.isEmpty()) {
                Text(
                    text = "暂无可选模组",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                optionalMods.forEach { mod ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                enabled = !uiState.busy && mod.installed,
                                value = mod.enabled,
                                onValueChange = { checked -> onModChecked(mod, checked) }
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = mod.enabled,
                            onCheckedChange = null,
                            enabled = !uiState.busy && mod.installed
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = mod.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { onLaunchClicked() },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("启动游戏")
            }
        }
    }

    @Composable
    private fun SettingsScreen(modifier: Modifier = Modifier) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier.verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                uiState.busyMessage?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(
                onClick = {
                    importJarLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入 desktop-1.0.jar")
            }

            Button(
                onClick = {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入模组")
            }

            Button(
                onClick = {
                    importSavesLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导入存档")
            }

            Button(
                onClick = { exportSavesLauncher.launch(buildSaveExportFileName()) },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出存档")
            }

            Button(
                onClick = { exportDebugLauncher.launch(buildDebugExportFileName()) },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("导出调试日志")
            }

            HorizontalDivider()

            Text(text = "渲染设置", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = uiState.renderScaleInput,
                onValueChange = { value -> uiState = uiState.copy(renderScaleInput = value) },
                enabled = !uiState.busy,
                label = { Text("内部渲染比例 (0.50 - 1.00)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { saveRenderScaleFromInput(showToast = true) },
                enabled = !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存渲染比例")
            }

            Text(text = "刷新率上限", style = MaterialTheme.typography.bodyMedium)
            TARGET_FPS_OPTIONS.forEach { fps ->
                TargetFpsOptionRow(
                    fps = fps,
                    selected = uiState.selectedTargetFps == fps,
                    enabled = !uiState.busy,
                    onSelect = ::onTargetFpsSelected
                )
            }

            Text(text = getString(R.string.renderer_backend_label), style = MaterialTheme.typography.bodyMedium)
            RendererOptionRow(
                backend = RendererBackend.OPENGL_ES2,
                label = RendererBackend.OPENGL_ES2.selectorLabel(),
                selected = uiState.selectedRenderer == RendererBackend.OPENGL_ES2,
                enabled = !uiState.busy,
                onSelect = ::onRendererSelected
            )
            RendererOptionRow(
                backend = RendererBackend.KOPPER_ZINK,
                label = RendererBackend.KOPPER_ZINK.selectorLabel(),
                selected = uiState.selectedRenderer == RendererBackend.KOPPER_ZINK,
                enabled = !uiState.busy,
                onSelect = ::onRendererSelected
            )
            RendererOptionRow(
                backend = RendererBackend.ANGLE,
                label = RendererBackend.ANGLE.selectorLabel(),
                selected = uiState.selectedRenderer == RendererBackend.ANGLE,
                enabled = !uiState.busy,
                onSelect = ::onRendererSelected
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = uiState.backImmediateExit,
                    enabled = !uiState.busy,
                    onCheckedChange = { checked ->
                        uiState = uiState.copy(backImmediateExit = checked)
                        saveBackBehaviorSelection(checked)
                    }
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (uiState.backImmediateExit) {
                        "Back 键：立即退出到主界面"
                    } else {
                        "Back 键：禁用"
                    }
                )
            }
            Text(
                text = "关闭上面的开关后，游戏运行时按 Back 键将不处理。",
                style = MaterialTheme.typography.bodySmall
            )

            HorizontalDivider()

            Text(text = "启动器图标", style = MaterialTheme.typography.titleMedium)
            LauncherIcon.entries.forEach { icon ->
                LauncherIconOptionRow(
                    icon = icon,
                    selected = uiState.selectedLauncherIcon == icon,
                    enabled = !uiState.busy,
                    onSelect = ::onLauncherIconSelected
                )
            }

            HorizontalDivider()

            Text(text = "状态信息", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(text = uiState.statusText, style = MaterialTheme.typography.bodySmall)
            }

            Text(text = "日志路径", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(text = uiState.logPathText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun RendererOptionRow(
        backend: RendererBackend,
        label: String,
        selected: Boolean,
        enabled: Boolean,
        onSelect: (RendererBackend) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    onValueChange = { onSelect(backend) }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = label)
        }
    }

    @Composable
    private fun TargetFpsOptionRow(
        fps: Int,
        selected: Boolean,
        enabled: Boolean,
        onSelect: (Int) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    onValueChange = { onSelect(fps) }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "$fps FPS")
        }
    }

    @Composable
    private fun LauncherIconOptionRow(
        icon: LauncherIcon,
        selected: Boolean,
        enabled: Boolean,
        onSelect: (LauncherIcon) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    onValueChange = { onSelect(icon) }
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = icon.title)
                Text(
                    text = icon.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    private fun buildMainStatusSummary(): String {
        return StringBuilder()
            .append(if (uiState.hasJar) "desktop-1.0.jar: OK" else "desktop-1.0.jar: missing")
            .append('\n')
            .append("ModTheSpire.jar: ")
            .append(if (uiState.hasMts) "OK (bundled)" else "missing")
            .append('\n')
            .append("BaseMod.jar: ")
            .append(if (uiState.hasBaseMod) "OK (required)" else "missing (required)")
            .append('\n')
            .append("StSLib.jar: ")
            .append(if (uiState.hasStsLib) "OK (required, bundled)" else "missing (required)")
            .append('\n')
            .append("Mods enabled: ")
            .append(uiState.optionalEnabledCount)
            .append('/')
            .append(uiState.optionalTotalCount)
            .toString()
    }

    private fun onModChecked(mod: ModItemUi, enabled: Boolean) {
        if (uiState.busy || mod.required) {
            return
        }
        try {
            ModManager.setOptionalModEnabled(this, mod.modId, enabled)
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                "Failed to update mod selection: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
        refreshStatus()
    }

    private fun onRendererSelected(backend: RendererBackend) {
        if (uiState.busy) {
            return
        }
        uiState = uiState.copy(selectedRenderer = backend)
        if (!saveRendererSelection()) {
            return
        }
        refreshStatus()
    }

    private fun onTargetFpsSelected(targetFps: Int) {
        if (uiState.busy) {
            return
        }
        val normalizedTargetFps = normalizeTargetFps(targetFps)
        uiState = uiState.copy(selectedTargetFps = normalizedTargetFps)
        saveTargetFpsSelection(normalizedTargetFps)
        refreshStatus()
    }

    private fun onLauncherIconSelected(icon: LauncherIcon) {
        if (uiState.busy || uiState.selectedLauncherIcon == icon) {
            return
        }
        LauncherIconManager.applySelection(this, icon)
        uiState = uiState.copy(selectedLauncherIcon = icon)
        Toast.makeText(this, "已切换启动器图标为：${icon.title}", Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun onLaunchClicked() {
        if (!saveRenderScaleFromInput(showToast = false)) {
            return
        }
        if (!saveRendererSelection()) {
            return
        }
        saveTargetFpsSelection(uiState.selectedTargetFps)
        saveBackBehaviorSelection(uiState.backImmediateExit)
        prepareAndLaunch(StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) {
            return
        }
        val showedCrashDialog = maybeShowCrashDialog(intent)
        if (!showedCrashDialog) {
            maybeLaunchFromDebugExtra(intent)
        }
    }

    private fun maybeShowCrashDialog(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(EXTRA_CRASH_OCCURRED, false)) {
            return false
        }

        val code = intent.getIntExtra(EXTRA_CRASH_CODE, -1)
        val isSignal = intent.getBooleanExtra(EXTRA_CRASH_IS_SIGNAL, false)
        val detail = intent.getStringExtra(EXTRA_CRASH_DETAIL)
        val message = if (!detail.isNullOrBlank()) {
            getString(R.string.sts_crash_detail_format, detail.trim())
        } else {
            @StringRes val messageId = if (isSignal) R.string.sts_signal_exit else R.string.sts_normal_exit
            getString(messageId, code)
        }

        intent.removeExtra(EXTRA_CRASH_OCCURRED)
        intent.removeExtra(EXTRA_CRASH_CODE)
        intent.removeExtra(EXTRA_CRASH_IS_SIGNAL)
        intent.removeExtra(EXTRA_CRASH_DETAIL)

        AlertDialog.Builder(this)
            .setTitle(R.string.sts_crash_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    private fun maybeLaunchFromDebugExtra(intent: Intent) {
        val debugLaunchMode = intent.getStringExtra(EXTRA_DEBUG_LAUNCH_MODE)
        Log.i(TAG, "Debug launch extra: $debugLaunchMode")
        if (debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_VANILLA
            && debugLaunchMode != StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD
        ) {
            return
        }
        if (!saveRenderScaleFromInput(showToast = false)) {
            return
        }
        if (!saveRendererSelection()) {
            return
        }
        saveTargetFpsSelection(uiState.selectedTargetFps)
        Log.i(TAG, "Auto launching mode from debug extra: $debugLaunchMode")
        prepareAndLaunch(debugLaunchMode)
    }

    private fun onJarPicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Importing desktop-1.0.jar...")
        executor.execute {
            try {
                copyUriToFile(uri, RuntimePaths.importedStsJar(this))
                StsJarValidator.validate(RuntimePaths.importedStsJar(this))
                runOnUiThread {
                    Toast.makeText(this, "Imported desktop-1.0.jar", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }
    }

    private fun onModJarsPicked(uris: List<Uri>?) {
        if (uris.isNullOrEmpty()) {
            return
        }
        setBusy(true, "Importing selected mod jars...")
        executor.execute {
            var imported = 0
            val errors = ArrayList<String>()
            for (uri in uris) {
                try {
                    val modId = importModJar(uri)
                    if (!ModManager.isRequiredModId(modId)) {
                        ModManager.setOptionalModEnabled(this, modId, true)
                    }
                    imported++
                } catch (error: Throwable) {
                    val name = resolveDisplayName(uri)
                    errors.add("$name: ${error.message}")
                }
            }

            val importedCount = imported
            val failedCount = errors.size
            val firstError = if (failedCount > 0) errors[0] else null
            runOnUiThread {
                when {
                    importedCount > 0 && failedCount == 0 -> {
                        Toast.makeText(this, "Imported $importedCount mod jar(s)", Toast.LENGTH_SHORT).show()
                    }

                    importedCount > 0 -> {
                        Toast.makeText(
                            this,
                            "Imported $importedCount, failed $failedCount ($firstError)",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    else -> {
                        Toast.makeText(this, "Mod import failed: $firstError", Toast.LENGTH_LONG).show()
                    }
                }
                refreshStatus()
            }
        }
    }

    @Throws(IOException::class)
    private fun importModJar(uri: Uri): String {
        val modsDir = RuntimePaths.modsDir(this)
        if (!modsDir.exists() && !modsDir.mkdirs()) {
            throw IOException("Failed to create mods directory")
        }

        val tempFile = File(modsDir, ".import-${System.nanoTime()}.tmp.jar")
        copyUriToFile(uri, tempFile)
        try {
            val modId = ModManager.normalizeModId(ModJarSupport.resolveModId(tempFile))
            if (modId.isBlank()) {
                throw IOException("modid is empty")
            }
            if (ModManager.MOD_ID_BASEMOD == modId) {
                ModJarSupport.validateBaseModJar(tempFile)
            } else if (ModManager.MOD_ID_STSLIB == modId) {
                ModJarSupport.validateStsLibJar(tempFile)
            }
            val targetFile = ModManager.resolveStorageFileForModId(this, modId)
            moveFileReplacing(tempFile, targetFile)
            return modId
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun moveFileReplacing(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: ${parent.absolutePath}")
        }
        if (target.exists() && !target.delete()) {
            throw IOException("Failed to replace existing file: ${target.absolutePath}")
        }
        if (source.renameTo(target)) {
            return
        }

        FileInputStream(source).use { input ->
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        if (!source.delete()) {
            throw IOException("Failed to clean temp file: ${source.absolutePath}")
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = cursor.getString(index)
                    if (!value.isNullOrBlank()) {
                        return value
                    }
                }
            }
            "unknown.jar"
        } catch (_: Throwable) {
            "unknown.jar"
        } finally {
            cursor?.close()
        }
    }

    private fun prepareAndLaunch(launchMode: String) {
        val renderer = uiState.selectedRenderer
        val targetFps = normalizeTargetFps(uiState.selectedTargetFps)
        val backImmediateExit = uiState.backImmediateExit

        val intent = Intent(this, StsGameActivity::class.java)
        intent.putExtra(StsGameActivity.EXTRA_LAUNCH_MODE, launchMode)
        intent.putExtra(StsGameActivity.EXTRA_RENDERER_BACKEND, renderer.rendererId())
        intent.putExtra(StsGameActivity.EXTRA_TARGET_FPS, targetFps)
        intent.putExtra(StsGameActivity.EXTRA_PRELAUNCH_PREPARED, false)
        intent.putExtra(
            StsGameActivity.EXTRA_WAIT_FOR_MAIN_MENU,
            StsLaunchSpec.LAUNCH_MODE_MTS_BASEMOD == launchMode
        )
        intent.putExtra(StsGameActivity.EXTRA_BACK_IMMEDIATE_EXIT, backImmediateExit)
        Log.i(
            TAG,
            "Start StsGameActivity directly, mode=$launchMode, renderer=${renderer.rendererId()}, targetFps=$targetFps, backImmediateExit=$backImmediateExit"
        )
        startActivity(intent)
    }

    private fun onSavesArchivePicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Importing save archive...")
        executor.execute {
            try {
                val result = importSaveArchive(uri)
                runOnUiThread {
                    val message = if (result.backupLabel.isNullOrEmpty()) {
                        "Imported ${result.importedFiles} save files"
                    } else {
                        "Imported ${result.importedFiles} save files (backup: ${result.backupLabel})"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Save import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }
    }

    private fun onSavesExportPicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting save archive...")
        executor.execute {
            try {
                val exportedCount = exportSaveBundle(uri)
                runOnUiThread {
                    Toast.makeText(this, "Save archive exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Save export failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }
    }

    private fun onDebugExportPicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        setBusy(true, "Exporting debug bundle...")
        executor.execute {
            try {
                val exportedCount = exportDebugBundle(uri)
                runOnUiThread {
                    Toast.makeText(this, "Debug bundle exported ($exportedCount files)", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Debug export failed: ${error.message}", Toast.LENGTH_LONG).show()
                    refreshStatus()
                }
            }
        }
    }

    private fun buildSaveExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-export-${formatter.format(Date())}.zip"
    }

    private fun buildDebugExportFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-debug-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    private fun exportSaveBundle(uri: Uri): Int {
        val stsRoot = RuntimePaths.stsRoot(this)
        contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            ZipOutputStream(output).use { zipOutput ->
                var exportedCount = 0
                for ((sourceFolder, archiveFolder) in SAVE_EXPORT_FOLDER_MAPPINGS) {
                    val sourceRoot = resolveSaveExportSourceFolder(stsRoot, sourceFolder) ?: continue
                    exportedCount += exportSaveFolderToZip(zipOutput, sourceRoot, archiveFolder)
                }
                if (exportedCount <= 0) {
                    val entry = ZipEntry("sts/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No save files found yet.\n" +
                        "Expected folders under: ${stsRoot.absolutePath}\n" +
                        "Folders: multiplayer, saves, betaPreferences, runs\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
                return exportedCount
            }
        }
    }

    private fun resolveSaveExportSourceFolder(stsRoot: File, sourceFolder: String): File? {
        val candidates = when (sourceFolder.lowercase(Locale.ROOT)) {
            "multiplayer" -> arrayOf("multiplayer", "multiple")
            "betapreferences" -> arrayOf("betaPreferences", "betapreferences", "betaPerferences", "betaperferences")
            else -> arrayOf(sourceFolder)
        }
        for (candidateName in candidates) {
            val candidate = File(stsRoot, candidateName)
            if (candidate.exists()) {
                return candidate
            }
        }
        return null
    }

    @Throws(IOException::class)
    private fun exportSaveFolderToZip(zipOutput: ZipOutputStream, sourceRoot: File, archiveRoot: String): Int {
        if (!sourceRoot.exists()) {
            return 0
        }
        val sourceFiles = ArrayList<File>()
        collectRegularFiles(sourceRoot, sourceFiles)
        var exportedCount = 0
        for (sourceFile in sourceFiles) {
            val entryName = buildSaveExportEntryName(sourceRoot, archiveRoot, sourceFile)
            writeFileToZip(zipOutput, sourceFile, entryName)
            exportedCount++
        }
        return exportedCount
    }

    @Throws(IOException::class)
    private fun buildSaveExportEntryName(sourceRoot: File, archiveRoot: String, sourceFile: File): String {
        val sourceRootPath = sourceRoot.canonicalPath
        val sourceFilePath = sourceFile.canonicalPath
        val relativePath = if (sourceFilePath.startsWith("$sourceRootPath${File.separator}")) {
            sourceFilePath.substring(sourceRootPath.length + 1)
        } else {
            sourceFile.name
        }
        val normalizedRelativePath = relativePath.replace('\\', '/')
        return "sts/$archiveRoot/$normalizedRelativePath"
    }

    @Throws(IOException::class)
    private fun exportDebugBundle(uri: Uri): Int {
        val stsRoot = RuntimePaths.stsRoot(this)
        val debugFiles = ArrayList<File>()
        addDebugFileIfExists(debugFiles, RuntimePaths.latestLog(this))
        addDebugFileIfExists(debugFiles, File(stsRoot, "jvm_output.log"))
        addDebugFileIfExists(debugFiles, RuntimePaths.enabledModsConfig(this))

        val hsErrFiles = stsRoot.listFiles { _, name ->
            name != null && name.startsWith("hs_err_pid") && name.endsWith(".log")
        }
        if (hsErrFiles != null && hsErrFiles.isNotEmpty()) {
            Arrays.sort(hsErrFiles) { a, b -> java.lang.Long.compare(b.lastModified(), a.lastModified()) }
            for (hsErrFile in hsErrFiles) {
                addDebugFileIfExists(debugFiles, hsErrFile)
            }
        }

        contentResolver.openOutputStream(uri).use { output ->
            if (output == null) {
                throw IOException("Unable to open destination file")
            }
            ZipOutputStream(output).use { zipOutput ->
                var exportedCount = 0
                for (file in debugFiles) {
                    writeFileToZip(zipOutput, stsRoot, file)
                    exportedCount++
                }
                if (exportedCount <= 0) {
                    val entry = ZipEntry("sts/README.txt")
                    zipOutput.putNextEntry(entry)
                    val message = "No debug log files found yet.\n" +
                        "Expected paths under: ${stsRoot.absolutePath}\n" +
                        "Files: latestlog.txt, jvm_output.log, hs_err_pid*.log\n"
                    zipOutput.write(message.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
                return exportedCount
            }
        }
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, stsRoot: File, sourceFile: File) {
        val entryName = buildDebugEntryName(stsRoot, sourceFile)
        writeFileToZip(zipOutput, sourceFile, entryName)
    }

    @Throws(IOException::class)
    private fun writeFileToZip(zipOutput: ZipOutputStream, sourceFile: File, entryName: String) {
        val entry = ZipEntry(entryName)
        if (sourceFile.lastModified() > 0) {
            entry.time = sourceFile.lastModified()
        }
        zipOutput.putNextEntry(entry)
        FileInputStream(sourceFile).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                zipOutput.write(buffer, 0, read)
            }
        }
        zipOutput.closeEntry()
    }

    @Throws(IOException::class)
    private fun buildDebugEntryName(stsRoot: File, sourceFile: File): String {
        val rootPath = stsRoot.canonicalPath
        val filePath = sourceFile.canonicalPath
        val relativePath = if (filePath.startsWith("$rootPath${File.separator}")) {
            filePath.substring(rootPath.length + 1)
        } else {
            sourceFile.name
        }
        return "sts/${relativePath.replace('\\', '/')}"
    }

    private fun addDebugFileIfExists(files: MutableList<File>, file: File?) {
        if (file != null && file.isFile && file.length() > 0) {
            files.add(file)
        }
    }

    private fun refreshStatus() {
        val hasJar = RuntimePaths.importedStsJar(this).exists()
        val hasMts = RuntimePaths.importedMtsJar(this).exists() || hasBundledAsset("components/mods/ModTheSpire.jar")
        val hasBaseMod = RuntimePaths.importedBaseModJar(this).exists() || hasBundledAsset("components/mods/BaseMod.jar")
        val hasStsLib = RuntimePaths.importedStsLibJar(this).exists() || hasBundledAsset("components/mods/StSLib.jar")

        val renderScale = readRenderScaleValue()
        val selectedRenderer = uiState.selectedRenderer
        val targetFps = normalizeTargetFps(uiState.selectedTargetFps)
        val selectedLauncherIcon = LauncherIconManager.readSelection(this)
        val rendererDecision = RendererConfig.resolveEffectiveBackend(this, selectedRenderer)
        val rendererSelectedLine = getString(R.string.renderer_selected_format, selectedRenderer.statusLabel())
        val rendererEffectiveLine = if (rendererDecision.isFallback()) {
            getString(
                R.string.renderer_effective_reason_format,
                rendererDecision.effective.statusLabel(),
                rendererDecision.reason
            )
        } else {
            getString(
                R.string.renderer_effective_format,
                rendererDecision.effective.statusLabel()
            )
        }

        val mods = ModManager.listInstalledMods(this)
        var optionalTotal = 0
        var optionalEnabled = 0
        val modItems = mods.map { mod ->
            if (!mod.required) {
                optionalTotal++
                if (mod.enabled) {
                    optionalEnabled++
                }
            }
            ModItemUi(
                modId = mod.modId,
                displayName = mod.displayName,
                required = mod.required,
                installed = mod.installed,
                enabled = mod.enabled
            )
        }

        val status = (if (hasJar) "desktop-1.0.jar: OK" else "desktop-1.0.jar: missing") +
            "\nModTheSpire.jar: " + if (hasMts) "OK (bundled)" else "missing" +
            "\nBaseMod.jar: " + if (hasBaseMod) "OK (required)" else "missing (required)" +
            "\nStSLib.jar: " + if (hasStsLib) "OK (required, bundled)" else "missing (required)" +
            "\nOptional mods enabled: $optionalEnabled/$optionalTotal" +
            "\nRender scale: ${formatRenderScale(renderScale)} (0.50-1.00)" +
            "\nTarget FPS: $targetFps" +
            "\nLauncher icon: ${selectedLauncherIcon.title}" +
            "\n$rendererSelectedLine" +
            "\n$rendererEffectiveLine" +
            "\nRuntime pack expected at build time: runtime-pack/jre8-pojav.zip"

        uiState = uiState.copy(
            busy = false,
            busyMessage = null,
            statusText = status,
            hasJar = hasJar,
            hasMts = hasMts,
            hasBaseMod = hasBaseMod,
            hasStsLib = hasStsLib,
            optionalEnabledCount = optionalEnabled,
            optionalTotalCount = optionalTotal,
            selectedLauncherIcon = selectedLauncherIcon,
            mods = modItems
        )
    }

    private fun hasBundledAsset(assetPath: String): Boolean {
        return try {
            assets.open(assetPath).use {
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun setBusy(busy: Boolean, message: String?) {
        uiState = if (busy) {
            uiState.copy(
                busy = true,
                busyMessage = message
            )
        } else {
            uiState.copy(
                busy = false,
                busyMessage = null
            )
        }
    }

    private fun saveRendererSelection(): Boolean {
        val selected = uiState.selectedRenderer
        return try {
            RendererConfig.writePreferredBackend(this, selected)
            true
        } catch (error: IOException) {
            Toast.makeText(
                this,
                getString(R.string.renderer_save_failed, error.message ?: "unknown"),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }

    private fun readRenderScaleValue(): Float {
        val config = renderScaleConfigFile()
        if (!config.exists()) {
            return DEFAULT_RENDER_SCALE
        }
        return try {
            FileInputStream(config).use { input ->
                val bytes = ByteArray(minOf(config.length().toInt(), 64))
                val read = input.read(bytes)
                if (read <= 0) {
                    return DEFAULT_RENDER_SCALE
                }
                val value = String(bytes, 0, read, StandardCharsets.UTF_8)
                    .trim()
                    .replace(',', '.')
                if (value.isEmpty()) {
                    return DEFAULT_RENDER_SCALE
                }
                val parsed = value.toFloat()
                when {
                    parsed < MIN_RENDER_SCALE -> MIN_RENDER_SCALE
                    parsed > MAX_RENDER_SCALE -> MAX_RENDER_SCALE
                    else -> parsed
                }
            }
        } catch (_: Throwable) {
            DEFAULT_RENDER_SCALE
        }
    }

    private fun saveRenderScaleFromInput(showToast: Boolean): Boolean {
        val input = uiState.renderScaleInput.trim().replace(',', '.')
        if (input.isEmpty()) {
            val config = renderScaleConfigFile()
            if (config.exists() && !config.delete()) {
                Toast.makeText(this, "Failed to reset render scale", Toast.LENGTH_SHORT).show()
                return false
            }
            uiState = uiState.copy(renderScaleInput = formatRenderScale(DEFAULT_RENDER_SCALE))
            if (showToast) {
                Toast.makeText(this, "Render scale reset to default 0.75", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
            return true
        }

        val parsed = try {
            input.toFloat()
        } catch (_: NumberFormatException) {
            Toast.makeText(this, "Invalid render scale, use 0.50 to 1.00", Toast.LENGTH_SHORT).show()
            return false
        }

        if (parsed < MIN_RENDER_SCALE || parsed > MAX_RENDER_SCALE) {
            Toast.makeText(this, "Render scale must be between 0.50 and 1.00", Toast.LENGTH_SHORT).show()
            return false
        }

        val config = renderScaleConfigFile()
        val parent = config.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Toast.makeText(this, "Failed to create config directory", Toast.LENGTH_SHORT).show()
            return false
        }

        val normalized = formatRenderScale(parsed)
        try {
            FileOutputStream(config, false).use { out ->
                out.write(normalized.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            Toast.makeText(this, "Failed to save render scale: ${error.message}", Toast.LENGTH_SHORT).show()
            return false
        }

        uiState = uiState.copy(renderScaleInput = normalized)
        if (showToast) {
            Toast.makeText(this, "Render scale saved: $normalized", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
        return true
    }

    private fun renderScaleConfigFile(): File {
        return File(RuntimePaths.stsRoot(this), "render_scale.txt")
    }

    private fun formatRenderScale(value: Float): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun copyUriToFile(uri: Uri, targetFile: File) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create directory: $parent")
        }
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                throw IOException("Unable to open file from picker")
            }
            FileOutputStream(targetFile, false).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun importSaveArchive(uri: Uri): SaveImportResult {
        val stsRoot = RuntimePaths.stsRoot(this)
        if (!stsRoot.exists() && !stsRoot.mkdirs()) {
            throw IOException("Failed to create save root: ${stsRoot.absolutePath}")
        }

        val importableCount = countImportableSaveEntries(uri)
        if (importableCount <= 0) {
            throw IOException("Archive did not contain importable save files")
        }

        val backupLabel = backupExistingSavesToDownloads(stsRoot)
        clearExistingSaveTargets(stsRoot)
        val importedFiles = extractSaveArchive(uri, stsRoot)
        if (importedFiles <= 0) {
            throw IOException("Archive did not contain importable save files")
        }
        return SaveImportResult(
            importedFiles = importedFiles,
            backupLabel = backupLabel
        )
    }

    @Throws(IOException::class)
    private fun countImportableSaveEntries(uri: Uri): Int {
        var importableFiles = 0
        contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty() || entry.isDirectory) {
                        continue
                    }
                    importableFiles++
                }
            }
        }
        return importableFiles
    }

    @Throws(IOException::class)
    private fun backupExistingSavesToDownloads(stsRoot: File): String? {
        val sourceFiles = collectSaveFilesForBackup(stsRoot)
        if (sourceFiles.isEmpty()) {
            return null
        }

        val backupFileName = buildSaveBackupFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backupExistingSavesToScopedDownloads(stsRoot, sourceFiles, backupFileName)
            "Download/$backupFileName"
        } else {
            backupExistingSavesToLegacyDownloads(stsRoot, sourceFiles, backupFileName)
        }
    }

    private fun collectSaveFilesForBackup(stsRoot: File): List<File> {
        val files = ArrayList<File>()
        for (folderName in SAVE_IMPORT_TOP_LEVEL_DIRS) {
            collectRegularFiles(File(stsRoot, folderName), files)
        }
        return files
    }

    private fun collectRegularFiles(root: File, sink: MutableList<File>) {
        if (!root.exists()) {
            return
        }
        if (root.isFile) {
            sink.add(root)
            return
        }
        val children = root.listFiles() ?: return
        for (child in children) {
            collectRegularFiles(child, sink)
        }
    }

    @Throws(IOException::class)
    private fun backupExistingSavesToScopedDownloads(
        stsRoot: File,
        sourceFiles: List<File>,
        backupFileName: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, backupFileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val backupUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create backup archive in Downloads")

        var success = false
        try {
            contentResolver.openOutputStream(backupUri).use { output ->
                if (output == null) {
                    throw IOException("Unable to open backup archive destination")
                }
                writeSaveFilesToZip(output, stsRoot, sourceFiles)
            }
            success = true
        } finally {
            if (success) {
                val pendingValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                contentResolver.update(backupUri, pendingValues, null, null)
            } else {
                contentResolver.delete(backupUri, null, null)
            }
        }
    }

    @Suppress("DEPRECATION")
    @Throws(IOException::class)
    private fun backupExistingSavesToLegacyDownloads(
        stsRoot: File,
        sourceFiles: List<File>,
        backupFileName: String
    ): String {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("Downloads directory is unavailable")
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
            throw IOException("Failed to create Downloads directory: ${downloadsDir.absolutePath}")
        }

        val backupFile = File(downloadsDir, backupFileName)
        FileOutputStream(backupFile, false).use { output ->
            writeSaveFilesToZip(output, stsRoot, sourceFiles)
        }
        return backupFile.absolutePath
    }

    @Throws(IOException::class)
    private fun writeSaveFilesToZip(output: OutputStream, stsRoot: File, sourceFiles: List<File>) {
        ZipOutputStream(output).use { zipOutput ->
            for (sourceFile in sourceFiles) {
                writeFileToZip(zipOutput, stsRoot, sourceFile)
            }
        }
    }

    private fun buildSaveBackupFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return "sts-saves-backup-${formatter.format(Date())}.zip"
    }

    @Throws(IOException::class)
    private fun clearExistingSaveTargets(stsRoot: File) {
        for (folderName in SAVE_IMPORT_TOP_LEVEL_DIRS) {
            val target = File(stsRoot, folderName)
            if (!target.exists()) {
                continue
            }
            if (!target.deleteRecursively()) {
                throw IOException("Failed to clear old save path: ${target.absolutePath}")
            }
        }
    }

    @Throws(IOException::class)
    private fun extractSaveArchive(uri: Uri, stsRoot: File): Int {
        val rootCanonical = stsRoot.canonicalPath
        var importedFiles = 0

        contentResolver.openInputStream(uri).use { rawInput ->
            if (rawInput == null) {
                throw IOException("Unable to open selected archive")
            }
            java.util.zip.ZipInputStream(rawInput).use { zipInput ->
                val buffer = ByteArray(8192)
                while (true) {
                    val entry = zipInput.nextEntry ?: break
                    val mappedPath = resolveImportableArchivePath(entry.name)
                    if (mappedPath.isNullOrEmpty()) {
                        continue
                    }

                    val output = File(stsRoot, mappedPath)
                    val outputCanonical = output.canonicalPath
                    if (outputCanonical != rootCanonical
                        && !outputCanonical.startsWith("$rootCanonical${File.separator}")
                    ) {
                        throw IOException("Unsafe archive entry: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        if (!output.exists() && !output.mkdirs()) {
                            throw IOException("Failed to create directory: ${output.absolutePath}")
                        }
                        continue
                    }

                    val parent = output.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Failed to create directory: ${parent.absolutePath}")
                    }

                    FileOutputStream(output, false).use { out ->
                        while (true) {
                            val read = zipInput.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                    importedFiles++
                }
            }
        }
        return importedFiles
    }

    private fun resolveImportableArchivePath(rawEntryName: String?): String? {
        val mappedPath = mapArchiveEntryPath(rawEntryName) ?: return null
        val normalizedPath = normalizeImportTargetPath(mappedPath) ?: return null
        if (normalizedPath.equals("desktop-1.0.jar", ignoreCase = true)) {
            return null
        }
        if (normalizedPath.startsWith("__MACOSX/")) {
            return null
        }
        return normalizedPath
    }

    private fun mapArchiveEntryPath(rawEntryName: String?): String? {
        if (rawEntryName == null) {
            return null
        }

        var path = rawEntryName.replace('\\', '/')
        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }

        val filesSts = path.indexOf("files/sts/")
        path = if (filesSts >= 0) {
            path.substring(filesSts + "files/sts/".length)
        } else if (path.startsWith("sts/")) {
            path.substring("sts/".length)
        } else {
            val nestedSts = path.indexOf("/sts/")
            if (nestedSts >= 0) {
                path.substring(nestedSts + "/sts/".length)
            } else {
                stripWrapperFolder(path)
            }
        }

        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty() || path.contains("../")) {
            return null
        }
        return path
    }

    private fun stripWrapperFolder(path: String): String {
        val firstSlash = path.indexOf('/')
        if (firstSlash <= 0 || firstSlash >= path.length - 1) {
            return path
        }
        val first = path.substring(0, firstSlash).lowercase(Locale.ROOT)
        val remainder = path.substring(firstSlash + 1)
        var second = remainder
        val secondSlash = remainder.indexOf('/')
        if (secondSlash > 0) {
            second = remainder.substring(0, secondSlash)
        }
        second = second.lowercase(Locale.ROOT)

        return if (!isLikelySaveTopLevel(first) && isLikelySaveTopLevel(second)) {
            remainder
        } else {
            path
        }
    }

    private fun normalizeImportTargetPath(path: String): String? {
        var normalizedPath = path.replace('\\', '/')
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1)
        }
        if (normalizedPath.isEmpty() || normalizedPath.contains("../")) {
            return null
        }

        val firstSlash = normalizedPath.indexOf('/')
        val folder = if (firstSlash >= 0) {
            normalizedPath.substring(0, firstSlash)
        } else {
            normalizedPath
        }
        val rest = if (firstSlash >= 0 && firstSlash < normalizedPath.length - 1) {
            normalizedPath.substring(firstSlash + 1)
        } else {
            ""
        }

        val mappedFolder = when (folder.lowercase(Locale.ROOT)) {
            "preferences", "perferences", "betapreferences", "betaperferences" -> "betaPreferences"
            "multiple", "multiplayer" -> "multiplayer"
            else -> folder
        }
        return if (rest.isEmpty()) {
            mappedFolder
        } else {
            "$mappedFolder/$rest"
        }
    }

    private fun isLikelySaveTopLevel(folder: String): Boolean {
        return folder == "betapreferences"
            || folder == "preferences"
            || folder == "perferences"
            || folder == "betaperferences"
            || folder == "saves"
            || folder == "sendtodevs"
            || folder == "runs"
            || folder == "metrics"
            || folder == "home"
            || folder == "multiplayer"
            || folder == "multiple"
    }

    private fun readBackBehaviorSelection(): Boolean {
        return getSharedPreferences(PREF_NAME_LAUNCHER, MODE_PRIVATE)
            .getBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, true)
    }

    private fun saveBackBehaviorSelection(immediateExit: Boolean) {
        getSharedPreferences(PREF_NAME_LAUNCHER, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_BACK_IMMEDIATE_EXIT, immediateExit)
            .apply()
    }

    private fun readTargetFpsSelection(): Int {
        val stored = getSharedPreferences(PREF_NAME_LAUNCHER, MODE_PRIVATE)
            .getInt(PREF_KEY_TARGET_FPS, DEFAULT_TARGET_FPS)
        return normalizeTargetFps(stored)
    }

    private fun saveTargetFpsSelection(targetFps: Int) {
        getSharedPreferences(PREF_NAME_LAUNCHER, MODE_PRIVATE)
            .edit()
            .putInt(PREF_KEY_TARGET_FPS, normalizeTargetFps(targetFps))
            .apply()
    }

    private fun normalizeTargetFps(targetFps: Int): Int {
        return if (TARGET_FPS_OPTIONS.contains(targetFps)) {
            targetFps
        } else {
            DEFAULT_TARGET_FPS
        }
    }

    private fun buildLogPathText(): String {
        return "Log: ${RuntimePaths.latestLog(this).absolutePath}\n" +
            "VM: ${File(RuntimePaths.stsRoot(this), "jvm_output.log").absolutePath}\n" +
            "Crash: ${File(RuntimePaths.stsRoot(this), "hs_err_pid*.log").absolutePath}"
    }
}
