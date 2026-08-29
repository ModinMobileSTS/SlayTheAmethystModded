package io.stamethyst.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.stamethyst.R
import io.stamethyst.backend.steamcloud.SteamAchievementService

private val AchievementBadgeBackground = Color(0xFFFFF8E1)
private val AchievementBadgeContent = Color(0xFF8A6A1F)

@Composable
internal fun SteamAchievementOverviewCard(
    state: MainScreenViewModel.SteamAchievementUi,
    onClick: () -> Unit,
) {
    val signedIn = state.accountName.isNotBlank()
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = signedIn,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (signedIn) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (signedIn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Box(
                    modifier = Modifier.padding(10.dp).size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(21.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_achievement), null, Modifier.size(24.dp))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.main_steam_achievements_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.main_steam_achievements_progress,
                        state.unlockedCount,
                        state.achievements.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.main_steam_achievements_view_and_sync),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SteamAchievementBottomSheetContent(
    state: MainScreenViewModel.SteamAchievementUi,
    debugModeEnabled: Boolean,
    onRefresh: () -> Unit,
    onSetAchievementUnlocked: (String, Boolean) -> Unit,
    onSyncAchievements: () -> Unit,
) {
    val pullToRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter).zIndex(2f),
                isRefreshing = state.loading,
                state = pullToRefreshState,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.main_steam_achievements_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (debugModeEnabled) {
                Text(
                    text = stringResource(R.string.main_steam_achievements_debug_operation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.achievements.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.main_steam_achievements_progress,
                        state.unlockedCount,
                        state.achievements.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.fromCache) {
                Text(
                    text = stringResource(R.string.main_steam_achievements_cache_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (state.errorSummary.isNotBlank()) {
                Text(
                    text = state.errorSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.localUploadCount > 0) {
                Button(onClick = onSyncAchievements, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.main_steam_achievements_sync_local))
                }
            }
            state.achievements.forEachIndexed { index, achievement ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                SteamAchievementRow(
                    achievement = achievement,
                    debugModeEnabled = debugModeEnabled,
                    controlsEnabled = !state.loading,
                    onSetAchievementUnlocked = onSetAchievementUnlocked,
                )
            }
            if (!state.loading && state.achievements.isEmpty() && state.errorSummary.isBlank()) {
                Text(
                    text = stringResource(R.string.main_steam_achievements_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SteamAchievementRow(
    achievement: SteamAchievementService.Achievement,
    debugModeEnabled: Boolean,
    controlsEnabled: Boolean,
    onSetAchievementUnlocked: (String, Boolean) -> Unit,
) {
    val iconResId = if (achievement.unlocked) achievement.unlockedIconResId else achievement.lockedIconResId
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Image(
                painter = painterResource(iconResId),
                contentDescription = null,
                modifier = Modifier.size(58.dp).alpha(if (achievement.unlocked) 1f else 0.45f),
                contentScale = ContentScale.Crop,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(achievement.titleResId), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val description = stringResource(achievement.descriptionResId)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (debugModeEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onSetAchievementUnlocked(achievement.apiName, true) },
                        enabled = controlsEnabled && !achievement.unlocked,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.main_steam_achievements_debug_unlock))
                    }
                    Button(
                        onClick = { onSetAchievementUnlocked(achievement.apiName, false) },
                        enabled = controlsEnabled && achievement.unlocked,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.main_steam_achievements_debug_lock))
                    }
                }
            }
        }
    }
}
