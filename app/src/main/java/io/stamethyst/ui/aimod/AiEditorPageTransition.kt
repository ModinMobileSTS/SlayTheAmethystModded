package io.stamethyst.ui.aimod

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds

/** Same horizontal direction and duration as the launcher's full-page navigation. */
@Composable
internal fun AiEditorPageTransition(
    menuPage: Int?,
    modifier: Modifier = Modifier,
    content: @Composable (Int?) -> Unit,
) {
    AnimatedContent(
        targetState = menuPage,
        modifier = modifier.fillMaxSize().clipToBounds(),
        // Menu tab changes are handled by HorizontalPager, not a second page transition.
        contentKey = { it != null },
        transitionSpec = {
            val direction = if (targetState != null) 1 else -1
            slideInHorizontally(tween(420)) { it * direction }.togetherWith(
                slideOutHorizontally(tween(420)) { -it * direction },
            )
        },
        label = "ai editor menu navigation",
    ) { page ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            content(page)
        }
    }
}
