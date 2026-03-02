package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.stamethyst.LauncherIcon
import io.stamethyst.R
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        delay(320)
        viewModel.bind(activity)
    }

    LauncherSettingsScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onImportMods = viewModel::onImportMods,
        onImportSaves = viewModel::onImportSaves,
        onExportSaves = viewModel::onExportSaves,
        onShareCrashReport = { viewModel.onShareCrashReport(activity) },
        onRenderScaleInputChange = { value -> viewModel.onRenderScaleInputChange(activity, value) },
        onTargetFpsSelected = { fps -> viewModel.onTargetFpsSelected(activity, fps) },
        onJvmHeapMaxSelected = { value -> viewModel.onJvmHeapMaxSelected(activity, value) },
        onBackBehaviorChanged = { enabled -> viewModel.onBackBehaviorChanged(activity, enabled) },
        onManualDismissBootOverlayChanged = { enabled -> viewModel.onManualDismissBootOverlayChanged(activity, enabled) },
        onShowFloatingMouseWindowChanged = { enabled -> viewModel.onShowFloatingMouseWindowChanged(activity, enabled) },
        onAutoSwitchLeftAfterRightClickChanged = { enabled -> viewModel.onAutoSwitchLeftAfterRightClickChanged(activity, enabled) },
        onLwjglDebugChanged = { enabled -> viewModel.onLwjglDebugChanged(activity, enabled) },
        onTouchscreenEnabledChanged = { enabled -> viewModel.onTouchscreenEnabledChanged(activity, enabled) },
        onOpenCompatibility = viewModel::onOpenCompatibility,
        onLauncherIconSelected = { icon -> viewModel.onLauncherIconSelected(activity, icon) },
    )
    SettingsEffectsHandler(viewModel = viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, heightDp = 2000)
@Composable
private fun LauncherSettingsScreenPreview() {
    LauncherSettingsScreenContent(
        uiState = SettingsScreenViewModel.UiState(
            busy = false,
            renderScaleInput = "1.00",
            selectedTargetFps = 60,
            selectedJvmHeapMaxMb = 1024,
            jvmHeapMinMb = 1024,
            jvmHeapMaxMb = 2048,
            jvmHeapStepMb = 128,
            selectedLauncherIcon = LauncherIcon.AMBER,
            backImmediateExit = true,
            manualDismissBootOverlay = false,
            showFloatingMouseWindow = true,
            autoSwitchLeftAfterRightClick = true,
            lwjglDebugEnabled = false,
            touchscreenEnabled = true,
            statusText = "desktop-1.0.jar: OK\nBaseMod.jar: OK\nStSLib.jar: OK",
            logPathText = "/example/path/to/logs",
            targetFpsOptions = listOf(60, 90, 120, 240),
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherSettingsScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onImportMods: () -> Unit = {},
    onImportSaves: () -> Unit = {},
    onExportSaves: () -> Unit = {},
    onShareCrashReport: () -> Unit = {},
    onRenderScaleInputChange: (String) -> Unit = {},
    onTargetFpsSelected: (Int) -> Unit = {},
    onJvmHeapMaxSelected: (Int) -> Unit = {},
    onBackBehaviorChanged: (Boolean) -> Unit = {},
    onManualDismissBootOverlayChanged: (Boolean) -> Unit = {},
    onShowFloatingMouseWindowChanged: (Boolean) -> Unit = {},
    onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit = {},
    onLwjglDebugChanged: (Boolean) -> Unit = {},
    onTouchscreenEnabledChanged: (Boolean) -> Unit = {},
    onOpenCompatibility: () -> Unit = {},
    onLauncherIconSelected: (LauncherIcon) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsBusyIndicator(uiState = uiState)
            }

            item {
                SettingsSectionCard(title = "资源与文件") {
                    SettingsImportSection(
                        busy = uiState.busy,
                        onImportMods = onImportMods,
                        onImportSaves = onImportSaves,
                        onExportSaves = onExportSaves,
                        onShareCrashReport = onShareCrashReport,
                    )
                }
            }

            item {
                SettingsSectionCard(title = "渲染") {
                    SettingsRenderSection(
                        uiState = uiState,
                        onRenderScaleInputChange = onRenderScaleInputChange,
                        onTargetFpsSelected = onTargetFpsSelected,
                        onJvmHeapMaxSelected = onJvmHeapMaxSelected,
                    )
                }
            }

            item {
                SettingsSectionCard(title = "输入与交互") {
                    SettingsInputSection(
                        uiState = uiState,
                        onBackBehaviorChanged = onBackBehaviorChanged,
                        onManualDismissBootOverlayChanged = onManualDismissBootOverlayChanged,
                        onShowFloatingMouseWindowChanged = onShowFloatingMouseWindowChanged,
                        onAutoSwitchLeftAfterRightClickChanged = onAutoSwitchLeftAfterRightClickChanged,
                        onTouchscreenEnabledChanged = onTouchscreenEnabledChanged,
                    )
                }
            }

            item {
                SettingsSectionCard(title = stringResource(R.string.compat_settings_title)) {
                    SettingsCompatibilitySection(
                        busy = uiState.busy,
                        onOpenCompatibility = onOpenCompatibility,
                    )
                }
            }

            item {
                SettingsSectionCard(title = "启动器图标") {
                    SettingsLauncherIconSection(
                        uiState = uiState,
                        onLauncherIconSelected = onLauncherIconSelected,
                    )
                }
            }

            item {
                SettingsSectionCard(title = "状态与日志") {
                    SettingsStatusSection(
                        uiState = uiState,
                        onLwjglDebugChanged = onLwjglDebugChanged
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsBusyIndicator(
    uiState: SettingsScreenViewModel.UiState
) {
    if (!uiState.busy) {
        return
    }
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    uiState.busyMessage?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun SettingsImportSection(
    busy: Boolean,
    onImportMods: () -> Unit,
    onImportSaves: () -> Unit,
    onExportSaves: () -> Unit,
    onShareCrashReport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsActionListItem(
            title = "导入模组",
            enabled = !busy,
            onClick = onImportMods
        )
        SettingsActionListItem(
            title = "导入存档",
            enabled = !busy,
            onClick = onImportSaves
        )
        SettingsActionListItem(
            title = "导出存档",
            enabled = !busy,
            onClick = onExportSaves
        )
        SettingsActionListItem(
            title = stringResource(R.string.sts_share_crash_report),
            enabled = !busy,
            onClick = onShareCrashReport
        )
    }
}

@Composable
private fun SettingsRenderSection(
    uiState: SettingsScreenViewModel.UiState,
    onRenderScaleInputChange: (String) -> Unit,
    onTargetFpsSelected: (Int) -> Unit,
    onJvmHeapMaxSelected: (Int) -> Unit,
) {
    var heapSliderValue by remember(uiState.selectedJvmHeapMaxMb) {
        mutableFloatStateOf(uiState.selectedJvmHeapMaxMb.toFloat())
    }

    OutlinedTextField(
        value = uiState.renderScaleInput,
        onValueChange = onRenderScaleInputChange,
        enabled = !uiState.busy,
        label = { Text("内部渲染比例 (0.50 - 1.00)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Text(text = "刷新率上限", style = MaterialTheme.typography.bodyMedium)
    uiState.targetFpsOptions.forEach { fps ->
        TargetFpsOptionRow(
            fps = fps,
            selected = uiState.selectedTargetFps == fps,
            enabled = !uiState.busy,
            onSelect = onTargetFpsSelected
        )
    }

    Text(text = "JVM 堆上限", style = MaterialTheme.typography.bodyMedium)
    Text(
        text = "${heapSliderValue.roundToInt()} MB",
        style = MaterialTheme.typography.bodySmall
    )
    Slider(
        value = heapSliderValue,
        onValueChange = { value -> heapSliderValue = value },
        onValueChangeFinished = { onJvmHeapMaxSelected(heapSliderValue.roundToInt()) },
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
}

@Composable
private fun SettingsInputSection(
    uiState: SettingsScreenViewModel.UiState,
    onBackBehaviorChanged: (Boolean) -> Unit,
    onManualDismissBootOverlayChanged: (Boolean) -> Unit,
    onShowFloatingMouseWindowChanged: (Boolean) -> Unit,
    onAutoSwitchLeftAfterRightClickChanged: (Boolean) -> Unit,
    onTouchscreenEnabledChanged: (Boolean) -> Unit,
) {
    SwitchSettingRow(
        checked = uiState.backImmediateExit,
        enabled = !uiState.busy,
        enabledText = "Back 键：立即退出到主界面",
        disabledText = "Back 键：禁用",
        description = "关闭上面的开关后，游戏运行时按 Back 键将不处理。",
        onCheckedChange = onBackBehaviorChanged
    )

    SwitchSettingRow(
        checked = uiState.manualDismissBootOverlay,
        enabled = !uiState.busy,
        enabledText = "加载遮幕：手动关闭",
        disabledText = "加载遮幕：自动关闭",
        description = "启用后，启动时加载遮幕不会自动消失，需要点击遮幕上的按钮手动关闭。",
        onCheckedChange = onManualDismissBootOverlayChanged
    )

    SwitchSettingRow(
        checked = uiState.showFloatingMouseWindow,
        enabled = !uiState.busy,
        enabledText = stringResource(R.string.settings_touch_mouse_floating_window_visible),
        disabledText = stringResource(R.string.settings_touch_mouse_floating_window_hidden),
        description = stringResource(R.string.settings_touch_mouse_floating_window_desc),
        onCheckedChange = onShowFloatingMouseWindowChanged
    )

    SwitchSettingRow(
        checked = uiState.autoSwitchLeftAfterRightClick,
        enabled = !uiState.busy,
        enabledText = "右键后自动切回左键：启用",
        disabledText = "右键后自动切回左键：禁用",
        description = "启用后，触发一次右键后会自动切换回左键模式。",
        onCheckedChange = onAutoSwitchLeftAfterRightClickChanged
    )

    SwitchSettingRow(
        checked = uiState.touchscreenEnabled,
        enabled = !uiState.busy,
        enabledText = "触屏输入：启用",
        disabledText = "触屏输入：禁用",
        description = "同步写入 STSGameplaySettings 的 Touchscreen Enabled。",
        onCheckedChange = onTouchscreenEnabledChanged
    )
}

@Composable
private fun SwitchSettingRow(
    checked: Boolean,
    enabled: Boolean,
    enabledText: String,
    disabledText: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = if (checked) enabledText else disabledText)
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SettingsCompatibilitySection(
    busy: Boolean,
    onOpenCompatibility: () -> Unit,
) {
    SettingsActionListItem(
        title = stringResource(R.string.compat_settings_open),
        enabled = !busy,
        onClick = onOpenCompatibility
    )
}

@Composable
private fun SettingsActionListItem(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(text = title)
        },
        trailingContent = {
            Text(
                text = ">",
                style = MaterialTheme.typography.titleMedium
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
    )
}

@Composable
private fun SettingsLauncherIconSection(
    uiState: SettingsScreenViewModel.UiState,
    onLauncherIconSelected: (LauncherIcon) -> Unit,
) {
    LauncherIcon.entries.forEach { icon ->
        LauncherIconOptionRow(
            icon = icon,
            selected = uiState.selectedLauncherIcon == icon,
            enabled = !uiState.busy,
            onSelect = onLauncherIconSelected
        )
    }
}

@Composable
private fun SettingsStatusSection(
    uiState: SettingsScreenViewModel.UiState,
    onLwjglDebugChanged: (Boolean) -> Unit,
) {
    var showStatusDialog by rememberSaveable { mutableStateOf(false) }
    var showLogDialog by rememberSaveable { mutableStateOf(false) }
    val statusPreview = remember(uiState.statusText) {
        uiState.statusText
            .lineSequence()
            .take(3)
            .joinToString("\n")
    }

    Text(
        text = statusPreview.ifBlank { "状态加载中..." },
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )

    SwitchSettingRow(
        checked = uiState.lwjglDebugEnabled,
        enabled = !uiState.busy,
        enabledText = "LWJGL Debug：启用",
        disabledText = "LWJGL Debug：禁用",
        description = "控制 JVM 启动参数中的 org.lwjgl.util.Debug / DebugLoader / DebugFunctions。",
        onCheckedChange = onLwjglDebugChanged
    )

    HorizontalDivider()

    SettingsActionListItem(
        title = "查看完整状态信息",
        enabled = uiState.statusText.isNotBlank(),
        onClick = { showStatusDialog = true }
    )
    SettingsActionListItem(
        title = "查看日志路径",
        enabled = uiState.logPathText.isNotBlank(),
        onClick = { showLogDialog = true }
    )

    if (showStatusDialog) {
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("状态信息") },
            text = {
                SelectionContainer {
                    Text(
                        text = uiState.statusText.ifBlank { "暂无状态信息" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("日志路径") },
            text = {
                SelectionContainer {
                    Text(
                        text = uiState.logPathText.ifBlank { "暂无日志路径" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
fun SettingsEffectsHandler(
    viewModel: SettingsScreenViewModel,
) {
    val activity = requireNotNull(LocalActivity.current)
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
