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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sectionOffsets = remember { mutableStateMapOf<ModDetailsSection, Int>() }
    val selectedSection by remember {
        derivedStateOf { resolveModDetailsSection(scroll.value, scroll.maxValue, sectionOffsets) }
    }
    val navigateToSection: (ModDetailsSection) -> Unit = { section ->
        scope.launch {
            val offset = snapshotFlow { sectionOffsets[section] }.filterNotNull().first()
            scroll.animateScrollTo(if (section == ModDetailsSection.INFO) 0 else offset)
        }
    }
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
    LaunchedEffect(startAtActions) {
        if (startAtActions) {
            val offset = snapshotFlow { sectionOffsets[ModDetailsSection.ACTIONS] }.filterNotNull().first()
            scroll.animateScrollTo(offset)
        }
    }
    val name = resolveModDisplayName(mod, showModFileName)
    val title = stringResource(R.string.mod_details_title)
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceIn(180.dp, 760.dp)
    LauncherDialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).testTag("mod_details_dialog")
                .semantics { paneTitle = title },
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedSection.ordinal,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().testTag("mod_details_section_tabs"),
            ) {
                ModDetailsSection.entries.forEach { section ->
                    Tab(
                        selected = selectedSection == section,
                        onClick = { navigateToSection(section) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("mod_details_tab_${section.key}"),
                    ) {
                        Text(
                            stringResource(section.labelRes),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                    .testTag("mod_details_scroll").padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
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
                                val toggleDescription = stringResource(R.string.mod_details_toggle, name)
                                Switch(mod.enabled, onCheckedChange = onToggleMod, enabled = selectionEnabled,
                                    modifier = Modifier.semantics { contentDescription = toggleDescription })
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
                ModDetailsSectionContent(ModDetailsSection.BADGES, sectionOffsets) {
                    ModDetailsSectionTitle(stringResource(R.string.mod_details_badges))
                    Spacer(Modifier.height(8.dp))
                    badgesContent { navigateToSection(ModDetailsSection.AI) }
                }
                ModDetailsSectionContent(ModDetailsSection.AI, sectionOffsets) {
                    aiContent()
                }
                ModDetailsSectionContent(ModDetailsSection.ACTIONS, sectionOffsets) {
                    ModDetailsSectionTitle(stringResource(R.string.main_mod_actions_title))
                    Spacer(Modifier.height(12.dp))
                    actionsContent()
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconToggleButton(checked = mod.favorite, onCheckedChange = onFavoriteChange, enabled = fileActionsEnabled) {
                    Icon(painterResource(R.drawable.ic_favorite_heart),
                        stringResource(if (mod.favorite) R.string.main_mod_favorite_remove else R.string.main_mod_favorite_add),
                        tint = if (mod.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onOpenAiEditor, enabled = fileActionsEnabled, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_code), null, Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(R.string.mod_details_ai_edit))
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
