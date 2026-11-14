package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.ui.LauncherDialog
import kotlinx.coroutines.launch

@Composable
internal fun ModDetailsDialog(
    mod: ModItemUi,
    showModFileName: Boolean,
    fileActionsEnabled: Boolean,
    startAtActions: Boolean = false,
    onDismiss: () -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    badgesContent: @Composable (onScrollToAi: () -> Unit) -> Unit,
    aiContent: @Composable () -> Unit,
    actionsContent: @Composable () -> Unit,
    suggestionContent: @Composable () -> Unit = {},
) {
    val pager = rememberPagerState(initialPage = if (startAtActions) 0 else 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.mod_details_title)
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceIn(180.dp, 760.dp)
    LauncherDialog(onDismissRequest = onDismiss) {
        ModDetailsSurface(
            mod = mod,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("mod_details_dialog")
                .semantics { paneTitle = title },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconToggleButton(
                    checked = mod.favorite,
                    onCheckedChange = onFavoriteChange,
                    enabled = fileActionsEnabled,
                    modifier = Modifier.testTag("mod_details_favorite"),
                ) {
                    Icon(
                        painterResource(if (mod.favorite) R.drawable.ic_favorite_heart else R.drawable.ic_favorite_outline),
                        stringResource(if (mod.favorite) R.string.main_mod_favorite_remove else R.string.main_mod_favorite_add),
                        tint = (if (mod.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            .copy(alpha = if (fileActionsEnabled) 1f else 0.38f),
                    )
                }
            }
            SecondaryTabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().testTag("mod_details_section_tabs"),
            ) {
                listOf(R.string.mod_manager_tools, R.string.mod_details_tab_info, R.string.mod_details_ai_title)
                    .forEachIndexed { index, label ->
                        Tab(
                            selected = pager.currentPage == index,
                            onClick = { scope.launch { pager.animateScrollToPage(index) } },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("mod_manager_tab_$index"),
                        ) {
                            Text(
                                stringResource(label), modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
            }
            HorizontalPager(
                state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top,
            ) { page ->
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .testTag("mod_details_scroll_$page").padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (page) {
                        0 -> actionsContent()
                        2 -> aiContent()
                        else -> ModDetailsInfoContent(
                            mod = mod,
                            showModFileName = showModFileName,
                            suggestionContent = suggestionContent,
                            relationsContent = { badgesContent { scope.launch { pager.animateScrollToPage(2) } } },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                FilledTonalButton(onClick = onDismiss) { Text(stringResource(R.string.mod_details_done)) }
            }
        }
    }
}

@Composable
internal fun ModDetailsSectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

/** A downloaded jar that still needs patch/import takes the card header's switch slot. */
internal fun ModItemUi.showsInstallControl(): Boolean =
    workshop?.state == WorkshopModState.ImportedUnpatched || workshop?.state == WorkshopModState.UpdatePendingInstall

internal fun ModItemUi.showsEnableControl(): Boolean =
    (installed || workshop?.state == WorkshopModState.TexturePackInstalled) && when (workshop?.state) {
        null, WorkshopModState.ImportedPatched, WorkshopModState.UpdateAvailable, WorkshopModState.TexturePackInstalled -> true
        else -> false
    }

/** Successful install messages repeat information already shown by the header and status. */
internal fun ModItemUi.detailsStatusText(): String? = when (workshop?.state) {
    null, WorkshopModState.ImportedPatched, WorkshopModState.TexturePackInstalled -> null
    else -> workshop.statusText.trim().takeIf { it.isNotBlank() }
}
