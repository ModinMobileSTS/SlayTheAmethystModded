package io.stamethyst.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.mods.ModJarSupport
import io.stamethyst.model.ModItemUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModDetailsInfoContent(
    mod: ModItemUi,
    showModFileName: Boolean,
    suggestionContent: @Composable () -> Unit,
    relationsContent: @Composable () -> Unit,
) {
    val name = resolveModDisplayName(mod, showModFileName)
    val authors by produceState<List<String>?>(null, mod.storagePath, mod.version, mod.workshop?.localJarPath) {
        value = null
        value = withContext(Dispatchers.IO) {
            sequenceOf(mod.storagePath, mod.workshop?.localJarPath.orEmpty())
                .filter { it.isNotBlank() }
                .mapNotNull { path -> runCatching { ModJarSupport.readModManifest(File(path)).authors }.getOrNull() }
                .firstOrNull { it.isNotEmpty() }.orEmpty()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("mod_details_section_info"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    ModCardLeadingImage(mod, size = 66.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (authors == null) {
                        Box(Modifier.width(112.dp).height(14.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp))
                            .testTag("mod_details_author_loading"))
                    } else {
                        Text(
                            stringResource(R.string.mod_details_author,
                                authors.orEmpty().joinToString("、").ifBlank { stringResource(R.string.mod_details_unknown_author) }),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ModCardMetadataRow(
                        mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) },
                        formatModFileSize(mod.fileSizeBytes) ?: stringResource(R.string.mod_details_unknown_size),
                    )
                }
            }
            val showsEnableState = mod.showsEnableControl()
            val statusLabel = if (showsEnableState) {
                stringResource(if (mod.enabled) R.string.mod_details_enabled else R.string.mod_details_disabled)
            } else {
                mod.workshop?.let { workshopStateBadgeText(it.state) }
            }
            if (statusLabel != null) {
                val enabled = showsEnableState && mod.enabled
                Surface(
                    color = if (enabled) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(
                            painterResource(if (enabled) R.drawable.ic_check_circle else R.drawable.ic_info_outline),
                            contentDescription = null, modifier = Modifier.size(18.dp),
                        )
                        Text(statusLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            mod.detailsStatusText()?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            suggestionContent()
        }
        ModDetailsDescription(mod)
        relationsContent()
        ModDetailsTechnicalInfo(mod)
    }
}

@Composable
private fun ModDetailsDescription(mod: ModItemUi) {
    var expanded by rememberSaveable(mod.modId, mod.description) { mutableStateOf(false) }
    var overflows by remember(mod.modId, mod.description) { mutableStateOf(false) }
    Column(Modifier.testTag("mod_details_section_description"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ModDetailsSectionTitle(stringResource(R.string.mod_details_description))
        Text(
            mod.description.ifBlank { stringResource(R.string.main_mod_no_description) },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("mod_details_description_expand")) {
                Text(stringResource(if (expanded) R.string.mod_details_collapse else R.string.mod_details_expand))
                Icon(painterResource(R.drawable.ic_expand_more), null,
                    Modifier.size(16.dp).rotate(if (expanded) 180f else 0f))
            }
        }
    }
}

@Composable
internal fun ModDetailsSuggestion(text: String?, read: Boolean, onClick: () -> Unit) {
    if (text.isNullOrBlank()) return
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("mod_details_suggestion"),
        shape = RoundedCornerShape(16.dp),
        color = if (read) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (read) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_error_outline), null, Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(if (read) R.string.mod_details_badge_suggestion else R.string.mod_details_badge_suggestion_unread),
                    style = MaterialTheme.typography.labelLarge)
                Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ModDetailsTechnicalInfo(mod: ModItemUi) {
    var expanded by rememberSaveable(mod.modId, mod.storagePath) { mutableStateOf(false) }
    val expandedLabel = stringResource(if (expanded) R.string.mod_details_collapse else R.string.mod_details_expand)
    val context = LocalContext.current
    val path = mod.detailsStoragePath()
    val pathLabel = stringResource(R.string.mod_details_file_location)
    val dependencies = mod.detailsDependencies()
    Column(Modifier.fillMaxWidth().testTag("mod_details_technical")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = expandedLabel }.testTag("mod_details_technical_toggle")
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_info_outline), null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.mod_details_technical_info), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(painterResource(R.drawable.ic_chevron_right), null,
                Modifier.size(18.dp).rotate(if (expanded) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.testTag("mod_details_technical_content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModDetailsTechnicalValue(stringResource(R.string.mod_details_mod_id), mod.manifestModId.ifBlank { mod.modId })
                ModDetailsTechnicalValue(
                    stringResource(R.string.mod_details_load_priority),
                    mod.effectivePriority?.let { "#$it" } ?: stringResource(R.string.mod_details_priority_default),
                )
                ModDetailsTechnicalValue(
                    stringResource(R.string.mod_details_dependencies),
                    dependencies.joinToString(", ").ifBlank { stringResource(R.string.mod_details_dependencies_none) },
                )
                if (path != null) {
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(pathLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(
                                onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(pathLabel, path))
                                }, modifier = Modifier.testTag("mod_details_copy_path"),
                            ) { Text(stringResource(R.string.mod_details_copy)) }
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                            SelectionContainer {
                                Text(path, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModDetailsTechnicalValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun ModItemUi.detailsDependencies(): List<String> = dependencies.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal fun ModItemUi.detailsStoragePath(): String? = storagePath.takeIf { it.isNotBlank() }
    ?: workshop?.localJarPath?.takeIf { it.isNotBlank() }
