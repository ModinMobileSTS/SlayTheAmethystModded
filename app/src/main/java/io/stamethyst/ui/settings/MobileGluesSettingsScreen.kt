package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.stamethyst.backend.render.MobileGluesAngleDepthClearFixMode
import io.stamethyst.backend.render.MobileGluesAnglePolicy
import io.stamethyst.backend.render.MobileGluesCustomGlVersion
import io.stamethyst.backend.render.MobileGluesFsr1QualityPreset
import io.stamethyst.backend.render.MobileGluesGlslCacheSizePreset
import io.stamethyst.backend.render.MobileGluesMultidrawMode
import io.stamethyst.backend.render.MobileGluesNoErrorPolicy
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherMobileGluesSettingsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
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
                    text = "这里的选项只在 MobileGlues 图形后端下生效。遇到华为 / Maleoon 这类设备的 shader 编译失败或进地牢闪退时，优先尝试“ANGLE 强制开启 + Multidraw 强制 DrawElements”。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

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

            Text(
                text = "当前有效图形后端：${uiState.effectiveRendererBackend.displayName}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
