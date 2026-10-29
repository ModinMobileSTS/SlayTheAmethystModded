package io.stamethyst.ui.settings.llm

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.settings.common.SettingsMetadataChip
import io.stamethyst.ui.settings.common.SettingsRouteScaffold
import io.stamethyst.ui.settings.common.SettingsSectionCard
import io.stamethyst.ui.settings.common.hapticClickable
import io.stamethyst.ui.settings.core.SettingsScreenViewModel

private const val DEEPSEEK_KEY_URL = "https://platform.deepseek.com/api_keys"
private const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
private const val SILICONFLOW_INVITE_URL = "https://cloud.siliconflow.cn/i/gd8h18mv"
private const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1/"
private const val AGENTROUTER_INVITE_URL = "https://agentrouter.org/register?aff=q5Yp"
private const val AGENTROUTER_BASE_URL = "https://agentrouter.org/v1"
private const val AIHUB_INVITE_URL = "https://api.aihub8.top/register?aff=geYU"
private const val AIHUB_BASE_URL = "https://api.aihub8.top/v1"

/** Provider picker shown before the setup guide: each provider keeps its own key flow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LauncherLlmTutorialScreen(
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
) {
    val navigator = currentNavigator

    SettingsRouteScaffold(
        modifier = modifier,
        uiState = uiState,
        title = stringResource(R.string.llm_tutorial_title),
        subtitle = "",
        iconResId = R.drawable.ic_llm_support,
        onGoBack = navigator::goBack,
    ) {
        item {
            ProviderCard(
                iconResId = R.drawable.ic_provider_deepseek,
                title = stringResource(R.string.llm_tutorial_provider_deepseek),
                tags = listOf(
                    R.string.llm_tutorial_tag_official,
                    R.string.llm_tutorial_tag_pay_as_you_go,
                    R.string.llm_tutorial_tag_no_verification,
                ),
                description = stringResource(R.string.llm_tutorial_provider_deepseek_desc),
                onClick = { navigator.push(Route.SettingsLlmTutorialDeepSeek) },
            )
        }
        item {
            ProviderCard(
                iconResId = R.drawable.ic_provider_siliconflow,
                title = stringResource(R.string.llm_tutorial_provider_siliconflow),
                tags = listOf(
                    R.string.llm_tutorial_tag_free_quota,
                    R.string.llm_tutorial_tag_verification,
                ),
                description = stringResource(R.string.llm_tutorial_provider_siliconflow_desc),
                onClick = { navigator.push(Route.SettingsLlmTutorialSiliconFlow) },
            )
        }
        item {
            ProviderCard(
                iconResId = R.drawable.ic_provider_agentrouter,
                title = stringResource(R.string.llm_tutorial_provider_agentrouter),
                tags = listOf(
                    R.string.llm_tutorial_tag_free_quota,
                    R.string.llm_tutorial_tag_requires_vpn,
                ),
                description = stringResource(R.string.llm_tutorial_provider_agentrouter_desc),
                onClick = { navigator.push(Route.SettingsLlmTutorialAgentRouter) },
            )
        }
        item {
            ProviderCard(
                iconResId = R.drawable.ic_provider_aihub,
                title = stringResource(R.string.llm_tutorial_provider_aihub),
                tags = listOf(R.string.llm_tutorial_tag_pay_as_you_go),
                description = stringResource(R.string.llm_tutorial_provider_aihub_desc),
                onClick = { navigator.push(Route.SettingsLlmTutorialAiHub) },
            )
        }
    }
}

@Composable
fun LauncherLlmTutorialDeepSeekScreen(
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
        subtitle = stringResource(R.string.llm_tutorial_provider_deepseek),
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
                TutorialUrlBlock(
                    url = DEEPSEEK_KEY_URL,
                    copyLabelRes = R.string.llm_tutorial_copy_link,
                    onCopy = { clipboard.setText(AnnotatedString(DEEPSEEK_KEY_URL)) },
                    onOpen = { uriHandler.openUri(DEEPSEEK_KEY_URL) },
                )
                TutorialImage(R.drawable.llm_tutorial_key_list, R.string.llm_tutorial_image_key_list, 511f / 520f)
                TutorialImage(R.drawable.llm_tutorial_key_create, R.string.llm_tutorial_image_key_create, 468f / 255f)
                TutorialImage(R.drawable.llm_tutorial_key_copy, R.string.llm_tutorial_image_key_copy, 472f / 316f)
                TutorialText(R.string.llm_tutorial_key_warning)
            }
        }
        item {
            TutorialConnectionCard(
                stepNumber = 2,
                baseUrl = DEEPSEEK_BASE_URL,
                onCopy = { clipboard.setText(AnnotatedString(DEEPSEEK_BASE_URL)) },
            )
        }
        item { TutorialModelsCard(stepNumber = 3) }
        item { TutorialTestCard(stepNumber = 4) }
        item { TutorialEditCard(stepNumber = 5) }
    }
}

@Composable
fun LauncherLlmTutorialSiliconFlowScreen(
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
        subtitle = stringResource(R.string.llm_tutorial_provider_siliconflow),
        iconResId = R.drawable.ic_llm_support,
        onGoBack = navigator::goBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_intro_title)) {
                TutorialText(R.string.llm_tutorial_sf_intro)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_sf_account_title)) {
                TutorialText(R.string.llm_tutorial_sf_account_step)
                TutorialUrlBlock(
                    url = SILICONFLOW_INVITE_URL,
                    copyLabelRes = R.string.llm_tutorial_copy_link,
                    onCopy = { clipboard.setText(AnnotatedString(SILICONFLOW_INVITE_URL)) },
                    onOpen = { uriHandler.openUri(SILICONFLOW_INVITE_URL) },
                )
                TutorialText(R.string.llm_tutorial_sf_verify_step)
                TutorialImage(R.drawable.llm_tutorial_sf_verify, R.string.llm_tutorial_sf_image_verify, 286f / 183f)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_sf_key_title)) {
                TutorialText(R.string.llm_tutorial_sf_key_step)
                TutorialImage(R.drawable.llm_tutorial_sf_key_entry, R.string.llm_tutorial_sf_image_key_entry, 560f / 636f)
                TutorialImage(R.drawable.llm_tutorial_sf_key_create, R.string.llm_tutorial_sf_image_key_create, 560f / 203f)
                TutorialImage(R.drawable.llm_tutorial_sf_key_desc, R.string.llm_tutorial_sf_image_key_desc, 520f / 213f)
                TutorialImage(R.drawable.llm_tutorial_sf_key_copy, R.string.llm_tutorial_sf_image_key_copy, 520f / 799f)
                TutorialText(R.string.llm_tutorial_key_warning)
            }
        }
        item {
            TutorialConnectionCard(
                stepNumber = 3,
                baseUrl = SILICONFLOW_BASE_URL,
                onCopy = { clipboard.setText(AnnotatedString(SILICONFLOW_BASE_URL)) },
            )
        }
        item { TutorialModelsCard(stepNumber = 4) }
        item { TutorialTestCard(stepNumber = 5) }
        item { TutorialEditCard(stepNumber = 6) }
    }
}

@Composable
fun LauncherLlmTutorialAgentRouterScreen(
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
        subtitle = stringResource(R.string.llm_tutorial_provider_agentrouter),
        iconResId = R.drawable.ic_llm_support,
        onGoBack = navigator::goBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_intro_title)) {
                TutorialText(R.string.llm_tutorial_ar_intro)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_ar_register_title)) {
                TutorialText(R.string.llm_tutorial_ar_register_step)
                TutorialUrlBlock(
                    url = AGENTROUTER_INVITE_URL,
                    copyLabelRes = R.string.llm_tutorial_copy_link,
                    onCopy = { clipboard.setText(AnnotatedString(AGENTROUTER_INVITE_URL)) },
                    onOpen = { uriHandler.openUri(AGENTROUTER_INVITE_URL) },
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_ar_token_title)) {
                TutorialText(R.string.llm_tutorial_ar_token_step)
                TutorialImage(R.drawable.llm_tutorial_ar_token_entry, R.string.llm_tutorial_ar_image_token_entry, 560f / 304f)
                TutorialImage(R.drawable.llm_tutorial_ar_token_action, R.string.llm_tutorial_ar_image_token_action, 560f / 398f)
                TutorialImage(R.drawable.llm_tutorial_ar_token_form, R.string.llm_tutorial_ar_image_token_form, 520f / 1012f)
                TutorialImage(R.drawable.llm_tutorial_ar_token_copy, R.string.llm_tutorial_ar_image_token_copy, 474f / 376f)
                TutorialText(R.string.llm_tutorial_key_warning)
            }
        }
        item {
            TutorialConnectionCard(
                stepNumber = 3,
                baseUrl = AGENTROUTER_BASE_URL,
                onCopy = { clipboard.setText(AnnotatedString(AGENTROUTER_BASE_URL)) },
            )
        }
        item { TutorialModelsCard(stepNumber = 4) }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_ar_responses_title)) {
                TutorialText(R.string.llm_tutorial_ar_responses_step)
                TutorialImage(R.drawable.llm_tutorial_ar_responses, R.string.llm_tutorial_ar_image_responses, 1316f / 1176f)
            }
        }
        item { TutorialTestCard(stepNumber = 6) }
        item { TutorialEditCard(stepNumber = 7) }
    }
}

@Composable
fun LauncherLlmTutorialAiHubScreen(
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
        subtitle = stringResource(R.string.llm_tutorial_provider_aihub),
        iconResId = R.drawable.ic_llm_support,
        onGoBack = navigator::goBack,
    ) {
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_intro_title)) {
                TutorialText(R.string.llm_tutorial_aihub_intro)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_aihub_register_title)) {
                TutorialText(R.string.llm_tutorial_aihub_register_step)
                TutorialUrlBlock(
                    url = AIHUB_INVITE_URL,
                    copyLabelRes = R.string.llm_tutorial_copy_link,
                    onCopy = { clipboard.setText(AnnotatedString(AIHUB_INVITE_URL)) },
                    onOpen = { uriHandler.openUri(AIHUB_INVITE_URL) },
                )
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_aihub_wallet_title)) {
                TutorialText(R.string.llm_tutorial_aihub_wallet_step)
                TutorialImage(R.drawable.llm_tutorial_aihub_wallet, R.string.llm_tutorial_aihub_image_wallet, 722f / 692f)
            }
        }
        item {
            SettingsSectionCard(title = stringResource(R.string.llm_tutorial_aihub_token_title)) {
                TutorialText(R.string.llm_tutorial_aihub_token_entry_step)
                TutorialImage(R.drawable.llm_tutorial_aihub_token_entry, R.string.llm_tutorial_aihub_image_token_entry, 271f / 302f)
                TutorialText(R.string.llm_tutorial_aihub_token_action_step)
                TutorialImage(R.drawable.llm_tutorial_aihub_token_action, R.string.llm_tutorial_aihub_image_token_action, 689f / 330f)
                TutorialText(R.string.llm_tutorial_aihub_token_form_step)
                TutorialImage(R.drawable.llm_tutorial_aihub_token_form, R.string.llm_tutorial_aihub_image_token_form, 707f / 467f)
                TutorialText(R.string.llm_tutorial_aihub_token_copy_step)
                TutorialImage(R.drawable.llm_tutorial_aihub_token_copy, R.string.llm_tutorial_aihub_image_token_copy, 647f / 547f)
                TutorialText(R.string.llm_tutorial_key_warning)
            }
        }
        item {
            TutorialConnectionCard(
                stepNumber = 4,
                baseUrl = AIHUB_BASE_URL,
                onCopy = { clipboard.setText(AnnotatedString(AIHUB_BASE_URL)) },
            )
        }
        item { TutorialModelsCard(stepNumber = 5) }
        item { TutorialTestCard(stepNumber = 6) }
        item { TutorialEditCard(stepNumber = 7) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(
    @DrawableRes iconResId: Int,
    title: String,
    tags: List<Int>,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(enabled = true, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconResId),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.forEach { tagRes ->
                        SettingsMetadataChip(text = stringResource(tagRes))
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TutorialConnectionCard(
    stepNumber: Int,
    baseUrl: String,
    onCopy: () -> Unit,
) {
    SettingsSectionCard(title = stringResource(R.string.llm_tutorial_config_title, stepNumber)) {
        TutorialText(R.string.llm_tutorial_config_step)
        TutorialUrlBlock(
            url = baseUrl,
            copyLabelRes = R.string.llm_tutorial_copy_base_url,
            onCopy = onCopy,
            onOpen = null,
        )
        TutorialText(R.string.llm_tutorial_config_key)
        TutorialImage(R.drawable.llm_tutorial_credentials, R.string.llm_tutorial_image_credentials, 454f / 646f)
    }
}

@Composable
private fun TutorialModelsCard(stepNumber: Int) {
    SettingsSectionCard(title = stringResource(R.string.llm_tutorial_models_title, stepNumber)) {
        TutorialText(R.string.llm_tutorial_models_step)
        TutorialImage(R.drawable.llm_tutorial_models, R.string.llm_tutorial_image_models, 367f / 625f)
    }
}

@Composable
private fun TutorialTestCard(stepNumber: Int) {
    SettingsSectionCard(title = stringResource(R.string.llm_tutorial_test_title, stepNumber)) {
        TutorialText(R.string.llm_tutorial_test_step)
        TutorialImage(R.drawable.llm_tutorial_test, R.string.llm_tutorial_image_test, 459f / 403f)
    }
}

@Composable
private fun TutorialEditCard(stepNumber: Int) {
    SettingsSectionCard(title = stringResource(R.string.llm_tutorial_edit_title, stepNumber)) {
        TutorialText(R.string.llm_tutorial_edit_step)
        TutorialImage(R.drawable.llm_tutorial_edit_mod_card, R.string.llm_tutorial_image_edit_mod_card, 390f / 251f)
        TutorialImage(R.drawable.llm_tutorial_edit_mod_action, R.string.llm_tutorial_image_edit_mod_action, 427f / 184f)
    }
}

@Composable
private fun TutorialUrlBlock(
    url: String,
    @StringRes copyLabelRes: Int,
    onCopy: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    Text(
        text = url,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    if (onOpen == null) {
        OutlinedButton(onClick = onCopy) {
            Text(stringResource(copyLabelRes))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy) {
                Text(stringResource(copyLabelRes))
            }
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.llm_tutorial_open_link))
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
