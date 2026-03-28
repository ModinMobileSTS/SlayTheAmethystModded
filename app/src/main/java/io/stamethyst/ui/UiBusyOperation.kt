package io.stamethyst.ui

enum class UiBusyOperation {
    NONE,
    MOD_IMPORT,
    NATIVE_LIBRARY_INSTALL,
    GAME_PROCESS_CLEANUP,
    OTHER_BUSY

    ;

    fun usesBlockingOverlay(): Boolean {
        return when (this) {
            MOD_IMPORT,
            NATIVE_LIBRARY_INSTALL,
            GAME_PROCESS_CLEANUP -> true
            NONE,
            OTHER_BUSY -> false
        }
    }
}
