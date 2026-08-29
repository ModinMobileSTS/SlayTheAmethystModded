package io.stamethyst.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.model.ModItemUi
import io.stamethyst.model.WorkshopModState
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ModCardBodyContent(
    mod: ModItemUi,
    isExpanded: Boolean,
    showModFileName: Boolean,
    showActionsButton: Boolean,
    actionsEnabled: Boolean,
    onActionsClick: () -> Unit,
    modSuggestionText: String? = null,
    suggestionUnread: Boolean = false,
    suggestionBadgeEnabled: Boolean = true,
    onSuggestionClick: () -> Unit = {},
    importPatchBadgeEnabled: Boolean = true,
    onImportPatchClick: () -> Unit = {},
    associationBadge: ModAssociationBadgeUi? = null,
    associationBadgeEnabled: Boolean = true,
    onAssociationBadgeClick: () -> Unit = {},
    updateBadgeEnabled: Boolean = true,
    onUpdateBadgeClick: () -> Unit = {},
    onOpenWorkshopDetails: (ModItemUi) -> Unit = {},
    workshopBadgeEnabled: Boolean = true,
    headerLeading: @Composable RowScope.() -> Unit = {},
    headerTrailing: @Composable RowScope.() -> Unit
) {
    val resolvedName = resolveModDisplayName(mod, showModFileName = showModFileName)
    val resolvedModId = mod.manifestModId.ifBlank { mod.modId }
    val resolvedVersion = mod.version.ifBlank { stringResource(R.string.main_mod_unknown_version) }
    val resolvedFileSize = formatModFileSize(mod.fileSizeBytes)
    val resolvedDescription = mod.description.ifBlank { stringResource(R.string.main_mod_no_description) }
    val dependencies = mod.dependencies
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    Row(verticalAlignment = Alignment.CenterVertically) {
        headerLeading()
        ModCardLeadingImage(mod)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = resolvedName,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.main_mod_modid_format, resolvedModId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            ModCardBadges(
                mod = mod,
                modSuggestionText = modSuggestionText,
                suggestionUnread = suggestionUnread,
                suggestionBadgeEnabled = suggestionBadgeEnabled,
                onSuggestionClick = onSuggestionClick,
                importPatchBadgeEnabled = importPatchBadgeEnabled,
                onImportPatchClick = onImportPatchClick,
                associationBadge = associationBadge,
                associationBadgeEnabled = associationBadgeEnabled,
                onAssociationBadgeClick = onAssociationBadgeClick,
                updateBadgeEnabled = updateBadgeEnabled,
                onUpdateBadgeClick = onUpdateBadgeClick,
                workshopBadgeEnabled = workshopBadgeEnabled,
                onWorkshopBadgeClick = { onOpenWorkshopDetails(mod) }
            )
        }
        headerTrailing()
    }

    Spacer(modifier = Modifier.height(2.dp))
    ModCardMetadataRow(
        version = resolvedVersion,
        fileSize = resolvedFileSize
    )
    Text(
        text = resolvedDescription,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        maxLines = if (isExpanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis
    )
    val workshopStatus = mod.workshop
        ?.takeUnless { it.state == WorkshopModState.ImportedPatched }
        ?.statusText
        .orEmpty()
    if (workshopStatus.isNotBlank()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = workshopStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    if (isExpanded && dependencies.isNotEmpty()) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.main_mod_dependencies_format, dependencies.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    if (showActionsButton && isExpanded) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onActionsClick,
                enabled = actionsEnabled
            ) {
                Text(text = stringResource(R.string.main_mod_actions))
            }
        }
    }
}

@Composable
private fun ModCardMetadataRow(
    version: String,
    fileSize: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModCardMetadataItem(
            iconResId = R.drawable.ic_mod_version,
            text = version,
            accessibilityText = stringResource(R.string.main_mod_version_format, version),
            modifier = Modifier.weight(1f, fill = false)
        )
        if (!fileSize.isNullOrBlank()) {
            ModCardMetadataItem(
                iconResId = R.drawable.ic_mod_size,
                text = fileSize,
                accessibilityText = stringResource(R.string.main_mod_file_size_format, fileSize)
            )
        }
    }
}

@Composable
private fun ModCardMetadataItem(
    @DrawableRes iconResId: Int,
    text: String,
    accessibilityText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = accessibilityText
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatModFileSize(bytes: Long): String? {
    if (bytes <= 0L) return null
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        "${String.format(Locale.US, "%.1f", value)} ${units[unitIndex]}"
    }
}

@Composable
private fun ModCardLeadingImage(mod: ModItemUi) {
    val context = LocalContext.current
    val publishedFileId = mod.workshop?.publishedFileId
    val imagePath = mod.workshop?.localPreviewImagePath.orEmpty()
    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = publishedFileId, key2 = imagePath) {
        value = withContext(Dispatchers.IO) {
            publishedFileId?.let { WorkshopPreviewCacheStore.decodeCached(context.applicationContext, it) }
                ?: imagePath.takeIf { it.isNotBlank() }?.let(ModCardPreviewImageLoader::load)
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_image_mod),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
    }
}

private object ModCardPreviewImageLoader {
    fun load(path: String): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_SIZE_PX, TARGET_SIZE_PX)
            }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sampleSize >= targetWidth && halfHeight / sampleSize >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private const val TARGET_SIZE_PX = 144
}

@Composable
private fun ModCardBadges(
    mod: ModItemUi,
    modSuggestionText: String?,
    suggestionUnread: Boolean,
    suggestionBadgeEnabled: Boolean,
    onSuggestionClick: () -> Unit,
    importPatchBadgeEnabled: Boolean,
    onImportPatchClick: () -> Unit,
    associationBadge: ModAssociationBadgeUi?,
    associationBadgeEnabled: Boolean,
    onAssociationBadgeClick: () -> Unit,
    updateBadgeEnabled: Boolean,
    onUpdateBadgeClick: () -> Unit,
    workshopBadgeEnabled: Boolean,
    onWorkshopBadgeClick: () -> Unit,
) {
    val showSuggestion = !modSuggestionText.isNullOrBlank()
    val showImportPatch = !mod.importPatchDetails.isNullOrBlank() || mod.importPatches.isNotEmpty()
    val showUpdate = mod.workshop?.state == WorkshopModState.UpdateAvailable
    // States that already communicate themselves through the dedicated action row (a button or
    // a progress bar) are suppressed here to avoid saying the same thing twice. Queued and
    // Cancelling render no action control, so they keep their badge.
    val workshopBadgeState = mod.workshop?.state?.takeIf {
        it != WorkshopModState.ImportedUnpatched &&
            it != WorkshopModState.DownloadFailed &&
            it != WorkshopModState.Downloading &&
            it != WorkshopModState.DownloadPaused &&
            it != WorkshopModState.FileMissing &&
            it != WorkshopModState.UpdateAvailable
    }
    val effectivePriority = mod.effectivePriority
    val hasBadges = showSuggestion || showImportPatch || associationBadge != null || showUpdate || mod.favorite ||
        mod.newlyImported || workshopBadgeState != null || effectivePriority != null
    if (!hasBadges) return

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showSuggestion) {
            ModSuggestionBadge(
                enabled = suggestionBadgeEnabled,
                unread = suggestionUnread,
                onClick = onSuggestionClick
            )
        }
        if (showImportPatch) {
            ModImportPatchBadge(
                outdated = mod.hasOutdatedImportPatches,
                enabled = importPatchBadgeEnabled,
                onClick = onImportPatchClick
            )
        }
        associationBadge?.let { badge ->
            ModAssociationBadge(
                badge = badge,
                enabled = associationBadgeEnabled,
                onClick = onAssociationBadgeClick
            )
        }
        if (showUpdate) {
            WorkshopUpdateBadge(
                enabled = updateBadgeEnabled,
                onClick = onUpdateBadgeClick
            )
        }
        if (mod.favorite) {
            FavoriteBadge()
        }
        if (mod.newlyImported) {
            NewImportBadge()
        }
        workshopBadgeState?.let { state ->
            if (state == WorkshopModState.ImportedPatched) {
                WorkshopBadge(enabled = workshopBadgeEnabled, onClick = onWorkshopBadgeClick)
            } else {
                WorkshopStateBadge(state)
            }
        }
        if (effectivePriority != null) {
            PriorityLoadBadge(priority = effectivePriority)
        }
    }
}

@Composable
private fun ModAssociationBadge(
    badge: ModAssociationBadgeUi,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = Color(badge.colorArgb)
    ModCardLabelBadge(
        iconResId = R.drawable.ic_link,
        text = badge.associatedCount.toString(),
        contentDescription = stringResource(R.string.main_mod_association_badge_content_description),
        enabled = enabled,
        onClick = onClick,
        containerColor = containerColor,
        contentColor = associationBadgeContentColor(containerColor, enabled)
    )
}

@Composable
private fun ModSuggestionBadge(
    enabled: Boolean,
    unread: Boolean,
    onClick: () -> Unit,
) {
    ModCardIconBadge(
        iconResId = R.drawable.ic_error_outline,
        contentDescription = null,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun WorkshopUpdateBadge(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ModCardLabelBadge(
        iconResId = R.drawable.ic_workshop_update,
        text = stringResource(R.string.workshop_action_update),
        contentDescription = stringResource(R.string.main_mod_update_available),
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun ModImportPatchBadge(
    outdated: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ModCardIconBadge(
        iconResId = R.drawable.ic_build,
        contentDescription = stringResource(R.string.main_mod_patch_badge_content_description),
        enabled = enabled,
        onClick = onClick,
        containerColor = if (outdated) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (outdated) {
            MaterialTheme.colorScheme.onErrorContainer
        } else if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.outline
        }
    )
}

@Composable
private fun FavoriteBadge() {
    ModCardIconBadge(
        iconResId = R.drawable.ic_favorite_heart,
        contentDescription = stringResource(R.string.main_mod_favorite_badge_content_description)
    )
}

@Composable
private fun WorkshopBadge(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ModCardIconBadge(
        iconResId = R.drawable.ic_dock_market,
        contentDescription = stringResource(R.string.main_mod_workshop_badge_content_description),
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun NewImportBadge() {
    ModCardTextBadge(text = stringResource(R.string.main_mod_new_import_badge))
}

@Composable
private fun WorkshopStateBadge(state: WorkshopModState) {
    val text = when (state) {
        WorkshopModState.NotDownloaded -> stringResource(R.string.workshop_download_state_not_downloaded)
        WorkshopModState.ImportedUnpatched -> stringResource(R.string.main_mod_workshop_state_needs_patch)
        WorkshopModState.ImportedPatched -> stringResource(R.string.main_mod_workshop_state_workshop)
        WorkshopModState.Queued -> stringResource(R.string.workshop_download_state_queued)
        WorkshopModState.Downloading -> stringResource(R.string.main_mod_workshop_state_downloading)
        WorkshopModState.Cancelling -> stringResource(R.string.workshop_download_state_cancelling)
        WorkshopModState.DownloadPaused -> stringResource(R.string.main_mod_workshop_state_paused)
        WorkshopModState.DownloadFailed -> stringResource(R.string.main_mod_workshop_state_failed)
        WorkshopModState.NonStandardDownloaded -> stringResource(R.string.main_mod_workshop_state_manual_required)
        WorkshopModState.TexturePackInstalled -> stringResource(R.string.main_mod_workshop_state_texture_pack)
        WorkshopModState.UpdateAvailable -> stringResource(R.string.main_mod_workshop_state_update_available)
        WorkshopModState.FileMissing -> stringResource(R.string.main_mod_workshop_state_file_missing)
    }
    ModCardTextBadge(text = text)
}

@Composable
private fun PriorityLoadBadge(priority: Int) {
    ModCardTextBadge(text = stringResource(R.string.main_mod_priority_badge_format, priority))
}

@Composable
private fun ModCardIconBadge(
    iconResId: Int,
    contentDescription: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.outline
    },
) {
    ModCardBadgeSurface(
        enabled = enabled,
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = contentDescription,
            modifier = Modifier.padding(4.dp).size(12.dp)
        )
    }
}

@Composable
private fun ModCardTextBadge(text: String) {
    ModCardBadgeSurface {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun ModCardLabelBadge(
    iconResId: Int,
    text: String,
    contentDescription: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.outline
    },
) {
    ModCardBadgeSurface(
        enabled = enabled,
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = contentDescription,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ModCardBadgeSurface(
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.outline
    },
    content: @Composable () -> Unit,
) {
    val surfaceModifier = if (onClick != null && enabled) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp)
    ) {
        Box(modifier = surfaceModifier) {
            content()
        }
    }
}

private fun associationBadgeContentColor(containerColor: Color, enabled: Boolean): Color {
    if (!enabled) {
        return Color.White.copy(alpha = 0.72f)
    }
    return if (containerColor.luminance() > 0.48f) {
        Color.Black
    } else {
        Color.White
    }
}
