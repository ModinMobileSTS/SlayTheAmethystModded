package io.stamethyst.ui.whatsnew

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.R
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayStyle
import io.stamethyst.config.LauncherConfig
import io.stamethyst.ui.settings.sections.BootOverlayStyleAnimatedPreview

@Composable
fun WhatsNewScreen(
    release: WhatsNewRelease,
    onClose: (suppressFutureDisplay: Boolean) -> Unit,
    onAction: (WhatsNewActionRoute, suppressFutureDisplay: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (release.entries.isEmpty() && release.moreGroups.isEmpty()) return

    var suppressFutureDisplay by rememberSaveable(release.id) { mutableStateOf(false) }
    val closeDescription = stringResource(R.string.whats_new_close)
    val configuration = LocalConfiguration.current
    val dialogWidth = (configuration.screenWidthDp.dp * 0.92f).coerceAtMost(560.dp)
    val dialogHeight = (configuration.screenHeightDp.dp * 0.88f).coerceAtMost(760.dp)

    Dialog(
        onDismissRequest = { onClose(suppressFutureDisplay) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .width(dialogWidth)
                .height(dialogHeight),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.whats_new_version, release.id),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                            IconButton(
                                onClick = { onClose(suppressFutureDisplay) },
                                modifier = Modifier.semantics { contentDescription = closeDescription },
                            ) {
                                Text("×", style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(release.titleRes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    release.entries.forEachIndexed { index, entry ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        WhatsNewFeature(
                            entry = entry,
                            symbol = if (index == 0) "</>" else "✦",
                            onAction = { route -> onAction(route, suppressFutureDisplay) },
                        )
                    }

                    if (release.moreGroups.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                        ) {
                            release.moreTitleRes?.let { title ->
                                Text(
                                    text = stringResource(title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            release.moreIntroductionRes?.let { introduction ->
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(introduction),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            release.moreGroups.forEach { group ->
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    text = stringResource(group.titleRes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(8.dp))
                                group.itemsRes.forEachIndexed { itemIndex, itemRes ->
                                    if (itemIndex > 0) {
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    }
                                    Text(
                                        text = stringResource(itemRes),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = suppressFutureDisplay,
                                role = Role.Checkbox,
                                onValueChange = { suppressFutureDisplay = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = suppressFutureDisplay, onCheckedChange = null)
                        Text(
                            text = stringResource(R.string.whats_new_do_not_show_again, release.id),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onClose(suppressFutureDisplay) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.whats_new_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsNewFeature(
    entry: WhatsNewEntry,
    symbol: String,
    onAction: (WhatsNewActionRoute) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text = stringResource(entry.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(entry.descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.imageRes?.let { imageRes ->
            Spacer(Modifier.height(16.dp))
            Image(
                painter = painterResource(imageRes),
                contentDescription = entry.imageDescriptionRes?.let { stringResource(it) },
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 144.dp, max = 240.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        if (entry.preview == WhatsNewPreview.SLING_BREAK_BOOT_OVERLAY) {
            Spacer(Modifier.height(16.dp))
            BootOverlayStyleAnimatedPreview(
                style = BootOverlayStyle.SLING_BREAK,
                imageConfig = BootOverlayImageConfig(),
                loadingAnimation = LauncherConfig.DEFAULT_BOOT_OVERLAY_ANIMATION,
                animated = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        if (entry.detailsRes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                entry.detailsRes.forEach { detail ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier
                                .padding(top = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(text = stringResource(detail), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        entry.noteRes?.let { note ->
            Spacer(Modifier.height(12.dp))
            WhatsNewHintCard(note)
        }
        entry.actionRoute?.let { route ->
            entry.actionLabelRes?.let { label ->
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { onAction(route) }) {
                    Text(stringResource(label))
                }
            }
        }
    }
}

@Composable
private fun WhatsNewHintCard(@StringRes messageRes: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ⓘ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
