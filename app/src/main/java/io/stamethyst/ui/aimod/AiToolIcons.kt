package io.stamethyst.ui.aimod

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import io.stamethyst.R
import io.stamethyst.ui.Icons
import io.stamethyst.ui.icon.Description
import io.stamethyst.ui.icon.Search

@Composable
internal fun agentToolIcon(name: String): ImageVector = when (name) {
    "read_workspace_file", "read_agent_workspace_file" -> Icons.Description
    "search_agent_api", "inspect_agent_patch_target", "grep_agent_workspace", "glob_agent_workspace" -> Icons.Search
    else -> ImageVector.vectorResource(
        when (name) {
            "list_agent_workspace" -> R.drawable.ic_settings_resources
            "list_agent_patch_mods" -> R.drawable.ic_dock_mods
            "create_agent_patch_workspace" -> R.drawable.ic_folder_add
            "write_agent_workspace_file" -> R.drawable.ic_edit
            "delete_agent_workspace_file", "delete_agent_patch_mod" -> R.drawable.ic_delete
            "decompile_agent_mod_source", "describe_agent_api_class",
            "generate_agent_patch_skeleton" -> R.drawable.ic_code
            "compile_agent_patch_source" -> R.drawable.ic_build
            "package_agent_patch_mod" -> R.drawable.ic_inventory
            "update_agent_patch_mod" -> R.drawable.ic_refresh
            "set_agent_patch_mod_enabled" -> R.drawable.ic_check_circle
            "validate_agent_patch_mod" -> R.drawable.ic_check_circle
            "smoke_test_agent_patch_mod" -> R.drawable.ic_play_arrow
            "read_agent_skill" -> R.drawable.ic_settings_tutorial
            else -> R.drawable.ic_build
        },
    )
}
