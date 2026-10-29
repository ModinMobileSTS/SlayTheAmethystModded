package io.stamethyst.ui.aimod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.stamethyst.R

/** OpenCode v2's attached queue pullout; touch devices keep the row actions visible. */
@Composable
internal fun AiInboxPanel(
    inbox: List<AiInboxItem>,
    busy: Boolean,
    editingId: String?,
    onEdit: (AiInboxItem) -> Unit,
    onUndo: (AiInboxItem) -> Unit,
    onRemove: (AiInboxItem) -> Unit,
    onSteer: (AiInboxItem) -> Unit,
    onReorder: (String, Int) -> Unit,
) {
    val queue = inbox.filter { it.delivery == AiInboxDelivery.QUEUE }
    val colors = MaterialTheme.colorScheme
    val currentReorder by rememberUpdatedState(onReorder)
    val rowHeightPx = with(LocalDensity.current) { 36.dp.toPx() }
    AnimatedVisibility(visible = queue.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(colors.surfaceContainerLow)
            .border(0.5.dp, colors.outlineVariant, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 12.dp).animateContentSize()) {
            if (queue.size > 3) Text(stringResource(R.string.ai_inbox_count, queue.size),
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Column(Modifier.heightIn(max = 162.dp).verticalScroll(rememberScrollState())) {
                queue.forEach { item -> key(item.id) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (editingId == item.id) colors.surfaceContainerHigh else colors.surfaceContainerLow),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val reorderLabel = stringResource(R.string.ai_inbox_reorder)
                        val dotColor = colors.onSurfaceVariant.copy(alpha = 0.5f)
                        Canvas(Modifier.size(28.dp, 36.dp).semantics { contentDescription = reorderLabel }
                            .pointerInput(item.id) {
                                var distance = 0f
                                detectDragGesturesAfterLongPress(onDragStart = { distance = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        distance += amount.y
                                        if (kotlin.math.abs(distance) >= rowHeightPx) {
                                            val direction = if (distance > 0) 1 else -1
                                            currentReorder(item.id, direction)
                                            distance -= direction * rowHeightPx
                                        }
                                    })
                            }) {
                            for (x in 0..1) for (y in 0..2) drawCircle(dotColor, 1.dp.toPx(),
                                Offset(size.width / 2 + (x - 0.5f) * 4.dp.toPx(), size.height / 2 + (y - 1) * 4.dp.toPx()))
                        }
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(3.dp)).clickable { onEdit(item) }.padding(vertical = 8.dp)) {
                        Text(item.text.ifBlank { stringResource(R.string.ai_inbox_attachments, item.attachments.size) },
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (editingId == item.id) colors.onSurfaceVariant else colors.onSurface)
                        val settings = listOfNotNull(item.modelName, item.reasoningEffort?.name?.lowercase()).joinToString(" · ")
                        if (settings.isNotEmpty()) Text(settings, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                        }
                        if (item.text.isNotBlank() && item.attachments.isNotEmpty()) Text(
                            stringResource(R.string.ai_inbox_attachments, item.attachments.size),
                            fontSize = 11.sp, color = colors.onSurfaceVariant)
                        if (editingId != item.id) {
                            TextButton(onClick = { onSteer(item) }, contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.height(36.dp).widthIn(min = 48.dp)) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_arrow_upward), null, Modifier.size(14.dp))
                                Text(stringResource(if (busy) R.string.ai_inbox_steer else R.string.ai_mod_editor_send), fontSize = 12.sp)
                            }
                            IconButton(onClick = { onUndo(item) }, modifier = Modifier.size(32.dp)) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_inbox_undo), stringResource(R.string.ai_inbox_undo), Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(32.dp)) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_inbox_close), stringResource(R.string.ai_inbox_cancel), Modifier.size(16.dp))
                        }
                    }
                } }
            }
        }
    }
}
