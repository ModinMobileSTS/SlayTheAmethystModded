package io.stamethyst.ui.settings.core

enum class SettingsLoadDomain {
    CONFIGURATION,
    RENDERER,
    RESOURCES,
    STEAM_ACCOUNT,
    STEAM_SAVES,
    DIAGNOSTICS,
    GAME_MODE,
    EASYTIER,
    TRANSLATION,
}

enum class SettingsLoadState {
    LOADING,
    READY,
    ERROR;

    companion object {
        fun combine(states: Iterable<SettingsLoadState>): SettingsLoadState = when {
            ERROR in states -> ERROR
            LOADING in states -> LOADING
            else -> READY
        }
    }
}
