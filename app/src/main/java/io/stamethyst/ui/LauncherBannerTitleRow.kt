package io.stamethyst.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/** The title, icon and subtitle all follow the same animated fraction as the banner body. */
@Composable
internal fun LauncherBannerTitleRow(
    @DrawableRes iconResId: Int,
    title: String,
    subtitle: String,
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 28.dp,
    iconContentDescription: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val fraction = LocalLauncherBannerCollapseFraction.current
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding()
            .padding(
                start = 16.dp,
                top = lerp(18.dp, 8.dp, fraction),
                end = 16.dp,
                bottom = lerp(16.dp, 8.dp, fraction),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lerp(12.dp, 10.dp, fraction)),
    ) {
        navigationIcon?.invoke()
        Surface(
            modifier = Modifier.size(lerp(52.dp, 40.dp, fraction)),
            shape = RoundedCornerShape(lerp(18.dp, 14.dp, fraction)),
            color = iconContainerColor,
            contentColor = iconContentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val sharedNavigationIcon = LauncherNavigationIcons.forHeaderResource(iconResId)
                val iconModifier = Modifier.size(lerp(iconSize, 24.dp, fraction))
                if (sharedNavigationIcon != null) {
                    Icon(
                        imageVector = sharedNavigationIcon,
                        contentDescription = iconContentDescription,
                        modifier = iconModifier,
                    )
                } else {
                    Icon(
                        painter = painterResource(iconResId),
                        contentDescription = iconContentDescription,
                        modifier = iconModifier,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = lerp(MaterialTheme.typography.headlineSmall, MaterialTheme.typography.titleLarge, fraction),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                BannerReveal(fraction) {
                    Text(
                        text = subtitle,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        actions()
    }
}
