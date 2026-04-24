package io.stamethyst.config

enum class SteamCloudSaveMode(val persistedValue: String) {
    INDEPENDENT("independent"),
    STEAM_CLOUD("steam_cloud");

    companion object {
        val DEFAULT: SteamCloudSaveMode = INDEPENDENT

        fun fromPersistedValue(value: String?): SteamCloudSaveMode {
            return entries.firstOrNull { it.persistedValue == value } ?: DEFAULT
        }
    }
}
