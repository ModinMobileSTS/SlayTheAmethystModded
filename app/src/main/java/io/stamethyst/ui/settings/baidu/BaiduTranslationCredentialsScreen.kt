package io.stamethyst.ui.settings.baidu

import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import io.stamethyst.ui.LauncherAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.workshop.BaiduAiTextTranslationClient
import io.stamethyst.backend.workshop.BaiduTranslationCredentials
import io.stamethyst.ui.LauncherTransientNoticeBus
import io.stamethyst.ui.LauncherTransientNoticeDuration
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LauncherBaiduTranslationCredentialsScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    notice: String? = null,
    embedded: Boolean = false,
    onBack: () -> Unit,
) {
    val activity = requireNotNull(LocalActivity.current)
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val translationClient = remember { BaiduAiTextTranslationClient() }
    var appIdInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var credentialsLoadState by remember { mutableStateOf(SettingsLoadState.LOADING) }
    var credentialsLoadAttempt by remember { mutableIntStateOf(0) }
    var isTesting by remember { mutableStateOf(false) }
    var pendingNotice by remember(notice) { mutableStateOf(notice?.takeIf(String::isNotBlank)) }

    LaunchedEffect(activity, credentialsLoadAttempt) {
        viewModel.bind(activity)
        credentialsLoadState = SettingsLoadState.LOADING
        try {
            val credentials = withContext(Dispatchers.IO) {
                viewModel.readBaiduTranslationCredentials(activity)
            }
            appIdInput = credentials.appId
            apiKeyInput = credentials.apiKey
            credentialsLoadState = SettingsLoadState.READY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            credentialsLoadState = SettingsLoadState.ERROR
        }
    }
    val controlsEnabled = settingsControlEnabled(
        credentialsLoadState == SettingsLoadState.READY && !isTesting && !viewModel.uiState.busy,
    )

    pendingNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { pendingNotice = null },
            title = { Text(stringResource(R.string.settings_baidu_translation_notice_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { pendingNotice = null }) {
                    Text(stringResource(R.string.common_action_confirm))
                }
            },
        )
    }

    SettingsDetailLayout(
        embedded = embedded,
        modifier = modifier,
        title = stringResource(R.string.settings_baidu_translation_credentials_title),
        onGoBack = onBack,
        spacing = 16.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsPanelCard {
                Text(
                    text = stringResource(R.string.settings_baidu_translation_credentials_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (credentialsLoadState == SettingsLoadState.ERROR) {
                    CompositionLocalProvider(LocalSettingsLoadRetry provides { credentialsLoadAttempt++ }) {
                        SettingsDependencyContent(
                            SettingsScreenViewModel.UiState(loadStates = mapOf(
                                SettingsLoadDomain.TRANSLATION to credentialsLoadState,
                            )),
                            SettingsLoadDomain.TRANSLATION,
                        ) {}
                    }
                }
                if (credentialsLoadState == SettingsLoadState.READY &&
                    viewModel.uiState.loadState(SettingsLoadDomain.TRANSLATION) == SettingsLoadState.READY) {
                    Text(
                        text = if (viewModel.uiState.baiduTranslationCredentialsConfigured) {
                            stringResource(R.string.settings_baidu_translation_credentials_page_configured_desc)
                        } else {
                            stringResource(R.string.settings_baidu_translation_credentials_page_not_configured_desc)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = appIdInput,
                    enabled = controlsEnabled,
                    onValueChange = { appIdInput = it.trim() },
                    label = { Text(stringResource(R.string.settings_baidu_translation_app_id_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    enabled = controlsEnabled,
                    onValueChange = { apiKeyInput = it.trim() },
                    label = { Text(stringResource(R.string.settings_baidu_translation_api_key_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Button(
                    enabled = controlsEnabled,
                    onClick = {
                        viewModel.onSaveBaiduTranslationCredentials(activity, appIdInput, apiKeyInput)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.common_action_save))
                }

                OutlinedButton(
                    onClick = {
                        if (isTesting) return@OutlinedButton
                        val credentials = BaiduTranslationCredentials(
                            appId = appIdInput,
                            apiKey = apiKeyInput,
                        )
                        val validationMessage = validateBaiduCredentialsForScreen(credentials)
                        if (validationMessage != null) {
                            LauncherTransientNoticeBus.show(
                                activity,
                                validationMessage,
                                LauncherTransientNoticeDuration.LONG,
                            )
                            return@OutlinedButton
                        }
                        isTesting = true
                        coroutineScope.launch {
                            runCatching {
                                translationClient.translate(
                                    text = BAIDU_TRANSLATION_SAMPLE_TEXT,
                                    sourceLanguage = BAIDU_AUTO_DETECT_LANGUAGE,
                                    targetLanguage = BAIDU_DEFAULT_TARGET_LANGUAGE,
                                    credentials = credentials,
                                    reference = BAIDU_TRANSLATION_SAMPLE_REFERENCE,
                                )
                            }.onSuccess { translatedText ->
                                isTesting = false
                                val successMessage = activity.getString(R.string.settings_baidu_translation_test_success)
                                LauncherTransientNoticeBus.show(
                                    activity,
                                    translatedText.takeIf(String::isNotBlank)
                                        ?.let { "$successMessage\n$it" }
                                        ?: successMessage,
                                    LauncherTransientNoticeDuration.LONG,
                                )
                            }.onFailure { error ->
                                isTesting = false
                                val fallbackMessage = activity.getString(R.string.settings_baidu_translation_test_failed)
                                val failureMessage = error.message
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { "$fallbackMessage\n$it" }
                                    ?: fallbackMessage
                                LauncherTransientNoticeBus.show(
                                    activity,
                                    failureMessage,
                                    LauncherTransientNoticeDuration.LONG,
                                )
                            }
                        }
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isTesting) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.settings_baidu_translation_test_loading))
                        }
                    } else {
                        Text(stringResource(R.string.settings_baidu_translation_test_action))
                    }
                }
            }

            if (!embedded) SettingsPanelCard {
                Text(stringResource(R.string.settings_baidu_translation_sample_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = BAIDU_TRANSLATION_SAMPLE_TEXT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (!embedded) SettingsPanelCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.settings_baidu_translation_tutorial_title), style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(
                        onClick = { uriHandler.openUri(BAIDU_TRANSLATION_GUIDE_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_baidu_translation_open_platform))
                    }

                    baiduTutorialSteps.forEachIndexed { index, step ->
                        BaiduTranslationTutorialStep(
                            stepNumber = index + 1,
                            step = step,
                            topPadding = if (index == 0) 0.dp else 4.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanelCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun BaiduTranslationTutorialStep(
    stepNumber: Int,
    step: BaiduTutorialStep,
    topPadding: Dp,
) {
    Column(
        modifier = Modifier.padding(top = topPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "$stepNumber. ${step.text}",
            style = MaterialTheme.typography.titleMedium,
        )

        step.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        step.imageResId?.let { imageResId ->
            Image(
                painter = painterResource(id = imageResId),
                contentDescription = step.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private fun validateBaiduCredentialsForScreen(credentials: BaiduTranslationCredentials): String? = when {
    credentials.appId.isBlank() && credentials.apiKey.isBlank() -> "未配置百度大模型文本翻译的 AppID 和 API Key。"
    credentials.appId.isBlank() -> "未配置百度大模型文本翻译的 AppID。"
    credentials.apiKey.isBlank() -> "未配置百度大模型文本翻译的 API Key。"
    else -> null
}

private data class BaiduTutorialStep(
    val text: String,
    val imageResId: Int? = null,
    val note: String? = null,
)

private val baiduTutorialSteps = listOf(
    BaiduTutorialStep(
        text = "进入百度翻译开放平台。",
        note = "入口地址：$BAIDU_TRANSLATION_GUIDE_URL",
    ),
    BaiduTutorialStep(
        text = "点击“立即使用”。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_02,
    ),
    BaiduTutorialStep(
        text = "根据提示填写个人信息。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_03,
    ),
    BaiduTutorialStep(
        text = "完成实名认证。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_04,
    ),
    BaiduTutorialStep(
        text = "认证完成后回到控制台，点击“立即开通”。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_05,
    ),
    BaiduTutorialStep(
        text = "选择“大模型文本翻译”。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_06,
    ),
    BaiduTutorialStep(
        text = "随便填一点测试内容后提交申请。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_07,
    ),
    BaiduTutorialStep(
        text = "回到主界面，点击“开发者信息”。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_08,
    ),
    BaiduTutorialStep(text = "把 AppID 记下来。"),
    BaiduTutorialStep(
        text = "进入“API Key 管理”，创建一个新的 API Key，名称可以随便填。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_10,
    ),
    BaiduTutorialStep(
        text = "把新建出来的 API Key 也记下来。",
        imageResId = R.drawable.baidu_ai_text_tutorial_step_10_result,
    ),
    BaiduTutorialStep(text = "回到 App，把 AppID 和 API Key 填进上面的配置区后保存即可使用。"),
)

private const val BAIDU_TRANSLATION_GUIDE_URL = "https://fanyi-api.baidu.com/product/13"
private const val BAIDU_TRANSLATION_SAMPLE_TEXT =
    "This mod adds a new relic and several balance changes for a smoother run."
private const val BAIDU_TRANSLATION_SAMPLE_REFERENCE = "将内容视为 Steam 创意工坊模组说明进行翻译。"
private const val BAIDU_AUTO_DETECT_LANGUAGE = "auto"
private const val BAIDU_DEFAULT_TARGET_LANGUAGE = "zh"
