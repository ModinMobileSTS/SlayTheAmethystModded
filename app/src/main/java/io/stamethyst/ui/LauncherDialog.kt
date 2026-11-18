package io.stamethyst.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Dialog as PlatformDialog

internal class LauncherDialogEntry {
    var visible by mutableStateOf(true)
    var content by mutableStateOf<@Composable () -> Unit>({})
    var properties by mutableStateOf(DialogProperties())
    var locals: CompositionLocalContext? = null
    var onDismissRequest: () -> Unit = {}
}

private val LocalDialogEntries = staticCompositionLocalOf<MutableList<LauncherDialogEntry>?> { null }

/** Keeps windows alive for exit even when an `if (show)` caller leaves composition. */
@Composable
internal fun LauncherDialogHost(content: @Composable () -> Unit) {
    val entries = remember { mutableStateListOf<LauncherDialogEntry>() }
    CompositionLocalProvider(LocalDialogEntries provides entries) {
        content()
        entries.toList().forEach { entry ->
            androidx.compose.runtime.key(entry) {
                AnimatedDialogWindow(entry) { entries.remove(entry) }
            }
        }
    }
}

@Composable
internal fun LauncherDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val entries = LocalDialogEntries.current
    val locals = currentCompositionLocalContext
    val entry = remember { LauncherDialogEntry() }
    SideEffect {
        entry.content = content
        entry.locals = locals
        entry.properties = properties
        entry.onDismissRequest = onDismissRequest
    }
    DisposableEffect(entry, entries) {
        entries?.add(entry)
        onDispose { entry.visible = false }
    }
    // Standalone previews/tests can render without an application host.
    if (entries == null) AnimatedDialogWindow(entry) {}
}

@Composable
private fun AnimatedDialogWindow(entry: LauncherDialogEntry, onExited: () -> Unit) {
    val progress = remember { Animatable(0f) }
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(entry.visible) {
        if (entry.visible) {
            progress.animateTo(1f, tween(260, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)))
        } else {
            exiting = true
            progress.animateTo(0f, tween(160, easing = CubicBezierEasing(.4f, 0f, 1f, 1f)))
            onExited()
        }
    }
    val properties = entry.properties
    PlatformDialog(
        onDismissRequest = { if (entry.visible) entry.onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = false,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        ),
    ) {
        val view = LocalView.current
        // Capture these before restoring caller locals: they belong to this window's Compose
        // owner, not the activity (or parent dialog). In particular, the parent loses window
        // focus when this dialog opens, which would suppress the cursor and selection handles.
        @Suppress("DEPRECATION")
        val windowInputLocals = arrayOf(
            LocalView provides view,
            LocalWindowInfo provides LocalWindowInfo.current,
            LocalFocusManager provides LocalFocusManager.current,
            LocalInputModeManager provides LocalInputModeManager.current,
            LocalTextInputService provides LocalTextInputService.current,
            LocalSoftwareKeyboardController provides LocalSoftwareKeyboardController.current,
            LocalTextToolbar provides LocalTextToolbar.current,
        )
        SideEffect {
            (view.parent as DialogWindowProvider).window.apply {
                setWindowAnimations(0)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
        }
        val density = LocalDensity.current
        val width = LocalConfiguration.current.screenWidthDp.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = .4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (entry.visible && properties.dismissOnClickOutside) entry.onDismissRequest()
                    },
                ))
            Box(
                Modifier
                    .widthIn(max = if (properties.usePlatformDefaultWidth) (width - 48.dp).coerceAtMost(560.dp) else width)
                    .graphicsLayer {
                        val fraction = progress.value
                        alpha = fraction
                        scaleX = (if (exiting) .99f else .98f) + fraction * (if (exiting) .01f else .02f)
                        scaleY = scaleX
                        translationY = with(density) { (if (exiting) 4.dp else 12.dp).toPx() } * (1f - fraction)
                    }
                    .pointerInput(entry.visible) {
                        if (!entry.visible) awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                propagateMinConstraints = true,
            ) {
                entry.locals?.let {
                    CompositionLocalProvider(it) {
                        // Keep caller navigation/theme locals without replacing window input services.
                        CompositionLocalProvider(*windowInputLocals) { entry.content() }
                    }
                }
            }
        }
    }
}
