package io.stamethyst.ui.workshop

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.stamethyst.backend.workshop.WorkshopPreviewCacheStore
import io.stamethyst.ui.FrostedGlassChrome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LocalWorkshopDetailHazeState = staticCompositionLocalOf<HazeState> {
    error("Workshop detail cards require a WorkshopDetailBackdrop")
}

@Composable
internal fun WorkshopDetailBackdrop(
    publishedFileId: ULong,
    previewUrl: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val hazeState = rememberHazeState()
    // A new item must never briefly display the previous item's artwork.
    val bitmap by key(publishedFileId) {
        produceState<Bitmap?>(initialValue = null, key1 = context, key2 = previewUrl) {
            value = withContext(Dispatchers.IO) {
                WorkshopPreviewCacheStore.load(context, publishedFileId, previewUrl)
            }
        }
    }
    CompositionLocalProvider(LocalWorkshopDetailHazeState provides hazeState) {
        Box(modifier = modifier) {
            // Only the fixed artwork is sampled; foreground text and controls stay sharp.
            Box(
                Modifier.matchParentSize()
                    .hazeSource(hazeState)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                bitmap?.let { preview ->
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
                }
            }
            content()
        }
    }
}

@Composable
internal fun WorkshopDetailCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    FrostedGlassChrome(
        modifier = modifier.fillMaxWidth(),
        hazeState = LocalWorkshopDetailHazeState.current,
        shape = CardDefaults.shape,
        shadowElevation = 2.dp,
        blurRadius = 20.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            ),
            content = content,
        )
    }
}
