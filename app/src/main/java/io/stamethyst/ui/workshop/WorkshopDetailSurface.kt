package io.stamethyst.ui.workshop

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WorkshopDetailHeroCard(
    publishedFileId: ULong,
    previewUrl: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current.applicationContext
    // Reset before loading a new item or URL: never flash the previous cover.
    val bitmap by key(publishedFileId, previewUrl) {
        produceState<Bitmap?>(initialValue = null, key1 = context) {
            value = withContext(Dispatchers.IO) {
                WorkshopPreviewCacheStore.load(context, publishedFileId, previewUrl)
            }
        }
    }
    WorkshopDetailCard {
        Box(Modifier.fillMaxWidth()) {
            bitmap?.let { preview ->
                WorkshopDetailCornerArtwork(
                    bitmap = preview,
                    modifier = Modifier.align(BiasAlignment(1f, -1f)),
                )
            }
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/** Only the image is masked; the card surface, text and controls remain opaque and sharp. */
@Composable
internal fun WorkshopDetailCornerArtwork(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = BiasAlignment(0.3f, -0.44f),
        modifier = modifier
            .size(240.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val mask = Brush.radialGradient(
                    0f to Color.Black,
                    0.14f to Color.Black,
                    0.32f to Color.Black.copy(alpha = 0.85f),
                    0.52f to Color.Black.copy(alpha = 0.28f),
                    // Fade out early toward the lower left, well before the square image edge.
                    0.82f to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(size.width, 0f),
                    radius = size.minDimension * (230f / 240f),
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
            },
    )
}

@Composable
internal fun WorkshopDetailCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            ),
            content = content,
        )
    }
}
