package io.stamethyst.config

enum class GpuResourceGuardianMode(val persistedValue: String) {
    OFF("off"),
    SAFE("safe"),
    AGGRESSIVE("aggressive"),
    DIAGNOSTIC("diagnostic"),
    LEGACY("legacy");

    val runtimePropertyValue: String
        get() = when (this) {
            LEGACY -> OFF.persistedValue
            else -> persistedValue
        }

    companion object {
        fun fromPersistedValue(value: String?): GpuResourceGuardianMode? {
            if (value.isNullOrBlank()) {
                return null
            }
            val normalized = value.trim()
            return entries.firstOrNull { it.persistedValue == normalized }
        }
    }
}
