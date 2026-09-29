package io.stamethyst.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/**
 * Swaps [text] with the vertical slide-fade the market load progress bar uses
 * (WorkshopLoadProgressBar): the incoming line fades in from half a line below while the outgoing
 * line fades out upward.
 *
 * The container size is snapped rather than animated so a row whose height is already fixed does
 * not pay a layout pass per frame; the incoming and outgoing lines overlap for the duration. Wrap
 * the surrounding column in `Modifier.animateContentSize` when a swap can change the row count.
 *
 * Use this for any card string that changes at runtime instead of swapping [Text] targets directly.
 */
@Composable
internal fun SlidingTextSwap(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (
                fadeIn(animationSpec = tween(durationMillis = 200)) +
                    slideInVertically(animationSpec = tween(durationMillis = 220)) { height -> height / 2 }
                ) togetherWith (
                fadeOut(animationSpec = tween(durationMillis = 140)) +
                    slideOutVertically(animationSpec = tween(durationMillis = 220)) { height -> -height / 2 }
                ) using SizeTransform(clip = false) { _, _ -> snap() }
        },
        label = "slidingTextSwap",
        modifier = modifier,
    ) { animatedText ->
        Text(
            text = animatedText,
            style = style,
            color = color,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}
