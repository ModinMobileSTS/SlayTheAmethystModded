package io.stamethyst.ui.main

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.stamethyst.config.LauncherThemeColor
import io.stamethyst.config.LauncherThemeMode
import io.stamethyst.model.ModItemUi
import io.stamethyst.ui.main.MainScreenViewModel.ModFolder
import io.stamethyst.ui.main.MainScreenViewModel.UiState
import io.stamethyst.ui.theme.LauncherTheme

class ModFolderBenchmarkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LauncherTheme(
                themeMode = LauncherThemeMode.LIGHT,
                themeColor = LauncherThemeColor.COLORLESS
            ) {
                val mods = remember { buildBenchmarkMods() }
                val folders = remember { buildBenchmarkFolders() }
                val assignments = remember(mods) { mods.associate { it.storagePath to BENCHMARK_FOLDER_ID } }
                var collapsed by remember { mutableStateOf(mapOf(BENCHMARK_FOLDER_ID to true)) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    ModFolderSection(
                        uiState = UiState(
                            initializing = false,
                            dependencyMods = emptyList(),
                            optionalMods = mods,
                            controlsEnabled = true,
                            showModFileName = true,
                            modFolders = folders,
                            folderAssignments = assignments,
                            folderCollapsed = collapsed,
                            unassignedCollapsed = true,
                            dependencyFolderCollapsed = true,
                            dragLocked = false,
                            unassignedFolderOrder = 1
                        ),
                        contentBottomInset = 0.dp,
                        hostAvailable = true,
                        callbacks = ModFolderSectionCallbacks(
                            onToggleFolderCollapsed = { folderId ->
                                collapsed = collapsed + (folderId to (collapsed[folderId] != true))
                            },
                            onToggleUnassignedCollapsed = {}
                        )
                    )
                }
            }
        }
    }

    private fun buildBenchmarkFolders(): List<ModFolder> {
        return listOf(ModFolder(id = BENCHMARK_FOLDER_ID, name = "Benchmark Mods"))
    }

    private fun buildBenchmarkMods(): List<ModItemUi> {
        return List(BENCHMARK_MOD_COUNT) { index ->
            ModItemUi(
                modId = "benchmark_mod_$index",
                manifestModId = "benchmark_mod_$index",
                storagePath = "/benchmark/mod-$index.jar",
                name = "Benchmark Mod $index",
                version = "1.$index.0",
                description = "Synthetic benchmark mod with enough text to exercise card composition and text layout.",
                dependencies = emptyList(),
                required = false,
                installed = true,
                enabled = index % 3 != 0,
                explicitPriority = if (index % 5 == 0) index else null,
                effectivePriority = index
            )
        }
    }

    private companion object {
        private const val BENCHMARK_FOLDER_ID = "benchmark-folder"
        private const val BENCHMARK_MOD_COUNT = 160
    }
}
