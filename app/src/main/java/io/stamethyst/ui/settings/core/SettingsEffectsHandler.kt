package io.stamethyst.ui.settings.core

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.services.*
import io.stamethyst.ui.settings.steamcloud.*

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import io.stamethyst.R
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.navigation.Route
import io.stamethyst.navigation.currentNavigator
import io.stamethyst.ui.modimport.ModImportRequestBus


@Composable
fun SettingsEffectsHandler(
    viewModel: SettingsScreenViewModel,
) {
    val activity = requireNotNull(LocalActivity.current)
    val navigator = currentNavigator
    val shareLogsChooserTitle = stringResource(R.string.settings_share_logs_chooser_title)
    val sharePerformanceLogsChooserTitle =
        stringResource(R.string.settings_share_performance_logs_chooser_title)
    val importJarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onJarPicked(activity, uri)
    }
    val importModsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        ModImportRequestBus.requestImport(uris.orEmpty())
    }
    val importSavesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.onSavesArchivePicked(activity, uri)
    }
    val exportModsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onModsExportPicked(activity, uri)
    }
    val exportSavesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onSavesExportPicked(activity, uri)
    }
    val exportLogsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onLogsExportPicked(activity, uri)
    }
    val exportPerformanceLogsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        viewModel.onPerformanceLogsExportPicked(activity, uri)
    }
    var pendingBootOverlayImageSlot by remember { mutableStateOf<BootOverlayImageSlot?>(null) }
    @Suppress("DEPRECATION")
    val bootOverlayImageCropLauncher = rememberLauncherForActivityResult(com.canhub.cropper.CropImageContract()) { result ->
        val slot = pendingBootOverlayImageSlot ?: return@rememberLauncherForActivityResult
        pendingBootOverlayImageSlot = null
        if (result.isSuccessful) {
            viewModel.onBootOverlayImagePicked(activity, slot, result.uriContent)
        } else {
            val error = result.error ?: return@rememberLauncherForActivityResult
            viewModel.onBootOverlayImageCropFailed(activity, error)
        }
    }
    val bootOverlayImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (pendingBootOverlayImageSlot == null) {
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            pendingBootOverlayImageSlot = null
            return@rememberLauncherForActivityResult
        }
        @Suppress("DEPRECATION")
        val cropContractOptions = com.canhub.cropper.CropImageContractOptions(
            uri = uri,
            cropImageOptions = CropImageOptions(
                guidelines = CropImageView.Guidelines.ON,
                fixAspectRatio = true,
                aspectRatioX = 16,
                aspectRatioY = 9,
                outputRequestWidth = 1920,
                outputRequestHeight = 1080,
                outputRequestSizeOptions = CropImageView.RequestSizeOptions.RESIZE_EXACT,
                outputCompressFormat = Bitmap.CompressFormat.JPEG,
                outputCompressQuality = 95,
                activityTitle = activity.getString(R.string.settings_boot_overlay_custom_image_crop_title)
            )
        )
        bootOverlayImageCropLauncher.launch(cropContractOptions)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsScreenViewModel.Effect.OpenImportJarPicker -> {
                    importJarLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                }

                SettingsScreenViewModel.Effect.OpenImportModsPicker -> {
                    importModsLauncher.launch(
                        arrayOf("application/java-archive", "application/octet-stream", "*/*")
                    )
                }

                SettingsScreenViewModel.Effect.OpenImportSavesPicker -> {
                    importSavesLauncher.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                }


                is SettingsScreenViewModel.Effect.OpenExportModsPicker -> {
                    exportModsLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.OpenExportSavesPicker -> {
                    exportSavesLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.OpenExportLogsPicker -> {
                    exportLogsLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.OpenExportPerformanceLogsPicker -> {
                    exportPerformanceLogsLauncher.launch(effect.fileName)
                }

                is SettingsScreenViewModel.Effect.OpenBootOverlayImagePicker -> {
                    pendingBootOverlayImageSlot = effect.slot
                    bootOverlayImageLauncher.launch(
                        PickVisualMediaRequest(
                            mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly,
                            defaultTab = ActivityResultContracts.PickVisualMedia.DefaultTab.PhotosTab
                        )
                    )
                }

                is SettingsScreenViewModel.Effect.ShareJvmLogsBundle -> {
                    val shareIntent = JvmLogShareService.buildShareIntent(activity, effect.payload)
                    activity.startActivity(
                        Intent.createChooser(shareIntent, shareLogsChooserTitle)
                    )
                }

                is SettingsScreenViewModel.Effect.SharePerformanceLogsBundle -> {
                    val shareIntent = JvmLogShareService.buildShareIntent(activity, effect.payload)
                    activity.startActivity(
                        Intent.createChooser(shareIntent, sharePerformanceLogsChooserTitle)
                    )
                }

                SettingsScreenViewModel.Effect.OpenCompatibility -> {
                    navigator.push(Route.Compatibility)
                }

                SettingsScreenViewModel.Effect.OpenMobileGluesSettings -> {
                    navigator.push(Route.MobileGluesSettings)
                }

                SettingsScreenViewModel.Effect.OpenFeedback -> {
                    navigator.push(Route.Feedback)
                }

                is SettingsScreenViewModel.Effect.ShowDialog -> {
                    androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle(effect.title)
                        .setMessage(effect.message)
                        .setPositiveButton(R.string.common_action_confirm, null)
                        .show()
                }
            }
        }
    }

}
