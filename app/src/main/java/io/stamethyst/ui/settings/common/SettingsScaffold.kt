package io.stamethyst.ui.settings.common

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.R
import io.stamethyst.ui.FloatingGlassHeader
import io.stamethyst.ui.Icons
import io.stamethyst.ui.feedback.FeedbackSubmissionNotice
import io.stamethyst.ui.icon.ArrowBack


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRouteScaffold(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    spec: SettingsRouteSpec,
    showBackButton: Boolean = true,
    onGoBack: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        title = stringResource(spec.titleResId),
        subtitle = stringResource(spec.subtitleResId),
        iconResId = spec.iconResId,
        showBackButton = showBackButton,
        onGoBack = onGoBack,
        content = content,
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRouteScaffold(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    title: String,
    subtitle: String,
    @DrawableRes iconResId: Int,
    showBackButton: Boolean = true,
    onGoBack: () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val blockingInteractionLocked = uiState.busyOperation.locksInteraction(uiState.busy)
    val headerHazeState = rememberHazeState()
    val headerContentTopInset = 88.dp + 16.dp
    val bottomContentInset = if (showBackButton) 32.dp else 132.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(state = headerHazeState),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 18.dp,
                end = 16.dp,
                bottom = bottomContentInset,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(headerContentTopInset))
            }
            item {
                SettingsBusyIndicator(uiState = uiState)
            }
            content()
        }

        FloatingGlassHeader(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            hazeState = headerHazeState,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            SettingsHeaderPinnedContent(
                title = title,
                subtitle = subtitle,
                iconResId = iconResId,
                showBackButton = showBackButton,
                enabled = !blockingInteractionLocked,
                onGoBack = onGoBack,
            )
        }
    }
}



@Composable
internal fun SettingsCategoryCard(
    @DrawableRes iconResId: Int,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconResId),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}



@Composable
internal fun SettingsFeedbackSubmissionNoticeDialog(
    notice: FeedbackSubmissionNotice?,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val visibleNotice = notice ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(visibleNotice.title) },
        text = { Text(visibleNotice.message) },
        confirmButton = {
            if (!visibleNotice.issueUrl.isNullOrBlank()) {
                TextButton(
                    onClick = {
                        onDismiss()
                        uriHandler.openUri(visibleNotice.issueUrl)
                    }
                ) {
                    Text(stringResource(R.string.common_action_open_issue))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_action_acknowledge))
                }
            }
        },
        dismissButton = {
            if (!visibleNotice.issueUrl.isNullOrBlank()) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_action_acknowledge))
                }
            }
        },
    )
}



@Composable
internal fun SettingsHeaderPinnedContent(
    title: String,
    subtitle: String,
    @DrawableRes iconResId: Int,
    showBackButton: Boolean,
    enabled: Boolean,
    onGoBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showBackButton) {
            HapticIconButton(
                onClick = onGoBack,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.ArrowBack,
                    contentDescription = stringResource(R.string.common_content_desc_back),
                )
            }
        }
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconResId),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

