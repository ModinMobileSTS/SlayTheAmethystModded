package io.stamethyst.ui.quickstart

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import io.stamethyst.ui.settings.SettingsScreenViewModel
import kotlinx.coroutines.delay

@Composable
fun QuickStartScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onImportSuccess: () -> Unit
) {
    val activity = requireNotNull(LocalActivity.current)
    val uiState = viewModel.uiState
    var imported by rememberSaveable { mutableStateOf(false) }
    var showJarSourceOverlay by rememberSaveable { mutableStateOf(false) }
    var showJarSourceDialog by rememberSaveable { mutableStateOf(false) }
    val noopInteraction = remember { MutableInteractionSource() }
    val closeJarSourceDialog = {
        showJarSourceOverlay = false
        showJarSourceDialog = false
    }
    val pickJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.onJarPicked(activity, uri) { success ->
                if (success) {
                    imported = true
                    closeJarSourceDialog()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    LaunchedEffect(showJarSourceDialog, imported) {
        if (showJarSourceDialog && !imported) {
            showJarSourceOverlay = false
            delay(100)
            if (showJarSourceDialog && !imported) {
                showJarSourceOverlay = true
            }
        } else {
            showJarSourceOverlay = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AnimatedContent(
                    targetState = imported,
                    label = "quickstart-title"
                ) { isImported ->
                    Text(
                        text = if (isImported) "导入完成，欢迎使用" else "快速开始",
                        style = if (isImported) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                }

                AnimatedContent(
                    targetState = imported,
                    label = "quickstart-subtitle"
                ) { isImported ->
                    Text(
                        text = if (isImported) {
                            ""
                        } else {
                            "首次启动需要先导入 desktop-1.0.jar 才能进入主界面。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                AnimatedVisibility(visible = !imported, label = "quickstart-import-box") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .widthIn(max = 232.dp)
                            .clip(shape = RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .dashedBorder(
                                color = MaterialTheme.colorScheme.outline,
                                cornerRadius = 14.dp,
                                strokeWidth = 1.dp
                            )
                            .clickable(enabled = !uiState.busy) {
                                pickJarLauncher.launch(
                                    arrayOf("application/java-archive", "application/octet-stream", "*/*")
                                )
                            }
                            .animateContentSize()
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "导入 desktop-1.0.jar",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                AnimatedVisibility(visible = !imported, label = "quickstart-jar-source-toggle") {
                    Text(
                        text = "找不到 desktop-1.0.jar？",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable(enabled = !uiState.busy) {
                            showJarSourceDialog = true
                        }
                    )
                }

                if (uiState.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    uiState.busyMessage?.let { busyMessage ->
                        Text(
                            text = busyMessage,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                AnimatedVisibility(visible = imported, label = "quickstart-continue-button") {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(
                                enabled = !uiState.busy,
                                onClick = onImportSuccess
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "→",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !imported && showJarSourceOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            label = "quickstart-jar-source-overlay"
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = closeJarSourceDialog)
            )
        }

        AnimatedVisibility(
            visible = !imported && showJarSourceDialog,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessHigh)) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            label = "quickstart-jar-source-dialog"
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .widthIn(max = 360.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(
                            interactionSource = noopInteraction,
                            indication = null
                        ) { }
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "找不到 desktop-1.0.jar？",
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "desktop-1.0.jar 来自电脑版 Slay the Spire 安装目录。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "获取方法",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = "1. 在电脑上从安装目录拷贝 desktop-1.0.jar",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "2. 常见目录：SteamLibrary/common/SlayTheSpire/desktop-1.0.jar",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "我知道了",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable(onClick = closeJarSourceDialog)
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp,
    strokeWidth: Dp,
    dashLength: Dp = 7.dp,
    gapLength: Dp = 5.dp
): Modifier {
    return drawWithContent {
        drawContent()
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2f
        val cornerRadiusPx = cornerRadius.toPx()

        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
            style = Stroke(
                width = strokePx,
                pathEffect = PathEffect.dashPathEffect(
                    intervals = floatArrayOf(dashLength.toPx(), gapLength.toPx())
                )
            )
        )
    }
}
