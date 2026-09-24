package io.stamethyst.ui.settings.llm

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.settings.common.SettingsRouteScaffold
import io.stamethyst.ui.settings.common.SettingsSectionCard
import io.stamethyst.ui.settings.core.SettingsScreenViewModel

private const val DEEPSEEK_KEY_URL = "https://platform.deepseek.com/api_keys"
private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"

@Composable
fun LauncherLlmTutorialScreen(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
) {
    val navigator = currentNavigator
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        title = stringResource(R.string.llm_tutorial_title),
        subtitle = stringResource(R.string.llm_tutorial_subtitle),
        iconResId = R.drawable.ic_llm_support,
        onGoBack = navigator::goBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_intro_title)) {
                TutorialText(R.string.llm_tutorial_intro)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_key_title)) {
                TutorialText(R.string.llm_tutorial_key_step)
                Text(
                    text = DEEPSEEK_KEY_URL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(DEEPSEEK_KEY_URL)) }) {
                        Text(stringResource(R.string.llm_tutorial_copy_link))
                    }
                    TextButton(onClick = { uriHandler.openUri(DEEPSEEK_KEY_URL) }) {
                        Text(stringResource(R.string.llm_tutorial_open_link))
                    }
                }
                TutorialImage(R.drawable.llm_tutorial_key_list, R.string.llm_tutorial_image_key_list, 511f / 520f)
                TutorialImage(R.drawable.llm_tutorial_key_create, R.string.llm_tutorial_image_key_create, 468f / 255f)
                TutorialImage(R.drawable.llm_tutorial_key_copy, R.string.llm_tutorial_image_key_copy, 472f / 316f)
                TutorialText(R.string.llm_tutorial_key_warning)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_config_title)) {
                TutorialText(R.string.llm_tutorial_config_step)
                Text(
                    text = DEEPSEEK_BASE_URL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(DEEPSEEK_BASE_URL)) }) {
                    Text(stringResource(R.string.llm_tutorial_copy_base_url))
                }
                TutorialText(R.string.llm_tutorial_config_key)
                TutorialImage(R.drawable.llm_tutorial_credentials, R.string.llm_tutorial_image_credentials, 454f / 646f)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_models_title)) {
                TutorialText(R.string.llm_tutorial_models_step)
                TutorialImage(R.drawable.llm_tutorial_models, R.string.llm_tutorial_image_models, 367f / 625f)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_test_title)) {
                TutorialText(R.string.llm_tutorial_test_step)
                TutorialImage(R.drawable.llm_tutorial_test, R.string.llm_tutorial_image_test, 459f / 403f)
            }
        }
    }
}

@Composable
private fun TutorialText(@StringRes textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
    )
}

@Composable
private fun TutorialImage(
    @DrawableRes imageRes: Int,
    @StringRes descriptionRes: Int,
    aspectRatio: Float,
) {
    Image(
        painter = painterResource(imageRes),
        contentDescription = stringResource(descriptionRes),
        modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(RoundedCornerShape(12.dp)),
    )
}
