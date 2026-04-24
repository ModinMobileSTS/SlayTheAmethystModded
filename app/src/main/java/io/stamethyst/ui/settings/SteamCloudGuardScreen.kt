package io.stamethyst.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.steamcloud.SteamCloudLoginChallenge
import io.stamethyst.backend.steamcloud.SteamCloudLoginChallengeKind
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack
import java.util.Locale

private const val STEAM_GUARD_CODE_LENGTH = 5
private const val STEAM_ANDROID_PACKAGE = "com.valvesoftware.android.steam.community"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSteamCloudGuardScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    val challenge = uiState.steamCloudLoginChallenge
    val canNavigateBack = challenge != null || !uiState.busy

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LaunchedEffect(challenge, uiState.busy, uiState.steamCloudRefreshTokenConfigured) {
        if (challenge == null && !uiState.busy) {
            if (uiState.steamCloudRefreshTokenConfigured) {
                navigator.popTo(Route.Settings)
            } else if (!navigator.popTo(Route.SteamCloudLogin)) {
                navigator.goBack()
            }
        }
    }

    fun handleBack() {
        when {
            challenge != null -> {
                viewModel.onCancelSteamCloudChallenge()
                navigator.goBack()
            }

            !uiState.busy -> navigator.goBack()
        }
    }

    BackHandler(onBack = ::handleBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_steam_cloud_guard_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = ::handleBack,
                        enabled = canNavigateBack
                    ) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            if (challenge != null) {
                SettingsSectionCard(title = steamCloudChallengeTitle(challenge)) {
                    Text(
                        text = steamCloudChallengeDescription(challenge),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (challenge.previousCodeWasIncorrect &&
                        challenge.kind != SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION
                    ) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(R.string.settings_steam_cloud_challenge_code_incorrect),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.size(16.dp))
                    when (challenge.kind) {
                        SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION -> {
                            Button(
                                onClick = viewModel::onAcceptSteamCloudDeviceConfirmation,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.settings_steam_cloud_challenge_continue_action))
                            }
                        }

                        SteamCloudLoginChallengeKind.DEVICE_CODE,
                        SteamCloudLoginChallengeKind.EMAIL_CODE -> {
                            SteamGuardCodeInput(
                                challenge = challenge,
                                onComplete = viewModel::onSubmitSteamCloudChallengeCode,
                            )
                            if (challenge.kind == SteamCloudLoginChallengeKind.DEVICE_CODE) {
                                Spacer(modifier = Modifier.size(16.dp))
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_steam_cloud_open_steam_action),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                        modifier = Modifier.clickable {
                                            openSteamApp(activity)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (uiState.busy) {
                SettingsSectionCard(title = stringResource(R.string.settings_steam_cloud_guard_title)) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamGuardCodeInput(
    challenge: SteamCloudLoginChallenge,
    onComplete: (String) -> Unit,
) {
    val challengeKey = "${challenge.kind.name}:${challenge.previousCodeWasIncorrect}:${challenge.emailHint}"
    val focusRequesters = remember { List(STEAM_GUARD_CODE_LENGTH) { FocusRequester() } }
    var cells by rememberSaveable(challengeKey) {
        mutableStateOf(List(STEAM_GUARD_CODE_LENGTH) { "" })
    }
    var submittedCode by rememberSaveable(challengeKey) { mutableStateOf("") }
    val code = cells.joinToString("")

    LaunchedEffect(challengeKey) {
        focusRequesters.first().requestFocus()
    }

    LaunchedEffect(code) {
        if (code.length == STEAM_GUARD_CODE_LENGTH && cells.all { it.length == 1 } && code != submittedCode) {
            submittedCode = code
            onComplete(code)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        cells.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    val normalized = normalizeSteamGuardInput(input)
                    if (normalized.isEmpty()) {
                        cells = cells.toMutableList().also { it[index] = "" }
                        if (index > 0) {
                            focusRequesters[index - 1].requestFocus()
                        }
                        return@OutlinedTextField
                    }

                    val nextCells = cells.toMutableList()
                    normalized.take(STEAM_GUARD_CODE_LENGTH - index).forEachIndexed { offset, char ->
                        nextCells[index + offset] = char.toString()
                    }
                    cells = nextCells
                    val nextIndex = (index + normalized.length)
                        .coerceAtMost(STEAM_GUARD_CODE_LENGTH - 1)
                    if (cells.any { it.isBlank() }) {
                        focusRequesters[nextIndex].requestFocus()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .focusRequester(focusRequesters[index])
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Backspace &&
                            cells[index].isEmpty() &&
                            index > 0
                        ) {
                            focusRequesters[index - 1].requestFocus()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                textStyle = TextStyle(
                    textAlign = TextAlign.Center,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    lineHeight = MaterialTheme.typography.titleLarge.lineHeight,
                    fontWeight = MaterialTheme.typography.titleLarge.fontWeight,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = if (index == STEAM_GUARD_CODE_LENGTH - 1) {
                        ImeAction.Done
                    } else {
                        ImeAction.Next
                    }
                ),
                keyboardActions = KeyboardActions(
                    onNext = {
                        if (index < STEAM_GUARD_CODE_LENGTH - 1) {
                            focusRequesters[index + 1].requestFocus()
                        }
                    },
                    onDone = {
                        if (code.length == STEAM_GUARD_CODE_LENGTH) {
                            onComplete(code)
                        }
                    }
                )
            )
        }
    }
}

private fun normalizeSteamGuardInput(input: String): String {
    return input
        .uppercase(Locale.US)
        .filter { it.isLetterOrDigit() }
        .take(STEAM_GUARD_CODE_LENGTH)
}

private fun openSteamApp(context: Context) {
    val launchIntent = context.packageManager
        .getLaunchIntentForPackage(STEAM_ANDROID_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?: return
    runCatching {
        context.startActivity(launchIntent)
    }
}

@Composable
private fun steamCloudChallengeTitle(challenge: SteamCloudLoginChallenge): String {
    return when (challenge.kind) {
        SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION ->
            stringResource(R.string.settings_steam_cloud_challenge_device_confirmation_title)

        SteamCloudLoginChallengeKind.DEVICE_CODE ->
            if (challenge.previousCodeWasIncorrect) {
                stringResource(R.string.settings_steam_cloud_challenge_device_code_retry_title)
            } else {
                stringResource(R.string.settings_steam_cloud_challenge_device_code_title)
            }

        SteamCloudLoginChallengeKind.EMAIL_CODE ->
            if (challenge.previousCodeWasIncorrect) {
                stringResource(R.string.settings_steam_cloud_challenge_email_code_retry_title)
            } else {
                stringResource(R.string.settings_steam_cloud_challenge_email_code_title)
            }
    }
}

@Composable
private fun steamCloudChallengeDescription(challenge: SteamCloudLoginChallenge): String {
    return when (challenge.kind) {
        SteamCloudLoginChallengeKind.DEVICE_CONFIRMATION ->
            stringResource(R.string.settings_steam_cloud_challenge_device_confirmation_desc)

        SteamCloudLoginChallengeKind.DEVICE_CODE ->
            stringResource(R.string.settings_steam_cloud_challenge_device_code_desc)

        SteamCloudLoginChallengeKind.EMAIL_CODE ->
            if (challenge.emailHint.isNotBlank()) {
                stringResource(
                    R.string.settings_steam_cloud_challenge_email_code_desc_with_hint,
                    challenge.emailHint
                )
            } else {
                stringResource(R.string.settings_steam_cloud_challenge_email_code_desc)
            }
    }
}
