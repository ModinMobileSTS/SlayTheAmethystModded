package io.stamethyst.model

import androidx.compose.runtime.Stable

@Stable
data class ModItemUi(
    val modId: String,
    val manifestModId: String,
    val storagePath: String,
    val name: String,
    val version: String,
    val description: String,
    val dependencies: List<String>,
    val required: Boolean,
    val installed: Boolean,
    val enabled: Boolean,
    val explicitPriority: Int?,
    val effectivePriority: Int?,
    val importPatchDetails: String? = null
)
