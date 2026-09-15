package io.stamethyst.ui

enum class UiBusyOperation {
    NONE,
    MOD_IMPORT,
    MOD_NAME_MIGRATION,
    NATIVE_LIBRARY_INSTALL,
    MTS_COMPONENT_UPDATE,
    GAME_PROCESS_CLEANUP,
    GAME_STARTUP_WARMUP,
    STEAM_CLOUD_SYNC,
    EXPORT_ARCHIVE,
    OTHER_BUSY

    ;

    fun usesBlockingOverlay(): Boolean {
        return when (this) {
            MOD_IMPORT,
            MOD_NAME_MIGRATION,
            NATIVE_LIBRARY_INSTALL,
            MTS_COMPONENT_UPDATE,
            GAME_PROCESS_CLEANUP,
            GAME_STARTUP_WARMUP,
            STEAM_CLOUD_SYNC,
            EXPORT_ARCHIVE -> true
            NONE,
            OTHER_BUSY -> false
        }
    }
}
