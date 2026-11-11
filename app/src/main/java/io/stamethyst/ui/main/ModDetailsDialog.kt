package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import io.stamethyst.ui.LauncherSwitch as Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import io.stamethyst.ui.LauncherDialog
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun ModDetailsDialog(
    mod: ModItemUi,
    showModFileName: Boolean,
    selectionEnabled: Boolean,
    fileActionsEnabled: Boolean,
    startAtActions: Boolean = false,
    onDismiss: () -> Unit,
    onToggleMod: (Boolean) -> Unit,
    onFavoriteChange: (Boolean) -> Unit,
    onOpenAiEditor: () -> Unit,
    badgesContent: @Composable (onScrollToAi: () -> Unit) -> Unit,
    aiContent: @Composable () -> Unit,
    actionsContent: @Composable () -> Unit,
    onOpenWorkshopDetails: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    deleteEnabled: Boolean = fileActionsEnabled,
) {
    val pager = rememberPagerState(initialPage = if (startAtActions) 0 else 1, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val sectionOffsets = remember { mutableStateMapOf<ModDetailsSection, Int>() }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var descriptionOverflows by remember { mutableStateOf(false) }
    val authors by produceState<List<String>>(emptyList(), mod.storagePath, mod.version, mod.workshop?.localJarPath) {
        value = withContext(Dispatchers.IO) {
            sequenceOf(mod.storagePath, mod.workshop?.localJarPath.orEmpty())
                .filter { it.isNotBlank() }
                .mapNotNull { path -> runCatching { ModJarSupport.readModManifest(File(path)).authors }.getOrNull() }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
        }
    }
    val name = resolveModDisplayName(mod, showModFileName)
    val title = stringResource(R.string.mod_details_title)
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceIn(180.dp, 760.dp)
    LauncherDialog(onDismissRequest = onDismiss) {
        ModDetailsSurface(
            mod = mod,
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("mod_details_dialog")
                .semantics { paneTitle = title },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 22.dp, vertical = 12.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            onDelete?.let { ModDeleteOverflow(deleteEnabled, it) }
            }
            SecondaryTabRow(
                selectedTabIndex = pager.currentPage,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().testTag("mod_details_section_tabs"),
            ) {
                listOf(R.string.mod_manager_tools, R.string.mod_details_tab_info, R.string.mod_details_ai_title).forEachIndexed { index, label ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("mod_manager_tab_$index"),
                    ) {
                        Text(
                            stringResource(label),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.Top) { page ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .testTag("mod_details_scroll").padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (page) {
                    0 -> actionsContent()
                    2 -> aiContent()
                    else -> {
                ModDetailsSectionContent(ModDetailsSection.INFO, sectionOffsets) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ModCardLeadingImage(mod, size = 88.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { heading() })
                            Text(
                                stringResource(R.string.mod_details_author,
                                    authors.joinToString("、").ifBlank { stringResource(R.string.mod_details_unknown_author) }),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ModCardMetadataRow(
                                mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) },
                                formatModFileSize(mod.fileSizeBytes) ?: stringResource(R.string.mod_details_unknown_size),
                            )
                        }
                    }
                    if (mod.showsEnableControl()) {
                        Surface(
                            modifier = Modifier.padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(if (mod.enabled) R.string.mod_details_enabled else R.string.mod_details_disabled),
                                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                ModDetailsSectionContent(ModDetailsSection.DESCRIPTION, sectionOffsets) {
                    ModDetailsSectionTitle(stringResource(R.string.mod_details_description))
                    Text(
                        mod.description.ifBlank { stringResource(R.string.main_mod_no_description) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!descriptionExpanded) descriptionOverflows = it.hasVisualOverflow },
                    )
                    if (descriptionOverflows || descriptionExpanded) {
                        TextButton(onClick = { descriptionExpanded = !descriptionExpanded }) {
                            Text(stringResource(if (descriptionExpanded) R.string.mod_details_collapse else R.string.mod_details_expand))
                        }
                    }
                    if (mod.dependencies.isNotEmpty()) {
                        Text(stringResource(R.string.main_mod_dependencies_format, mod.dependencies.distinct().joinToString()),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    mod.detailsStatusText()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Text("ID: ${mod.modId}\n${mod.storagePath}", style = MaterialTheme.typography.bodySmall)
                Text(if (mod.effectivePriority == null) stringResource(R.string.main_mod_priority_dialog_status_default)
                    else stringResource(R.string.mod_details_effective_priority, mod.effectivePriority),
                    style = MaterialTheme.typography.bodySmall)
                if (!mod.importPatchDetails.isNullOrBlank()) {
                    Text(mod.importPatchDetails, style = MaterialTheme.typography.bodySmall)
                }
                mod.importPatches.forEach { patch ->
                    Text("${patch.name}: ${patch.summary}", style = MaterialTheme.typography.bodySmall)
                }
                ModDetailsSectionTitle(stringResource(R.string.mod_details_badges))
                badgesContent { scope.launch { pager.animateScrollToPage(2) } }
                if (mod.workshop != null) {
                    TextButton(onClick = onOpenWorkshopDetails) {
                        Text(stringResource(R.string.main_mod_open_market_page))
                    }
                }
                    }
                }
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun ModDetailsSectionContent(
    section: ModDetailsSection,
    offsets: MutableMap<ModDetailsSection, Int>,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("mod_details_section_${section.key}")
            .onGloballyPositioned { offsets[section] = it.positionInParent().y.roundToInt().coerceAtLeast(0) },
    ) {
        if (section != ModDetailsSection.INFO) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.testTag("mod_details_divider_${section.key}"),
            )
            Spacer(Modifier.height(18.dp))
        }
        content()
    }
}

@Composable
internal fun ModDetailsSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() })
}

internal fun ModItemUi.showsEnableControl(): Boolean =
    (installed || workshop?.state == WorkshopModState.TexturePackInstalled) && when (workshop?.state) {
        null, WorkshopModState.ImportedPatched, WorkshopModState.UpdateAvailable, WorkshopModState.TexturePackInstalled -> true
        else -> false
    }

/** Successful install messages repeat information already shown by the header, toggle and badges. */
internal fun ModItemUi.detailsStatusText(): String? = when (workshop?.state) {
    null, WorkshopModState.ImportedPatched, WorkshopModState.TexturePackInstalled -> null
    else -> workshop.statusText.trim().takeIf { it.isNotBlank() }
}
