package io.stamethyst.ui

enum class UiBusyOperation {
    NONE,
    MOD_IMPORT,
    NATIVE_LIBRARY_INSTALL,
    GAME_PROCESS_CLEANUP,
    STEAM_CLOUD_SYNC,
    OTHER_BUSY

    ;

    fun usesBlockingOverlay(): Boolean {
        return when (this) {
            MOD_IMPORT,
            NATIVE_LIBRARY_INSTALL,
            GAME_PROCESS_CLEANUP,
            STEAM_CLOUD_SYNC -> true
            NONE,
            OTHER_BUSY -> false
        }
    }
}
