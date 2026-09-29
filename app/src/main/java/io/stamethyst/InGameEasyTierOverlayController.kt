package io.stamethyst

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.stamethyst.backend.easytier.EasyTierPermissionCoordinator
import io.stamethyst.backend.workshop.WorkshopItemSummary
import io.stamethyst.config.LauncherConfig
import io.stamethyst.ui.LauncherNavigationRequestBus
import io.stamethyst.ui.main.EasyTierBottomSheetContent
import io.stamethyst.ui.main.MainScreenViewModel
import io.stamethyst.ui.theme.LauncherTheme
import kotlinx.coroutines.delay

internal class InGameEasyTierOverlayController(
    private val activity: StsGameActivity,
    private val viewModel: MainScreenViewModel,
) {
    private var overlayVisible by mutableStateOf(false)
    private var kickDialogVisible = false
    private var composeView: ComposeView? = null

    fun attachToHost(host: FrameLayout) {
        if (composeView?.parent === host) {
            return
        }
        detachView()
        val themeMode = LauncherConfig.readThemeMode(activity)
        val themeColor = LauncherConfig.readThemeColor(activity)
        composeView = ComposeView(activity).apply {
            visibility = View.GONE
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LauncherTheme(
                    themeMode = themeMode,
                    themeColor = themeColor,
                ) {
                    InGameEasyTierDialogHost(
                        visible = overlayVisible,
                        activity = activity,
                        viewModel = viewModel,
                        onDismiss = ::dismiss,
                        onKickDialogVisibilityChanged = { visible ->
                            kickDialogVisible = visible
                            if (visible) {
                                composeView?.visibility = View.VISIBLE
                            } else if (!overlayVisible) {
                                composeView?.visibility = View.GONE
                            }
                        },
                    )
                }
            }
        }.also { view ->
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        viewModel.syncEasyTierUi(activity)
        composeView?.visibility = View.VISIBLE
        overlayVisible = true
    }

    fun dismiss() {
        overlayVisible = false
        composeView?.postDelayed(
            {
                if (!overlayVisible && !kickDialogVisible) {
                    composeView?.visibility = View.GONE
                }
            },
            DISMISS_VIEW_DELAY_MS,
        )
    }

    fun onDestroy() {
        overlayVisible = false
        kickDialogVisible = false
        detachView()
    }

    private fun detachView() {
        val view = composeView ?: return
        composeView = null
        view.disposeComposition()
        (view.parent as? FrameLayout)?.removeView(view)
    }

    private companion object {
        const val DISMISS_VIEW_DELAY_MS = 80L
    }
}

@Composable
private fun InGameEasyTierDialogHost(
    visible: Boolean,
    activity: StsGameActivity,
    viewModel: MainScreenViewModel,
    onDismiss: () -> Unit,
    onKickDialogVisibilityChanged: (Boolean) -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onEasyTierVpnPermissionResult(
            host = activity,
            granted = result.resultCode == Activity.RESULT_OK,
        )
    }

    // Kick/disconnect events arrive as ACTION_CONNECTION_EVENT broadcasts from the :easytier
    // process, so this only needs to register the receiver once instead of polling. The overlay
    // stays attached with visibility=GONE for the whole session, and a GONE-but-attached
    // ComposeView still composes, so a loop here would run for the entire session and read the
    // EasyTier state file (plus a getRunningServices binder call while a session is active)
    // every second for a dialog that is almost never shown.
    LaunchedEffect(viewModel, activity) {
        viewModel.syncEasyTierUi(activity)
    }

    val uiState = viewModel.uiState
    val kickDialog = uiState.pendingEasyTierKickDialog
    SideEffect {
        onKickDialogVisibilityChanged(kickDialog != null)
    }

    kickDialog?.let { dialog ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissEasyTierKickDialog(activity) },
            title = {
                androidx.compose.material3.Text(
                    stringResource(R.string.main_easytier_kicked_dialog_title)
                )
            },
            text = {
                androidx.compose.material3.Text(
                    dialog.message.ifBlank {
                        activity.getString(R.string.main_easytier_summary_session_kicked)
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { viewModel.dismissEasyTierKickDialog(activity) }
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.common_action_confirm))
                }
            },
        )
    }

    if (!visible || kickDialog != null) {
        return
    }

    fun openTutorialWorkshopDetails(item: WorkshopItemSummary) {
        LauncherNavigationRequestBus.requestWorkshopDetail(item)
        onDismiss()
        activity.startActivity(
            Intent(activity, LauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }
    LaunchedEffect(Unit) {
        viewModel.syncEasyTierUi(activity)
        viewModel.refreshEasyTierRooms(activity, forceRoomInfoReload = true)
        while (true) {
            delay(EASY_TIER_ROOM_AUTO_REFRESH_INTERVAL_MS)
            viewModel.refreshEasyTierRooms(
                activity,
                forceRoomInfoReload = true,
                showLoading = false,
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.90f)
                .widthIn(max = 760.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            EasyTierBottomSheetContent(
                indicator = uiState.easyTierIndicator,
                roomBrowser = uiState.easyTierRoomBrowser,
                onRefreshRooms = {
                    viewModel.refreshEasyTierRooms(activity, forceRoomInfoReload = true)
                },
                onSelectRoom = { roomId ->
                    viewModel.selectEasyTierRoom(activity, roomId)
                },
                onCreateRoom = { roomId, description, password, allowNewJoins ->
                    val permissionIntent =
                        EasyTierPermissionCoordinator.prepareVpnPermissionIntent(activity)
                    if (permissionIntent != null) {
                        viewModel.queueEasyTierRoomCreation(roomId, description, password, allowNewJoins)
                        viewModel.onEasyTierVpnPermissionRequired(activity)
                        permissionLauncher.launch(permissionIntent)
                    } else {
                        viewModel.createEasyTierRoom(activity, roomId, description, password, allowNewJoins)
                    }
                },
                onLockRoom = { viewModel.lockEasyTierRoom(activity) },
                onUnlockRoom = { viewModel.unlockEasyTierRoom(activity) },
                onCloseRoom = { viewModel.closeEasyTierRoom(activity) },
                onKickMember = { playerId, message ->
                    viewModel.kickEasyTierRoomMember(activity, playerId, message)
                },
                onConnect = {
                    val permissionIntent =
                        EasyTierPermissionCoordinator.prepareVpnPermissionIntent(activity)
                    if (permissionIntent != null) {
                        viewModel.onEasyTierVpnPermissionRequired(activity)
                        permissionLauncher.launch(permissionIntent)
                    } else {
                        viewModel.onConnectEasyTier(activity)
                    }
                },
                onDisconnect = { viewModel.onDisconnectEasyTier(activity) },
                onOpenTutorialWorkshopDetails = ::openTutorialWorkshopDetails,
                onDownloadTutorialWorkshopItem = ::openTutorialWorkshopDetails,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val EASY_TIER_ROOM_AUTO_REFRESH_INTERVAL_MS = 5_000L
