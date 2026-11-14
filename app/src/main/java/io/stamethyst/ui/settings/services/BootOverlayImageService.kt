package io.stamethyst.ui.settings.services

import io.stamethyst.ui.settings.baidu.*
import io.stamethyst.ui.settings.common.*
import io.stamethyst.ui.settings.core.*
import io.stamethyst.ui.settings.files.*
import io.stamethyst.ui.settings.first_run.*
import io.stamethyst.ui.settings.mobileglues.*
import io.stamethyst.ui.settings.native_library.*
import io.stamethyst.ui.settings.sections.*
import io.stamethyst.ui.settings.steamcloud.*

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import io.stamethyst.config.BootOverlayImageConfig
import io.stamethyst.config.BootOverlayImageMode
import io.stamethyst.config.BootOverlayImageSlot
import io.stamethyst.config.RuntimePaths
import io.stamethyst.ui.preferences.LauncherPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object BootOverlayImageService {
    private const val COPY_BUFFER_SIZE = 64 * 1024

    fun importImage(
        context: Context,
        slot: BootOverlayImageSlot,
        uri: Uri
    ): BootOverlayImageConfig {
        val targetFile = targetFile(context, slot)
        val parent = targetFile.parentFile
            ?: throw IOException("Failed to resolve loading image directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create loading image directory")
        }
        val tempFile = File.createTempFile("${slot.fileName}.", ".tmp", parent)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    throw IOException("Failed to open selected image")
                }
                FileOutputStream(tempFile, false).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!isDecodableImage(tempFile)) {
                throw IOException("Selected file is not a supported image")
            }
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Failed to replace loading image")
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        val current = LauncherPreferences.readBootOverlayImageConfig(context)
        val importedPath = targetFile.absolutePath
        val importedVersion = System.currentTimeMillis()
        targetFile.setLastModified(importedVersion)
        val next = when (slot) {
            BootOverlayImageSlot.START -> current.copy(
                startImagePath = importedPath,
                startImageVersion = importedVersion,
                endImagePath = if (current.mode == BootOverlayImageMode.SINGLE) {
                    importedPath
                } else {
                    current.endImagePath
                },
                endImageVersion = if (current.mode == BootOverlayImageMode.SINGLE) {
                    importedVersion
                } else {
                    current.endImageVersion
                }
            )
            BootOverlayImageSlot.END -> current.copy(
                mode = BootOverlayImageMode.DUAL,
                endImagePath = importedPath,
                endImageVersion = importedVersion
            )
        }.normalize()
        LauncherPreferences.saveBootOverlayImageConfig(context, next)
        return next
    }

    fun saveMode(context: Context, mode: BootOverlayImageMode): BootOverlayImageConfig {
        val current = LauncherPreferences.readBootOverlayImageConfig(context)
        val next = when (mode) {
            BootOverlayImageMode.SINGLE if current.startImagePath.isNullOrBlank() ->
                current.copy(
                    mode = BootOverlayImageMode.SINGLE,
                    startImagePath = current.endImagePath,
                    startImageVersion = current.endImageVersion,
                    endImagePath = current.endImagePath,
                    endImageVersion = current.endImageVersion
                )
            BootOverlayImageMode.SINGLE -> current.copy(
                mode = BootOverlayImageMode.SINGLE,
                endImagePath = current.startImagePath,
                endImageVersion = current.startImageVersion
            )
            BootOverlayImageMode.DUAL -> current.copy(mode = BootOverlayImageMode.DUAL)
        }.normalize()
        LauncherPreferences.saveBootOverlayImageConfig(context, next)
        return next
    }

    fun reset(context: Context): BootOverlayImageConfig {
        val artwork = LauncherPreferences.readBootOverlayImageConfig(context).artwork
        LauncherPreferences.clearBootOverlayImageConfig(context)
        LauncherPreferences.saveBootOverlayImageConfig(context, BootOverlayImageConfig(artwork = artwork))
        RuntimePaths.bootOverlayImagesDir(context)
            .listFiles()
            ?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        return LauncherPreferences.readBootOverlayImageConfig(context)
    }

    private fun BootOverlayImageConfig.normalize(): BootOverlayImageConfig {
        return if (mode == BootOverlayImageMode.SINGLE) {
            copy(
                mode = BootOverlayImageMode.SINGLE,
                endImagePath = startImagePath,
                endImageVersion = startImageVersion
            )
        } else {
            copy(mode = BootOverlayImageMode.DUAL)
        }
    }

    private fun targetFile(context: Context, slot: BootOverlayImageSlot): File {
        return File(RuntimePaths.bootOverlayImagesDir(context), slot.fileName)
    }

    private fun isDecodableImage(file: File): Boolean {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }
}

