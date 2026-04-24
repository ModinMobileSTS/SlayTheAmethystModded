package io.stamethyst.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherSteamCloudLoginScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState
    val loginChallenge = uiState.steamCloudLoginChallenge
    var username by rememberSaveable { mutableStateOf(uiState.steamCloudAccountName) }
    var password by rememberSaveable { mutableStateOf("") }
    var loginAttempted by rememberSaveable { mutableStateOf(false) }
    val canNavigateBack = !uiState.busy || loginChallenge != null
    val loginLoading = uiState.busy && loginChallenge == null

    LaunchedEffect(activity) {
        viewModel.bind(activity)
    }

    LaunchedEffect(uiState.steamCloudAccountName) {
        if (username.isBlank() && uiState.steamCloudAccountName.isNotBlank()) {
            username = uiState.steamCloudAccountName
        }
    }

    LaunchedEffect(
        loginAttempted,
        uiState.busy,
        uiState.steamCloudRefreshTokenConfigured,
        loginChallenge
    ) {
        if (loginAttempted &&
            !uiState.busy &&
            loginChallenge == null &&
            uiState.steamCloudRefreshTokenConfigured
        ) {
            if (!navigator.popTo(Route.FirstRunSetup)) {
                navigator.popTo(Route.Settings)
            }
        }
    }

    LaunchedEffect(loginChallenge?.kind, loginChallenge?.previousCodeWasIncorrect, loginChallenge?.emailHint) {
        if (loginChallenge != null) {
            navigator.push(Route.SteamCloudGuard)
        }
    }

    fun handleBack() {
        when {
            loginChallenge != null -> {
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
                title = { Text(stringResource(R.string.settings_steam_cloud_login_title)) },
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
            SettingsSectionCard(title = stringResource(R.string.settings_steam_cloud_login_title)) {
                Text(
                    text = stringResource(R.string.settings_steam_cloud_login_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.busy && loginChallenge == null,
                    label = { Text(stringResource(R.string.settings_steam_cloud_username_label)) }
                )
                Spacer(modifier = Modifier.size(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !uiState.busy && loginChallenge == null,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.settings_steam_cloud_password_label)) }
                )
                Spacer(modifier = Modifier.size(10.dp))
//                Text(
//                    text = stringResource(R.string.settings_steam_cloud_login_dialog_password_policy),
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                )
                Spacer(modifier = Modifier.size(16.dp))
                Button(
                    onClick = {
                        if (viewModel.onStartSteamCloudLogin(activity, username, password)) {
                            loginAttempted = true
                        }
                    },
                    enabled = !loginLoading && loginChallenge == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (loginLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(stringResource(R.string.settings_steam_cloud_login_action))
                    }
                }
            }
        }
    }
}
