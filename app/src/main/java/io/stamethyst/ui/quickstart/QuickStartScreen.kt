package io.stamethyst.ui.quickstart

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.stamethyst.ui.settings.SettingsScreenViewModel

@Composable
fun QuickStartScreen(
    viewModel: SettingsScreenViewModel,
    modifier: Modifier = Modifier,
    onImportSuccess: () -> Unit
) {
    val activity = requireNotNull(LocalActivity.current)
    val uiState = viewModel.uiState
    val pickJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.onJarPicked(activity, uri) { success ->
                if (success) {
                    onImportSuccess()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.bind(activity)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "快速开始",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "首次启动需要先导入 desktop-1.0.jar 才能进入主界面。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "请点击下方按钮选择 desktop-1.0.jar。",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (uiState.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    uiState.busyMessage?.let { busyMessage ->
                        Text(text = busyMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        pickJarLauncher.launch(
                            arrayOf("application/java-archive", "application/octet-stream", "*/*")
                        )
                    },
                    enabled = !uiState.busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("上传 desktop-1.0.jar")
                }
            }
        }
    }
}
