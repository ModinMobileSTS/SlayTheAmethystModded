package io.stamethyst.config

enum class RichPresencePrefix(val persistedValue: String) {
    GAME("game"),
    DEVICE("device"),
    NONE("none");

    companion object {
        val DEFAULT: RichPresencePrefix = GAME

        fun fromPersistedValue(value: String?): RichPresencePrefix {
            return entries.firstOrNull { it.persistedValue == value } ?: DEFAULT
        }
    }
}

data class RichPresenceDisplayPreferences(
    val prefix: RichPresencePrefix = RichPresencePrefix.DEFAULT,
    val showCharacter: Boolean = true,
    val showFloor: Boolean = true,
    val showAscension: Boolean = false,
    val showAct: Boolean = false,
)
