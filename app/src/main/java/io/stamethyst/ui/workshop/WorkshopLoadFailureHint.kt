package io.stamethyst.ui.workshop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import io.stamethyst.R

private const val WORKSHOP_WEBSITE_URL = "https://workshop.apricityx.top/"

@Composable
internal fun WorkshopLoadFailureHint() {
    val uriHandler = LocalUriHandler.current
    val websiteHint = stringResource(R.string.workshop_load_failure_website_hint, WORKSHOP_WEBSITE_URL)

    Text(
        text = stringResource(R.string.workshop_load_failure_steam_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
    Text(
        text = websiteHint,
        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary),
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                uriHandler.openUri(WORKSHOP_WEBSITE_URL)
            },
    )
}
