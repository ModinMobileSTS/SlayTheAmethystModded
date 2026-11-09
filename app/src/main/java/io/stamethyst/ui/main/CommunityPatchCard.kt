package io.stamethyst.ui.main

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.R
import io.stamethyst.backend.mods.RemoteAgentPatch

internal fun RemoteAgentPatch.communityItemKey(): String = "$patchModId:$sha256"

/** Uses only catalogue metadata: no invented review, compatibility or install status. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunityPatchCard(patch: RemoteAgentPatch, modifier: Modifier = Modifier) {
    val key = patch.communityItemKey()
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().testTag("community_patch_$key"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp).testTag("community_patch_icon_$key"),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_code), null, modifier = Modifier.size(27.dp))
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Text(patch.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (patch.authorName.isNotBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(R.drawable.ic_account_circle), null,
                                modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(R.string.community_patch_contributor, patch.authorName),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val versionLabel = stringResource(R.string.main_mod_agent_patch_version_format, patch.version)
                Surface(
                    modifier = Modifier.align(Alignment.Top).widthIn(max = 104.dp)
                        .semantics { contentDescription = versionLabel },
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text("v${patch.version}", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
            }

            val description = patch.description.ifBlank { stringResource(R.string.main_mod_agent_patch_description_empty) }
            val descriptionStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 24.sp)
            val measurer = rememberTextMeasurer()
            // Measure the collapsed form even when expanded, so rotation/font changes cannot leave
            // a stale overflow flag or hide the only way to collapse the text.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val overflows = measurer.measure(
                    text = description,
                    style = descriptionStyle,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = constraints.maxWidth),
                ).hasVisualOverflow
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = description,
                        modifier = Modifier.fillMaxWidth().testTag("community_patch_description_$key"),
                        style = descriptionStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    CommunityPatchFooter {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_patch_layers), null,
                                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.community_patch_target_label),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            patch.targetVersions.ifEmpty { listOf("*") }.forEach { version ->
                                Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                    Text(
                                        version, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (overflows || expanded) {
                            val expansionState = stringResource(
                                if (expanded) R.string.community_patch_description_expanded else R.string.community_patch_description_collapsed,
                            )
                            TextButton(
                                onClick = { expanded = !expanded },
                                modifier = Modifier.semantics { stateDescription = expansionState }
                                    .testTag("community_patch_expand_$key"),
                            ) {
                                Text(
                                    stringResource(if (expanded) R.string.community_patch_collapse else R.string.community_patch_expand),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                Icon(
                                    painterResource(R.drawable.ic_expand_more), null,
                                    modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep the action at the trailing edge; wrap it below the version chips on narrow/large-text layouts. */
@Composable
private fun CommunityPatchFooter(content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val targets = measurables.first().measure(looseConstraints)
        val action = measurables.getOrNull(1)?.measure(looseConstraints)
        val inline = action == null || targets.width + 12.dp.roundToPx() + action.width <= constraints.maxWidth
        val height = if (inline) maxOf(targets.height, action?.height ?: 0)
        else targets.height + 4.dp.roundToPx() + requireNotNull(action).height
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            targets.placeRelative(0, if (inline) (height - targets.height) / 2 else 0)
            action?.placeRelative(constraints.maxWidth - action.width, if (inline) (height - action.height) / 2 else height - action.height)
        }
    }
}
