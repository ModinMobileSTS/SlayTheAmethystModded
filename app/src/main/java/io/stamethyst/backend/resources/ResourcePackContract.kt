package io.stamethyst.backend.resources

import java.io.File
import java.io.IOException
import java.nio.file.Files

internal object ResourcePackContract {
    const val ABI = "arm64-v8a"
    const val SCHEMA_VERSION = 2
    const val INSTALL_MARKER_FILE_NAME = ".resource-pack-installed"
    const val MANIFEST_FILE_NAME = "manifest.properties"
    const val EMBEDDED_ARCHIVE_ASSET_PATH = "resource-pack/resources.zip"
    const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L

    fun requireArchiveBytes(bytes: Long) {
        if (bytes <= 0L) {
            throw IOException("Resource pack archive is missing or empty")
        }
        if (bytes > MAX_ARCHIVE_BYTES) {
            throw IOException("Resource pack archive is too large: $bytes bytes")
        }
    }

    val requiredAssetFiles = listOf(
        "components/jre/version",
        "components/jre/universal.tar.xz",
        "components/lwjgl3/version",
        "components/lwjgl3/lwjgl-glfw-classes.jar",
        "components/log4j_runtime/log4j-api.jar",
        "components/log4j_runtime/log4j-core.jar",
        "components/mods/ModTheSpire.jar",
        "components/mods/BaseMod.jar",
        "components/mods/StSLib.jar",
        "ui/boot_bright.png",
        "ui/boot_dark.png",
        "ui/update_notice.png"
    )

    val runtimeArchiveAlternatives = listOf(
        "components/jre/bin-aarch64.tar.xz",
        "components/jre/bin-arm64.tar.xz"
    )

    val nativeLibraries: Set<String> = linkedSetOf(
        "libEGL_mesa.so",
        "libOSMesa.so",
        "libVkLayer_khronos_timeline_semaphore.so",
        "libcutils.so",
        "libgdx-freetype.so",
        "libgdx.so",
        "libgl4es_114.so",
        "libglapi.so",
        "libglxshim.so",
        "libjnidispatch.so",
        "liblinkerhook.so",
        "libmobileglues.so",
        "libeasytier_android_jni.so",
        "libeasytier_ffi.so",
        "libspirv-cross-c-shared.so",
        "libvulkan_freedreno.so",
        "libzink_dri.so"
    )

    fun collectMissingContent(packRoot: File): List<String> {
        val missing = ArrayList<String>()
        requiredAssetFiles.forEach { assetPath ->
            val file = File(File(packRoot, "assets"), assetPath)
            if (!isUsableFile(file)) {
                missing += "assets/$assetPath"
            }
        }
        if (runtimeArchiveAlternatives.none { assetPath ->
                val file = File(File(packRoot, "assets"), assetPath)
                isUsableFile(file)
            }
        ) {
            missing += "assets/components/jre/{bin-aarch64.tar.xz,bin-arm64.tar.xz}"
        }
        nativeLibraries.forEach { libraryName ->
            val file = File(File(File(packRoot, "lib"), ABI), libraryName)
            if (!isUsableFile(file)) {
                missing += "lib/$ABI/$libraryName"
            }
        }
        return missing
    }

    private fun isUsableFile(file: File): Boolean {
        return file.isFile && file.length() > 0L && !Files.isSymbolicLink(file.toPath())
    }
}
