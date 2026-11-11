package io.stamethyst.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.stamethyst.R

/** Destructive actions require a deliberate menu step, followed by the existing confirmation. */
@Composable
internal fun ModDeleteOverflow(enabled: Boolean, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.main_mod_actions_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ModDeleteMenuItem(enabled) { expanded = false; onDelete() }
        }
    }
}
