package io.stamethyst.config

enum class BackBehavior(val persistedValue: String) {
    EXIT_TO_LAUNCHER("exit_to_launcher"),
    SEND_ESCAPE("send_escape"),
    NONE("none");

    companion object {
        @JvmStatic
        fun fromPersistedValue(value: String?): BackBehavior? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}
