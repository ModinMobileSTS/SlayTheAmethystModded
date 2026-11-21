package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import io.stamethyst.R
import io.stamethyst.ui.AppSearchBar

@Composable
internal fun WorkshopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    history: List<String>,
    onHistorySelected: (String) -> Unit,
    onHistoryDeleted: (String) -> Unit,
    inputFieldModifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    var expanded by remember { mutableStateOf(false) }
    // React to keyboard dismissal without recomposing on every animated inset value.
    LaunchedEffect(ime, density, focusManager) {
        var wasKeyboardVisible = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { visible ->
            if (wasKeyboardVisible && !visible && expanded) {
                focusManager.clearFocus(force = true)
                expanded = false
            }
            wasKeyboardVisible = visible
        }
    }

    AppSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = {
            expanded = false
            onSearch(it)
        },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        history = history,
        onHistorySelected = {
            expanded = false
            onHistorySelected(it)
        },
        onHistoryDeleted = onHistoryDeleted,
        placeholder = stringResource(R.string.workshop_search_placeholder),
        inputFieldModifier = inputFieldModifier,
    )
}
