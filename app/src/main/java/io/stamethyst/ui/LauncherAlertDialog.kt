package io.stamethyst.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Material-styled content with application-drawn motion, never a system window animation.
 *
 * The heading and ordinary text scroll together, leaving actions reachable in short windows and
 * above the IME. Set [scrollableText] to false when [text] owns a bounded scroller (in particular
 * a LazyColumn): measuring that content inside another vertical scroller would give it infinite
 * height. That mode still reserves space for actions before measuring the caller's viewport.
 */
@Composable
internal fun LauncherAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    scrollableText: Boolean = true,
) {
    LauncherDialog(onDismissRequest, properties) {
        Surface(modifier.widthIn(min = 280.dp, max = 560.dp), shape, containerColor,
            tonalElevation = tonalElevation) {
            Column(Modifier.padding(24.dp)) {
                Column(
                    Modifier.weight(1f, fill = false)
                        .then(if (scrollableText) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                ) {
                    icon?.let {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)) { it() }
                        }
                    }
                    title?.let {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                Box(Modifier.align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                                    .padding(bottom = 16.dp)) { it() }
                            }
                        }
                    }
                    text?.let {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Box(
                                    Modifier.then(if (scrollableText) Modifier else Modifier.weight(1f, fill = false))
                                        .padding(bottom = 24.dp),
                                ) { it() }
                            }
                        }
                    }
                }
                val direction = LocalLayoutDirection.current
                CompositionLocalProvider(LocalLayoutDirection provides
                    if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                            confirmButton()
                            dismissButton?.invoke()
                        }
                    }
                }
            }
        }
    }
}
