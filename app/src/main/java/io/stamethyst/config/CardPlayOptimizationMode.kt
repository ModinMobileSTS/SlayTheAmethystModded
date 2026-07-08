package io.stamethyst.config

enum class CardPlayOptimizationMode(val persistedValue: String) {
    RELEASE_POP_BACK("release_pop_back"),
    RELEASE_KEEP_OPEN("release_keep_open"),
    TAP_CARD_THEN_TARGET("tap_card_then_target"),
    VANILLA("vanilla");

    val optimizationEnabled: Boolean
        get() = this != VANILLA

    val tapInspectEnabled: Boolean
        get() = this == RELEASE_KEEP_OPEN || this == TAP_CARD_THEN_TARGET

    val tapPlayEnabled: Boolean
        get() = this == TAP_CARD_THEN_TARGET

    companion object {
        fun fromPersistedValue(value: String?): CardPlayOptimizationMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            return entries.firstOrNull { it.persistedValue == value.trim() }
        }
    }
}
