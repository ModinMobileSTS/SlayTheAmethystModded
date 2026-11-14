package io.stamethyst.config

enum class BootOverlayArtwork(
    val persistedValue: String,
    val startResourcePath: String,
    val endResourcePath: String,
) {
    POSTER("poster", "boot-art/poster_dark.jpg", "boot-art/poster_bright.jpg"),
    ORIGINAL("original", "ui/boot_dark.png", "ui/boot_bright.png");

    companion object {
        fun fromPersistedValue(value: String?): BootOverlayArtwork =
            entries.firstOrNull { it.persistedValue == value } ?: POSTER
    }
}

enum class BootOverlayImageMode(
    val persistedValue: String
) {
    SINGLE("single"),
    DUAL("dual");

    companion object {
        fun fromPersistedValue(value: String?): BootOverlayImageMode? {
            return entries.firstOrNull { it.persistedValue == value }
        }
    }
}

enum class BootOverlayImageSlot(
    val fileName: String
) {
    START("boot_overlay_start.image"),
    END("boot_overlay_end.image")
}

data class BootOverlayImageConfig(
    val mode: BootOverlayImageMode = BootOverlayImageMode.DUAL,
    val startImagePath: String? = null,
    val endImagePath: String? = null,
    val startImageVersion: Long = 0L,
    val endImageVersion: Long = 0L,
    val artwork: BootOverlayArtwork = BootOverlayArtwork.POSTER,
) {
    val hasCustomImages: Boolean
        get() = !startImagePath.isNullOrBlank() || !endImagePath.isNullOrBlank()

    fun startResourcePath(): String = artwork.startResourcePath

    fun endResourcePath(): String = if (mode == BootOverlayImageMode.SINGLE) {
        artwork.startResourcePath
    } else {
        artwork.endResourcePath
    }

    fun imagePathFor(slot: BootOverlayImageSlot): String? {
        return when (slot) {
            BootOverlayImageSlot.START -> startImagePath
            BootOverlayImageSlot.END -> endImagePath
        }
    }

    fun resolvedStartImagePath(): String? {
        return startImagePath
    }

    fun resolvedEndImagePath(): String? {
        return if (mode == BootOverlayImageMode.SINGLE) {
            startImagePath
        } else {
            endImagePath
        }
    }

    fun resolvedStartImageVersion(): Long {
        return startImageVersion
    }

    fun resolvedEndImageVersion(): Long {
        return if (mode == BootOverlayImageMode.SINGLE) {
            startImageVersion
        } else {
            endImageVersion
        }
    }
}
