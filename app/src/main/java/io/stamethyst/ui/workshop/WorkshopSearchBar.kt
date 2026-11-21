package io.stamethyst.ui.workshop

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.stamethyst.R
import io.stamethyst.ui.SearchHistorySuggestions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkshopSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    history: List<String>,
    onHistorySelected: (String) -> Unit,
    onHistoryDeleted: (String) -> Unit,
    historyEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val showHistory = historyEnabled && expanded
    val shape = SearchBarDefaults.dockedShape
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    var fieldFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // Observe visibility changes without reading the keyboard's animated height in composition.
    LaunchedEffect(historyEnabled, ime, density, focusManager) {
        if (!historyEnabled) {
            expanded = false
            if (fieldFocused) focusManager.clearFocus()
            return@LaunchedEffect
        }
        var wasVisible = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { visible ->
            if (wasVisible && !visible && fieldFocused) focusManager.clearFocus()
            wasVisible = visible
        }
    }
    fun submitQuery() {
        expanded = false
        onSearch(query)
    }

    // Keep the popup out of the measured glass header. The editable anchor lets the
    // menu follow the available space around the IME without stealing input focus.
    ExposedDropdownMenuBox(
        expanded = showHistory,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        TextField(
            value = query,
            onValueChange = {
                onQueryChange(it)
                if (historyEnabled) expanded = true
            },
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = SearchBarDefaults.InputFieldHeight)
                .shadow(elevation = 2.dp, shape = shape, clip = false)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = false)
                .onFocusChanged {
                    if (fieldFocused != it.isFocused) {
                        fieldFocused = it.isFocused
                        expanded = historyEnabled && it.isFocused
                    }
                },
            placeholder = { Text(stringResource(R.string.workshop_search_placeholder)) },
            trailingIcon = {
                TextButton(onClick = ::submitQuery) {
                    Text(stringResource(R.string.workshop_search_action))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitQuery() }),
            singleLine = true,
            shape = shape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            )
        )
        if (historyEnabled) {
            ExposedDropdownMenu(
                expanded = showHistory,
                onDismissRequest = { expanded = false },
                shape = shape,
                containerColor = containerColor,
            ) {
                SearchHistorySuggestions(
                    history = history,
                    onSelect = {
                        expanded = false
                        onHistorySelected(it)
                    },
                    onDelete = onHistoryDeleted,
                )
            }
        }
    }
}
