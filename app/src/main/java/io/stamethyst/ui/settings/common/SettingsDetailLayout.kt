package io.stamethyst.ui.settings.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.Icons
import io.stamethyst.ui.LauncherTopAppBar
import io.stamethyst.ui.icon.ArrowBack
import io.stamethyst.ui.settings.core.SettingsScreenViewModel

/** Search owns scrolling and navigation; standalone detail pages keep their existing chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDetailLayout(
    embedded: Boolean,
    modifier: Modifier = Modifier,
    title: String,
    onGoBack: () -> Unit,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (embedded) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    } else {
        Scaffold(
            topBar = {
                LauncherTopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onGoBack) {
                            Icon(Icons.ArrowBack, stringResource(R.string.common_content_desc_back))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsDetailRouteLayout(
    embedded: Boolean,
    modifier: Modifier = Modifier,
    uiState: SettingsScreenViewModel.UiState,
    spec: SettingsRouteSpec,
    onGoBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        Column(
            modifier = (if (embedded) modifier else Modifier).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
    if (embedded) {
        body()
    } else {
        SettingsRouteScaffold(
            modifier = modifier,
            uiState = uiState,
            spec = spec,
            onGoBack = onGoBack,
        ) {
            item { body() }
        }
    }
}
