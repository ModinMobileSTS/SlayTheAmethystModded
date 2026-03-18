package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.backend.render.MobileGluesPreset
import io.stamethyst.backend.render.MobileGluesSettings
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMobileGluesSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    var showWarningDialog by rememberSaveable { mutableStateOf(true) }
    var showCustomSettings by rememberSaveable { mutableStateOf(false) }
    val currentSettings = currentMobileGluesSettings(uiState)
    val exactPreset = MobileGluesPreset.entries.firstOrNull { it.settings == currentSettings }
    val nearestPreset = exactPreset ?: MobileGluesPreset.closestTo(currentSettings)
    var presetSliderValue by rememberSaveable { mutableFloatStateOf(nearestPreset.ordinal.toFloat()) }
    val selectedPreset = MobileGluesPreset.fromSliderIndex(presetSliderValue.roundToInt())

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(
        uiState.mobileGluesAnglePolicy,
        uiState.mobileGluesNoErrorPolicy,
        uiState.mobileGluesMultidrawMode,
        uiState.mobileGluesExtComputeShaderEnabled,
        uiState.mobileGluesExtTimerQueryEnabled,
        uiState.mobileGluesExtDirectStateAccessEnabled,
        uiState.mobileGluesGlslCacheSizePreset,
        uiState.mobileGluesAngleDepthClearFixMode,
        uiState.mobileGluesCustomGlVersion,
        uiState.mobileGluesFsr1QualityPreset
    ) {
        presetSliderValue = nearestPreset.ordinal.toFloat()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MobileGlues 设置") },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsBusyIndicator(uiState = uiState)

            SettingsSectionCard(title = "说明") {
                Text(
                    text = "这里的选项只在 MobileGlues 图形后端下生效。调整该界面的任何选项都有可能造成游戏的渲染问题，更改时确保你知道你自己在做什么！",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SettingsSectionCard(title = "MobileGlues 参数预设") {
                Text(
                    text = if (exactPreset != null) {
                        "当前档位：${exactPreset.title}"
                    } else {
                        "当前配置：自定义（最接近 ${nearestPreset.title}）"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "左侧更偏性能，右侧更偏兼容。但是，不一定兼容档拉满了兼容性就一定好，可能兼容策略又会触发新的问题，建议每个档位都可以试试，找到最适合自己的。",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "性能优先",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "兼容性优先",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Slider(
                    value = presetSliderValue,
                    onValueChange = { presetSliderValue = it },
                    onValueChangeFinished = {
                        viewModel.onApplyMobileGluesPreset(
                            activity,
                            MobileGluesPreset.fromSliderIndex(presetSliderValue.roundToInt())
                        )
                    },
                    valueRange = 0f..MobileGluesPreset.entries.lastIndex.toFloat(),
                    steps = (MobileGluesPreset.entries.size - 2).coerceAtLeast(0),
                    enabled = !uiState.busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "滑条当前位置：${MobileGluesPreset.fromSliderIndex(presetSliderValue.roundToInt()).title}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${selectedPreset.shortLabel} | ${selectedPreset.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = selectedPreset.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            SettingsSectionCard(title = "当前参数") {
                currentMobileGluesParameterLines(currentSettings).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                SwitchSettingRow(
                    checked = showCustomSettings,
                    enabled = !uiState.busy,
                    enabledText = "自定义设置：开启",
                    disabledText = "自定义设置：关闭",
                    description = "开启后才显示所有 MobileGlues 专项参数；关闭时仅保留四档预设和当前参数摘要。",
                    onCheckedChange = { showCustomSettings = it }
                )
                TextButton(
                    onClick = { viewModel.onResetMobileGluesSettings(activity) },
                    enabled = !uiState.busy
                ) {
                    Text("回到默认设置")
                }
            }

            if (showCustomSettings) {
                SettingsSectionCard(title = "驱动与绘制") {
                    SettingsDropdownField(
                        label = "ANGLE 策略",
                        valueText = uiState.mobileGluesAnglePolicy.displayName,
                        enabled = !uiState.busy,
                        supportingText = "控制是否走 ANGLE。Mate 70 / Maleoon 建议先试“强制开启”。",
                        options = MobileGluesAnglePolicy.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = { viewModel.onMobileGluesAnglePolicyChanged(activity, it) }
                    )
                    SettingsDropdownField(
                        label = "Multidraw 模式",
                        valueText = uiState.mobileGluesMultidrawMode.displayName,
                        enabled = !uiState.busy,
                        supportingText = "如果 Auto 会选到更激进路径，可改成“强制 DrawElements”。",
                        options = MobileGluesMultidrawMode.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = { viewModel.onMobileGluesMultidrawModeChanged(activity, it) }
                    )
                    SettingsDropdownField(
                        label = "错误忽略策略",
                        valueText = uiState.mobileGluesNoErrorPolicy.displayName,
                        enabled = !uiState.busy,
                        supportingText = "越激进越容易掩盖真实图形问题。",
                        options = MobileGluesNoErrorPolicy.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = { viewModel.onMobileGluesNoErrorPolicyChanged(activity, it) }
                    )
                    SettingsDropdownField(
                        label = "自定义 OpenGL 版本",
                        valueText = uiState.mobileGluesCustomGlVersion.displayName,
                        enabled = !uiState.busy,
                        supportingText = "用于强制收敛或放宽桌面 GL 能力范围。",
                        options = MobileGluesCustomGlVersion.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = { viewModel.onMobileGluesCustomGlVersionChanged(activity, it) }
                    )
                }

                SettingsSectionCard(title = "扩展与兼容") {
                    SwitchSettingRow(
                        checked = uiState.mobileGluesExtComputeShaderEnabled,
                        enabled = !uiState.busy,
                        enabledText = "Compute Shader 扩展：启用",
                        disabledText = "Compute Shader 扩展：禁用",
                        description = "实验性较强，部分 shader 或模组可能因此走到更激进的路径。",
                        onCheckedChange = {
                            viewModel.onMobileGluesExtComputeShaderChanged(activity, it)
                        }
                    )
                    SwitchSettingRow(
                        checked = uiState.mobileGluesExtTimerQueryEnabled,
                        enabled = !uiState.busy,
                        enabledText = "Timer Query 扩展：启用",
                        disabledText = "Timer Query 扩展：禁用",
                        description = "通常不是必要项，遇到驱动问题时建议保持关闭。",
                        onCheckedChange = {
                            viewModel.onMobileGluesExtTimerQueryChanged(activity, it)
                        }
                    )
                    SwitchSettingRow(
                        checked = uiState.mobileGluesExtDirectStateAccessEnabled,
                        enabled = !uiState.busy,
                        enabledText = "Direct State Access：启用",
                        disabledText = "Direct State Access：禁用",
                        description = "为部分桌面 GL 路径提供更现代的资源访问方式。",
                        onCheckedChange = {
                            viewModel.onMobileGluesExtDirectStateAccessChanged(activity, it)
                        }
                    )
                    SettingsDropdownField(
                        label = "ANGLE Depth Clear Workaround",
                        valueText = uiState.mobileGluesAngleDepthClearFixMode.displayName,
                        enabled = !uiState.busy,
                        supportingText = "仅在 ANGLE 路径下有意义。",
                        options = MobileGluesAngleDepthClearFixMode.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = {
                            viewModel.onMobileGluesAngleDepthClearFixModeChanged(activity, it)
                        }
                    )
                }

                SettingsSectionCard(title = "缓存与图像") {
                    SettingsDropdownField(
                        label = "GLSL Cache 大小",
                        valueText = uiState.mobileGluesGlslCacheSizePreset.displayName,
                        enabled = !uiState.busy,
                        supportingText = "只控制预设，不会主动清理旧缓存文件。",
                        options = MobileGluesGlslCacheSizePreset.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = {
                            viewModel.onMobileGluesGlslCacheSizePresetChanged(activity, it)
                        }
                    )
                    SettingsDropdownField(
                        label = "FSR1 质量",
                        valueText = uiState.mobileGluesFsr1QualityPreset.displayName,
                        enabled = !uiState.busy,
                        supportingText = "开启后可能改善性能，也可能带来图像伪影。",
                        options = MobileGluesFsr1QualityPreset.entries,
                        optionLabel = { it.displayName },
                        optionDescription = { it.description },
                        onOptionSelected = {
                            viewModel.onMobileGluesFsr1QualityPresetChanged(activity, it)
                        }
                    )
                }
            }

            Text(
                text = "当前有效图形后端：${uiState.effectiveRendererBackend.displayName}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("高级设置警告") },
            text = {
                Text(
                    text = "此设置仅面向高级用户，更改此界面的选项可能会导致游戏无法启动、崩溃或黑屏的问题。更改里面的选项时，请确定你明白自己在做什么！",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("我知道了")
                }
            }
        )
    }
}

private fun currentMobileGluesSettings(
    uiState: SettingsScreenViewModel.UiState
): MobileGluesSettings {
    return MobileGluesSettings(
        anglePolicy = uiState.mobileGluesAnglePolicy,
        noErrorPolicy = uiState.mobileGluesNoErrorPolicy,
        multidrawMode = uiState.mobileGluesMultidrawMode,
        extComputeShaderEnabled = uiState.mobileGluesExtComputeShaderEnabled,
        extTimerQueryEnabled = uiState.mobileGluesExtTimerQueryEnabled,
        extDirectStateAccessEnabled = uiState.mobileGluesExtDirectStateAccessEnabled,
        glslCacheSizePreset = uiState.mobileGluesGlslCacheSizePreset,
        angleDepthClearFixMode = uiState.mobileGluesAngleDepthClearFixMode,
        customGlVersion = uiState.mobileGluesCustomGlVersion,
        fsr1QualityPreset = uiState.mobileGluesFsr1QualityPreset,
    )
}

private fun currentMobileGluesParameterLines(settings: MobileGluesSettings): List<String> {
    return listOf(
        "ANGLE 策略：${settings.anglePolicy.displayName}",
        "Multidraw 模式：${settings.multidrawMode.displayName}",
        "错误忽略策略：${settings.noErrorPolicy.displayName}",
        "自定义 OpenGL 版本：${settings.customGlVersion.displayName}",
        "Compute Shader 扩展：${if (settings.extComputeShaderEnabled) "启用" else "禁用"}",
        "Timer Query 扩展：${if (settings.extTimerQueryEnabled) "启用" else "禁用"}",
        "Direct State Access：${if (settings.extDirectStateAccessEnabled) "启用" else "禁用"}",
        "ANGLE Depth Clear Workaround：${settings.angleDepthClearFixMode.displayName}",
        "GLSL Cache：${settings.glslCacheSizePreset.displayName}",
        "FSR1：${settings.fsr1QualityPreset.displayName}",
    )
}
