package io.stamethyst.ui.feedback

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.stamethyst.backend.feedback.FeedbackCategory
import io.stamethyst.backend.feedback.GameIssueType
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherFeedbackScreen(
    modifier: Modifier = Modifier,
    onSubmissionCompleted: (FeedbackSubmissionNotice) -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val viewModel: FeedbackScreenViewModel = viewModel()
    val uiState = viewModel.uiState

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LauncherFeedbackScreenContent(
        modifier = modifier,
        uiState = uiState,
        onGoBack = navigator::goBack,
        onCategorySelected = viewModel::onCategorySelected,
        onGameIssueTypeSelected = viewModel::onGameIssueTypeSelected,
        onReproducedOnLastRunSelected = viewModel::onReproducedOnLastRunSelected,
        onSuspectUnknownChanged = viewModel::onSuspectUnknownChanged,
        onSuspectedModToggled = viewModel::onSuspectedModToggled,
        onSummaryChanged = viewModel::onSummaryChanged,
        onDetailChanged = viewModel::onDetailChanged,
        onReproductionStepsChanged = viewModel::onReproductionStepsChanged,
        onEmailChanged = viewModel::onEmailChanged,
        onEmailNotificationsEnabledChanged = viewModel::onEmailNotificationsEnabledChanged,
        onAddScreenshots = viewModel::onAddScreenshots,
        onRemoveScreenshot = viewModel::onRemoveScreenshot,
        onSubmit = { viewModel.onSubmit(activity) }
    )
    FeedbackEffectsHandler(
        viewModel = viewModel,
        onSubmissionCompleted = onSubmissionCompleted
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherFeedbackScreenContent(
    modifier: Modifier = Modifier,
    uiState: FeedbackScreenViewModel.UiState,
    onGoBack: () -> Unit = {},
    onCategorySelected: (FeedbackCategory) -> Unit = {},
    onGameIssueTypeSelected: (GameIssueType) -> Unit = {},
    onReproducedOnLastRunSelected: (Boolean) -> Unit = {},
    onSuspectUnknownChanged: (Boolean) -> Unit = {},
    onSuspectedModToggled: (String, Boolean) -> Unit = { _, _ -> },
    onSummaryChanged: (String) -> Unit = {},
    onDetailChanged: (String) -> Unit = {},
    onReproductionStepsChanged: (String) -> Unit = {},
    onEmailChanged: (String) -> Unit = {},
    onEmailNotificationsEnabledChanged: (Boolean) -> Unit = {},
    onAddScreenshots: () -> Unit = {},
    onRemoveScreenshot: (String) -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val isGameBug = uiState.category == FeedbackCategory.GAME_BUG

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("问题反馈") },
                navigationIcon = {
                    IconButton(onClick = onGoBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                if (uiState.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    uiState.busyMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            item {
                FeedbackSectionCard(title = "说明") {
                        Text(
                            text = "这里用于提交启动器 Bug、游戏内 Bug 和功能建议。提交时会自动附带日志、设备信息、启用模组快照，并支持附加截图。",
                            style = MaterialTheme.typography.bodySmall
                        )
                }
            }

            if (!uiState.endpointConfigured) {
                item {
                    FeedbackSectionCard(
                        title = "上传未配置",
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "当前构建没有配置反馈上传地址，表单可以填写，但无法真正提交到云函数。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                FeedbackSectionCard(title = "1. 反馈类型") {
                    FeedbackRadioRow(
                        selected = uiState.category == FeedbackCategory.FEATURE_REQUEST,
                        enabled = !uiState.busy,
                        text = "功能建议",
                        onClick = { onCategorySelected(FeedbackCategory.FEATURE_REQUEST) }
                    )
                    FeedbackRadioRow(
                        selected = uiState.category == FeedbackCategory.LAUNCHER_BUG,
                        enabled = !uiState.busy,
                        text = "启动器 Bug",
                        onClick = { onCategorySelected(FeedbackCategory.LAUNCHER_BUG) }
                    )
                    FeedbackRadioRow(
                        selected = uiState.category == FeedbackCategory.GAME_BUG,
                        enabled = !uiState.busy,
                        text = "游戏内 Bug",
                        onClick = { onCategorySelected(FeedbackCategory.GAME_BUG) }
                    )
                }
            }

            if (isGameBug) {
                item {
                    FeedbackSectionCard(title = "2. 最近一次运行") {
                        FeedbackRadioRow(
                            selected = uiState.reproducedOnLastRun == true,
                            enabled = !uiState.busy,
                            text = "是，这就是最近一次运行复现的问题",
                            onClick = { onReproducedOnLastRunSelected(true) }
                        )
                        FeedbackRadioRow(
                            selected = uiState.reproducedOnLastRun == false,
                            enabled = !uiState.busy,
                            text = "不是，但我仍要继续提交",
                            onClick = { onReproducedOnLastRunSelected(false) }
                        )
                        if (uiState.reproducedOnLastRun == false) {
                            Text(
                                text = "建议先复现一次以便收集更完整日志，但也可以继续提交。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                item {
                    FeedbackSectionCard(title = "3. 怀疑模组") {
                        FeedbackCheckboxRow(
                            checked = uiState.suspectUnknown,
                            enabled = !uiState.busy,
                            title = "不确定",
                            subtitle = "如果无法定位具体模组，请至少勾选这个选项。",
                            onCheckedChange = onSuspectUnknownChanged
                        )
                        if (uiState.availableMods.isEmpty()) {
                            Text(
                                text = "当前没有已启用模组，提交时会只附带日志和环境信息。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            uiState.availableMods.forEach { mod ->
                                val label = buildString {
                                    append(mod.name.ifBlank { mod.manifestModId.ifBlank { mod.modId } })
                                    if (mod.version.isNotBlank()) {
                                        append(" · ").append(mod.version)
                                    }
                                    if (mod.required) {
                                        append(" · 核心")
                                    }
                                }
                                FeedbackCheckboxRow(
                                    checked = uiState.selectedSuspectedModKeys.contains(mod.key),
                                    enabled = !uiState.busy,
                                    title = label,
                                    subtitle = mod.manifestModId.ifBlank { mod.modId.ifBlank { mod.storagePath } },
                                    onCheckedChange = { checked ->
                                        onSuspectedModToggled(mod.key, checked)
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    FeedbackSectionCard(title = "4. 问题表现") {
                        FeedbackRadioRow(
                            selected = uiState.gameIssueType == GameIssueType.PERFORMANCE,
                            enabled = !uiState.busy,
                            text = "卡顿",
                            onClick = { onGameIssueTypeSelected(GameIssueType.PERFORMANCE) }
                        )
                        FeedbackRadioRow(
                            selected = uiState.gameIssueType == GameIssueType.DISPLAY,
                            enabled = !uiState.busy,
                            text = "显示不正常",
                            onClick = { onGameIssueTypeSelected(GameIssueType.DISPLAY) }
                        )
                        FeedbackRadioRow(
                            selected = uiState.gameIssueType == GameIssueType.CRASH,
                            enabled = !uiState.busy,
                            text = "崩溃",
                            onClick = { onGameIssueTypeSelected(GameIssueType.CRASH) }
                        )
                    }
                }
            }

            item {
                FeedbackSectionCard(title = if (isGameBug) "5. 问题说明" else "2. 问题说明") {
                    OutlinedTextField(
                        value = uiState.summary,
                        onValueChange = onSummaryChanged,
                        enabled = !uiState.busy,
                        label = { Text("一句话总结") },
                        placeholder = { Text(summaryPlaceholder(uiState)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(1.dp))
                    OutlinedTextField(
                        value = uiState.detail,
                        onValueChange = onDetailChanged,
                        enabled = !uiState.busy,
                        label = { Text(detailLabel(uiState)) },
                        placeholder = { Text(detailPlaceholder(uiState)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4
                    )
                    OutlinedTextField(
                        value = uiState.reproductionSteps,
                        onValueChange = onReproductionStepsChanged,
                        enabled = !uiState.busy,
                        label = { Text(reproductionLabel(uiState)) },
                        placeholder = { Text(reproductionPlaceholder(uiState)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            }

            item {
                FeedbackSectionCard(title = if (isGameBug) "6. 截图" else "3. 截图") {
                    Text(
                        text = "截图为可选，最多 4 张。建议优先附上异常画面、报错弹窗或卡住时的界面。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = onAddScreenshots,
                        enabled = !uiState.busy && uiState.screenshots.size < 4
                    ) {
                        Text("添加截图")
                    }
                    if (uiState.screenshots.isEmpty()) {
                        Text(
                            text = "当前未附加截图。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        uiState.screenshots.forEach { screenshot ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = screenshot.displayName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = screenshot.sizeLabel,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                TextButton(
                                    onClick = { onRemoveScreenshot(screenshot.id) },
                                    enabled = !uiState.busy
                                ) {
                                    Text("移除")
                                }
                            }
                        }
                    }
                }
            }

            item {
                FeedbackSectionCard(title = if (isGameBug) "7. 邮箱通知（可选）" else "4. 邮箱通知（可选）") {
                    FeedbackCheckboxRow(
                        checked = uiState.emailNotificationsEnabled,
                        enabled = !uiState.busy,
                        title = "勾选后，在您的反馈有进展时向您发送邮件消息。",
                        subtitle = "邮件地址不会公开显示在 GitHub Issue 中。",
                        onCheckedChange = onEmailNotificationsEnabledChanged
                    )
                    OutlinedTextField(
                        value = uiState.email,
                        onValueChange = onEmailChanged,
                        enabled = !uiState.busy,
                        label = { Text("通知邮箱地址") },
                        placeholder = { Text("name@example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            item {
                Button(
                    onClick = onSubmit,
                    enabled = !uiState.busy && uiState.endpointConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("提交反馈")
                }
            }
        }
    }
}

@Composable
private fun FeedbackSectionCard(
    title: String,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun FeedbackRadioRow(
    selected: Boolean,
    enabled: Boolean,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text)
    }
}

@Composable
private fun FeedbackCheckboxRow(
    checked: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FeedbackEffectsHandler(
    viewModel: FeedbackScreenViewModel,
    onSubmissionCompleted: (FeedbackSubmissionNotice) -> Unit = {},
) {
    val activity = requireNotNull(LocalActivity.current)
    val screenshotPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.onScreenshotUrisPicked(activity, uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                FeedbackScreenViewModel.Effect.OpenScreenshotPicker -> {
                    screenshotPicker.launch(arrayOf("image/*"))
                }
                is FeedbackScreenViewModel.Effect.SubmissionCompleted -> {
                    onSubmissionCompleted(effect.notice)
                }
            }
        }
    }
}

private fun summaryPlaceholder(uiState: FeedbackScreenViewModel.UiState): String {
    return when (uiState.category) {
        FeedbackCategory.FEATURE_REQUEST -> "例如：希望反馈入口能自动记住上次填写的邮箱"
        FeedbackCategory.LAUNCHER_BUG -> "例如：导入某个模组后启动器直接闪退"
        FeedbackCategory.GAME_BUG -> when (uiState.gameIssueType) {
            GameIssueType.PERFORMANCE -> "例如：进入战斗后明显掉帧"
            GameIssueType.DISPLAY -> "例如：主菜单出现大面积黑块"
            GameIssueType.CRASH -> "例如：打出某张牌后游戏黑屏闪退"
            null -> "例如：进入游戏后出现异常表现"
        }
        null -> "先选择反馈类型，再填写总结"
    }
}

private fun detailLabel(uiState: FeedbackScreenViewModel.UiState): String {
    return when (uiState.category) {
        FeedbackCategory.FEATURE_REQUEST -> "功能需求说明"
        FeedbackCategory.LAUNCHER_BUG -> "启动器问题描述"
        FeedbackCategory.GAME_BUG -> "游戏内问题描述"
        null -> "详细描述"
    }
}

private fun detailPlaceholder(uiState: FeedbackScreenViewModel.UiState): String {
    return when (uiState.category) {
        FeedbackCategory.FEATURE_REQUEST -> "说明你为什么需要这个功能，它解决什么痛点。"
        FeedbackCategory.LAUNCHER_BUG -> "说明你在启动器里做了什么、看到了什么、哪里不符合预期。"
        FeedbackCategory.GAME_BUG -> when (uiState.gameIssueType) {
            GameIssueType.PERFORMANCE -> "例如：使用卡包大师进入战斗后持续卡顿。"
            GameIssueType.DISPLAY -> "例如：使用命运之戒进入主菜单后出现大量黑块。"
            GameIssueType.CRASH -> "例如：使用某模组角色打出某张牌后黑屏闪退。"
            null -> "简要描述你在游戏里遇到的问题。"
        }
        null -> "先选择反馈类型，再填写详细描述"
    }
}

private fun reproductionLabel(uiState: FeedbackScreenViewModel.UiState): String {
    return if (uiState.category == FeedbackCategory.FEATURE_REQUEST) {
        "使用场景（可选）"
    } else {
        "复现步骤"
    }
}

private fun reproductionPlaceholder(uiState: FeedbackScreenViewModel.UiState): String {
    return if (uiState.category == FeedbackCategory.FEATURE_REQUEST) {
        "例如：经常切换不同模组组合时，希望减少重复填写。"
    } else {
        "按顺序写出你做了哪些操作，越具体越好。"
    }
}
