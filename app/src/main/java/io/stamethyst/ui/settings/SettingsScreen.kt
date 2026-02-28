package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.stamethyst.LauncherIcon
import io.stamethyst.R
import io.stamethyst.backend.render.RendererBackend
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current!!
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        if (uiState.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            uiState.busyMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Button(
            onClick = viewModel::onImportJar,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导入 desktop-1.0.jar")
        }

        Button(
            onClick = viewModel::onImportMods,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导入模组")
        }

        Button(
            onClick = viewModel::onImportSaves,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导入存档")
        }

        Button(
            onClick = viewModel::onExportSaves,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导出存档")
        }

        Button(
            onClick = { viewModel.onShareCrashReport(activity) },
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.sts_share_crash_report))
        }

        HorizontalDivider()

        Text(text = "渲染设置", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = uiState.renderScaleInput,
            onValueChange = viewModel::onRenderScaleInputChange,
            enabled = !uiState.busy,
            label = { Text("内部渲染比例 (0.50 - 1.00)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { viewModel.onSaveRenderScale(activity) },
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存渲染比例")
        }

        Text(text = "刷新率上限", style = MaterialTheme.typography.bodyMedium)
        uiState.targetFpsOptions.forEach { fps ->
            TargetFpsOptionRow(
                fps = fps,
                selected = uiState.selectedTargetFps == fps,
                enabled = !uiState.busy,
                onSelect = { selectedFps -> viewModel.onTargetFpsSelected(activity, selectedFps) }
            )
        }

        Text(text = "JVM 堆上限", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${uiState.selectedJvmHeapMaxMb} MB",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = uiState.selectedJvmHeapMaxMb.toFloat(),
            onValueChange = { value ->
                viewModel.onJvmHeapMaxSelected(activity, value.roundToInt())
            },
            valueRange = uiState.jvmHeapMinMb.toFloat()..uiState.jvmHeapMaxMb.toFloat(),
            steps = ((uiState.jvmHeapMaxMb - uiState.jvmHeapMinMb) / uiState.jvmHeapStepMb - 1)
                .coerceAtLeast(0),
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "如果遇到黑屏等问题，可以尝试提高这个值，但过高可能会导致无法进入游戏的问题",
            style = MaterialTheme.typography.bodySmall
        )

        Text(text = stringResource(R.string.renderer_backend_label), style = MaterialTheme.typography.bodyMedium)
        RendererBackend.entries.forEach { backend ->
            RendererOptionRow(
                backend = backend,
                label = backend.selectorLabel(),
                selected = uiState.selectedRenderer == backend,
                enabled = !uiState.busy,
                onSelect = { selectedBackend -> viewModel.onRendererSelected(activity, selectedBackend) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = uiState.backImmediateExit,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onBackBehaviorChanged(activity, enabled) }
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = uiState.manualDismissBootOverlay,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onManualDismissBootOverlayChanged(activity, enabled) }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (uiState.manualDismissBootOverlay) {
                    "加载遮幕：手动关闭"
                } else {
                    "加载遮幕：自动关闭"
                }
            )
        }
        Text(
            text = "启用后，启动时加载遮幕不会自动消失，需要点击遮幕上的按钮手动关闭。",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = uiState.touchscreenEnabled,
                enabled = !uiState.busy,
                onCheckedChange = { enabled -> viewModel.onTouchscreenEnabledChanged(activity, enabled) }
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (uiState.touchscreenEnabled) {
                    "触屏输入：启用"
                } else {
                    "触屏输入：禁用"
                }
            )
        }
        Text(
            text = "同步写入 STSGameplaySettings 的 Touchscreen Enabled。",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        Text(text = stringResource(R.string.compat_settings_title), style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = viewModel::onOpenCompatibility,
            enabled = !uiState.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.compat_settings_open))
        }

        HorizontalDivider()

        Text(text = "启动器图标", style = MaterialTheme.typography.titleMedium)
        LauncherIcon.entries.forEach { icon ->
            LauncherIconOptionRow(
                icon = icon,
                selected = uiState.selectedLauncherIcon == icon,
                enabled = !uiState.busy,
                onSelect = { selectedIcon -> viewModel.onLauncherIconSelected(activity, selectedIcon) }
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
    SettingsEffectsHandler(viewModel = viewModel)
}

@Composable
fun SettingsEffectsHandler(
    viewModel: SettingsScreenViewModel,
) {
    val activity = LocalActivity.current!!
    val navigator = currentNavigator
    val importJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onJarPicked(activity, uri)
    }
    val importModsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onModJarsPicked(activity, uris)
    }
    val importSavesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onSavesArchivePicked(activity, uri)
    }
    val exportSavesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onSavesExportPicked(activity, uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsScreenViewModel.Effect.OpenImportJarPicker -> {
                    importJarLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                }

                SettingsScreenViewModel.Effect.OpenImportModsPicker -> {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                }

                SettingsScreenViewModel.Effect.OpenImportSavesPicker -> {
                    importSavesLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                }

                is SettingsScreenViewModel.Effect.OpenExportSavesPicker -> {
                    exportSavesLauncher.launch(effect.fileName)
                }

                SettingsScreenViewModel.Effect.OpenCompatibility -> {
                    navigator.push(Route.Compatibility)
                }
            }
        }
    }
}

@Composable
private fun RendererOptionRow(
    backend: RendererBackend,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (RendererBackend) -> Unit,
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
    onSelect: (Int) -> Unit,
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
    onSelect: (LauncherIcon) -> Unit,
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
