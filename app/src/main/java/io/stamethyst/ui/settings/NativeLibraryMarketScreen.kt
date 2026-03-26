package io.stamethyst.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.backend.nativelib.NativeLibraryMarketAvailability
import io.stamethyst.backend.nativelib.NativeLibraryMarketPackageState
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.ArrowBack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherNativeLibraryMarketScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val uiState = viewModel.uiState

    LaunchedEffect(activity) {
        viewModel.onOpenNativeLibraryMarket(activity)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_native_library_market_title)) },
                navigationIcon = {
                    IconButton(onClick = navigator::goBack) {
                        Icon(
                            imageVector = Icons.ArrowBack,
                            contentDescription = stringResource(R.string.common_content_desc_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.refreshNativeLibraryMarket(activity) },
                        enabled = !uiState.busy && !uiState.nativeLibraryMarketLoading
                    ) {
                        Text(stringResource(R.string.common_action_refresh))
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsBusyIndicator(uiState = uiState)
            }

            item {
                SettingsSectionCard(
                    title = stringResource(R.string.settings_native_library_market_title)
                ) {
                    Text(
                        text = stringResource(R.string.settings_native_library_market_dialog_desc),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (uiState.nativeLibraryMarketLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    uiState.nativeLibraryMarketErrorText?.let { errorText ->
                        HorizontalDivider()
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (!uiState.nativeLibraryMarketLoading && uiState.nativeLibraryMarketPackages.isEmpty()) {
                item {
                    SettingsSectionCard(
                        title = stringResource(R.string.settings_native_library_market_dialog_title)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_native_library_market_empty),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            items(
                count = uiState.nativeLibraryMarketPackages.size,
                key = { index -> uiState.nativeLibraryMarketPackages[index].catalogEntry.id }
            ) { index ->
                NativeLibraryMarketPackageCard(
                    packageState = uiState.nativeLibraryMarketPackages[index],
                    enabled = !uiState.busy,
                    onInstall = { packageId ->
                        viewModel.onInstallNativeLibraryPackage(activity, packageId)
                    },
                    onRemove = { packageId ->
                        viewModel.onRemoveNativeLibraryPackage(activity, packageId)
                    }
                )
            }
        }
    }
}

@Composable
private fun NativeLibraryMarketPackageCard(
    packageState: NativeLibraryMarketPackageState,
    enabled: Boolean,
    onInstall: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val packageEntry = packageState.catalogEntry
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = packageEntry.displayName,
                style = MaterialTheme.typography.titleSmall
            )
            if (packageEntry.description.isNotBlank()) {
                Text(
                    text = packageEntry.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = stringResource(
                    R.string.settings_native_library_market_files,
                    packageEntry.files.joinToString(", ") { it.fileName }
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = when (packageState.availability) {
                    NativeLibraryMarketAvailability.INSTALLED ->
                        stringResource(R.string.settings_native_library_market_status_installed)
                    NativeLibraryMarketAvailability.BUNDLED ->
                        stringResource(R.string.settings_native_library_market_status_bundled)
                    NativeLibraryMarketAvailability.NOT_INSTALLED ->
                        stringResource(R.string.settings_native_library_market_status_not_installed)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                when (packageState.availability) {
                    NativeLibraryMarketAvailability.INSTALLED -> {
                        TextButton(
                            onClick = { onRemove(packageEntry.id) },
                            enabled = enabled
                        ) {
                            Text(stringResource(R.string.common_action_remove))
                        }
                    }

                    NativeLibraryMarketAvailability.NOT_INSTALLED -> {
                        TextButton(
                            onClick = { onInstall(packageEntry.id) },
                            enabled = enabled
                        ) {
                            Text(stringResource(R.string.common_action_install))
                        }
                    }

                    NativeLibraryMarketAvailability.BUNDLED -> Unit
                }
            }
        }
    }
}
